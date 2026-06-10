package app.amber.core.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import app.amber.feature.live.LiveScreenSnapshot
import app.amber.feature.live.LiveUiTreeProcessor
import app.amber.feature.live.LiveWindowCandidate
import kotlin.coroutines.resume

class AmberAccessibilityService : AccessibilityService(), AccessibilityController {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> _screenEvents.tryEmit(
                ScreenEvent(
                    packageName = e.packageName?.toString().orEmpty(),
                    eventType = e.eventType,
                    atMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    data class ScreenEvent(val packageName: String, val eventType: Int, val atMillis: Long)

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        activeService = this
        AccessibilityActive.activeController = this
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        if (AccessibilityActive.activeController === this) {
            AccessibilityActive.activeController = null
        }
        super.onDestroy()
    }

    override suspend fun tap(x: Float, y: Float, durationMillis: Long): Boolean =
        gesture(path = Path().apply { moveTo(x, y) }, durationMillis = durationMillis)

    override suspend fun longPress(x: Float, y: Float, durationMillis: Long): Boolean =
        gesture(path = Path().apply { moveTo(x, y) }, durationMillis = durationMillis)

    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return gesture(path = path, durationMillis = durationMillis)
    }

    override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    override fun setFocusedText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * 在指定包名的窗口里填入文本：优先该窗口的输入焦点节点，否则深度优先找第一个
     * 可编辑节点。供 Live 伴随"只填不发"使用 —— 点击 Amber 自己的按钮时活动窗口
     * 是 Amber，rootInActiveWindow 拿不到对方输入框，必须按包名扫 windows。
     */
    fun setTextInPackage(packageName: String, text: String): Boolean {
        if (packageName.isBlank()) return false
        for (window in windows.orEmpty()) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != packageName) continue
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findFirstEditable(root, depth = 0)
                ?: continue
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return true
        }
        return false
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 80) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * API 30+ 截当前屏为软件 Bitmap；低版本或失败返回 null（调用方降级保守模式）。
     */
    @Suppress("NewApi")
    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(null)
                    }
                },
            )
        }
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

    override fun dumpUiTree(maxNodes: Int): String {
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

    override fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> {
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

        private val _screenEvents = MutableSharedFlow<ScreenEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /** 屏幕变化事件（窗口切换/内容变化），LiveModeManager 据此驱动分析。 */
        val screenEvents: SharedFlow<ScreenEvent> = _screenEvents.asSharedFlow()

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

// AccessibilityTextMatch moved to :core:automation:api so feature tools can
// consume the wire type without pulling the :app-only AmberAccessibilityService.
