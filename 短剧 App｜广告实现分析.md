# 短剧 App｜广告实现分析

本文面向“读代码理解实现”，聚焦 shortTV Android 工程中广告体系的核心模块、配置化策略、展示链路、频控与合规模块（GDPR/CCPA）如何协同。

## 1. 入口与依赖

### 1.1 主要依赖

- 广告聚合/封装库：`com.hades.aar.kotlin:ad-manager`
  - 业务侧通过 `AdUtil.loadAdSync(...)` / `AdUtil.show(...)` 统一调用，不直接持有 Admob/TradPlus 的具体 SDK。
- 归因/活动：`dev.deeplink.sdk:attribution`（与广告投放链路、campaign 用户识别有关，但不直接负责广告展示）

依赖声明位置：[app/build.gradle](file:///Users/chenxunlin/trace-workspace/shortTV/app/build.gradle#L153-L181)

### 1.2 初始化入口

应用启动阶段通过 AndroidX Startup 初始化配置与广告：

- [ConfigureInitializer.init](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/startup/ConfigureInitializer.kt#L26-L44) 调用 [AdManager.init](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L120-L221)

`AdManager.init` 的核心职责：

- 根据实验桶选择聚合平台：Admob 或 TradPlus（见 [ABTestFactory.mTradPlusPlatform](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/abtest/ABTestFactory.kt#L68-L76)）
- 组装对应平台的配置对象（AdmobConfig / TradPlusConfig），统一设置：
  - debug/展示 toast（非生产）
  - 各格式缓存过期时间（reward/appOpen/interstitial）
  - test device id
  - TradPlus 额外：deviceId 获取、ADX url 等
- 注入 adLoadedListener 做“请求/失败/成功”埋点：[AdManager.init: adLoadedListener](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L162-L203)
- `AdUtil.setLoader(...)` 将 loaderType + config 交给广告封装库

## 2. 广告位与场景模型

### 2.1 场景枚举

广告位按“场景/用途”划分，统一使用枚举 [AdScene](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/bean/ad/AdScene.kt#L7-L14)：

- REWARD：激励视频
- INTERSTITIAL：插屏
- APP_OPEN：开屏（App Open）
- NATIVE：原生
- BANNER：横幅
- MEDIA_VIDEO：贴片/媒体视频（与播放器场景结合）

### 2.2 展示触发点（示例）

- 开屏链路：启动页 RoutingActivity 等待合规完成后预加载开屏/插屏
  - [RoutingActivity.waitGDPRConsent](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/ui/activity/RoutingActivity.kt#L179-L214)
- 沉浸页（播放页）触发：
  - onCreate 预加载 reward + interstitial：[AdFeature](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/immersion/feature/AdFeature.kt#L15-L36)
  - 退出沉浸页时满足条件展示插屏：[AdFeature](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/immersion/feature/AdFeature.kt#L26-L36)
- 解锁页“看广告解锁”按钮触发激励广告：
  - [PurePayingUserAdRetentionUnlockView.showRewardVideo](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/ui/view/immersion/unlock/PurePayingUserAdRetentionUnlockView.kt#L89-L103)

## 3. 配置化体系（本地兜底 + 远端更新 + ActiveTime 分桶）

广告相关的“开关/频控/广告单元 ID”基本都走统一的配置框架：

- 读本地 assets：`app/src/main/assets/ad/*.json`
- 再读远端 Remote Config：覆盖/更新
- 通过 activeTime（安装时长）选择命中档位

通用加载逻辑在 [AppConfigureUtil](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/AppConfigureUtil.kt#L20-L119)：

- 本地配置文件路径约定：`ad/$key.json`
- activeTime：首次拉取时强制用 0，否则用 `now - installTime`（见 [getActiveTime](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/AppConfigureUtil.kt#L112-L118)）

### 3.1 广告位开关（AdSwitch）

广告位开关由 [AdSwitchConfigure](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdSwitchConfigure.kt) 管理：

- 区分 organic 与 campaign 用户（key 不同）：
  - `adSwitch_organic_android.json`
  - `adSwitch_campaign_android.json`
  - key 选择逻辑：[getSwitchKey](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdSwitchConfigure.kt#L95-L97)
- 依赖国家分层（tier）后，从对应 tier 的数组里按 activeTime 命中具体档位
- `isEnable(adScene)` 会返回开关并触发“按 activeTime 自动更新”（见 [AdSwitch.isEnable](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdSwitchConfigure.kt#L108-L116)）

### 3.2 广告单元 ID（AdUnitId）

广告单元 ID 与优先级由 [AdUnitIdConfigureChooser](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdUnitIdConfigureChooser.kt#L14-L99) 统一选择：

- ABTest 决定读取 Admob 配置还是 TradPlus 配置
- 提供 `adGroup(scene)` 返回当前场景的一组 `AdLoadParam`（包含 adUnitId、format、priority、maxSize、name 等）
- 自动化测试模式会强制只走原生全屏兜底（避免真实全屏广告干扰自动化），见 [adGroup: autoTestParams](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdUnitIdConfigureChooser.kt#L80-L96)

本地兜底配置示例：

- Admob：[adUnitId_android_v3.json](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/assets/ad/adUnitId_android_v3.json)
- TradPlus：[adUnitId_android_tp_v1.json](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/assets/ad/adUnitId_android_tp_v1.json)

### 3.3 行为频控与触发阈值（AdActionCount + AdActionCounter）

频控/阈值分两类：

1) “场景进入次数”阈值（例如看连续 N 集后才允许出插屏）
- 配置来自 [AdActionCountConfigure](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdActionCountConfigure.kt)
- 核心字段：
  - `interstitialGap`：插屏冷却（ms）
  - `exitImmersionPageCount/switchTabCount/unlockVideoCount/...`：各场景达到多少次才允许展示

2) “可消费对象”阈值（从未付费用户行为累积得到一个“可以消耗的机会”）
- 实现为 [AdActionCounter](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdActionCounter.kt)
- 典型计数来源（达到阈值则产生 consumable）：
  - 未付费从沉浸页退出 N 次
  - 进入付费点未付费
  - 未付费观看激励任务达到 N 次
- 插屏展示成功后才会 `consume()`（见 [AdManager.showInterstitial: consume](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L511-L520)）

## 4. “是否允许展示广告”的业务策略

### 4.1 付费/余额对插屏与开屏的关停

插屏与开屏只在“非订阅且无余额”时允许展示：

- [AdSceneManager.adEnable](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdSceneManager.kt#L18-L33)

这个开关会影响：

- 预加载：`preloadAd` 对除 REWARD/NATIVE/BANNER 外的场景进行校验（见 [AdManager.preloadAd](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L246-L271)）
- 展示前校验：`interstitialEnable/appOpenEnable` 内部也会判断（见 [AdManager.interstitialEnable](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L1250-L1280)）

### 4.2 广告位开关（运营/投放）

即使用户满足“可展示”的大前提，仍需通过 AdSwitch 允许：

- `AdSwitchConfigure.value().isEnable(adScene)`（在 preload 与 enable 校验里都会触发）

## 5. 展示链路（按广告格式）

所有展示基本都走同一模式：

1) 通过 `AdUnitIdConfigureChooser.adGroup(scene)` 拿到候选广告单元列表
2) 调用 `AdUtil.show(AdRequestParam)` 请求并展示
3) 通过 listener 回调统一做：
   - 埋点（请求、展示、点击、填充失败）
   - 统计（展示次数、展示时长）
   - 收入上报（onPaid 回调带 EcpmValue）
4) 展示成功后 `preloadAd(...)` 做下一次缓存预热

### 5.1 激励广告（Reward）

- 展示入口：[AdManager.showRewardVideo](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L312-L429)
- 关键点：
  - 展示前等待 GDPR（带 loading dialog）：[waitGDPRConsent](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L1316-L1437)
  - 频控：`AD_REWARD_MIN_SHOW_GAP` 防止短时间连续拉起
  - `dismissListener` 中把 reward 结果回传业务回调 `onReward(reward)`（extra 里携带是否发放奖励）
  - `paidEventListener` 收到 `EcpmValue` 时上报收入与曝光（以及“先 dismiss 后到 paid”的补偿逻辑）

### 5.2 插屏（Interstitial）

- 展示入口：[AdManager.showInterstitial](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L434-L578)
- 展示条件：
  - `interstitialEnable(scene)`：包含 AdSceneManager、冷却、AdSwitch、可消费对象（见 [interstitialEnable](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L1250-L1280)）
  - `AdActionManager.mInterstitialGap`：冷却时间窗（见 [AdActionManager.InterstitialCoolDown](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdActionManager.kt#L34-L63)）
- 展示成功后：
  - 记录展示时间用于冷却
  - 预加载下一次插屏
  - 消耗一个 `AdActionCounter`（仅展示成功才消耗）

### 5.3 开屏（App Open）

- 展示入口：[AdManager.showAppOpenAd](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L837-L941)
- 展示成功后会根据 `adInfo.format` 区分到底是 appOpen/interstitial/native 兜底之一，并打不同事件：
  - EVENT_AD_SPLASH_APP_OPEN / EVENT_AD_SPLASH_INTERSTITIAL / EVENT_AD_SPLASH_NATIVE

开屏预加载触发点在启动页合规完成后（见 [RoutingActivity](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/ui/activity/RoutingActivity.kt#L179-L189)）。

### 5.4 原生（Native）

- 展示入口：[AdManager.showNativeAd](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L943-L991)
- 特性：
  - 通过 `adLayoutResource` 注入布局，便于适配 Admob/TradPlus 不同渲染模板
  - 预加载策略：Admob 支持缓存多个原生（最多 3），TradPlus 仅 1（见 [preloadAd: native cache size](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L277-L283)）

### 5.5 横幅（Banner）

- 展示入口：[AdManager.showBannerAd](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L993-L1036)
- 特性：
  - adUnitParams 会绑定 `AdSize`
  - Banner 自动刷新会触发 `onAdLoaded(isAuto=true)`，代码中补了一次“请求打点”（见 [AdManager.init: isAuto](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L183-L201)）

## 6. 合规（GDPR/CCPA）与展示时序

### 6.1 GDPR

GDPR 流程在 `AdManager.waitGDPRConsent` 中封装：

- 若无需弹窗或已同意：直接继续
- 否则：
  - 先 `requestConsentInfoUpdate`
  - 在超时时间内尝试 `loadConsentForm`
  - 若启动页已弹过（按日期缓存）则不再重复弹
  - 弹窗展示/结果均有埋点（EVENT_GDPR_POP_SHOW / EVENT_GDPR_RESULT）

关键实现：[AdManager.waitGDPRConsent](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L1316-L1437)

### 6.2 CCPA

启动页会异步设置 CCPA，并在配置完成后再预加载开屏/插屏：

- [RoutingActivity: CCPAManager.asyncSetCCPAWithCache / runAfterCCPAConfigured](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/ui/activity/RoutingActivity.kt#L179-L189)

## 7. 埋点与收入

广告埋点主要在两处汇总：

1) 加载阶段统一埋点（请求/失败/成功）：
- [AdManager.init: adLoadedListener](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L162-L203)

2) 展示阶段（placement show、show success、click、paid、duration、impression 等）：
- 以 reward/interstitial/appOpen/native/banner 的 `AdRequestParam` listener 为入口
- 典型调用：`EventManager.logEvent(...)` + `addAdExtraInfo(...)` + `logAdRevenue/logAdRealImpression/...`

如果要排查“展示了但没收入/收入回调晚到”，重点看 `paidEventListener` 里对 `dismiss=true` 后补偿上报的逻辑：

- Reward：[showRewardVideoAfterGDPR.paidEventListener](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L410-L427)
- Interstitial：[showInterstitial.paidEventListener](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L560-L575)
- AppOpen：[showAppOpenAd.paidEventListener](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L916-L931)

## 8. 常见排查路径（从“为什么没展示”出发）

### 8.1 预加载没跑 / 缓存为空

- 入口是否触发（例如沉浸页 onCreate、启动页合规结束后）
- `TesterConfigFactory.preloadAdEnable` 是否为 false（见 [preloadAd](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L246-L251)）
- `AdUnitIdConfigureChooser.adGroup(scene)` 是否为空（配置未下发、tier 不匹配、activeTime 命中异常）

### 8.2 展示被策略拦截

- 插屏/开屏：
  - 用户订阅或有余额导致 [AdSceneManager.adEnable=false](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdSceneManager.kt#L18-L33)
  - `AdSwitchConfigure` 对该 scene 关闭
  - 插屏冷却未到（`mInterstitialGap.inWaiting()`）
  - 没有可消费对象（`AdActionCounter.getConsumable()==null`）
- 激励：
  - reward 的展示频控（短时间多次拉起被拒绝）
  - GDPR 等待/弹窗流程中被超时或 activity 销毁

### 8.3 展示回调太晚导致 NPE/上下文失效

业务侧有“fragment 已 detach”保护：

- [AdManager.getSafeContext](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt#L1439-L1446)

## 9. 代码导航（核心文件清单）

- 广告统一入口： [AdManager.kt](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/ad/AdManager.kt)
- 广告位开关： [AdSwitchConfigure.kt](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdSwitchConfigure.kt)
- 广告单元 ID 选择： [AdUnitIdConfigureChooser.kt](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdUnitIdConfigureChooser.kt)
- 场景频控/触发： [AdActionManager.kt](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdActionManager.kt)
- 可消费计数器： [AdActionCounter.kt](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdActionCounter.kt)
- 关停策略（订阅/余额）： [AdSceneManager.kt](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/ad/AdSceneManager.kt)
- 配置加载工具： [AppConfigureUtil.kt](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/java/com/startshorts/androidplayer/manager/configure/AppConfigureUtil.kt)
- 本地兜底配置：
  - [adUnitId_android_v3.json](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/assets/ad/adUnitId_android_v3.json)
  - [adSwitch_organic_android.json](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/assets/ad/adSwitch_organic_android.json)
  - [adSwitch_campaign_android.json](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/assets/ad/adSwitch_campaign_android.json)
  - [adActionCount_android_v2.json](file:///Users/chenxunlin/trace-workspace/shortTV/app/src/main/assets/ad/adActionCount_android_v2.json)
