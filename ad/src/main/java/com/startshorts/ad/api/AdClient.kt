package com.startshorts.ad.api

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import java.lang.ref.WeakReference

/**
 * lib/ad 的统一广告能力接口。
 *
 * 设计目标：
 * - 业务侧只关注「场景(sceneId)」与「候选广告单元(adUnits)」，不直接依赖具体 SDK 的类。
 * - 同一场景可以配置多个 adUnitId，并按优先级依次尝试加载/展示。
 * - 原生/横幅以容器承载（嵌入式）；插屏/激励/开屏为全屏展示（弹框/覆盖）。
 *
 * 线程约束：
 * - 建议在主线程调用，内部实现会切换到主线程执行与 UI 相关的操作。
 */
interface AdClient {
    /**
     * 初始化广告 SDK 与模块内部状态。
     *
     * - 需要传入 Application Context（内部会自动取 applicationContext）。
     * - 需要在应用启动阶段尽早调用，避免首次展示时初始化导致额外耗时。
     */
    fun init(context: Context, config: AdInitConfig)

    /**
     * 预加载广告到模块缓存。
     *
     * - 对全屏类广告（插屏/激励/开屏/原生）会尝试预加载并缓存；横幅不会缓存。
     * - repeat 表示对同一批 adUnits 重复预热的次数（常用于冷启动后加速首次展示）。
     */
    fun preload(adUnits: List<AdUnitParam>, repeat: Int = 1)

    /**
     * 展示激励广告。
     *
     * @param sceneId 业务侧场景标识，用于配置与埋点（模块本身不做强约束）。
     * @param adUnits 当前场景的候选广告单元（同一格式可配置多个 adUnitId 作为兜底）。
     * @param maxShowTimeMs 最大等待时长（包含加载与展示链路的等待），<= 0 表示不限制。
     * @param onResult true 表示用户获得激励；false 表示未获得（失败/未完成/超时等）。
     */
    fun showReward(
        activity: Activity,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        maxShowTimeMs: Long = -1,
        onClicked: (() -> Unit)? = null,
        onShown: (() -> Unit)? = null,
        onResult: (Boolean) -> Unit
    )

