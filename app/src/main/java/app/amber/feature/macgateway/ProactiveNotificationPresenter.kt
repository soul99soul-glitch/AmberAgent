package app.amber.feature.macgateway

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.amber.agent.EXTRA_OPEN_PERSONAL_AGENT
import app.amber.agent.PROACTIVE_ACTIVE_CHANNEL_ID
import app.amber.agent.PROACTIVE_PASSIVE_CHANNEL_ID
import app.amber.agent.PROACTIVE_URGENT_CHANNEL_ID
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.utils.NotificationUtil

const val ACTION_PROACTIVE_SNOOZE = "app.amber.action.PROACTIVE_SNOOZE"
const val ACTION_PROACTIVE_MUTE = "app.amber.action.PROACTIVE_MUTE"

/**
 * Each resumed cloud Personal Agent page registers its displayed pairing. One visible
 * activity cannot be cleared by another activity pausing in multi-window mode.
 */
object ProactiveForegroundStageTracker {
    private val displayedCloudHosts = java.util.concurrent.ConcurrentHashMap<Any, MacGatewayConnection>()

    fun enter(owner: Any, connection: MacGatewayConnection) {
        displayedCloudHosts[owner] = connection
    }
    fun leave(owner: Any) {
        displayedCloudHosts.remove(owner)
    }
    fun isVisible(connection: MacGatewayConnection?): Boolean =
        connection != null && displayedCloudHosts.containsValue(connection)

}

sealed interface ProactivePushPresentationOutcome {
    data object DroppedInvalidOrMismatched : ProactivePushPresentationOutcome
    data object SuppressedForegroundPolled : ProactivePushPresentationOutcome
    data object SuppressedAlreadyHandled : ProactivePushPresentationOutcome
    data object SuppressedNoPermission : ProactivePushPresentationOutcome
    data class Notified(
        val channelId: String,
        val tag: String,
        val notificationId: Int,
    ) : ProactivePushPresentationOutcome
}

data class BuiltProactiveNotification(
    val channelId: String,
    val tag: String,
    val notificationId: Int,
    val notification: Notification,
    val actions: List<ActionDescriptor>,
) {
    enum class ActionKind {
        ACT,
        SNOOZE,
        MUTE,
        OPEN_APPROVAL,
    }

    data class ActionDescriptor(
        val kind: ActionKind,
        val title: String,
        val requestCode: Int,
        val intent: Intent,
        val pendingIntent: PendingIntent,
    )
}

/**
 * Presents validated `amber_v = "1"` FCM data-only pushes onto the three proactive channels.
 *
 * Enforces §3.6 / §3.7 invariants:
 * - Drops invalid payloads or gateway/device mismatches silently.
 * - Suppresses banners while the foreground cloud main stage is visible and triggers an immediate poll.
 * - Deduplicates late/duplicate proactive pushes using `proactive_cards.notified_at` / `feedback_state`.
 * - "Let Amber handle" uses a direct Activity [PendingIntent] (`amber://proactive/<signalId>`, no trampoline).
 * - "In 1 hour" / "Less like this" use explicit non-exported Broadcast [PendingIntent]s with unique
 *   request codes AND unique data URIs (extras are excluded from Intent equivalence) and `FLAG_IMMUTABLE`.
 * - `type == "approval"` NEVER shows an approve button — only "Open app to review".
 */
