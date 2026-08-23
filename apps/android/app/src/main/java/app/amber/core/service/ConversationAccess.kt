package app.amber.core.service

import app.amber.core.model.Conversation
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

interface ConversationAccess {
    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation>
    fun getConversationFlowOrNull(conversationId: Uuid): StateFlow<Conversation>?
    fun updateConversation(
        conversationId: Uuid,
        conversation: Conversation,
        checkDeletedFiles: Boolean = true,
    )

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation)
    fun addError(error: Throwable, conversationId: Uuid? = null, title: String? = null)
}
