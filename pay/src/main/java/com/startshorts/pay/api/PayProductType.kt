package com.startshorts.pay.api

/**
 * Google Play Billing 商品类型。
 *
 * - [INAPP]：一次性商品（消耗型/非消耗型）
 * - [SUBS]：订阅商品（包含 base plan / offer）
 */
enum class PayProductType {
    INAPP,
    SUBS
}
