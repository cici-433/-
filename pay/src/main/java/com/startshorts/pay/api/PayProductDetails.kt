package com.startshorts.pay.api

import com.android.billingclient.api.ProductDetails

/**
 * [ProductDetails] 的业务侧友好封装，用于 UI 展示与拉起购买。
 *
 * 字段约定：
 * - [raw]：完整的 Google Billing ProductDetails（用于 launchBillingFlow）
 * - [formattedPrice]：用于 UI 展示的单价字符串
 *
 * 订阅（SUBS）注意事项：
 * - Billing v5+ 订阅使用 base plan/offer 机制：下单必须指定 offerToken。
 * - 一个订阅可能存在多个 base plan/offer，且每个 offer 有多段价格（例如试用价、首期优惠价、续期价）。
 * - 本结构体的 [formattedPrice] 只做“默认展示价”的便捷输出；业务方若需要严格展示策略（例如优先展示续期价），
 *   应自行从 [raw.subscriptionOfferDetails] 的 pricingPhases 中选择。
 *
 * 一次性商品（INAPP）注意事项：
 * - UI 展示价一般来自 [ProductDetails.oneTimePurchaseOfferDetails]。
 */
data class PayProductDetails(
    val productId: String,
    val type: PayProductType,
    val title: String,
    val description: String,
    val formattedPrice: String?,
    val raw: ProductDetails
)
