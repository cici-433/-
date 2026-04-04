# Pay 核心库（Google Play Billing）

本模块提供一个可复用的 Google Play 支付核心库，覆盖：

- 一次性商品（INAPP）：查询商品、拉起购买、补单恢复、消费（consume）、确认（acknowledge）
- 订阅（SUBS）：查询商品、拉起购买、升级/降级（SubscriptionUpdateParams）、补单恢复、确认（acknowledge）
- 统一错误模型与主线程回调

## 1. 模块结构

- `com.startshorts.pay.api`
  - 对外 API、数据模型、错误模型、监听回调
- `com.startshorts.pay.core`
  - Google Play BillingClient 的具体实现（对外隐藏）

## 2. 快速接入

### 2.1 初始化与连接

```kotlin
val payClient = PayClientFactory.createGooglePayClient(context)
payClient.setListener(object : PayPurchaseListener {
    override fun onReady() {
    }

    override fun onDisconnected() {
    }

    override fun onPurchaseEvent(event: PayPurchaseEvent) {
        when (event) {
            is PayPurchaseEvent.PurchasesUpdated -> {
                if (event.responseCode == BillingClient.BillingResponseCode.OK) {
                    val purchases = event.purchases
                    // 1) 服务端校验（推荐）+ 发放权益
                    // 2) 成功后再 acknowledge 或 consume
                }
            }
            is PayPurchaseEvent.Restored -> {
                val inApp = event.inAppPurchases
                val subs = event.subsPurchases
            }
        }
    }

    override fun onError(error: PayError) {
    }
})

val connectResult = payClient.connect()
```

### 2.2 查询商品（INAPP + SUBS）

```kotlin
val result = payClient.queryProductDetails(
    listOf(
        PayProductId(id = "coin_pack_199", type = PayProductType.INAPP),
        PayProductId(id = "premium_monthly", type = PayProductType.SUBS)
    )
)
val products = (result as? PayResult.Success)?.value.orEmpty()
```

### 2.3 拉起一次性购买（INAPP）

```kotlin
val inApp = products.first { it.productId == "coin_pack_199" }
payClient.launchInAppPurchase(
    activity = activity,
    productDetails = inApp,
    obfuscatedAccountId = userId
)
```

### 2.4 拉起订阅购买（SUBS）

订阅必须选择 offerToken（Google Play Console 的 Base Plan/Offer）。

```kotlin
val subs = products.first { it.productId == "premium_monthly" }
val offerToken = subs.raw.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return

payClient.launchSubscriptionPurchase(
    activity = activity,
    productDetails = subs,
    offerToken = offerToken,
    obfuscatedAccountId = userId
)
```

### 2.5 订阅升级/降级（替换订阅）

当用户已有旧订阅 token，需要升级/降级到新订阅时：

```kotlin
val updateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
    .setOldPurchaseToken(oldPurchaseToken)
    .setReplaceProrationMode(BillingFlowParams.ProrationMode.IMMEDIATE_WITH_TIME_PRORATION)
    .build()

payClient.launchSubscriptionPurchase(
    activity = activity,
    productDetails = newSubs,
    offerToken = newOfferToken,
    obfuscatedAccountId = userId,
    subscriptionUpdateParams = updateParams
)
```

### 2.6 补单恢复（Restore / Recovery）

建议在如下场景触发：

- App 启动后（或用户登录后）主动查询一次
- 支付回调丢失/进程被杀/网络失败时
- 发现用户权益与 Google Play 订单不一致时

```kotlin
val restore = payClient.queryPurchases()
```

### 2.7 发放权益后确认/消费

推荐流程：

1) 客户端收到 `PurchasesUpdated`
2) 将 `purchaseToken`、`products`、`orderId` 等上报服务端
3) 服务端调用 Google Play Developer API 校验 + 落单 + 发放权益
4) 服务端返回成功后，客户端再执行确认/消费，避免因“提前 ack/consume”导致对账困难

```kotlin
val purchaseToken = purchase.purchaseToken

// 非消耗型商品/订阅：acknowledge
payClient.acknowledge(purchaseToken)

// 消耗型商品（金币）：consume
payClient.consume(purchaseToken)
```

### 2.8 本地假支付（仅客户端流程联调）

当你只关心客户端流程（商品查询 → 拉起购买 → 收到回调 → 走 ack/consume/补单逻辑），但当前环境无法使用 Google Play（例如本地调试机、未上架轨道、无测试账号）时，可以用本库内置的假支付实现：

```kotlin
val payClient = PayClientFactory.createFakePayClient()
payClient.setListener(listener)
payClient.connect()
```

能力范围：

- `queryProductDetails(...)`：基于 productId/type 生成虚拟的 `PayProductDetails`（用于 UI 展示）
- `launchInAppPurchase/launchSubscriptionPurchase`：立即回调 `PurchasesUpdated(OK)` 并生成一笔虚拟 `Purchase`
- `queryPurchases()`：返回内存中的虚拟订单，并回调 `Restored`
- `acknowledge/consume`：更新/移除内存订单（仅用于模拟结单行为）

限制：

- 生成的 `Purchase` 仅用于本地流程模拟，无法用于服务端调用 Developer API 校验
- 订单仅存在内存中，进程重启后会丢失

## 3. 完整支付流程（推荐）

该流程适用于一次性商品（INAPP）与订阅（SUBS），差异点主要在“订阅需要 offerToken”与“结单方式（ack/consume）不同”。

