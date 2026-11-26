package app.amber.agent

import android.app.Application
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update_v3"
const val SCREEN_CAPTURE_NOTIFICATION_CHANNEL_ID = "screen_capture"
const val MEMORY_NOTIFICATION_CHANNEL_ID = "memory_tasks"
const val BOARD_NOTIFICATION_CHANNEL_ID = "today_board"
const val DEEP_READ_NOTIFICATION_CHANNEL_ID = "deep_read"
const val NOVEL_GHOSTWRITE_FAILURE_NOTIFICATION_CHANNEL_ID = "novel_ghostwrite_failure"
// Must match `channel_id` sent by amber-gateway's FCM sender.
const val MAC_GATEWAY_NOTIFICATION_CHANNEL_ID = "mac_gateway"
const val PHONE_USE_RUN_CHANNEL_ID = "phone_use_virtual_run"


internal fun Application.createNotificationChannel() {
    val notificationManager = NotificationManagerCompat.from(this)
    val chatCompletedChannel = NotificationChannelCompat
        .Builder(
            CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH
        )
        .setName(getString(R.string.notification_channel_chat_completed))
        .setVibrationEnabled(true)
        .build()
    notificationManager.createNotificationChannel(chatCompletedChannel)

    val chatLiveUpdateChannel = NotificationChannelCompat
        .Builder(
            CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
        .setName(getString(R.string.notification_channel_chat_live_update))
        .setVibrationEnabled(false)
        .build()
    notificationManager.createNotificationChannel(chatLiveUpdateChannel)

    val screenCaptureChannel = NotificationChannelCompat
        .Builder(SCREEN_CAPTURE_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(getString(R.string.notification_channel_screen_capture))
        .setVibrationEnabled(false)
        .setShowBadge(false)
        .build()
    notificationManager.createNotificationChannel(screenCaptureChannel)

    val memoryChannel = NotificationChannelCompat
        .Builder(MEMORY_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(getString(R.string.notification_channel_memory_tasks))
        .setVibrationEnabled(false)
        .setShowBadge(false)
        .build()
    notificationManager.createNotificationChannel(memoryChannel)

    val boardChannel = NotificationChannelCompat
        .Builder(BOARD_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(getString(R.string.notification_channel_today_board))
        .setVibrationEnabled(false)
        .setShowBadge(false)
        .build()
    notificationManager.createNotificationChannel(boardChannel)

    val deepReadChannel = NotificationChannelCompat
        .Builder(DEEP_READ_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
        .setName(getString(R.string.notification_channel_deep_read))
        .setVibrationEnabled(false)
        .setShowBadge(false)
        .build()
    notificationManager.createNotificationChannel(deepReadChannel)

    // Terminal ghostwrite-batch failure; same terminal-result convention as chat_completed.
    val novelGhostwriteFailureChannel = NotificationChannelCompat
        .Builder(
            NOVEL_GHOSTWRITE_FAILURE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH
        )
        .setName(getString(R.string.notification_channel_novel_ghostwrite_failure))
        .setVibrationEnabled(true)
        .build()
    notificationManager.createNotificationChannel(novelGhostwriteFailureChannel)

    val macGatewayChannel = NotificationChannelCompat
        .Builder(MAC_GATEWAY_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
        .setName(getString(R.string.notification_channel_mac_gateway))
        .setVibrationEnabled(true)
        .build()
    notificationManager.createNotificationChannel(macGatewayChannel)

    val phoneUseRunChannel = NotificationChannelCompat
        .Builder(PHONE_USE_RUN_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(getString(R.string.notification_channel_phone_use_virtual_run))
        .setVibrationEnabled(false)
        .setShowBadge(false)
        .build()
    notificationManager.createNotificationChannel(phoneUseRunChannel)
}
