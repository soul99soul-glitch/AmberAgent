package app.amber.feature.home

import app.amber.agent.data.db.entity.ContinueCandidateDismissEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * P8-08 验收测试（聚合层，纯 JVM）：
 * - 排序规则：waitingUser → failedResumable → 最近 paused/draft → 用户固定项；
 * - dismiss 后到期前不出现、到期后恢复；
 * - 来源不再产出（完成/删除）后候选消失；
 * - 点击路由参数原样透传。
 */
class ContinueCandidateAggregatorTest {

    private val t0 = Instant.parse("2026-08-01T00:00:00Z")

    private fun candidate(
        kind: ContinueSourceKind,
        id: String,
        status: ContinueStatus,
        updatedAt: Instant = t0,
        priority: Int = 0,
        route: ContinueRoute = ContinueRoute.Chat(conversationId = id),
    ) = ContinueCandidate(
        sourceKind = kind,
        sourceId = id,
        route = route,
        title = "title-$id",
        summary = "summary-$id",
        lastUpdatedAt = updatedAt,
        status = status,
        priority = priority,
    )

    private class FakeSource(initial: List<ContinueCandidate>) : ContinueCandidateSource {
        val flow = MutableStateFlow(initial)

        override fun observe(): Flow<List<ContinueCandidate>> = flow
    }

    private class FakeDismissStore(initial: List<ContinueCandidateDismissEntity> = emptyList()) :
        ContinueDismissStore {
        val flow = MutableStateFlow(initial)
        var deleteExpiredCalls = 0

        override fun observeDismissed(): Flow<List<ContinueCandidateDismissEntity>> = flow

        override suspend fun dismiss(sourceKind: ContinueSourceKind, sourceId: String, until: Instant) {
            flow.value = flow.value.filterNot { it.sourceKind == sourceKind.name && it.sourceId == sourceId } +
                ContinueCandidateDismissEntity(sourceKind.name, sourceId, until.toEpochMilli())
        }

        override suspend fun deleteExpired(nowMs: Long) {
            deleteExpiredCalls++
            flow.value = flow.value.filterNot { it.dismissUntilMs <= nowMs }
        }
    }

    @Test
    fun `sort places waitingUser then failedResumable then recent paused and draft then pinned`() = runTest {
        val source = FakeSource(
            listOf(
                candidate(ContinueSourceKind.COUNCIL, "c", ContinueStatus.FAILED_RESUMABLE, t0.plusSeconds(5)),
                candidate(ContinueSourceKind.DEEP_READ, "d", ContinueStatus.FAILED_RESUMABLE, t0.plusSeconds(1)),
                candidate(ContinueSourceKind.COUNCIL, "w", ContinueStatus.WAITING_USER, t0.plusSeconds(2)),
                candidate(ContinueSourceKind.MINIAPP_DRAFT, "draft-old", ContinueStatus.DRAFT, t0.minusSeconds(9)),
                candidate(ContinueSourceKind.DEEP_READ, "p", ContinueStatus.PAUSED, t0.minusSeconds(3)),
                candidate(ContinueSourceKind.COUNCIL, "pinned", ContinueStatus.PAUSED, t0.minusSeconds(1), priority = 5),
            ),
        )
        val aggregator = ContinueCandidateAggregator(listOf(source), FakeDismissStore()) { t0 }

        val result = aggregator.observe().first()

        assertEquals(
            listOf("w", "c", "d", "p", "draft-old", "pinned"),
            result.map { it.sourceId },
        )
    }

    @Test
    fun `dismissed candidate disappears before expiry and reappears after`() = runTest {
        val source = FakeSource(
            listOf(candidate(ContinueSourceKind.COUNCIL, "c1", ContinueStatus.FAILED_RESUMABLE)),
        )
        val dismiss = FakeDismissStore()
        val aggregator = ContinueCandidateAggregator(listOf(source), dismiss) { t0 }

        // 未隐藏：可见
        assertTrue(aggregator.observe().first().isNotEmpty())

        // 隐藏到 t0 + 10s：到期前不出现
        dismiss.dismiss(ContinueSourceKind.COUNCIL, "c1", t0.plusSeconds(10))
        assertEquals(0, aggregator.observe().first().size)

        // 到期后恢复
        val later = ContinueCandidateAggregator(listOf(source), dismiss) { t0.plusSeconds(11) }
        assertEquals(1, later.observe().first().size)
    }

    @Test
    fun `candidate removed by its source (completed or deleted) no longer appears`() = runTest {
        val source = FakeSource(
            listOf(candidate(ContinueSourceKind.MINIAPP_DRAFT, "g1", ContinueStatus.PAUSED)),
        )
        val aggregator = ContinueCandidateAggregator(listOf(source), FakeDismissStore()) { t0 }
        assertEquals(1, aggregator.observe().first().size)

        // 完成/删除：来源不再产出该候选
        source.flow.value = emptyList()
        assertEquals(0, aggregator.observe().first().size)
    }

    @Test
    fun `aggregation prunes expired dismiss records via deleteExpired`() = runTest {
        val source = FakeSource(
            listOf(candidate(ContinueSourceKind.COUNCIL, "c1", ContinueStatus.PAUSED)),
        )
        val dismiss = FakeDismissStore(
            listOf(
                ContinueCandidateDismissEntity("council", "c1", t0.minusSeconds(1).toEpochMilli()),
                ContinueCandidateDismissEntity("council", "c2", t0.plusSeconds(60).toEpochMilli()),
            ),
        )
        val aggregator = ContinueCandidateAggregator(listOf(source), dismiss) { t0 }

        // Minor-2: 聚合路径顺带调用 deleteExpired —— 过期记录被清理，未过期保留
        aggregator.observe().first()
        assertEquals(1, dismiss.deleteExpiredCalls)
        assertEquals(listOf("c2"), dismiss.flow.value.map { it.sourceId })
    }

    @Test
    fun `route parameters are passed through unchanged`() = runTest {
        val routes = listOf<ContinueRoute>(
            ContinueRoute.CouncilRoom(conversationId = "conv-456"),
            ContinueRoute.DeepRead(topicId = "topic-789", title = "某个话题"),
            ContinueRoute.Chat(conversationId = "chat-000"),
        )
        val source = FakeSource(
            routes.mapIndexed { index, route ->
                candidate(
                    kind = ContinueSourceKind.entries[index],
                    id = "id-$index",
                    status = ContinueStatus.PAUSED,
                    route = route,
                )
            },
        )
        val aggregator = ContinueCandidateAggregator(listOf(source), FakeDismissStore()) { t0 }

        val result = aggregator.observe().first()
        assertEquals(routes.toSet(), result.map { it.route }.toSet())
    }

    @Test
    fun `sort is stable across equal timestamps`() = runTest {
        val source = FakeSource(
            listOf(
                candidate(ContinueSourceKind.COUNCIL, "b", ContinueStatus.FAILED_RESUMABLE, t0),
                candidate(ContinueSourceKind.COUNCIL, "a", ContinueStatus.FAILED_RESUMABLE, t0),
            ),
        )
        val aggregator = ContinueCandidateAggregator(listOf(source), FakeDismissStore()) { t0 }
        val result = aggregator.observe().first()
        // 同组同时间：按 (sourceKind, sourceId) 稳定排序
        assertEquals(listOf("a", "b"), result.map { it.sourceId })
    }
}
