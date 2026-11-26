package app.amber.feature.macgateway

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.amber.agent.MAC_GATEWAY_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.agent.RouteActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject

/**
 * Handles both Cloud Proactive `amber_v = "1"` data-only pushes and legacy Mac amber-gateway
 * notification messages (`message.notification` on the `mac_gateway` channel).
 */
class MacGatewayMessagingService : FirebaseMessagingService() {
    private val repository: MacGatewayRepository by inject()
    private val proactivePresenter: ProactiveNotificationPresenter by inject()

    override fun onNewToken(token: String) {
        repository.onNewPushToken()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (ProactivePushMessage.isAmberV1DataPush(data)) {
            val snapshot = data.toMap()
            runCatching {
                runBlocking {
                    withTimeoutOrNull(DATA_PUSH_HANDLING_TIMEOUT_MS) {
                        proactivePresenter.handleIncomingDataPush(snapshot)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "failed to process amber_v=1 data push", error)
            }
            return
        }
        handleLegacyMacNotification(message)
    }

    private fun handleLegacyMacNotification(message: RemoteMessage) {
        val notification = message.notification ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, RouteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val built = NotificationCompat.Builder(this, MAC_GATEWAY_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // Same tag as the system-displayed variant, so a newer state replaces the older one per task.
        runCatching { NotificationManagerCompat.from(this).notify(notification.tag, 0, built) }
    }

    private companion object {
        const val TAG = "MacGatewayMessaging"
        const val DATA_PUSH_HANDLING_TIMEOUT_MS = 8_000L
    }
}
