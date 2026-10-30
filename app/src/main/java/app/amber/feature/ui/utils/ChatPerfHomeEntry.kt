package app.amber.feature.ui.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.amber.agent.BuildConfig
import org.json.JSONObject
import java.lang.ref.WeakReference

internal const val HOME_FIRST_PATH = "home-first-first-history"
internal const val NATIVE_STRESS_PATH = "native-launcher-stress"

/** Fixture-only lifecycle/identity owner. Never reads message bodies or warms caches. */
internal object ChatPerfHomeEntry {
    val enabled get() = BuildConfig.BUILD_TYPE == "perffixture"
    private var owner = WeakReference<Activity>(null)
    private var serial = 0
    private var activityId = "none"
    private var pathId = NATIVE_STRESS_PATH
    private var home = false
    private var topRoute = "unknown"
    private var topConversation: String? = null
    private val visited = mutableSetOf<String>() // process lifetime, NOT reset by Activity recreation
    private val rows = mutableSetOf<String>()
    private var lastEntryActivity: String? = null
    private var lastEntryConversation: String? = null
    private var config = JSONObject()
    private var pending: Request? = null
    private data class Request(val runId: String, val scenario: String, val conversation: String, val phase: String)

    fun attach(activity: Activity, path: String) {
        if (!enabled) return
        owner = WeakReference(activity)
        activityId = "${android.os.Process.myPid()}-${++serial}"
        pathId = path
        home = false
        topRoute = "unknown"
        topConversation = null
        rows.clear()
        pending = null
    }

    fun route(isHome: Boolean, configuration: JSONObject, actualTop: String = if (isHome) "Home" else "Other", actualChatId: String? = null) {
        if (!enabled) return
        home = isHome
        topRoute = actualTop
        topConversation = actualChatId
        config = configuration
    }

    fun chatObserved(conversation: String) {
        if (enabled) visited += conversation
    }

    fun rowPlaced(conversation: String, visible: Boolean) {
        if (!enabled) return
        if (visible) rows += conversation else rows -= conversation
    }

    fun snapshot(conversation: String): JSONObject {
        val activity = owner.get()
        return JSONObject().apply {
            put("pathId", pathId); put("pid", android.os.Process.myPid()); put("activityId", activityId)
            put("topRoute", topRoute); put("topConversation", topConversation ?: JSONObject.NULL)
            put("home", home); put("focused", activity?.hasWindowFocus() == true)
            put("chatVisited", visited.isNotEmpty()); put("targetVisited", conversation in visited)
            put("rowVisible", home && conversation in rows)
            put("lastEntryActivity", lastEntryActivity ?: JSONObject.NULL)
            put("lastEntryConversation", lastEntryConversation ?: JSONObject.NULL)
            put("appConfiguration", config)
            put("displayId", runCatching {
                if (Build.VERSION.SDK_INT >= 30) activity?.display?.displayId
                else @Suppress("DEPRECATION") activity?.windowManager?.defaultDisplay?.displayId
            }.getOrNull() ?: JSONObject.NULL)
            put("thermalStatus", runCatching {
                if (Build.VERSION.SDK_INT >= 29) (activity?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus else null
            }.getOrNull() ?: JSONObject.NULL)
        }
    }

    fun arm(runId: String, scenario: String, conversation: String, phase: String): String? {
        if (!enabled || pathId != HOME_FIRST_PATH || owner.get() == null || !home) return "not_home_owner"
        if (owner.get()?.isDestroyed != false || owner.get()?.isFinishing != false) return "inactive_owner"
        if (conversation !in rows) return "row_not_visible"
        if (runId.isBlank() || scenario !in listOf("S1", "S2", "S3") || phase !in listOf("cold", "warm")) return "invalid_request"
        if (phase == "cold" && visited.isNotEmpty()) return "cold_already_visited_chat"
        if (phase == "warm" && (lastEntryActivity != activityId || lastEntryConversation != conversation)) return "warm_owner_changed"
        if (conversation != "10000000-0000-4000-8000-0000000000" + scenario.removePrefix("S").padStart(2, '0')) return "wrong_fixture_id"
        pending = Request(runId, scenario, conversation, phase)
        return null // NO startRun/marker/metrics IO here
    }

    /** Called immediately before the ORIGINAL real Home-row navigation callback. */
    fun openFromHome(conversation: String) {
        if (!enabled) return
        val request = pending
        if (request != null && request.conversation == conversation && home && conversation in rows) {
            val context = owner.get() ?: return
            pending = null
            ChatPerfCommandBridge.prepareFixtureRun(request.runId)
            ChatPerfTrace.startRun(context, request.scenario, request.runId, HOME_FIRST_PATH)
            val t0 = SystemClock.elapsedRealtimeNanos()
            lastEntryActivity = activityId
            lastEntryConversation = conversation
            Log.i("AmberPerfFixture", "home-entry runId=${request.runId} pathId=$HOME_FIRST_PATH scenario=${request.scenario} phase=${request.phase} " +
                "activityId=$activityId pid=${android.os.Process.myPid()} t0MonoNs=$t0 priorChatVisited=${visited.isNotEmpty()} current=${ChatPerfTrace.currentRunId()}")
        }
        visited += conversation
    }
}

internal fun Modifier.chatPerfHomeRow(conversation: String): Modifier {
    if (!ChatPerfHomeEntry.enabled) return this
    return composed {
        DisposableEffect(conversation) { onDispose { ChatPerfHomeEntry.rowPlaced(conversation, false) } }
        semantics { contentDescription = "perf_home_row_$conversation" }
        .onGloballyPositioned {
            val b = it.boundsInWindow()
            ChatPerfHomeEntry.rowPlaced(conversation, it.isAttached && b.width > 0 && b.height > 0)
        }
    }
}
