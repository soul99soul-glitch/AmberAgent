package app.amber.feature.chat.impl

import app.amber.core.agent.runtime.Agent
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentHandler
import app.amber.feature.chat.api.ChatEventPayload
import app.amber.feature.chat.api.ChatTurnArtifact
import app.amber.feature.chat.api.ChatTurnDescriptor
import app.amber.feature.chat.api.ChatTurnInput
import kotlinx.coroutines.flow.last
import app.amber.ai.ui.UIMessage
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationHandler
import app.amber.core.service.ConversationAccess
import java.time.Instant
import kotlin.uuid.Uuid
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.model.Assistant
import app.amber.core.model.Conversation

class ChatTurnAgent(
    private val generationHandler: GenerationHandler,
    private val sessionResolver: ChatSessionResolver,
    private val conversationAccess: ConversationAccess,
) : Agent<ChatTurnInput, ChatTurnArtifact> {

    override val descriptor: AgentDescriptor = ChatTurnDescriptor.value

    override val handler = AgentHandler<ChatTurnInput, ChatTurnArtifact> { input, scope ->
        val session = sessionResolver.resolve(input)

        scope.events.commit(
            ChatEventPayload.UserMessageAccepted(
                messageNodeId = input.messageNodeId,
                messageId = input.userMessageText.hashCode().toString(),
            )
        )

        var lastMessages: List<UIMessage> = emptyList()
        var toolCallCount = 0

        try {
            generationHandler.generateText(
                settings = session.settings,
                model = session.model,
                messages = session.messages,
                inputTransformers = session.inputTransformers,
                outputTransformers = session.outputTransformers,
                assistant = session.assistant,
                memories = session.memories,
                tools = session.tools,
                maxSteps = input.maxToolIterations,
                autoApproveTools = session.autoApproveTools,
                autoApproveHighRiskTools = session.autoApproveHighRiskTools,
                autoApprovedToolNames = session.autoApprovedToolNames,
                invocationContext = session.invocationContext,
                conversation = session.conversation,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        lastMessages = chunk.messages
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
                    }
                }
            }
        } catch (e: Exception) {
            scope.events.commitError(e, recoverable = false)
            throw e
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
            toolCallsCount = toolCallCount,
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
    fun resolve(input: ChatTurnInput): ChatSession
}

data class ChatSession(
    val settings: Settings,
    val model: app.amber.ai.provider.Model,
    val messages: List<UIMessage>,
    val inputTransformers: List<app.amber.core.ai.transformers.InputMessageTransformer>,
    val outputTransformers: List<app.amber.core.ai.transformers.OutputMessageTransformer>,
    val assistant: Assistant,
    val memories: List<app.amber.core.model.AssistantMemory>?,
    val tools: List<app.amber.ai.core.Tool>,
    val autoApproveTools: Boolean,
    val autoApproveHighRiskTools: Boolean,
    val autoApprovedToolNames: Set<String>,
    val invocationContext: app.amber.feature.runtime.ToolInvocationContext,
    val conversation: Conversation?,
)
