package app.amber.core.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Owns one native observation or same-root action; no node survives the call. */
internal class AccessibilityNativeScreen(private val service: AccessibilityService) {
    /**
     * Reads the active window on the accessibility service main thread. The
     * caller owns the main-thread dispatch; every root, child and window
     * obtained here is recycled before this method returns.
     */
    fun captureScreenSnapshot(): ScreenSnapshot? = withActiveRoot { root, windowId, packageName ->
        buildScreenSnapshot(root, windowId, packageName)?.also { snapshot ->
            if (!ScreenSnapshotInvalidationRegistry.isTakeoverPauseActive()) {
                ScreenSnapshotInvalidationRegistry.markFreshCapture(snapshot.id)
            }
        }
    }

    /**
     * Applies one action only when the supplied snapshot still describes the
     * active window. No coordinate fallback is used: an unsupported or stale
     * target is handed back to the caller as a receipt.
     */
    fun performScreenAction(
        snapshot: ScreenSnapshot,
        action: ScreenAction,
    ): ScreenActionReceipt {
        if (ScreenSnapshotInvalidationRegistry.isInvalidated(snapshot.id)) {
            return staleScreenReceipt(
                ScreenSnapshotInvalidationRegistry.invalidationReason(snapshot.id)
                    ?: "returned_to_host_snapshot_invalidated"
            )
        }
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
            val text = if (password) "" else node.text?.toString().orEmpty()
            ScreenNodeRecord(
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
        editableValue = editableValue,
        hasOwnLabel = rawText.isNotBlank() || rawContentDescription.isNotBlank(),
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
            field(record.editableValue.orEmpty())
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
            (service.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }.getOrNull()
        runCatching {
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics.setTo(service.resources.displayMetrics)
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
        val windowInfos = runCatching { service.windows.orEmpty() }.getOrDefault(emptyList())
        val rootInActive = runCatching { service.rootInActiveWindow }.getOrNull()
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
}

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
    val children: MutableList<ScreenNodeRecord> = mutableListOf(),
)
