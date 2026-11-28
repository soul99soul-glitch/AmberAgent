package app.amber.core.automation.display.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.amber.agent.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the reply available while Settings owns the pairing-code dialog. API 34+. */
class WirelessAdbPairingNotification : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pairing: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = requireNotNull(getSystemService(NotificationManager::class.java))
        if (intent?.action == REPLY && pairing?.isActive != true) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString()?.trim().orEmpty()
            if (code.matches(Regex("[0-9]{6}"))) {
                manager.notify(ID, notification(R.string.vd_adb_pairing, false))
                pairing = scope.launch {
                    val message = try {
                        LocalAdbTlsClient(applicationContext).pair(null, code)
                        completedPairs.value++
                        R.string.vd_adb_pair_success
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        R.string.vd_adb_pair_error
                    }
                    stopForeground(STOP_FOREGROUND_DETACH)
                    manager.notify(ID, notification(message, false))
                    stopSelf()
                }
            } else {
                manager.notify(ID, notification(R.string.vd_adb_six_digits, true))
            }
        } else if (intent?.action == PROMPT) {
            startForeground(ID, notification(R.string.vd_adb_pair_instructions, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        }
        return START_NOT_STICKY
    }

    private fun notification(message: Int, reply: Boolean): Notification {
        val text = getString(message)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(getString(R.string.vd_adb_section))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
        if (reply) {
            val submit = PendingIntent.getService(
                this, ID, Intent(this, WirelessAdbPairingNotification::class.java).setAction(REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val label = getString(R.string.vd_adb_code)
            val input = RemoteInput.Builder(CODE).setLabel(label).build()
            builder.addAction(Notification.Action.Builder(null, label, submit).addRemoteInput(input).build())
        }
        return builder.build()
    }

    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val completedPairs = MutableStateFlow(0)
        internal val pairRevision = completedPairs.asStateFlow()
        private const val CHANNEL = "wireless_adb_pairing"
        private const val ID = 49202
        private const val PROMPT = "wireless_adb.prompt"
        private const val REPLY = "wireless_adb.reply"
        private const val CODE = "wireless_adb.code"

        fun show(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.vd_adb_section), NotificationManager.IMPORTANCE_HIGH))
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            context.startForegroundService(Intent(context, WirelessAdbPairingNotification::class.java).setAction(PROMPT))
            return true
        }
    }
}
