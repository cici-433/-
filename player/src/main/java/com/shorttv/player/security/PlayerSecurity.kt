package com.shorttv.player.security

import android.content.Context
import android.net.Uri
import com.ss.ttm.player.BufferProcessCallback
import com.ss.ttvideoengine.TTVideoEngine
import com.ss.ttvideoengine.source.DirectUrlSource
import com.ss.ttvideoengine.strategy.source.StrategySource
import com.shorttv.player.PlayerError
import com.shorttv.player.PlayerItem
import com.shorttv.player.PlaybackSecurityPolicy
import video.lexo.decrypt.DecryptResult
import video.lexo.decrypt.DecryptState
import video.lexo.decrypt.PlayerDecryptor
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 安全模块的入口。
 *
 * 该类负责把“可播放的数据”转换成“安全可控的数据源”。
 * 它不关心播放器生命周期，只关心：
 * 1. 播放地址是否合规
 * 2. 播放请求是否需要补充鉴权参数
 * 3. 加密播放是否需要挂载解密回调
 */
class DefaultPlaybackSecurityManager(
    private val context: Context,
    private val policy: PlaybackSecurityPolicy,
    private val requestSigner: RequestSigner = DefaultRequestSigner()
) {

    private var released = false
    private val decryptSessions = ConcurrentHashMap<String, DecryptSession>()

    /**
     * 构建安全数据源。
     *
     * 火山播放器更适合消费 StrategySource，因此这里统一把播放请求收敛成 StrategySource。
     * 如果业务已经自己传入 strategySource，则直接复用；
     * 否则通过安全增强后的播放地址生成 DirectUrlSource。
     */
    @Throws(PlayerSecurityException::class)
    fun build(item: PlayerItem): SecuredPlaybackSource {
        released = false
        item.strategySource?.let {
            return SecuredPlaybackSource(
                strategySource = it,
                resolvedUrl = item.playUrl,
                needDecrypt = item.needDecrypt
            )
        }

        val url = item.playUrl ?: throw PlayerSecurityException(
            PlayerError(
                code = 4001,
                message = "播放源为空，既没有 strategySource，也没有 playUrl"
            )
        )

        val signedUrl = buildSignedUrl(url, item)
        return SecuredPlaybackSource(
            strategySource = createDirectUrlSource(signedUrl, item.cacheKey ?: item.id),
            resolvedUrl = signedUrl,
            needDecrypt = item.needDecrypt
        )
    }

    /**
     * 把安全能力挂载到指定播放器实例上。
     *
     * 当前主要负责在需要解密时安装火山播放器的 buffer 处理回调，
     * 让播放器在读取分片数据时能够边读边解密。
     */
    fun attach(player: TTVideoEngine, source: SecuredPlaybackSource) {
        if (!source.needDecrypt) {
            player.setBufferProcessCallback(null)
            return
        }
        player.setIntOption(TTVideoEngine.PLAYER_OPTION_ALLOW_ALL_PROTO_NAME, 1)
        player.setStringOption(TTVideoEngine.PLAYER_OPTION_BUFFER_PROCESS_PROTO_NAME, "jiuzhou")
        player.setStringOption(TTVideoEngine.PLAYER_OPTION_BUFFER_PROCESS_COVERT_ORDER, "21")
        player.setBufferProcessCallback(object : BufferProcessCallback() {
            override fun processBuffer(url: String?, data: ByteBuffer?): ProcessBufferResult {
                val session = getSession(url) ?: return ProcessBufferResult().apply {
                    ret = ERROR_CODE_NOT_FIND_SESSION
                }
                val result = session.decryptor.process(data)
                return ProcessBufferResult().apply {
                    when (result.result) {
                        DecryptResult.RESULT_EOF -> ret = ProcessBufferResult.EOF
                        DecryptResult.RESULT_EAGAIN -> ret = ProcessBufferResult.EAGAIN
                        else -> {
                            ret = result.result
                            buffer = result.data
                        }
                    }
                }
            }

            override fun isChunk(url: String?): Boolean {
                return true
            }

            override fun opened(url: String?, ret: Int) {
                if (released || url.isNullOrEmpty()) {
                    return
                }
                decryptSessions.getOrPut(url) {
                    DecryptSession(PlayerDecryptor(context, policy.decryptKey))
                }
            }

            override fun readed(url: String?, ret: Int) {
                val session = getSession(url) ?: return
                if (session.readState == DecryptSession.INIT_CODE) {
                    session.decryptor.updateState(DecryptState.START)
                }
                if (ret == ProcessBufferResult.EOF) {
                    session.decryptor.updateState(DecryptState.END)
                }
                session.readState = ret
            }

            override fun seeked(url: String?, ret: Long, where: Int) {
            }

            override fun closed(url: String?, ret: Int) {
                removeSession(url)?.decryptor?.release()
            }
        })
    }

    /**
     * 释放当前安全模块持有的所有解密相关资源。
     */
    fun release(player: TTVideoEngine?) {
        released = true
        player?.setBufferProcessCallback(null)
        decryptSessions.values.forEach { it.decryptor.release() }
        decryptSessions.clear()
    }

    /**
     * 统一做 uri 合规校验。
     *
     * 这里显式阻断 http 裸链，是为了让业务在开发阶段尽早发现风险。
     */
    @Throws(PlayerSecurityException::class)
    private fun validateUri(uri: Uri) {
        if (!policy.requireHttps) {
            return
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http") {
            throw PlayerSecurityException(
                PlayerError(
                    code = 4002,
                    message = "当前安全策略不允许使用 HTTP 播放源，实际 scheme=$scheme"
                )
            )
        }
    }

    /**
     * 在原始播放地址基础上补齐安全策略要求的鉴权参数与签名参数。
     */
    private fun buildSignedUrl(rawUrl: String, item: PlayerItem): String {
        val uri = Uri.parse(rawUrl)
        validateUri(uri)
        val queryParameters = linkedMapOf<String, String>()
        policy.authTokenProvider?.invoke()
            ?.takeIf { it.isNotBlank() }
            ?.let { queryParameters[policy.authTokenQueryName] = it }
        policy.nonceProvider?.invoke()
            ?.takeIf { it.isNotBlank() }
            ?.let { queryParameters[policy.nonceQueryName] = it }
        queryParameters["requestId"] = UUID.randomUUID().toString()
        val signedQuery = requestSigner.sign(item, queryParameters)
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.forEach { key ->
            uri.getQueryParameters(key).forEach { value ->
                builder.appendQueryParameter(key, value)
            }
        }
        signedQuery.forEach { (key, value) ->
            builder.appendQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    /**
     * 用安全增强后的播放地址构造火山播放器可直接消费的 [StrategySource]。
     */
    private fun createDirectUrlSource(url: String, cacheKeyHint: String): StrategySource {
        val uri = Uri.parse(url)
        val cacheKey = cacheKeyHint.ifBlank {
            uri.buildUpon().clearQuery().fragment(null).build().toString()
        }
        val urlItem = DirectUrlSource.UrlItem.Builder()
            .setUrl(url)
            .setCacheKey(cacheKey)
            .build()
        return DirectUrlSource.Builder()
            .setVid(cacheKey)
            .addItem(urlItem)
            .build()
    }

    /**
     * 获取指定 url 对应的解密会话。
     */
    private fun getSession(url: String?): DecryptSession? {
        if (url.isNullOrEmpty()) {
            return null
        }
        return decryptSessions[url]
    }

    /**
     * 删除并返回指定 url 对应的解密会话。
     */
    private fun removeSession(url: String?): DecryptSession? {
        if (url.isNullOrEmpty()) {
            return null
        }
        return decryptSessions.remove(url)
    }

    private companion object {
        const val ERROR_CODE_NOT_FIND_SESSION = -100404
    }
}

/**
 * 安全处理后的播放源描述。
 *
 * 它把最终给播放器消费的 source、解析后的真实地址
 * 以及是否需要解密这三个关键信息打包在一起。
 */
data class SecuredPlaybackSource(
    val strategySource: StrategySource,
    val resolvedUrl: String?,
    val needDecrypt: Boolean
)

/**
 * 请求签名器。
 *
 * 实际业务可以把签名算法替换成服务端约定的版本，
 * 例如 HMAC、时间戳签名或设备指纹签名。
 */
interface RequestSigner {
    /**
     * 根据播放项和待签名参数生成最终要追加的查询参数。
     */
    fun sign(item: PlayerItem, queryParameters: Map<String, String>): Map<String, String>
}

/**
 * 默认签名器。
 *
 * 这里没有引入真正的加密摘要算法，
 * 而是提供了一个简单的“可替换实现”，方便播放器模块先稳定落地。
 */
class DefaultRequestSigner : RequestSigner {
    /**
     * 基于播放项信息和待签名参数生成一个简化版 signature。
     *
     * 这里的实现更偏向演示与占位，方便业务之后替换为真实签名算法。
     */
    override fun sign(item: PlayerItem, queryParameters: Map<String, String>): Map<String, String> {
        val signatureSeed = buildString {
            append(item.id)
            append('|')
            append(item.title)
            append('|')
            append(queryParameters.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" })
        }
        return queryParameters + mapOf("signature" to signatureSeed.hashCode().toString())
    }
}

/**
 * 安全模块统一异常。
 */
class PlayerSecurityException(
    val error: PlayerError
) : IOException(error.message, error.cause)

/**
 * 单个播放地址对应的解密会话。
 *
 * 每个会话持有独立的 [PlayerDecryptor]，
 * 并记录最近一次读取状态，方便正确维护 START / END 状态机。
 */
private class DecryptSession(
    val decryptor: PlayerDecryptor,
    var readState: Int = INIT_CODE
) {
    companion object {
        const val INIT_CODE = -9999999
    }
}
