package app.amber.core.memory.dream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import app.amber.agent.data.db.dao.MemoryDreamPlanDAO
import app.amber.agent.data.db.entity.MemoryDreamPlanEntity
import kotlin.uuid.Uuid

enum class MemoryDreamPlanStatus(val wireName: String) {
    PENDING("pending"),
    APPLIED("applied"),
    DISMISSED("dismissed"),
}

enum class MemoryDreamPlanSource(val wireName: String) {
    MANUAL("manual"),
    AUTO("auto"),
}

data class PersistedMemoryDreamPlan(
    val id: String,
    val plan: MemoryDreamPlan,
    val status: MemoryDreamPlanStatus,
    val source: MemoryDreamPlanSource,
    val createdAt: Long,
    val appliedAt: Long?,
    val dismissedAt: Long?,
) {
    val summary: String
        get() = "合并 ${plan.mergeSuggestions.size} · 提升 ${plan.promoteMemoryIds.size} · " +
            "归档 ${plan.archiveMemoryIds.size} · 替换 ${plan.supersedeSuggestions.size} · " +
            "忽略候选 ${plan.ignoreCandidateIds.size}"
}

class MemoryDreamPlanStore(
    private val dao: MemoryDreamPlanDAO,
    private val json: Json,
) {
    val pendingPlanFlow: Flow<PersistedMemoryDreamPlan?> =
        dao.getPendingPlanFlow().map { entity -> entity?.toPersisted() }

    suspend fun getPendingPlan(): PersistedMemoryDreamPlan? =
        dao.getPendingPlan()?.toPersisted()

    suspend fun countAutoPlansSince(createdAfter: Long): Int =
        dao.countPlansSince(MemoryDreamPlanSource.AUTO.wireName, createdAfter)

    suspend fun savePending(
        plan: MemoryDreamPlan,
        source: MemoryDreamPlanSource,
        now: Long = System.currentTimeMillis(),
    ): PersistedMemoryDreamPlan {
        dao.updatePendingStatus(MemoryDreamPlanStatus.DISMISSED.wireName, now)
        val entity = MemoryDreamPlanEntity(
            id = Uuid.random().toString(),
            planJson = json.encodeToString(MemoryDreamPlan.serializer(), plan),
            status = MemoryDreamPlanStatus.PENDING.wireName,
            source = source.wireName,
            mergeCount = plan.mergeSuggestions.size,
            promoteCount = plan.promoteMemoryIds.size,
            archiveCount = plan.archiveMemoryIds.size,
            supersedeCount = plan.supersedeSuggestions.size,
            ignoreCandidateCount = plan.ignoreCandidateIds.size,
            createdAt = now,
            appliedAt = null,
            dismissedAt = null,
        )
        dao.insert(entity)
        return entity.toPersisted()
    }

    suspend fun recordAutoRun(
        plan: MemoryDreamPlan,
        now: Long = System.currentTimeMillis(),
    ) {
        dao.insert(
            MemoryDreamPlanEntity(
                id = Uuid.random().toString(),
                planJson = json.encodeToString(MemoryDreamPlan.serializer(), plan),
                status = MemoryDreamPlanStatus.DISMISSED.wireName,
                source = MemoryDreamPlanSource.AUTO.wireName,
                mergeCount = plan.mergeSuggestions.size,
                promoteCount = plan.promoteMemoryIds.size,
                archiveCount = plan.archiveMemoryIds.size,
                supersedeCount = plan.supersedeSuggestions.size,
                ignoreCandidateCount = plan.ignoreCandidateIds.size,
                createdAt = now,
                appliedAt = null,
                dismissedAt = now,
            )
        )
    }

    suspend fun saveApplied(
        plan: MemoryDreamPlan,
        source: MemoryDreamPlanSource,
        now: Long = System.currentTimeMillis(),
    ): PersistedMemoryDreamPlan {
        dao.updatePendingStatus(MemoryDreamPlanStatus.DISMISSED.wireName, now)
        val entity = MemoryDreamPlanEntity(
            id = Uuid.random().toString(),
            planJson = json.encodeToString(MemoryDreamPlan.serializer(), plan),
            status = MemoryDreamPlanStatus.APPLIED.wireName,
            source = source.wireName,
            mergeCount = plan.mergeSuggestions.size,
            promoteCount = plan.promoteMemoryIds.size,
            archiveCount = plan.archiveMemoryIds.size,
            supersedeCount = plan.supersedeSuggestions.size,
            ignoreCandidateCount = plan.ignoreCandidateIds.size,
            createdAt = now,
            appliedAt = now,
            dismissedAt = null,
        )
        dao.insert(entity)
        return entity.toPersisted()
    }

    suspend fun markApplied(id: String, now: Long = System.currentTimeMillis()) {
        dao.markApplied(id, now)
    }

    suspend fun markDismissed(id: String, now: Long = System.currentTimeMillis()) {
        dao.markDismissed(id, now)
    }

    private fun MemoryDreamPlanEntity.toPersisted(): PersistedMemoryDreamPlan =
        PersistedMemoryDreamPlan(
            id = id,
            plan = json.decodeFromString(MemoryDreamPlan.serializer(), planJson),
            status = status.toStatus(),
            source = source.toSource(),
            createdAt = createdAt,
            appliedAt = appliedAt,
            dismissedAt = dismissedAt,
        )

    private fun String.toStatus(): MemoryDreamPlanStatus =
        MemoryDreamPlanStatus.entries.firstOrNull { it.wireName == this } ?: MemoryDreamPlanStatus.PENDING

    private fun String.toSource(): MemoryDreamPlanSource =
        MemoryDreamPlanSource.entries.firstOrNull { it.wireName == this } ?: MemoryDreamPlanSource.MANUAL
}
