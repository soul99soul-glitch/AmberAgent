package me.rerere.rikkahub.data.agent.board.collector

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.agent.board.BoardSignalSourceType
import me.rerere.rikkahub.data.agent.board.aggregator.SignalAggregator
import me.rerere.rikkahub.data.agent.system.AmberNotificationListenerService

/**
 * Captures system notifications. Behaves in two modes:
 *
 * 1. **Live mode**: registers as a [AmberNotificationListenerService.Listener] so each
 *    posted notification flows into the [SignalAggregator] as it arrives. This is the
 *    primary path — it captures notifications even when the app is fully backgrounded,
 *    because Android keeps the listener service alive once granted.
 *
 * 2. **Pull mode** ([collect]): on demand snapshot of currently active notifications.
 *    Invoked by the scheduler on first run or when re-enabled, so users don't show up to
 *    an empty board because the service hadn't been observing yet.
 *
 * Both paths funnel through the same [convert] mapping so dedup and hashing work
 * uniformly regardless of how the signal entered the system.
 */
class NotificationSignalCollector(
    private val context: Context,
    private val aggregator: SignalAggregator,
    private val ioScope: CoroutineScope,
) : BoardSignalCollector, AmberNotificationListenerService.Listener {
    override val sourceType: String = BoardSignalSourceType.NOTIFICATION
    fun start() {
        AmberNotificationListenerService.addListener(this)
    }

    fun stop() {
        AmberNotificationListenerService.removeListener(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val signal = convert(sbn) ?: return
        ioScope.launch {
            runCatching { aggregator.ingest(signal) }
                .onFailure { android.util.Log.w(TAG, "ingest from listener failed", it) }
        }
    }

    override suspend fun collect(limit: Int): List<RawBoardSignal> {
        return AmberNotificationListenerService.getActiveNotificationsSnapshot()
            .asSequence()
            .mapNotNull { convert(it) }
            .take(limit)
            .toList()
    }

    /**
     * Translate a [StatusBarNotification] into our raw shape. Filters out:
     *  - Foreground service notifications (mostly persistent, low signal-to-noise).
     *  - Empty / blank notifications (no useful text).
     *  - Our own app's notifications (avoid feedback loops with Today Board's own
     *    notifications, e.g. "Board updated").
     */
    private fun convert(sbn: StatusBarNotification): RawBoardSignal? {
        val notif = sbn.notification ?: return null
        val ourPackage = context.packageName
        if (sbn.packageName == ourPackage) return null

        val isForegroundService = notif.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        if (isForegroundService) return null

        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val combined = listOf(text, bigText).filter { it.isNotBlank() }.distinct().joinToString("\n")
        if (title.isBlank() && combined.isBlank()) return null

        // Compose a stable per-notification identifier. sbn.key is package + id + tag and
        // survives across reposts of the same logical notification, which is what we want
        // for dedup ("user kept getting reminded about the same thing") rather than the
        // post-time-based id which would treat each repost as new.
        val sourceRef = sbn.key

        return RawBoardSignal(
            sourceType = BoardSignalSourceType.NOTIFICATION,
            sourceRef = sourceRef,
            title = title.ifBlank { combined.lineSequence().firstOrNull().orEmpty().take(80) },
            content = combined.take(2_000),
            signalTime = sbn.postTime,
            metadataJson = buildMetadata(sbn),
        )
    }

    private fun buildMetadata(sbn: StatusBarNotification): String {
        val pkg = sbn.packageName.replace("\"", "\\\"")
        val channel = sbn.notification?.channelId.orEmpty().replace("\"", "\\\"")
        val tag = sbn.tag.orEmpty().replace("\"", "\\\"")
        return """{"package":"$pkg","channel":"$channel","tag":"$tag"}"""
    }

    companion object {
        private const val TAG = "BoardNotifCollector"
    }
}
