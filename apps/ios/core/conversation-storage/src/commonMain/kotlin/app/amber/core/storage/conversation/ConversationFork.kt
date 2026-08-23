package app.amber.core.storage.conversation

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * P1-c: fork 纯函数（KMP 共用，iOS/Android 同语义）。
 *
 * - `forkTurns = "none"`：空 messageNodes（新会话只带系统上下文与初始信封）。
 * - `forkTurns = "all"`：完整复制 messageNodes。
 * - `forkTurns = "N"`（正整数）：保留**最近 N 个用户轮次**——一个 user 消息节点
 *   与其后连续的 assistant 节点序列算一轮。
 * - 变体折叠：每个 MessageNode 只保留 `selectIndex` 当前选中的消息（messages
 *   变单元素），不把未选中候选带给子线程。
 * - 截断点安全：截断后最后一个 assistant 消息里不得有 output 为空的 tool part
 *   （有则继续往前截到安全点）——子线程不能拿到半截未执行的工具调用。
 * - 新 Conversation：newId、createAt/updateAt=now、isPinned=false、assistantId
 *   继承 source（除非显式传入 [assistantId]——spawn 的 `role_assistant_id`）。
 */
fun forkConversation(
    source: Conversation,
    newId: Uuid,
    newTitle: String,
    forkTurns: String,
    assistantId: Uuid? = null,
): Conversation {
    val folded = source.messageNodes.mapNotNull { node ->
        foldedCurrentMessage(node)
    }
    val messages = when (forkTurns) {
        "none" -> emptyList()
        "all" -> folded
        else -> {
            val turns = forkTurns.toIntOrNull()
                ?: throw IllegalArgumentException("fork_turns 必须是 none/all/正整数，实际: $forkTurns")
            require(turns > 0) { "fork_turns 必须是正整数，实际: $forkTurns" }
            lastUserTurns(folded, turns)
        }
    }
    val now = Clock.System.now()
    return Conversation(
        id = newId,
        assistantId = assistantId ?: source.assistantId,
        title = newTitle,
        messageNodes = messages.map { MessageNode.of(it) },
        isPinned = false,
        createAt = now,
        updateAt = now,
    )
}

/** 变体折叠：取 node.selectIndex 当前选中的消息；越界/空节点防御性返回 null。 */
internal fun foldedCurrentMessage(node: MessageNode): UIMessage? {
    if (node.messages.isEmpty()) return null
    val index = node.selectIndex.takeIf { it in node.messages.indices } ?: 0
    return node.messages[index]
}

/**
 * 保留最近 [turns] 个用户轮次。一轮 = 一个 user 消息 + 其后连续 assistant
 * 消息序列（直到下一个 user 消息）。随后做截断点安全检查：结果末尾的
 * assistant 消息若含 output 为空的 tool part，继续往前截到安全点。
 */
internal fun lastUserTurns(
    messages: List<UIMessage>,
    turns: Int,
): List<UIMessage> {
    // 收集每个 user 消息的起点（含它本身）。
    val userStarts = messages.indices.filter { messages[it].role == MessageRole.USER }
    if (userStarts.isEmpty()) return emptyList()

    val keepStart = userStarts[(userStarts.size - turns).coerceAtLeast(0)]
    var truncated = messages.subList(keepStart, messages.size).toList()
    truncated = trimUnsafeTrailingToolMessages(truncated)
    return truncated
}

/**
 * 截断点安全：末尾若存在 output 为空的 tool part（半截未执行的工具调用），
 * 整条 assistant 消息移除，继续往前直到最后一条 assistant 消息安全为止。
 * user 消息没有 tool part，天然是安全终点。
 */
internal fun trimUnsafeTrailingToolMessages(messages: List<UIMessage>): List<UIMessage> {
    var result = messages
    while (result.isNotEmpty()) {
        val last = result.last()
        val unsafe = last.role == MessageRole.ASSISTANT && last.parts.any { part ->
            part is UIMessagePart.Tool && part.output.isEmpty()
        }
        if (!unsafe) break
        result = result.dropLast(1)
    }
    return result
}
