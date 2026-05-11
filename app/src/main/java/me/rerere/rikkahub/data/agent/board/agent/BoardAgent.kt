package me.rerere.rikkahub.data.agent.board.agent

import android.util.Log
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.agent.board.BoardRepository
import me.rerere.rikkahub.data.agent.board.aggregator.ScoredSignal
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import me.rerere.rikkahub.data.db.entity.BoardFocusRuleEntity
import kotlin.uuid.Uuid

/**
 * Single board generation run: build prompt -> call LLM -> parse -> validate -> persist.
 * Retries the model call once on parse failure. Returns [BoardRunResult] so the worker
 * can keep the previous board visible when generation fails.
 */
class BoardAgent(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val boardRepository: BoardRepository,
) {
    suspend fun run(
        scoredSignals: List<ScoredSignal>,
        focusRules: List<BoardFocusRuleEntity>,
        boardDate: String,
    ): BoardRunResult {
        if (scoredSignals.isEmpty()) return BoardRunResult.Empty

        val settings = settingsStore.settingsFlow.value
        val density = settings.agentRuntime.todayBoard.density
        val prompt = BoardPrompt.build(scoredSignals, focusRules, density)

        val rawText = callModel(settings, prompt)
            ?: return BoardRunResult.Failed("model call failed")

        val parsed = BoardOutputParser.parse(rawText)
            ?: retry(settings, prompt)
            ?: return BoardRunResult.Failed("parse failed after retry")

        val signalsByRef = scoredSignals.associateBy { it.signal.sourceRef }
        val validation = BoardOutputValidator.validate(parsed, signalsByRef, density)
        if (validation.warnings.isNotEmpty()) {
            Log.i(TAG, "validation warnings: ${validation.warnings.joinToString("; ")}")
        }

        val output = validation.output
        if (output.items.isEmpty()) return BoardRunResult.Empty

        val now = System.currentTimeMillis()
        val entities = output.items.map { item ->
            val sourceContent = signalsByRef[item.source_ref]?.signal?.content.orEmpty()
            item.toEntity(sourceContent = sourceContent, boardDate = boardDate, nowMs = now)
        }
        boardRepository.saveItems(entities)

        return BoardRunResult.Success(summary = output.summary, itemCount = entities.size)
    }

    private suspend fun retry(settings: Settings, prompt: String): BoardAgentOutput? {
        Log.w(TAG, "first parse failed, retrying once with corrective hint")
        // Append a corrective hint so we don't pay for an identical second round-trip
        // when the first one drifted off the JSON contract.
        val correctedPrompt = prompt + "\n\n## 重试提示\n上一次输出未能解析为合法 JSON。请只返回 JSON 对象，不要代码围栏，不要前后解释。"
        val text = callModel(settings, correctedPrompt) ?: return null
        return BoardOutputParser.parse(text)
    }

    private suspend fun callModel(settings: Settings, prompt: String): String? {
        val model = resolveModel(settings) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        return runCatching {
            val response = providerManager.getProviderByType(provider).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
            response.choices.firstOrNull()?.message?.toText()
        }.onFailure { Log.e(TAG, "board model call failed", it) }
            .getOrNull()
    }

    private fun resolveModel(settings: Settings): me.rerere.ai.provider.Model? {
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
