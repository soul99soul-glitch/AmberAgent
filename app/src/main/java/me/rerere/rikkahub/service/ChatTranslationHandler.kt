package me.rerere.rikkahub.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.AppScope
import java.util.Locale
import kotlin.uuid.Uuid

class ChatTranslationHandler(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsAggregator,
    private val generationHandler: GenerationHandler,
    private val conversationAccess: ConversationAccess,
) {
    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale,
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage,
                ) { translatedText ->
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect {}

                conversationAccess.saveConversation(
                    conversationId,
                    conversationAccess.getConversationFlow(conversationId).value,
                )
            } catch (e: Exception) {
                clearTranslationField(conversationId, message.id)
                conversationAccess.addError(
                    e, conversationId,
                    title = context.getString(R.string.error_title_translate_message),
                )
            }
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val current = conversationAccess.getConversationFlow(conversationId).value
        val updatedNodes = current.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                node.copy(messages = node.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(translation = null) else msg
                })
            } else {
                node
            }
        }
        conversationAccess.updateConversation(conversationId, current.copy(messageNodes = updatedNodes))
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String,
    ) {
        val current = conversationAccess.getConversationFlow(conversationId).value
        val updatedNodes = current.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                node.copy(messages = node.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(translation = translationText) else msg
                })
            } else {
                node
            }
        }
        conversationAccess.updateConversation(conversationId, current.copy(messageNodes = updatedNodes))
    }
}

interface ConversationAccess {
    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation>
    fun getConversationFlowOrNull(conversationId: Uuid): StateFlow<Conversation>?
    fun updateConversation(conversationId: Uuid, conversation: Conversation, checkDeletedFiles: Boolean = true)
    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation)
    fun addError(error: Throwable, conversationId: Uuid? = null, title: String? = null)
}
