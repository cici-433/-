package com.startshorts.pay.api

import android.app.Activity
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.Purchase

/**
 * Google Play Billing 核心客户端抽象。
 *
 * 该接口覆盖“支付基础能力”，不绑定任何具体业务（金币/会员/解锁等）。
 *
 * 能力边界：
 * - 连接生命周期：建立/断开 BillingService 连接
 * - 商品信息：查询 INAPP/SUBS 的 ProductDetails（用于展示价格与拉起支付）
 * - 下单：拉起一次性购买与订阅购买（含订阅升级/降级）
 * - 补单：查询本地账号当前拥有的订单（进程被杀/回调丢失场景兜底）
 * - 结单：acknowledge/consume（在发放权益成功后执行）
 *
 * 线程与回调：
 * - 方法的具体实现可能在内部切换线程，但 [PayPurchaseListener] 的所有回调必须在主线程触发。
 * - [launchInAppPurchase] 与 [launchSubscriptionPurchase] 只负责“触发 Google Play 购买流程”，最终结果通过
 *   [PayPurchaseListener.onPurchaseEvent] 投递。
 *
 * 安全与对账：
 * - 强烈建议：客户端拿到 [Purchase.purchaseToken] 后，先走服务端校验（Google Play Developer API），再发放权益。
 * - 在服务端校验与权益发放成功后，再调用 [acknowledge]/[consume] 结单。
 * - 服务端与客户端都应以 purchaseToken 做幂等（同 token 只处理一次），避免补单/回调重放导致重复发货。
 *
 * 常见边界：
 * - `PurchasesUpdated` 回调可能延迟/丢失（App 被杀、网络抖动等）；必须配合 [queryPurchases] 做恢复。
 * - 已拥有商品（ITEM_ALREADY_OWNED）通常意味着未 consume/ack 的历史订单仍在；需要先补单结单。
 */
interface PayClient {

    /**
     * 设置监听器，用于接收连接状态、购买回调与错误信息。
     *
     * - 回调保证在主线程。
     * - 建议在 connect 前设置；避免连接成功/购买回调早于监听器注册。
     */
    fun setListener(listener: PayPurchaseListener?)

    /**
     * 连接 Google Play BillingService。
     *
     * - 允许重复调用：若已经 ready，应返回成功。
     * - 建议在 App 启动/用户登录后尽早连接，减少页面内等待。
     */
    suspend fun connect(): PayResult<Unit>

    /**
     * 断开连接并释放内部 BillingClient。
     *
     * 一般在：
     * - 业务明确不再使用支付能力（例如：退出登录、应用退出等）
     * - 或者由上层生命周期管理（例如 Application 单例持有并在进程销毁时释放）
     */
    fun disconnect()

    /**
     * 批量查询商品详情（[com.android.billingclient.api.ProductDetails]）。
     *
     * 使用建议：
     * - INAPP 与 SUBS 可以混合传入，本实现会按类型分组分别查询（Google Billing 的参数要求）。
     * - 对 SUBS：UI 展示价通常取某个 offer 的 pricing phase；下单必须使用对应 offerToken。
     *
     * @param ids 需要查询的商品 id + 类型。
     */
    suspend fun queryProductDetails(ids: List<PayProductId>): PayResult<List<PayProductDetails>>

    /**
     * Launches a billing flow for an in-app product.
     *
     * Purchase results will be delivered via [PayPurchaseListener.onPurchaseEvent].
     *
     * 注意：
     * - 该方法只负责“拉起 Google Play 购买页”，并不代表购买成功。
     * - 最终成功与否以 `PurchasesUpdated` 的 responseCode + purchases 为准。
     * - `obfuscatedAccountId/obfuscatedProfileId` 建议使用业务账号的不可逆 hash，用于 Google 侧反作弊与对账辅助。
     */
    fun launchInAppPurchase(
        activity: Activity,
        productDetails: PayProductDetails,
        obfuscatedAccountId: String? = null,
        obfuscatedProfileId: String? = null
    ): PayResult<Unit>

    /**
     * Launches a billing flow for a subscription.
     *
     * @param offerToken Must be a valid token from [com.android.billingclient.api.ProductDetails.SubscriptionOfferDetails.offerToken].
     * @param subscriptionUpdateParams Used for upgrade/downgrade flows.
     *
     * 注意：
     * - SUBS 必须带 offerToken（base plan / offer 机制），否则通常会 DEVELOPER_ERROR。
     * - 升级/降级：需要 oldPurchaseToken，通过 [subscriptionUpdateParams] 传入。
     */
    fun launchSubscriptionPurchase(
        activity: Activity,
        productDetails: PayProductDetails,
        offerToken: String,
        obfuscatedAccountId: String? = null,
        obfuscatedProfileId: String? = null,
        subscriptionUpdateParams: BillingFlowParams.SubscriptionUpdateParams? = null
    ): PayResult<Unit>

    /**
     * Queries currently owned purchases for restore/recovery.
     *
     * 典型场景：
     * - App 启动/登录后：恢复未处理订单（回调丢失/进程被杀）
     * - ITEM_ALREADY_OWNED：先查出已拥有订单并走补单/结单流程
     *
     * 返回值约定：
     * - Pair.first：INAPP 已拥有订单
     * - Pair.second：SUBS 已拥有订单
     */
    suspend fun queryPurchases(): PayResult<Pair<List<Purchase>, List<Purchase>>>

    /**
     * Acknowledges a purchase after entitlement has been granted on server side.
     *
     * 适用：
     * - 订阅（SUBS）
     * - 非消耗型一次性商品（non-consumable INAPP）
     *
     * 风险：
     * - 未 ack 的订单可能在一段时间后被 Google 自动退款（具体策略以官方为准）。
     * - 过早 ack 可能导致服务端对账困难（建议：服务端校验 + 发放权益成功后再 ack）。
     */
    suspend fun acknowledge(purchaseToken: String): PayResult<Unit>

    /**
     * Consumes a purchase token for consumable in-app products after entitlement has been granted.
     *
     * 适用：
     * - 消耗型一次性商品（如金币包）
     *
     * 约束：
     * - 未 consume 的消耗型商品再次购买可能返回 ITEM_ALREADY_OWNED。
     * - 同一 purchaseToken 重复 consume 应按幂等处理（通常会返回非 OK，业务方可忽略或记录）。
     */
    suspend fun consume(purchaseToken: String): PayResult<Unit>
}
