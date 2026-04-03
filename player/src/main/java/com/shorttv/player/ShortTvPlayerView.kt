package com.shorttv.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout

/**
 * 播放器承载 view。
 *
 * 这个类的职责非常单一：
 * 1. 为播放器提供一个稳定的 TextureView 渲染面
 * 2. 对外屏蔽 Surface / SurfaceTexture 的细节
 * 3. 让业务层只需要关心“把播放器挂到哪里”
 *
 * 之所以单独封装一个 view，而不是让页面自己管理 TextureView，
 * 是因为这样可以保证播放器库自己的生命周期更可控。
 */
class ShortTvPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    private val textureView = TextureView(context)

    private var callback: SurfaceCallback? = null
    private var currentSurface: Surface? = null

    init {
        textureView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        textureView.surfaceTextureListener = this
        addView(textureView)
    }

    /**
     * 注册 surface 回调。
     *
     * 播放器 attach 到该 view 时，
     * 会通过这个回调拿到可用的渲染 surface。
     */
    fun setSurfaceCallback(callback: SurfaceCallback?) {
        this.callback = callback
        currentSurface?.let { callback?.onSurfaceReady(it) }
    }

    /**
     * 返回内部真正参与渲染的 [TextureView]。
     *
     * 这个方法主要给播放器实现层使用，
     * 例如设置 displayMode、读取渲染目标等。
     */
    fun renderView(): TextureView {
        return textureView
    }

    /**
     * 当底层 [SurfaceTexture] 首次可用时创建 [Surface]，
     * 并通知播放器开始绑定渲染目标。
     */
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        currentSurface = Surface(surface)
        currentSurface?.let { callback?.onSurfaceReady(it) }
    }

    /**
     * 当前实现不关心尺寸变化，
     * 因为布局和显示模式由外层 view 与播放器共同控制。
     */
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
    }

    /**
     * 当 [SurfaceTexture] 销毁时，先让播放器解除绑定，
     * 再释放当前 [Surface]，避免资源泄漏。
     */
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        callback?.onSurfaceDestroyed()
        currentSurface?.release()
        currentSurface = null
        return true
    }

    /**
     * 帧内容刷新时不需要额外处理，
     * 因为播放器已经直接把画面输出到当前 texture。
     */
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    /**
     * 渲染 surface 生命周期回调。
     *
     * 这个接口的意义是把 View 层的 surface 生命周期
     * 和播放器实现层解耦开。
     */
    interface SurfaceCallback {
        /**
         * 当渲染 surface 可用时通知播放器。
         */
        fun onSurfaceReady(surface: Surface)

        /**
         * 当 surface 销毁时通知播放器解除绑定。
         */
        fun onSurfaceDestroyed()
    }
}
