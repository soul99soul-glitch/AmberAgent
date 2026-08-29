package app.amber.feature.chat.impl

import app.amber.core.agent.runtime.Agent
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentHandler
import app.amber.feature.chat.api.ChatEventPayload
import app.amber.feature.chat.api.ChatTurnArtifact
import app.amber.feature.chat.api.ChatTurnDescriptor
import app.amber.feature.chat.api.ChatTurnInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import app.amber.ai.ui.UIMessage
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.GenerationTerminal
import app.amber.core.ai.RunKernel
import app.amber.core.service.ConversationAccess
import java.time.Instant
import kotlin.uuid.Uuid
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.model.Conversation

class ChatTurnAgent(
    private val kernel: RunKernel,
    private val sessionResolver: ChatSessionResolver,
    private val conversationAccess: ConversationAccess,
) : Agent<ChatTurnInput, ChatTurnArtifact> {

    override val descriptor: AgentDescriptor = ChatTurnDescriptor.value

    override val handler = AgentHandler<ChatTurnInput, ChatTurnArtifact> { input, scope ->
        val runId = scope.runId.value

        var lastMessages: List<UIMessage> = emptyList()
        var failure: Throwable? = null
        var hooks: ChatRunHooks? = null
        val checkpointCoalescer = StreamCheckpointCoalescer()

        try {
            val session = sessionResolver.resolve(input, runId, events = scope.events)
            hooks = session.hooks

            scope.events.commit(
                ChatEventPayload.UserMessageAccepted(
                    messageNodeId = input.messageNodeId,
                    messageId = input.userMessageText.hashCode().toString(),
                )
            )

            // Durable path: arm the run (terminal store row + resume
            // bookkeeping) before the loop's first write-ahead ledger
            // prepare. Inside the try: a failing hook must still settle the
            // run via onRunFinished instead of leaking a RUNNING row.
            hooks?.onRunStarted?.invoke(runId)

            kernel.run(
                GenerationRunSession(
                    settings = session.settings,
                    model = session.model,
                    messages = session.messages,
                    inputTransformers = session.inputTransformers,
                    outputTransformers = session.outputTransformers,
                    memories = session.memories,
                    tools = session.tools,
                    maxSteps = session.maxSteps,
                    processingStatus = hooks?.processingStatus ?: MutableStateFlow(null),
                    autoApproveTools = session.autoApproveTools,
                    autoApproveHighRiskTools = session.autoApproveHighRiskTools,
                    autoApprovedToolNames = hooks?.autoApprovedToolNames ?: session.autoApprovedToolNames,
                    invocationContext = session.invocationContext,
                    conversation = session.conversation,
                    // Step 6: the run's sandbox policy — enforced at the
                    // dispatcher boundary on every execution, independent of
                    // approval. v1 chat turns stay permissive; narrowing
                    // arrives via sub-agent payloads.
                    executionPolicy = session.executionPolicy,
                    consumeSteerMessages = hooks?.consumeSteerMessages ?: { emptyList() },
                    // runId / onTerminal / responsesResume enter the generation
                    // loop only on the durable path — with the flags off the
                    // loop keeps the exact legacy non-durable behavior (the
                    // kernel's durablePath gate also requires them).
                    runId = runId.takeIf { hooks?.durable == true },
                    onTerminal = hooks?.takeIf { it.durable }?.let { h ->
                        { terminal -> h.onTerminal(runId, terminal) }
                    },
                    // Tool lifecycle events stay gated by the kernel's
                    // durable-path check; passing the writer unconditionally
                    // keeps the non-durable loop exactly as before.
                    toolLifecycleEvents = scope.events,
                    responsesResume = hooks?.takeIf { it.durable }?.responsesResumeFor?.invoke(runId),
                ),
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        lastMessages = chunk.messages
                        hooks?.onStreamingMessages?.invoke(runId, chunk.messages)
                        // Stream the latest message list to chat.db so UI updates
                        // in real time (mirrors the legacy path's behavior).
                        val conversationUuid = Uuid.parse(input.conversationId.value)
                        val current = conversationAccess.getConversationFlow(conversationUuid).value
                        val updated = mergeMessages(current, chunk.messages)
                        conversationAccess.updateConversation(
                            conversationUuid,
                            updated,
                            checkDeletedFiles = false,
                        )

                        val lastMsg = lastMessages.lastOrNull()
                        if (lastMsg != null) {
                            scope.events.emit(
                                ChatEventPayload.AssistantTextDelta(
                                    messageId = lastMsg.id.toString(),
                                    delta = "",
                                )
                            )
                        }

                        // Durable, coalesced (1s / 512 chars) stream checkpoint
                        // so a process death mid-stream is recoverable.
                        val tail = lastMessages.lastOrNull {
                            it.role == app.amber.ai.core.MessageRole.ASSISTANT
                        }
                        if (tail != null) {
                            val partsHash = tail.streamPartsHash()
                            val charCount = tail.streamContentLength()
                            val due = checkpointCoalescer.offer(
                                nowMs = System.currentTimeMillis(),
                                messageId = tail.id.toString(),
                                partsHash = partsHash,
                                charCount = charCount,
                            )
                            if (due) {
                                scope.events.commit(
                                    ChatEventPayload.StreamCheckpoint(
                                        conversationId = input.conversationId.value,
                                        messageId = tail.id.toString(),
                                        partsHash = partsHash,
                                        toolStates = tail.toolStateSnapshots(),
                                        charCount = charCount,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            failure = e
            scope.events.commitError(e, recoverable = false)
            throw e
        } finally {
            // Terminal persistence (pause/complete/fail + ledger reconcile)
            // is owned by the hooks; never let it break the runner's own
            // status transition.
            if (hooks != null) {
                runCatching { hooks.onRunFinished(runId, failure) }
            }
        }

        val assistantMsg = lastMessages.lastOrNull()
        val msgId = assistantMsg?.id?.toString() ?: "unknown"

        scope.events.commit(
            ChatEventPayload.AssistantMessageFinalized(
                messageNodeId = input.messageNodeId,
                messageId = msgId,
                inputTokens = assistantMsg?.usage?.promptTokens ?: 0,
                outputTokens = assistantMsg?.usage?.completionTokens ?: 0,
                regenerateOf = input.regenerateOf,
            )
        )

        ChatTurnArtifact(
            assistantMessageId = msgId,
            producedInNode = input.messageNodeId,
            inputTokens = assistantMsg?.usage?.promptTokens ?: 0,
            outputTokens = assistantMsg?.usage?.completionTokens ?: 0,
            toolCallsCount = lastMessages.sumOf { msg -> msg.parts.count { it is app.amber.ai.ui.UIMessagePart.Tool } },
        )
    }
}

/**
 * Merge generated UIMessages into the conversation. Existing nodes are
 * updated by matching message id; new messages are appended as new nodes.
 * Mirrors ChatService.mergeGeneratedMessagesIntoWindow's no-startIndex branch.
 */
private fun mergeMessages(
    conversation: Conversation,
    generatedMessages: List<UIMessage>,
): Conversation {
    if (generatedMessages.isEmpty()) return conversation
    val generatedById = generatedMessages.associateBy { it.id }
    var changed = false
    val updatedNodes = conversation.messageNodes.map { node ->
        val selected = node.currentMessage
        val replacement = generatedById[selected.id] ?: return@map node
        if (replacement === selected || replacement == selected) {
            node
        } else {
            changed = true
            node.copy(
                messages = node.messages.map { msg ->
                    if (msg.id == selected.id) replacement else msg
                },
            )
        }
    }
    val existingIds = updatedNodes.mapTo(mutableSetOf()) { it.currentMessage.id }
    val appendedNodes = generatedMessages
        .filterNot { it.id in existingIds }
        .map { msg ->
            changed = true
            app.amber.core.model.MessageNode(messages = listOf(msg))
        }
    return if (!changed) conversation
    else conversation.copy(
        messageNodes = updatedNodes + appendedNodes,
        updateAt = Instant.now(),
    )
}

interface ChatSessionResolver {
    /**
     * [events] is the run scope's event writer: the session's recipe context
     * uses it so nested recipe steps emit their tool lifecycle events into the
     * same run stream (Step 3). Null keeps nested steps ledger-only.
     */
    suspend fun resolve(
        input: ChatTurnInput,
        runId: String,
        events: app.amber.core.agent.runtime.AgentEventWriter? = null,
    ): ChatSession
}

/**
 * Durable-runtime hooks for a kernel-dispatched chat turn, wired by the
 * session resolver from ChatService's existing machinery. A null hooks
 * bundle keeps the bare loop (non-durable, no steer) — the same shape
 * SubAgent/DeepRead-style consumers use.
 *
 * All callbacks receive the kernel runId (= the runner-assigned id), so the
 * terminal store, the tool-effect ledger and the runtime event log all key
 * the same run.
 */
class ChatRunHooks(
    /**
     * True when the durable runtime flags are on for this turn: the runId,
     * the in-loop terminal signal and the responses-resume gate are
     * forwarded into the generation loop, and the hooks perform durable
     * writes. False keeps the generation loop bare (legacy semantics) while
     * the lifecycle orchestration (notifications, keep-alive, generation
     * task, title/suggestion/memory) still runs.
     */
    val durable: Boolean = false,
    /** Live processing-status text for the UI while the run is active. */
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    /** Tool names the user approved for the rest of this run ("don't ask again"). */
    val autoApprovedToolNames: Set<String> = emptySet(),
    /** Steer drain: queued mid-run user messages, consumed between steps. */
    val consumeSteerMessages: suspend () -> List<UIMessage> = { emptyList() },
    /** Arm the run before the loop's first write-ahead ledger prepare. */
    val onRunStarted: suspend (runId: String) -> Unit = {},
    /** In-loop terminal signal (approval pause / step limit) to persist. */
    val onTerminal: suspend (runId: String, terminal: GenerationTerminal) -> Unit = { _, _ -> },
    /** Streaming message updates, forwarded to the live-status notification. */
    val onStreamingMessages: suspend (runId: String, messages: List<UIMessage>) -> Unit = { _, _ -> },
    /**
     * Settle the run after the generation flow ends — terminal-store finish,
     * ledger reconcile, subagent cascade cancel, plus the per-turn
     * orchestration (notifications, keep-alive, generation task, and the
     * success side-effects: title / suggestions / memory extraction).
     * Implementations must do their durable writes under NonCancellable: on
     * cancellation this runs inside the cancelled handler coroutine.
     */
    val onRunFinished: suspend (runId: String, cause: Throwable?) -> Unit = { _, _ -> },
    /** Server-side stored-response resume gate for this run, if enabled. */
    val responsesResumeFor: suspend (runId: String) -> app.amber.ai.provider.ResponsesResumeRequest? = { null },
)

data class ChatSession(
    val settings: Settings,
    val model: app.amber.ai.provider.Model,
    val messages: List<UIMessage>,
    val inputTransformers: List<app.amber.core.ai.transformers.InputMessageTransformer>,
    val outputTransformers: List<app.amber.core.ai.transformers.OutputMessageTransformer>,
    val memories: List<app.amber.core.model.AssistantMemory>?,
    val tools: List<app.amber.ai.core.Tool>,
    val maxSteps: Int,
    val autoApproveTools: Boolean,
    val autoApproveHighRiskTools: Boolean,
    val autoApprovedToolNames: Set<String>,
    val invocationContext: app.amber.feature.runtime.ToolInvocationContext,
    val conversation: Conversation?,
    /** Step 6: the run's sandbox policy (permissive default — no new restriction). */
    val executionPolicy: app.amber.feature.runtime.ExecutionPolicy =
        app.amber.feature.runtime.ExecutionPolicy.permissive(),
    val hooks: ChatRunHooks? = null,
)
