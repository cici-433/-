package com.startshorts.pay.api

/**
 * 支付相关操作的轻量结果封装。
 *
 * 设计目的：
 * - 避免在上层业务中大量 try/catch（业务方按 Success/Failure 分支处理）
 * - 统一携带 [PayError] 便于埋点、排错与策略分流
 *
 * 注意：
 * - 购买结果不会通过 [PayResult] 返回（因为购买流程是异步回调），而是通过 [PayPurchaseListener] 投递。
 */
sealed class PayResult<out T> {
    data class Success<T>(val value: T) : PayResult<T>()
    data class Failure(val error: PayError) : PayResult<Nothing>()

    /**
     * 返回成功值；失败返回 null。
     *
     * 适用于只关心成功分支的场景，但错误分支仍建议通过日志/埋点记录。
     */
    fun getOrNull(): T? = (this as? Success<T>)?.value
}
