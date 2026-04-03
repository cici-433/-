package com.shorttv.player.optimize

import android.content.Context
import android.os.SystemClock
import com.ss.ttvideoengine.PreloaderURLItem
import com.ss.ttvideoengine.TTVideoEngine
import com.ss.ttvideoengine.selector.strategy.GearStrategy
import com.ss.ttvideoengine.strategy.StrategyManager
import com.ss.ttvideoengine.source.DirectUrlSource
import com.shorttv.player.BufferEndEvent
import com.shorttv.player.BufferEvent
import com.shorttv.player.PlayerItem
import com.shorttv.player.PlaybackOptimizationPolicy
import com.shorttv.player.PlaybackSource
import com.shorttv.player.QualityDowngradeEvent
import com.shorttv.player.RetryEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * 优化模块的统一入口。
 *
 * 这个类负责串联三个核心优化能力：
 * 1. 首帧统计
 * 2. 连续播放与预加载
 * 3. 断点续播
 */
class DefaultPlaybackOptimizationManager(
    context: Context,
    private val policy: PlaybackOptimizationPolicy
) {

    private val positionStore = PlaybackPositionStore(context)
    private val firstFrameTracker = FirstFrameTracker(policy.firstFrameWarningMs)
    private val preloadManager = UrlPreloadManager(policy.enableEnginePreload, policy.preloadSizeBytes)
    private val playlistCoordinator = PlaylistCoordinator(preloadManager, policy.preloadWindow)
    private val abrStartupConfigurator = AbrStartupConfigurator(policy.enableAbrStartup)
    private val bufferTracker = BufferTracker()
    private val retryController = StartupRetryController(
        policy.enableStartupRetry,
        policy.startupRetryMaxCount,
        firstFrameTracker
    )
    private val qualityDowngradeController = QualityDowngradeController(
        enableBufferAutoDowngrade = policy.enableBufferAutoDowngrade,
        bufferDowngradeTriggerCount = policy.bufferDowngradeTriggerCount,
        bufferDowngradeMinDurationMs = policy.bufferDowngradeMinDurationMs,
        enableErrorAutoDowngrade = policy.enableErrorAutoDowngrade
    )

    /**
     * 在播放器实例创建完成后应用起播相关优化配置。
     */
    fun onPlayerCreated(player: TTVideoEngine) {
        abrStartupConfigurator.configure(player)
    }

    /**
     * 根据当前降档状态解析本次真正应该使用的播放源。
     */
    fun resolvePlaybackItem(item: PlayerItem): PlayerItem {
        return qualityDowngradeController.resolve(item)
    }

    /**
     * 在每次起播前初始化首帧统计、卡顿统计与重试状态。
     */
    fun onPlayRequested(item: PlayerItem, resetRetryState: Boolean = true) {
        firstFrameTracker.markPlayRequest(item.id)
        bufferTracker.clear(item.id)
        if (resetRetryState) {
            retryController.clear(item.id)
        }
    }

    /**
     * 在播放器 prepared 后触发连续播放和预加载逻辑。
     */
    fun onPrepared(item: PlayerItem, playlist: List<PlayerItem>, currentIndex: Int) {
        playlistCoordinator.onItemPrepared(playlist, currentIndex)
        preloadManager.markAsPlaying(item)
    }

    /**
     * 标记首帧完成，并返回首帧统计结果。
     */
    fun onFirstFrame(item: PlayerItem): FirstFrameResult {
        return firstFrameTracker.markFirstFrame(item.id)
    }

    /**
     * 记录一次 buffering 开始事件。
     */
    fun onBufferStart(item: PlayerItem, code: Int, afterFirstFrame: Int, action: Int): BufferEvent {
        return bufferTracker.onStart(
            itemId = item.id,
            code = code,
            afterFirstFrame = afterFirstFrame != 0,
            action = action
        )
    }

    /**
     * 记录一次 buffering 结束事件。
     */
    fun onBufferEnd(item: PlayerItem, code: Int): BufferEndEvent? {
        return bufferTracker.onEnd(item.id, code)
    }

    /**
     * 判断当前错误是否需要执行起播重试。
     */
    fun shouldRetry(item: PlayerItem, errorCode: Int): RetryEvent? {
        return retryController.onError(item, errorCode)
    }

    /**
     * 判断当前卡顿是否达到自动降档条件。
     */
    fun shouldDowngradeAfterBuffer(item: PlayerItem, event: BufferEndEvent): QualityDowngradeEvent? {
        return qualityDowngradeController.onBufferEnd(item, event)
    }

    /**
     * 判断当前错误是否需要自动降级画质。
     */
    fun shouldDowngradeAfterError(item: PlayerItem, errorCode: Int): QualityDowngradeEvent? {
        return qualityDowngradeController.onError(item, errorCode)
    }

    /**
     * 保存指定播放项的续播位置。
     */
    fun savePosition(item: PlayerItem, positionMs: Long) {
        if (!policy.rememberPlaybackPosition) {
            return
        }
        positionStore.save(item.id, positionMs)
    }

    /**
     * 读取并消费指定播放项的起播位置。
     */
    fun consumeStartPosition(item: PlayerItem): Int {
        if (!policy.rememberPlaybackPosition) {
            return 0
        }
        return positionStore.get(item.id).toInt()
    }

    /**
     * 清理某个播放项持久化保存的续播位置。
     */
    fun clearPosition(item: PlayerItem) {
        positionStore.remove(item.id)
    }

    /**
     * 清理某个播放项的运行时状态。
     *
     * 这里不会删除持久化的续播位置，
     * 只会清理与当前一次播放过程相关的内存状态。
     */
    fun clearRuntimeState(item: PlayerItem) {
        bufferTracker.clear(item.id)
        retryController.clear(item.id)
        firstFrameTracker.clear(item.id)
        qualityDowngradeController.clear(item.id)
    }

    /**
     * 找到当前播放项之后的下一条可播放内容。
     */
    fun nextItem(playlist: List<PlayerItem>, currentIndex: Int): PlayerItem? {
        return playlistCoordinator.findNextPlayableItem(playlist, currentIndex)
    }
}

