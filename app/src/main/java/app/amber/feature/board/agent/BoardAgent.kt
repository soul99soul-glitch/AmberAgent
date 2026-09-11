package app.amber.feature.board.agent

import android.util.Log
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.feature.board.BoardRepository
import app.amber.feature.board.aggregator.ScoredSignal
import app.amber.feature.board.boardRequestBodies
import app.amber.feature.board.boardRequestHeaders
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.findProvider
import app.amber.core.settings.resolveTaskChatModel
import app.amber.agent.data.db.entity.BoardFocusRuleEntity
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * Single board generation run: build prompt -> call LLM -> parse -> validate -> persist.
 * Retries the model call once on parse failure. Returns [BoardRunResult] so the worker
 * can keep the previous board visible when generation fails.
 */
class BoardAgent(
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val boardRepository: BoardRepository,
) {
    suspend fun run(
        scoredSignals: List<ScoredSignal>,
        focusRules: List<BoardFocusRuleEntity>,
        boardDate: String,
        locale: Locale = Locale.getDefault(),
    ): BoardRunResult {
        if (scoredSignals.isEmpty()) return BoardRunResult.Empty

        val settings = settingsStore.settingsFlow.value
        val prompt = BoardPrompt.build(scoredSignals, focusRules, locale = locale)

        val rawText = callModel(settings, prompt, locale)
            ?: return BoardRunResult.Failed(
                "model call failed" + (lastCallFailureReason?.let {
                    if (locale.language.equals("zh", ignoreCase = true)) "：$it" else ": $it"
                } ?: "")
            )

        val parsed = BoardOutputParser.parse(rawText)
            ?: retry(settings, prompt, locale)
            ?: return BoardRunResult.Failed("parse failed after retry")

        val signalsByKey = scoredSignals.associateBy { boardSignalKey(it.signal.sourceType, it.signal.sourceRef) }
        val validation = BoardOutputValidator.validate(parsed, scoredSignals)
        if (validation.warnings.isNotEmpty()) {
            Log.i(TAG, "validation warnings: ${validation.warnings.joinToString("; ")}")
        }

        val output = validation.output
        if (output.items.isEmpty()) return BoardRunResult.Empty

        val now = System.currentTimeMillis()
        val entities = output.items.map { item ->
            val sourceContent = signalsByKey[boardSignalKey(item.source_type, item.source_ref)]?.signal?.content.orEmpty()
            item.toEntity(sourceContent = sourceContent, boardDate = boardDate, nowMs = now)
        }
        boardRepository.saveItems(entities)

        return BoardRunResult.Success(summary = output.summary, itemCount = entities.size)
    }

    private suspend fun retry(settings: Settings, prompt: String, locale: Locale): BoardAgentOutput? {
        Log.w(TAG, "first parse failed, retrying once with corrective hint")
        // Append a corrective hint so we don't pay for an identical second round-trip
        // when the first one drifted off the JSON contract.
        val correctedPrompt = prompt + if (locale.language.equals("zh", ignoreCase = true)) {
            "\n\n## 重试提示\n上一次输出未能解析为合法 JSON。请只返回 JSON 对象，不要代码围栏，不要前后解释。"
        } else {
            "\n\n## Retry hint\nThe previous output was not valid JSON. Return only a JSON object; no code fences or surrounding explanation."
        }
        val text = callModel(settings, correctedPrompt, locale) ?: return null
        return BoardOutputParser.parse(text)
    }

    /** Stores the reason for the last callModel failure for user-facing messages. */
    private var lastCallFailureReason: String? = null

    private suspend fun callModel(settings: Settings, prompt: String, locale: Locale): String? {
        val model = resolveModel(settings)
        if (model == null) {
            lastCallFailureReason = if (locale.language.equals("zh", ignoreCase = true)) {
                "请先配置聊天模型（设置 → 模型）"
            } else {
                "Configure a chat model in Settings > Models."
            }
            return null
        }
        val provider = model.findProvider(settings.providers)
        if (provider == null) {
            lastCallFailureReason = if (locale.language.equals("zh", ignoreCase = true)) {
                "模型 ${model.displayName} 的提供商不可用"
            } else {
                "The provider for model ${model.displayName} is unavailable"
            }
            return null
        }
        lastCallFailureReason = null
        return runCatching {
            val response = providerCatalog.text(provider).complete(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.system(
                        if (locale.language.equals("zh", ignoreCase = true)) {
                            "你是 AmberAgent 的「今日看板」助理。基于用户提供的信号产出结构化 JSON 看板。仅输出 JSON，不要代码围栏、不要前后解释。"
                        } else {
                            "You are AmberAgent's Today Board assistant. Produce a structured JSON board from the user's signals. Output JSON only; no code fences or surrounding explanation."
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
        }.onFailure { e ->
            Log.e(TAG, "board model call failed", e)
            lastCallFailureReason = e.message?.take(100) ?: e::class.simpleName
        }.getOrNull()
    }

    private fun resolveModel(settings: Settings): app.amber.ai.provider.Model? {
        val boardModelIdStr = settings.agentRuntime.todayBoard.boardModelId
        val specific = boardModelIdStr
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { settings.resolveTaskChatModel(it) }
        return specific ?: settings.resolveTaskChatModel(settings.chatModelId)
    }

    companion object {
        private const val TAG = "BoardAgent"
    }
}

sealed interface BoardRunResult {
    data class Success(val summary: String, val itemCount: Int) : BoardRunResult
    data object Empty : BoardRunResult
    data class Failed(val reason: String) : BoardRunResult
}
