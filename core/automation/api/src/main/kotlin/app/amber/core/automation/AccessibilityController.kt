package app.amber.core.automation

import android.graphics.Rect

/**
 * AccessibilityServiceController surface used by ScreenAutomationTools
 * (lifted out of `:app/.../AmberAccessibilityService.kt`). The full Service
 * class continues to live in :app — Android `AccessibilityService` extension
 * requires the manifest namespace + can't easily move. This interface
 * captures only the methods downstream tools call on the running service.
 *
 * Active-instance lookup is provided by [getActiveAccessibilityController],
 * which routes through the in-process global the Service writes on connect.
 */
interface AccessibilityController {

    // 手势方法返回 true 只表示注入完成（dispatchGesture onCompleted），
    // 不代表目标 UI 产生了效果；效果需经 findTextNodes/dumpUiTree 复核。
    suspend fun tap(x: Float, y: Float, durationMillis: Long = 80): Boolean

    suspend fun longPress(x: Float, y: Float, durationMillis: Long = 600): Boolean

    suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMillis: Long = 350,
    ): Boolean

    fun setFocusedText(text: String): Boolean

    fun dumpUiTree(maxNodes: Int = 120): String

    fun findTextNodes(query: String, maxNodes: Int = 120): List<AccessibilityTextMatch>

    fun back(): Boolean

    fun home(): Boolean

    /**
     * Captures the current active accessibility window. Implementations may
     * return null when the service cannot safely read the active window.
     * Callers invoke this from [kotlinx.coroutines.Dispatchers.Main].
     */
    fun captureScreenSnapshot(): ScreenSnapshot? = null

    /**
     * Performs one action against a snapshot previously returned by
     * [captureScreenSnapshot]. Implementations must reject stale snapshots;
     * the default keeps older controller implementations safe and explicit.
     */
    fun performScreenAction(
        snapshot: ScreenSnapshot,
        action: ScreenAction,
    ): ScreenActionReceipt = ScreenActionReceipt(
        status = "rejected",
        dispatched = false,
        reason = "unsupported",
    )
}

data class ScreenNode(
    val ref: String,
    val label: String,
    val viewId: String,
    val className: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
)

data class ScreenSnapshot(
    val id: String,
    val packageName: String,
    val windowId: Int,
    val nodes: List<ScreenNode>,
)

enum class ScreenActionKind {
    CLICK,
    TYPE,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
}

data class ScreenAction(
    val kind: ScreenActionKind,
    val ref: String,
    val text: String? = null,
)

data class ScreenActionReceipt(
    val status: String,
    val dispatched: Boolean,
    val reason: String? = null,
)

data class AccessibilityTextMatch(
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val enabled: Boolean,
)

/**
 * Holder for the in-process Service instance. The :app-side
 * AmberAccessibilityService writes `activeController = this` on
 * `onServiceConnected()` and clears it in `onUnbind()`. Tools call
 * [getActiveAccessibilityController] to reach the live instance.
 */
object AccessibilityActive {
    @Volatile
    var activeController: AccessibilityController? = null
}

fun getActiveAccessibilityController(): AccessibilityController? =
    AccessibilityActive.activeController
