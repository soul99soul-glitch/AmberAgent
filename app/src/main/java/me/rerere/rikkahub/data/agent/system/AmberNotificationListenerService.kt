package me.rerere.rikkahub.data.agent.system

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * System-level notification listener. Multiple consumers may want to react to incoming
 * notifications — Today Board collectors, Feishu enhancement, future widgets — so this
 * service exposes both:
 *   - a snapshot API for one-shot reads (kept for existing callers)
 *   - a listener registry so consumers can subscribe to live posted/removed events
 *
 * Listeners receive raw [StatusBarNotification] values; transformation/filtering is the
 * subscriber's responsibility. Errors thrown by one listener are logged and isolated so
 * they cannot block delivery to other listeners or to the system.
 *
 * The service is bound by Android once the user grants notification access; subscribers
 * registered before binding will receive events as soon as the service connects.
 */
class AmberNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        activeService = this
    }

    override fun onListenerDisconnected() {
        // Android may unbind the listener (e.g. when notification access is revoked or
        // the system briefly tears the binding down) without calling onDestroy. Null the
        // reference here so getActiveNotificationsSnapshot() doesn't try to dereference a
        // disconnected service, which throws SecurityException on some OEMs.
        if (activeService === this) {
            activeService = null
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        // Iterate over a snapshot so listeners can mutate the registry from within the
        // callback (e.g. unsubscribe after first hit) without ConcurrentModification.
        listeners.forEach { listener ->
            runCatching { listener.onNotificationPosted(notification) }
                .onFailure { android.util.Log.w(TAG, "listener.onPosted failed", it) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        listeners.forEach { listener ->
            runCatching { listener.onNotificationRemoved(notification) }
                .onFailure { android.util.Log.w(TAG, "listener.onRemoved failed", it) }
        }
    }

    /**
     * Subscriber contract. Implementations should be cheap — this is invoked on the
     * system listener thread; do not block, perform DB writes, or call the network here.
     * Hand off to a coroutine scope or queue for processing.
     */
    interface Listener {
        fun onNotificationPosted(sbn: StatusBarNotification)
        fun onNotificationRemoved(sbn: StatusBarNotification) {}
    }

    companion object {
        private const val TAG = "AmberNotifListener"

        @Volatile
        private var activeService: AmberNotificationListenerService? = null

        // Listeners registered process-wide; survive service restarts so subscribers don't
        // need to re-register every time the user toggles notification access.
        private val listeners = CopyOnWriteArrayList<Listener>()

        fun getActiveNotificationsSnapshot(): List<StatusBarNotification> =
            activeService?.activeNotifications?.toList().orEmpty()

        fun addListener(listener: Listener) {
            // CopyOnWriteArrayList.addIfAbsent is atomic; the contains+add pattern is not.
            listeners.addIfAbsent(listener)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        fun isConnected(): Boolean = activeService != null
    }
}