/**
 * 首帧统计器。
 *
 * 首帧统计是短剧播放器最重要的体验指标之一。
 * 这里把统计逻辑独立出来，便于以后替换成更复杂的埋点体系。
 */
class FirstFrameTracker(
    private val warningThresholdMs: Long
) {

    private val requestStartTime = ConcurrentHashMap<String, Long>()
    private val renderedItems = ConcurrentHashMap.newKeySet<String>()

    fun markPlayRequest(itemId: String) {
        requestStartTime[itemId] = SystemClock.elapsedRealtime()
        renderedItems.remove(itemId)
    }

    fun markFirstFrame(itemId: String): FirstFrameResult {
        val startTime = requestStartTime.remove(itemId) ?: SystemClock.elapsedRealtime()
        renderedItems.add(itemId)
        val cost = SystemClock.elapsedRealtime() - startTime
        return FirstFrameResult(
            costMs = cost,
            slow = cost >= warningThresholdMs
        )
    }

    fun hasFirstFrame(itemId: String): Boolean {
        return renderedItems.contains(itemId)
    }

    fun clear(itemId: String) {
        requestStartTime.remove(itemId)
        renderedItems.remove(itemId)
    }
}

data class FirstFrameResult(
    val costMs: Long,
    val slow: Boolean
)

/**
 * 播放位置存储。
 *
 * 对短剧场景来说，记住用户上次看到哪里是连续体验的一部分。
 * 这里使用 SharedPreferences 是因为它轻量、稳定、足够满足单值存储需求。
 */
class PlaybackPositionStore(context: Context) {

    private val preferences = context.getSharedPreferences(
        "shorttv_player_position_store",
        Context.MODE_PRIVATE
    )

    /**
     * 持久化保存续播位置。
     */
    fun save(itemId: String, positionMs: Long) {
        preferences.edit().putLong(itemId, positionMs).apply()
    }

    /**
     * 读取上次保存的续播位置。
     */
    fun get(itemId: String): Long {
        return preferences.getLong(itemId, 0L)
    }

    /**
     * 删除某个播放项的续播位置。
     */
    fun remove(itemId: String) {
        preferences.edit().remove(itemId).apply()
    }
}

/**
 * 连续播放协调器。
 *
 * 该类不直接控制播放器，而是专门负责“下一集是谁”和“下一集何时开始预加载”。
 * 这样可以让连续播放逻辑和播放器内核逻辑解耦。
 */
class PlaylistCoordinator(
    private val preloadManager: UrlPreloadManager,
    private val preloadWindow: Int
) {

    /**
     * 在当前播放项准备完成后，预热后续窗口内的内容。
     */
    fun onItemPrepared(playlist: List<PlayerItem>, currentIndex: Int) {
        if (preloadWindow <= 0) {
            return
        }
        val start = currentIndex + 1
        val end = minOf(currentIndex + preloadWindow, playlist.lastIndex)
        for (index in start..end) {
            if (index in playlist.indices) {
                preloadManager.preload(playlist[index])
            }
        }
    }

    /**
     * 返回当前下标之后的下一条播放项。
     */
    fun findNextPlayableItem(playlist: List<PlayerItem>, currentIndex: Int): PlayerItem? {
        val nextIndex = currentIndex + 1
        return if (nextIndex in playlist.indices) playlist[nextIndex] else null
    }
}

