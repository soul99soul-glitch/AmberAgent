package app.amber.core.automation.display

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.amber.agent.PHONE_USE_RUN_CHANNEL_ID
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.utils.NotificationUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

const val PHONE_USE_VIRTUAL_DISPLAY_ENABLED_PREF = "phone_use_virtual_display_enabled"
private const val AMBER_PREFS_NAME = "amber_agent.preferences"

fun isPhoneUseVirtualDisplayEnabled(context: Context): Boolean =
    context.applicationContext
        .getSharedPreferences(AMBER_PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PHONE_USE_VIRTUAL_DISPLAY_ENABLED_PREF, false)

fun setPhoneUseVirtualDisplayEnabled(context: Context, enabled: Boolean) {
    context.applicationContext
        .getSharedPreferences(AMBER_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PHONE_USE_VIRTUAL_DISPLAY_ENABLED_PREF, enabled)
        .apply()
}

enum class VirtualDisplayRunControlState {
    RUNNING,
    PAUSED,
    TAKEN_OVER,
    STOPPED,
}

/**
 * Live handle for an active virtual display automation run.
 * Shared between [app.amber.feature.tools.ScreenAppScopeTools], [VirtualDisplayRunControlReceiver],
 * and [app.amber.feature.ui.pages.phoneuse.PhoneUseLivePage].
 */
class ActiveVirtualDisplayRun(
    val runId: String,
    val packageName: String,
    val goal: String,
    val session: PhoneDisplaySession,
    val frameSource: VirtualDisplayFrameSource? = (session as? VirtualDisplaySession)?.frameSource,
    private val onPauseRequested: () -> Unit = {},
    private val onResumeRequested: () -> Unit = {},
    private val onTakeoverRequested: suspend () -> Unit = {},
    private val onEmergencyStopRequested: suspend () -> Unit = {},
) {
    private val _controlState = MutableStateFlow(VirtualDisplayRunControlState.RUNNING)
    val controlState: StateFlow<VirtualDisplayRunControlState> = _controlState.asStateFlow()

    val sessionState: StateFlow<VirtualDisplaySessionState>?
        get() = (session as? VirtualDisplaySession)?.state

    fun pause() {
        if (_controlState.value == VirtualDisplayRunControlState.STOPPED ||
            _controlState.value == VirtualDisplayRunControlState.TAKEN_OVER
        ) {
            return
        }
        _controlState.value = VirtualDisplayRunControlState.PAUSED
        onPauseRequested()
    }

    fun resume() {
        if (_controlState.value != VirtualDisplayRunControlState.PAUSED) return
        _controlState.value = VirtualDisplayRunControlState.RUNNING
        onResumeRequested()
    }

    suspend fun takeover() {
        if (_controlState.value == VirtualDisplayRunControlState.STOPPED ||
            _controlState.value == VirtualDisplayRunControlState.TAKEN_OVER
        ) {
            return
        }
        _controlState.value = VirtualDisplayRunControlState.TAKEN_OVER
        onTakeoverRequested()
    }

    suspend fun emergencyStop() {
        if (_controlState.value == VirtualDisplayRunControlState.STOPPED) return
        _controlState.value = VirtualDisplayRunControlState.STOPPED
        onEmergencyStopRequested()
    }
}

/**
 * Process-scoped registry of active virtual display runs.
 * Allows [VirtualDisplayRunControlReceiver] and [app.amber.feature.ui.pages.phoneuse.PhoneUseLivePage]
 * to observe frames and trigger pause / takeover / emergency stop without leaking global state after close.
 */
object VirtualDisplayRunRegistry {
    private val runs = ConcurrentHashMap<String, ActiveVirtualDisplayRun>()
    private val _activeRuns = MutableStateFlow<Map<String, ActiveVirtualDisplayRun>>(emptyMap())
    val activeRuns: StateFlow<Map<String, ActiveVirtualDisplayRun>> = _activeRuns.asStateFlow()

    fun register(run: ActiveVirtualDisplayRun) {
        runs[run.runId] = run
        _activeRuns.update { it + (run.runId to run) }
    }

    fun unregister(runId: String, expected: ActiveVirtualDisplayRun? = null) {
        val removed = if (expected != null) {
            runs.remove(runId, expected)
        } else {
            runs.remove(runId) != null
        }
        if (removed) {
            _activeRuns.update { it - runId }
        }
    }

    fun get(runId: String): ActiveVirtualDisplayRun? = runs[runId]

    fun firstActiveRun(): ActiveVirtualDisplayRun? = runs.values.firstOrNull()

    internal fun clearForTest() {
        runs.clear()
        _activeRuns.value = emptyMap()
    }
}

