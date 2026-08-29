package app.amber.feature.home

import android.content.Context
import app.amber.agent.R
import app.amber.agent.data.db.dao.HotListDAO
import app.amber.core.utils.JsonInstant
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.board.hotlist.deepread.hasAnyReadySection
import app.amber.feature.board.hotlist.deepread.isComplete
import app.amber.feature.board.hotlist.deepread.statusOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * DeepRead 域的可继续候选：deep_read_cache 中「未完成但有进度」的深度阅读
 * （部分章节 READY 或仍在生成阶段）。完成后（isComplete）自动消失；
 * 用户删除缓存行后也自然消失。路由到 DeepRead 页可续跑缺失章节。
 */
class DeepReadContinueSource(
    private val hotListDao: HotListDAO,
    private val context: Context,
    private val now: () -> Instant = Instant::now,
) : ContinueCandidateSource {

    override fun observe(): Flow<List<ContinueCandidate>> =
        hotListDao.observeAllDeepReads().map { entities ->
            val nowMs = now().toEpochMilli()
            entities.mapNotNull { entity ->
                if (entity.expiresAt <= nowMs) return@mapNotNull null
                val output = runCatching {
                    JsonInstant.decodeFromString<DeepReadOutput>(entity.outputJson)
                }.getOrNull() ?: return@mapNotNull null
                if (output.isComplete()) return@mapNotNull null
                val readyCount = DeepReadGenerationStageAll.count { stage ->
                    output.statusOf(stage) == DeepReadSectionStatus.READY
                }
                if (readyCount == 0 && output.generationPhase == DeepReadGenerationPhase.IDLE) {
                    return@mapNotNull null
                }
                val progress = context.getString(
                    R.string.deep_read_notification_running_content,
                    context.getString(R.string.session_home_feature_deep_read),
                    "$readyCount/${DeepReadGenerationStageAll.size}",
                )
                ContinueCandidate(
                    sourceKind = ContinueSourceKind.DEEP_READ,
                    sourceId = entity.topicId,
                    route = ContinueRoute.DeepRead(topicId = entity.topicId, title = entity.title),
                    title = entity.title,
                    summary = progress,
                    lastUpdatedAt = Instant.ofEpochMilli(entity.updatedAt),
                    status = ContinueStatus.FAILED_RESUMABLE,
                )
            }
        }

    companion object {
        /** 与 DeepReadGenerationStage.entries 同序，作为「总部分数」。 */
        private val DeepReadGenerationStageAll = DeepReadGenerationStage.entries
    }
}
