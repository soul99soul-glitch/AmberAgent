package app.amber.core.automation.display

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.AccessibilityTextMatch
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.automation.ScreenAction
import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenActionReceipt
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenSnapshot
import app.amber.core.automation.ScreenSnapshotInvalidationRegistry
import kotlinx.coroutines.delay
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Display-scoped [AccessibilityController] bound to one virtual display (`displayId > 0`) and
 * one monotonic [generation].
 *
 * Isolation invariants:
 * - [back], [home], and [returnToHostApp] ALWAYS return `false` so global actions never leak to Display 0.
 * - [captureScreenSnapshot], [performScreenAction], [setFocusedText], [dumpUiTree], and [findTextNodes]
 *   ONLY inspect [AmberAccessibilityService.windowsOnDisplay] for [displayId] — never falling back to
 *   `rootInActiveWindow` or default-display windows.
 * - `snapshotId` always starts with `"vd:${displayId}:${generation}:${seq}"`.
 * - Text entry ONLY uses `ACTION_SET_TEXT` on an editable non-password node on [displayId] with
 *   immediate same-node readback verification (never clipboard paste or IME key injection).
 * - Touch gestures ([tap], [longPress], [swipe]) route every MotionEvent (down/move/up/cancel) through
 *   [motionInjector], verifying [isGenerationValid] before each event.
 */
