package me.rerere.rikkahub.data.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AmberAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        activeService = this
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    suspend fun tap(x: Float, y: Float, durationMillis: Long = 80): Boolean =
        gesture(path = Path().apply { moveTo(x, y) }, durationMillis = durationMillis)

    suspend fun longPress(x: Float, y: Float, durationMillis: Long = 600): Boolean =
        gesture(path = Path().apply { moveTo(x, y) }, durationMillis = durationMillis)

    suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long = 350): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return gesture(path = path, durationMillis = durationMillis)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun setFocusedText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun activePackageName(): String? =
        rootInActiveWindow?.packageName?.toString()

    fun dumpUiTree(maxNodes: Int = 120): String {
        val root = rootInActiveWindow ?: return ""
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (lines.size >= maxNodes) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val label = listOfNotNull(
                node.className?.toString(),
                node.viewIdResourceName,
                node.text?.toString()?.takeIf { it.isNotBlank() },
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            ).joinToString(" | ")
            lines += "${"  ".repeat(depth)}- $label bounds=$rect clickable=${node.isClickable} focused=${node.isFocused}"
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child -> visit(child, depth + 1) }
            }
        }
        visit(root, 0)
        return lines.joinToString("\n")
    }

    fun findTextNodes(query: String, maxNodes: Int = 120): List<AccessibilityTextMatch> {
        val root = rootInActiveWindow ?: return emptyList()
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val results = mutableListOf<AccessibilityTextMatch>()
        var visited = 0

        fun visit(node: AccessibilityNodeInfo) {
            if (visited >= maxNodes) return
            visited++
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val haystack = "$text\n$description"
            if (haystack.contains(needle, ignoreCase = true)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                results += AccessibilityTextMatch(
                    text = text,
                    contentDescription = description,
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName,
                    bounds = Rect(rect),
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                )
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(::visit)
            }
        }

        visit(root)
        return results
    }

    private suspend fun gesture(path: Path, durationMillis: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
                .build()
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }

    companion object {
        @Volatile
        private var activeService: AmberAccessibilityService? = null

        fun getActiveService(): AmberAccessibilityService? = activeService
    }
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
