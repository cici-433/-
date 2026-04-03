package com.shorttv.player

import android.view.View
import com.ss.ttvideoengine.Resolution
import com.ss.ttvideoengine.TTVideoEngineInterface
import com.ss.ttvideoengine.strategy.source.StrategySource

/**
 * 播放器对外暴露的最小能力集合。
 *
 * 这个接口的目标不是暴露底层火山播放器 SDK 的所有细节，
 * 而是给业务方一个稳定、清晰、可测试的调用面。
 *
 * 业务侧只需要关心：
 * 1. 要播什么
 * 2. 在哪个 view 上播
 * 3. 播放状态怎么监听
 * 4. 何时释放资源
 */
interface ShortTvPlayer {

    /**
     * 将播放器绑定到承载渲染的容器 view。
     *
     * 调用方可以在页面初始化后调用一次，
     * 后续播放器会在这个 view 内部管理渲染 surface。
     */
    fun attach(playerView: ShortTvPlayerView)

    /**
     * 设置播放列表。
     *
     * 该接口不仅服务于普通单视频播放，
     * 也服务于短剧连续切集的播放场景。
     */
    fun setPlaylist(items: List<PlayerItem>, startIndex: Int = 0)

    /**
     * 播放指定下标的视频。
     */
    fun play(index: Int = currentIndex())

    /**
     * 暂停当前播放。
     */
    fun pause()

    /**
     * 继续当前播放。
     */
    fun resume()

    /**
     * 跳转到指定进度。
     */
    fun seekTo(positionMs: Long)

    fun switchResolution(resolution: Resolution)

    /**
     * 返回当前播放的下标。
     */
    fun currentIndex(): Int

    /**
     * 返回当前播放项。
     */
    fun currentItem(): PlayerItem?

    /**
     * 注册监听器。
     */
    fun setListener(listener: Listener?)

    /**
     * 释放播放器资源。
     *
     * 页面销毁时必须调用，
     * 否则可能残留 surface、音频焦点或后台线程。
     */
    fun release()

    interface Listener {
        /**
         * 统一的播放状态回调。
         *
         * 状态只回答一个问题：播放器“现在能做什么”。
         *
         * 因此这里应该只承载稳定、可持续一段时间的生命周期阶段，
         * 例如 preparing、playing、paused、seeking、error。
         */
        fun onPlaybackStateChanged(item: PlayerItem?, event: PlaybackStateEvent) {}

        /**
         * 统一的播放事件回调。
         *
         * 事件只回答一个问题：播放器“刚刚发生了什么”。
         *
         * 它是瞬时通知，不负责表达当前控制能力。
         *
         * 为了避免一个大而全的事件对象不断膨胀，这里使用强类型事件子类表达
         * first frame、progress、buffering、seek completed、retry、
         * quality downgrade、自动切下一集和 error。
         */
        fun onPlaybackEvent(item: PlayerItem?, event: PlaybackEvent) {}
    }
}

/**
 * 单个播放项的数据模型。
 *
 * 设计时刻意同时支持两种火山播放器输入方式：
 * 1. playUrl：适合直接播放单个加签地址
 * 2. strategySource：适合多清晰度、ABR、火山缓存和更复杂的播放编排场景
 */
data class PlayerItem(
    val id: String,
    val title: String,
    val playUrl: String? = null,
    val strategySource: StrategySource? = null,
    val cacheKey: String? = null,
    val fallbackSources: List<PlaybackSource> = emptyList(),
    val needDecrypt: Boolean = false,
    val displayMode: Int = TTVideoEngineInterface.IMAGE_LAYOUT_ASPECT_FILL,
    val extras: Map<String, String> = emptyMap()
)

/**
 * 播放器配置。
 *
 * 这部分配置对应播放器库最核心的三个方向：
 * 1. 封装：统一控制自动播放、循环等基础行为
 * 2. 安全：通过 securityPolicy 注入链路保护策略
 * 3. 优化：通过 optimizationPolicy 注入首帧、连续播放和预加载策略
 */
