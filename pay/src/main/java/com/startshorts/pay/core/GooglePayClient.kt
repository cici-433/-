package com.startshorts.pay.core

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.startshorts.pay.api.PayClient
import com.startshorts.pay.api.PayError
import com.startshorts.pay.api.PayProductDetails
import com.startshorts.pay.api.PayProductId
import com.startshorts.pay.api.PayProductType
import com.startshorts.pay.api.PayPurchaseEvent
import com.startshorts.pay.api.PayPurchaseListener
import com.startshorts.pay.api.PayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 基于 Google Play BillingClient 的 [PayClient] 实现。
 *
 * 设计要点：
 * - 连接管理：通过 [connect] 建立连接；通过 [disconnect] 主动释放资源。
 * - 回调统一：购买回调与错误回调统一切回主线程（[MainThreadDispatcher]）。
 * - 职责聚焦：仅负责与 Google Play Billing 交互；不包含“服务端校验/权益发放/业务对账”。
 *
 * 重要约束（强烈建议业务侧遵守）：
 * - 购买成功不等于发货成功：务必以 purchaseToken 在服务端进行校验与落单，再发放权益。
 * - 结单时机：服务端发货成功后，客户端再 acknowledge/consume。
 * - 幂等：purchaseToken 可能重复出现（回调补发/补单查询），服务端与客户端都应幂等处理。
 */
