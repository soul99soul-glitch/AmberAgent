package app.amber.core.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import app.amber.feature.live.LiveScreenSnapshot
import app.amber.feature.live.LiveUiTreeProcessor
import app.amber.feature.live.LiveWindowCandidate
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

    fun captureLiveUiSnapshot(
        ownPackageName: String,
        maxNodes: Int = 180,
    ): LiveScreenSnapshot? {
        val windowSnapshots = windows.orEmpty().mapNotNull { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                return@mapNotNull null
            }
            val root = window.root ?: return@mapNotNull null
            val bounds = window.boundsOrRootBounds(root)
            val snapshot = captureRootWindow(
                root = root,
                windowTitle = window.title?.toString().orEmpty(),
                maxNodes = maxNodes,
                bounds = bounds,
            )
            val candidate = snapshot.toCandidate(
                ownPackageName = ownPackageName,
                type = window.type,
                layer = window.layer,
                active = window.isActive,
                focused = window.isFocused,
                splitDivider = false,
            )
            snapshot.copy(candidate = candidate).takeIf { candidate.isEligible() }
        }

        val bestWindow = windowSnapshots.maxByOrNull { it.candidate.selectionScore() }
        if (bestWindow != null) return bestWindow.toSnapshot()

        return rootInActiveWindow
            ?.let { root ->
                val bounds = Rect()
                root.getBoundsInScreen(bounds)
                captureRootWindow(
                    root = root,
                    windowTitle = "",
                    maxNodes = maxNodes,
                    bounds = bounds,
                )
            }
            ?.let { captured ->
                val candidate = captured.toCandidate(
                    ownPackageName = ownPackageName,
                    type = AccessibilityWindowInfo.TYPE_APPLICATION,
                    layer = 0,
                    active = true,
                    focused = true,
                    splitDivider = false,
                )
                captured.copy(candidate = candidate).takeIf { candidate.isEligible() }
            }
            ?.toSnapshot()
    }

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

    private fun captureRootWindow(
        root: AccessibilityNodeInfo,
        windowTitle: String,
        maxNodes: Int,
        bounds: Rect,
    ): CapturedWindow {
        val lines = mutableListOf<String>()
        val visibleTexts = linkedSetOf<String>()
        var visited = 0
        var title = LiveUiTreeProcessor.sanitizeText(windowTitle)

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= maxNodes) return
            visited++

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = if (node.isPassword) "" else LiveUiTreeProcessor.sanitizeText(node.text?.toString().orEmpty())
            val description = if (node.isPassword) "" else LiveUiTreeProcessor.sanitizeText(node.contentDescription?.toString().orEmpty())
            if (title.isBlank() && text.isNotBlank()) {
                title = text.take(80)
            }
            if (node.isVisibleToUser) {
                listOf(text, description)
                    .filter { it.isNotBlank() && it != "[已隐藏敏感内容]" }
                    .forEach { visibleTexts += it }
            }

            val className = node.className?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val parts = buildList {
                if (className.isNotBlank()) add("class=$className")
                if (viewId.isNotBlank()) add("id=$viewId")
                if (text.isNotBlank()) add("text=$text")
                if (description.isNotBlank()) add("desc=$description")
                add("bounds=${rect.toShortString()}")
                if (node.isClickable) add("clickable=true")
                if (node.isFocused) add("focused=true")
                if (node.isEditable) add("editable=true")
            }
            lines += "${"  ".repeat(depth)}- ${parts.joinToString(" | ")}"

            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child -> visit(child, depth + 1) }
            }
        }

        visit(root, 0)
        val packageName = root.packageName?.toString().orEmpty()
        val uiTree = LiveUiTreeProcessor.sanitizeUiTree(lines.joinToString("\n"))
        val visibleText = visibleTexts.joinToString("\n").take(8_000)
        return CapturedWindow(
            packageName = packageName,
            appLabel = appLabel(packageName),
            title = title,
            uiTree = uiTree,
            visibleText = visibleText,
            contentText = LiveUiTreeProcessor.compressContentText(visibleText, uiTree),
            nodeCount = visited,
            bounds = Rect(bounds),
        )
    }

    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String {
        if (packageName.isBlank()) return ""
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun CapturedWindow.toSnapshot(): LiveScreenSnapshot =
        LiveScreenSnapshot(
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            uiTree = uiTree,
            visibleText = visibleText,
            contentText = contentText,
            windowDebugLabel = candidate.debugLabel(),
            nodeCount = nodeCount,
        )

    private fun CapturedWindow.toCandidate(
        ownPackageName: String,
        type: Int,
        layer: Int,
        active: Boolean,
        focused: Boolean,
        splitDivider: Boolean,
    ): LiveWindowCandidate =
        LiveWindowCandidate(
            type = type,
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            area = bounds.area(),
            visibleTextLength = contentText.length,
            visibleTextCount = contentText.lineSequence().count { it.isNotBlank() },
            nodeCount = nodeCount,
            layer = layer,
            active = active,
            focused = focused,
            ownApp = packageName == ownPackageName,
            splitDivider = splitDivider,
            systemLike = isSystemLikeWindow(type, packageName, appLabel, title),
        )

    private fun AccessibilityWindowInfo.boundsOrRootBounds(root: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        if (bounds.area() > 0) return bounds
        root.getBoundsInScreen(bounds)
        return bounds
    }

    private fun Rect.area(): Int =
        width().coerceAtLeast(0) * height().coerceAtLeast(0)

    private fun isSystemLikeWindow(type: Int, packageName: String, appLabel: String, title: String): Boolean {
        if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true
        if (type == AccessibilityWindowInfo.TYPE_SYSTEM) return true
        val lower = listOf(packageName, appLabel, title).joinToString(" ").lowercase()
        return packageName in systemWindowPackages ||
            "canvas window" in lower ||
            "分屏分割线" in lower ||
            "split" in lower && "divider" in lower
    }

    companion object {
        @Volatile
        private var activeService: AmberAccessibilityService? = null

        fun getActiveService(): AmberAccessibilityService? = activeService

        private val systemWindowPackages = setOf(
            "android",
            "com.android.systemui",
            "com.miui.systemui",
            "com.miui.home",
            "com.miui.securitycenter",
        )
    }
}

private data class CapturedWindow(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val uiTree: String,
    val visibleText: String,
    val contentText: String,
    val nodeCount: Int,
    val bounds: Rect,
    val candidate: LiveWindowCandidate = LiveWindowCandidate(
        type = 0,
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        area = bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0),
        visibleTextLength = contentText.length,
        visibleTextCount = contentText.lineSequence().count { it.isNotBlank() },
        nodeCount = nodeCount,
    ),
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
