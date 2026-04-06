package com.startshorts.download.api

import android.content.Context
import com.startshorts.download.core.DefaultPackageClient
import java.io.File

/**
 * 安装/升级/卸载统一结果：
 * - Success：动作完成
 * - Failure：动作失败（reason 用于上报与排障）
 * - Canceled：用户取消或系统中止（可单独统计取消率）
 */
sealed class PackageActionResult {
    data class Success(val packageName: String) : PackageActionResult()
    data class Failure(val packageName: String?, val reason: String) : PackageActionResult()
    data class Canceled(val packageName: String?) : PackageActionResult()
}

/**
 * APK 安装/升级/卸载能力抽象：
 * - 具体实现需要考虑 Android 版本差异、厂商拦截、权限与用户确认流程；
 * - 对应用市场场景建议结合策略中心进行灰度、熔断与失败原因聚合。
 */
interface PackageClient {
    /**
     * 安装 APK 文件（新装）。
     */
    suspend fun install(apkFile: File): PackageActionResult

    /**
     * 升级 APK 文件（全量覆盖安装）。
     */
    suspend fun upgrade(apkFile: File): PackageActionResult

    /**
     * 增量升级（Patch -> target APK -> install）：
     * - patchFile 是增量包；
     * - base/target 版本用于绑定与校验；
     * - targetApkSha256 用于合成后校验与安全兜底。
     */
    suspend fun upgradeWithPatch(
        patchFile: File,
        basePackageName: String,
        baseVersionCode: Long,
        targetVersionCode: Long,
        targetApkSha256: String?
    ): PackageActionResult

    suspend fun uninstall(packageName: String): PackageActionResult
}

/**
 * 默认实现工厂：
 * - 当前默认实现支持 Session 安装/卸载结果收敛；
 * - 增量升级（Patch 合成）需要业务侧接入 PatchEngine 后补齐。
 */
object PackageClientFactory {
    fun create(context: Context): PackageClient {
        return DefaultPackageClient(context.applicationContext)
    }
}
