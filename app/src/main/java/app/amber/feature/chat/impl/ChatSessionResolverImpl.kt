package app.amber.feature.chat.impl

import app.amber.feature.chat.api.ChatTurnInput
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
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.repository.MemoryRepository
import app.amber.core.service.ChatService
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

class ChatSessionResolverImpl(
    private val settingsStore: SettingsAggregator,
    private val memoryRepository: MemoryRepository,
    private val templateTransformer: TemplateTransformer,
    private val chatService: ChatService,
) : ChatSessionResolver {

    override suspend fun resolve(
        input: ChatTurnInput,
        runId: String,
        events: app.amber.core.agent.runtime.AgentEventWriter?,
    ): ChatSession {
        val conversationId = Uuid.parse(input.conversationId.value)
        val settings = settingsStore.settingsFlow.value
        val model = settings.getCurrentChatModel()
            ?: throw IllegalStateException("No chat model configured")
        // Same source as the legacy loop: the full (window-merged) conversation
        // so long histories are not truncated to the loaded window.
        val conversation = chatService.conversationForGeneration(conversationId)
        val hooks = chatService.chatRunHooks(conversationId)

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

        // Per-run tool surface: identical gates to the legacy loop (capability
        // audit, recipe runtime, thread graph, JS cell), keyed by this runId.
        // Step 6: the run's sandbox policy is created here and shared by the
        // session (kernel -> dispatcher) and the recipe context (nested steps)
        // so both enforce exactly the same boundary.
        // v1: default codifies no new restriction; narrowing arrives via
        // sub-agent payloads.
        val executionPolicy = app.amber.feature.runtime.ExecutionPolicy.permissive()
        val tools = chatService.createKernelRunTools(
            settings = settings,
            conversationId = conversationId,
            runId = runId,
            conversation = conversation,
            durablePath = hooks.durable,
            events = events,
            executionPolicy = executionPolicy,
        )

        // Legacy messageRange parity (variant regenerate): generate from a
        // window of the conversation instead of the whole message list.
        val messages = conversation.currentMessages.let { all ->
            val start = input.messageRangeStart
            val end = input.messageRangeEndExclusive
            if (start != null && end != null) {
                val clampedEnd = end.coerceAtMost(all.size)
                all.subList(start.coerceAtMost(clampedEnd), clampedEnd)
            } else {
                all
            }
        }

        return ChatSession(
            settings = settings,
            model = model,
            messages = messages,
            inputTransformers = buildList {
                addAll(inputTransformers)
                add(templateTransformer)
            },
            outputTransformers = outputTransformers,
            memories = memories,
            tools = tools,
            maxSteps = settings.agentRuntime.maxToolLoopSteps.coerceIn(
                app.amber.core.settings.MIN_AGENT_TOOL_LOOP_STEPS,
                app.amber.core.settings.MAX_AGENT_TOOL_LOOP_STEPS,
            ),
            autoApproveTools = settings.agentRuntime.autoApproveAllToolCalls ||
                conversation.autoApproveToolCalls,
            autoApproveHighRiskTools = settings.agentRuntime.autoApproveHighRiskToolCalls,
            autoApprovedToolNames = emptySet(),
            invocationContext = ToolInvocationContext.Normal,
            conversation = conversation,
            executionPolicy = executionPolicy,
            // Turn hooks (terminal store, ledger reconcile, steer, responses
            // resume, and the notification / keep-alive / generation-task
            // orchestration) — always present on the chat path; the durable
            // parts self-gate on the runtime flags.
            hooks = hooks,
        )
    }
}
