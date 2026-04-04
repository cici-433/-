package com.startshorts.ad.core

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.AdView
import com.startshorts.ad.api.AdBannerSize
import com.startshorts.ad.api.AdClient
import com.startshorts.ad.api.AdInitConfig
import com.startshorts.ad.api.AdLoadedInfo
import com.startshorts.ad.api.AdPlatform
import com.startshorts.ad.api.AdPriority
import com.startshorts.ad.api.AdUnitParam
import com.startshorts.ad.api.AdFormat as ApiAdFormat
import java.lang.ref.WeakReference
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayDeque

/**
 * AdMob 直连实现。
 *
 * 核心策略：
 * - 加载：同一场景支持多个 adUnitId，按优先级依次尝试，直到成功或全部失败。
 * - 缓存：支持对 INTERSTITIAL / REWARDED_VIDEO / APP_OPEN / NATIVE 做内存缓存；BANNER 不缓存。
 * - 过期：通过 [AdInitConfig.expirationGapsMs] 控制各格式缓存过期时间，过期会被清理并丢弃。
 * - 容器渲染：Native/Banner 由业务传入容器承载；Native 会按约定的 viewId 名称做自动绑定。
 *
 * 说明：
 * - 该实现不依赖项目内的广告聚合库，仅依赖 Google Mobile Ads SDK。
 * - 为避免 lib/ad 依赖 app 的 R 类，Native 绑定通过 viewId 名称运行时查找。
 */
internal class AdmobAdClient : AdClient {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = EnumMap<ApiAdFormat, ArrayDeque<CachedAd>>(ApiAdFormat::class.java)
    private val bannerTagKey: Int = "com.startshorts.ad.banner".hashCode()
    private val nativeTagKey: Int = "com.startshorts.ad.native".hashCode()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initConfig: AdInitConfig? = null

    /**
     * 初始化 AdMob SDK，并根据配置设置测试设备等参数。
     */
    override fun init(context: Context, config: AdInitConfig) {
        require(config.platform == AdPlatform.ADMOB) { "Only ADMOB is supported in AdmobAdClient" }
        appContext = context.applicationContext
        initConfig = config

        config.admobTestDeviceId?.let { testDeviceId ->
            val requestConfiguration = RequestConfiguration.Builder()
                .setTestDeviceIds(listOf(testDeviceId))
                .build()
            MobileAds.setRequestConfiguration(requestConfiguration)
        }

        MobileAds.initialize(context.applicationContext)
    }

    /**
     * 预加载：按格式分组，对可缓存格式进行一次或多次预热。
     *
     * 为了避免对同一格式的多个 adUnitId 重复拉取造成流量浪费：
     * - 每次预热仅会对每个格式选择优先级最高的单元做加载并入缓存。
     */
    override fun preload(adUnits: List<AdUnitParam>, repeat: Int) {
        if (adUnits.isEmpty() || repeat <= 0) return
        val context = appContext ?: return
        val config = initConfig ?: return
        repeat(repeat) {
            adUnits
                .groupBy { it.format }
                .forEach { (format, units) ->
                    when (format) {
                        ApiAdFormat.INTERSTITIAL -> preloadInterstitial(context, config, units)
                        ApiAdFormat.REWARDED_VIDEO -> preloadRewarded(context, config, units)
                        ApiAdFormat.APP_OPEN -> preloadAppOpen(context, config, units)
                        ApiAdFormat.NATIVE -> preloadNative(context, config, units)
                        ApiAdFormat.BANNER -> Unit
                        ApiAdFormat.MEDIA_VIDEO -> Unit
                    }
                }
        }
    }

    override fun showReward(
        activity: Activity,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        maxShowTimeMs: Long,
        onClicked: (() -> Unit)?,
        onShown: (() -> Unit)?,
        onResult: (Boolean) -> Unit
    ) {
        runOnMain {
            val config = initConfig
            val context = appContext
            if (config == null || context == null) {
                onResult(false)
                return@runOnMain
            }

            val timeout = createTimeout(maxShowTimeMs) { onResult(false) }

            val cached = popCachedRewarded(config)
            if (cached != null) {
                showRewarded(activity, sceneId, cached.ad, onClicked, onShown, timeout, onResult)
                return@runOnMain
            }

            loadRewardedSequentially(
                context = context,
                config = config,
                sceneId = sceneId,
                adUnits = adUnits,
                onLoaded = { rewardedAd ->
                    if (timeout.isFinished()) return@loadRewardedSequentially
                    showRewarded(activity, sceneId, rewardedAd, onClicked, onShown, timeout, onResult)
                },
                onFailed = {
                    timeout.finish()
                    onResult(false)
                }
            )
        }
    }

