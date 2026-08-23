package app.amber.feature.home

import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.feature.runtime.RunTerminal
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.RunTerminalStore
import app.amber.feature.runtime.ToolEffect
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.runtime.ToolEffectStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.time.Instant

/**
 * Active `generate_image` projection for Home.
 *
 * The durable run/effect ledgers are the only source here: process-local
 * generation state is deliberately not promoted to a Home candidate. A
 * candidate routes to the owning chat, which is the only Android destination
 * that can show the tool call without inventing a gallery route in the dirty
 * Home UI.
 */
class ImageGenerationContinueSource(
    private val runTerminalStore: RunTerminalStore,
    private val toolEffectLedger: ToolEffectLedger,
    private val conversationDao: ConversationDAO,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) : ContinueCandidateSource {

    override fun observe(): Flow<List<ContinueCandidate>> = flow {
        while (currentCoroutineContext().isActive) {
            val runs = runCatching { runTerminalStore.unfinished() }.getOrDefault(emptyList())
            val effectsByRun = runs.associate { run ->
                run.runId to runCatching { toolEffectLedger.listByRun(run.runId) }
                    .getOrDefault(emptyList())
            }
            val existingConversationIds = mutableSetOf<String>()
            for (run in runs) {
                val exists = try {
                    conversationDao.existsById(run.conversationId)
                } catch (_: Exception) {
                    false
                }
                if (exists) existingConversationIds += run.conversationId
            }
            emit(imageGenerationContinueCandidates(runs, effectsByRun, existingConversationIds))
            delay(pollIntervalMillis)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    companion object {
        private const val DEFAULT_POLL_INTERVAL_MILLIS = 5_000L
    }
}

/** Pure projection kept testable without a Room/Koin graph. */
internal fun imageGenerationContinueCandidates(
    runs: List<RunTerminal>,
    effectsByRun: Map<String, List<ToolEffect>>,
    existingConversationIds: Set<String>,
): List<ContinueCandidate> = runs.flatMap { run ->
    if (run.conversationId !in existingConversationIds) return@flatMap emptyList()
    effectsByRun[run.runId].orEmpty()
        .filter { effect ->
            effect.toolName == GENERATE_IMAGE_TOOL_NAME &&
                effect.status in ACTIVE_EFFECT_STATUSES
        }
        .map { effect ->
            val waitingForUser = run.state == RunTerminalState.WAITING_USER ||
                run.state == RunTerminalState.OUTCOME_UNKNOWN ||
                effect.status == ToolEffectStatus.OUTCOME_UNKNOWN
            val updatedAtMs = maxOf(run.updatedAtMs, effect.finishedAtMs ?: effect.startedAtMs)
            ContinueCandidate(
                sourceKind = ContinueSourceKind.IMAGE_GENERATION,
                sourceId = "${run.conversationId}:${effect.toolCallId}",
                route = ContinueRoute.Chat(conversationId = run.conversationId),
                title = "AI 生图",
                summary = if (waitingForUser) "需要确认图片生成结果" else "正在生成图片",
                lastUpdatedAt = Instant.ofEpochMilli(updatedAtMs),
                status = if (waitingForUser) {
                    ContinueStatus.WAITING_USER
                } else {
                    ContinueStatus.FAILED_RESUMABLE
                },
                isRunning = !waitingForUser,
            )
        }
}

private const val GENERATE_IMAGE_TOOL_NAME = "generate_image"

private val ACTIVE_EFFECT_STATUSES = setOf(
    ToolEffectStatus.PREPARED,
    ToolEffectStatus.STARTED,
    ToolEffectStatus.OUTCOME_UNKNOWN,
)
