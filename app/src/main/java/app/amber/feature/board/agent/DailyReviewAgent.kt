package app.amber.feature.board.agent

import android.util.Log
import kotlinx.coroutines.withTimeout
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.feature.board.BoardRepository
import app.amber.feature.board.boardRequestBodies
import app.amber.feature.board.boardRequestHeaders
import app.amber.feature.board.collector.AppUsageCollector
import app.amber.feature.board.collector.AppUsageEntry
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.findProvider
import app.amber.core.settings.resolveTaskChatModel
import app.amber.agent.data.db.entity.BoardItemEntity
import app.amber.agent.data.db.entity.DailyReviewEntity
import app.amber.core.repository.ConversationRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val boardRepository: BoardRepository,
    private val conversationRepository: ConversationRepository,
    private val appUsageCollector: AppUsageCollector,
) {
    suspend fun run(
        boardDate: String,
        phase: String,
        locale: Locale = Locale.getDefault(),
    ): DailyReviewRunResult {
        val settings = settingsStore.settingsFlow.value
        val now = System.currentTimeMillis()

        // 1. Collect data sources
        val appUsage = appUsageCollector.collectToday(now)
        val completedItems = boardRepository.getCompletedItems(boardDate)
        val recentChats = collectRecentChatSummaries(locale)

        if (appUsage.isEmpty() && completedItems.isEmpty() && recentChats.isEmpty()) {
            return DailyReviewRunResult.Empty
        }

        // 2. Build prompt
        val existing = boardRepository.getDailyReview(boardDate)
        val prompt = buildPrompt(phase, appUsage, completedItems, recentChats, existing?.content, locale)

        // 3. Call LLM
        val rawMarkdown = callModel(settings, prompt, locale)
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
        locale: Locale,
    ): String = buildString {
        val chinese = locale.language.equals("zh", ignoreCase = true)
        appendLine(
            if (chinese) {
                "你是 AmberAgent 的「今日复盘」助理。根据下面的数据生成一份**中文 Markdown** 格式的任务复盘。"
            } else {
                "You are AmberAgent's Daily Review assistant. Use the data below to generate a **Markdown task review in English**."
            }
        )
        appendLine()
        appendLine(if (chinese) "## 输出要求" else "## Output requirements")
        appendLine(if (chinese) "- 直接输出 Markdown，不要代码围栏、不要 JSON。" else "- Output Markdown directly; no code fences and no JSON.")
        appendLine(if (chinese) "- 语气：简洁、务实、像给自己写的日记，不要客套话。" else "- Keep it concise and practical, like a private diary; omit pleasantries.")
        appendLine(if (chinese) "- 用具体数字和事实，不要空洞总结。" else "- Use concrete numbers and facts; avoid vague summaries.")
        appendLine(if (chinese) "- 如果某个数据源为空，跳过该部分，不要写「无数据」。" else "- Skip an empty data source instead of writing that data is unavailable.")
        appendLine()

        if (phase == PHASE_NOON) {
            if (chinese) {
                appendLine("## 当前阶段：上午回顾（13:00）")
                appendLine("覆盖今天从早上到现在的活动。生成以下部分（按需）：")
                appendLine("1. **已完成事项** — 从看板完成的事项")
                appendLine("2. **📱 应用使用** — 今天用了哪些 app，各用了多久，结合 app 用途推测在做什么")
                appendLine("3. **💬 对话摘要** — 今天跟 AI 聊了什么重要话题")
                appendLine("4. **上午小结** — 一两句话总结上午状态")
            } else {
                appendLine("## Current phase: morning review (13:00)")
                appendLine("Cover activity from this morning through now. Generate the following sections as needed:")
                appendLine("1. **Completed items** — items completed from the board")
                appendLine("2. **📱 App usage** — which apps were used and for how long; infer the user's activity from their purpose")
                appendLine("3. **💬 Conversation summary** — important topics discussed with AI today")
                appendLine("4. **Morning recap** — one or two sentences summarizing the morning")
            }
        } else {
            if (chinese) {
                appendLine("## 当前阶段：下午/晚间补充（19:00）")
                if (existingContent != null) {
                    appendLine("在已有的上午复盘基础上，**只生成下午新增部分**，格式同上但标题用「下午」。")
                } else {
                    appendLine("上午复盘未生成，请生成完整的今日复盘（覆盖全天）。")
                }
                appendLine("不要重复上午的内容。")
                if (existingContent != null) {
                    appendLine()
                    appendLine("## 已有的上午复盘内容（参考，不要重复）")
                    appendLine(existingContent.take(2000))
                }
            } else {
                appendLine("## Current phase: afternoon/evening supplement (19:00)")
                if (existingContent != null) {
                    appendLine("Based on the existing morning review, generate **only new afternoon content** using the same format with afternoon headings.")
                } else {
                    appendLine("The morning review was not generated; produce a complete review covering the whole day.")
                }
                appendLine("Do not repeat the morning content.")
                if (existingContent != null) {
                    appendLine()
                    appendLine("## Existing morning review (reference; do not repeat)")
                    appendLine(existingContent.take(2000))
                }
            }
        }

        appendLine()

        if (chinese) {
            appendLine("## 旧版回顾兼容提示")
            appendLine(
                if (phase == PHASE_NOON) {
                    "如果数据不足，可以继续参考应用使用、已完成看板事项和对话主题。"
                } else {
                    "如果下午数据不足，可以继续参考应用使用、已完成看板事项和对话主题。"
                }
            )
        } else {
            appendLine("## Compatibility note for older reviews")
            appendLine(
                if (phase == PHASE_NOON) {
                    "If data is sparse, you may still use app usage, completed board items, and conversation topics."
                } else {
                    "If afternoon data is sparse, you may still use app usage, completed board items, and conversation topics."
                }
            )
        }

        appendLine()
        if (appUsage.isEmpty()) {
            if (chinese) {
                appendLine("## 注意：应用使用数据不可用")
                appendLine("可能原因：未授予「使用情况访问」权限。跳过应用使用部分即可。")
            } else {
                appendLine("## Note: app usage data is unavailable")
                appendLine("Possible reason: usage access permission was not granted. Skip the app usage section.")
            }
            appendLine()
        }
        if (appUsage.isNotEmpty()) {
            appendLine(if (chinese) "## 数据：应用使用" else "## Data: app usage")
            for (entry in appUsage) {
                appendLine(
                    if (chinese) {
                        "- ${entry.appLabel}（${entry.packageName}）：前台 ${entry.formattedDuration()}"
                    } else {
                        "- ${entry.appLabel} (${entry.packageName}): foreground ${entry.formattedDuration()}"
                    }
                )
            }
            appendLine()
        }

        if (completedItems.isNotEmpty()) {
            appendLine(if (chinese) "## 数据：今日已完成的看板事项" else "## Data: board items completed today")
            for (item in completedItems) {
                appendLine(
                    if (chinese) {
                        "- ${item.title}（来源：${item.sourceType}）"
                    } else {
                        "- ${item.title} (source: ${item.sourceType})"
                    }
                )
            }
            appendLine()
        }

        if (recentChats.isNotEmpty()) {
            appendLine(if (chinese) "## 数据：今日对话主题" else "## Data: conversation topics today")
            for (chat in recentChats) {
                appendLine("- $chat")
            }
            appendLine()
        }
    }

    private suspend fun collectRecentChatSummaries(locale: Locale): List<String> = runCatching {
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
                if (locale.language.equals("zh", ignoreCase = true)) {
                    "${conv.title}（${nodeCount}轮对话）"
                } else {
                    "${conv.title} (${nodeCount} conversation turns)"
                }
            }
    }.getOrElse { emptyList() }

    private suspend fun callModel(settings: Settings, prompt: String, locale: Locale): String? {
        val model = resolveModel(settings) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        return withTimeout(90_000L) {
            runCatching {
                val response = providerCatalog.text(provider).complete(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.system(
                            if (locale.language.equals("zh", ignoreCase = true)) {
                                "你是 AmberAgent 的「今日复盘」助理。根据用户提供的数据生成中文 Markdown 任务复盘。直接输出 Markdown，不要代码围栏。"
                            } else {
                                "You are AmberAgent's Daily Review assistant. Generate an English Markdown task review from the user's data. Output Markdown directly; no code fences."
                            }
                        ),
                        UIMessage.user(prompt),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        customHeaders = model.boardRequestHeaders(settings.providers),
                        customBody = model.boardRequestBodies(settings.providers),
                    ),
                )
                response.choices.firstOrNull()?.message?.toText()
            }.onFailure { Log.e(TAG, "daily review model call failed", it) }
                .getOrNull()
        }
    }

    private fun resolveModel(settings: Settings): app.amber.ai.provider.Model? {
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