data class PlayerConfig(
    val autoPlay: Boolean = true,
    val loopCurrentItem: Boolean = false,
    val progressUpdateIntervalMs: Long = 500L,
    val defaultMute: Boolean = false,
    val defaultSpeed: Float = 1f,
    val playerSceneTag: String = "ShortTv",
    val securityPolicy: PlaybackSecurityPolicy = PlaybackSecurityPolicy(),
    val optimizationPolicy: PlaybackOptimizationPolicy = PlaybackOptimizationPolicy()
)

/**
 * 统一错误模型。
 *
 * 将底层火山播放器错误、业务校验错误、安全校验错误都收敛成一种结构，
 * 便于业务方进行统一展示和统一埋点。
 */
data class PlayerError(
    val code: Int,
    val message: String,
    val cause: Throwable? = null
)

/**
 * 安全策略配置。
 *
 * 这里把安全相关的行为尽量做成配置化：
 * 1. 是否要求 https
 * 2. 是否对播放 URL 追加 token 参数
 * 3. 是否对请求追加 nonce 参数
 */
data class PlaybackSecurityPolicy(
    val requireHttps: Boolean = true,
    val authTokenQueryName: String = "token",
    val authTokenProvider: (() -> String?)? = null,
    val nonceQueryName: String = "nonce",
    val nonceProvider: (() -> String?)? = null,
    val decryptKey: String = "shortmax00000000"
)

/**
 * 优化策略配置。
 *
 * 这里重点控制短剧场景下最敏感的几个优化项：
 * 1. 首帧统计阈值
 * 2. 预加载窗口大小
 * 3. 是否保存断点续播位置
 */
data class PlaybackOptimizationPolicy(
    val firstFrameWarningMs: Long = 1200L,
    val preloadWindow: Int = 1,
    val preloadSizeBytes: Long = 512 * 1024L,
    val rememberPlaybackPosition: Boolean = true,
    val enableEnginePreload: Boolean = true,
    val enableAbrStartup: Boolean = true,
    val enableStartupRetry: Boolean = true,
    val startupRetryMaxCount: Int = 1,
    val enableBufferAutoDowngrade: Boolean = true,
    val bufferDowngradeTriggerCount: Int = 2,
    val bufferDowngradeMinDurationMs: Long = 1500L,
    val enableErrorAutoDowngrade: Boolean = true
)

/**
 * 一次 buffering 开始时的上下文信息。
 *
 * 该事件适合用于：
 * 1. 卡顿埋点
 * 2. 卡顿提示 UI
 * 3. 降级策略判断的原始输入
 */
data class BufferEvent(
    val count: Int,
    val startedAtMs: Long,
    val code: Int,
    val afterFirstFrame: Boolean,
    val action: Int
)

/**
 * 一次 buffering 结束时的结果信息。
 *
 * 与 [BufferEvent] 配合使用后，
 * 业务层可以知道一次卡顿到底持续了多久、是否发生在首帧后。
 */
data class BufferEndEvent(
    val count: Int,
    val durationMs: Long,
    val code: Int,
    val afterFirstFrame: Boolean,
    val action: Int
)

/**
 * 一次起播重试事件。
 *
 * 当播放器在首帧前遇到可恢复错误时，
 * 会优先通过重试提升成功率，而不是直接把失败暴露给用户。
 */
data class RetryEvent(
    val attempt: Int,
    val errorCode: Int,
    val reason: String
)

/**
 * 一个可切换的播放源描述。
 *
 * 它主要用于多清晰度或多线路场景，
 * 让播放器在同一个 [PlayerItem] 内部就能完成自动降档切换。
 */
data class PlaybackSource(
    val label: String,
    val playUrl: String? = null,
    val strategySource: StrategySource? = null,
    val cacheKey: String? = null
)

/**
 * 一次自动降级画质事件。
 *
 * 业务层可以利用该事件做提示、上报，
 * 也可以和网络状态一起联动做体验诊断。
 */
data class QualityDowngradeEvent(
    val reason: String,
    val triggerCode: Int,
    val fromLevel: Int,
    val toLevel: Int,
    val fromLabel: String,
    val toLabel: String
)

