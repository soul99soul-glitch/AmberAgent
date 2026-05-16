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
 *    contain actionable keywords (待办/TODO/提醒/deadline/会议/方案/跟进/bug
 *    etc.) pass through. Conversations with only trivial content or explicit
 *    generation/rendering tests are dropped before they enter the signal stream.
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
        val score = ChatHistoryBoardSignalHeuristics.relevanceScore(
            title = title,
            tailTexts = tailTexts,
            nodeCount = nodeCount,
        ) ?: return null

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
    }
}

internal object ChatHistoryBoardSignalHeuristics {
    fun relevanceScore(title: String, tailTexts: List<String>, nodeCount: Int): Int? {
        val fullText = "$title ${tailTexts.joinToString(" ")}"
        if (looksLikeLowValueTestConversation(fullText)) return null

        var score = 0
        for ((keywords, weight) in KEYWORD_TIERS) {
            for (keyword in keywords) {
                if (containsKeyword(fullText, keyword)) {
                    score += weight
                    break
                }
            }
        }
        if (score < MIN_KEYWORD_SCORE) return null

        when {
            nodeCount >= 10 -> score += 3
            nodeCount >= 5 -> score += 2
            nodeCount >= 3 -> score += 1
            nodeCount <= 1 && score < STRONG_KEYWORD_THRESHOLD -> return null
        }

        return score.takeIf { it > 0 }
    }

    fun looksLikeLowValueTestConversation(text: String): Boolean {
        return LOW_VALUE_TEST_MARKERS.any { marker ->
            text.contains(marker, ignoreCase = true)
        }
    }

    private fun containsKeyword(text: String, keyword: String): Boolean {
        if (!keyword.isAsciiWord()) return text.contains(keyword, ignoreCase = true)
        val pattern = Regex(
            pattern = "(?<![A-Za-z0-9_])${Regex.escape(keyword)}(?![A-Za-z0-9_])",
            option = RegexOption.IGNORE_CASE,
        )
        return pattern.containsMatchIn(text)
    }

    private fun String.isAsciiWord(): Boolean =
        isNotEmpty() && all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }

    private const val MIN_KEYWORD_SCORE = 3
    private const val STRONG_KEYWORD_THRESHOLD = 5

    private val LOW_VALUE_TEST_MARKERS = listOf(
        "乱码",
        "混合语言",
        "长文生成",
        "长文对话",
        "重复 prompt",
        "重复prompt",
        "测试 prompt",
        "测试prompt",
        "随便测试",
        "流式渲染长文",
    )

    private val KEYWORD_TIERS: List<Pair<List<String>, Int>> = listOf(
        // Explicit action / deadline signals.
        listOf(
            "TODO", "todo", "待办", "提醒", "deadline", "截止", "到期",
            "紧急", "urgent", "ASAP", "尽快",
            "follow up", "action item", "跟进", "决定", "决策",
            "bug", "修复", "上线", "发布", "部署",
        ) to 5,
        // Planning, meeting, or broader work-progress signals.
        listOf(
            "计划", "方案", "会议", "面试", "必须",
            "接下来", "下一步",
        ) to 4,
        // Project/work context; useful only with depth or stronger terms.
        listOf(
            "项目", "需求", "review", "PR", "合并", "反馈",
            "报告", "文档", "设计",
        ) to 2,
        // High-value business context, kept weak so it cannot pass alone easily.
        listOf(
            "预算", "合同", "客户",
        ) to 1,
    )
}
