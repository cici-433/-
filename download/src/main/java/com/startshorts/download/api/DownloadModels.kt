package com.startshorts.download.api

import java.io.File

/**
 * 任务优先级：
 * - 调度器优先选择更高优先级任务。
 * - 同优先级按入队时间先来先服务，避免“饥饿”。
 */
enum class DownloadPriority(val value: Int) {
    HIGH(3),
    NORMAL(2),
    LOW(1)
}

/**
 * 网络策略（轻量级约束）：
 * - 该策略仅用于“是否允许启动/继续下载”的判断；
 * - 具体权限、系统网络能力、厂商 ROM 行为差异都需要调用方兜底。
 */
sealed class NetworkPolicy {
    data object Any : NetworkPolicy()
    data object WifiOnly : NetworkPolicy()
    data object UnmeteredOnly : NetworkPolicy()
}

/**
 * 下载任务状态机（最小集合）：
 * - QUEUED：已入队，等待调度
 * - DOWNLOADING：执行中（可能为单连接或多分片）
 * - PAUSED：被暂停（手动或策略阻断）
 * - COMPLETED：已完成（校验通过 + rename 成功）
 * - FAILED：失败（可重试或待人工处理）
 * - CANCELED：已取消（可选清理文件）
 */
enum class TaskStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED
}

/**
 * 入队请求：
 * - url：下载地址
 * - destFile：目标文件（最终落盘路径）
 * - headers：业务自定义 header（鉴权/灰度/Range 相关 header 建议在此传入）
 * - checksumSha256：可选，最终文件 SHA-256 校验（防劫持/防损坏）
 * - priority：任务优先级
 * - networkPolicy：网络策略
 */
data class DownloadRequest(
    val url: String,
    val destFile: File,
    val headers: Map<String, String> = emptyMap(),
    val checksumSha256: String? = null,
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val networkPolicy: NetworkPolicy = NetworkPolicy.Any
)

/**
 * 进度快照：
 * - downloadedBytes：当前已写入字节数
 * - totalBytes：总大小（可能为 null，例如服务端不返回 Content-Length）
 * - bytesPerSecond：基于最近一次采样的瞬时速率估算（用于 UI 展示/自适应）
 */
data class DownloadProgress(
    val taskId: String,
    val status: TaskStatus,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long
)

/**
 * 下载框架运行参数（最小可用集）：
 * - maxParallelTasks：全局并发任务数上限
 * - maxSegmentsPerTask：单任务最大分片数（仅当服务端支持 Range 且文件足够大）
 * - segmentMinBytes：达到该阈值才会增加分片数（避免小文件分片带来的额外开销）
 * - maxTaskRetries/maxSegmentRetries：任务级/分片级重试次数
 * - baseRetryDelayMs/maxRetryDelayMs：指数退避重试区间
 */
data class DownloadConfig(
    val maxParallelTasks: Int = 3,
    val maxSegmentsPerTask: Int = 6,
    val segmentMinBytes: Long = 2L * 1024 * 1024,
    val maxTaskRetries: Int = 3,
    val maxSegmentRetries: Int = 3,
    val baseRetryDelayMs: Long = 1_000,
    val maxRetryDelayMs: Long = 60_000
)

/**
 * 事件监听（可选）：
 * - 用于接入埋点/日志/可观测性系统；
 * - 所有回调均为“至少一次”语义，调用方需要自行去重。
 */
interface DownloadEventListener {
    fun onTaskQueued(taskId: String) {}
    fun onTaskStarted(taskId: String) {}
    fun onTaskSucceeded(taskId: String) {}
    fun onTaskFailed(taskId: String, reason: String) {}
    fun onTaskPaused(taskId: String, reason: String?) {}
    fun onTaskCanceled(taskId: String) {}
}
