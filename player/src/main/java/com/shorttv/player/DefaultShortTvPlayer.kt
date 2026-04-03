package com.shorttv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.ss.ttm.player.PlaybackParams
import com.ss.ttvideoengine.Resolution
import com.ss.ttvideoengine.TTVideoEngine
import com.ss.ttvideoengine.TTVideoEngineInterface
import com.ss.ttvideoengine.VideoEngineCallback
import com.shorttv.player.optimize.DefaultPlaybackOptimizationManager
import com.shorttv.player.security.DefaultPlaybackSecurityManager
import com.shorttv.player.security.PlayerSecurityException
import com.shorttv.player.security.SecuredPlaybackSource

/**
 * 播放器默认实现。
 *
 * 设计目标有三个：
 * 1. 封装：对外屏蔽火山播放器复杂的初始化、回调和生命周期细节
 * 2. 安全：在真正下发 strategySource 之前统一走地址校验、签名和解密能力挂载
 * 3. 优化：把 ABR 起播、首帧统计、断点续播和预加载统一沉淀到库内部
 */
internal class DefaultShortTvPlayer(
    context: Context,
    private val config: PlayerConfig
) : ShortTvPlayer, ShortTvPlayerView.SurfaceCallback {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val securityManager = DefaultPlaybackSecurityManager(appContext, config.securityPolicy)
    private val optimizationManager = DefaultPlaybackOptimizationManager(
        appContext,
        config.optimizationPolicy
    )

    private var playerView: ShortTvPlayerView? = null
    private var listener: ShortTvPlayer.Listener? = null
    private var playlist: List<PlayerItem> = emptyList()
    private var currentIndex = 0
    private var currentSurface: Surface? = null
    private var internalPlayer: TTVideoEngine? = null
    private var released = false
    private var prepared = false
    private var retryPendingForItemId: String? = null
    private var lastKnownPositionMs: Long = 0L
    private var currentPlaybackState = PlaybackState.IDLE
    private var currentPlaybackStateItemId: String? = null
    private var stateBeforeSeeking: PlaybackState? = null

    /**
     * 把播放器与承载渲染的 view 绑定起来。
     *
     * 这里会先解绑旧 view，再绑定新 view，
     * 避免 surface 生命周期重复派发。
     */
    override fun attach(playerView: ShortTvPlayerView) {
        this.playerView?.setSurfaceCallback(null)
        this.playerView = playerView
        playerView.setSurfaceCallback(this)
    }

    /**
     * 设置新的播放列表，并根据配置决定是否立即自动播放。
     */
    override fun setPlaylist(items: List<PlayerItem>, startIndex: Int) {
        playlist = items
        currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (config.autoPlay && items.isNotEmpty()) {
            play(currentIndex)
        }
    }

    /**
     * 开始播放指定位置的剧集。
     *
     * 该方法内部会完成以下动作：
     * 1. 清理上一条播放项的运行时状态
     * 2. 恢复上次进度
     * 3. 应用安全策略和降档后的播放源
     * 4. 创建并启动新的 `TTVideoEngine`
     */
    override fun play(index: Int) {
        if (released || playlist.isEmpty() || index !in playlist.indices) {
            return
        }

        val previousItem = currentItem()
        val nextItem = playlist[index]
        if (previousItem != null && previousItem.id != nextItem.id) {
            optimizationManager.clearRuntimeState(previousItem)
        }
        currentIndex = index
        val item = nextItem
        prepared = false
        lastKnownPositionMs = optimizationManager.consumeStartPosition(item).toLong()
        dispatchPlaybackState(
            item = item,
            state = PlaybackState.PREPARING,
            message = if (retryPendingForItemId == item.id) "retry" else null
        )
        val isRetryPlay = retryPendingForItemId == item.id
        optimizationManager.onPlayRequested(item, resetRetryState = !isRetryPlay)
        retryPendingForItemId = null

        try {
            val playbackItem = optimizationManager.resolvePlaybackItem(item)
            val securedSource = securityManager.build(playbackItem)
            clearInternalPlayer()
            val player = TTVideoEngine(appContext, TTVideoEngine.PLAYER_TYPE_OWN)
            internalPlayer = player
            optimizationManager.onPlayerCreated(player)
            configurePlayer(player, item, playbackItem, securedSource)
            player.play()
        } catch (exception: PlayerSecurityException) {
            dispatchPlaybackEvent(
                item = item,
                event = PlaybackEvent.ErrorOccurred(
                    error = exception.error,
                    positionMs = lastKnownPositionMs
                )
            )
            dispatchPlaybackState(
                item = item,
                state = PlaybackState.ERROR,
                positionMs = lastKnownPositionMs,
                errorCode = exception.error.code,
                message = exception.error.message
            )
        } catch (exception: Exception) {
            val error = PlayerError(5001, "播放器准备失败: ${exception.message}", exception)
            dispatchPlaybackEvent(
                item = item,
                event = PlaybackEvent.ErrorOccurred(
                    error = error,
                    positionMs = lastKnownPositionMs
                )
            )
            dispatchPlaybackState(
                item = item,
                state = PlaybackState.ERROR,
                positionMs = lastKnownPositionMs,
                errorCode = error.code,
                message = error.message
            )
        }
    }

    override fun pause() {
        if (!released) {
            internalPlayer?.pause()
            currentItem()?.let {
                dispatchPlaybackState(
                    item = it,
                    state = PlaybackState.PAUSED,
                    positionMs = lastKnownPositionMs
                )
            }
        }
    }

    override fun resume() {
        if (!released && prepared) {
            internalPlayer?.play()
            currentItem()?.let {
                dispatchPlaybackState(
                    item = it,
                    state = PlaybackState.PLAYING,
                    positionMs = lastKnownPositionMs
                )
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        if (!released && prepared) {
            val item = currentItem() ?: return
            stateBeforeSeeking = currentPlaybackState.takeUnless { it == PlaybackState.SEEKING }
                ?: inferStateAfterSeeking()
            dispatchPlaybackState(
                item = item,
                state = PlaybackState.SEEKING,
                positionMs = lastKnownPositionMs
            )
            internalPlayer?.seekTo(positionMs.toInt()) { success ->
                val restoreState = stateBeforeSeeking ?: inferStateAfterSeeking()
                stateBeforeSeeking = null
                if (success) {
                    lastKnownPositionMs = positionMs
                    optimizationManager.savePosition(item, positionMs)
                    dispatchPlaybackEvent(
                        item = item,
                        event = PlaybackEvent.SeekCompleted(positionMs = positionMs)
                    )
                }
                dispatchPlaybackState(item, restoreState, positionMs = lastKnownPositionMs)
            }
        }
    }

    override fun switchResolution(resolution: Resolution) {
        if (released || !prepared) {
            return
        }
        internalPlayer?.apply {
            setIntOption(TTVideoEngine.PLAYER_OPTION_ENABLE_HLS_SEAMLESS_SWITCH, 1)
            setIntOption(TTVideoEngine.PLAYER_OPTION_ENABLE_MASTER_M3U8_OPTIMIZE, 1)
            configResolution(resolution)
        }
    }

    override fun currentIndex(): Int = currentIndex

    override fun currentItem(): PlayerItem? = playlist.getOrNull(currentIndex)

    override fun setListener(listener: ShortTvPlayer.Listener?) {
        this.listener = listener
        listener?.onPlaybackStateChanged(
            currentItem(),
            PlaybackStateEvent(
                state = currentPlaybackState,
                positionMs = lastKnownPositionMs.takeIf { it > 0L }
            )
        )
    }

    override fun release() {
        if (released) {
            return
        }
        released = true
        playerView?.setSurfaceCallback(null)
        currentItem()?.let { optimizationManager.clearRuntimeState(it) }
        clearInternalPlayer()
        currentSurface = null
        dispatchPlaybackState(currentItem(), PlaybackState.RELEASED, positionMs = lastKnownPositionMs)
    }

    override fun onSurfaceReady(surface: Surface) {
        currentSurface = surface
        if (!released) {
            internalPlayer?.setSurface(surface)
            playerView?.let {
                internalPlayer?.setDisplayMode(it.renderView(), currentItem()?.displayMode ?: TTVideoEngineInterface.IMAGE_LAYOUT_ASPECT_FILL)
            }
        }
    }

    override fun onSurfaceDestroyed() {
        internalPlayer?.setSurface(null)
        currentSurface = null
    }

    /**
     * 将播放器实例和当前播放项真正绑定起来。
     *
     * 这里集中设置火山播放器的大部分基础选项，
     * 例如起播进度、静音、速度和渲染目标。
     */
    private fun configurePlayer(
        player: TTVideoEngine,
        item: PlayerItem,
        playbackItem: PlayerItem,
        securedSource: SecuredPlaybackSource
    ) {
        player.tag = config.playerSceneTag
        player.strategySource = securedSource.strategySource
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_INT_ALLOW_ALL_EXTENSIONS, 1)
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_POSITION_UPDATE_INTERVAL, config.progressUpdateIntervalMs.toInt())
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_INT_ENABLE_ERROR_THROW_OPTIMIZE, 1)
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_NOTIFY_BUFFERING_DIRECTLY, 0)
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_ENABLE_SEEK_LASTFRAME, 1)
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_ENABLE_SEEK_END, 1)
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_ENABLE_HLS_SEAMLESS_SWITCH, 1)
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_ENABLE_MASTER_M3U8_OPTIMIZE, 1)
        player.setPlaybackParams(PlaybackParams().apply {
            speed = config.defaultSpeed
        })
        player.setStartTime(lastKnownPositionMs.toInt())
        player.setIsMute(config.defaultMute)
        player.isLooping = false
        currentSurface?.let { player.setSurface(it) }
        playerView?.let { player.setDisplayMode(it.renderView(), playbackItem.displayMode) }
        securityManager.attach(player, securedSource)
        bindCallback(player)
    }

    /**
     * 绑定火山播放器回调，并把底层事件转换成模块统一事件。
     *
     * 这是整个播放器实现里最关键的一层适配：
     * 对下对接 TTVideoEngine，对上输出稳定的业务回调和状态流转。
     */
    private fun bindCallback(player: TTVideoEngine) {
        player.setVideoEngineCallback(object : VideoEngineCallback {
            override fun onPrepared(engine: TTVideoEngine) {
                if (released) {
                    return
                }
                prepared = true
                val item = currentItem() ?: return
                optimizationManager.onPrepared(item, playlist, currentIndex)
                dispatchPlaybackState(item, PlaybackState.PREPARED, positionMs = lastKnownPositionMs)
            }

            override fun onRenderStart(engine: TTVideoEngine) {
                val item = currentItem() ?: return
                val result = optimizationManager.onFirstFrame(item)
                dispatchPlaybackEvent(
                    item = item,
                    event = PlaybackEvent.FirstFrameRendered(
                        firstFrameCostMs = result.costMs,
                        positionMs = lastKnownPositionMs
                    )
                )
                dispatchPlaybackState(item, PlaybackState.PLAYING, positionMs = lastKnownPositionMs)
            }

            override fun onCurrentPlaybackTimeUpdate(engine: TTVideoEngine, currentPlaybackTime: Int) {
                val item = currentItem() ?: return
                val durationMs = engine.duration.toLong().coerceAtLeast(0L)
                lastKnownPositionMs = currentPlaybackTime.toLong()
                optimizationManager.savePosition(item, currentPlaybackTime.toLong())
                dispatchPlaybackEvent(
                    item = item,
                    event = PlaybackEvent.Progress(
                        positionMs = currentPlaybackTime.toLong(),
                        durationMs = durationMs
                    )
                )
            }

            override fun onVideoStreamBitrateChanged(resolution: Resolution, bitrate: Int) {
                val item = currentItem() ?: return
                dispatchPlaybackEvent(
                    item = item,
                    event = PlaybackEvent.ResolutionChanged(
                        resolution = resolution,
                        bitrate = bitrate,
                        positionMs = lastKnownPositionMs
                    )
                )
            }

            override fun onBufferStart(code: Int, afterFirstFrame: Int, action: Int) {
                val item = currentItem() ?: return
                val event = optimizationManager.onBufferStart(item, code, afterFirstFrame, action)
                dispatchPlaybackEvent(
                    item = item,
                    event = PlaybackEvent.BufferingStart(
                        bufferEvent = event,
                        positionMs = lastKnownPositionMs
                    )
                )
            }

            override fun onBufferEnd(code: Int) {
                val item = currentItem() ?: return
                val event = optimizationManager.onBufferEnd(item, code) ?: return
                dispatchPlaybackEvent(
                    item = item,
                    event = PlaybackEvent.BufferingEnd(
                        bufferEndEvent = event,
                        positionMs = lastKnownPositionMs
                    )
                )
                val downgradeEvent = optimizationManager.shouldDowngradeAfterBuffer(item, event) ?: return
                replayWithDowngrade(item, downgradeEvent)
            }

            override fun onCompletion(engine: TTVideoEngine) {
                val item = currentItem() ?: return
                optimizationManager.clearPosition(item)
                optimizationManager.clearRuntimeState(item)
                if (config.loopCurrentItem) {
                    play(currentIndex)
                    return
                }
                val nextItem = optimizationManager.nextItem(playlist, currentIndex)
                if (nextItem != null) {
                    dispatchPlaybackEvent(
                        item = item,
                        event = PlaybackEvent.AutoPlayNext(
                            nextItem = nextItem,
                            positionMs = lastKnownPositionMs
                        )
                    )
                    play(currentIndex + 1)
                } else {
                    dispatchPlaybackState(item, PlaybackState.COMPLETED, positionMs = lastKnownPositionMs)
                }
            }

            override fun onError(error: com.ss.ttvideoengine.utils.Error) {
                prepared = false
                stateBeforeSeeking = null
                val item = currentItem()
                val retryEvent = item?.let { optimizationManager.shouldRetry(it, error.code) }
                if (item != null && retryEvent != null) {
                    retryPendingForItemId = item.id
                    mainHandler.post {
                        dispatchPlaybackEvent(
                            item = item,
                            event = PlaybackEvent.Retry(retryEvent = retryEvent)
                        )
                        play(currentIndex)
                    }
                    return
                }
                val downgradeEvent = item?.let { optimizationManager.shouldDowngradeAfterError(it, error.code) }
                if (item != null && downgradeEvent != null) {
                    replayWithDowngrade(item, downgradeEvent)
                    return
                }
                item?.let { optimizationManager.clearRuntimeState(it) }
                val playerError = PlayerError(
                    code = error.code,
                    message = error.description ?: "火山播放器发生未知错误"
                )
                dispatchPlaybackEvent(
                    item = item,
                    event = PlaybackEvent.ErrorOccurred(
                        error = playerError,
                        positionMs = lastKnownPositionMs
                    )
                )
                dispatchPlaybackState(
                    item = item,
                    state = PlaybackState.ERROR,
                    positionMs = lastKnownPositionMs,
                    errorCode = error.code,
                    message = error.description
                )
            }
        })
    }

    private fun clearInternalPlayer() {
        prepared = false
        securityManager.release(internalPlayer)
        internalPlayer?.releaseAsync()
        internalPlayer = null
    }

    /**
     * 在降级画质后重新触发当前播放项的起播流程。
     *
     * 为了尽量减少用户感知，这里会先保存当前位置，
     * 然后让新的播放源从接近原位置继续播放。
     */
    private fun replayWithDowngrade(item: PlayerItem, event: QualityDowngradeEvent) {
        optimizationManager.savePosition(item, lastKnownPositionMs)
        mainHandler.post {
            dispatchPlaybackEvent(
                item = item,
                event = PlaybackEvent.QualityDowngraded(
                    qualityDowngradeEvent = event,
                    positionMs = lastKnownPositionMs
                )
            )
            play(currentIndex)
        }
    }

    /**
     * 统一派发播放事件。
     *
     * 事件描述的是“刚刚发生了什么”，
     * 例如首帧完成、buffering 开始、seek 完成、重试、自动降档等。
     */
    private fun dispatchPlaybackEvent(
        item: PlayerItem?,
        event: PlaybackEvent
    ) {
        listener?.onPlaybackEvent(item, event)
    }

    /**
     * 在 seek 完成后推断应该恢复到哪个稳定状态。
     */
    private fun inferStateAfterSeeking(): PlaybackState {
        return when {
            !prepared -> PlaybackState.PREPARING
            currentPlaybackState == PlaybackState.PAUSED -> PlaybackState.PAUSED
            else -> PlaybackState.PLAYING
        }
    }

    /**
     * 统一派发播放状态。
     *
     * 这里会做一次轻量去重：
     * 如果状态、播放项、错误信息都没有变化，就不重复通知业务层。
     */
    private fun dispatchPlaybackState(
        item: PlayerItem?,
        state: PlaybackState,
        positionMs: Long? = null,
        errorCode: Int? = null,
        message: String? = null
    ) {
        if (
            currentPlaybackState == state &&
            currentPlaybackStateItemId == item?.id &&
            errorCode == null &&
            message == null
        ) {
            return
        }
        currentPlaybackState = state
        currentPlaybackStateItemId = item?.id
        val event = PlaybackStateEvent(
            state = state,
            positionMs = positionMs,
            errorCode = errorCode,
            message = message
        )
        mainHandler.post {
            listener?.onPlaybackStateChanged(item, event)
        }
    }
}
