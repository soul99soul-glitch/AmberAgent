package app.amber.core.service

import android.content.Context
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import app.amber.agent.R
import app.amber.core.settings.findProvider
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.resolveTaskChatModel
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.utils.applyPlaceholders
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
            conversationAccess.updateConversation(conversationId, updatedConversation, checkDeletedFiles = false)
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

            conversationAccess.getConversationFlowOrNull(conversationId)?.let { flow ->
                conversationAccess.updateConversation(
                    conversationId,
                    flow.value.copy(chatSuggestions = emptyList()),
                )
            }

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

            val latest = conversationAccess.getConversationFlowOrNull(conversationId)?.value ?: conversation
            val updatedConversation = latest.copy(
                chatSuggestions = suggestions.take(10),
                updateAt = Instant.now(),
            )
            conversationAccess.updateConversation(conversationId, updatedConversation, checkDeletedFiles = false)
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
