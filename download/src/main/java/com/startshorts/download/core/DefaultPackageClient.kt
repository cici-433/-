package com.startshorts.download.core

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.Build
import com.startshorts.download.api.PackageActionResult
import com.startshorts.download.api.PackageClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlin.coroutines.resume

/**
 * PackageClient 默认实现（最小可用）：
 * - install/upgrade：使用 PackageInstaller Session 写入并 commit，收敛结果；
 * - uninstall：优先走 PackageInstaller.uninstall（可能需要系统权限/用户确认），收敛结果；
 * - upgradeWithPatch：当前仅提供接口占位（Patch 合成能力需要引入 PatchEngine）。
 *
 * 注意：
 * - 安装/卸载可能触发用户确认流程，系统会先回调 STATUS_PENDING_USER_ACTION，
 *   本实现会尝试启动确认 Intent，然后等待后续最终结果回调。
 * - 若宿主不具备相应权限或 ROM 拦截，会返回 Failure，并附带 statusMessage。
 */
internal class DefaultPackageClient(private val appContext: Context) : PackageClient {
    override suspend fun install(apkFile: File): PackageActionResult {
        return installOrUpgrade(apkFile)
    }

    override suspend fun upgrade(apkFile: File): PackageActionResult {
        return installOrUpgrade(apkFile)
    }

    override suspend fun upgradeWithPatch(
        patchFile: File,
        basePackageName: String,
        baseVersionCode: Long,
        targetVersionCode: Long,
        targetApkSha256: String?
    ): PackageActionResult {
        return PackageActionResult.Failure(
            packageName = basePackageName,
            reason = "PATCH_NOT_SUPPORTED"
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun uninstall(packageName: String): PackageActionResult {
        /**
         * 卸载：
         * - 部分设备/系统版本可能要求 REQUEST_DELETE_PACKAGES/DELETE_PACKAGES；
         * - 缺权限会抛 SecurityException，本实现会以 Failure 返回。
         */
        return withContext(Dispatchers.Main) {
            val installer = appContext.packageManager.packageInstaller
            val action = "com.startshorts.download.PACKAGE_UNINSTALL.${UUID.randomUUID()}"
            val intent = Intent(action)
            val pending = PendingIntent.getBroadcast(
                appContext,
                0,
                intent,
                pendingIntentFlags()
            )

            awaitResult(action) { receiver ->
                installer.uninstall(packageName, pending.intentSender)
                receiver.packageNameHint = packageName
            }
        }
    }

    private suspend fun installOrUpgrade(apkFile: File): PackageActionResult {
        /**
         * 安装/升级：
         * - 写入 base.apk 到 Session；
         * - commit 后通过广播回调收敛结果（Success/Failure/Canceled）。
         */
        return withContext(Dispatchers.Main) {
            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val parsed = parsePackageInfo(apkFile)
            parsed?.packageName?.let { params.setAppPackageName(it) }

            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                withContext(Dispatchers.IO) {
                    session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                        FileInputStream(apkFile).use { input ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                out.write(buf, 0, r)
                            }
                            out.flush()
                            session.fsync(out)
                        }
                    }
                }

                val action = "com.startshorts.download.PACKAGE_INSTALL.${UUID.randomUUID()}"
                val intent = Intent(action)
                val pending = PendingIntent.getBroadcast(appContext, 0, intent, pendingIntentFlags())

                awaitResult(action) { receiver ->
                    receiver.packageNameHint = parsed?.packageName
                    session.commit(pending.intentSender)
                }
            } catch (t: Throwable) {
                try {
                    session.abandon()
                } catch (_: Throwable) {
                }
                PackageActionResult.Failure(parsed?.packageName, t.message ?: "INSTALL_FAILED")
            } finally {
                try {
                    session.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private suspend fun awaitResult(
        action: String,
        start: (receiver: ResultReceiver) -> Unit
    ): PackageActionResult {
        /**
         * 统一等待广播回调：
         * - 动态注册 receiver，避免强依赖 Manifest；
         * - 支持协程取消时自动反注册，防止泄漏。
         */
        return suspendCancellableCoroutine { cont ->
            val filter = IntentFilter(action)
            val receiver = ResultReceiver { result ->
                if (!cont.isActive) return@ResultReceiver
                cont.resume(result)
            }

            receiver.register(appContext, filter)
            cont.invokeOnCancellation { receiver.unregister(appContext) }

            try {
                start(receiver)
            } catch (t: Throwable) {
                receiver.unregister(appContext)
                cont.resume(PackageActionResult.Failure(receiver.packageNameHint, t.message ?: "FAILED"))
            }
        }
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private fun parsePackageInfo(apkFile: File): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private class ResultReceiver(
        private val onFinalResult: (PackageActionResult) -> Unit
    ) : BroadcastReceiver() {
        var packageNameHint: String? = null
        private var registered = false

        override fun onReceive(context: Context, intent: Intent) {
            /**
             * STATUS_PENDING_USER_ACTION：
             * - 系统要求用户确认（例如未知来源安装/卸载确认）；
             * - 启动确认 Intent 后继续等待最终结果。
             */
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "UNKNOWN"
            val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: packageNameHint

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    } ?: return
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (_: Throwable) {
                        onFinalResult(PackageActionResult.Failure(pkg, "PENDING_USER_ACTION"))
                        unregister(context)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    onFinalResult(PackageActionResult.Success(pkg ?: ""))
                    unregister(context)
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    onFinalResult(PackageActionResult.Canceled(pkg))
                    unregister(context)
                }
                else -> {
                    onFinalResult(PackageActionResult.Failure(pkg, message))
                    unregister(context)
                }
            }
        }

        fun register(context: Context, filter: IntentFilter) {
            if (registered) return
            registered = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(this, filter)
            }
        }

        fun unregister(context: Context) {
            if (!registered) return
            registered = false
            try {
                context.unregisterReceiver(this)
            } catch (_: Throwable) {
            }
        }
    }
}
