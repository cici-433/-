package com.startshorts.download.api

import android.content.Context
import com.startshorts.download.core.DefaultDownloadClient
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/**
 * 下载框架对外入口（API 层）：
 * - enqueue/pause/resume/cancel 为任务控制面；
 * - observe 为数据面，输出 [DownloadProgress] 流。
 *
 * 约定：
 * - enqueue 返回 taskId，作为后续控制与订阅的唯一标识；
 * - pause/resume/cancel 是幂等的（重复调用不会导致异常）。
 */
interface DownloadClient {
    /**
     * 入队一个下载任务，返回任务 taskId。
     */
    suspend fun enqueue(request: DownloadRequest): String

    /**
     * 暂停任务：
     * - reason 仅用于上层记录与排障，不参与调度逻辑。
     */
    suspend fun pause(taskId: String, reason: String? = null)

    /**
     * 恢复任务：
     * - 会重新进入调度队列；
     * - 若任务已 COMPLETED/CANCELED，则不会再次执行。
     */
    suspend fun resume(taskId: String)

    /**
     * 取消任务：
     * - deleteFile=true 时会尝试删除临时文件与目标文件；
     * - 默认会从持久化存储中移除任务记录。
     */
    suspend fun cancel(taskId: String, deleteFile: Boolean = true)

    /**
     * 订阅任务进度（冷流）：
     * - 订阅开始时会先发射一次当前快照；
     * - 后续按下载执行推进持续发射。
     */
    fun observe(taskId: String): Flow<DownloadProgress>
}

/**
 * 工厂方法：
 * - 复用调用方传入的 OkHttpClient（便于统一超时、代理、证书、DNS、限速等策略）；
 * - 内部会使用 applicationContext，避免 Activity 泄漏。
 */
object DownloadClientFactory {
    fun create(
        context: Context,
        config: DownloadConfig = DownloadConfig(),
        okHttpClient: OkHttpClient = OkHttpClient(),
        eventListener: DownloadEventListener? = null
    ): DownloadClient {
        return DefaultDownloadClient(
            appContext = context.applicationContext,
            okHttpClient = okHttpClient,
            config = config,
            eventListener = eventListener
        )
    }
}
