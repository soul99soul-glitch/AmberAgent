package me.rerere.rikkahub.data.agent.board.collector

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.agent.board.BoardSignalSourceType
import me.rerere.rikkahub.data.agent.board.aggregator.SignalAggregator
import app.amber.feature.system.AmberNotificationListenerService

/**
 * Captures system notifications with signal-to-noise filtering.
 *
 * Three-layer filter before a notification becomes a signal:
 *
 * 1. **Hard reject**: foreground-service, empty content, our own app, system UI noise.
 * 2. **Package classification**: communication apps (WeChat, DingTalk, Feishu, Gmail,
 *    Outlook, Slack, Telegram, WhatsApp) are high-value; known low-signal packages
 *    (shopping, delivery, ad SDKs, phone managers) are dropped.
 * 3. **Same-source merging**: when multiple notifications from the same package+channel
 *    arrive within a merge window, they're combined into a single signal to avoid
 *    flooding the Board with 20 WeChat messages as 20 separate items.
 *
 * Live mode and pull mode are unchanged from the original design.
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
        val snapshots = AmberNotificationListenerService.getActiveNotificationsSnapshot()
        return mergeNotifications(
            snapshots.mapNotNull { convert(it) }
        ).take(limit)
    }

    /**
     * Translate a [StatusBarNotification] into our raw shape. Hard-filters:
     *  - Foreground service notifications (persistent, low signal).
     *  - Empty / blank notifications.
     *  - Our own app's notifications (avoid feedback loops).
     *  - Known low-signal packages (shopping, delivery, system bloatware).
     */
    private fun convert(sbn: StatusBarNotification): RawBoardSignal? {
        val notif = sbn.notification ?: return null
        val ourPackage = context.packageName
        if (sbn.packageName == ourPackage) return null

        val isForegroundService = notif.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        if (isForegroundService) return null

        // Package-level filtering
        val pkg = sbn.packageName
        if (isLowSignalPackage(pkg)) return null

        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val combined = listOf(text, bigText).filter { it.isNotBlank() }.distinct().joinToString("\n")
        if (title.isBlank() && combined.isBlank()) return null

        val sourceRef = sbn.key
        val isHighValue = isHighValuePackage(pkg)
        val isWorkContext = pkg in WORK_CONTEXT_PACKAGES
        val hasAtMention = combined.contains("@") || title.contains("@")
            || combined.contains("回复了你") || combined.contains("提到了你")
            || combined.contains("replied") || combined.contains("mentioned")
        val hasWorkSignal = isWorkContext && (
            combined.contains("审批") || combined.contains("日程") || combined.contains("会议")
            || combined.contains("待办") || combined.contains("任务") || combined.contains("OKR")
            || combined.contains("approval") || combined.contains("meeting")
            || combined.contains("schedule") || combined.contains("todo")
        )

        // Encode priority hints in metadata so the aggregator scoring can use them
        val priorityHint = when {
            hasWorkSignal -> "high"
            isHighValue && hasAtMention -> "high"
            isHighValue -> "medium"
            else -> "low"
        }

        return RawBoardSignal(
            sourceType = BoardSignalSourceType.NOTIFICATION,
            sourceRef = sourceRef,
            title = title.ifBlank { combined.lineSequence().firstOrNull().orEmpty().take(80) },
            content = combined.take(2_000),
            signalTime = sbn.postTime,
            metadataJson = buildMetadata(sbn, priorityHint),
        )
    }

    /**
     * Merge notifications from the same package+channel into combined signals.
     * Keeps the latest notification's timestamp, concatenates content.
     */
    private fun mergeNotifications(signals: List<RawBoardSignal>): List<RawBoardSignal> {
        if (signals.size <= 1) return signals

        data class MergeKey(val pkg: String, val channel: String)

        val grouped = mutableMapOf<MergeKey, MutableList<RawBoardSignal>>()
        for (signal in signals) {
            // Extract package and channel from metadata
            val pkg = extractJsonField(signal.metadataJson, "package")
            val channel = extractJsonField(signal.metadataJson, "channel")
            val key = MergeKey(pkg, channel)
            grouped.getOrPut(key) { mutableListOf() }.add(signal)
        }

        return grouped.flatMap { (_, group) ->
            if (group.size <= MERGE_THRESHOLD) {
                // Few enough to keep individual
                group
            } else {
                // Merge into one combined signal
                val latest = group.maxBy { it.signalTime }
                val mergedContent = buildString {
                    appendLine("[${group.size}条通知合并]")
                    for (s in group.sortedByDescending { it.signalTime }.take(5)) {
                        appendLine("• ${s.title}: ${s.content.take(200)}")
                    }
                    if (group.size > 5) {
                        appendLine("…及其他${group.size - 5}条")
                    }
                }
                listOf(
                    latest.copy(
                        title = "${latest.title}（等${group.size}条）",
                        content = mergedContent.take(2_000),
                    )
                )
            }
        }
    }

    private fun extractJsonField(json: String, key: String): String {
        val pattern = "\"$key\":\"([^\"]*)\""
        return Regex(pattern).find(json)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun buildMetadata(sbn: StatusBarNotification, priorityHint: String): String {
        val pkg = sbn.packageName.replace("\"", "\\\"")
        val channel = sbn.notification?.channelId.orEmpty().replace("\"", "\\\"")
        val tag = sbn.tag.orEmpty().replace("\"", "\\\"")
        // For work-context apps, include the channel ID which often encodes the notification
        // type (e.g. "message", "calendar_remind", "approval", "todo" on Lark/Feishu).
        val workType = if (sbn.packageName in WORK_CONTEXT_PACKAGES) {
            classifyLarkChannel(channel)
        } else ""
        val workTypePart = if (workType.isNotBlank()) ""","work_type":"$workType"""" else ""
        return """{"package":"$pkg","channel":"$channel","tag":"$tag","priority":"$priorityHint"$workTypePart}"""
    }

    /**
     * Classify Lark/Feishu notification channels into work categories.
     * Channel IDs vary across Lark versions but follow stable patterns.
     */
    private fun classifyLarkChannel(channel: String): String {
        val ch = channel.lowercase()
        return when {
            ch.contains("message") || ch.contains("im") || ch.contains("chat") -> "message"
            ch.contains("calendar") || ch.contains("schedule") || ch.contains("meeting") -> "calendar"
            ch.contains("approval") || ch.contains("审批") -> "approval"
            ch.contains("todo") || ch.contains("task") || ch.contains("待办") -> "task"
            ch.contains("doc") || ch.contains("wiki") || ch.contains("sheet") -> "document"
            ch.contains("bot") || ch.contains("webhook") -> "bot"
            else -> "other"
        }
    }

    companion object {
        private const val TAG = "BoardNotifCollector"

        /** Notifications from same source exceeding this count get merged. */
        private const val MERGE_THRESHOLD = 2

        // ---- Package classification ----

        /** Communication apps — high signal-to-noise, treated as important. */
        private val HIGH_VALUE_PACKAGES = setOf(
            "com.tencent.mm",                    // WeChat
            "com.tencent.wework",                // 企业微信
            "com.alibaba.android.rimet",         // DingTalk
            "com.ss.android.lark",               // Feishu / Lark
            "com.ss.android.lark.saxmsa667",     // 小米办公 Pro (Feishu-based)
            "com.google.android.gm",             // Gmail
            "com.microsoft.office.outlook",      // Outlook
            "com.slack",                         // Slack
            "org.telegram.messenger",            // Telegram
            "com.whatsapp",                      // WhatsApp
            "com.github.android",                // GitHub
            "com.microsoft.teams",               // Teams
        )

        /** Packages whose notifications carry extra-rich work context (meetings, approvals, tasks). */
        private val WORK_CONTEXT_PACKAGES = setOf(
            "com.ss.android.lark",
            "com.ss.android.lark.saxmsa667",
            "com.alibaba.android.rimet",
            "com.tencent.wework",
        )

        /** Known low-signal packages — shopping, delivery, system bloatware, ads. */
        private val LOW_SIGNAL_PACKAGES = setOf(
            // Shopping & delivery
            "com.taobao.taobao",
            "com.jingdong.app.mall",
            "com.xunmeng.pinduoduo",
            "com.eg.android.AlipayGphone",  // Alipay (mostly promo)
            "com.sankuai.meituan",
            "me.ele",
            "com.sf.activity",              // SF Express
            "com.achievo.vipshop",
            // System / utility noise
            "com.android.vending",          // Play Store
            "com.google.android.apps.maps",
            "com.android.providers.downloads",
            "com.miui.cleanmaster",
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.coloros.safecenter",
            // Weather & media
            "com.moji.mjweather",
            "com.autonavi.minimap",         // Amap
        )

        /** Prefixes for broad package-family filtering. */
        private val LOW_SIGNAL_PREFIXES = listOf(
            "com.android.systemui",
            "com.google.android.googlequicksearchbox",
        )

        private fun isHighValuePackage(pkg: String): Boolean = pkg in HIGH_VALUE_PACKAGES

        private fun isLowSignalPackage(pkg: String): Boolean {
            if (pkg in LOW_SIGNAL_PACKAGES) return true
            return LOW_SIGNAL_PREFIXES.any { pkg.startsWith(it) }
        }
    }
}