1) App 从服务端拉取“商品配置”
- 目的：控制展示、价格标签/运营信息、可购状态、权益描述等（避免客户端硬编码）
- 结果：拿到业务侧商品列表（通常包含：productId、类型 INAPP/SUBS、展示信息、权益信息等）

2) App 使用服务端下发的 productId 从 Google Play 拉取 `ProductDetails`
- 目的：获取真实价格/币种/标题以及订阅 offer/base plan 与 offerToken（订阅下单必需）
- 结果：UI 展示与下单参数均以 Google Play 返回为准

3) App 拉起 Google Play 购买页（Billing Flow）
- INAPP：调用 `launchInAppPurchase(...)`
- SUBS：选择目标 offerToken 后调用 `launchSubscriptionPurchase(...)`

4) Google Play 回调 `PurchasesUpdated`
- 结果：拿到 purchaseToken、products、orderId（可能为空）等
- 注意：该回调可能延迟/丢失/补发；不可只依赖一次回调判断是否“最终发货成功”

5) App 上报服务端创建/确认订单
- 上报信息建议包含：purchaseToken、productId、type、用户标识（可用 obfuscatedAccountId 对齐）等
- 要求：服务端必须以 purchaseToken 做幂等（同 token 只发放一次）

6) 服务端调用 Google Play Developer API 校验 purchaseToken
- 校验通过后落单并发放权益（金币入账/开通会员/解锁权益等）
- 服务端将“发货结果”返回给 App（或由 App 轮询/推送获取）

7) App 在确认“服务端已发货成功”后结单
- 订阅/非消耗型：`acknowledge(purchaseToken)`
- 消耗型（如金币）：`consume(purchaseToken)`

8) App 启动/登录后补单兜底（强烈建议）
- 调用 `queryPurchases()` 拿到历史订单
- 对每个 purchaseToken 走第 5~7 步（服务端幂等保障不会重复发货）

## 4. 推荐时序（端到端）

1) `connect()`
2) `queryProductDetails(INAPP/SUBS)`
3) `launchInAppPurchase/launchSubscriptionPurchase`
4) `PurchasesUpdated` 回调（可能延迟/补发）
5) 服务端校验（Developer API）+ 发放权益
6) 客户端 `acknowledge/consume`
7) App 启动/登录后 `queryPurchases()` 补单兜底

## 5. Google Pay 常见疑难问题与规避/解决

### 4.1 回调丢失 / 支付完成但客户端没收到 PurchasesUpdated

**原因：**

- 购买流程中 App 被杀、进程重启
- Google Play 服务延迟投递回调
- 网络波动导致购买确认链路中断

**规避/解决：**

- 在 App 启动/登录后调用 `queryPurchases()` 作为补单入口
- 服务端以 purchaseToken 为幂等键（同 token 只发放一次）
- 客户端对同 purchaseToken 的确认/消费也做幂等处理

### 4.2 ITEM_ALREADY_OWNED（已拥有该商品）

**原因：**

- 非消耗型 INAPP 已购买但未确认/未消费
- 订阅仍在有效期内

**规避/解决：**

- 对消耗型商品（如金币）成功发放后及时 `consume`
- 对非消耗型/订阅成功发放后及时 `acknowledge`
- 收到该错误时触发 `queryPurchases()` 并走“补单/恢复”逻辑

### 4.3 未 acknowledge 导致自动退款（一般为 3 天左右）

**原因：**

- Google Play 要求对购买进行确认（订阅/非消耗型）或消费（消耗型）

**规避/解决：**

- 严格在“服务端校验 + 发放权益成功后”再 `acknowledge/consume`
- 建立后台任务或用户下次启动时补偿确认（`queryPurchases()` + 识别未确认订单）

### 4.4 订阅无法购买：缺少 offerToken / Base Plan 配置错误

**原因：**

- Billing v5+ 使用 ProductDetails + OfferToken 机制，订阅必须指定 offerToken
- Play Console 未配置 base plan/offer 或未激活

**规避/解决：**

- 使用 `ProductDetails.subscriptionOfferDetails` 获取 offerToken
- 确保 Play Console：订阅已创建、base plan 已激活、定价已生效、已发布到对应测试轨道

### 4.5 DEVELOPER_ERROR（常见于配置或参数错误）

**原因：**

- productId 不存在/未发布到当前安装包所属轨道
- type 传错（INAPP/SUBS）
- 订阅升级/降级的 oldPurchaseToken 无效
- Play Console 账号/测试账号未正确配置

**规避/解决：**

- 使用内部测试/封闭测试轨道，并确保测试账号加入 License testers
- productId 与 type 必须与 Play Console 一致
- 升降级流程只在用户确实拥有旧订阅时触发

### 4.6 多端/多账号导致的权益错乱

**原因：**

- 用户在不同 Google 账号购买，但在 App 内用同一业务账号登录（或反过来）
- purchaseToken 与业务账号绑定不清晰

**规避/解决：**

- 启用 `obfuscatedAccountId`（推荐为业务 userId 的不可逆 hash）
- 服务端用 purchaseToken + accountId 做绑定校验与风控

### 4.7 服务端校验缺失导致的作弊风险

**风险：**

- 仅依赖客户端回调无法抵御篡改/重放

**规避/解决：**

- 服务端必须调用 Google Play Developer API 校验 purchaseToken
- 服务端以 purchaseToken 幂等落单，发放权益与确认消费分离

## 6. 约束与注意事项

- 本模块只负责“与 Google Play Billing 交互的核心能力”，不包含具体业务（金币、会员、解锁等）
- 权益发放必须由业务方实现，且强烈建议通过服务端校验完成
