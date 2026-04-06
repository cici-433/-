package com.startshorts.download.core

import com.startshorts.download.api.DownloadPriority
import com.startshorts.download.api.NetworkPolicy
import com.startshorts.download.api.TaskStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分片持久化记录：
 * - start/end：分片字节范围（闭区间）
 * - downloadedBytes：已下载并写入的字节数（相对 start 的偏移）
 * - status：分片状态（用于恢复与诊断）
 * - retryCount：分片级重试次数（用于治理与熔断）
 */
internal data class SegmentRecord(
    val start: Long,
    val end: Long,
    val downloadedBytes: Long,
    val status: TaskStatus,
    val retryCount: Int
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("start", start)
            .put("end", end)
            .put("downloadedBytes", downloadedBytes)
            .put("status", status.name)
            .put("retryCount", retryCount)
    }

    companion object {
        fun fromJson(json: JSONObject): SegmentRecord {
            return SegmentRecord(
                start = json.getLong("start"),
                end = json.getLong("end"),
                downloadedBytes = json.optLong("downloadedBytes", 0L),
                status = TaskStatus.valueOf(json.getString("status")),
                retryCount = json.optInt("retryCount", 0)
            )
        }
    }
}

/**
 * 任务持久化记录（内部模型）：
 * - destPath/tmpPath：最终文件与临时文件路径
 * - etag/lastModified：远端一致性字段（当前用于记录，便于后续增强恢复策略）
 * - segments：分片计划与进度（空表示顺序下载或尚未探测/规划）
 *
 * 注意：
 * - networkPolicy 用 simpleName 序列化，仅用于当前枚举的最小兼容；
 * - 如果未来 NetworkPolicy 增加字段/类型，请同步升级序列化策略并考虑兼容迁移。
 */
internal data class TaskRecord(
    val taskId: String,
    val url: String,
    val headers: Map<String, String>,
    val destPath: String,
    val tmpPath: String,
    val checksumSha256: String?,
    val priority: DownloadPriority,
    val networkPolicy: NetworkPolicy,
    val status: TaskStatus,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val etag: String?,
    val lastModified: String?,
    val errorReason: String?,
    val retryCount: Int,
    val segments: List<SegmentRecord>,
    val createTimeMs: Long,
    val updateTimeMs: Long
) {
    fun toJson(): JSONObject {
        val headersJson = JSONObject()
        headers.forEach { (k, v) -> headersJson.put(k, v) }

        val segmentsJson = JSONArray()
        segments.forEach { segmentsJson.put(it.toJson()) }

        return JSONObject()
            .put("taskId", taskId)
            .put("url", url)
            .put("headers", headersJson)
            .put("destPath", destPath)
            .put("tmpPath", tmpPath)
            .put("checksumSha256", checksumSha256)
            .put("priority", priority.name)
            .put("networkPolicy", networkPolicy.javaClass.simpleName)
            .put("status", status.name)
            .put("downloadedBytes", downloadedBytes)
            .put("totalBytes", totalBytes)
            .put("etag", etag)
            .put("lastModified", lastModified)
            .put("errorReason", errorReason)
            .put("retryCount", retryCount)
            .put("segments", segmentsJson)
            .put("createTimeMs", createTimeMs)
            .put("updateTimeMs", updateTimeMs)
    }

    companion object {
        fun fromJson(json: JSONObject): TaskRecord {
            val headersJson = json.optJSONObject("headers") ?: JSONObject()
            val headers = mutableMapOf<String, String>()
            headersJson.keys().forEach { k -> headers[k] = headersJson.optString(k) }

            val segmentsArray = json.optJSONArray("segments") ?: JSONArray()
            val segments = buildList {
                for (i in 0 until segmentsArray.length()) {
                    add(SegmentRecord.fromJson(segmentsArray.getJSONObject(i)))
                }
            }

            val totalBytes = if (json.isNull("totalBytes")) null else json.optLong("totalBytes")

            return TaskRecord(
                taskId = json.getString("taskId"),
                url = json.getString("url"),
                headers = headers,
                destPath = json.getString("destPath"),
                tmpPath = json.getString("tmpPath"),
                checksumSha256 = json.optString("checksumSha256").takeIf { it.isNotBlank() },
                priority = DownloadPriority.valueOf(json.optString("priority", DownloadPriority.NORMAL.name)),
                networkPolicy = parseNetworkPolicy(json.optString("networkPolicy", NetworkPolicy.Any.javaClass.simpleName)),
                status = TaskStatus.valueOf(json.getString("status")),
                downloadedBytes = json.optLong("downloadedBytes", 0L),
                totalBytes = totalBytes,
                etag = json.optString("etag").takeIf { it.isNotBlank() },
                lastModified = json.optString("lastModified").takeIf { it.isNotBlank() },
                errorReason = json.optString("errorReason").takeIf { it.isNotBlank() },
                retryCount = json.optInt("retryCount", 0),
                segments = segments,
                createTimeMs = json.optLong("createTimeMs", System.currentTimeMillis()),
                updateTimeMs = json.optLong("updateTimeMs", System.currentTimeMillis())
            )
        }

        private fun parseNetworkPolicy(name: String): NetworkPolicy {
            return when (name) {
                NetworkPolicy.WifiOnly.javaClass.simpleName -> NetworkPolicy.WifiOnly
                NetworkPolicy.UnmeteredOnly.javaClass.simpleName -> NetworkPolicy.UnmeteredOnly
                else -> NetworkPolicy.Any
            }
        }
    }
}
