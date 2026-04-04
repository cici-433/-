package com.startshorts.pay.core

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.startshorts.pay.api.PayClient
import com.startshorts.pay.api.PayError
import com.startshorts.pay.api.PayProductDetails
import com.startshorts.pay.api.PayProductId
import com.startshorts.pay.api.PayProductType
import com.startshorts.pay.api.PayPurchaseEvent
import com.startshorts.pay.api.PayPurchaseListener
import com.startshorts.pay.api.PayResult
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.absoluteValue

/**
 * 本地假支付实现，用于在不依赖 Play Console/Google Play 环境的情况下跑通客户端支付流程。
 *
 * 行为约定：
 * - connect()/disconnect()：仅维护内存连接状态，并在主线程回调 listener
 * - queryProductDetails()：基于传入的 productId/type 生成可用于 UI 展示的虚拟 ProductDetails
 * - launchInAppPurchase()/launchSubscriptionPurchase()：立即生成一笔虚拟 Purchase，并派发 PurchasesUpdated(OK)
 * - queryPurchases()：返回当前内存中尚未 consume 的 Purchase，并派发 Restored 事件
 * - acknowledge()：将对应 token 标记为 acknowledged（仅影响后续 queryPurchases 返回的 Purchase 字段）
 * - consume()：从内存中移除对应 token（用于模拟“消耗型商品结单”）
 *
 * 限制：
 * - Purchase 仅用于本地流程模拟，无法用于服务端调用 Google Play Developer API 校验
 * - 订单数据仅保存在内存中，进程重启后会丢失
 */
internal class FakePayClient : PayClient {

    private val dispatcher = MainThreadDispatcher()

    private var listener: PayPurchaseListener? = null
    private var connected: Boolean = false

    private data class PurchaseRecord(
        val productId: String,
        val type: PayProductType,
        val purchaseToken: String,
        val orderId: String,
        val purchaseTime: Long,
        val obfuscatedAccountId: String?,
        val obfuscatedProfileId: String?,
        val acknowledged: Boolean
    )

    private val records = LinkedHashMap<String, PurchaseRecord>()

    /**
     * 设置购买与连接状态监听器。
     *
     * @param listener 回调对象；可传 null 解除监听
     */
    override fun setListener(listener: PayPurchaseListener?) {
        this.listener = listener
    }

    /**
     * 建立“假连接”，并触发 [PayPurchaseListener.onReady]。
     */
    override suspend fun connect(): PayResult<Unit> {
        connected = true
        dispatcher.post { listener?.onReady() }
        return PayResult.Success(Unit)
    }

    /**
     * 断开“假连接”，并触发 [PayPurchaseListener.onDisconnected]。
     */
    override fun disconnect() {
        val wasConnected = connected
        connected = false
        if (wasConnected) {
            dispatcher.post { listener?.onDisconnected() }
        }
    }

    /**
     * 生成用于展示与下单的虚拟 [PayProductDetails] 列表。
     *
     * 返回值中的 [PayProductDetails.raw] 为通过反射构造的 [ProductDetails]，仅用于本地流程模拟。
     */
    override suspend fun queryProductDetails(ids: List<PayProductId>): PayResult<List<PayProductDetails>> {
        if (!connected) {
            return PayResult.Failure(
                PayError(
                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                    "FakePayClient is not connected"
                )
            )
        }

        val result = ids.map { id ->
            val raw = buildRawProductDetails(
                productId = id.id,
                type = id.type,
                formattedPrice = defaultFormattedPrice(id.id, id.type),
                offerToken = defaultOfferToken(id.id)
            )
            PayProductDetails(
                productId = id.id,
                type = id.type,
                title = id.id,
                description = id.id,
                formattedPrice = raw.oneTimePurchaseOfferDetails?.formattedPrice
                    ?: raw.subscriptionOfferDetails?.firstOrNull()
                        ?.pricingPhases
                        ?.pricingPhaseList
                        ?.firstOrNull()
                        ?.formattedPrice,
                raw = raw
            )
        }

        return PayResult.Success(result)
    }

