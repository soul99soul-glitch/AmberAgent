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
import java.util.UUID

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
    private val onResumeRequested: (ActiveVirtualDisplayRun) -> Unit = {},
    private val onTakeoverRequested: suspend (ActiveVirtualDisplayRun) -> Unit = {},
    private val onEmergencyStopRequested: suspend (ActiveVirtualDisplayRun) -> Unit = {},
) {
    private val _controlState = MutableStateFlow(VirtualDisplayRunControlState.RUNNING)
    val controlState: StateFlow<VirtualDisplayRunControlState> = _controlState.asStateFlow()

    // A restored run or a later screen_run_goal under the same run gets a new control identity.
    val controlId: String = UUID.randomUUID().toString()
    val controlUri: Uri = Uri.Builder().scheme("amber").authority("phone-use-live")
        .appendPath(runId).appendPath(controlId).build()

    fun viewIntent(context: Context): Intent = Intent(context, RouteActivity::class.java).apply {
        action = VirtualDisplayRunNotifier.ACTION_VIEW_VIRTUAL_RUN
        data = controlUri
        putExtra(VirtualDisplayRunNotifier.EXTRA_RUN_ID, runId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

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
        onResumeRequested(this)
    }

    suspend fun takeover() {
        if (_controlState.value == VirtualDisplayRunControlState.STOPPED ||
            _controlState.value == VirtualDisplayRunControlState.TAKEN_OVER
        ) {
            return
        }
        _controlState.value = VirtualDisplayRunControlState.TAKEN_OVER
        onTakeoverRequested(this)
    }

    suspend fun emergencyStop() {
        if (_controlState.value == VirtualDisplayRunControlState.STOPPED) return
        _controlState.value = VirtualDisplayRunControlState.STOPPED
        onEmergencyStopRequested(this)
    }
}

/**
 * Process-scoped registry of active virtual display runs.
 * Allows [VirtualDisplayRunControlReceiver] and [app.amber.feature.ui.pages.phoneuse.PhoneUseLivePage]
 * to observe frames and trigger pause / takeover / emergency stop without leaking global state after close.
 */
object VirtualDisplayRunRegistry {
    private val _activeRuns = MutableStateFlow<Map<String, ActiveVirtualDisplayRun>>(emptyMap())
    val activeRuns: StateFlow<Map<String, ActiveVirtualDisplayRun>> = _activeRuns.asStateFlow()

    fun register(run: ActiveVirtualDisplayRun) {
        _activeRuns.update { it + (run.runId to run) }
    }

    fun unregister(runId: String, expected: ActiveVirtualDisplayRun? = null) {
        _activeRuns.update { current ->
            if (expected == null || current[runId] === expected) current - runId else current
        }
    }

    fun get(runId: String): ActiveVirtualDisplayRun? = _activeRuns.value[runId]

    fun forIntent(intent: Intent): ActiveVirtualDisplayRun? {
        val runId = intent.getStringExtra(VirtualDisplayRunNotifier.EXTRA_RUN_ID) ?: return null
        return get(runId)?.takeIf { it.controlUri == intent.data }
    }

    fun firstActiveRun(): ActiveVirtualDisplayRun? = _activeRuns.value.values.firstOrNull()

    internal fun clearForTest() {
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

    fun buildActionSpecs(run: ActiveVirtualDisplayRun): List<ActionSpec> {
        val runId = run.runId
        val viewIntent = run.viewIntent(context)
        val viewPi = PendingIntent.getActivity(
            context,
            requestCode(runId, "view"),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pauseIntent = Intent(context, VirtualDisplayRunControlReceiver::class.java).apply {
            action = ACTION_PAUSE_VIRTUAL_RUN
            data = run.controlUri
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
            data = run.controlUri
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
        run: ActiveVirtualDisplayRun,
        paused: Boolean = false,
    ): Notification {
        val specs = buildActionSpecs(run)
        val viewSpec = specs.first { it.kind == ActionKind.VIEW }
        val title = if (paused) {
            context.getString(R.string.phone_use_vd_notif_title_paused)
        } else {
            context.getString(R.string.phone_use_vd_notif_title_running)
        }
        val content = context.getString(
            R.string.phone_use_vd_notif_content,
            run.packageName,
            run.goal.take(80),
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
        run: ActiveVirtualDisplayRun,
        paused: Boolean = false,
    ) {
        if (VirtualDisplayRunRegistry.get(run.runId) !== run || !NotificationUtil.hasNotificationPermission(context)) return
        val notification = buildNotification(run, paused)
        runCatching {
            NotificationManagerCompat.from(context).notify(run.controlId, NOTIFICATION_ID, notification)
        }
    }

    fun cancel(run: ActiveVirtualDisplayRun) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(run.controlId, NOTIFICATION_ID)
        }
    }

    companion object {
        const val ACTION_VIEW_VIRTUAL_RUN = "app.amber.action.PHONE_USE_VD_VIEW"
        const val ACTION_PAUSE_VIRTUAL_RUN = "app.amber.action.PHONE_USE_VD_PAUSE"
        const val ACTION_EMERGENCY_STOP_VIRTUAL_RUN = "app.amber.action.PHONE_USE_VD_EMERGENCY_STOP"
        const val EXTRA_RUN_ID = "phone_use_vd_run_id"

        private const val NOTIFICATION_ID = 49_200

        private fun requestCode(runId: String, kind: String): Int =
            "vd|$runId|$kind".hashCode()
    }
}