    /**
     * 展示插屏：优先使用缓存；无缓存则按优先级依次尝试加载并展示。
     */
    override fun showInterstitial(
        activity: Activity,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        maxShowTimeMs: Long,
        onClicked: (() -> Unit)?,
        onShown: (() -> Unit)?,
        onComplete: ((Boolean) -> Unit)?
    ) {
        runOnMain {
            val config = initConfig
            val context = appContext
            if (config == null || context == null) {
                onComplete?.invoke(false)
                return@runOnMain
            }

            val timeout = createTimeout(maxShowTimeMs) { onComplete?.invoke(false) }

            val cached = popCachedInterstitial(config)
            if (cached != null) {
                showInterstitial(activity, sceneId, cached.ad, onClicked, onShown, timeout) { showed ->
                    onComplete?.invoke(showed)
                }
                return@runOnMain
            }

            loadInterstitialSequentially(
                context = context,
                config = config,
                sceneId = sceneId,
                adUnits = adUnits,
                onLoaded = { interstitialAd ->
                    if (timeout.isFinished()) return@loadInterstitialSequentially
                    showInterstitial(activity, sceneId, interstitialAd, onClicked, onShown, timeout) { showed ->
                        onComplete?.invoke(showed)
                    }
                },
                onFailed = {
                    timeout.finish()
                    onComplete?.invoke(false)
                }
            )
        }
    }

    /**
     * 展示开屏：优先使用缓存；无缓存则按优先级依次尝试加载并展示。
     */
    override fun showAppOpen(
        activity: Activity,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        maxShowTimeMs: Long,
        onClicked: (() -> Unit)?,
        onShown: (() -> Unit)?,
        onComplete: ((Boolean) -> Unit)?
    ) {
        runOnMain {
            val config = initConfig
            val context = appContext
            if (config == null || context == null) {
                onComplete?.invoke(false)
                return@runOnMain
            }

            val timeout = createTimeout(maxShowTimeMs) { onComplete?.invoke(false) }

            val cached = popCachedAppOpen(config)
            if (cached != null) {
                showAppOpen(activity, sceneId, cached.ad, onClicked, onShown, timeout) { showed ->
                    onComplete?.invoke(showed)
                }
                return@runOnMain
            }

            loadAppOpenSequentially(
                context = context,
                config = config,
                sceneId = sceneId,
                adUnits = adUnits,
                onLoaded = { appOpenAd ->
                    if (timeout.isFinished()) return@loadAppOpenSequentially
                    showAppOpen(activity, sceneId, appOpenAd, onClicked, onShown, timeout) { showed ->
                        onComplete?.invoke(showed)
                    }
                },
                onFailed = {
                    timeout.finish()
                    onComplete?.invoke(false)
                }
            )
        }
    }

    /**
     * 展示原生：优先使用缓存；无缓存则按优先级依次尝试加载并渲染到容器。
     *
     * - 为避免内存泄漏与旧广告残留，会清理容器内旧的 NativeAd（若由本模块设置过）。
     * - 渲染过程中如果布局缺失必要的 NativeAdView，则会销毁本次 NativeAd 并放弃展示。
     */
    override fun showNative(
        activityRef: WeakReference<Activity>,
        adContainer: ViewGroup,
        adLayoutResId: Int,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        onClicked: (() -> Unit)?,
        onShown: (() -> Unit)?
    ) {
        runOnMain {
            val config = initConfig
            val context = appContext
            val activity = activityRef.get()
            if (config == null || context == null || activity == null) return@runOnMain

            val existing = adContainer.getTag(nativeTagKey)
            if (existing is NativeAd) {
                existing.destroy()
            }
            adContainer.setTag(nativeTagKey, null)
            adContainer.removeAllViews()

            val cached = popCachedNative(config)
            if (cached != null) {
                val view = inflateAndBindNative(activity, adContainer, adLayoutResId, cached.ad)
                if (view != null) {
                    adContainer.setTag(nativeTagKey, cached.ad)
                    onShown?.invoke()
                } else {
                    cached.ad.destroy()
                }
                return@runOnMain
            }

            loadNativeSequentially(
                context = context,
                config = config,
                sceneId = sceneId,
                adUnits = adUnits,
                onLoaded = { nativeAd ->
                    val view = inflateAndBindNative(activity, adContainer, adLayoutResId, nativeAd)
                    if (view != null) {
                        adContainer.setTag(nativeTagKey, nativeAd)
                        onShown?.invoke()
                    } else {
                        nativeAd.destroy()
                    }
                },
                onClicked = onClicked
            )
        }
    }