    /**
     * 展示插屏广告。
     *
     * @param onComplete true 表示本次确实展示成功并结束；false 表示失败或未展示。
     */
    fun showInterstitial(
        activity: Activity,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        maxShowTimeMs: Long = -1,
        onClicked: (() -> Unit)? = null,
        onShown: (() -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null
    )

    /**
     * 展示开屏广告（App Open）。
     *
     * @param onComplete true 表示本次确实展示成功并结束；false 表示失败或未展示。
     */
    fun showAppOpen(
        activity: Activity,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        maxShowTimeMs: Long = -1,
        onClicked: (() -> Unit)? = null,
        onShown: (() -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null
    )

    /**
     * 在容器内展示原生广告（Native）。
     *
     * @param adLayoutResId 业务侧提供的原生广告布局资源 ID。
     *
     * 布局约定：
     * - 需要包含一个 NativeAdView，且 id 名称为 "native_ad_view"
     * - 其他可选子 View（若存在将被自动绑定）："media_view"、"title_tv"、"cta_tv"、"content_tv"、"app_icon"
     */
    fun showNative(
        activityRef: WeakReference<Activity>,
        adContainer: ViewGroup,
        adLayoutResId: Int,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        onClicked: (() -> Unit)? = null,
        onShown: (() -> Unit)? = null
    )

    /**
     * 在容器内展示横幅广告（Banner）。
     *
     * @param adSize 横幅尺寸枚举，内部会转换为 AdMob SDK 的 AdSize。
     * @param maxShowTimeMs 最大等待时长，<= 0 表示不限制。
     */
    fun showBanner(
        activityRef: WeakReference<Activity>,
        adContainer: ViewGroup,
        adSize: AdBannerSize,
        maxShowTimeMs: Long,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        onClicked: (() -> Unit)? = null,
        onShown: (() -> Unit)? = null
    )

    /**
     * 是否存在指定格式的可用缓存（过期缓存会被清理）。
     */
    fun hasCache(vararg formats: AdFormat): Boolean

    /**
     * 获取指定格式的可用缓存数量（过期缓存会被清理）。
     */
    fun getCachedCount(vararg formats: AdFormat): Int
}

/**
 * 广告平台枚举。
 *
 * 当前 lib/ad 的实现以直连 AdMob 为主，TRAD_PLUS 仅保留为历史兼容字段。
 */
enum class AdPlatform {
    ADMOB,
    TRAD_PLUS
}

/**
 * 广告格式枚举。
 */
enum class AdFormat {
    REWARDED_VIDEO,
    INTERSTITIAL,
    APP_OPEN,
    NATIVE,
    BANNER,
    MEDIA_VIDEO
}

/**
 * 广告单元优先级：同一场景多个 adUnitId 会按优先级由高到低依次尝试。
 */
enum class AdPriority {
    HIGH,
    MID,
    LOW
}

/**
 * Banner 尺寸枚举：与 AdMob SDK 内置尺寸一一对应。
 */
enum class AdBannerSize {
    BANNER,
    LARGE_BANNER,
    MEDIUM_RECTANGLE,
    FULL_BANNER,
    LEADERBOARD
}

/**
 * 单个广告单元的请求参数。
 *
 * @param maxSize 模块侧缓存上限（用于 preload 缓存队列长度控制），默认 1。
 * @param pam 预留字段（此前用于聚合/增强能力），直连 AdMob 场景下不做处理。
 * @param imaLoadVideoTimeoutMs 预留字段（贴片/媒体视频加载超时），直连 AdMob 场景下不做处理。
 */
data class AdUnitParam(
    val adUnitId: String,
    val format: AdFormat,
    val priority: AdPriority = AdPriority.MID,
    val maxSize: Int = 1,
    val name: String? = null,
    val pam: Boolean = false,
    val imaLoadVideoTimeoutMs: Long = 0L
)

/**
 * 广告模块初始化配置。
 *
 * - admobTestDeviceId：用于将某设备标记为测试设备，避免真实计费与流量污染。
 * - expirationGapsMs：各格式缓存的过期时间（单位 ms）；当缓存超过该时长会被丢弃。
 * - loadListener/logger：用于把模块内的加载/失败等事件回传给业务侧埋点与日志系统。
 *
 * 直连 AdMob 实现下：
 * - pamRemoteConfigHolder / valueRecorder / tradPlusAppId / deviceIdProvider / metaInHouseUrl 为历史兼容字段，不一定生效。
 */
data class AdInitConfig(
    val platform: AdPlatform,
    val debug: Boolean = false,
    val toastAdImpressionInfo: Boolean = false,
    val admobTestDeviceId: String? = null,
    val expirationGapsMs: Map<AdFormat, Long> = emptyMap(),
    val pamRemoteConfigHolder: (() -> String)? = null,
    val valueRecorder: AdValueRecorder? = null,
    val tradPlusAppId: String? = null,
    val deviceIdProvider: (() -> String)? = null,
    val metaInHouseUrl: String? = null,
    val loadListener: AdLoadListener? = null,
    val logger: AdLogger? = null
)

/**
 * Key-Value 存储抽象（历史兼容字段）。
 */
interface AdValueRecorder {
    fun getString(key: String): String?
    fun setString(key: String, value: String)
}

/**
 * 日志抽象：由业务侧注入，用于对接既有 Logger 实现。
 */
interface AdLogger {
    fun debug(tag: String?, message: String?)
    fun info(tag: String?, message: String?)
    fun error(tag: String?, message: String?)
}

/**
 * 广告加载成功后返回给业务侧的关键信息，用于统计与埋点。
 */
data class AdLoadedInfo(
    val adUnitId: String,
    val format: AdFormat,
    val name: String?,
    val mediationName: String?
)

/**
 * 加载监听：用于采集广告请求/填充/失败信息。
 */
interface AdLoadListener {
    /**
     * 开始请求某个广告单元（一次尝试）。
     */
    fun onAdRequest(adUnit: AdUnitParam)

    /**
     * 某个广告单元加载成功。
     *
     * @param costTimeMs 从发起请求到成功回调的耗时
     * @param mediationName 对于聚合/中介场景，可用于区分最终填充来源；直连 AdMob 时通常为 adapter className
     * @param isAuto 预留字段：表示是否为 SDK 自动拉取（直连实现一般为 false）
     */
    fun onAdLoaded(ad: AdLoadedInfo, costTimeMs: Long, mediationName: String, isAuto: Boolean)

    /**
     * 某个广告单元加载失败（一次尝试）。
     */
    fun onAdFailedToLoad(adUnit: AdUnitParam, errorCode: Int, errorMsg: String, mediationName: String)
}
