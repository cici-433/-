# lib/ad

该模块提供项目内统一的广告能力接口（`AdClient`），当前实现为直连 AdMob（Google Mobile Ads SDK），不依赖 `com.hades.aar.kotlin:ad-manager` 等聚合库。

## 依赖

`lib/ad/build.gradle` 已内置：

- `com.google.android.gms:play-services-ads`

业务侧仍需要保证：

- App 的 `AndroidManifest.xml` 中已配置 AdMob App ID（`com.google.android.gms.ads.APPLICATION_ID`）
- 已正确集成 Google Mobile Ads SDK 的基础依赖与初始化时机（本模块会在 `AdClient.init` 内执行 `MobileAds.initialize`）

## 快速接入

### 1) 初始化

```kotlin
val adClient = AdClientFactory.createAdClient()
adClient.init(
    context = applicationContext,
    config = AdInitConfig(
        platform = AdPlatform.ADMOB,
        admobTestDeviceId = "YOUR_TEST_DEVICE_ID",
        expirationGapsMs = mapOf(
            AdFormat.INTERSTITIAL to 60_000L,
            AdFormat.REWARDED_VIDEO to 60_000L,
            AdFormat.APP_OPEN to 60_000L,
            AdFormat.NATIVE to 60_000L,
        ),
        loadListener = object : AdLoadListener {
            override fun onAdRequest(adUnit: AdUnitParam) {}
            override fun onAdLoaded(ad: AdLoadedInfo, costTimeMs: Long, mediationName: String, isAuto: Boolean) {}
            override fun onAdFailedToLoad(adUnit: AdUnitParam, errorCode: Int, errorMsg: String, mediationName: String) {}
        }
    )
)
```

### 2) 预加载

```kotlin
adClient.preload(
    adUnits = listOf(
        AdUnitParam(adUnitId = "xxx", format = AdFormat.INTERSTITIAL, priority = AdPriority.HIGH),
        AdUnitParam(adUnitId = "yyy", format = AdFormat.REWARDED_VIDEO, priority = AdPriority.HIGH),
    ),
    repeat = 1
)
```

### 3) 展示（全屏类）

```kotlin
adClient.showInterstitial(
    activity = activity,
    sceneId = "home_exit",
    adUnits = listOf(
        AdUnitParam(adUnitId = "interstitial_1", format = AdFormat.INTERSTITIAL, priority = AdPriority.HIGH),
        AdUnitParam(adUnitId = "interstitial_2", format = AdFormat.INTERSTITIAL, priority = AdPriority.MID),
    ),
    maxShowTimeMs = 6_000,
    onComplete = { showed -> }
)

adClient.showReward(
    activity = activity,
    sceneId = "unlock",
    adUnits = listOf(
        AdUnitParam(adUnitId = "reward_1", format = AdFormat.REWARDED_VIDEO, priority = AdPriority.HIGH),
        AdUnitParam(adUnitId = "reward_2", format = AdFormat.REWARDED_VIDEO, priority = AdPriority.MID),
    ),
    maxShowTimeMs = 10_000,
    onResult = { rewarded -> }
)
```

## 原生广告布局约定

`showNative` 需要业务侧提供一个 Native 布局资源 `adLayoutResId`，且布局内需要满足以下约定（按 id 名称匹配，而不是 R.id 常量）：

- 必须存在 `NativeAdView` 且 id 名称为：`native_ad_view`
- 可选子 View（若存在会自动绑定）：
  - `media_view`（MediaView）
  - `title_tv`（TextView）
  - `cta_tv`（TextView）
  - `content_tv`（TextView）
  - `app_icon`（ImageView）

示例调用：

```kotlin
adClient.showNative(
    activityRef = WeakReference(activity),
    adContainer = container,
    adLayoutResId = R.layout.view_native_ad_admob_1,
    sceneId = "feed",
    adUnits = listOf(
        AdUnitParam(adUnitId = "native_1", format = AdFormat.NATIVE, priority = AdPriority.HIGH),
        AdUnitParam(adUnitId = "native_2", format = AdFormat.NATIVE, priority = AdPriority.MID),
    )
)
```

## 缓存与超时语义

- 缓存仅在内存中维护，进程被杀后丢失。
- 缓存过期由 `AdInitConfig.expirationGapsMs` 控制。
- `maxShowTimeMs` 为模块侧的“最长等待时长”，超时后会回调失败并忽略后续成功回调（不会强行取消底层 SDK 请求）。

