package com.startshorts.pay.api

/**
 * Google Play Console 中定义的商品标识。
 *
 * 说明：
 * - 同一个 `id` 在 Play Console 中的类型是固定的（INAPP 或 SUBS），查询/下单时必须匹配。
 * - 建议在业务侧使用常量集中管理商品 id，避免字符串散落导致配置错误。
 *
 * @param id Product ID in Google Play Console.
 * @param type INAPP for one-time purchases, SUBS for subscriptions.
 */
data class PayProductId(
    val id: String,
    val type: PayProductType
)