class ProactiveNotificationPresenter internal constructor(
    private val context: Context,
    private val awaitGatewayReady: suspend () -> Unit,
    private val currentConnection: () -> MacGatewayConnection?,
    private val isCloudMainStageVisible: () -> Boolean,
    private val requestImmediatePoll: (String) -> Unit,
    private val alreadyHandled: suspend (gatewayId: String, deviceId: String, signalId: String) -> Boolean,
    private val markCardNotified: suspend (gatewayId: String, deviceId: String, signalId: String, notifiedAt: Long) -> Unit,
    private val hasNotificationPermission: () -> Boolean = { NotificationUtil.hasNotificationPermission(context) },
    private val notifyPlatform: (tag: String, id: Int, notification: Notification) -> Unit = { tag, id, notification ->
        notifyWithManager(context, tag, id, notification)
    },
    private val cancelPlatform: (tag: String, id: Int) -> Unit = { tag, id ->
        runCatching { NotificationManagerCompat.from(context).cancel(tag, id) }
    },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        context: Context,
        gatewayRepository: MacGatewayRepository,
        receipts: ProactiveDeliveryReceipts,
    ) : this(
        context = context.applicationContext,
        awaitGatewayReady = { gatewayRepository.awaitReady() },
        currentConnection = { gatewayRepository.state.value.connection },
        isCloudMainStageVisible = {
            val state = gatewayRepository.state.value
            state.isCloudDialogueActive && ProactiveForegroundStageTracker.isVisible(state.connection)
        },
        requestImmediatePoll = gatewayRepository::requestImmediatePoll,
        alreadyHandled = receipts::handled,
        markCardNotified = receipts::notified,
    )

    private val incomingMutex = Mutex()

    suspend fun handleIncomingDataPush(data: Map<String, String>): ProactivePushPresentationOutcome =
        incomingMutex.withLock { present(data) }

    private suspend fun present(data: Map<String, String>): ProactivePushPresentationOutcome {
        awaitGatewayReady()
        val message = ProactivePushMessage.parseForPairing(data, currentConnection())
            ?: return ProactivePushPresentationOutcome.DroppedInvalidOrMismatched

        if (message.type == ProactivePushType.PROACTIVE) {
            val signalId = message.signalId
                ?: return ProactivePushPresentationOutcome.DroppedInvalidOrMismatched
            if (alreadyHandled(message.gatewayId, message.deviceId, signalId)) {
                requestImmediatePoll(POLL_REASON_PUSH)
                return ProactivePushPresentationOutcome.SuppressedAlreadyHandled
            }
        }

        if (!message.matchesPairing(currentConnection())) return ProactivePushPresentationOutcome.DroppedInvalidOrMismatched
        if (isCloudMainStageVisible()) {
            requestImmediatePoll(POLL_REASON_PUSH)
            return ProactivePushPresentationOutcome.SuppressedForegroundPolled
        }

        if (!hasNotificationPermission()) {
            if (message.type == ProactivePushType.PROACTIVE || message.type == ProactivePushType.APPROVAL) {
                requestImmediatePoll(POLL_REASON_PUSH)
            }
            return ProactivePushPresentationOutcome.SuppressedNoPermission
        }

        val built = buildNotification(message)
        notifyPlatform(built.tag, built.notificationId, built.notification)

        if (message.type == ProactivePushType.PROACTIVE) {
            val signalId = message.signalId!!
            markCardNotified(message.gatewayId, message.deviceId, signalId, nowMs())
            requestImmediatePoll(POLL_REASON_PUSH)
        } else if (message.type == ProactivePushType.APPROVAL) {
            requestImmediatePoll(POLL_REASON_PUSH)
        }

        return ProactivePushPresentationOutcome.Notified(
            channelId = built.channelId,
            tag = built.tag,
            notificationId = built.notificationId,
        )
    }

    private fun Intent.forPairing(message: ProactivePushMessage, operation: String): Intent = apply {
        addCategory("app.amber.proactive.${Uri.encode(message.gatewayId)}/${Uri.encode(message.deviceId)}/$operation")
        putExtra(EXTRA_GATEWAY_ID, message.gatewayId)
        putExtra(EXTRA_DEVICE_ID, message.deviceId)
    }

    fun buildNotification(message: ProactivePushMessage): BuiltProactiveNotification {
        val channelId = channelIdFor(message.interruptionLevel)
        val priority = priorityFor(message.interruptionLevel)
        val notificationId = PROACTIVE_NOTIFICATION_ID
        val tag = if (message.type == ProactivePushType.PROACTIVE) {
            proactiveNotificationTag(message.gatewayId, message.deviceId, requireNotNull(message.signalId))
        } else {
            "${Uri.encode(message.gatewayId)}/${Uri.encode(message.deviceId)}/${message.tag}"
        }

        val actions = mutableListOf<BuiltProactiveNotification.ActionDescriptor>()
        val contentIntent: PendingIntent = when (message.type) {
            ProactivePushType.PROACTIVE -> {
                val signalId = requireNotNull(message.signalId) { "proactive push requires signalId" }
                val openIntent = buildDeepLinkActivityIntent(context, signalId).forPairing(message, "open")
                val openPi = PendingIntent.getActivity(
                    context,
                    actionRequestCode(message.gatewayId, signalId, "open"),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                val actIntent = buildDeepLinkActivityIntent(context, signalId).forPairing(message, "act")
                val actRequestCode = actionRequestCode(message.gatewayId, signalId, "act")
                val actPi = PendingIntent.getActivity(
                    context,
                    actRequestCode,
                    actIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                actions += BuiltProactiveNotification.ActionDescriptor(
                    kind = BuiltProactiveNotification.ActionKind.ACT,
                    title = context.getString(R.string.proactive_notif_action_act),
                    requestCode = actRequestCode,
                    intent = actIntent,
                    pendingIntent = actPi,
                )

                val snoozeIntent = buildBackgroundActionIntent(
                    context = context,
                    gatewayId = message.gatewayId,
                    deviceId = message.deviceId,
                    signalId = signalId,
                    action = ACTION_PROACTIVE_SNOOZE,
                    notificationTag = tag,
                    notificationId = notificationId,
                )
                val snoozeRequestCode = actionRequestCode(message.gatewayId, signalId, ACTION_PROACTIVE_SNOOZE)
                val snoozePi = PendingIntent.getBroadcast(
                    context,
                    snoozeRequestCode,
                    snoozeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                actions += BuiltProactiveNotification.ActionDescriptor(
                    kind = BuiltProactiveNotification.ActionKind.SNOOZE,
                    title = context.getString(R.string.proactive_notif_action_snooze),
                    requestCode = snoozeRequestCode,
                    intent = snoozeIntent,
                    pendingIntent = snoozePi,
                )

                val muteIntent = buildBackgroundActionIntent(
                    context = context,
                    gatewayId = message.gatewayId,
                    deviceId = message.deviceId,
                    signalId = signalId,
                    action = ACTION_PROACTIVE_MUTE,
                    notificationTag = tag,
                    notificationId = notificationId,
                )
                val muteRequestCode = actionRequestCode(message.gatewayId, signalId, ACTION_PROACTIVE_MUTE)
                val mutePi = PendingIntent.getBroadcast(
                    context,
                    muteRequestCode,
                    muteIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                actions += BuiltProactiveNotification.ActionDescriptor(
                    kind = BuiltProactiveNotification.ActionKind.MUTE,
                    title = context.getString(R.string.proactive_notif_action_mute),
                    requestCode = muteRequestCode,
                    intent = muteIntent,
                    pendingIntent = mutePi,
                )
                openPi
            }

            ProactivePushType.APPROVAL -> {
                // Safety invariant: approval pushes NEVER expose a notification-shade approve button.
                val reviewIntent = buildOpenPersonalAgentIntent(context, tag).forPairing(message, "review")
                val reviewRequestCode = actionRequestCode(message.gatewayId, tag, "open_approval")
                val reviewPi = PendingIntent.getActivity(
                    context,
                    reviewRequestCode,
                    reviewIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                actions += BuiltProactiveNotification.ActionDescriptor(
                    kind = BuiltProactiveNotification.ActionKind.OPEN_APPROVAL,
                    title = context.getString(R.string.proactive_notif_action_open_approval),
                    requestCode = reviewRequestCode,
                    intent = reviewIntent,
                    pendingIntent = reviewPi,
                )
                reviewPi
            }

            ProactivePushType.GENERIC -> {
                val openIntent = buildOpenPersonalAgentIntent(context, tag).forPairing(message, "open")
                PendingIntent.getActivity(
                    context,
                    actionRequestCode(message.gatewayId, tag, "generic"),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(message.title)
            .setOnlyAlertOnce(true)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(priority)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        if (message.interruptionLevel == ProactiveInterruptionLevel.PASSIVE) {
            builder.setSilent(true)
        }

        actions.forEach { descriptor ->
            val actionBuilder = NotificationCompat.Action.Builder(
                0,
                descriptor.title,
                descriptor.pendingIntent,
            )
            if (descriptor.kind == BuiltProactiveNotification.ActionKind.SNOOZE ||
                descriptor.kind == BuiltProactiveNotification.ActionKind.MUTE
            ) {
                actionBuilder.setAuthenticationRequired(true)
            }
            builder.addAction(actionBuilder.build())
        }

        return BuiltProactiveNotification(
            channelId = channelId,
            tag = tag,
            notificationId = notificationId,
            notification = builder.build(),
            actions = actions,
        )
    }

    fun showFeedbackIncompleteNotification(
        signalId: String,
        action: ProactiveFeedbackAction,
    ) {
        if (!hasNotificationPermission()) return
        val bodyRes = when (action) {
            ProactiveFeedbackAction.SNOOZE -> R.string.proactive_feedback_incomplete_body_snooze
            ProactiveFeedbackAction.MUTE_TOPIC -> R.string.proactive_feedback_incomplete_body_mute
            else -> R.string.proactive_feedback_incomplete_body_generic
        }
        val title = context.getString(R.string.proactive_feedback_incomplete_title)
        val body = context.getString(bodyRes)
        // Incomplete work can belong to a forgotten pairing. Open the stage without resolving
        // this signal on the currently paired gateway.
        val intent = buildOpenPersonalAgentIntent(context, "incomplete:$signalId:${action.wire}")
        val contentIntent = PendingIntent.getActivity(
            context,
            actionRequestCode("local", signalId, "incomplete:${action.wire}"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, PROACTIVE_ACTIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notifyPlatform(
            incompleteNotificationTag(signalId, action),
            PROACTIVE_FEEDBACK_INCOMPLETE_NOTIFICATION_ID,
            notification,
        )
    }

    fun cancelProactiveNotification(gatewayId: String, deviceId: String, signalId: String) {
        cancelPlatform(proactiveNotificationTag(gatewayId, deviceId, signalId), PROACTIVE_NOTIFICATION_ID)
    }

    companion object {
        const val POLL_REASON_PUSH = "push"
        const val PROACTIVE_NOTIFICATION_ID = 0
        const val PROACTIVE_FEEDBACK_INCOMPLETE_NOTIFICATION_ID = 1

        const val EXTRA_GATEWAY_ID = "proactive_gateway_id"
        const val EXTRA_DEVICE_ID = "proactive_device_id"
        const val EXTRA_SIGNAL_ID = "proactive_signal_id"
        const val EXTRA_ACTION = "proactive_action"
        const val EXTRA_NOTIFICATION_TAG = "proactive_notification_tag"
        const val EXTRA_NOTIFICATION_ID = "proactive_notification_id"

        fun channelIdFor(level: ProactiveInterruptionLevel): String = when (level) {
            ProactiveInterruptionLevel.PASSIVE -> PROACTIVE_PASSIVE_CHANNEL_ID
            ProactiveInterruptionLevel.ACTIVE -> PROACTIVE_ACTIVE_CHANNEL_ID
            ProactiveInterruptionLevel.TIME_SENSITIVE -> PROACTIVE_URGENT_CHANNEL_ID
        }

        fun priorityFor(level: ProactiveInterruptionLevel): Int = when (level) {
            ProactiveInterruptionLevel.PASSIVE -> NotificationCompat.PRIORITY_LOW
            ProactiveInterruptionLevel.ACTIVE -> NotificationCompat.PRIORITY_DEFAULT
            ProactiveInterruptionLevel.TIME_SENSITIVE -> NotificationCompat.PRIORITY_HIGH
        }

        fun actionRequestCode(gatewayId: String, signalId: String, action: String): Int =
            "$gatewayId|$signalId|$action".hashCode()

        fun proactiveNotificationTag(gatewayId: String, deviceId: String, signalId: String): String =
            "${Uri.encode(gatewayId)}/${Uri.encode(deviceId)}/proactive:$signalId"

        fun incompleteNotificationTag(signalId: String, action: ProactiveFeedbackAction): String =
            "proactive-incomplete:$signalId:${action.wire}"

        fun buildDeepLinkActivityIntent(context: Context, signalId: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("amber://proactive/$signalId"), context, RouteActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }

        fun buildOpenPersonalAgentIntent(context: Context, tag: String): Intent =
            Intent(context, RouteActivity::class.java).apply {
                action = "app.amber.action.OPEN_PROACTIVE_STAGE"
                data = Uri.parse("amber-proactive-open://${Uri.encode(tag)}")
                putExtra(EXTRA_OPEN_PERSONAL_AGENT, true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }

        fun buildBackgroundActionIntent(
            context: Context,
            gatewayId: String,
            deviceId: String,
            signalId: String,
            action: String,
            notificationTag: String,
            notificationId: Int,
        ): Intent = Intent(context, ProactiveNotificationActionReceiver::class.java).apply {
            this.action = action
            // Distinct data URI per (gatewayId, signalId, action) so Android PendingIntent
            // equivalence (which ignores extras) never aliases different cards or gateways.
            data = Uri.parse(
                "amber-proactive-action://${Uri.encode(gatewayId)}/${Uri.encode(deviceId)}/${Uri.encode(signalId)}/${Uri.encode(action)}",
            )
            putExtra(EXTRA_GATEWAY_ID, gatewayId)
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_SIGNAL_ID, signalId)
            putExtra(EXTRA_ACTION, action)
            putExtra(EXTRA_NOTIFICATION_TAG, notificationTag)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        @SuppressLint("MissingPermission")
        private fun notifyWithManager(
            context: Context,
            tag: String,
            id: Int,
            notification: Notification,
        ) {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        }
    }
}