    /**
     * 立即生成一笔虚拟 INAPP 订单并派发 [PayPurchaseEvent.PurchasesUpdated]。
     *
     * 若同一 productId 存在未 consume 的订单，则返回 ITEM_ALREADY_OWNED。
     */
    override fun launchInAppPurchase(
        activity: Activity,
        productDetails: PayProductDetails,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?
    ): PayResult<Unit> {
        if (!connected) {
            return PayResult.Failure(
                PayError(
                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                    "FakePayClient is not connected"
                )
            )
        }

        if (records.values.any { it.type == PayProductType.INAPP && it.productId == productDetails.productId }) {
            val error = PayError(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED, "Item already owned (fake)")
            dispatcher.post { listener?.onError(error) }
            return PayResult.Failure(error)
        }

        val record = newPurchaseRecord(
            productId = productDetails.productId,
            type = PayProductType.INAPP,
            obfuscatedAccountId = obfuscatedAccountId,
            obfuscatedProfileId = obfuscatedProfileId
        )
        records[record.purchaseToken] = record

        val purchase = record.toPurchase()
        dispatcher.post {
            listener?.onPurchaseEvent(
                PayPurchaseEvent.PurchasesUpdated(
                    purchases = listOf(purchase),
                    responseCode = BillingClient.BillingResponseCode.OK,
                    debugMessage = "FAKE"
                )
            )
        }
        return PayResult.Success(Unit)
    }

    /**
     * 立即生成一笔虚拟 SUBS 订单并派发 [PayPurchaseEvent.PurchasesUpdated]。
     *
     * 若同一 productId 存在未 consume 的订单，则返回 ITEM_ALREADY_OWNED。
     */
    override fun launchSubscriptionPurchase(
        activity: Activity,
        productDetails: PayProductDetails,
        offerToken: String,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?,
        subscriptionUpdateParams: BillingFlowParams.SubscriptionUpdateParams?
    ): PayResult<Unit> {
        if (!connected) {
            return PayResult.Failure(
                PayError(
                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                    "FakePayClient is not connected"
                )
            )
        }

        if (records.values.any { it.type == PayProductType.SUBS && it.productId == productDetails.productId }) {
            val error = PayError(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED, "Item already owned (fake)")
            dispatcher.post { listener?.onError(error) }
            return PayResult.Failure(error)
        }

        val record = newPurchaseRecord(
            productId = productDetails.productId,
            type = PayProductType.SUBS,
            obfuscatedAccountId = obfuscatedAccountId,
            obfuscatedProfileId = obfuscatedProfileId
        )
        records[record.purchaseToken] = record

        val purchase = record.toPurchase()
        dispatcher.post {
            listener?.onPurchaseEvent(
                PayPurchaseEvent.PurchasesUpdated(
                    purchases = listOf(purchase),
                    responseCode = BillingClient.BillingResponseCode.OK,
                    debugMessage = "FAKE offerToken=$offerToken"
                )
            )
        }
        return PayResult.Success(Unit)
    }

    /**
     * 返回当前内存中所有未 consume 的订单，并派发 [PayPurchaseEvent.Restored]。
     */
    override suspend fun queryPurchases(): PayResult<Pair<List<Purchase>, List<Purchase>>> {
        if (!connected) {
            return PayResult.Failure(
                PayError(
                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                    "FakePayClient is not connected"
                )
            )
        }
        val inApps = records.values.filter { it.type == PayProductType.INAPP }.map { it.toPurchase() }
        val subs = records.values.filter { it.type == PayProductType.SUBS }.map { it.toPurchase() }
        dispatcher.post { listener?.onPurchaseEvent(PayPurchaseEvent.Restored(inApps, subs)) }
        return PayResult.Success(inApps to subs)
    }

