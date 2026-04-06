package com.startshorts.download.core

import java.io.File
import java.security.MessageDigest

/**
 * 文件校验工具：
 * - 用于下载完成后的完整性校验；
 * - 当前仅提供 SHA-256（与设计文档一致）。
 */
internal object FileVerifier {
    /**
     * 计算文件 SHA-256（小写 hex）。
     */
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
