package com.startshorts.download.core

import android.content.Context
import com.startshorts.download.api.DownloadClient
import com.startshorts.download.api.DownloadConfig
import com.startshorts.download.api.DownloadEventListener
import com.startshorts.download.api.DownloadProgress
import com.startshorts.download.api.DownloadRequest
import com.startshorts.download.api.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * 下载框架默认实现：
 * - 采用“调度器 + 执行器 + 持久化存储”最小闭环。
 * - 任务状态变更统一写入 [TaskStore]，用于断点续传与进程重启恢复。
 *
 * 一致性约定（与设计文档一致）：
 * - COMPLETED 必须满足：下载完成 + 可选校验通过 + rename 成功；
 * - 中途任意失败/取消，都不会破坏可恢复性（临时文件 + store 记录可继续）。
 */
internal class DefaultDownloadClient(
    private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val config: DownloadConfig,
    private val eventListener: DownloadEventListener?
) : DownloadClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val progressFlow = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 256)
    private val rateTracker = RateTracker()

    private val storeDir = File(appContext.filesDir, "download").apply { mkdirs() }
    private val taskStore = TaskStore(File(storeDir, "store").apply { mkdirs() })
    private val scheduler = DownloadScheduler(
        scope = scope,
        maxParallelTasks = max(1, config.maxParallelTasks),
        runner = ::runTask
    )

    init {
        scheduler.start()
        /**
         * 进程重启恢复：
         * - 处于 QUEUED/DOWNLOADING 的任务统一回到 QUEUED，重新进入调度；
         * - PAUSED/FAILED/CANCELED/COMPLETED 不自动恢复，由业务侧控制。
         */
        scope.launch(Dispatchers.IO) {
            taskStore.list()
                .filter { it.status == TaskStatus.QUEUED || it.status == TaskStatus.DOWNLOADING }
                .forEach { record ->
                    taskStore.updateStatus(record.taskId, TaskStatus.QUEUED, null)
                    scheduler.submit(record.taskId, record.priority)
                }
        }
    }

    override suspend fun enqueue(request: DownloadRequest): String {
        /**
         * 入队即落库：
         * - 先写 TaskStore，再提交给调度器，保证“任务可恢复”优先级高于“立即开跑”。
         */
        val taskId = UUID.randomUUID().toString()
        val destFile = request.destFile
        destFile.parentFile?.mkdirs()

        val tmpFile = File(destFile.parentFile, destFile.name + ".download")

        val task = TaskRecord(
            taskId = taskId,
            url = request.url,
            headers = request.headers,
            destPath = destFile.absolutePath,
            tmpPath = tmpFile.absolutePath,
            checksumSha256 = request.checksumSha256,
            priority = request.priority,
            networkPolicy = request.networkPolicy,
            status = TaskStatus.QUEUED,
            downloadedBytes = if (tmpFile.exists()) tmpFile.length() else 0L,
            totalBytes = null,
            etag = null,
            lastModified = null,
            errorReason = null,
            retryCount = 0,
            segments = emptyList(),
            createTimeMs = System.currentTimeMillis(),
            updateTimeMs = System.currentTimeMillis()
        )

        taskStore.upsert(task)
        scheduler.submit(taskId, request.priority)
        eventListener?.onTaskQueued(taskId)
        emitProgress(taskId)
        return taskId
    }

    override suspend fun pause(taskId: String, reason: String?) {
        /**
         * pause 需要同时处理：
         * - 调度队列（未运行任务移出队列）
         * - 执行中任务（取消协程）
         * - 状态落库（PAUSED）
         */
        scheduler.unschedule(taskId)
        scheduler.cancelRunning(taskId)
        taskStore.updateStatus(taskId, TaskStatus.PAUSED, reason)
        eventListener?.onTaskPaused(taskId, reason)
        emitProgress(taskId)
    }

    override suspend fun resume(taskId: String) {
        /**
         * resume 会把任务重新放回 QUEUED：
         * - 下载器会根据临时文件大小与分片进度继续写入；
         * - 若任务已完成/取消，恢复操作会被忽略。
         */
        val task = taskStore.get(taskId) ?: return
        if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CANCELED) return
        taskStore.updateStatus(taskId, TaskStatus.QUEUED, null)
        scheduler.submit(taskId, task.priority)
        eventListener?.onTaskQueued(taskId)
        emitProgress(taskId)
    }

    override suspend fun cancel(taskId: String, deleteFile: Boolean) {
        /**
         * cancel 的语义是“终止任务并从系统中移除”：
         * - 默认会删除临时文件与目标文件（可由 deleteFile 控制）；
         * - 最终会删除 TaskStore 中的记录，避免无限增长。
         */
        scheduler.unschedule(taskId)
        scheduler.cancelRunning(taskId)
        taskStore.updateStatus(taskId, TaskStatus.CANCELED, null)
        if (deleteFile) {
            taskStore.get(taskId)?.let { record ->
                File(record.tmpPath).delete()
                File(record.destPath).delete()
            }
        }
        taskStore.delete(taskId)
        eventListener?.onTaskCanceled(taskId)
        progressFlow.tryEmit(
            DownloadProgress(
                taskId = taskId,
                status = TaskStatus.CANCELED,
                downloadedBytes = 0L,
                totalBytes = null,
                bytesPerSecond = 0L
            )
        )
    }

    override fun observe(taskId: String): Flow<DownloadProgress> {
        /**
         * 订阅策略：
         * - progressFlow 是“全任务共享流”，通过 taskId 进行过滤；
         * - 订阅开始先发射一次当前快照，避免 UI 等待首个增量事件。
         */
        return progressFlow
            .filter { it.taskId == taskId }
            .onStart { emitProgress(taskId) }
    }

    private suspend fun runTask(taskId: String) {
        /**
         * 单任务执行流程：
         * 1) 网络策略判断（不满足则 PAUSED）
         * 2) 下载执行（顺序下载或 Range 分片下载）
         * 3) 完成校验（长度 + 可选 SHA-256）
         * 4) 原子落盘（rename 临时文件 -> 目标文件）
         */
        val initial = taskStore.get(taskId) ?: return
        if (!NetworkPolicyChecker.isAllowed(appContext, initial.networkPolicy)) {
            taskStore.updateStatus(taskId, TaskStatus.PAUSED, "NETWORK_POLICY")
            eventListener?.onTaskPaused(taskId, "NETWORK_POLICY")
            emitProgress(taskId)
            return
        }

        taskStore.update(taskId) { old ->
            if (old.segments.isEmpty()) return@update old

            val backoffBytes = 4L * 1024L
            var changed = false
            val updatedSegments = old.segments.map { seg ->
                if (seg.status == TaskStatus.COMPLETED || seg.downloadedBytes <= 0L) return@map seg
                val newDownloaded = max(0L, seg.downloadedBytes - backoffBytes)
                if (newDownloaded == seg.downloadedBytes) return@map seg
                changed = true
                seg.copy(
                    downloadedBytes = newDownloaded,
                    status = TaskStatus.QUEUED
                )
            }

            if (!changed) return@update old
            old.copy(
                segments = updatedSegments,
                downloadedBytes = updatedSegments.sumOf { it.downloadedBytes },
                updateTimeMs = System.currentTimeMillis()
            )
        }

        taskStore.updateStatus(taskId, TaskStatus.DOWNLOADING, null)
        eventListener?.onTaskStarted(taskId)
        emitProgress(taskId)

        val downloader = SegmentDownloader(okHttpClient, config)

        try {
            val remote = downloader.download(
                task = taskStore.get(taskId) ?: return,
                onRemoteInfo = { info ->
                    taskStore.update(taskId) {
                        it.copy(
                            totalBytes = info.totalBytes,
                            etag = info.etag,
                            lastModified = info.lastModified,
                            updateTimeMs = System.currentTimeMillis()
                        )
                    }
                },
                onSegments = { segments ->
                    taskStore.update(taskId) {
                        if (it.segments.isNotEmpty()) it else it.copy(segments = segments, updateTimeMs = System.currentTimeMillis())
                    }
                },
                onSegmentProgress = { start, end, downloadedBytes ->
                    taskStore.update(taskId) { old ->
                        val updatedSegments = old.segments.map { seg ->
                            if (seg.start == start && seg.end == end) {
                                val st = if (downloadedBytes >= (end - start + 1)) TaskStatus.COMPLETED else TaskStatus.DOWNLOADING
                                seg.copy(downloadedBytes = downloadedBytes, status = st)
                            } else {
                                seg
                            }
                        }
                        val totalDownloaded = if (updatedSegments.isNotEmpty()) updatedSegments.sumOf { it.downloadedBytes } else old.downloadedBytes
                        old.copy(
                            segments = updatedSegments,
                            downloadedBytes = totalDownloaded,
                            updateTimeMs = System.currentTimeMillis()
                        )
                    }
                },
                onProgress = { downloadedBytes, totalBytes ->
                    taskStore.update(taskId) { old ->
                        old.copy(
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes ?: old.totalBytes,
                            updateTimeMs = System.currentTimeMillis()
                        )
                    }
                    emitProgress(taskId)
                }
            )

            val finished = taskStore.get(taskId) ?: return
            val tmpFile = File(finished.tmpPath)
            val destFile = File(finished.destPath)

            if (remote.totalBytes != null && tmpFile.length() != remote.totalBytes) {
                error("SIZE_MISMATCH")
            }

            finished.checksumSha256?.let { expected ->
                val actual = FileVerifier.sha256Hex(tmpFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    error("CHECKSUM_MISMATCH")
                }
            }

            destFile.parentFile?.mkdirs()
            if (destFile.exists()) destFile.delete()
            val renamed = tmpFile.renameTo(destFile)
            if (!renamed) error("RENAME_FAILED")

            taskStore.updateStatus(taskId, TaskStatus.COMPLETED, null)
            eventListener?.onTaskSucceeded(taskId)
            emitProgress(taskId)
        } catch (ce: CancellationException) {
            emitProgress(taskId)
        } catch (t: Throwable) {
            taskStore.updateStatus(taskId, TaskStatus.FAILED, t.message ?: "UNKNOWN")
            eventListener?.onTaskFailed(taskId, t.message ?: "UNKNOWN")
            emitProgress(taskId)
        }
    }

    private suspend fun emitProgress(taskId: String) {
        /**
         * progress 的 bytesPerSecond 使用轻量采样估算：
         * - 适合 UI 展示与粗粒度自适应；
         * - 更严格的速率统计可由上层埋点系统完成。
         */
        val task = taskStore.get(taskId) ?: return
        val bps = rateTracker.compute(taskId, task.downloadedBytes)
        progressFlow.emit(
            DownloadProgress(
                taskId = taskId,
                status = task.status,
                downloadedBytes = task.downloadedBytes,
                totalBytes = task.totalBytes,
                bytesPerSecond = bps
            )
        )
    }
    
    /**
     * 速率跟踪器：
     * - 给每个下载任务计算一个“瞬时下载速度（bytesPerSecond）”，用于进度回调里的
     */
    private class RateTracker {
        private data class Sample(val bytes: Long, val timeMs: Long)

        private val mutex = Mutex()
        private val samples = mutableMapOf<String, Sample>()

        suspend fun compute(taskId: String, downloadedBytes: Long): Long {
            val now = System.currentTimeMillis()
            return mutex.withLock {
                val prev = samples[taskId]
                samples[taskId] = Sample(downloadedBytes, now)
                if (prev == null) return@withLock 0L
                val dt = max(1L, now - prev.timeMs)
                val db = max(0L, downloadedBytes - prev.bytes)
                (db * 1000L) / dt
            }
        }
    }
}
