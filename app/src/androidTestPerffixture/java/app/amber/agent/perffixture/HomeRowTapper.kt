package app.amber.agent.perffixture

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import app.amber.agent.RouteActivity
import app.amber.feature.ui.utils.ChatPerfHomeEntry
import org.json.JSONArray
import org.json.JSONObject

/** Fixture-only physical tap; does not invoke accessibility actions or navigation callbacks. */
internal class HomeRowTapper(
    private val instrumentation: Instrumentation,
    private val token: String,
    private val phase: String,
    private val conversation: String,
    private val expectedPid: Int,
    private val expectedActivity: String,
    private val windowAccess: HomeWindowAccess,
    private val readOwner: () -> HomeRowTapContract.Owner,
) {
    private val description = "perf_home_row_$conversation"

    private fun bounds(rect: Rect) = HomeRowTapContract.Bounds(rect.left, rect.top, rect.right, rect.bottom)
    private fun audit(event: String, data: JSONObject) {
        // Only fixed fixture identity, geometry, node flags/actions and input admission.
        instrumentation.sendStatus(2, Bundle().apply {
            putString("homeRowTapToken", token)
            putString("homeRowTapPhase", phase)
            putString("homeRowTapEvent", event)
            putString("homeRowTapAudit", data.toString())
        })
    }
    private fun rectJson(rect: HomeRowTapContract.Bounds) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun nodeInfo(node: AccessibilityNodeInfo): HomeRowTapContract.Node {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return HomeRowTapContract.Node(node.packageName?.toString(),
            if (node.contentDescription?.toString() == description) description else null,
            node.isVisibleToUser, node.isEnabled, node.isClickable, node.windowId,
            node.actionList.map { it.id }.toIntArray(), bounds(rect))
    }
    private fun nodeJson(node: HomeRowTapContract.Node) = JSONObject().apply {
        put("package", node.packageName ?: JSONObject.NULL)
        put("stableContentDesc", node.stableDescription ?: JSONObject.NULL) // no arbitrary ancestor descriptions/text
        put("visible", node.visible); put("enabled", node.enabled); put("clickable", node.clickable)
        put("windowId", node.windowId); put("bounds", rectJson(node.bounds)); put("actionIds", JSONArray(node.actions.toList()))
    }
    private fun ownerJson(owner: HomeRowTapContract.Owner) = JSONObject().apply {
        put("package", owner.packageName); put("pid", owner.pid); put("activityId", owner.activityId)
        put("focused", owner.focused); put("shown", owner.shown); put("homeWithRow", owner.homeWithRow)
        put("windowId", owner.windowId); put("displayId", owner.displayId)
        put("windowBounds", rectJson(owner.window)); put("screenBounds", rectJson(owner.screen))
    }

    fun tap() {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var root: AccessibilityNodeInfo?
        var matches: List<AccessibilityNodeInfo>
        do {
            root = instrumentation.uiAutomation.rootInActiveWindow
            val found = mutableListOf<AccessibilityNodeInfo>()
            if (root != null && root.packageName?.toString() != HomeRowTapContract.PACKAGE) {
                matches = emptyList() // do not traverse another app's content; audit and reject below
                break
            }
            var visited = 0
            fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null) return
                check(++visited <= 4096 && depth <= 64) { "Home target accessibility traversal exceeded test bound" }
                if (node.contentDescription?.toString() == description) found += node
                for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
            }
            visit(root, 0)
            matches = found
            if (matches.isNotEmpty()) break
            if (SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        val owner = readOwner() // real ShellHomeActivity's bounded main-thread owner validation
        val actualRoot = root
        val nodes = matches.map(::nodeInfo)
        val ancestors = mutableListOf<HomeRowTapContract.Node>()
        if (matches.size == 1) {
            var parent = matches.single().parent
            while (parent != null) {
                check(ancestors.size < 64) { "Home target ancestor chain exceeded test bound" }
                ancestors += nodeInfo(parent)
                parent = parent.parent
            }
        }
        var windowQuery: HomeWindowAccess.Snapshot? = null
        if (actualRoot != null) {
            do {
                windowQuery = windowAccess.query(actualRoot, owner.windowId, owner.displayId)
                if (windowQuery.ready() || SystemClock.uptimeMillis() >= deadline) break
                SystemClock.sleep(50) // shares the existing 5s preparation deadline
            } while (SystemClock.uptimeMillis() < deadline)
        }
        val window = windowQuery?.takeIf { it.ready() }?.matched
        audit("selection", JSONObject().apply {
            put("owner", ownerJson(owner)); put("root", actualRoot?.let { nodeJson(nodeInfo(it)) } ?: JSONObject.NULL)
            put("rootWindowActive", window?.active ?: JSONObject.NULL); put("rootWindowFocused", window?.focused ?: JSONObject.NULL)
            put("rootWindowDisplayId", window?.displayId ?: JSONObject.NULL)
            put("windowRetrieval", JSONObject().apply {
                put("flagsBefore", windowAccess.flagsBefore ?: JSONObject.NULL)
                put("flagsEnabled", windowAccess.flagsEnabled ?: JSONObject.NULL)
                put("matchedSource", window?.source ?: JSONObject.NULL)
                put("matchedWindowId", window?.id ?: JSONObject.NULL)
                put("conflicting", windowQuery?.conflicting ?: JSONObject.NULL)
                put("errors", JSONArray(windowQuery?.errors ?: emptyList<String>()))
                put("observations", JSONArray(windowQuery?.observations?.map { value ->
                    JSONObject().put("source", value.source).put("id", value.id).put("displayId", value.displayId)
                        .put("active", value.active).put("focused", value.focused)
                } ?: emptyList<JSONObject>()))
            })
            put("targetCount", nodes.size); put("targets", JSONArray(nodes.map { nodeJson(it) }))
            put("ancestors", JSONArray(ancestors.map { nodeJson(it) }))
            put("contentPolicy", "fixture-metadata-only-no-text-or-action-labels")
        })
        checkNotNull(actualRoot) { "Home tap: active root missing; see selection audit" }
        val point = HomeRowTapContract.select(description, nodes, ancestors, nodeInfo(actualRoot),
            window?.active == true, window?.focused == true, window?.displayId ?: -1,
            owner, expectedPid, expectedActivity)
        // Recheck focus/owner and clipping immediately before DOWN; fail if the viewport changed.
        val latestOwner = readOwner()
        val latest = HomeRowTapContract.select(description, nodes, ancestors, nodeInfo(actualRoot),
            window?.active == true, window?.focused == true, window?.displayId ?: -1,
            latestOwner, expectedPid, expectedActivity)
        check(point.x == latest.x && point.y == latest.y) { "Home tap viewport changed during audit" }
        audit("selected-point", JSONObject().put("x", point.x).put("y", point.y).put("clippedBounds", rectJson(point.visibleBounds)))
        val downTime = SystemClock.uptimeMillis()
        HomeRowTapContract.tap(point) { action, x, y ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                val accepted = instrumentation.uiAutomation.injectInputEvent(event, true)
                audit("input", JSONObject().put("action", action).put("accepted", accepted).put("x", x).put("y", y))
                accepted
            } catch (error: Throwable) {
                audit("input-error", JSONObject().put("action", action).put("errorClass", error.javaClass.name))
                throw error
            } finally { event.recycle() }
        }
    }

    companion object {
        /** Must be called by ShellHomeActivity.onActivity, with its real resumed owner. */
        fun captureOwner(activity: RouteActivity, conversation: String): HomeRowTapContract.Owner {
            val state = ChatPerfHomeEntry.snapshot(conversation)
            check(state.getInt("pid") == Process.myPid()) { "Home tap runtime snapshot PID mismatch" }
            val decor = activity.window.decorView
            val location = IntArray(2).also { decor.getLocationOnScreen(it) }
            val decorBounds = Rect(location[0], location[1], location[0] + decor.width, location[1] + decor.height)
            val visible = Rect().also { decor.getWindowVisibleDisplayFrame(it) }
            if (!decorBounds.intersect(visible)) decorBounds.setEmpty()
            val display = activity.display
            val metrics = DisplayMetrics().also { @Suppress("DEPRECATION") display?.getRealMetrics(it) }
            fun rect(r: Rect) = HomeRowTapContract.Bounds(r.left, r.top, r.right, r.bottom)
            return HomeRowTapContract.Owner(activity.packageName, state.getString("activityId"), Process.myPid(),
                decor.createAccessibilityNodeInfo().windowId, display?.displayId ?: -1,
                state.getBoolean("home") && state.getString("topRoute") == "Home" && state.getBoolean("rowVisible"),
                activity.hasWindowFocus(), decor.isShown && decor.visibility == View.VISIBLE,
                rect(decorBounds), HomeRowTapContract.Bounds(0, 0, metrics.widthPixels, metrics.heightPixels))
        }
    }
}