    /**
     * 展示横幅：每次调用都会替换容器内旧的 AdView（若由本模块设置过），并发起新的加载。
     */
    override fun showBanner(
        activityRef: WeakReference<Activity>,
        adContainer: ViewGroup,
        adSize: AdBannerSize,
        maxShowTimeMs: Long,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        onClicked: (() -> Unit)?,
        onShown: (() -> Unit)?
    ) {
        runOnMain {
            val config = initConfig
            val activity = activityRef.get()
            if (config == null || activity == null) return@runOnMain
            val adUnit = adUnits.filter { it.format == ApiAdFormat.BANNER }.sortedByPriority().firstOrNull() ?: return@runOnMain

            val existing = adContainer.getTag(bannerTagKey)
            if (existing is AdView) {
                existing.destroy()
            }
            adContainer.setTag(bannerTagKey, null)
            adContainer.removeAllViews()

            val adView = AdView(activity)
            adContainer.setTag(bannerTagKey, adView)
            adView.adUnitId = adUnit.adUnitId
            adView.setAdSize(adSize.toAdSize())
            adView.adListener = object : AdListener() {
                override fun onAdLoaded() {
                    onShown?.invoke()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {}

                override fun onAdClicked() {
                    onClicked?.invoke()
                }
            }
            adContainer.addView(
                adView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            adView.loadAd(AdRequest.Builder().build())
        }
    }

    /**
     * 查询缓存：会先清理过期缓存，再返回结果。
     */
    override fun hasCache(vararg formats: ApiAdFormat): Boolean {
        val config = initConfig ?: return false
        return formats.any { format ->
            cleanupExpired(config, format)
            (cache[format]?.isNotEmpty() == true)
        }
    }

    /**
     * 查询缓存数量：会先清理过期缓存，再返回数量。
     */
    override fun getCachedCount(vararg formats: ApiAdFormat): Int {
        val config = initConfig ?: return 0
        return formats.sumOf { format ->
            cleanupExpired(config, format)
            cache[format]?.size ?: 0
        }
    }

    private fun preloadInterstitial(context: Context, config: AdInitConfig, adUnits: List<AdUnitParam>) {
        val unit = adUnits.filter { it.format == ApiAdFormat.INTERSTITIAL }.sortedByPriority().firstOrNull() ?: return
        loadInterstitialSequentially(
            context = context,
            config = config,
            sceneId = "preload",
            adUnits = listOf(unit),
            onLoaded = { ad -> pushCached(config, CachedInterstitial(unit, SystemClock.elapsedRealtime(), ad)) },
            onFailed = {}
        )
    }

    private fun preloadRewarded(context: Context, config: AdInitConfig, adUnits: List<AdUnitParam>) {
        val unit = adUnits.filter { it.format == ApiAdFormat.REWARDED_VIDEO }.sortedByPriority().firstOrNull() ?: return
        loadRewardedSequentially(
            context = context,
            config = config,
            sceneId = "preload",
            adUnits = listOf(unit),
            onLoaded = { ad -> pushCached(config, CachedRewarded(unit, SystemClock.elapsedRealtime(), ad)) },
            onFailed = {}
        )
    }

    private fun preloadAppOpen(context: Context, config: AdInitConfig, adUnits: List<AdUnitParam>) {
        val unit = adUnits.filter { it.format == ApiAdFormat.APP_OPEN }.sortedByPriority().firstOrNull() ?: return
        loadAppOpenSequentially(
            context = context,
            config = config,
            sceneId = "preload",
            adUnits = listOf(unit),
            onLoaded = { ad -> pushCached(config, CachedAppOpen(unit, SystemClock.elapsedRealtime(), ad)) },
            onFailed = {}
        )
    }

    private fun preloadNative(context: Context, config: AdInitConfig, adUnits: List<AdUnitParam>) {
        val unit = adUnits.filter { it.format == ApiAdFormat.NATIVE }.sortedByPriority().firstOrNull() ?: return
        loadNativeSequentially(
            context = context,
            config = config,
            sceneId = "preload",
            adUnits = listOf(unit),
            onLoaded = { ad -> pushCached(config, CachedNative(unit, SystemClock.elapsedRealtime(), ad)) },
            onClicked = null
        )
    }

    private fun loadInterstitialSequentially(
        context: Context,
        config: AdInitConfig,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        onLoaded: (InterstitialAd) -> Unit,
        onFailed: () -> Unit
    ) {
        val units = adUnits.filter { it.format == ApiAdFormat.INTERSTITIAL }.sortedByPriority()
        loadSequentially(
            units = units,
            request = { unit, done ->
                val start = SystemClock.elapsedRealtime()
                config.loadListener?.onAdRequest(unit)
                InterstitialAd.load(
                    context,
                    unit.adUnitId,
                    AdRequest.Builder().build(),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            config.loadListener?.onAdLoaded(
                                AdLoadedInfo(
                                    adUnitId = unit.adUnitId,
                                    format = ApiAdFormat.INTERSTITIAL,
                                    name = unit.name,
                                    mediationName = ad.responseInfo?.mediationAdapterClassName
                                ),
                                SystemClock.elapsedRealtime() - start,
                                ad.responseInfo?.mediationAdapterClassName.orEmpty(),
                                false
                            )
                            done(Result.success(ad))
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            config.loadListener?.onAdFailedToLoad(
                                unit,
                                error.code,
                                error.message,
                                error.responseInfo?.mediationAdapterClassName.orEmpty()
                            )
                            done(Result.failure(RuntimeException(error.message)))
                        }
                    }
                )
            },
            onLoaded = onLoaded,
            onFailed = onFailed
        )
    }