/**
 * 统一播放事件回调对应的事件体。
 *
 * 这个模型专门承载瞬时通知。
 * 如果一个信息不会持续存在，而是“发生即结束”，就应该进入事件模型。
 */
sealed class PlaybackEvent {
    /**
     * 首帧已经渲染完成。
     */
    data class FirstFrameRendered(
        val firstFrameCostMs: Long,
        val positionMs: Long? = null
    ) : PlaybackEvent()

    /**
     * 发生了一次进度更新。
     */
    data class Progress(
        val positionMs: Long,
        val durationMs: Long
    ) : PlaybackEvent()

    /**
     * 发生了一次 buffering 开始。
     */
    data class BufferingStart(
        val bufferEvent: BufferEvent,
        val positionMs: Long? = null
    ) : PlaybackEvent()

    /**
     * 发生了一次 buffering 结束。
     */
    data class BufferingEnd(
        val bufferEndEvent: BufferEndEvent,
        val positionMs: Long? = null
    ) : PlaybackEvent()

    /**
     * 一次 seek 已完成。
     */
    data class SeekCompleted(
        val positionMs: Long
    ) : PlaybackEvent()

    data class ResolutionChanged(
        val resolution: Resolution,
        val bitrate: Int? = null,
        val positionMs: Long? = null
    ) : PlaybackEvent()

    /**
     * 因可恢复错误而重新尝试起播。
     */
    data class Retry(
        val retryEvent: RetryEvent
    ) : PlaybackEvent()

    /**
     * 因弱网或错误切换到更低档播放源。
     */
    data class QualityDowngraded(
        val qualityDowngradeEvent: QualityDowngradeEvent,
        val positionMs: Long? = null
    ) : PlaybackEvent()

    /**
     * 即将自动切换到下一条播放项。
     */
    data class AutoPlayNext(
        val nextItem: PlayerItem,
        val positionMs: Long? = null
    ) : PlaybackEvent()

    /**
     * 刚刚发生了一次播放错误。
     */
    data class ErrorOccurred(
        val error: PlayerError,
        val positionMs: Long? = null
    ) : PlaybackEvent()
}

/**
 * 播放器对外暴露的统一状态枚举。
 *
 * 状态只描述播放器当前所处阶段和控制能力边界。
 * 只要这个信息会持续一段时间，并决定“现在能不能执行某个操作”，
 * 它就应该建模为状态。
 *
 * 反过来，first frame rendered、buffering start/end、progress、
 * seek completed、retry、quality downgrade 都只是瞬时通知，不应混入状态。
 */
enum class PlaybackState {
    /**
     * 播放器空闲，尚未开始准备当前播放项。
     */
    IDLE,

    /**
     * 已经收到播放请求，正在准备资源和播放器实例。
     */
    PREPARING,

    /**
     * 底层播放器准备完成。
     */
    PREPARED,

    /**
     * 正在稳定播放。
     */
    PLAYING,

    /**
     * 被业务主动暂停。
     */
    PAUSED,

    /**
     * 正在执行 seek。
     *
     * 进入该状态后，业务层应认为当前播放位置处于切换中。
     */
    SEEKING,

    /**
     * 当前播放项已播放完成。
     */
    COMPLETED,

    /**
     * 当前播放流程以错误结束。
     */
    ERROR,

    /**
     * 播放器资源已释放。
     */
    RELEASED
}

/**
 * 统一播放状态回调对应的事件体。
 *
 * 它只承载稳定状态本身和状态上下文，
 * 不承载瞬时事件。
 */
data class PlaybackStateEvent(
    val state: PlaybackState,
    val positionMs: Long? = null,
    val errorCode: Int? = null,
    val message: String? = null
)

/**
 * 播放器工厂。
 *
 * 统一通过工厂来创建播放器实例，
 * 可以避免业务层直接 new 实现类，降低耦合。
 */
object PlayerFactory {
    fun create(
        hostView: View,
        config: PlayerConfig = PlayerConfig()
    ): ShortTvPlayer {
        return DefaultShortTvPlayer(hostView.context.applicationContext, config)
    }
}
