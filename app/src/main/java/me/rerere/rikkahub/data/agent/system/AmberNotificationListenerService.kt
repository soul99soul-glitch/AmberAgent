package me.rerere.rikkahub.data.agent.system

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AmberNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        activeService = this
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var activeService: AmberNotificationListenerService? = null

        fun getActiveNotificationsSnapshot(): List<StatusBarNotification> =
            activeService?.activeNotifications?.toList().orEmpty()
    }
}
