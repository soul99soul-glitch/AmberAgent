package app.amber.core.model

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import app.amber.core.settings.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val autoApproveToolCalls: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()
        var anyNodeChanged = false

        messages.forEachIndexed { index, message ->
            val existingNode = newNodes.getOrNull(index)
            if (existingNode == null) {
                // Brand-new index — append a fresh node.
                newNodes.add(message.toMessageNode())
                anyNodeChanged = true
                return@forEachIndexed
            }

            val existingIdx = existingNode.messages.indexOfFirst { it.id == message.id }
            // Identity short-circuit: if the incoming UIMessage is the SAME
            // reference as the one already at the selected slot AND that
            // slot is the currently-selected branch, the node is unchanged
            // for every observable purpose. Skip `node.copy(...)` so the
            // outer reference stays stable across the 33ms streaming flush.
            //
            // Without this guard, ChatService.updateConversation re-emits
            // a new MessageNode instance for *every* historical node every
            // flush, defeating @Immutable MessageNode's reference-equality
            // skip in LazyColumn item { ChatMessage(node = node) } —
            // which is precisely the payoff M1.1 was meant to deliver.
            // (See M1.1 review report for the original analysis.)
            if (existingIdx >= 0
                && existingNode.messages[existingIdx] === message
                && existingNode.selectIndex == existingIdx
            ) {
                return@forEachIndexed
            }

            val newMessages = existingNode.messages.toMutableList()
            var newMessageIndex = existingNode.selectIndex
            if (existingIdx >= 0) {
                newMessages[existingIdx] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            newNodes[index] = existingNode.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )
            anyNodeChanged = true
        }

        // If nothing changed at the node-list level either, return `this`
        // so the outer Conversation reference is also preserved — gives
        // every snapshotFlow downstream a chance to short-circuit too.
        if (!anyNodeChanged && newNodes.size == this.messageNodes.size) {
            return this
        }
        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

// @Immutable: all four constructor properties are val + stable types
// (Uuid, immutable List of @Immutable UIMessage, Int, Boolean). Lets
// Compose skip recomposition of ChatMessage Composables when the
// LazyColumn item passes an unchanged historical node by reference —
// during streaming, only the trailing assistant MessageNode gets a
// new instance; earlier nodes' references stay the same flush after
// flush, and @Immutable converts that into a real recomposition skip.
@Immutable
@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