/**
 * URL 预加载器。
 *
 * 这个实现做的是“轻量级预热”：
 * 通过发起带 Range 的短连接请求，把下一条视频的 DNS、TCP、TLS 和部分响应链路提前热起来。
 *
 * 它不是一个完整缓存系统，但足以表达短剧播放器里“预加载”的基础设计思想。
 */
class UrlPreloadManager(
    private val enabled: Boolean,
    private val preloadSizeBytes: Long
) {

    private val preloadedKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * 对目标播放项执行一次轻量级 URL 预热。
     */
    fun preload(item: PlayerItem) {
        val url = item.playUrl ?: return
        val cacheKey = item.stableCacheKey()
        if (!enabled || !preloadedKeys.add(cacheKey)) {
            return
        }
        try {
            val source = createDirectUrlSource(url, cacheKey)
            TTVideoEngine.addTask(PreloaderURLItem(source, preloadSizeBytes))
        } catch (_: Exception) {
            preloadedKeys.remove(cacheKey)
        }
    }

    /**
     * 当前内容真正进入播放后，移除它的预热标记。
     */
    fun markAsPlaying(item: PlayerItem) {
        preloadedKeys.remove(item.stableCacheKey())
    }

    private fun createDirectUrlSource(url: String, cacheKey: String): DirectUrlSource {
        val urlItem = DirectUrlSource.UrlItem.Builder()
            .setUrl(url)
            .setCacheKey(cacheKey)
            .build()
        return DirectUrlSource.Builder()
            .setVid(cacheKey)
            .addItem(urlItem)
            .build()
    }
}

/**
 * 卡顿事件跟踪器。
 *
 * 它负责把底层零散的 buffering start/end 回调
 * 拼接成更适合业务侧消费的事件对象。
 */
class BufferTracker {

    private val activeEvents = ConcurrentHashMap<String, BufferSnapshot>()
    private val counters = ConcurrentHashMap<String, Int>()

    /**
     * 记录一次卡顿开始。
     */
    fun onStart(itemId: String, code: Int, afterFirstFrame: Boolean, action: Int): BufferEvent {
        val count = counters.merge(itemId, 1, Int::plus) ?: 1
        val startedAt = SystemClock.elapsedRealtime()
        activeEvents[itemId] = BufferSnapshot(startedAt, count, code, afterFirstFrame, action)
        return BufferEvent(
            count = count,
            startedAtMs = startedAt,
            code = code,
            afterFirstFrame = afterFirstFrame,
            action = action
        )
    }

    /**
     * 结束当前卡顿并计算持续时长。
     */
    fun onEnd(itemId: String, code: Int): BufferEndEvent? {
        val snapshot = activeEvents.remove(itemId) ?: return null
        return BufferEndEvent(
            count = snapshot.count,
            durationMs = (SystemClock.elapsedRealtime() - snapshot.startedAtMs).coerceAtLeast(0L),
            code = code,
            afterFirstFrame = snapshot.afterFirstFrame,
            action = snapshot.action
        )
    }

    /**
     * 清空某个播放项的卡顿状态。
     */
    fun clear(itemId: String) {
        activeEvents.remove(itemId)
        counters.remove(itemId)
    }
}

/**
 * 起播重试控制器。
 *
 * 该控制器只关注“首帧前错误”，
 * 因为首帧后错误更适合交给降档或错误态处理。
 */
class StartupRetryController(
    private val enabled: Boolean,
    private val maxRetryCount: Int,
    private val firstFrameTracker: FirstFrameTracker
) {

    private val attempts = ConcurrentHashMap<String, Int>()

    /**
     * 根据当前错误判断是否可以继续重试起播。
     */
    fun onError(item: PlayerItem, errorCode: Int): RetryEvent? {
        if (!enabled || maxRetryCount <= 0 || firstFrameTracker.hasFirstFrame(item.id)) {
            return null
        }
        val nextAttempt = (attempts[item.id] ?: 0) + 1
        if (nextAttempt > maxRetryCount) {
            return null
        }
        attempts[item.id] = nextAttempt
        return RetryEvent(
            attempt = nextAttempt,
            errorCode = errorCode,
            reason = "startup_error"
        )
    }

    /**
     * 清理某个播放项的重试计数。
     */
    fun clear(itemId: String) {
        attempts.remove(itemId)
    }
}

/**
 * 自动降级画质控制器。
 *
 * 这里维护每个播放项当前已经降到哪一档，
 * 并在卡顿或错误达到阈值时切到更低档位。
 */