internal class GooglePayClient(
    private val context: Context
) : PayClient {

    private val mainThreadDispatcher = MainThreadDispatcher()

    @Volatile
    private var billingClient: BillingClient? = null

    @Volatile
    private var isReady: Boolean = false

    @Volatile
    private var listener: PayPurchaseListener? = null

    /**
     * Google Play Billing 的购买回调入口。
     *
     * 注意：
     * - responseCode != OK 时也可能回调（例如用户取消、网络错误），purchases 通常为空。
     * - 即使 responseCode == OK，也建议业务侧对 purchases 逐条校验 purchaseState、token、products 等字段。
     * - 该回调可能延迟投递（比如进程重启后补发），业务侧必须配合补单逻辑。
     */
    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        val safePurchases = purchases.orEmpty()
        mainThreadDispatcher.post {
            listener?.onPurchaseEvent(
                PayPurchaseEvent.PurchasesUpdated(
                    purchases = safePurchases,
                    responseCode = billingResult.responseCode,
                    debugMessage = billingResult.debugMessage
                )
            )
        }
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            notifyError(billingResult.toPayError())
        }
    }

    override fun setListener(listener: PayPurchaseListener?) {
        this.listener = listener
    }

    /**
     * 建立 BillingClient 连接。
     *
     * 线程：
     * - 强制在主线程调用 BillingClient.startConnection（通过 Dispatchers.Main.immediate）。
     *
     * 幂等：
     * - 若已有连接并 ready，则直接返回成功。
     *
     * 回调：
     * - 成功会触发 [PayPurchaseListener.onReady]。
     * - 失败会触发 [PayPurchaseListener.onError]，并返回 [PayResult.Failure]。
     */
    override suspend fun connect(): PayResult<Unit> {
        return withContext(Dispatchers.Main.immediate) {
            val existing = billingClient
            if (existing != null && isReady) {
                return@withContext PayResult.Success(Unit)
            }

            val client = BillingClient.newBuilder(context)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases()
                .build()
            billingClient = client

            suspendCancellableCoroutine { cont ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingServiceDisconnected() {
                        isReady = false
                        mainThreadDispatcher.post { listener?.onDisconnected() }
                    }

                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            isReady = true
                            mainThreadDispatcher.post { listener?.onReady() }
                            cont.resume(PayResult.Success(Unit))
                        } else {
                            isReady = false
                            val error = billingResult.toPayError()
                            notifyError(error)
                            cont.resume(PayResult.Failure(error))
                        }
                    }
                })
            }
        }
    }

    /**
     * 释放连接与内部引用。
     *
     * 注意：
     * - disconnect 后不可继续调用 query/launch/ack/consume；需要重新 connect。
     */
    override fun disconnect() {
        isReady = false
        billingClient?.endConnection()
        billingClient = null
    }

    /**
     * 查询商品详情。
     *
     * 关键点：
     * - Google Billing 的 queryProductDetailsAsync 需要按 productType 查询；因此这里先 groupBy type。
     * - 订阅商品会返回多个 offer 与 pricing phase；此处只做“默认展示价”的提取。
     */
    override suspend fun queryProductDetails(ids: List<PayProductId>): PayResult<List<PayProductDetails>> {
        val client = billingClient
        if (client == null || !isReady) {
            return PayResult.Failure(PayError(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, "BillingClient is not connected"))
        }

        val grouped = ids.groupBy { it.type }
        val results = mutableListOf<PayProductDetails>()

        for ((type, groupIds) in grouped) {
            val queryProducts = groupIds.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it.id)
                    .setProductType(type.toBillingProductType())
                    .build()
            }

            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(queryProducts)
                .build()

            val result = suspendCancellableCoroutine<Pair<BillingResult, List<ProductDetails>>> { cont ->
                client.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                    cont.resume(billingResult to productDetailsList)
                }
            }

            val billingResult = result.first
            val details = result.second

            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                val error = billingResult.toPayError()
                notifyError(error)
                return PayResult.Failure(error)
            }

            results += details.map { it.toPayProductDetails(type) }
        }

        return PayResult.Success(results)
    }

    override fun launchInAppPurchase(
        activity: Activity,
        productDetails: PayProductDetails,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?
    ): PayResult<Unit> {
        if (productDetails.type != PayProductType.INAPP) {
            return PayResult.Failure(PayError(BillingClient.BillingResponseCode.DEVELOPER_ERROR, "ProductDetails is not INAPP"))
        }
        return launchBillingFlow(
            activity = activity,
            productDetails = productDetails.raw,
            offerToken = null,
            obfuscatedAccountId = obfuscatedAccountId,
            obfuscatedProfileId = obfuscatedProfileId,
            subscriptionUpdateParams = null
        )
    }

    override fun launchSubscriptionPurchase(
        activity: Activity,
        productDetails: PayProductDetails,
        offerToken: String,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?,
        subscriptionUpdateParams: BillingFlowParams.SubscriptionUpdateParams?
    ): PayResult<Unit> {
        if (productDetails.type != PayProductType.SUBS) {
            return PayResult.Failure(PayError(BillingClient.BillingResponseCode.DEVELOPER_ERROR, "ProductDetails is not SUBS"))
        }
        if (offerToken.isBlank()) {
            return PayResult.Failure(PayError(BillingClient.BillingResponseCode.DEVELOPER_ERROR, "offerToken is blank"))
        }
        return launchBillingFlow(
            activity = activity,
            productDetails = productDetails.raw,
            offerToken = offerToken,
            obfuscatedAccountId = obfuscatedAccountId,
            obfuscatedProfileId = obfuscatedProfileId,
            subscriptionUpdateParams = subscriptionUpdateParams
        )
    }

    private fun launchBillingFlow(
        activity: Activity,
        productDetails: ProductDetails,
        offerToken: String?,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?,
        subscriptionUpdateParams: BillingFlowParams.SubscriptionUpdateParams?
    ): PayResult<Unit> {
        val client = billingClient
        if (client == null || !isReady) {
            return PayResult.Failure(PayError(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, "BillingClient is not connected"))
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .apply {
                if (!offerToken.isNullOrBlank()) {
                    setOfferToken(offerToken)
                }
            }
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .apply {
                if (!obfuscatedAccountId.isNullOrBlank()) {
                    setObfuscatedAccountId(obfuscatedAccountId)
                }
                if (!obfuscatedProfileId.isNullOrBlank()) {
                    setObfuscatedProfileId(obfuscatedProfileId)
                }
                if (subscriptionUpdateParams != null) {
                    setSubscriptionUpdateParams(subscriptionUpdateParams)
                }
            }
            .build()

        val billingResult = client.launchBillingFlow(activity, flowParams)
        return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            PayResult.Success(Unit)
        } else {
            val error = billingResult.toPayError()
            notifyError(error)
            PayResult.Failure(error)
        }
    }

    override suspend fun queryPurchases(): PayResult<Pair<List<Purchase>, List<Purchase>>> {
        val client = billingClient
        if (client == null || !isReady) {
            return PayResult.Failure(PayError(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, "BillingClient is not connected"))
        }

        val inApp = queryPurchasesByType(client, PayProductType.INAPP)
        if (inApp is PayResult.Failure) return inApp

        val subs = queryPurchasesByType(client, PayProductType.SUBS)
        if (subs is PayResult.Failure) return subs

        val inAppPurchases = (inApp as PayResult.Success).value
        val subsPurchases = (subs as PayResult.Success).value

        mainThreadDispatcher.post {
            listener?.onPurchaseEvent(PayPurchaseEvent.Restored(inAppPurchases, subsPurchases))
        }

        return PayResult.Success(inAppPurchases to subsPurchases)
    }

    /**
     * 按商品类型查询当前账号“已拥有订单”。
     *
     * 注意：
     * - 该查询是补单/恢复的重要入口，应在 App 启动/登录/支付异常等时机调用。
     * - 返回的订单未必都已完成结单（ack/consume），业务侧需要自行判断并补偿处理。
     */
    private suspend fun queryPurchasesByType(client: BillingClient, type: PayProductType): PayResult<List<Purchase>> {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(type.toBillingProductType())
            .build()

        val result = suspendCancellableCoroutine<Pair<BillingResult, List<Purchase>>> { cont ->
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                cont.resume(billingResult to purchases)
            }
        }

        val billingResult = result.first
        val purchases = result.second
        return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            PayResult.Success(purchases)
        } else {
            val error = billingResult.toPayError()
            notifyError(error)
            PayResult.Failure(error)
        }
    }

    override suspend fun acknowledge(purchaseToken: String): PayResult<Unit> {
        val client = billingClient
        if (client == null || !isReady) {
            return PayResult.Failure(PayError(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, "BillingClient is not connected"))
        }
        if (purchaseToken.isBlank()) {
            return PayResult.Failure(PayError(BillingClient.BillingResponseCode.DEVELOPER_ERROR, "purchaseToken is blank"))
        }

        /**
         * 结单建议：
         * - 仅在服务端确认“已校验+已发货/已开通权益”后调用
         * - 对未 ack 的订单，Google Play 可能在一定时间后退款（以官方策略为准）
         */
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()

        val billingResult = suspendCancellableCoroutine<BillingResult> { cont ->
            client.acknowledgePurchase(params) { result ->
                cont.resume(result)
            }
        }

        return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            PayResult.Success(Unit)
        } else {
            val error = billingResult.toPayError()
            notifyError(error)
            PayResult.Failure(error)
        }
    }

    override suspend fun consume(purchaseToken: String): PayResult<Unit> {
        val client = billingClient
        if (client == null || !isReady) {
            return PayResult.Failure(PayError(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, "BillingClient is not connected"))
        }
        if (purchaseToken.isBlank()) {
            return PayResult.Failure(PayError(BillingClient.BillingResponseCode.DEVELOPER_ERROR, "purchaseToken is blank"))
        }

        /**
         * 消耗建议：
         * - 仅对消耗型商品（例如金币）调用 consume
         * - 若不 consume，用户再次购买可能返回 ITEM_ALREADY_OWNED
         */
        val params = ConsumeParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()

        val billingResult = suspendCancellableCoroutine<BillingResult> { cont ->
            client.consumeAsync(params) { result, _ ->
                cont.resume(result)
            }
        }

        return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            PayResult.Success(Unit)
        } else {
            val error = billingResult.toPayError()
            notifyError(error)
            PayResult.Failure(error)
        }
    }

    private fun ProductDetails.toPayProductDetails(type: PayProductType): PayProductDetails {
        /**
         * 价格提取策略（仅用于默认展示）：
         * - INAPP：oneTimePurchaseOfferDetails.formattedPrice
         * - SUBS：取第一个 offer 的第一个 pricing phase
         *
         * 业务如需严格展示/下单策略，应自行选择 offer 与 pricing phase。
         */
        val formattedPrice = when (type) {
            PayProductType.INAPP -> this.oneTimePurchaseOfferDetails?.formattedPrice
            PayProductType.SUBS -> {
                val offer = this.subscriptionOfferDetails?.firstOrNull()
                val pricing = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
                pricing?.formattedPrice
            }
        }

        return PayProductDetails(
            productId = productId,
            type = type,
            title = title,
            description = description,
            formattedPrice = formattedPrice,
            raw = this
        )
    }

    private fun notifyError(error: PayError) {
        mainThreadDispatcher.post { listener?.onError(error) }
    }
}
