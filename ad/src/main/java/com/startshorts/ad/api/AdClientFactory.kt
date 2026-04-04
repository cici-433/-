package com.startshorts.ad.api

import com.startshorts.ad.core.AdmobAdClient

/**
 * AdClient 创建入口。
 *
 * 当前默认实现为直连 AdMob 的 [AdmobAdClient]。
 */
object AdClientFactory {
    fun createAdClient(): AdClient = AdmobAdClient()
}