    private fun loadRewardedSequentially(
        context: Context,
        config: AdInitConfig,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        onLoaded: (RewardedAd) -> Unit,
        onFailed: () -> Unit
    ) {
        val units = adUnits.filter { it.format == ApiAdFormat.REWARDED_VIDEO }.sortedByPriority()
        loadSequentially(
            units = units,
            request = { unit, done ->
                val start = SystemClock.elapsedRealtime()
                config.loadListener?.onAdRequest(unit)
                RewardedAd.load(
                    context,
                    unit.adUnitId,
                    AdRequest.Builder().build(),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedAd) {
                            config.loadListener?.onAdLoaded(
                                AdLoadedInfo(
                                    adUnitId = unit.adUnitId,
                                    format = ApiAdFormat.REWARDED_VIDEO,
                                    name = unit.name,
                                    mediationName = ad.responseInfo?.mediationAdapterClassName
                                ),
                                SystemClock.elapsedRealtime() - start,
                                ad.responseInfo?.mediationAdapterClassName.orEmpty(),
                                false
                            )
                            done(Result.success(ad))
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            config.loadListener?.onAdFailedToLoad(
                                unit,
                                error.code,
                                error.message,
                                error.responseInfo?.mediationAdapterClassName.orEmpty()
                            )
                            done(Result.failure(RuntimeException(error.message)))
                        }
                    }
                )
            },
            onLoaded = onLoaded,
            onFailed = onFailed
        )
    }

    private fun loadAppOpenSequentially(
        context: Context,
        config: AdInitConfig,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        onLoaded: (AppOpenAd) -> Unit,
        onFailed: () -> Unit
    ) {
        val units = adUnits.filter { it.format == ApiAdFormat.APP_OPEN }.sortedByPriority()
        loadSequentially(
            units = units,
            request = { unit, done ->
                val start = SystemClock.elapsedRealtime()
                config.loadListener?.onAdRequest(unit)
                AppOpenAd.load(
                    context,
                    unit.adUnitId,
                    AdRequest.Builder().build(),
                    AppOpenAd.APP_OPEN_AD_ORIENTATION_PORTRAIT,
                    object : AppOpenAd.AppOpenAdLoadCallback() {
                        override fun onAdLoaded(ad: AppOpenAd) {
                            config.loadListener?.onAdLoaded(
                                AdLoadedInfo(
                                    adUnitId = unit.adUnitId,
                                    format = ApiAdFormat.APP_OPEN,
                                    name = unit.name,
                                    mediationName = ad.responseInfo?.mediationAdapterClassName
                                ),
                                SystemClock.elapsedRealtime() - start,
                                ad.responseInfo?.mediationAdapterClassName.orEmpty(),
                                false
                            )
                            done(Result.success(ad))
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            config.loadListener?.onAdFailedToLoad(
                                unit,
                                error.code,
                                error.message,
                                error.responseInfo?.mediationAdapterClassName.orEmpty()
                            )
                            done(Result.failure(RuntimeException(error.message)))
                        }
                    }
                )
            },
            onLoaded = onLoaded,
            onFailed = onFailed
        )
    }

    private fun loadNativeSequentially(
        context: Context,
        config: AdInitConfig,
        sceneId: String,
        adUnits: List<AdUnitParam>,
        onLoaded: (NativeAd) -> Unit,
        onClicked: (() -> Unit)?
    ) {
        val units = adUnits.filter { it.format == ApiAdFormat.NATIVE }.sortedByPriority()
        loadSequentially(
            units = units,
            request = { unit, done ->
                val start = SystemClock.elapsedRealtime()
                config.loadListener?.onAdRequest(unit)
                val builder = AdLoader.Builder(context, unit.adUnitId)
                    .forNativeAd { nativeAd ->
                        config.loadListener?.onAdLoaded(
                            AdLoadedInfo(
                                adUnitId = unit.adUnitId,
                                format = ApiAdFormat.NATIVE,
                                name = unit.name,
                                mediationName = nativeAd.responseInfo?.mediationAdapterClassName
                            ),
                            SystemClock.elapsedRealtime() - start,
                            nativeAd.responseInfo?.mediationAdapterClassName.orEmpty(),
                            false
                        )
                        done(Result.success(nativeAd))
                    }
                    .withAdListener(
                        object : AdListener() {
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                config.loadListener?.onAdFailedToLoad(
                                    unit,
                                    error.code,
                                    error.message,
                                    error.responseInfo?.mediationAdapterClassName.orEmpty()
                                )
                                done(Result.failure(RuntimeException(error.message)))
                            }

                            override fun onAdClicked() {
                                onClicked?.invoke()
                            }
                        }
                    )
                    .withNativeAdOptions(
                        NativeAdOptions.Builder()
                            .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_BOTTOM_RIGHT)
                            .build()
                    )
                builder.build().loadAd(AdRequest.Builder().build())
            },
            onLoaded = onLoaded,
            onFailed = {}
        )
    }

    private fun showInterstitial(
        activity: Activity,
        sceneId: String,
        ad: InterstitialAd,
        onClicked: (() -> Unit)?,
        onShown: (() -> Unit)?,
        timeout: Timeout,
        onComplete: (Boolean) -> Unit
    ) {
        val showed = AtomicBoolean(false)
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                if (timeout.isFinished()) return
                showed.set(true)
                onShown?.invoke()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                if (timeout.finish()) {
                    onComplete(false)
                }
            }

            override fun onAdDismissedFullScreenContent() {
                if (timeout.finish()) {
                    onComplete(showed.get())
                }
            }

            override fun onAdClicked() {
                if (timeout.isFinished()) return
                onClicked?.invoke()
            }
        }
        ad.onPaidEventListener = OnPaidEventListener { _: AdValue -> }
        ad.show(activity)
    }

    private fun showAppOpen(
        activity: Activity,
        sceneId: String,
        ad: AppOpenAd,
        onClicked: (() -> Unit)?,
        onShown: (() -> Unit)?,
        timeout: Timeout,
        onComplete: (Boolean) -> Unit
    ) {
        val showed = AtomicBoolean(false)
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                if (timeout.isFinished()) return
                showed.set(true)
                onShown?.invoke()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                if (timeout.finish()) {
                    onComplete(false)
                }
            }

            override fun onAdDismissedFullScreenContent() {
                if (timeout.finish()) {
                    onComplete(showed.get())
                }
            }

            override fun onAdClicked() {
                if (timeout.isFinished()) return
                onClicked?.invoke()
            }
        }
        ad.onPaidEventListener = OnPaidEventListener { _: AdValue -> }
        ad.show(activity)
    }

    private fun showRewarded(
        activity: Activity,
        sceneId: String,
        ad: RewardedAd,
        onClicked: (() -> Unit)?,
        onShown: (() -> Unit)?,
        timeout: Timeout,
        onResult: (Boolean) -> Unit
    ) {
        val rewardEarned = AtomicBoolean(false)
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                if (timeout.isFinished()) return
                onShown?.invoke()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                if (timeout.finish()) {
                    onResult(false)
                }
            }

            override fun onAdDismissedFullScreenContent() {
                if (timeout.finish()) {
                    onResult(rewardEarned.get())
                }
            }

            override fun onAdClicked() {
                if (timeout.isFinished()) return
                onClicked?.invoke()
            }
        }
        ad.onPaidEventListener = OnPaidEventListener { _: AdValue -> }
        ad.show(activity) { _: RewardItem ->
            rewardEarned.set(true)
        }
    }

    private fun inflateAndBindNative(
        activity: Activity,
        container: ViewGroup,
        adLayoutResId: Int,
        nativeAd: NativeAd
    ): View? {
        val root = LayoutInflater.from(activity).inflate(adLayoutResId, container, false)
        val nativeAdView = findNativeAdView(activity, root) ?: return null

        val titleTv = nativeAdView.findViewById<View?>(activity.findId("title_tv")) as? TextView
        val ctaTv = nativeAdView.findViewById<View?>(activity.findId("cta_tv")) as? TextView
        val contentTv = nativeAdView.findViewById<View?>(activity.findId("content_tv")) as? TextView
        val appIconIv = nativeAdView.findViewById<View?>(activity.findId("app_icon")) as? ImageView
        val mediaView = nativeAdView.findViewById<View?>(activity.findId("media_view")) as? MediaView

        if (mediaView != null) {
            nativeAdView.mediaView = mediaView
        }
        if (titleTv != null) {
            nativeAdView.headlineView = titleTv
            titleTv.text = nativeAd.headline
        }
        if (ctaTv != null) {
            nativeAdView.callToActionView = ctaTv
            ctaTv.text = nativeAd.callToAction
            ctaTv.visibility = if (nativeAd.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        if (contentTv != null) {
            nativeAdView.bodyView = contentTv
            contentTv.text = nativeAd.body
            contentTv.visibility = if (nativeAd.body.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        if (appIconIv != null) {
            nativeAdView.iconView = appIconIv
            val drawable = nativeAd.icon?.drawable
            appIconIv.setImageDrawable(drawable)
            appIconIv.visibility = if (drawable == null) View.GONE else View.VISIBLE
        }
        nativeAdView.setNativeAd(nativeAd)
        container.addView(root)
        return root
    }

    private fun findNativeAdView(activity: Activity, root: View): NativeAdView? {
        val id = activity.findId("native_ad_view")
        val fromId = if (id != 0) root.findViewById<View?>(id) else null
        if (fromId is NativeAdView) return fromId
        if (root is NativeAdView) return root
        return null
    }

    private fun pushCached(config: AdInitConfig, ad: CachedAd) {
        cleanupExpired(config, ad.unit.format)
        val deque = cache.getOrPut(ad.unit.format) { ArrayDeque() }
        val max = maxOf(1, ad.unit.maxSize)
        while (deque.size >= max) {
            deque.removeFirst().destroy()
        }
        deque.addLast(ad)
    }

    private fun popCachedInterstitial(config: AdInitConfig): CachedInterstitial? {
        return popCached(config, ApiAdFormat.INTERSTITIAL) as? CachedInterstitial
    }

    private fun popCachedRewarded(config: AdInitConfig): CachedRewarded? {
        return popCached(config, ApiAdFormat.REWARDED_VIDEO) as? CachedRewarded
    }

    private fun popCachedAppOpen(config: AdInitConfig): CachedAppOpen? {
        return popCached(config, ApiAdFormat.APP_OPEN) as? CachedAppOpen
    }

    private fun popCachedNative(config: AdInitConfig): CachedNative? {
        return popCached(config, ApiAdFormat.NATIVE) as? CachedNative
    }

    private fun popCached(config: AdInitConfig, format: ApiAdFormat): CachedAd? {
        cleanupExpired(config, format)
        return cache[format]?.removeFirstOrNull()
    }

    private fun cleanupExpired(config: AdInitConfig, format: ApiAdFormat) {
        val gap = config.expirationGapsMs[format] ?: return
        if (gap <= 0) return
        val deque = cache[format] ?: return
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val first = deque.firstOrNull() ?: break
            if (now - first.loadedAtMs > gap) {
                deque.removeFirst().destroy()
            } else {
                break
            }
        }
    }

    private fun createTimeout(maxShowTimeMs: Long, onTimeout: () -> Unit): Timeout {
        if (maxShowTimeMs <= 0) return Timeout.disabled()
        val finished = AtomicBoolean(false)
        val r = Runnable {
            if (finished.compareAndSet(false, true)) {
                onTimeout()
            }
        }
        mainHandler.postDelayed(r, maxShowTimeMs)
        return Timeout(enabled = true, finished = finished) {
            mainHandler.removeCallbacks(r)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun Activity.findId(name: String): Int {
        return resources.getIdentifier(name, "id", packageName)
    }

    private fun List<AdUnitParam>.sortedByPriority(): List<AdUnitParam> {
        return sortedWith(compareBy({ it.priority.rank() }, { it.adUnitId }))
    }

    private fun AdPriority.rank(): Int {
        return when (this) {
            AdPriority.HIGH -> 0
            AdPriority.MID -> 1
            AdPriority.LOW -> 2
        }
    }

    private fun AdBannerSize.toAdSize(): AdSize {
        return when (this) {
            AdBannerSize.BANNER -> AdSize.BANNER
            AdBannerSize.LARGE_BANNER -> AdSize.LARGE_BANNER
            AdBannerSize.MEDIUM_RECTANGLE -> AdSize.MEDIUM_RECTANGLE
            AdBannerSize.FULL_BANNER -> AdSize.FULL_BANNER
            AdBannerSize.LEADERBOARD -> AdSize.LEADERBOARD
        }
    }

    private fun <T> loadSequentially(
        units: List<AdUnitParam>,
        request: (AdUnitParam, (Result<T>) -> Unit) -> Unit,
        onLoaded: (T) -> Unit,
        onFailed: () -> Unit
    ) {
        if (units.isEmpty()) {
            onFailed()
            return
        }
        fun next(index: Int) {
            if (index >= units.size) {
                onFailed()
                return
            }
            request(units[index]) { result ->
                result.onSuccess { ad ->
                    onLoaded(ad)
                }.onFailure {
                    next(index + 1)
                }
            }
        }
        next(0)
    }

    private sealed interface CachedAd {
        val unit: AdUnitParam
        val loadedAtMs: Long
        fun destroy()
    }

    private data class CachedInterstitial(
        override val unit: AdUnitParam,
        override val loadedAtMs: Long,
        val ad: InterstitialAd
    ) : CachedAd {
        override fun destroy() = Unit
    }

    private data class CachedRewarded(
        override val unit: AdUnitParam,
        override val loadedAtMs: Long,
        val ad: RewardedAd
    ) : CachedAd {
        override fun destroy() = Unit
    }

    private data class CachedAppOpen(
        override val unit: AdUnitParam,
        override val loadedAtMs: Long,
        val ad: AppOpenAd
    ) : CachedAd {
        override fun destroy() = Unit
    }

    private data class CachedNative(
        override val unit: AdUnitParam,
        override val loadedAtMs: Long,
        val ad: NativeAd
    ) : CachedAd {
        override fun destroy() = ad.destroy()
    }

    private class Timeout(
        private val enabled: Boolean,
        private val finished: AtomicBoolean,
        private val cancel: () -> Unit
    ) {
        fun finish(): Boolean {
            if (!enabled) return true
            if (!finished.compareAndSet(false, true)) return false
            cancel()
            return true
        }

        fun isFinished(): Boolean {
            return enabled && finished.get()
        }

        companion object {
            fun disabled(): Timeout = Timeout(false, AtomicBoolean(true)) {}
        }
    }
}
