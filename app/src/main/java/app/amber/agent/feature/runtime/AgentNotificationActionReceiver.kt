package app.amber.agent.feature.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import app.amber.agent.AppScope
import app.amber.core.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

class AgentNotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_GENERATION) return
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return

        val pendingResult = goAsync()
        get<AppScope>().launch {
            try {
                get<ChatService>().stopGeneration(conversationId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP_GENERATION = "app.amber.agent.action.STOP_GENERATION"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
