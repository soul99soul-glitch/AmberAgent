package me.rerere.rikkahub.data.agent.board.collector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.agent.board.BoardSignalSourceType
import me.rerere.rikkahub.data.repository.ConversationRepository

/**
 * Surfaces recent chat sessions as signals so the Board agent can incorporate "you were
 * talking about X yesterday" context. Specifically useful for thread continuity:
 * conversations with TODO-like content that haven't been actioned yet.
 *
 * Implementation: pulls the N most recent conversations (across all assistants) updated
 * within the last 36 hours. We project each conversation's title + last message snippet as
 * one signal per conversation — fine-grained per-message ingestion would balloon the signal
 * table for little extra value at the agent stage.
 *
 * Dedup uses `conversation.id` as sourceRef so the same conversation only produces one
 * signal regardless of how many times the scheduler runs. ContentHash further guards
 * against identical re-emissions. When the conversation's content changes, both sourceRef
 * and contentHash dedup are bypassed, producing a fresh signal.
 *
 * The 36-hour window is wider than 24 because we want to catch yesterday's late-evening
 * conversations even when a morning trigger fires before they roll off.
 */
class ChatHistorySignalCollector(
    private val conversationRepository: ConversationRepository,
    private val freshnessWindowMs: Long = 36L * 60L * 60L * 1000L,
) : BoardSignalCollector {
    override val sourceType: String = BoardSignalSourceType.CHAT_HISTORY

    override suspend fun collect(limit: Int): List<RawBoardSignal> = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - freshnessWindowMs
        val conversations = runCatching { conversationRepository.getRecentConversations(limit) }
            .getOrElse { return@withContext emptyList() }

        conversations
            .asSequence()
            .filter { it.updateAt.toEpochMilli() >= cutoff }
            .map { conversation ->
                val title = conversation.title.ifBlank { "未命名对话" }
                val tail = conversation.messageNodes
                    .lastOrNull()
                    ?.messages
                    ?.lastOrNull()
                    ?.toText()
                    .orEmpty()
                    .trim()
                val content = buildString {
                    append("最近一次更新：")
                    append(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(conversation.updateAt))
                }
                RawBoardSignal(
                    sourceType = BoardSignalSourceType.CHAT_HISTORY,
                    sourceRef = conversation.id.toString(),
                    title = title.take(120),
                    content = content,
                    signalTime = conversation.updateAt.toEpochMilli(),
                    metadataJson = """{"conversation_id":"${conversation.id}"}""",
                )
            }
            .take(limit)
            .toList()
    }
}
