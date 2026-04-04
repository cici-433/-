package com.startshorts.pay.core

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.startshorts.pay.api.PayError
import com.startshorts.pay.api.PayProductType

/**
 * 将业务侧的 [PayProductType] 映射为 Google Billing 的 productType 常量。
 *
 * @see BillingClient.ProductType
 */
internal fun PayProductType.toBillingProductType(): String {
    return when (this) {
        PayProductType.INAPP -> BillingClient.ProductType.INAPP
        PayProductType.SUBS -> BillingClient.ProductType.SUBS
    }
}

/**
 * 将 [BillingResult] 统一转换为 [PayError]，便于上层进行统一埋点与错误处理。
 *
 * 注意：
 * - [BillingResult.debugMessage] 仅用于排查问题，不建议直接展示给用户。
 * - [cause] 用于携带本地异常（例如解析/线程切换过程中的异常），不一定存在。
 */
internal fun BillingResult.toPayError(cause: Throwable? = null): PayError {
    return PayError(
        responseCode = this.responseCode,
        debugMessage = this.debugMessage,
        cause = cause
    )
}
