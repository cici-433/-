package com.startshorts.pay.api

import android.content.Context
import com.startshorts.pay.core.FakePayClient
import com.startshorts.pay.core.GooglePayClient

/**
 * [PayClient] 工厂。
 *
 * 设计目标：
 * - 业务侧只依赖 `api` 包暴露的接口与数据结构，不直接依赖 Google Play Billing 的实现细节。
 * - 默认返回基于 Google Play Billing 的实现（[GooglePayClient]）。
 *
 * 使用建议：
 * - 传入 [Context.getApplicationContext]，避免将 Activity/Service 的 Context 意外持有导致泄漏。
 * - 创建后尽快调用 [PayClient.connect] 建立连接；页面退出或不再需要支付能力时调用 [PayClient.disconnect]。
 */
object PayClientFactory {

    /**
     * 创建 Google Play Billing 的 [PayClient] 实现。
     *
     * @param context 任意 Context。内部会强制使用 applicationContext。
     */
    fun createGooglePayClient(context: Context): PayClient =
        GooglePayClient(context.applicationContext)

    /**
     * 创建本地假支付实现，用于本地开发/自动化测试环境跑通“客户端支付流程”。
     *
     * 注意：
     * - 不依赖 Google Play 环境，不会真实扣款
     * - 订单仅保存在内存中，进程重启会丢失
     */
    fun createFakePayClient(): PayClient = FakePayClient()
}
