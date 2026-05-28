package app.amber.feature.chat.impl

import app.amber.feature.chat.api.ChatTurnInput
import kotlinx.coroutines.flow.first
import app.amber.feature.runtime.ToolInvocationContext
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.MiniAppOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.MiniAppPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.SearchImageInjectorTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import app.amber.core.settings.getCurrentAssistant
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

class ChatSessionResolverImpl(
    private val settingsStore: SettingsAggregator,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val templateTransformer: TemplateTransformer,
    private val chatService: ChatService,
) : ChatSessionResolver {

    override fun resolve(input: ChatTurnInput): ChatSession {
        val conversationId = Uuid.parse(input.conversationId.value)
        val settings = settingsStore.settingsFlow.value
        val model = settings.getCurrentChatModel()
            ?: throw IllegalStateException("No chat model configured")
        val assistant = settings.getCurrentAssistant()
        val conversation = chatService.getConversationFlow(conversationId).value

        val inputTransformers = listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            MiniAppPromptTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
            OcrTransformer,
        )
        val outputTransformers = listOf(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            SearchImageInjectorTransformer,
            MiniAppOutputTransformer,
            RegexOutputTransformer,
        )

        val memories = if (settings.agentRuntime.enableCoreMemory) {
            runCatching { kotlinx.coroutines.runBlocking { memoryRepository.getGlobalMemories() } }.getOrNull()
        } else {
            emptyList()
        }

        val tools = chatService.createDebugRunTools(settings)

        return ChatSession(
            settings = settings,
            model = model,
            messages = conversation.currentMessages,
            inputTransformers = buildList {
                addAll(inputTransformers)
                add(templateTransformer)
            },
            outputTransformers = outputTransformers,
            assistant = assistant,
            memories = memories,
            tools = tools,
            autoApproveTools = settings.agentRuntime.autoApproveAllToolCalls ||
                conversation.autoApproveToolCalls,
            autoApproveHighRiskTools = settings.agentRuntime.autoApproveHighRiskToolCalls,
            autoApprovedToolNames = emptySet(),
            invocationContext = ToolInvocationContext.Normal,
            conversation = conversation,
        )
    }
}