class QualityDowngradeController(
    private val enableBufferAutoDowngrade: Boolean,
    private val bufferDowngradeTriggerCount: Int,
    private val bufferDowngradeMinDurationMs: Long,
    private val enableErrorAutoDowngrade: Boolean
) {

    private val selectedLevels = ConcurrentHashMap<String, Int>()

    /**
     * 根据当前已选档位解析出真正要播放的 source。
     */
    fun resolve(item: PlayerItem): PlayerItem {
        val level = selectedLevels[item.id] ?: 0
        val selectedSource = item.sourceAt(level) ?: return item
        return item.copy(
            playUrl = selectedSource.playUrl ?: item.playUrl,
            strategySource = selectedSource.strategySource ?: item.strategySource,
            cacheKey = selectedSource.cacheKey ?: item.cacheKey
        )
    }

    /**
     * 在一次卡顿结束后评估是否应该降档。
     */
    fun onBufferEnd(item: PlayerItem, event: BufferEndEvent): QualityDowngradeEvent? {
        if (!enableBufferAutoDowngrade) {
            return null
        }
        if (!event.afterFirstFrame || event.action != BUFFER_ACTION_NONE) {
            return null
        }
        if (event.count < bufferDowngradeTriggerCount || event.durationMs < bufferDowngradeMinDurationMs) {
            return null
        }
        return downgrade(item, "buffer", event.code)
    }

    /**
     * 在一次播放错误发生后评估是否应该降档。
     */
    fun onError(item: PlayerItem, errorCode: Int): QualityDowngradeEvent? {
        if (!enableErrorAutoDowngrade) {
            return null
        }
        return downgrade(item, "error", errorCode)
    }

    /**
     * 清除某个播放项当前记住的降档级别。
     */
    fun clear(itemId: String) {
        selectedLevels.remove(itemId)
    }

    private fun downgrade(item: PlayerItem, reason: String, triggerCode: Int): QualityDowngradeEvent? {
        val sources = item.availableSources()
        if (sources.size <= 1) {
            return null
        }
        val currentLevel = (selectedLevels[item.id] ?: 0).coerceIn(0, sources.lastIndex)
        val nextLevel = currentLevel + 1
        if (nextLevel > sources.lastIndex) {
            return null
        }
        selectedLevels[item.id] = nextLevel
        return QualityDowngradeEvent(
            reason = reason,
            triggerCode = triggerCode,
            fromLevel = currentLevel,
            toLevel = nextLevel,
            fromLabel = sources[currentLevel].label,
            toLabel = sources[nextLevel].label
        )
    }

    private companion object {
        const val BUFFER_ACTION_NONE = 0
    }
}

/**
 * ABR 起播配置器。
 *
 * 它负责把火山播放器起播相关的全局配置和实例配置准备好，
 * 让播放器在短剧场景下尽量更快拿到首帧。
 */
class AbrStartupConfigurator(
    private val enabled: Boolean
) {

    /**
     * 为指定播放器实例应用 ABR 起播配置。
     */
    fun configure(player: TTVideoEngine) {
        if (!enabled) {
            return
        }
        initializeGlobalConfig()
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_ENABLE_GEAR_STRATEGY, 1)
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_STAND_ALONG_ABR_START_UP, 1)
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_ENABLE_DASH_ABR, 0)
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_ENABLE_HLS_ABR, 0)
    }

    private fun initializeGlobalConfig() {
        if (initialized) {
            return
        }
        synchronized(this) {
            if (initialized) {
                return
            }
            StrategyManager.instance().startSpeedPredictor()
            StrategyManager.instance().initGearGlobalConfig()
            GearStrategy.getGlobalConfig().apply {
                setIntValue(GearStrategy.KEY_ABR_STARTUP_USE_CACHE, 2)
                GearStrategy.setGlobalConfig(this)
            }
            initialized = true
        }
    }

    private companion object {
        @Volatile
        var initialized = false
    }
}

/**
 * 一次 buffering 过程的内部快照。
 *
 * 它只在优化层内部使用，用来把开始事件和结束事件关联起来。
 */
private data class BufferSnapshot(
    val startedAtMs: Long,
    val count: Int,
    val code: Int,
    val afterFirstFrame: Boolean,
    val action: Int
)

/**
 * 计算一个稳定的缓存 key。
 *
 * 这样即便播放 URL 带有动态签名参数，
 * 预加载和缓存去重仍然能够命中同一条内容。
 */
private fun PlayerItem.stableCacheKey(): String {
    return cacheKey
        ?.takeIf { it.isNotBlank() }
        ?: id.ifBlank { (playUrl ?: title).substringBefore('?') }
}

/**
 * 返回当前播放项所有可选播放源，包含默认源和降级备选源。
 */
private fun PlayerItem.availableSources(): List<PlaybackSource> {
    return buildList {
        add(
            PlaybackSource(
                label = "default",
                playUrl = playUrl,
                strategySource = strategySource,
                cacheKey = cacheKey
            )
        )
        addAll(fallbackSources)
    }
}

/**
 * 读取指定档位的播放源。
 */
private fun PlayerItem.sourceAt(level: Int): PlaybackSource? {
    return availableSources().getOrNull(level)
}