/**
 * Ongoing notification manager for virtual display runs (R3 / R4).
 *
 * Strictly exposes ONLY three actions:
 * 1. View (`PhoneUseLive(runId)`)
 * 2. Pause (explicit non-exported [VirtualDisplayRunControlReceiver], `FLAG_IMMUTABLE`)
 * 3. Emergency stop (explicit non-exported [VirtualDisplayRunControlReceiver], `FLAG_IMMUTABLE`)
 *
 * NEVER includes any approval action (L3 approvals remain strictly in-app).
 */
class VirtualDisplayRunNotifier(
    private val context: Context,
) {
    enum class ActionKind {
        VIEW,
        PAUSE,
        EMERGENCY_STOP,
    }

    data class ActionSpec(
        val kind: ActionKind,
        val action: String,
        val title: String,
        val pendingIntent: PendingIntent,
    )

    fun buildActionSpecs(runId: String): List<ActionSpec> {
        val viewIntent = Intent(context, RouteActivity::class.java).apply {
            action = ACTION_VIEW_VIRTUAL_RUN
            data = Uri.parse("amber://phone-use-live/${Uri.encode(runId)}")
            putExtra(EXTRA_RUN_ID, runId)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }
        val viewPi = PendingIntent.getActivity(
            context,
            requestCode(runId, "view"),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pauseIntent = Intent(context, VirtualDisplayRunControlReceiver::class.java).apply {
            action = ACTION_PAUSE_VIRTUAL_RUN
            putExtra(EXTRA_RUN_ID, runId)
        }
        val pausePi = PendingIntent.getBroadcast(
            context,
            requestCode(runId, "pause"),
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(context, VirtualDisplayRunControlReceiver::class.java).apply {
            action = ACTION_EMERGENCY_STOP_VIRTUAL_RUN
            putExtra(EXTRA_RUN_ID, runId)
        }
        val stopPi = PendingIntent.getBroadcast(
            context,
            requestCode(runId, "stop"),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return listOf(
            ActionSpec(
                kind = ActionKind.VIEW,
                action = ACTION_VIEW_VIRTUAL_RUN,
                title = context.getString(R.string.phone_use_vd_notif_action_view),
                pendingIntent = viewPi,
            ),
            ActionSpec(
                kind = ActionKind.PAUSE,
                action = ACTION_PAUSE_VIRTUAL_RUN,
                title = context.getString(R.string.phone_use_vd_notif_action_pause),
                pendingIntent = pausePi,
            ),
            ActionSpec(
                kind = ActionKind.EMERGENCY_STOP,
                action = ACTION_EMERGENCY_STOP_VIRTUAL_RUN,
                title = context.getString(R.string.phone_use_vd_notif_action_emergency_stop),
                pendingIntent = stopPi,
            ),
        )
    }

    fun buildNotification(
        runId: String,
        packageName: String,
        goal: String,
        paused: Boolean = false,
    ): Notification {
        val specs = buildActionSpecs(runId)
        val viewSpec = specs.first { it.kind == ActionKind.VIEW }
        val title = if (paused) {
            context.getString(R.string.phone_use_vd_notif_title_paused)
        } else {
            context.getString(R.string.phone_use_vd_notif_title_running)
        }
        val content = context.getString(
            R.string.phone_use_vd_notif_content,
            packageName,
            goal.take(80),
        )
        val builder = NotificationCompat.Builder(context, PHONE_USE_RUN_CHANNEL_ID)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(viewSpec.pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        specs.forEach { spec ->
            builder.addAction(
                NotificationCompat.Action.Builder(0, spec.title, spec.pendingIntent)
                    .setAuthenticationRequired(true)
                    .build(),
            )
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    fun showRunning(
        runId: String,
        packageName: String,
        goal: String,
        paused: Boolean = false,
    ) {
        if (runId.isBlank()) return
        if (!NotificationUtil.hasNotificationPermission(context)) return
        val notification = buildNotification(
            runId = runId,
            packageName = packageName,
            goal = goal,
            paused = paused,
        )
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(runId), notification)
        }
    }

    fun cancel(runId: String) {
        if (runId.isBlank()) return
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId(runId))
        }
    }

    companion object {
        const val ACTION_VIEW_VIRTUAL_RUN = "app.amber.action.PHONE_USE_VD_VIEW"
        const val ACTION_PAUSE_VIRTUAL_RUN = "app.amber.action.PHONE_USE_VD_PAUSE"
        const val ACTION_EMERGENCY_STOP_VIRTUAL_RUN = "app.amber.action.PHONE_USE_VD_EMERGENCY_STOP"
        const val EXTRA_RUN_ID = "phone_use_vd_run_id"

        private const val NOTIFICATION_ID_BASE = 49_200

        fun notificationId(runId: String): Int =
            NOTIFICATION_ID_BASE + ((runId.hashCode() and 0x7FFF) % 5_000)

        private fun requestCode(runId: String, kind: String): Int =
            "vd|$runId|$kind".hashCode()
    }
}
