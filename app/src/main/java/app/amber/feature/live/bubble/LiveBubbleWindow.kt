package app.amber.feature.live.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.amber.core.automation.AmberAccessibilityService
import kotlin.math.roundToInt

/**
 * 伴随气泡的悬浮窗宿主：TYPE_ACCESSIBILITY_OVERLAY 挂在无障碍服务上，
 * 不需要 SYSTEM_ALERT_WINDOW 权限；服务断开时系统自动移除窗口。
 * 自持 Lifecycle/SavedState owner——不能借 Activity 的（后台 STOPPED 会冻结重组）。
 * 所有方法仅主线程调用（LiveModeManager.runLoop 在 Main.immediate）。
 */
class LiveBubbleWindow {
    private var host: ComposeView? = null
    private var hostService: AmberAccessibilityService? = null
    private var owner: BubbleLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = host != null

    /** 已显示且宿主服务相同则幂等；服务实例换了（重连）则先收回再重挂。 */
    fun show(service: AmberAccessibilityService, content: @Composable () -> Unit) {
        if (host != null && hostService === service) return
        hide()
        val newOwner = BubbleLifecycleOwner().apply { create(); resume() }
        val view = ComposeView(service).apply {
            setViewTreeLifecycleOwner(newOwner)
            setViewTreeSavedStateRegistryOwner(newOwner)
            setContent(content)
        }
        val dm = service.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - (64 * dm.density).roundToInt() // 初始贴右
            y = dm.heightPixels / 3
        }
        val added = runCatching { windowManager(service).addView(view, lp) }.isSuccess
        if (!added) {
            newOwner.destroy()
            return
        }
        host = view
        hostService = service
        owner = newOwner
        params = lp
    }

    fun hide() {
        val view = host ?: return
        runCatching { hostService?.let { windowManager(it).removeViewImmediate(view) } }
        owner?.destroy()
        host = null
        hostService = null
        owner = null
        params = null
    }

    /** 拖动中：位移叠加、夹回屏内、立即应用。 */
    fun moveBy(dx: Float, dy: Float) {
        val view = host ?: return
        val lp = params ?: return
        val service = hostService ?: return
        lp.x += dx.roundToInt()
        lp.y += dy.roundToInt()
        clamp(service, lp, view.width, view.height)
        runCatching { windowManager(service).updateViewLayout(view, lp) }
    }

    /** 松手贴边（左右取近）。 */
    fun snapToEdge() {
        val view = host ?: return
        val lp = params ?: return
        val service = hostService ?: return
        val screenW = service.resources.displayMetrics.widthPixels
        lp.x = if (lp.x + view.width / 2 < screenW / 2) 0 else (screenW - view.width).coerceAtLeast(0)
        runCatching { windowManager(service).updateViewLayout(view, lp) }
    }

    /** 内容尺寸变化（展开/收起卡片）后调用：等重新布局完把窗口夹回屏内。 */
    fun requestReclamp() {
        val view = host ?: return
        view.post {
            val lp = params ?: return@post
            val service = hostService ?: return@post
            clamp(service, lp, view.width, view.height)
            runCatching { windowManager(service).updateViewLayout(view, lp) }
        }
    }

    private fun clamp(context: Context, lp: WindowManager.LayoutParams, w: Int, h: Int) {
        val dm = context.resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
    }

    private fun windowManager(context: Context): WindowManager =
        context.getSystemService(WindowManager::class.java)

    /** 常驻 RESUMED 的窗口生命周期——Compose 重组不受宿主 Activity 后台影响。 */
    private class BubbleLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun create() {
            savedState.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
