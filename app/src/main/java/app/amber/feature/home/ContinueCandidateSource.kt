package app.amber.feature.home

import app.amber.agent.data.db.entity.ContinueCandidateDismissEntity
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant

/**
 * P8-08 — 候选来源抽象。每个域一个 [ContinueCandidateSource]，把该域的
 * 持久投影（文件账本 / Room 表 / 缓存 JSON）映射为「可继续」候选列表。
 * 进程内运行状态不作为候选依据。
 */
interface ContinueCandidateSource {
    fun observe(): Flow<List<ContinueCandidate>>
}

/** dismissUntil 记录的持久化抽象；真实实现为 Room DAO，测试用内存实现。 */
interface ContinueDismissStore {
    fun observeDismissed(): Flow<List<ContinueCandidateDismissEntity>>

    suspend fun dismiss(sourceKind: ContinueSourceKind, sourceId: String, until: Instant)

    /** Minor-2: 删除所有已过期（dismissUntil 早于 nowMs）的记录。 */
    suspend fun deleteExpired(nowMs: Long)
}

/** 默认隐藏时长：24 小时（首页「继续」的暂时隐藏语义）。 */
val DEFAULT_DISMISS_DURATION: Duration = Duration.ofHours(24)
