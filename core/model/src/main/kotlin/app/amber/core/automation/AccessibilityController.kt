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
}

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
