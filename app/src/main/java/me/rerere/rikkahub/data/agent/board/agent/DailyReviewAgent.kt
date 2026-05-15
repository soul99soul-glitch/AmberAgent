package me.rerere.rikkahub.data.agent.board.agent

import android.util.Log
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.agent.board.BoardRepository
import me.rerere.rikkahub.data.agent.board.collector.AppUsageCollector
import me.rerere.rikkahub.data.agent.board.collector.AppUsageEntry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import me.rerere.rikkahub.data.db.entity.BoardItemEntity
import me.rerere.rikkahub.data.db.entity.DailyReviewEntity
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/**
 * Generates a daily review ("今日回顾") — a diary-like Markdown summary of what
 * the user did today: app usage, completed board items, chat topics, and work
 * outcomes.
 *
 * Two phases:
 * - **noon** (13:00): first half-day snapshot. Generates fresh content.
 * - **evening** (19:00): appends an afternoon section below the noon content.
 *   Does NOT replace the noon output.
 *
 * The generated Markdown is persisted in [DailyReviewEntity]. The UI renders it
 * directly in the "今日回顾" tab.
 */
class DailyReviewAgent(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val boardRepository: BoardRepository,
    private val conversationRepository: ConversationRepository,
    private val appUsageCollector: AppUsageCollector,
) {
    suspend fun run(boardDate: String, phase: String): DailyReviewRunResult {
        val settings = settingsStore.settingsFlow.value
        val now = System.currentTimeMillis()

        // 1. Collect data sources
        val appUsage = appUsageCollector.collectToday(now)
        val completedItems = boardRepository.getCompletedItems(boardDate)
        val recentChats = collectRecentChatSummaries()

        if (appUsage.isEmpty() && completedItems.isEmpty() && recentChats.isEmpty()) {
            return DailyReviewRunResult.Empty
        }

        // 2. Build prompt
        val existing = boardRepository.getDailyReview(boardDate)
        val prompt = buildPrompt(phase, appUsage, completedItems, recentChats, existing?.content)

        // 3. Call LLM
        val rawMarkdown = callModel(settings, prompt)
            ?: return DailyReviewRunResult.Failed("model call failed")

        // 4. Compose final content
        val finalContent = if (phase == PHASE_EVENING && existing != null) {
            // Append evening section below noon content
            "${existing.content}\n\n$rawMarkdown"
        } else {
            // Noon phase, or evening with no prior noon (noon failed/skipped) — standalone
            rawMarkdown
        }

        // 5. Persist — use boardDate as deterministic id so noon and evening
        // always upsert the same row. This avoids PK vs unique-index conflicts
        // when noon fails and evening creates a new entity.
        val entity = DailyReviewEntity(
            id = "review:$boardDate",
            boardDate = boardDate,
            content = finalContent,
            phase = phase,
            generatedAt = existing?.generatedAt ?: now,
            updatedAt = now,
        )
        boardRepository.saveDailyReview(entity)

        return DailyReviewRunResult.Success(phase)
    }

    private fun buildPrompt(
        phase: String,
        appUsage: List<AppUsageEntry>,
        completedItems: List<BoardItemEntity>,
        recentChats: List<String>,
        existingContent: String?,
    ): String = buildString {
        appendLine("你是 AmberAgent 的「今日回顾」助理。根据下面的数据生成一份**中文 Markdown** 格式的日报。")
        appendLine()
        appendLine("## 输出要求")
        appendLine("- 直接输出 Markdown，不要代码围栏、不要 JSON。")
        appendLine("- 语气：简洁、务实、像给自己写的日记，不要客套话。")
        appendLine("- 用具体数字和事实，不要空洞总结。")
        appendLine("- 如果某个数据源为空，跳过该部分，不要写「无数据」。")
        appendLine()

        if (phase == PHASE_NOON) {
            appendLine("## 当前阶段：上午回顾（13:00）")
            appendLine("覆盖今天从早上到现在的活动。生成以下部分（按需）：")
            appendLine("1. **📱 应用使用** — 今天用了哪些 app，各用了多久，结合 app 用途推测在做什么")
            appendLine("2. **✅ 已完成事项** — 从看板完成的待办")
            appendLine("3. **💬 对话摘要** — 今天跟 AI 聊了什么重要话题")
            appendLine("4. **📋 上午小结** — 一两句话总结上午状态")
        } else {
            appendLine("## 当前阶段：下午/晚间补充（19:00）")
            if (existingContent != null) {
                appendLine("在已有的上午回顾基础上，**只生成下午新增部分**，格式同上但标题用「下午」。")
            } else {
                appendLine("上午回顾未生成，请生成完整的今日回顾（覆盖全天）。")
            }
            appendLine("不要重复上午的内容。")
            if (existingContent != null) {
                appendLine()
                appendLine("## 已有的上午回顾内容（参考，不要重复）")
                appendLine(existingContent.take(2000))
            }
        }

        appendLine()
        if (appUsage.isEmpty()) {
            appendLine("## 注意：应用使用数据不可用")
            appendLine("可能原因：未授予「使用情况访问」权限。跳过应用使用部分即可。")
            appendLine()
        }
        if (appUsage.isNotEmpty()) {
            appendLine("## 数据：应用使用")
            for (entry in appUsage) {
                appendLine("- ${entry.appLabel}（${entry.packageName}）：前台 ${entry.formattedDuration()}")
            }
            appendLine()
        }

        if (completedItems.isNotEmpty()) {
            appendLine("## 数据：今日已完成的看板事项")
            for (item in completedItems) {
                appendLine("- ${item.title}（来源：${item.sourceType}）")
            }
            appendLine()
        }

        if (recentChats.isNotEmpty()) {
            appendLine("## 数据：今日对话主题")
            for (chat in recentChats) {
                appendLine("- $chat")
            }
            appendLine()
        }
    }

    private suspend fun collectRecentChatSummaries(): List<String> = runCatching {
        val todayStart = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        // Use getRecentConversations but only read title + updateAt to avoid
        // loading full message node trees. The node count is a bonus — we'll
        // read it via the lightweight countConversationNodes() per conversation.
        conversationRepository.getRecentConversations(20)
            .filter { it.updateAt.toEpochMilli() >= todayStart }
            .filter { it.title.isNotBlank() }
            .take(10)
            .map { conv ->
                val nodeCount = runCatching {
                    conversationRepository.countConversationNodes(conv.id)
                }.getOrDefault(conv.messageNodes.size)
                "${conv.title}（${nodeCount}轮对话）"
            }
    }.getOrElse { emptyList() }

    private suspend fun callModel(settings: Settings, prompt: String): String? {
        val model = resolveModel(settings) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        return withTimeout(90_000L) {
            runCatching {
                val response = providerManager.getProviderByType(provider).generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.system("你是 AmberAgent 的「今日回顾」助理。根据用户提供的数据生成中文 Markdown 日报。直接输出 Markdown，不要代码围栏。"),
                        UIMessage.user(prompt),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
                response.choices.firstOrNull()?.message?.toText()
            }.onFailure { Log.e(TAG, "daily review model call failed", it) }
                .getOrNull()
        }
    }

    private fun resolveModel(settings: Settings): me.rerere.ai.provider.Model? {
        val boardModelIdStr = settings.agentRuntime.todayBoard.boardModelId
        val specific = boardModelIdStr
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { settings.resolveTaskChatModel(it) }
        return specific ?: settings.resolveTaskChatModel(settings.chatModelId)
    }

    companion object {
        private const val TAG = "DailyReviewAgent"
        const val PHASE_NOON = "noon"
        const val PHASE_EVENING = "evening"
    }
}

sealed interface DailyReviewRunResult {
    data class Success(val phase: String) : DailyReviewRunResult
    data object Empty : DailyReviewRunResult
    data class Failed(val reason: String) : DailyReviewRunResult
}
