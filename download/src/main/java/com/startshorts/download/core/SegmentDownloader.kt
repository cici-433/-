package com.startshorts.download.core

import com.startshorts.download.api.DownloadConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 服务端资源探测信息：
 * - totalBytes：总大小（可能为 null）
 * - acceptRanges：是否支持 Range（决定是否允许分片下载/断点续传）
 * - etag/lastModified：用于一致性与缓存校验（当前实现仅做持久化记录）
 */
internal data class RemoteInfo(
    val totalBytes: Long?,
    val acceptRanges: Boolean,
    val etag: String?,
    val lastModified: String?
)

/**
 * 下载执行器（单任务维度）：
 * - 负责 HEAD/Range 探测；
 * - 在满足条件时执行多分片并发下载，并对同一个临时文件做随机写入；
 * - 若探测或执行过程中发现服务端不支持 Range，会自动回退到单连接顺序下载，保证可用性。
 */
internal class SegmentDownloader(
    private val http: okhttp3.OkHttpClient,
    private val config: DownloadConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * 执行下载并返回 [RemoteInfo]：
     * - onRemoteInfo：探测到远端信息后回调（用于落库）
     * - onSegments：分片计划回调（用于落库；分片回退到单连接时会回调 emptyList）
     * - onSegmentProgress：分片级进度回调（用于断点续传）
     * - onProgress：任务级聚合进度回调（用于 UI/速率统计）
     *
     * 关键逻辑：
     * 1) 先探测远端（HEAD 优先，失败则 Range=0-0 回退探测），拿到 totalBytes / acceptRanges / etag 等信息
     * 2) 判断是否满足分片条件（支持 Range + 有明确 totalBytes + 文件足够大）
     * 3) 满足则：
     *    - 预分配临时文件大小（RandomAccessFile.setLength）
     *    - 生成/复用分片计划（start/end）
     *    - 多协程并发 Range 下载，每个分片随机写入同一个临时文件
     * 4) 任意分片下载过程中如果发现服务端未返回 206（忽略 Range），抛出 RANGE_NOT_SUPPORTED
     *    由外层 catch 触发整体回退：清理临时文件 + 清空分片计划 + 改为顺序下载从 0 开始
     */
    suspend fun download(
        task: TaskRecord,
        onRemoteInfo: suspend (RemoteInfo) -> Unit,
        onSegments: suspend (List<SegmentRecord>) -> Unit,
        onSegmentProgress: suspend (start: Long, end: Long, downloadedBytes: Long) -> Unit,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): RemoteInfo {
        return coroutineScope {
            val remote = probe(task)
            onRemoteInfo(remote)

            val tmpFile = File(task.tmpPath)
            tmpFile.parentFile?.mkdirs()

            val total = remote.totalBytes
            val canSegment = remote.acceptRanges && total != null && total >= config.segmentMinBytes * 2

            if (!canSegment) {
                /**
                 * 不满足分片条件则顺序下载：
                 * - totalBytes 为空或文件过小不分片；
                 * - acceptRanges=false 表示服务端不支持 Range，直接顺序下载。
                 *
                 * 断点续传：
                 * - 若本地临时文件存在且 acceptRanges=true，会带 Range=already- 尝试续传；
                 * - 服务端若忽略 Range 返回 200，会自动从 0 重下，避免文件拼接损坏。
                 */
                downloadSingle(task, remote, onProgress)
                return@coroutineScope remote
            }

            /**
             * 分片下载路径下：
             * - 预分配临时文件到 totalBytes，保证 RandomAccessFile 随机写入时不会触发扩容抖动；
             * - 预分配失败会抛异常，外层 catch 会触发回退到顺序下载。
             */
            preallocate(tmpFile, total!!)

            val segments = ensureSegments(task, total, config.maxSegmentsPerTask).also { onSegments(it) }
            val downloaded = AtomicLong(segments.sumOf { it.downloadedBytes })
            onProgress(downloaded.get(), total)

            try {
                val jobs = segments.map { seg ->
                    async(ioDispatcher) {
                        downloadSegment(task, seg, downloaded, total, onSegmentProgress, onProgress)
                    }
                }
                jobs.awaitAll()
            } catch (t: Throwable) {
                /**
                 * 分片下载失败回退：
                 * - 常见原因：部分 CDN/网关在特定条件下忽略 Range，导致 200 而非 206；
                 * - 回退策略：清空临时文件与分片计划，改为顺序下载从 0 开始。
                 *
                 * 为什么要“从 0 开始”：
                 * - 分片写入可能已在临时文件中留下零散数据；
                 * - 如果继续续传，容易出现“洞/重复/错位”导致 hash 或安装失败；
                 * - 以可用性优先，先保证一定能下完，再做更精细的恢复优化。
                 */
                onSegments(emptyList())
                tmpFile.delete()
                downloadSingle(task.copy(downloadedBytes = 0L, segments = emptyList()), remote, onProgress)
            }

            remote
        }
    }

    private suspend fun probe(task: TaskRecord): RemoteInfo = withContext(ioDispatcher) {
        /**
         * 优先 HEAD 探测：
         * - 不少服务端会返回 Content-Length / Accept-Ranges / ETag / Last-Modified；
         * - 若 HEAD 不支持/失败，回退到 Range GET (0-0) 解析 Content-Range。
         *
         * 为什么需要 Range=0-0 回退探测：
         * - 有些服务端禁用 HEAD 或对 HEAD 不返回 Content-Length；
         * - Range=0-0 在支持 Range 的情况下会返回 206 + Content-Range: bytes 0-0/total，
         *   可以可靠解析 total；
         * - 即便不支持 Range，依然可能返回 200，此时 acceptRanges=false，后续走顺序下载。
         */
        val headReq = Request.Builder().url(task.url).head().apply {
            task.headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()

        try {
            http.newCall(headReq).execute().use { resp ->
                val total = resp.header("Content-Length")?.toLongOrNull()
                val acceptRanges = resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                if (resp.isSuccessful) {
                    return@withContext RemoteInfo(
                        totalBytes = total,
                        acceptRanges = acceptRanges,
                        etag = resp.header("ETag"),
                        lastModified = resp.header("Last-Modified")
                    )
                }
            }
        } catch (_: Throwable) {
        }

        val rangeReq = Request.Builder().url(task.url).get().apply {
            task.headers.forEach { (k, v) -> addHeader(k, v) }
            // 下载这个资源的第0字节到第0字节，解析 totalBytes
            addHeader("Range", "bytes=0-0")
        }.build()

        http.newCall(rangeReq).execute().use { resp ->
            val contentRange = resp.header("Content-Range")
            val total = parseTotalBytesFromContentRange(contentRange) ?: resp.header("Content-Length")?.toLongOrNull()
            val acceptRanges = resp.code == 206 || (resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true)
            RemoteInfo(
                totalBytes = total,
                acceptRanges = acceptRanges,
                etag = resp.header("ETag"),
                lastModified = resp.header("Last-Modified")
            )
        }
    }

    private suspend fun downloadSingle(
        task: TaskRecord,
        remote: RemoteInfo,
        onProgress: suspend (Long, Long?) -> Unit
    ) = withContext(ioDispatcher) {
        /**
         * 顺序下载（支持断点续传）：
         * - 若本地已有临时文件且服务端支持 Range，则请求 Range=already-；
         * - 若服务端忽略 Range 返回 200，则强制从 0 重下，避免文件拼接损坏。
         *
         * 关键点：
         * - RandomAccessFile 写入：
         *   - shouldResume=true 时 seek 到 already 位置继续写入；
         *   - shouldResume=false 时 setLength(0) 清空文件，从头写入。
         * - 循环内通过 ensureActive 响应取消（pause/cancel 会 cancel 协程）。
         */
        val tmpFile = File(task.tmpPath)
        val already = task.downloadedBytes
        val canResume = remote.acceptRanges && already > 0L && tmpFile.exists()

        val req = Request.Builder().url(task.url).get().apply {
            task.headers.forEach { (k, v) -> addHeader(k, v) }
            if (canResume) addHeader("Range", "bytes=$already-")
        }.build()

        executeWithRetry(config.maxTaskRetries, config.baseRetryDelayMs, config.maxRetryDelayMs) {
            http.newCall(req).execute().use { resp ->
                ensureSuccess(resp)
                val body = resp.body ?: error("empty body")
                tmpFile.parentFile?.mkdirs()
                RandomAccessFile(tmpFile, "rw").use { raf ->
                    val shouldResume = canResume && resp.code == 206
                    if (shouldResume) raf.seek(already) else raf.setLength(0L)
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = if (shouldResume) already else 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            raf.write(buf, 0, read)
                            downloaded += read
                            onProgress(downloaded, remote.totalBytes)
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadSegment(
        task: TaskRecord,
        seg: SegmentRecord,
        downloaded: AtomicLong,
        total: Long,
        onSegmentProgress: suspend (start: Long, end: Long, downloadedBytes: Long) -> Unit,
        onProgress: suspend (Long, Long?) -> Unit
    ) {
        /**
         * 分片下载：
         * - 每个分片独立重试，降低单分片波动对整体的影响；
         * - 写入通过 RandomAccessFile.seek 实现随机写入同一临时文件；
         * - 服务端未返回 206 时认为不支持 Range，抛出异常触发外层回退。
         *
         * 关键点：
         * - cursor 表示“当前分片已经写到哪里”，初始为 start + downloadedBytes（用于断点续传）
         * - onSegmentProgress 上报的是“分片内部已下载字节数”（相对 start），用于持久化与恢复
         * - onProgress 上报的是“任务聚合已下载字节数”，用于 UI 展示与速率统计
         * - 为避免分片级进度过于频繁导致落库/回调压力，采用 256KB 阈值节流分片进度上报
         */
        var attempt = 0
        var cursor = seg.start + seg.downloadedBytes
        if (cursor > seg.end) return
        onSegmentProgress(seg.start, seg.end, cursor - seg.start)

        while (true) {
            try {
                executeRangeOnce(task, cursor, seg.end).use { resp ->
                    if (resp.code != 206) error("RANGE_NOT_SUPPORTED")
                    val body = resp.body ?: error("empty body")
                    val tmpFile = File(task.tmpPath)
                    RandomAccessFile(tmpFile, "rw").use { raf ->
                        raf.seek(cursor)
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var deltaSinceLastReport = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buf)
                                if (read <= 0) break
                                raf.write(buf, 0, read)
                                cursor += read
                                val now = downloaded.addAndGet(read.toLong())
                                deltaSinceLastReport += read.toLong()
                                if (deltaSinceLastReport >= 256 * 1024) {
                                    onSegmentProgress(seg.start, seg.end, cursor - seg.start)
                                    deltaSinceLastReport = 0L
                                }
                                onProgress(now, total)
                            }
                        }
                    }
                }
                onSegmentProgress(seg.start, seg.end, seg.end - seg.start + 1)
                return
            } catch (t: Throwable) {
                attempt++
                if (attempt > config.maxSegmentRetries) throw t
                val backoff = computeBackoffMs(attempt, config.baseRetryDelayMs, config.maxRetryDelayMs)
                delay(backoff)
            }
        }
    }

    private suspend fun executeRangeOnce(task: TaskRecord, start: Long, end: Long): Response {
        /**
         * 执行一次 Range 请求：
         * - 由 downloadSegment 负责校验返回码必须为 206
         * - 这里返回 Response，由调用方 use { } 关闭，避免连接泄漏
         */
        return withContext(ioDispatcher) {
            val req = Request.Builder().url(task.url).get().apply {
                task.headers.forEach { (k, v) -> addHeader(k, v) }
                addHeader("Range", "bytes=$start-$end")
            }.build()
            http.newCall(req).execute()
        }
    }

    private fun ensureSegments(task: TaskRecord, totalBytes: Long, maxSegments: Int): List<SegmentRecord> {
        /**
         * 分片计划生成：
         * - 若任务已有 segments（来自 TaskStore 恢复），直接复用，保证断点续传一致
         * - 否则根据 totalBytes 与 segmentMinBytes 估算分片数，并限制在 [2, maxSegments] 区间
         *
         * 分片区间定义：
         * - start/end 为闭区间（end-start+1 即分片总大小）
         */
        if (task.segments.isNotEmpty()) return task.segments
        val count = min(maxSegments, max(2, (totalBytes / config.segmentMinBytes).toInt()))
        val segSize = totalBytes / count
        val segments = mutableListOf<SegmentRecord>()
        var cur = 0L
        for (i in 0 until count) {
            val start = cur
            val end = if (i == count - 1) totalBytes - 1 else (cur + segSize - 1)
            segments.add(
                SegmentRecord(
                    start = start,
                    end = end,
                    downloadedBytes = 0L,
                    status = com.startshorts.download.api.TaskStatus.QUEUED,
                    retryCount = 0
                )
            )
            cur = end + 1
        }
        return segments
    }

    private fun preallocate(file: File, totalBytes: Long) {
        /**
         * 预分配临时文件：
         * - setLength(totalBytes) 会直接把文件长度扩到目标大小
         * - 适用于分片随机写入，避免写入过程频繁扩容造成的性能波动
         */
        if (!file.exists()) file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() != totalBytes) raf.setLength(totalBytes)
        }
    }

    private suspend fun executeWithRetry(
        maxRetries: Int,
        baseDelayMs: Long,
        maxDelayMs: Long,
        block: suspend () -> Unit
    ) {
        /**
         * 通用重试包装（指数退避）：
         * - 用于顺序下载等“任务级请求”
         * - attempt 从 1 开始指数增长 delay，并封顶到 maxDelayMs
         */
        var attempt = 0
        while (true) {
            try {
                block()
                return
            } catch (t: Throwable) {
                attempt++
                if (attempt > maxRetries) throw t
                delay(computeBackoffMs(attempt, baseDelayMs, maxDelayMs))
            }
        }
    }

    private fun computeBackoffMs(attempt: Int, baseDelayMs: Long, maxDelayMs: Long): Long {
        /**
         * 指数退避：
         * - delay = baseDelayMs * 2^attempt
         * - attempt 过大时移位会溢出，因此对 attempt 做上限保护
         */
        val factor = 1 shl min(16, attempt)
        val raw = baseDelayMs * factor.toLong()
        return min(maxDelayMs, raw)
    }

    private fun ensureSuccess(resp: Response) {
        /**
         * 统一 HTTP 成功判定：
         * - 顺序下载依赖 resp.isSuccessful
         * - Range 下载单独要求 206，在 downloadSegment 内校验
         */
        if (!resp.isSuccessful) error("http ${resp.code}")
    }

    private fun parseTotalBytesFromContentRange(contentRange: String?): Long? {
        /**
         * 解析 Content-Range 的 total：
         * - 形如：bytes 0-0/123456
         * - 返回 "/" 之后的 totalBytes
         */
        if (contentRange.isNullOrBlank()) return null
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0 || slash >= contentRange.length - 1) return null
        return contentRange.substring(slash + 1).toLongOrNull()
    }
}
