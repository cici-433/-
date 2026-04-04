package com.startshorts.pay.api

/**
 * Google Play Billing 相关操作的统一错误模型。
 *
 * 该模型用于将不同 API（连接、查询、下单、ack/consume、补单）产生的错误以一致形式向上层抛出。
 *
 * 使用建议：
 * - 业务侧根据 [responseCode] 做策略分流（例如：USER_CANCELED 不提示错误；ITEM_ALREADY_OWNED 触发补单）。
 * - [debugMessage] 用于排查问题，不建议直接展示给用户。
 *
 * @see com.android.billingclient.api.BillingClient.BillingResponseCode
 *
 * @param responseCode Billing response code defined in [com.android.billingclient.api.BillingClient.BillingResponseCode].
 * @param debugMessage Raw debug message returned by Google Play Billing.
 * @param cause Optional exception cause from local processing.
 */
data class PayError(
    val responseCode: Int,
    val debugMessage: String? = null,
    val cause: Throwable? = null
)
