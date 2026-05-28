package me.rerere.rikkahub.data.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.dao.MemoryDreamPlanDAO
import me.rerere.rikkahub.data.db.entity.MemoryDreamPlanEntity
import app.amber.core.memory.dream.MemoryDreamPlan
import app.amber.core.memory.dream.MemoryDreamPlanSource
import app.amber.core.memory.dream.MemoryDreamPlanStatus
import app.amber.core.memory.dream.MemoryDreamPlanStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryDreamPlanStoreTest {
    @Test
    fun pendingPlanSurvivesUntilApplyOrDismiss() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)

        val first = store.savePending(
            plan = MemoryDreamPlan(promoteMemoryIds = listOf(1)),
            source = MemoryDreamPlanSource.AUTO,
            now = 100L,
        )

        assertEquals(first.id, store.getPendingPlan()?.id)
        assertEquals(1, store.countAutoPlansSince(0L))

        store.markApplied(first.id, now = 200L)
        assertNull(store.getPendingPlan())

        val second = store.savePending(
            plan = MemoryDreamPlan(archiveMemoryIds = listOf(2)),
            source = MemoryDreamPlanSource.MANUAL,
            now = 300L,
        )
        val third = store.savePending(
            plan = MemoryDreamPlan(ignoreCandidateIds = listOf("candidate")),
            source = MemoryDreamPlanSource.MANUAL,
            now = 400L,
        )

        assertEquals(third.id, store.getPendingPlan()?.id)
        assertEquals(MemoryDreamPlanStatus.DISMISSED.wireName, dao.find(second.id)?.status)

        store.markDismissed(third.id, now = 500L)
        assertNull(store.getPendingPlan())

        store.recordAutoRun(
            plan = MemoryDreamPlan(),
            now = 600L,
        )
        assertNull(store.getPendingPlan())
        assertEquals(2, store.countAutoPlansSince(0L))

        val pending = store.savePending(
            plan = MemoryDreamPlan(promoteMemoryIds = listOf(3)),
            source = MemoryDreamPlanSource.MANUAL,
            now = 700L,
        )
        val applied = store.saveApplied(
            plan = MemoryDreamPlan(archiveMemoryIds = listOf(4)),
            source = MemoryDreamPlanSource.AUTO,
            now = 800L,
        )
        assertEquals(MemoryDreamPlanStatus.DISMISSED.wireName, dao.find(pending.id)?.status)
        assertEquals(MemoryDreamPlanStatus.APPLIED.wireName, dao.find(applied.id)?.status)
        assertEquals(800L, dao.find(applied.id)?.appliedAt)
        assertNull(store.getPendingPlan())
    }
}

private class FakeMemoryDreamPlanDao : MemoryDreamPlanDAO {
    private val plans = mutableListOf<MemoryDreamPlanEntity>()
    private val pendingFlow = MutableStateFlow<MemoryDreamPlanEntity?>(null)

    override fun getPendingPlanFlow(): Flow<MemoryDreamPlanEntity?> = pendingFlow

    override suspend fun getPendingPlan(): MemoryDreamPlanEntity? = pendingFlow.value

    override suspend fun countPlansSince(source: String, createdAfter: Long): Int =
        plans.count { it.source == source && it.createdAt >= createdAfter }

    override suspend fun updatePendingStatus(status: String, dismissedAt: Long) {
        plans.replaceAll { plan ->
            if (plan.status == MemoryDreamPlanStatus.PENDING.wireName) {
                plan.copy(status = status, dismissedAt = dismissedAt)
            } else {
                plan
            }
        }
        refreshPending()
    }

    override suspend fun markApplied(id: String, appliedAt: Long) {
        plans.replaceAll { plan ->
            if (plan.id == id) {
                plan.copy(status = MemoryDreamPlanStatus.APPLIED.wireName, appliedAt = appliedAt)
            } else {
                plan
            }
        }
        refreshPending()
    }

    override suspend fun markDismissed(id: String, dismissedAt: Long) {
        plans.replaceAll { plan ->
            if (plan.id == id) {
                plan.copy(status = MemoryDreamPlanStatus.DISMISSED.wireName, dismissedAt = dismissedAt)
            } else {
                plan
            }
        }
        refreshPending()
    }

    override suspend fun insert(plan: MemoryDreamPlanEntity) {
        plans.removeAll { it.id == plan.id }
        plans.add(plan)
        refreshPending()
    }

    fun find(id: String): MemoryDreamPlanEntity? = plans.firstOrNull { it.id == id }

    private fun refreshPending() {
        pendingFlow.value = plans
            .filter { it.status == MemoryDreamPlanStatus.PENDING.wireName }
            .maxByOrNull { it.createdAt }
    }
}