    /**
     * 标记订单已确认（仅用于模拟 acknowledged 字段）。
     */
    override suspend fun acknowledge(purchaseToken: String): PayResult<Unit> {
        val current = records[purchaseToken] ?: return PayResult.Success(Unit)
        records[purchaseToken] = current.copy(acknowledged = true)
        return PayResult.Success(Unit)
    }

    /**
     * 消耗订单（从内存中移除），用于模拟消耗型商品结单。
     */
    override suspend fun consume(purchaseToken: String): PayResult<Unit> {
        records.remove(purchaseToken)
        return PayResult.Success(Unit)
    }

    private fun newPurchaseRecord(
        productId: String,
        type: PayProductType,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?
    ): PurchaseRecord {
        val token = UUID.randomUUID().toString()
        val orderId = System.currentTimeMillis().toString()
        val time = System.currentTimeMillis()
        return PurchaseRecord(
            productId = productId,
            type = type,
            purchaseToken = token,
            orderId = orderId,
            purchaseTime = time,
            obfuscatedAccountId = obfuscatedAccountId,
            obfuscatedProfileId = obfuscatedProfileId,
            acknowledged = false
        )
    }

    private fun PurchaseRecord.toPurchase(): Purchase {
        val json = JSONObject().apply {
            put("orderId", orderId)
            put("packageName", "com.startshorts.fake")
            put("purchaseTime", purchaseTime)
            put("purchaseState", 0)
            put("purchaseToken", purchaseToken)
            put("acknowledged", acknowledged)
            put("quantity", 1)
            put("productId", productId)
            put("products", JSONArray().apply { put(productId) })
            if (type == PayProductType.SUBS) {
                put("autoRenewing", true)
            }
            if (!obfuscatedAccountId.isNullOrEmpty()) {
                put("obfuscatedAccountId", obfuscatedAccountId)
            }
            if (!obfuscatedProfileId.isNullOrEmpty()) {
                put("obfuscatedProfileId", obfuscatedProfileId)
            }
        }
        return Purchase(json.toString(), "FAKE")
    }

    private fun defaultOfferToken(productId: String): String = "fake_offer_${productId.hashCode().absoluteValue}"

    private fun defaultFormattedPrice(productId: String, type: PayProductType): String {
        val cents = (productId.hashCode().absoluteValue % 990) + 1
        val dollars = cents / 100
        val remain = cents % 100
        return if (type == PayProductType.SUBS) {
            "$$dollars.${remain.toString().padStart(2, '0')}/mo"
        } else {
            "$$dollars.${remain.toString().padStart(2, '0')}"
        }
    }

    private fun buildRawProductDetails(
        productId: String,
        type: PayProductType,
        formattedPrice: String,
        offerToken: String
    ): ProductDetails {
        val json = JSONObject().apply {
            put("productId", productId)
            put("type", if (type == PayProductType.INAPP) "inapp" else "subs")
            put("name", productId)
            put("title", productId)
            put("description", productId)
            if (type == PayProductType.INAPP) {
                put(
                    "oneTimePurchaseOfferDetails",
                    JSONObject().apply {
                        put("formattedPrice", formattedPrice)
                        put("priceAmountMicros", 0)
                        put("priceCurrencyCode", "USD")
                    }
                )
            } else {
                put(
                    "subscriptionOfferDetails",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("offerToken", offerToken)
                                put("basePlanId", "fake_base_plan")
                                put(
                                    "pricingPhases",
                                    JSONObject().apply {
                                        put(
                                            "pricingPhaseList",
                                            JSONArray().apply {
                                                put(
                                                    JSONObject().apply {
                                                        put("formattedPrice", formattedPrice)
                                                        put("priceAmountMicros", 0)
                                                        put("priceCurrencyCode", "USD")
                                                        put("billingPeriod", "P1M")
                                                        put("recurrenceMode", 1)
                                                    }
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        }

        val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(json.toString())
    }
}
