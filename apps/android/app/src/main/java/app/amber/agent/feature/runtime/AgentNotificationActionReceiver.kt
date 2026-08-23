package app.amber.agent.feature.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch
import app.amber.agent.AppScope
import app.amber.agent.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.core.service.ChatService
import app.amber.core.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

/**
 * Notification action receiver — stop, approve, deny and ask_user short reply.
 *
 * P1-05: stop carries runId and ChatService validates ownership before
 * cancelling — a stale or mismatched runId cancels nothing.
 *
 * P8-10/P8-11: approve/deny/reply carry a one-time token bound to
 * (runId, conversationId, toolCallId, args digest). ChatService consumes the
 * token; an unknown, replayed or stale token resolves to nothing (fail
 * closed). A short reply that cannot be delivered surfaces an explicit
 * failure notification instead of silently dropping the user's text.
 */
class AgentNotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_GENERATION -> stopGeneration(intent)
            ACTION_APPROVE_TOOL -> decideTool(context, intent, approved = true)
            ACTION_DENY_TOOL -> decideTool(context, intent, approved = false)
            ACTION_REPLY_ASK_USER -> replyAskUser(context, intent)
            else -> return
        }
    }

    private fun stopGeneration(intent: Intent) {
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return
        // P1-05: forward the runId when the notification carries one.
        // ChatService.stopGeneration validates ownership before cancelling —
        // a stale or mismatched runId cancels nothing.
        val runId = intent.getStringExtra(EXTRA_RUN_ID)?.takeIf { it.isNotBlank() }
        val pendingResult = goAsync()
        get<AppScope>().launch {
            try {
                get<ChatService>().stopGeneration(conversationId, runId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun decideTool(context: Context, intent: Intent, approved: Boolean) {
        val conversationId = conversationIdOf(intent) ?: return
        val toolCallId = intent.getStringExtra(EXTRA_TOOL_CALL_ID)?.takeIf { it.isNotBlank() } ?: return
        // P8-10: no token, no action — notification ID alone is never enough.
        val token = intent.getStringExtra(EXTRA_APPROVAL_TOKEN)?.takeIf { it.isNotBlank() } ?: return
        val runId = intent.getStringExtra(EXTRA_RUN_ID)?.takeIf { it.isNotBlank() }
        val pendingResult = goAsync()
        get<AppScope>().launch {
            try {
                // A rejected token (replay / stale run / digest changed) simply
                // does nothing — never re-executes or re-applies a decision.
                get<ChatService>().handleNotificationApproval(
                    conversationId = conversationId,
                    runId = runId,
                    toolCallId = toolCallId,
                    approved = approved,
                    reason = "",
                    answer = null,
                    token = token,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun replyAskUser(context: Context, intent: Intent) {
        val conversationId = conversationIdOf(intent) ?: return
        val toolCallId = intent.getStringExtra(EXTRA_TOOL_CALL_ID)?.takeIf { it.isNotBlank() } ?: return
        val token = intent.getStringExtra(EXTRA_APPROVAL_TOKEN)?.takeIf { it.isNotBlank() } ?: return
        val runId = intent.getStringExtra(EXTRA_RUN_ID)?.takeIf { it.isNotBlank() }
        val answer = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(EXTRA_REPLY_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (answer.isBlank()) return
        val pendingResult = goAsync()
        get<AppScope>().launch {
            try {
                val accepted = get<ChatService>().handleNotificationApproval(
                    conversationId = conversationId,
                    runId = runId,
                    toolCallId = toolCallId,
                    approved = true,
                    reason = "",
                    answer = answer,
                    token = token,
                )
                // P8-11: when the conversation state does not allow the reply
                // (stale token, tool resolved, run gone), fail explicitly.
                if (!accepted) {
                    showReplyFailedNotification(context, conversationId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun conversationIdOf(intent: Intent): Uuid? =
        intent.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

    private fun showReplyFailedNotification(context: Context, conversationId: Uuid) {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = conversationId.hashCode() + REPLY_FAILED_NOTIFICATION_OFFSET,
        ) {
            title = context.getString(R.string.notification_live_status_action_failed)
            content = context.getString(R.string.notification_live_status_action_reply_failed)
            smallIcon = R.drawable.amberagent_live_status_icon
            autoCancel = true
            category = androidx.core.app.NotificationCompat.CATEGORY_STATUS
        }
    }

    companion object {
        const val ACTION_STOP_GENERATION = "app.amber.agent.action.STOP_GENERATION"
        const val ACTION_APPROVE_TOOL = "app.amber.agent.action.APPROVE_TOOL"
        const val ACTION_DENY_TOOL = "app.amber.agent.action.DENY_TOOL"
        const val ACTION_REPLY_ASK_USER = "app.amber.agent.action.REPLY_ASK_USER"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_TOOL_CALL_ID = "tool_call_id"
        const val EXTRA_APPROVAL_TOKEN = "approval_token"
        const val EXTRA_REPLY_TEXT = "reply_text"
        private const val REPLY_FAILED_NOTIFICATION_OFFSET = 30_000
    }
}
