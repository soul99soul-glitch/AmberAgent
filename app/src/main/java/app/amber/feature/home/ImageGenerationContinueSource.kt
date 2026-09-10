package app.amber.feature.home

import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.agent.data.db.dao.ToolEffectConversationRow
import app.amber.agent.data.db.dao.ToolEffectDAO
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
    private val toolEffectDao: ToolEffectDAO,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ContinueCandidateSource {

    override fun observe(): Flow<List<ContinueCandidate>> = flow {
        while (currentCoroutineContext().isActive) {
            val runs = runTerminalStore.unfinished()
            val effectsByRun = runs.associate { run ->
                run.runId to toolEffectLedger.listByRun(run.runId)
            }
            val existingConversationIds = mutableSetOf<String>()
            for (run in runs) {
                val exists = conversationDao.existsById(run.conversationId)
                if (exists) existingConversationIds += run.conversationId
            }
            val completed = toolEffectDao.listRecentFinishedWithConversation(
                toolName = GENERATE_IMAGE_TOOL_NAME,
                sinceMs = nowMillis() - COMPLETED_LOOKBACK_MILLIS,
                limit = COMPLETED_LIMIT,
            )
            emit(
                imageGenerationContinueCandidates(runs, effectsByRun, existingConversationIds) +
                    imageGenerationCompletedContinueCandidates(completed)
            )
            delay(pollIntervalMillis)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    companion object {
        private const val DEFAULT_POLL_INTERVAL_MILLIS = 5_000L
        private const val COMPLETED_LOOKBACK_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        private const val COMPLETED_LIMIT = 20
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
                route = ContinueRoute.ImageGeneration(
                    conversationId = run.conversationId,
                    messageId = effect.messagePersistenceCursor,
                    toolCallId = effect.toolCallId,
                ),
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

/** Projects completed results from the indexed effect query, still anchored to the source call. */
internal fun imageGenerationCompletedContinueCandidates(
    rows: List<ToolEffectConversationRow>,
): List<ContinueCandidate> = rows.map { row ->
    val effect = row.effect.let(ToolEffect::from)
    ContinueCandidate(
        sourceKind = ContinueSourceKind.IMAGE_GENERATION,
        sourceId = "${row.conversationId}:${effect.toolCallId}",
        route = ContinueRoute.ImageGeneration(
            conversationId = row.conversationId,
            messageId = effect.messagePersistenceCursor,
            toolCallId = effect.toolCallId,
        ),
        title = "AI 生图",
        summary = "最近生成的图片",
        lastUpdatedAt = Instant.ofEpochMilli(effect.finishedAtMs ?: effect.startedAtMs),
        status = ContinueStatus.DRAFT,
    )
}

private const val GENERATE_IMAGE_TOOL_NAME = "generate_image"

private val ACTIVE_EFFECT_STATUSES = setOf(
    ToolEffectStatus.PREPARED,
    ToolEffectStatus.STARTED,
    ToolEffectStatus.OUTCOME_UNKNOWN,
)
