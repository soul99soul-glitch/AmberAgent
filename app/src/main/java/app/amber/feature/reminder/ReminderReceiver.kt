package app.amber.feature.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import app.amber.agent.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.utils.sendNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** Pure receiver state transition used by JVM tests and kept intentionally short. */
data class ReminderDeliveryDecision(
    val commit: Boolean,
    val shouldNotify: Boolean,
    val shouldReschedule: Boolean,
)

fun decideReminderDelivery(snapshot: ReminderSnapshot?, incomingFireKey: String?): ReminderDeliveryDecision {
    val commit = snapshot != null && incomingFireKey != null &&
        !snapshot.isCorrupt && snapshot.enabled && snapshot.fireKey() == incomingFireKey
    return ReminderDeliveryDecision(
        commit = commit,
        shouldNotify = commit,
        shouldReschedule = commit,
    )
}

class ReminderReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val id = intent.getStringExtra(ReminderStore.EXTRA_REMINDER_ID)
                val fireKey = intent.getStringExtra(ReminderStore.EXTRA_FIRE_KEY)
                if (id.isNullOrBlank() || fireKey.isNullOrBlank()) return@launch
                val store = get<ReminderStore>()
                val current = store.read(id)
                val decision = decideReminderDelivery(current, fireKey)
                if (!decision.commit) return@launch
                val commit = store.commitFire(id, fireKey) ?: return@launch
                context.sendNotification(
                    channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                    notificationId = id.hashCode(),
                ) {
                    title = commit.fired.title
                    content = commit.fired.message
                    smallIcon = R.drawable.amberagent_live_status_icon
                    autoCancel = true
                    useDefaults = true
                    useBigTextStyle = true
                    category = NotificationCompat.CATEGORY_ALARM
                    priority = NotificationCompat.PRIORITY_HIGH
                    contentIntent = reminderPendingIntent(context, commit.fired)
                }
                if (decision.shouldReschedule) {
                    get<ReminderScheduler>().schedule(commit.updated)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "app.amber.action.REMINDER_FIRE"

        fun fireUri(id: String, fireKey: String): android.net.Uri =
            android.net.Uri.parse("amberagent://reminder/fire/$id/${fireKey.hashCode()}")

        internal fun reminderOpenIntent(context: Context, reminder: ReminderSnapshot): Intent =
            Intent(context, RouteActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(
                    RouteActivity.EXTRA_OPEN_CHAT_PROMPT,
                    "提醒：${reminder.title}\n${reminder.message}",
                )
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        private fun reminderPendingIntent(context: Context, reminder: ReminderSnapshot): PendingIntent {
            return PendingIntent.getActivity(
                context,
                reminder.id.hashCode(),
                reminderOpenIntent(context, reminder),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

/** Receives process-independent system changes and delegates to the one owner scheduler. */
class ReminderRescheduleReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                get<ReminderScheduler>().rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManagerActions.EXACT_ALARM_PERMISSION_CHANGED,
        )
    }
}

private object AlarmManagerActions {
    const val EXACT_ALARM_PERMISSION_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
}