class VirtualDisplayAccessibilityController(
    private val displayId: Int,
    private val generation: Long,
    private val expectedPackageName: String,
    private val isGenerationValid: () -> Boolean,
    private val displayBoundsProvider: () -> Rect,
    private val motionInjector: suspend (MotionEvent) -> Boolean,
    private val windowsProvider: (Int) -> List<AccessibilityWindowInfo> = { id ->
        AmberAccessibilityService.getActiveService()?.windowsOnDisplay(id).orEmpty()
    },
    private val uptimeMillisProvider: () -> Long = { SystemClock.uptimeMillis() },
) : AccessibilityController {

    private val snapshotSeq = AtomicLong(0L)

    @Volatile
    private var lastSnapshotId: String? = null

    /** Global navigation keys are prohibited on virtual displays because they target Display 0. */
    override fun back(): Boolean = false

    /** Global home action is prohibited on virtual displays because it targets Display 0. */
    override fun home(): Boolean = false

    /** Virtual display sessions never manipulate Display 0 host foregrounding. */
    override fun returnToHostApp(hostPackageName: String): Boolean = false

    fun invalidateLatestSnapshot(reason: String) {
        val snapshotId = lastSnapshotId ?: return
        invalidateSnapshot(snapshotId, reason)
    }

    override suspend fun tap(x: Float, y: Float, durationMillis: Long): Boolean {
        if (!canDispatch()) return false
        val holdMs = durationMillis.coerceIn(20L, 400L)
        val downTime = uptimeMillisProvider()
        val downOk = dispatchMotion(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        if (!downOk) return false
        try {
            delay(holdMs)
        } catch (ce: CancellationException) {
            dispatchCancelQuietly(downTime, x, y)
            throw ce
        }
        if (!canDispatch()) {
            dispatchCancelQuietly(downTime, x, y)
            return false
        }
        return dispatchMotion(downTime, uptimeMillisProvider(), MotionEvent.ACTION_UP, x, y)
    }

    override suspend fun longPress(x: Float, y: Float, durationMillis: Long): Boolean {
        if (!canDispatch()) return false
        val holdMs = durationMillis.coerceIn(400L, 2_000L)
        val downTime = uptimeMillisProvider()
        val downOk = dispatchMotion(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        if (!downOk) return false
        try {
            delay(holdMs)
        } catch (ce: CancellationException) {
            dispatchCancelQuietly(downTime, x, y)
            throw ce
        }
        if (!canDispatch()) {
            dispatchCancelQuietly(downTime, x, y)
            return false
        }
        return dispatchMotion(downTime, uptimeMillisProvider(), MotionEvent.ACTION_UP, x, y)
    }

    override suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMillis: Long,
    ): Boolean {
        if (!canDispatch()) return false
        val totalMs = durationMillis.coerceIn(80L, 2_000L)
        val steps = (totalMs / SWIPE_STEP_MS).toInt().coerceIn(4, 30)
        val stepDelay = (totalMs / steps).coerceAtLeast(8L)
        val downTime = uptimeMillisProvider()
        if (!dispatchMotion(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, fromY)) {
            return false
        }
        var lastX = fromX
        var lastY = fromY
        try {
            for (step in 1..steps) {
                delay(stepDelay)
                val fraction = step.toFloat() / steps.toFloat()
                lastX = fromX + (toX - fromX) * fraction
                lastY = fromY + (toY - fromY) * fraction
                if (!canDispatch() || !dispatchMotion(downTime, uptimeMillisProvider(), MotionEvent.ACTION_MOVE, lastX, lastY)) {
                    dispatchCancelQuietly(downTime, lastX, lastY)
                    return false
                }
            }
        } catch (ce: CancellationException) {
            dispatchCancelQuietly(downTime, lastX, lastY)
            throw ce
        }
        if (!canDispatch()) {
            dispatchCancelQuietly(downTime, lastX, lastY)
            return false
        }
        return dispatchMotion(downTime, uptimeMillisProvider(), MotionEvent.ACTION_UP, toX, toY)
    }

    override fun setFocusedText(text: String): Boolean {
        if (!canDispatch()) return false
        return withDisplayAppRoot { root, _, packageName ->
            if (!matchesExpectedPackage(packageName)) return@withDisplayAppRoot false
            val target = locateEditableNode(root) ?: return@withDisplayAppRoot false
            try {
                if (target.isPassword || !target.isEnabled || !target.isEditable) {
                    return@withDisplayAppRoot false
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (!canDispatch()) return@withDisplayAppRoot false
                val dispatched = runCatching {
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }.getOrDefault(false)
                if (!dispatched) return@withDisplayAppRoot false
                val refreshed = runCatching { target.refresh() }.getOrDefault(false)
                if (!refreshed) return@withDisplayAppRoot false
                target.text?.toString() == text
            } finally {
                if (target !== root) {
                    runCatching { target.recycle() }
                }
            }
        } ?: false
    }

    override fun captureScreenSnapshot(): ScreenSnapshot? {
        if (!canDispatch()) return null
        return withDisplayAppRoot { root, windowId, packageName ->
            val seq = snapshotSeq.incrementAndGet()
            buildScreenSnapshot(root, windowId, packageName, seq)?.also { snapshot ->
                lastSnapshotId = snapshot.id
                if (!ScreenSnapshotInvalidationRegistry.isTakeoverPauseActive()) {
                    ScreenSnapshotInvalidationRegistry.markFreshCapture(snapshot.id)
                }
            }
        }
    }

    override fun performScreenAction(
        snapshot: ScreenSnapshot,
        action: ScreenAction,
    ): ScreenActionReceipt {
        if (!canDispatch()) {
            return staleReceipt("virtual_display_generation_invalid")
        }
        val expectedPrefix = snapshotIdPrefix(displayId, generation)
        if (!snapshot.id.startsWith(expectedPrefix)) {
            return staleReceipt("snapshot_display_or_generation_mismatch")
        }
        if (ScreenSnapshotInvalidationRegistry.isInvalidated(snapshot.id)) {
            return staleReceipt(
                ScreenSnapshotInvalidationRegistry.invalidationReason(snapshot.id)
                    ?: "virtual_display_snapshot_invalidated",
            )
        }
        if (snapshot.id != lastSnapshotId) return staleReceipt("superseded_snapshot")
        val expected = snapshot.nodes.firstOrNull { it.ref == action.ref }
            ?: return rejectedReceipt("unknown_ref")
        if (snapshot.packageName.isBlank() || action.ref.isBlank()) {
            return rejectedReceipt("invalid_target")
        }
        if (!matchesExpectedPackage(snapshot.packageName)) {
            return rejectedReceipt("package_not_bound_to_virtual_session")
        }
        if (action.kind == ScreenActionKind.TYPE && action.text == null) {
            return rejectedReceipt("missing_text")
        }

        return withDisplayAppRoot { root, windowId, packageName ->
            if (!canDispatch()) {
                return@withDisplayAppRoot staleReceipt("virtual_display_generation_invalid")
            }
            if (packageName != snapshot.packageName || windowId != snapshot.windowId) {
                return@withDisplayAppRoot staleReceipt("virtual_window_changed")
            }
            val target = resolveScreenNode(root, action.ref)
                ?: return@withDisplayAppRoot staleReceipt("node_unavailable")
            try {
                if (!runCatching { target.refresh() }.getOrDefault(false)) {
                    return@withDisplayAppRoot staleReceipt("node_refresh_failed")
                }
                val displayBounds = displayBoundsProvider()
                val validation = validateTarget(
                    node = target,
                    expected = expected,
                    expectedPackageName = snapshot.packageName,
                    displayBounds = displayBounds,
                )
                if (validation != null) {
                    return@withDisplayAppRoot validation
                }
                when (action.kind) {
                    ScreenActionKind.CLICK -> {
                        if (!target.isClickable) {
                            rejectedReceipt("not_clickable")
                        } else {
                            dispatchNodeAction(target, AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }

                    ScreenActionKind.TYPE -> {
                        if (!target.isEditable) {
                            rejectedReceipt("not_editable")
                        } else {
                            val args = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    action.text,
                                )
                            }
                            dispatchNodeAction(
                                node = target,
                                action = AccessibilityNodeInfo.ACTION_SET_TEXT,
                                arguments = args,
                                expectedReadBack = action.text,
                            )
                        }
                    }

                    ScreenActionKind.SCROLL_FORWARD -> {
                        if (!target.isScrollable) {
                            rejectedReceipt("not_scrollable")
                        } else {
                            dispatchNodeAction(target, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                    }

                    ScreenActionKind.SCROLL_BACKWARD -> {
                        if (!target.isScrollable) {
                            rejectedReceipt("not_scrollable")
                        } else {
                            dispatchNodeAction(target, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        }
                    }
                }
            } finally {
                if (target !== root) {
                    runCatching { target.recycle() }
                }
            }
        } ?: staleReceipt("virtual_window_unavailable")
    }

    override fun dumpUiTree(maxNodes: Int): String {
        if (!canDispatch()) return ""
        return withDisplayAppRoot { root, _, _ ->
            val lines = mutableListOf<String>()
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (lines.size >= maxNodes || depth > MAX_SCREEN_DEPTH) return
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val password = node.isPassword
                val label = listOfNotNull(
                    node.className?.toString(),
                    node.viewIdResourceName,
                    if (password) MASKED_LABEL else node.text?.toString()?.takeIf { it.isNotBlank() },
                    if (password) null else node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                ).joinToString(" | ")
                lines += "${"  ".repeat(depth)}- $label bounds=$rect clickable=${node.isClickable} focused=${node.isFocused}"
                for (index in 0 until node.childCount) {
                    val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                    try {
                        visit(child, depth + 1)
                    } finally {
                        runCatching { child.recycle() }
                    }
                }
            }
            visit(root, 0)
            lines.joinToString("\n")
        }.orEmpty()
    }

    override fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> {
        if (!canDispatch()) return emptyList()
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        return withDisplayAppRoot { root, _, _ ->
            val results = mutableListOf<AccessibilityTextMatch>()
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (visited >= maxNodes || depth > MAX_SCREEN_DEPTH) return
                visited++
                if (!node.isPassword) {
                    val text = node.text?.toString().orEmpty()
                    val description = node.contentDescription?.toString().orEmpty()
                    if ("$text\n$description".contains(needle, ignoreCase = true)) {
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
                }
                for (index in 0 until node.childCount) {
                    val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                    try {
                        visit(child, depth + 1)
                    } finally {
                        runCatching { child.recycle() }
                    }
                }
            }
            visit(root, 0)
            results
        }.orEmpty()
    }

    /**
     * Checks whether the expected target package currently has an application window on [displayId].
     */
    internal fun observedTargetPackage(): String? {
        if (!canDispatch()) return null
        return withDisplayAppRoot { _, _, packageName -> packageName }
    }

    private fun canDispatch(): Boolean = displayId > Display.DEFAULT_DISPLAY && isGenerationValid()

    private fun matchesExpectedPackage(packageName: String): Boolean {
        if (expectedPackageName.isBlank()) return false
        val normalized = expectedPackageName.removeSuffix(AmberDisplayUserService.PROBE_TARGET_SUFFIX)
        return packageName == normalized
    }

    private suspend fun dispatchMotion(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): Boolean {
        if (!canDispatch()) return false
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try {
            motionInjector(event)
        } finally {
            event.recycle()
        }
    }

    private suspend fun dispatchCancelQuietly(downTime: Long, x: Float, y: Float) {
        runCatching {
            val cancelEvent = MotionEvent.obtain(downTime, uptimeMillisProvider(), MotionEvent.ACTION_CANCEL, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                motionInjector(cancelEvent)
            } finally {
                cancelEvent.recycle()
            }
        }
    }

    private fun <T> withDisplayAppRoot(
        block: (root: AccessibilityNodeInfo, windowId: Int, packageName: String) -> T,
    ): T? {
        val windows = runCatching { windowsProvider(displayId) }.getOrDefault(emptyList())
        if (windows.isEmpty()) return null

        // An authorized package underneath another window cannot receive a coordinate gesture safely.
        try {
            val window = windows.maxByOrNull { it.layer } ?: return null
            if (window.displayId != displayId || window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.id < 0) {
                return null
            }
            val root = window.root ?: return null
            try {
                val packageName = root.packageName?.toString() ?: return null
                if (!matchesExpectedPackage(packageName)) return null
                return block(root, window.id, packageName)
            } finally {
                runCatching { root.recycle() }
            }
        } finally {
            windows.forEach { window -> runCatching { window.recycle() } }
        }
    }

    private fun locateEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused != null) {
            if (focused.isEditable && !focused.isPassword && focused.isEnabled) {
                return focused
            }
            if (focused !== root) runCatching { focused.recycle() }
        }
        return findFirstEditable(root, depth = 0, visited = intArrayOf(0))
    }

    private fun findFirstEditable(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: IntArray,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_SCREEN_DEPTH || visited[0] >= MAX_SCREEN_NODES) return null
        visited[0]++
        if (node.isEditable && !node.isPassword && node.isEnabled && node.isVisibleToUser) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            val match = findFirstEditable(child, depth + 1, visited)
            if (match != null) {
                if (match !== child) runCatching { child.recycle() }
                return match
            }
            runCatching { child.recycle() }
        }
        return null
    }

    private fun buildScreenSnapshot(
        root: AccessibilityNodeInfo,
        windowId: Int,
        packageName: String,
        seq: Long,
    ): ScreenSnapshot? {
        if (packageName.isBlank()) return null
        val displayBounds = displayBoundsProvider()
        if (displayBounds.width() <= 0 || displayBounds.height() <= 0) return null

        val state = TraversalState()
        val tree = captureNode(root, ROOT_REF, 0, displayBounds, state) ?: return null
        if (state.failed || state.truncated) return null

        val records = buildList { flatten(tree, this) }
        if (records.isEmpty()) return null
        val nodes = records.map { it.toScreenNode() }
        val hash = hashSnapshot(packageName, windowId, records)
        val id = formatSnapshotId(displayId, generation, seq, hash)
        return ScreenSnapshot(
            id = id,
            packageName = packageName,
            windowId = windowId,
            nodes = nodes,
        )
    }

    private fun captureNode(
        node: AccessibilityNodeInfo,
        ref: String,
        depth: Int,
        displayBounds: Rect,
        state: TraversalState,
    ): NodeRecord? {
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
        val included = visible && isValidBounds(bounds, displayBounds)
        val record = runCatching {
            val password = node.isPassword
            val text = if (password) "" else node.text?.toString().orEmpty()
            NodeRecord(
                ref = ref,
                rawText = if (password) "" else text.ifBlank { node.hintText?.toString().orEmpty() },
                editableValue = text.takeIf { node.isEditable && !password },
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

        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrElse {
                state.failed = true
                return null
            } ?: continue
            try {
                captureNode(child, "$ref/$index", depth + 1, displayBounds, state)
                    ?.let(record.children::add)
                if (state.failed || state.truncated) return null
            } finally {
                runCatching { child.recycle() }
            }
        }
        return record
    }

    private fun flatten(record: NodeRecord, out: MutableList<NodeRecord>) {
        if (record.included) out += record
        record.children.forEach { flatten(it, out) }
    }

    private fun NodeRecord.toScreenNode(): ScreenNode = ScreenNode(
        ref = ref,
        label = publicLabel(this),
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
        editableValue = editableValue,
        hasOwnLabel = rawText.isNotBlank() || rawContentDescription.isNotBlank(),
    )

    private fun publicLabel(record: NodeRecord): String {
        if (record.password) return MASKED_LABEL
        val own = record.rawText.ifBlank { record.rawContentDescription }
        if (own.isNotBlank()) return own.take(MAX_LABEL_LENGTH)
        val childLabels = record.children.asSequence()
            .filter { it.included }
            .map(::publicLabel)
            .filter { it.isNotBlank() && it != MASKED_LABEL }
            .take(3)
            .toList()
        return childLabels.joinToString(" · ").ifBlank {
            if (record.clickable || record.editable) {
                record.viewId.substringAfterLast('/').ifBlank {
                    if (record.editable) "editable text field" else ""
                }
            } else {
                ""
            }
        }.take(MAX_LABEL_LENGTH)
    }

    private fun validateTarget(
        node: AccessibilityNodeInfo,
        expected: ScreenNode,
        expectedPackageName: String,
        displayBounds: Rect,
    ): ScreenActionReceipt? {
        if (node.packageName?.toString() != expectedPackageName) {
            return staleReceipt("node_package_changed")
        }
        if (node.isPassword) return rejectedReceipt("password_target")
        if (!node.isEnabled) return rejectedReceipt("disabled_target")
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!node.isVisibleToUser || !isValidBounds(bounds, displayBounds)) {
            return rejectedReceipt("invisible_target")
        }
        if (expected.viewId != node.viewIdResourceName.orEmpty()) return staleReceipt("node_view_id_changed")
        if (expected.className != node.className?.toString().orEmpty()) return staleReceipt("node_class_changed")
        if (expected.left != bounds.left || expected.top != bounds.top ||
            expected.right != bounds.right || expected.bottom != bounds.bottom
        ) {
            return staleReceipt("node_bounds_changed")
        }
        if (expected.clickable != node.isClickable || expected.editable != node.isEditable ||
            expected.scrollable != node.isScrollable || expected.enabled != node.isEnabled
        ) {
            return staleReceipt("node_flags_changed")
        }
        return null
    }

    private fun dispatchNodeAction(
        node: AccessibilityNodeInfo,
        action: Int,
        arguments: Bundle? = null,
        expectedReadBack: String? = null,
    ): ScreenActionReceipt {
        if (!canDispatch()) return staleReceipt("virtual_display_generation_invalid")
        val dispatched = runCatching { node.performAction(action, arguments) }
            .getOrElse { return unknownReceipt("dispatch_exception") }
        if (!dispatched) return rejectedReceipt("action_rejected")
        if (expectedReadBack != null) {
            val refreshed = runCatching { node.refresh() }.getOrDefault(false)
            if (!refreshed) return unknownReceipt("readback_unavailable")
            if (node.text?.toString() != expectedReadBack) {
                return unknownReceipt("readback_mismatch")
            }
        }
        return ScreenActionReceipt(status = "ok", dispatched = true)
    }

    private fun resolveScreenNode(root: AccessibilityNodeInfo, ref: String): AccessibilityNodeInfo? {
        val parts = ref.split('/')
        if (parts.isEmpty() || parts.first() != ROOT_REF || parts.drop(1).any { it.toIntOrNull() == null }) {
            return null
        }
        var current = root
        try {
            for (segment in parts.drop(1)) {
                val child = runCatching { current.getChild(segment.toInt()) }.getOrNull()
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

    private fun isValidBounds(bounds: Rect, displayBounds: Rect): Boolean =
        bounds.right > bounds.left &&
            bounds.bottom > bounds.top &&
            Rect.intersects(bounds, displayBounds)

    private fun hashSnapshot(
        packageName: String,
        windowId: Int,
        records: List<NodeRecord>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update(';'.code.toByte())
        }
        field(displayId.toString())
        field(generation.toString())
        field(packageName)
        field(windowId.toString())
        records.forEach { record ->
            field(record.ref)
            field(record.rawText)
            field(record.editableValue.orEmpty())
            field(record.viewId)
            field(record.className)
            field(record.bounds.left.toString())
            field(record.bounds.top.toString())
            field(record.bounds.right.toString())
            field(record.bounds.bottom.toString())
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    private fun staleReceipt(reason: String) =
        ScreenActionReceipt(status = "stale_snapshot", dispatched = false, reason = reason)

    private fun rejectedReceipt(reason: String) =
        ScreenActionReceipt(status = "rejected", dispatched = false, reason = reason)

    private fun unknownReceipt(reason: String) =
        ScreenActionReceipt(status = "unknown", dispatched = true, reason = reason)

    private class TraversalState {
        var visited: Int = 0
        var truncated: Boolean = false
        var failed: Boolean = false
    }

    private class NodeRecord(
        val ref: String,
        val rawText: String,
        val editableValue: String?,
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
        val children: MutableList<NodeRecord> = mutableListOf(),
    )

    companion object {
        private const val ROOT_REF = "0"
        private const val MAX_SCREEN_NODES = 512
        private const val MAX_SCREEN_DEPTH = 64
        private const val MAX_LABEL_LENGTH = 160
        private const val MASKED_LABEL = "[password]"
        private const val SWIPE_STEP_MS = 16L

        fun snapshotIdPrefix(displayId: Int, generation: Long): String =
            "vd:$displayId:$generation:"

        fun formatSnapshotId(displayId: Int, generation: Long, seq: Long, contentHash: String): String =
            if (contentHash.isBlank()) "vd:$displayId:$generation:$seq"
            else "vd:$displayId:$generation:$seq:$contentHash"
    }
}
