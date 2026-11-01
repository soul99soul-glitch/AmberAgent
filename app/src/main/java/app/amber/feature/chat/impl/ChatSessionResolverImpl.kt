package app.amber.feature.chat.impl

import android.content.Context
import android.util.Log
import app.amber.agent.R
import app.amber.agent.BuildConfig
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
import app.amber.core.memory.store.MemoryRepository
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.canResumeToolExecution
import app.amber.core.model.Conversation
import app.amber.core.service.ChatRunCoordinator
import app.amber.core.service.ChatRunHooksFactory
import app.amber.core.service.ChatErrorStore
import app.amber.core.service.ConversationStore
import app.amber.core.service.LocalAsyncTaskCoordinator
import app.amber.core.service.PendingMessageQueue
import app.amber.core.service.PendingUserMessageDisplayCopy
import app.amber.core.service.tools.RunToolCatalogFactory
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

class ChatSessionResolverImpl(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val memoryRepository: MemoryRepository,
    private val templateTransformer: TemplateTransformer,
    private val runCoordinator: ChatRunCoordinator,
    private val hooksFactory: ChatRunHooksFactory,
    private val conversationStore: ConversationStore,
    private val pendingMessageQueue: PendingMessageQueue,
    private val errorStore: ChatErrorStore,
    private val toolCatalogFactory: RunToolCatalogFactory,
    private val localAsyncTaskCoordinator: LocalAsyncTaskCoordinator? = null,
) : ChatSessionResolver {

    override suspend fun resolve(
        input: ChatTurnInput,
        runId: String,
        events: app.amber.core.agent.runtime.AgentEventWriter?,
    ): ChatSession {
        val conversationId = Uuid.parse(input.conversationId.value)
        val anchorNodeId = runCatching { Uuid.parse(input.messageNodeId.value) }.getOrNull()
        val managedTask = localAsyncTaskCoordinator?.bindRunToAnchorNode(anchorNodeId, runId)
        val hasConcurrentTasks = localAsyncTaskCoordinator?.hasActiveOrQueuedTasks(
            conversationId = conversationId,
            excludingRunId = runId,
        ) == true
        val settings = settingsStore.settingsFlow.value
        val model = settings.getCurrentChatModel()
            ?: throw IllegalStateException("No chat model configured")
        val perfEnabled = BuildConfig.DEBUG && runCatching { Log.isLoggable("AmberChatPerf", Log.DEBUG) }.getOrDefault(false)
        // Same source as the legacy loop: the full (window-merged) conversation
        // so long histories are not truncated to the loaded window.
        val loadStartedAt = if (perfEnabled) System.nanoTime() else 0L
        val conversation = try {
            conversationStore.conversationForGeneration(
                conversationId = conversationId,
                preserveActiveTurns = hasConcurrentTasks,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorStore.addError(error, conversationId, title = context.getString(R.string.error_title_send_message))
            throw error
        }
        if (perfEnabled) {
            Log.d("AmberChatPerf", "resolve.conversation nodes=${conversation.messageNodes.size} elapsedMs=${(System.nanoTime() - loadStartedAt) / 1_000_000.0}")
        }
        val hooks = hooksFactory.create(
            conversationId = conversationId,
            cancelGeneration = { convId, cancelRunId ->
                if (managedTask != null && localAsyncTaskCoordinator != null) {
                    localAsyncTaskCoordinator.stopTask(convId, cancelRunId ?: runId)
                } else {
                    runCoordinator.stopGeneration(convId, cancelRunId)
                }
            },
            runIdHint = runId,
            anchorNodeId = anchorNodeId,
        )

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
        val toolsStartedAt = if (perfEnabled) System.nanoTime() else 0L
        val tools = toolCatalogFactory.createKernelRunTools(
            settings = settings,
            conversationId = conversationId,
            runId = runId,
            conversation = conversation,
            durablePath = hooks.durable,
            conversationProvider = { conversationStore.getConversationFlow(conversationId).value },
            conversationQueueTools = pendingMessageQueue.createTools(conversationId, PendingUserMessageDisplayCopy.from(context)),
            autoApprovedToolNames = hooks.autoApprovedToolNames,
            events = events,
            executionPolicy = executionPolicy,
        )
        if (perfEnabled) {
            Log.d("AmberChatPerf", "resolve.tools count=${tools.size} elapsedMs=${(System.nanoTime() - toolsStartedAt) / 1_000_000.0}")
        }

        // Legacy messageRange parity (variant regenerate): generate from a
        // window of the conversation instead of the whole message list.
        val messages = conversation.currentMessages.let { all ->
            val start = input.messageRangeStart
            val end = input.messageRangeEndExclusive
            if (start != null && end != null) {
                val clampedEnd = end.coerceAtMost(all.size)
                all.subList(start.coerceAtMost(clampedEnd), clampedEnd)
            } else if (managedTask != null && anchorNodeId != null) {
                resolveMessagesForAnchoredTask(conversation, anchorNodeId)
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

    private fun resolveMessagesForAnchoredTask(
        conversation: Conversation,
        anchorNodeId: Uuid,
    ): List<UIMessage> {
        val nodes = conversation.messageNodes
        val anchorIndex = nodes.indexOfFirst { it.id == anchorNodeId }
        if (anchorIndex < 0) return conversation.currentMessages

        var endInclusive = anchorIndex
        for (idx in (anchorIndex + 1) until nodes.size) {
            if (nodes[idx].currentMessage.role == MessageRole.ASSISTANT) {
                endInclusive = idx
            } else {
                break
            }
        }

        val result = ArrayList<UIMessage>(endInclusive + 1)
        for (idx in 0..endInclusive) {
            val msg = nodes[idx].currentMessage
            if (idx < anchorIndex && msg.role == MessageRole.ASSISTANT) {
                // Sanitize in-flight unexecuted tool parts from earlier concurrent tasks
                // so this task's prompt remains valid without mutating ConversationStore.
                val validParts = msg.parts.filter { part ->
                    part !is UIMessagePart.Tool ||
                        part.isExecuted ||
                        part.isPending ||
                        part.approvalState.canResumeToolExecution()
                }
                if (validParts.isNotEmpty()) {
                    result += if (validParts.size == msg.parts.size) msg else msg.copy(parts = validParts)
                }
            } else {
                result += msg
            }
        }
        return result
    }
}
