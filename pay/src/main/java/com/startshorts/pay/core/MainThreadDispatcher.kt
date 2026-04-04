package com.startshorts.pay.core

import android.os.Handler
import android.os.Looper

/**
 * 主线程派发器。
 *
 * 目的：
 * - Google Billing 的回调线程并不完全可控（不同 API/版本可能在 Binder 线程或主线程回调）。
 * - 为了简化上层业务处理，本模块统一在主线程触发 [com.startshorts.pay.api.PayPurchaseListener] 的回调。
 *
 * 约束：
 * - 该派发器只提供 “post 到主线程” 能力，不做队列合并/去重。
 * - 调用方如果在回调中执行耗时操作，应自行切换到后台线程（例如协程 Dispatchers.IO）。
 */
internal class MainThreadDispatcher {
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 确保 [block] 在主线程执行：
     * - 当前已在主线程：立即执行
     * - 否则：post 到主线程队列
     */
    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            handler.post(block)
        }
    }
}
