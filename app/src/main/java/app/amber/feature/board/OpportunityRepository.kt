package app.amber.feature.board

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.dao.FeishuDocDependencyDAO
import app.amber.agent.data.db.dao.OpportunityDao
import app.amber.agent.data.db.dao.ReferenceAnchorDao
import app.amber.agent.data.db.entity.BoardTaskEntity
import app.amber.agent.data.db.entity.OpportunityEntity
import app.amber.agent.data.db.entity.OpportunityStatus
import app.amber.agent.data.db.entity.OpportunityType
import app.amber.agent.data.db.entity.ReferenceAnchorEntity
import app.amber.agent.data.db.entity.ReferenceAnchorStatus
import app.amber.agent.data.db.entity.stableBoardTaskId
import app.amber.agent.data.db.entity.stableOpportunityId
import kotlin.math.roundToInt

interface OpportunityScanner {
    suspend fun scan(boardDate: String): Int
}

class OpportunityRepository(
    private val database: AppDatabase,
    private val opportunityDao: OpportunityDao,
    private val taskRepository: BoardTaskRepository,
    private val boardRepository: BoardRepository,
    private val dependencyDao: FeishuDocDependencyDAO,
) {
    fun observeSuggested(now: Long = System.currentTimeMillis()): Flow<List<OpportunityEntity>> =
        opportunityDao.observeSuggested(now)

    suspend fun getSuggested(limit: Int = 20, now: Long = System.currentTimeMillis()): List<OpportunityEntity> =
        opportunityDao.getSuggested(now, limit)

    suspend fun getOpportunity(id: String): OpportunityEntity? =
        opportunityDao.getById(id)

    suspend fun upsertSuggested(opportunity: OpportunityEntity): OpportunityEntity? {
        if (opportunity.status != OpportunityStatus.SUGGESTED) {
            opportunityDao.upsert(opportunity)
            return opportunity
        }
        if (opportunityDao.hasMutedType(opportunity.opportunityType) > 0) {
            return null
        }
        val existing = opportunityDao.getByDedupeKey(opportunity.dedupeKey)
        if (existing != null && existing.status != OpportunityStatus.SUGGESTED) {
            return null
        }
        val now = System.currentTimeMillis()
        val entity = (existing ?: opportunity).copy(
            title = opportunity.title,
            summary = opportunity.summary,
            evidenceJson = opportunity.evidenceJson,
            scoreJson = opportunity.scoreJson,
            confidence = opportunity.confidence,
            suggestedActionsJson = opportunity.suggestedActionsJson,
            dueAt = opportunity.dueAt,
            triggerAt = opportunity.triggerAt,
            expiresAt = opportunity.expiresAt,
            updatedAt = now,
        )
        opportunityDao.upsert(entity)
        return entity
    }

    suspend fun dismiss(id: String, reason: String? = null) {
        opportunityDao.dismiss(id, reason, System.currentTimeMillis())
    }

    suspend fun mute(id: String, scope: String = "type") {
        val now = System.currentTimeMillis()
        if (scope == "type") {
            val opportunity = opportunityDao.getById(id) ?: return
            opportunityDao.muteType(opportunity.opportunityType, now)
        } else {
            opportunityDao.mute(id, scope, now)
        }
    }

    suspend fun dispatch(id: String): BoardTaskEntity? = database.withTransaction {
        val opportunity = opportunityDao.getById(id) ?: return@withTransaction null
        if (opportunity.status != OpportunityStatus.SUGGESTED) return@withTransaction null
        val metadata = buildJsonObject {
            put("origin", "opportunity:${opportunity.id}")
            put("opportunity_type", opportunity.opportunityType)
            put("opportunity_id", opportunity.id)
            put("evidence_json", JsonPrimitive(opportunity.evidenceJson))
        }.toString()
        val taskId = stableBoardTaskId("opportunity", opportunity.id)
        val updated = opportunityDao.markDispatched(opportunity.id, taskId, System.currentTimeMillis())
        if (updated == 0) return@withTransaction null
        taskRepository.createDispatched(
            sourceType = "opportunity",
            sourceRef = opportunity.id,
            title = opportunity.title,
            summary = opportunity.summary,
            riskLevel = if (opportunity.opportunityType == OpportunityType.DEPENDENCY_STALE) "medium" else "low",
            displayBoardDate = boardRepository.todayBoardDate(),
            metadataJson = metadata,
        )
    }

    suspend fun expireSuggested(now: Long = System.currentTimeMillis()): Int =
        opportunityDao.expire(now)

    suspend fun pruneOldTerminal(cutoffMs: Long): Int =
        opportunityDao.deleteOldTerminal(cutoffMs)

    suspend fun dependencyById(id: String) =
        dependencyDao.getById(id)
}

class ReferenceAnchorRepository(
    private val anchorDao: ReferenceAnchorDao,
) {
    suspend fun upsert(anchor: ReferenceAnchorEntity) =
        anchorDao.upsert(anchor)

    suspend fun activeByUpstream(upstreamDocRef: String, limit: Int = MAX_ANCHORS_PER_DOC): List<ReferenceAnchorEntity> =
        anchorDao.getActiveByUpstream(upstreamDocRef, limit)

    suspend fun markDrifted(anchor: ReferenceAnchorEntity, lastValue: String, evidenceJson: String, scoreJson: String) {
        anchorDao.markDrifted(anchor.id, lastValue, evidenceJson, scoreJson, System.currentTimeMillis())
    }

    suspend fun ack(anchorId: String) =
        anchorDao.ack(anchorId, System.currentTimeMillis())

    companion object {
        private const val MAX_ANCHORS_PER_DOC = 30
    }
}

fun opportunity(
    type: String,
    sourceType: String,
    sourceRef: String,
    title: String,
    summary: String,
    evidenceJson: String,
    scoreJson: String,
    confidence: Float,
    suggestedActionsJson: String,
    dueAt: Long? = null,
    triggerAt: Long? = null,
    expiresAt: Long? = null,
): OpportunityEntity {
    val dedupeKey = "$type|$sourceType:$sourceRef"
    val now = System.currentTimeMillis()
    return OpportunityEntity(
        id = stableOpportunityId(dedupeKey),
        dedupeKey = dedupeKey,
        opportunityType = type,
        sourceType = sourceType,
        sourceRef = sourceRef,
        title = title.take(180),
        summary = summary.take(600),
        evidenceJson = evidenceJson,
        scoreJson = scoreJson,
        confidence = confidence.coerceIn(0f, 1f),
        suggestedActionsJson = suggestedActionsJson,
        dueAt = dueAt,
        triggerAt = triggerAt,
        expiresAt = expiresAt,
        createdAt = now,
        updatedAt = now,
    )
}

fun scoreJson(vararg scores: Pair<String, Int>): String =
    buildJsonObject {
        var total = 0
        scores.forEach { (key, value) ->
            total += value
            put(key, value)
        }
        put("total", total)
        put("confidence", (total.coerceIn(0, 100).toFloat() / 100f))
    }.toString()

fun confidenceFromScore(score: Int): Float =
    (score.coerceIn(0, 100).toFloat() / 100f * 100).roundToInt() / 100f

fun evidenceJson(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
    buildJsonObject {
        putJsonObject("evidence") {
            builder()
        }
    }.toString()
