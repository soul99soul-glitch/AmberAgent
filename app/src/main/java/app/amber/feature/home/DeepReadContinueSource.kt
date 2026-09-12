package app.amber.feature.home

import android.content.Context
import androidx.work.WorkInfo
import app.amber.agent.R
import app.amber.agent.data.db.dao.HotListDAO
import app.amber.core.utils.JsonInstant
import app.amber.feature.board.hotlist.DeepReadCachePolicy
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.board.hotlist.deepread.isComplete
import app.amber.feature.board.hotlist.deepread.statusOf
import app.amber.feature.board.hotlist.deepread.withInferredSectionStates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

/**
 * DeepRead 域的可继续候选：deep_read_cache 中「未完成但有进度」的深度阅读
 * （部分章节 READY 或仍在生成阶段）。完成后（isComplete）自动消失；
 * 用户删除缓存行后也自然消失。路由到 DeepRead 页可续跑缺失章节。
 */
class DeepReadContinueSource(
    private val hotListDao: HotListDAO,
    private val context: Context,
    private val observeActiveWorkStates: () -> Flow<Map<String, WorkInfo.State>> = { flowOf(emptyMap()) },
    private val now: () -> Instant = Instant::now,
) : ContinueCandidateSource {

    override fun observe(): Flow<List<ContinueCandidate>> =
        combine(hotListDao.observeAllDeepReads(), observeActiveWorkStates()) { entities, workStates ->
            val nowMs = now().toEpochMilli()
            entities.mapNotNull { entity ->
                if (!DeepReadCachePolicy.isFresh(entity.expiresAt, nowMs, entity.pinned)) {
                    return@mapNotNull null
                }
                val output = runCatching {
                    JsonInstant.decodeFromString<DeepReadOutput>(entity.outputJson)
                }.getOrNull()?.withInferredSectionStates() ?: return@mapNotNull null
                if (output.isComplete()) return@mapNotNull null
                val readyCount = DeepReadGenerationStageAll.count { stage ->
                    output.statusOf(stage) == DeepReadSectionStatus.READY
                }
                val workState = workStates[entity.topicId]
                val isRunning = workState == WorkInfo.State.RUNNING
                val hasActiveWork = workState != null
                if (readyCount == 0 && output.generationPhase == DeepReadGenerationPhase.IDLE && !hasActiveWork) {
                    return@mapNotNull null
                }
                ContinueCandidate(
                    sourceKind = ContinueSourceKind.DEEP_READ,
                    sourceId = entity.topicId,
                    route = ContinueRoute.DeepRead(
                        topicId = entity.topicId,
                        title = entity.title,
                        sourceUrl = entity.sourceUrl,
                    ),
                    title = entity.title,
                    summary = if (isRunning) {
                        output.homeProgressSummary()
                    } else {
                        context.getString(R.string.session_home_status_resumable)
                    },
                    lastUpdatedAt = Instant.ofEpochMilli(entity.updatedAt),
                    status = ContinueStatus.FAILED_RESUMABLE,
                    isRunning = isRunning,
                )
            }
        }

    private fun DeepReadOutput.homeProgressSummary(): String = when {
        verificationState.status == DeepReadSectionStatus.RUNNING ||
            generationPhase == DeepReadGenerationPhase.VERIFYING ->
            context.getString(R.string.session_home_deep_read_progress_verifying)

        generationPhase == DeepReadGenerationPhase.COLLECTING ->
            context.getString(R.string.session_home_deep_read_progress_collecting)

        generationPhase == DeepReadGenerationPhase.PLANNING ->
            context.getString(R.string.session_home_deep_read_progress_planning)

        generationPhase == DeepReadGenerationPhase.WRITING -> {
            val stage = DeepReadGenerationStageAll.firstOrNull {
                statusOf(it) == DeepReadSectionStatus.RUNNING
            } ?: DeepReadGenerationStageAll.firstOrNull {
                statusOf(it) != DeepReadSectionStatus.READY
            }
            when (stage) {
                DeepReadGenerationStage.OVERVIEW -> context.getString(
                    R.string.session_home_deep_read_progress_overview,
                )
                DeepReadGenerationStage.NARRATIVE -> context.getString(
                    R.string.session_home_deep_read_progress_narrative,
                )
                DeepReadGenerationStage.ANALYSIS -> context.getString(
                    R.string.session_home_deep_read_progress_analysis,
                )
                DeepReadGenerationStage.EXTENDED_READING -> context.getString(
                    R.string.session_home_deep_read_progress_extended_reading,
                )
                null -> context.getString(R.string.session_home_deep_read_progress_finishing)
            }
        }

        else -> context.getString(R.string.session_home_deep_read_progress_preparing)
    }

    companion object {
        /** 与 DeepReadGenerationStage.entries 同序，作为「总部分数」。 */
        private val DeepReadGenerationStageAll = DeepReadGenerationStage.entries
    }
}
