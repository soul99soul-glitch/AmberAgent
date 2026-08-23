package app.amber.feature.home

import app.amber.agent.data.db.entity.ContinueCandidateDismissEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * P8-08 — 首页「继续」聚合器。
 *
 * 把各域 [ContinueCandidateSource] 的持久投影合并为一条列表流：
 * 1. 合并所有来源的候选；
 * 2. 过滤掉 dismissUntil 未到期的隐藏项（dismiss 记录来自持久层）；
 * 3. 按计划 §P8-08 排序（waitingUser → active/failedResumable → 最近 paused/draft → 固定项）。
 */
class ContinueCandidateAggregator(
    private val sources: List<ContinueCandidateSource>,
    private val dismissStore: ContinueDismissStore,
    private val now: () -> Instant = Instant::now,
) {

    fun observe(): Flow<List<ContinueCandidate>> {
        val sourceFlows = sources.map { it.observe() }
        return combine(sourceFlows + dismissStore.observeDismissed()) { values ->
            @Suppress("UNCHECKED_CAST")
            val dismissed = values.last() as List<ContinueCandidateDismissEntity>
            val nowMs = now().toEpochMilli()
            val activeDismiss = dismissed
                .filter { it.dismissUntilMs > nowMs }
                .mapTo(mutableSetOf()) { it.sourceKind to it.sourceId }
            values.dropLast(1)
                .flatMap { @Suppress("UNCHECKED_CAST") it as List<ContinueCandidate> }
                .filterNot { (it.sourceKind.name to it.sourceId) in activeDismiss }
        }.flatMapLatest { candidates ->
            flow {
                // Minor-2: 每次聚合顺带清理过期的 dismiss 记录（不建独立调度），
                // 避免 deleteExpired 成为永不调用的死代码。
                dismissStore.deleteExpired(now().toEpochMilli())
                emit(sortContinueCandidates(candidates))
            }
        }
    }
}
