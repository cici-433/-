package com.startshorts.pay.api

/**
 * 购买与连接状态监听器。
 *
 * 回调线程：
 * - 所有回调都在主线程触发，便于直接更新 UI。
 *
 * 回调频率与幂等：
 * - onPurchaseEvent 可能重复触发（回调补发/补单查询），务必用 purchaseToken 做幂等。
 * - onDisconnected 可能多次触发（系统/Play 服务断连重试）。
 */
interface PayPurchaseListener {

    /**
     * BillingClient 已连接并可用。
     */
    fun onReady()

    /**
     * BillingClient 与 Play 服务断开。
     *
     * 建议策略：
     * - UI 层展示“服务不可用/请稍后重试”
     * - 业务层可在合适时机调用 connect() 重新连接
     */
    fun onDisconnected()

    /**
     * 购买事件（包括用户购买回调与补单恢复回调）。
     */
    fun onPurchaseEvent(event: PayPurchaseEvent)

    /**
     * 发生错误。
     *
     * 注意：
     * - debugMessage 可能包含内部信息，不建议直接展示给用户。
     * - 建议根据 responseCode 映射用户可理解的提示文案，并结合补单策略兜底。
     */
    fun onError(error: PayError)
}
