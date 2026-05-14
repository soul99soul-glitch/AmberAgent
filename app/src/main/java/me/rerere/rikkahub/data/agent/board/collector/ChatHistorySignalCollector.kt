package me.rerere.rikkahub.data.agent.board.collector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.agent.board.BoardSignalSourceType
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/**
 * Surfaces recent chat sessions as signals — but only those that look like they
 * contain actionable context (TODOs, decisions, follow-ups, planning, deadlines).
 *
 * Two-stage filtering:
 *
 * 1. **Keyword pre-filter**: conversations whose title or last few messages
 *    contain actionable keywords (待办/TODO/提醒/deadline/会议/方案/需要/计划
 *    etc.) pass through. Conversations with only trivial content (e.g. "帮我
 *    翻译这句话") are dropped before they enter the signal stream.
 *
 * 2. **Content extraction**: instead of just emitting the title + timestamp,
 *    we extract the last [TAIL_NODE_COUNT] message turns so the Board agent
 *    has enough context to judge importance. Single-message throwaway
 *    conversations are deprioritized (only pass if they match a strong keyword).
 *
 * This replaces the old "emit everything updated within 36h" approach which
 * flooded the signal stream with noise and left the LLM doing all the filtering.
 */
class ChatHistorySignalCollector(
    private val conversationRepository: ConversationRepository,
    private val freshnessWindowMs: Long = 36L * 60L * 60L * 1000L,
) : BoardSignalCollector {
    override val sourceType: String = BoardSignalSourceType.CHAT_HISTORY

    override suspend fun collect(limit: Int): List<RawBoardSignal> = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - freshnessWindowMs
        // Fetch more candidates than limit because we'll filter most out.
        val candidates = runCatching {
            conversationRepository.getRecentConversations(limit * 3)
        }.getOrElse { return@withContext emptyList() }

        candidates
            .filter { it.updateAt.toEpochMilli() >= cutoff }
            .mapNotNull { conversation -> scoreAndExtract(conversation) }
            .sortedByDescending { it.relevanceScore }
            .take(limit)
            .map { it.signal }
    }

    /**
     * Evaluate a conversation for board-worthiness. Returns null if the
     * conversation has no actionable content.
     */
    private suspend fun scoreAndExtract(conversation: Conversation): ScoredCandidate? {
        val title = conversation.title.ifBlank { "未命名对话" }
        val nodeCount = conversation.messageNodes.size

        // Extract last N message turns for content analysis
        val tailNodes = try {
            val window = conversationRepository.getConversationTailById(
                conversation.id, TAIL_NODE_COUNT
            )
            window?.conversation?.messageNodes ?: conversation.messageNodes.takeLast(TAIL_NODE_COUNT)
        } catch (_: Exception) {
            conversation.messageNodes.takeLast(TAIL_NODE_COUNT)
        }

        val tailTexts = tailNodes.flatMap { node ->
            node.messages.map { msg ->
                val role = msg.role.name.lowercase()
                val text = msg.toText().trim().take(500)
                "$role: $text"
            }
        }
        val fullText = "$title ${tailTexts.joinToString(" ")}"

        // Keyword relevance scoring
        var score = 0
        for ((keywords, weight) in KEYWORD_TIERS) {
            for (kw in keywords) {
                if (fullText.contains(kw, ignoreCase = true)) {
                    score += weight
                    break // one match per tier is enough
                }
            }
        }

        // Depth bonus: multi-turn conversations are more likely to be substantive
        when {
            nodeCount >= 10 -> score += 3
            nodeCount >= 5 -> score += 2
            nodeCount >= 3 -> score += 1
            // Single-message conversations need a strong keyword match to pass
            nodeCount <= 1 && score < STRONG_KEYWORD_THRESHOLD -> return null
        }

        // Hard filter: no keywords matched and conversation is short
        if (score <= 0) return null

        // Build rich content for the signal
        val content = buildString {
            appendLine("对话深度：${nodeCount}轮")
            appendLine("最近更新：${formatTime(conversation.updateAt.toEpochMilli())}")
            appendLine()
            appendLine("--- 最近对话内容 ---")
            for (text in tailTexts.takeLast(MAX_CONTENT_TURNS)) {
                appendLine(text.take(300))
            }
        }.take(2000)

        val signal = RawBoardSignal(
            sourceType = BoardSignalSourceType.CHAT_HISTORY,
            sourceRef = conversation.id.toString(),
            title = title.take(120),
            content = content,
            signalTime = conversation.updateAt.toEpochMilli(),
            metadataJson = """{"conversation_id":"${conversation.id}","node_count":$nodeCount,"relevance":$score}""",
        )
        return ScoredCandidate(signal, score)
    }

    private fun formatTime(ms: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(ms))

    private data class ScoredCandidate(val signal: RawBoardSignal, val relevanceScore: Int)

    companion object {
        /** How many tail message nodes to load for content extraction. */
        private const val TAIL_NODE_COUNT = 6

        /** Max message turns to include in signal content. */
        private const val MAX_CONTENT_TURNS = 8

        /** Minimum score for single-message conversations to pass. */
        private const val STRONG_KEYWORD_THRESHOLD = 5

        /**
         * Keyword tiers: higher-weight keywords indicate stronger actionability.
         * Each tier is checked independently; one match per tier contributes its weight.
         */
        private val KEYWORD_TIERS: List<Pair<List<String>, Int>> = listOf(
            // Tier 1 (weight 5): explicit action / deadline signals
            listOf(
                "TODO", "todo", "待办", "提醒", "deadline", "截止", "到期",
                "紧急", "urgent", "ASAP", "尽快",
            ) to 5,
            // Tier 2 (weight 3): planning / decision / follow-up context
            listOf(
                "计划", "方案", "决定", "会议", "面试", "准备",
                "需要", "必须", "应该", "接下来", "下一步",
                "follow up", "action item", "跟进",
            ) to 3,
            // Tier 3 (weight 2): project / work context
            listOf(
                "项目", "需求", "bug", "修复", "上线", "发布", "部署",
                "review", "PR", "合并", "测试", "反馈",
                "报告", "文档", "设计",
            ) to 2,
            // Tier 4 (weight 1): general substantive topics
            listOf(
                "问题", "分析", "研究", "学习", "总结",
                "预算", "合同", "客户", "用户",
            ) to 1,
        )
    }
}
