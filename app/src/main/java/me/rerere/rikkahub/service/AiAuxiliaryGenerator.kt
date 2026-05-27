package me.rerere.rikkahub.service

import android.content.Context
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.time.Instant
import java.util.Locale
import kotlin.uuid.Uuid

class AiAuxiliaryGenerator(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val conversationRepo: ConversationRepository,
    private val conversationAccess: ConversationAccess,
) {
    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false,
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.resolveTaskChatModel(settings.titleModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() },
                        ),
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )

            val title = result.choices[0].message?.toText()?.trim().orEmpty()
            val updatedConversation = conversationAccess.getConversationFlow(conversationId).value.copy(
                title = title,
                updateAt = Instant.now(),
            )
            conversationAccess.updateConversation(conversationId, updatedConversation)
            conversationRepo.updateConversationMetadata(
                conversationId = conversationId,
                title = title,
                updateAt = updatedConversation.updateAt,
            )
        }.onFailure {
            it.printStackTrace()
            conversationAccess.addError(it, conversationId, title = context.getString(R.string.error_title_generate_title))
        }
    }

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.resolveTaskChatModel(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val currentConv = conversationAccess.getConversationFlow(conversationId).value
            conversationAccess.updateConversation(
                conversationId,
                currentConv.copy(chatSuggestions = emptyList()),
            )

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() },
                        ),
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latest = conversationAccess.getConversationFlow(conversationId).value
            val updatedConversation = latest.copy(
                chatSuggestions = suggestions.take(10),
                updateAt = Instant.now(),
            )
            conversationAccess.updateConversation(conversationId, updatedConversation)
            conversationRepo.updateConversationMetadata(
                conversationId = conversationId,
                chatSuggestions = updatedConversation.chatSuggestions,
                updateAt = updatedConversation.updateAt,
            )
        }.onFailure {
            it.printStackTrace()
        }
    }
}
