package com.startshorts.pay.api

import com.android.billingclient.api.Purchase

/**
 * Google Play Billing 推送的“购买事件”。
 *
 * 事件来源：
 * - [PurchasesUpdated]：用户完成/取消/失败一次购买流程后，Google Play 回调的结果
 * - [Restored]：调用 [PayClient.queryPurchases] 主动查询到的“已拥有订单”，用于补单/恢复
 *
 * 重要提示：
 * - 同一个 purchaseToken 可能在不同时间重复出现（回调补发/补单查询），业务侧必须做幂等。
 * - [PurchasesUpdated.purchases] 可能为空（例如用户取消），此时应结合 responseCode 判断场景。
 */
sealed class PayPurchaseEvent {

    /**
     * 用户完成一次购买流程后返回的更新事件。
     *
     * @param purchases Google Play 返回的订单列表；当 responseCode != OK 时通常为空。
     * @param responseCode BillingResponseCode。
     * @param debugMessage Google Play 返回的 debug message（用于排查，勿直接展示给用户）。
     */
    data class PurchasesUpdated(
        val purchases: List<Purchase>,
        val responseCode: Int,
        val debugMessage: String?
    ) : PayPurchaseEvent()

    /**
     * 主动恢复（补单）查询返回的结果事件。
     *
     * @param inAppPurchases 当前账号已拥有的一次性商品订单（INAPP）。
     * @param subsPurchases 当前账号已拥有的订阅订单（SUBS）。
     */
    data class Restored(
        val inAppPurchases: List<Purchase>,
        val subsPurchases: List<Purchase>
    ) : PayPurchaseEvent()
}
