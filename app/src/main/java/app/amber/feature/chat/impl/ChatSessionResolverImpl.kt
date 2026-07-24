package app.amber.feature.chat.impl

import app.amber.feature.chat.api.ChatTurnInput
import kotlinx.coroutines.flow.first
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.core.ai.transformers.Base64ImageToLocalFileTransformer
import app.amber.core.ai.transformers.DocumentAsPromptTransformer
import app.amber.core.ai.transformers.MiniAppOutputTransformer
import app.amber.core.ai.transformers.MiniAppPromptTransformer
import app.amber.core.ai.transformers.OcrTransformer
import app.amber.core.ai.transformers.PlaceholderTransformer
import app.amber.core.ai.transformers.PromptInjectionTransformer
import app.amber.core.ai.transformers.RegexOutputTransformer
import app.amber.core.ai.transformers.TemplateTransformer
import app.amber.core.ai.transformers.ThinkTagTransformer
import app.amber.core.ai.transformers.TimeReminderTransformer
import app.amber.core.settings.getCurrentAssistant
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.MemoryRepository
import app.amber.core.service.ChatService
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

class ChatSessionResolverImpl(
    private val settingsStore: SettingsAggregator,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val templateTransformer: TemplateTransformer,
    private val chatService: ChatService,
) : ChatSessionResolver {

    override suspend fun resolve(input: ChatTurnInput): ChatSession {
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
            MiniAppOutputTransformer,
            RegexOutputTransformer,
        )

        val memories = if (settings.agentRuntime.enableCoreMemory) {
            // resolve 已是挂起函数：直接挂起读取，不再 runBlocking 阻塞 runner 线程
            runCatching { memoryRepository.getGlobalMemories() }.getOrElse {
                if (it is CancellationException) throw it
                emptyList()
            }
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
