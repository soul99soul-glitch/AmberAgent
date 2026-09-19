package app.amber.core.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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

    /**
     * Reads the active window on the accessibility service main thread. The
     * caller owns the main-thread dispatch; every root, child and window
     * obtained here is recycled before this method returns.
     */
    override fun captureScreenSnapshot(): ScreenSnapshot? = withActiveRoot { root, windowId, packageName ->
        buildScreenSnapshot(root, windowId, packageName)
    }

    /**
     * Applies one action only when the supplied snapshot still describes the
     * active window. No coordinate fallback is used: an unsupported or stale
     * target is handed back to the caller as a receipt.
     */
    override fun performScreenAction(
        snapshot: ScreenSnapshot,
        action: ScreenAction,
    ): ScreenActionReceipt {
        val expected = snapshot.nodes.firstOrNull { it.ref == action.ref }
            ?: return rejectedScreenReceipt("unknown_ref")
        if (snapshot.packageName.isBlank() || action.ref.isBlank()) {
            return rejectedScreenReceipt("invalid_target")
        }
        if (action.kind == ScreenActionKind.TYPE && action.text == null) {
            return rejectedScreenReceipt("missing_text")
        }

        return withActiveRoot { root, windowId, packageName ->
            if (packageName != snapshot.packageName || windowId != snapshot.windowId) {
                return@withActiveRoot staleScreenReceipt("active_window_changed")
            }
            // Snapshot verification and path resolution must use the same root generation.
            val fresh = buildScreenSnapshot(root, windowId, packageName)
                ?: return@withActiveRoot staleScreenReceipt("snapshot_unavailable")
            if (fresh.id != snapshot.id) return@withActiveRoot staleScreenReceipt("snapshot_mismatch")

            val target = resolveScreenNode(root, action.ref)
                ?: return@withActiveRoot staleScreenReceipt("node_unavailable")
            try {
                if (!runCatching { target.refresh() }.getOrDefault(false)) {
                    return@withActiveRoot staleScreenReceipt("node_refresh_failed")
                }

                val displayBounds = screenBounds()
                    ?: return@withActiveRoot staleScreenReceipt("display_unavailable")
                val validation = validateScreenTarget(
                    node = target,
                    expected = expected,
                    expectedPackageName = snapshot.packageName,
                    displayBounds = displayBounds,
                )
                if (validation != null) {
                    return@withActiveRoot validation
                }

                when (action.kind) {
                    ScreenActionKind.CLICK -> {
                        if (!target.isClickable) {
                            rejectedScreenReceipt("not_clickable")
                        } else {
                            dispatchScreenAction(target, AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }

                    ScreenActionKind.TYPE -> {
                        if (!target.isEditable) {
                            rejectedScreenReceipt("not_editable")
                        } else {
                            val arguments = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    action.text,
                                )
                            }
                            dispatchScreenAction(
                                node = target,
                                action = AccessibilityNodeInfo.ACTION_SET_TEXT,
                                arguments = arguments,
                                readback = action.text,
                            )
                        }
                    }

                    ScreenActionKind.SCROLL_FORWARD -> {
                        if (!target.isScrollable) {
                            rejectedScreenReceipt("not_scrollable")
                        } else {
                            dispatchScreenAction(
                                target,
                                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                            )
                        }
                    }

                    ScreenActionKind.SCROLL_BACKWARD -> {
                        if (!target.isScrollable) {
                            rejectedScreenReceipt("not_scrollable")
                        } else {
                            dispatchScreenAction(
                                target,
                                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                            )
                        }
                    }
                }
            } finally {
                if (target !== root) {
                    runCatching { target.recycle() }
                }
            }
        } ?: staleScreenReceipt("active_window_unavailable")
    }

    override fun setFocusedText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 仲裁契约（蓝图 §7.2 P0-5）：读取目标包名输入框的当前文本；找不到返回 null。 */
    fun readTextInPackage(packageName: String): String? {
        val target = locateEditableInPackage(packageName) ?: return null
        return target.text?.toString()
    }

    /** 写入后回读验证：定位→写入→refresh→重读比对。MISMATCH = 写入未被目标接受
     *  （或被其它写入者并发覆盖），调用方据此熔断，不重试不排队。 */
    fun setTextInPackageVerified(packageName: String, text: String): LiveFillOutcome {
        val target = locateEditableInPackage(packageName) ?: return LiveFillOutcome.NOT_FOUND
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return LiveFillOutcome.NOT_FOUND
        }
        val readback = runCatching {
            target.refresh()
            locateEditableInPackage(packageName)?.text?.toString()
        }.getOrNull() ?: return LiveFillOutcome.UNKNOWN
        return if (readback == text) LiveFillOutcome.SUCCESS else LiveFillOutcome.MISMATCH
    }

    private fun locateEditableInPackage(packageName: String): AccessibilityNodeInfo? {
        if (packageName.isBlank()) return null
        for (window in windows.orEmpty()) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != packageName) continue
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findFirstEditable(root, depth = 0)
            if (target != null) return target
        }
        return null
    }

    private fun findFirstEditable(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: IntArray = intArrayOf(0),
    ): AccessibilityNodeInfo? {
        // 节点数上限（Phase 7 检查建议 1）：目标进程主线程繁忙时每次 getChild 都是
        // binder 调用，无界遍历会阻塞 Amber 主线程。
        if (depth > 80 || visited[0] >= 400) return null
        visited[0]++
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstEditable(child, depth + 1, visited)?.let { return it }
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
            // takeScreenshot 在服务未声明 canTakeScreenshot、或被系统限流时会同步抛异常；
            // 吞掉并 resume(null)，让调用方降级保守模式，而不是把整次分析炸成失败。
            val launched = runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.isSuccess
            if (!launched && cont.isActive) cont.resume(null)
        }
    }

    /**
     * API 34+ 按窗口截图（takeScreenshotOfWindow）：只拍候选窗口，气泡等 overlay 不混入。
     * 窗口已失效（id 过期）或低版本返回 null，调用方回退整屏 [takeScreenshotBitmap]。
     */
    suspend fun takeScreenshotOfWindowBitmap(windowId: Int): Bitmap? {
        if (windowId < 0 || Build.VERSION.SDK_INT < 34) return null
        return suspendCancellableCoroutine { cont ->
            val launched = runCatching {
                takeScreenshotOfWindow(
                    windowId,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.isSuccess
            if (!launched && cont.isActive) cont.resume(null)
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
            snapshot.copy(candidate = candidate, windowId = window.id).takeIf { candidate.isEligible() }
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

    private fun buildScreenSnapshot(
        root: AccessibilityNodeInfo,
        windowId: Int,
        packageName: String,
    ): ScreenSnapshot? {
        if (packageName.isBlank()) return null
        val displayBounds = screenBounds() ?: return null
        val state = ScreenTraversalState()
        val tree = captureScreenNode(
            node = root,
            ref = ROOT_SCREEN_REF,
            depth = 0,
            displayBounds = displayBounds,
            state = state,
        ) ?: return null
        if (state.failed || state.truncated) return null

        val records = buildList { flattenScreenNodes(tree, this) }
        if (records.isEmpty() || records.any { it.ref.isBlank() }) return null
        val nodes = records.map { record -> record.toPublicNode() }
        val id = hashScreenSnapshot(packageName, windowId, records)
        return ScreenSnapshot(
            id = id,
            packageName = packageName,
            windowId = windowId,
            nodes = nodes,
        )
    }

    private fun captureScreenNode(
        node: AccessibilityNodeInfo,
        ref: String,
        depth: Int,
        displayBounds: Rect,
        state: ScreenTraversalState,
    ): ScreenNodeRecord? {
        if (depth > MAX_SCREEN_DEPTH || state.visited >= MAX_SCREEN_NODES) {
            state.truncated = true
            return null
        }
        state.visited++

        val bounds = Rect()
        val visible = runCatching {
            node.getBoundsInScreen(bounds)
            node.isVisibleToUser
        }.getOrElse {
            state.failed = true
            return null
        }
        val included = visible && isValidScreenBounds(bounds, displayBounds)
        val record = runCatching {
            val password = node.isPassword
            ScreenNodeRecord(
                ref = ref,
                rawText = if (password) "" else node.text?.toString().orEmpty().ifBlank { node.hintText?.toString().orEmpty() },
                rawContentDescription = if (password) "" else node.contentDescription?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                bounds = Rect(bounds),
                clickable = node.isClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                enabled = node.isEnabled,
                password = password,
                included = included,
            )
        }.getOrElse {
            state.failed = true
            return null
        }

        try {
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrElse {
                    state.failed = true
                    return null
                } ?: continue
                try {
                    captureScreenNode(
                        node = child,
                        ref = "$ref/$index",
                        depth = depth + 1,
                        displayBounds = displayBounds,
                        state = state,
                    )?.let(record.children::add)
                    if (state.failed || state.truncated) return null
                } finally {
                    runCatching { child.recycle() }
                }
            }
        } catch (_: Throwable) {
            state.failed = true
            return null
        }
        return record
    }

    private fun flattenScreenNodes(
        record: ScreenNodeRecord,
        destination: MutableList<ScreenNodeRecord>,
    ) {
        if (record.included) destination += record
        record.children.forEach { child -> flattenScreenNodes(child, destination) }
    }

    private fun ScreenNodeRecord.toPublicNode(): ScreenNode = ScreenNode(
        ref = ref,
        label = publicScreenLabel(this),
        viewId = viewId,
        className = className,
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        enabled = enabled,
    )

    private fun hashScreenSnapshot(
        packageName: String,
        windowId: Int,
        records: List<ScreenNodeRecord>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update(';'.code.toByte())
        }
        field(packageName)
        field(windowId.toString())
        records.forEach { record ->
            field(record.ref)
            field(record.rawText)
            field(record.rawContentDescription)
            field(record.viewId)
            field(record.className)
            field(record.bounds.left.toString())
            field(record.bounds.top.toString())
            field(record.bounds.right.toString())
            field(record.bounds.bottom.toString())
            field(record.clickable.toString())
            field(record.editable.toString())
            field(record.scrollable.toString())
            field(record.enabled.toString())
            field(record.password.toString())
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun publicScreenLabel(record: ScreenNodeRecord): String {
        if (record.password) return MASKED_SCREEN_LABEL
        val own = record.rawText.ifBlank { record.rawContentDescription }
        if (own.isNotBlank()) return own.take(MAX_SCREEN_LABEL_LENGTH)
        val childLabels = record.children.asSequence()
            .filter { it.included }
            .map(::publicScreenLabel)
            .filter { it.isNotBlank() && it != MASKED_SCREEN_LABEL }
            .take(MAX_PARENT_CHILD_LABELS)
            .toList()
        return childLabels.joinToString(separator = " · ").ifBlank {
            if (record.clickable || record.editable) record.viewId.substringAfterLast('/').ifBlank {
                if (record.editable) "editable text field" else ""
            } else ""
        }.take(MAX_SCREEN_LABEL_LENGTH)
    }

    private fun validateScreenTarget(
        node: AccessibilityNodeInfo,
        expected: ScreenNode,
        expectedPackageName: String,
        displayBounds: Rect,
    ): ScreenActionReceipt? {
        if (node.packageName?.toString() != expectedPackageName) {
            return staleScreenReceipt("node_package_changed")
        }
        if (node.isPassword) return rejectedScreenReceipt("password_target")
        if (!node.isEnabled) return rejectedScreenReceipt("disabled_target")
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!node.isVisibleToUser || !isValidScreenBounds(bounds, displayBounds)) {
            return rejectedScreenReceipt("invisible_target")
        }
        val actualLabel = screenLabelForLiveNode(node, displayBounds)
        val changed = when {
            expected.viewId != node.viewIdResourceName.orEmpty() -> "view_id"
            expected.className != node.className?.toString().orEmpty() -> "class"
            expected.left != bounds.left || expected.top != bounds.top ||
                expected.right != bounds.right || expected.bottom != bounds.bottom -> "bounds"
            expected.clickable != node.isClickable || expected.editable != node.isEditable ||
                expected.scrollable != node.isScrollable || expected.enabled != node.isEnabled -> "flags"
            actualLabel == null || expected.label != actualLabel -> "label"
            else -> null
        }
        return changed?.let { staleScreenReceipt("node_${it}_changed") }
    }

    private fun screenLabelForLiveNode(node: AccessibilityNodeInfo, displayBounds: Rect): String? {
        val state = ScreenTraversalState()
        val record = captureScreenNode(node, ROOT_SCREEN_REF, 0, displayBounds, state) ?: return null
        return if (state.failed || state.truncated) null else publicScreenLabel(record)
    }

    private fun dispatchScreenAction(
        node: AccessibilityNodeInfo,
        action: Int,
        arguments: Bundle? = null,
        readback: String? = null,
    ): ScreenActionReceipt {
        val dispatched = runCatching { node.performAction(action, arguments) }
            .getOrElse { return unknownScreenReceipt("dispatch_exception") }
        if (!dispatched) return rejectedScreenReceipt("action_rejected")
        if (readback != null) {
            val refreshed = runCatching { node.refresh() }.getOrDefault(false)
            if (!refreshed) return unknownScreenReceipt("readback_unavailable")
            if (node.text?.toString() != readback) {
                return unknownScreenReceipt("readback_mismatch")
            }
        }
        return ScreenActionReceipt(status = "ok", dispatched = true)
    }

    private fun resolveScreenNode(
        root: AccessibilityNodeInfo,
        ref: String,
    ): AccessibilityNodeInfo? {
        val parts = ref.split('/')
        if (parts.isEmpty() || parts.first() != ROOT_SCREEN_REF || parts.drop(1).any { it.toIntOrNull() == null }) {
            return null
        }
        var current = root
        try {
            for (indexText in parts.drop(1)) {
                val child = runCatching { current.getChild(indexText.toInt()) }.getOrNull()
                    ?: run {
                        if (current !== root) runCatching { current.recycle() }
                        return null
                    }
                if (current !== root) runCatching { current.recycle() }
                current = child
            }
            return current
        } catch (_: Throwable) {
            if (current !== root) runCatching { current.recycle() }
            return null
        }
    }

    private fun screenBounds(): Rect? {
        val metrics = android.util.DisplayMetrics()
        val display = runCatching {
            (getSystemService(WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }.getOrNull()
        runCatching {
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics.setTo(resources.displayMetrics)
        }
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            .takeIf { it.width() > 0 && it.height() > 0 }
    }

    private fun isValidScreenBounds(bounds: Rect, displayBounds: Rect): Boolean =
        bounds.right > bounds.left &&
            bounds.bottom > bounds.top &&
            Rect.intersects(bounds, displayBounds)

    private fun <T> withActiveRoot(
        block: (root: AccessibilityNodeInfo, windowId: Int, packageName: String) -> T,
    ): T? {
        val windowInfos = runCatching { windows.orEmpty() }.getOrDefault(emptyList())
        val rootInActive = runCatching { rootInActiveWindow }.getOrNull()
        val appWindows = windowInfos
            .asSequence()
            .filter { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (window.isActive || window.isFocused)
            }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isActive }
                    .thenByDescending { it.isFocused },
            )
            .toList()
        val appRoots = appWindows.mapNotNull { window ->
            runCatching { window.root }
                .getOrNull()
                ?.let { root -> window to root }
        }

        var root: AccessibilityNodeInfo? = null
        var windowId = -1
        try {
            val activeRoot = rootInActive ?: return null
            val activeWindow = windowInfos.firstOrNull { it.id == activeRoot.windowId }
            // An IME may own input focus while the application owns the editing node.
            // All other active roots (including system permission dialogs) remain authoritative.
            val selected = if (activeWindow?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                appRoots.firstOrNull { (window, _) -> window.isFocused }
            } else null
            if (selected != null) {
                root = selected.second
                windowId = selected.first.id
            } else {
                root = activeRoot
                windowId = activeRoot.windowId
            }
            val selectedRoot = root ?: return null
            val packageName = selectedRoot.packageName?.toString().orEmpty()
            if (packageName.isBlank() || windowId < 0) return null
            return block(selectedRoot, windowId, packageName)
        } finally {
            root?.let { runCatching { it.recycle() } }
            rootInActive
                ?.takeUnless { it === root }
                ?.let { runCatching { it.recycle() } }
            appRoots.forEach { (_, candidate) ->
                candidate.takeUnless { it === root }?.let { runCatching { it.recycle() } }
            }
            windowInfos.forEach { window -> runCatching { window.recycle() } }
        }
    }

    private fun staleScreenReceipt(reason: String): ScreenActionReceipt =
        ScreenActionReceipt(status = "stale_snapshot", dispatched = false, reason = reason)

    private fun rejectedScreenReceipt(reason: String): ScreenActionReceipt =
        ScreenActionReceipt(status = "rejected", dispatched = false, reason = reason)

    private fun unknownScreenReceipt(reason: String): ScreenActionReceipt =
        ScreenActionReceipt(status = "unknown", dispatched = true, reason = reason)

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
            windowId = windowId,
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
    /** 候选窗口 id（takeScreenshotOfWindow 用）；fallback 路径为 -1。 */
    val windowId: Int = -1,
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

private const val ROOT_SCREEN_REF = "0"
private const val MAX_SCREEN_NODES = 512
private const val MAX_SCREEN_DEPTH = 64
private const val MAX_PARENT_CHILD_LABELS = 3
private const val MAX_SCREEN_LABEL_LENGTH = 160
private const val MASKED_SCREEN_LABEL = "[password]"

private class ScreenTraversalState {
    var visited: Int = 0
    var truncated: Boolean = false
    var failed: Boolean = false
}

private class ScreenNodeRecord(
    val ref: String,
    val rawText: String,
    val rawContentDescription: String,
    val viewId: String,
    val className: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val included: Boolean,
    val children: MutableList<ScreenNodeRecord> = mutableListOf(),
)

// AccessibilityTextMatch moved to :core:automation:api so feature tools can
// consume the wire type without pulling the :app-only AmberAccessibilityService.

/** Live 填入的写入后回读结果（蓝图 §7.2 P0-5 仲裁契约）。 */
enum class LiveFillOutcome { SUCCESS, NOT_FOUND, MISMATCH, UNKNOWN }
