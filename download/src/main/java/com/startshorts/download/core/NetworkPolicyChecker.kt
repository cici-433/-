package com.startshorts.download.core

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.startshorts.download.api.NetworkPolicy

/**
 * 网络策略判断（best-effort）：
 * - 若宿主未声明 ACCESS_NETWORK_STATE 或系统限制导致读取失败，则默认允许（避免误伤下载能力）。
 * - 业务如需强策略，建议在上层自行做权限与网络状态检查后再调用下载框架。
 */
internal object NetworkPolicyChecker {
    @SuppressLint("MissingPermission")
    fun isAllowed(context: Context, policy: NetworkPolicy): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            when (policy) {
                NetworkPolicy.Any -> true
                NetworkPolicy.WifiOnly -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                NetworkPolicy.UnmeteredOnly -> !cm.isActiveNetworkMetered
            }
        } catch (_: SecurityException) {
            true
        }
    }
}
