package app.amber.core.memory.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.dao.MemoryCandidateDAO
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.dao.MemoryEventDAO
import app.amber.agent.data.db.entity.MemoryCandidateEntity
import app.amber.agent.data.db.entity.MemoryEntity
import app.amber.agent.data.db.entity.MemoryEventEntity
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryCandidateStatus
import app.amber.core.memory.model.MemoryEvent
import app.amber.core.memory.model.MemoryEventType
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.model.AssistantMemory
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.utils.JsonInstant
import app.amber.feature.runtime.ContentDigest

open class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val candidateDAO: MemoryCandidateDAO,
    private val eventDAO: MemoryEventDAO,
    // 生产路径经 DI 恒为非 null；默认 null 仅兼容构造纯 Fake DAO 的单元测试
    private val appDatabase: AppDatabase? = null,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val SHORT_TERM_MEMORY_ID = "__short_term__"
        const val LONG_TERM_MEMORY_ID = "__long_term__"

        /** Trigger label for tool-driven writes (P2-06 provenance). */
        const val TRIGGER_TOOL = "tool"

        /** Trigger label for automatic extraction writes (P2-06 provenance). */
        const val TRIGGER_AUTO_EXTRACTION = "auto_extraction"
        const val TRIGGER_DREAM = "dream"

        /**
         * Prefix MemoryExtractor puts on a pending candidate's reason to carry
         * the model's intent toward an existing record. acceptCandidate parses
         * it back: "updates" supersedes (archive old + new version),
         * "invalidates" archives the target, "confirms" reinforces it.
         */
        private val INTENT_TARGET_REASON = Regex("^(updates|invalidates|confirms) memory #(\\d+)")
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId).map { entities ->
            entities.map { it.toAssistantMemory() }
        }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toAssistantMemory() }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID).map { entities ->
            entities.map { it.toAssistantMemory() }
        }

    fun getMemoryCountsFlow(): Flow<Map<String, Int>> =
        memoryDAO.getMemoryCountsFlow().map { rows ->
            rows.associate { it.assistantId to it.count }
        }

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID).map { it.toAssistantMemory() }

    /**
     * Read one memory for an edit/delete conflict without asking callers to
     * scan every bucket. The returned revision is the value a subsequent CAS
     * must bind to.
     */
    suspend fun getMemoryById(id: Int): AssistantMemory? =
        memoryDAO.getMemoryById(id)?.toAssistantMemory()

    fun getShortTermMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(SHORT_TERM_MEMORY_ID).map { entities ->
            entities.map { it.toAssistantMemory() }
        }

    suspend fun getShortTermMemories(): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(SHORT_TERM_MEMORY_ID).map { it.toAssistantMemory() }

    fun getLongTermMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(LONG_TERM_MEMORY_ID).map { entities ->
            entities.map { it.toAssistantMemory() }
        }

    suspend fun getLongTermMemories(): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(LONG_TERM_MEMORY_ID).map { it.toAssistantMemory() }

    open suspend fun getActiveRecords(scopes: Set<MemoryScope>, now: Long = System.currentTimeMillis()): List<MemoryRecord> {
        if (scopes.isEmpty()) return emptyList()
        return memoryDAO.getActiveMemoriesByScopes(scopes.map { it.wireName }, now).map { it.toRecord() }
    }

    suspend fun getAllActiveRecords(now: Long = System.currentTimeMillis()): List<MemoryRecord> =
        memoryDAO.getActiveMemories(now).map { it.toRecord() }

    suspend fun getAllRecords(): List<MemoryRecord> =
        memoryDAO.getAllMemories().map { it.toRecord() }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) = withMemoryWriter {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(
        id: Int,
        content: String,
        expectedRevision: Long? = null,
    ): AssistantMemory {
        if (expectedRevision != null) {
            return updateContentCas(
                id = id,
                content = content,
                expectedRevision = expectedRevision,
            ).memory
        }
        return withMemoryWriter {
            memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
            val affected = memoryDAO.updateContentBlind(
                id = id,
                content = content,
                updatedAt = System.currentTimeMillis(),
            )
            check(affected > 0) { "Memory record #$id not found" }
            memoryDAO.getMemoryById(id)?.toAssistantMemory()
                ?: error("Memory record #$id not found after update")
        }
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val scope = scopeForBucket(assistantId)
        val kind = if (scope == MemoryScope.SHORT_TERM) MemoryKind.PROJECT else MemoryKind.NOTE
        return addMemory(
            scope = scope,
            kind = kind,
            content = content,
            assistantId = assistantId,
        ).toAssistantMemory()
    }

    suspend fun addMemory(
        scope: MemoryScope,
        kind: MemoryKind,
        content: String,
        assistantId: String = bucketForScope(scope),
        sourceConversationId: String? = null,
        sourceMessageIds: List<String> = emptyList(),
        supersedesIds: List<Int> = emptyList(),
        expiresAt: Long? = null,
        confidence: Float = 1f,
        pinned: Boolean = false,
        sourceRunId: String? = null,
        sourceTrigger: String? = null,
        topicTitle: String? = null,
        memberIds: List<Int> = emptyList(),
    ): MemoryRecord = withMemoryWriter {
        addMemoryInternal(
            scope = scope,
            kind = kind,
            content = content,
            assistantId = assistantId,
            sourceConversationId = sourceConversationId,
            sourceMessageIds = sourceMessageIds,
            supersedesIds = supersedesIds,
            expiresAt = expiresAt,
            confidence = confidence,
            pinned = pinned,
            sourceRunId = sourceRunId,
            sourceTrigger = sourceTrigger,
            topicTitle = topicTitle,
            memberIds = memberIds,
        )
    }

    private suspend fun addMemoryInternal(
        scope: MemoryScope,
        kind: MemoryKind,
        content: String,
        assistantId: String,
        sourceConversationId: String? = null,
        sourceMessageIds: List<String> = emptyList(),
        supersedesIds: List<Int> = emptyList(),
        expiresAt: Long? = null,
        confidence: Float = 1f,
        pinned: Boolean = false,
        sourceRunId: String? = null,
        sourceTrigger: String? = null,
        topicTitle: String? = null,
        memberIds: List<Int> = emptyList(),
        lastUsedAt: Long? = null,
        useCount: Int = 0,
    ): MemoryRecord {
        val now = System.currentTimeMillis()
        val id = memoryDAO.insertMemory(
            MemoryEntity(
                assistantId = assistantId,
                content = content,
                scope = scope.wireName,
                kind = kind.wireName,
                sourceConversationId = sourceConversationId,
                sourceMessageIdsJson = JsonInstant.encodeToString(sourceMessageIds),
                supersedesIdsJson = JsonInstant.encodeToString(supersedesIds.distinct()),
                expiresAt = expiresAt,
                confidence = confidence.coerceIn(0f, 1f),
                pinned = pinned,
                archived = false,
                createdAt = now,
                updatedAt = now,
                revision = 1,
                sourceRunId = sourceRunId,
                sourceTrigger = sourceTrigger,
                topicTitle = topicTitle,
                memberIdsJson = JsonInstant.encodeToString(memberIds.distinct()),
                lastUsedAt = lastUsedAt,
                useCount = useCount,
            )
        ).toInt()
        return memoryDAO.getMemoryById(id)?.toRecord() ?: error("Created memory #$id not found")
    }

    suspend fun upsertRecord(record: MemoryRecord): MemoryRecord = withMemoryWriter {
        upsertRecordInternal(record)
    }

    private suspend fun upsertRecordInternal(record: MemoryRecord): MemoryRecord {
        val entity = record.toEntity()
        if (record.id == 0) {
            val id = memoryDAO.insertMemory(entity).toInt()
            return memoryDAO.getMemoryById(id)?.toRecord() ?: record.copy(id = id)
        }
        val updatedAt = System.currentTimeMillis()
        val affected = memoryDAO.updateRecordCas(
            id = entity.id,
            assistantId = entity.assistantId,
            content = entity.content,
            scope = entity.scope,
            kind = entity.kind,
            sourceConversationId = entity.sourceConversationId,
            sourceMessageIdsJson = entity.sourceMessageIdsJson,
            supersedesIdsJson = entity.supersedesIdsJson,
            expiresAt = entity.expiresAt,
            confidence = entity.confidence,
            pinned = entity.pinned,
            archived = entity.archived,
            createdAt = entity.createdAt,
            updatedAt = updatedAt,
            lastUsedAt = entity.lastUsedAt,
            sourceRunId = entity.sourceRunId,
            sourceTrigger = entity.sourceTrigger,
            topicTitle = entity.topicTitle,
            memberIdsJson = entity.memberIdsJson,
            useCount = entity.useCount,
            expectedRevision = entity.revision,
        )
        if (affected == 0) {
            val current = memoryDAO.getMemoryById(record.id)
            throw MemoryStaleException(record.id, entity.revision, current?.revision ?: 0)
        }
        return memoryDAO.getMemoryById(record.id)?.toRecord() ?: record
    }

    suspend fun deleteMemory(id: Int) = withMemoryWriter {
        memoryDAO.deleteMemory(id)
    }

    // ---- P2-06 memory write CAS and pollution audit ----

    /**
     * Compare-and-set content update. The revision the approval was bound to
     * must still match: otherwise the write is rejected (0 rows affected) and
     * the caller must re-generate the diff instead of overwriting. The
     * provenance markers record which run/trigger performed the write. The
     * result carries old/new content digests (hashes only) for the audit.
     */
    suspend fun updateContentCas(
        id: Int,
        content: String,
        expectedRevision: Long,
        sourceRunId: String? = null,
        sourceTrigger: String? = null,
    ): MemoryCasUpdateResult = withMemoryWriter {
        updateContentCasInternal(
            id = id,
            content = content,
            expectedRevision = expectedRevision,
            sourceRunId = sourceRunId,
            sourceTrigger = sourceTrigger,
        )
    }

    private suspend fun updateContentCasInternal(
        id: Int,
        content: String,
        expectedRevision: Long,
        sourceRunId: String?,
        sourceTrigger: String?,
    ): MemoryCasUpdateResult {
        // A record that vanished since the caller's snapshot is "stale" in the
        // same sense as a revision mismatch — surface it uniformly so callers
        // can fall back to review instead of aborting a batch.
        val old = memoryDAO.getMemoryById(id)
            ?: throw MemoryStaleException(id, expectedRevision, actualRevision = 0)
        // Topic content is dream-synthesized; a by-id content rewrite would let
        // it drift from the member set. Topics change only via the dream applier.
        require(old.kind != MemoryKind.TOPIC.wireName) {
            "Memory record #$id is a dream-managed topic; content edits are rejected."
        }
        val updatedAt = System.currentTimeMillis()
        val affected = memoryDAO.updateContentCas(
            id = id,
            content = content,
            expectedRevision = expectedRevision,
            updatedAt = updatedAt,
            sourceRunId = sourceRunId,
            sourceTrigger = sourceTrigger,
        )
        if (affected == 0) {
            val current = memoryDAO.getMemoryById(id)
            throw MemoryStaleException(id, expectedRevision, current?.revision ?: 0)
        }
        val updated = memoryDAO.getMemoryById(id)?.toAssistantMemory()
            ?: error("Memory record #$id not found after update")
        return MemoryCasUpdateResult(
            memory = updated,
            oldDigest = ContentDigest.sha256(old.content),
            newDigest = ContentDigest.sha256(updated.content),
        )
    }

    /**
     * Settings-page edit: replace content and its editable classification in
     * one full-record CAS. Moving scope also moves the assistant bucket so
     * the three library flows stay consistent with the stored scope.
     */
    suspend fun updateMemoryCas(memory: AssistantMemory): MemoryCasUpdateResult = withMemoryWriter {
        updateMemoryCasInternal(memory)
    }

    private suspend fun updateMemoryCasInternal(memory: AssistantMemory): MemoryCasUpdateResult {
        val old = memoryDAO.getMemoryById(memory.id) ?: error("Memory record #${memory.id} not found")
        val updatedAt = System.currentTimeMillis()
        val affected = memoryDAO.updateRecordCas(
            id = old.id,
            assistantId = bucketForScope(memory.scope),
            content = memory.content,
            scope = memory.scope.wireName,
            kind = old.kind,
            sourceConversationId = old.sourceConversationId,
            sourceMessageIdsJson = old.sourceMessageIdsJson,
            supersedesIdsJson = old.supersedesIdsJson,
            expiresAt = old.expiresAt,
            confidence = old.confidence,
            pinned = memory.pinned,
            archived = old.archived,
            createdAt = old.createdAt,
            updatedAt = updatedAt,
            lastUsedAt = old.lastUsedAt,
            sourceRunId = old.sourceRunId,
            sourceTrigger = old.sourceTrigger,
            topicTitle = old.topicTitle,
            memberIdsJson = old.memberIdsJson,
            useCount = old.useCount,
            expectedRevision = memory.revision,
        )
        if (affected == 0) {
            val current = memoryDAO.getMemoryById(memory.id)
            throw MemoryStaleException(memory.id, memory.revision, current?.revision ?: 0)
        }
        val updated = memoryDAO.getMemoryById(memory.id)?.toAssistantMemory()
            ?: error("Memory record #${memory.id} not found after update")
        return MemoryCasUpdateResult(
            memory = updated,
            oldDigest = ContentDigest.sha256(old.content),
            newDigest = ContentDigest.sha256(updated.content),
        )
    }

    /**
     * Compare-and-set delete. The revision the approval was bound to must
     * still match, otherwise the delete is rejected (no blind removal).
     * Returns the digest of the removed content for the audit trail.
     */
    suspend fun deleteMemoryCas(id: Int, expectedRevision: Long): MemoryCasDeleteResult = withMemoryWriter {
        deleteMemoryCasInternal(id, expectedRevision)
    }

    private suspend fun deleteMemoryCasInternal(id: Int, expectedRevision: Long): MemoryCasDeleteResult {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        if (old.revision != expectedRevision) {
            throw MemoryStaleException(id, expectedRevision, old.revision)
        }
        val affected = memoryDAO.deleteCas(id, expectedRevision)
        if (affected == 0) {
            val current = memoryDAO.getMemoryById(id)
            throw MemoryStaleException(id, expectedRevision, current?.revision ?: 0)
        }
        return MemoryCasDeleteResult(
            memoryId = id,
            oldDigest = ContentDigest.sha256(old.content),
        )
    }

    /** Current revision of a memory record, or null when it does not exist. */
    suspend fun memoryRevision(id: Int): Long? = memoryDAO.revisionOf(id)

    open suspend fun touchMemories(ids: List<Int>, usedAt: Long = System.currentTimeMillis()) = withMemoryWriter {
        if (ids.isNotEmpty()) {
            memoryDAO.touchMemories(ids, usedAt)
        }
    }

    /** Reinforce a memory without rewriting it (extraction "confirm" action). */
    suspend fun reinforceMemory(id: Int, usedAt: Long = System.currentTimeMillis()) =
        touchMemories(listOf(id), usedAt)

    /** Archived records visible to the library's 已归档 section. */
    fun getArchivedMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getArchivedMemoriesFlow().map { entities ->
            entities.map { it.toAssistantMemory() }
        }

    /**
     * Archive one record with a CAS bound to [expectedRevision]; a revision
     * mismatch throws [MemoryStaleException] instead of touching the newer row.
     */
    suspend fun archiveMemoryCas(
        id: Int,
        expectedRevision: Long,
        sourceRunId: String? = null,
        sourceTrigger: String? = null,
    ): MemoryRecord = withMemoryWriter {
        val old = memoryDAO.getMemoryById(id)
            ?: throw MemoryStaleException(id, expectedRevision, actualRevision = 0)
        if (old.archived) return@withMemoryWriter old.toRecord()
        archiveInternal(old, expectedRevision, sourceRunId, sourceTrigger).toRecord()
    }

    /**
     * Restore an archived record. A stale expiresAt is cleared on restore so
     * the revived row is not re-archived by the next maintenance pass.
     */
    suspend fun restoreMemory(id: Int, expectedRevision: Long): MemoryRecord = withMemoryWriter {
        val old = memoryDAO.getMemoryById(id)
            ?: throw MemoryStaleException(id, expectedRevision, actualRevision = 0)
        val now = System.currentTimeMillis()
        val affected = memoryDAO.updateRecordCas(
            id = old.id,
            assistantId = old.assistantId,
            content = old.content,
            scope = old.scope,
            kind = old.kind,
            sourceConversationId = old.sourceConversationId,
            sourceMessageIdsJson = old.sourceMessageIdsJson,
            supersedesIdsJson = old.supersedesIdsJson,
            expiresAt = old.expiresAt?.takeIf { it > now },
            confidence = old.confidence,
            pinned = old.pinned,
            archived = false,
            createdAt = old.createdAt,
            updatedAt = now,
            lastUsedAt = old.lastUsedAt,
            sourceRunId = old.sourceRunId,
            sourceTrigger = old.sourceTrigger,
            topicTitle = old.topicTitle,
            memberIdsJson = old.memberIdsJson,
            useCount = old.useCount,
            expectedRevision = expectedRevision,
        )
        if (affected == 0) {
            val current = memoryDAO.getMemoryById(id)
            throw MemoryStaleException(id, expectedRevision, current?.revision ?: 0)
        }
        memoryDAO.getMemoryById(id)?.toRecord() ?: error("Memory record #$id not found after restore")
    }

    /**
     * Supersede-style update: archive the old record and insert a new version
     * linked through supersedesIds, so the previous wording stays recoverable
     * in the archive. The archive is CAS-bound to the snapshot the caller saw;
     * a stale snapshot throws [MemoryStaleException] before any row changes.
     */
    suspend fun supersedeMemory(
        targetId: Int,
        newContent: String,
        expectedRevision: Long,
        confidence: Float? = null,
        expiresAt: Long? = null,
        sourceConversationId: String? = null,
        sourceMessageIds: List<String>? = null,
        sourceRunId: String? = null,
        sourceTrigger: String? = null,
    ): MemoryRecord = withMemoryWriter {
        val db = appDatabase
        if (db == null) {
            supersedeInternal(
                targetId, newContent, expectedRevision, confidence, expiresAt,
                sourceConversationId, sourceMessageIds, sourceRunId, sourceTrigger,
            )
        } else {
            db.withTransaction {
                supersedeInternal(
                    targetId, newContent, expectedRevision, confidence, expiresAt,
                    sourceConversationId, sourceMessageIds, sourceRunId, sourceTrigger,
                )
            }
        }
    }

    private suspend fun supersedeInternal(
        targetId: Int,
        newContent: String,
        expectedRevision: Long,
        confidence: Float?,
        expiresAt: Long?,
        sourceConversationId: String?,
        sourceMessageIds: List<String>?,
        sourceRunId: String?,
        sourceTrigger: String?,
    ): MemoryRecord {
        val old = memoryDAO.getMemoryById(targetId)
            ?: throw MemoryStaleException(targetId, expectedRevision, actualRevision = 0)
        require(old.kind != MemoryKind.TOPIC.wireName) {
            "Memory record #$targetId is a dream-managed topic; supersede is rejected."
        }
        require(!old.archived) { "Memory record #$targetId is already archived." }
        // Reject the stale snapshot before any write — outside a transaction
        // (test fakes without AppDatabase) an inserted-then-failed archive
        // would orphan the replacement.
        if (old.revision != expectedRevision) {
            throw MemoryStaleException(targetId, expectedRevision, old.revision)
        }
        val created = addMemoryInternal(
            scope = MemoryScope.fromWireName(old.scope),
            kind = MemoryKind.fromWireName(old.kind),
            content = newContent,
            assistantId = old.assistantId,
            sourceConversationId = sourceConversationId ?: old.sourceConversationId,
            sourceMessageIds = sourceMessageIds ?: decodeStringList(old.sourceMessageIdsJson),
            supersedesIds = listOf(old.id),
            // A superseded record whose expiry already passed must not birth
            // a newborn zombie — the new version is fresh information.
            expiresAt = expiresAt ?: old.expiresAt?.takeIf { it > System.currentTimeMillis() },
            confidence = confidence ?: old.confidence,
            pinned = old.pinned,
            sourceRunId = sourceRunId ?: old.sourceRunId,
            sourceTrigger = sourceTrigger ?: old.sourceTrigger,
            // The replacement inherits the old record's reinforcement evidence:
            // promotion credit must survive a version rewrite.
            lastUsedAt = old.lastUsedAt,
            useCount = old.useCount,
        )
        archiveInternal(old, expectedRevision, sourceRunId, sourceTrigger)
        return created
    }

    private suspend fun archiveInternal(
        old: MemoryEntity,
        expectedRevision: Long,
        sourceRunId: String?,
        sourceTrigger: String?,
    ): MemoryEntity {
        val affected = memoryDAO.updateRecordCas(
            id = old.id,
            assistantId = old.assistantId,
            content = old.content,
            scope = old.scope,
            kind = old.kind,
            sourceConversationId = old.sourceConversationId,
            sourceMessageIdsJson = old.sourceMessageIdsJson,
            supersedesIdsJson = old.supersedesIdsJson,
            expiresAt = old.expiresAt,
            confidence = old.confidence,
            pinned = old.pinned,
            archived = true,
            createdAt = old.createdAt,
            updatedAt = System.currentTimeMillis(),
            lastUsedAt = old.lastUsedAt,
            sourceRunId = sourceRunId ?: old.sourceRunId,
            sourceTrigger = sourceTrigger ?: old.sourceTrigger,
            topicTitle = old.topicTitle,
            memberIdsJson = old.memberIdsJson,
            useCount = old.useCount,
            expectedRevision = expectedRevision,
        )
        if (affected == 0) {
            val current = memoryDAO.getMemoryById(old.id)
            throw MemoryStaleException(old.id, expectedRevision, current?.revision ?: 0)
        }
        return memoryDAO.getMemoryById(old.id) ?: old
    }

    fun getPendingCandidatesFlow(): Flow<List<MemoryCandidate>> =
        candidateDAO.getCandidatesByStatusFlow(MemoryCandidateStatus.PENDING.wireName).map { list ->
            list.map { it.toCandidate() }
        }

    fun getPendingCandidateCountFlow(): Flow<Int> =
        candidateDAO.countCandidatesByStatusFlow(MemoryCandidateStatus.PENDING.wireName)

    suspend fun getPendingCandidates(): List<MemoryCandidate> =
        candidateDAO.getCandidatesByStatus(MemoryCandidateStatus.PENDING.wireName).map { it.toCandidate() }

    suspend fun getAllCandidates(): List<MemoryCandidate> =
        candidateDAO.getAllCandidates().map { it.toCandidate() }

    suspend fun addCandidate(candidate: MemoryCandidate) = withMemoryWriter {
        candidateDAO.insert(candidate.toEntity())
    }

    suspend fun addCandidates(candidates: List<MemoryCandidate>) = withMemoryWriter {
        if (candidates.isNotEmpty()) {
            candidateDAO.insertAll(candidates.map { it.toEntity() })
        }
    }

    suspend fun updateCandidate(candidate: MemoryCandidate) = withMemoryWriter {
        updateCandidateInternal(candidate)
    }

    private suspend fun updateCandidateInternal(candidate: MemoryCandidate) {
        candidateDAO.update(candidate.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    suspend fun acceptCandidate(id: String): MemoryRecord = withMemoryWriter {
        val db = appDatabase
        if (db != null) {
            db.withTransaction { acceptCandidateInternal(id) }
        } else {
            acceptCandidateInternal(id)
        }
    }

    private suspend fun acceptCandidateInternal(id: String): MemoryRecord {
            val candidate = candidateDAO.getCandidateById(id)?.toCandidate()
                ?: error("Memory candidate #$id not found")
            check(candidate.status == MemoryCandidateStatus.PENDING) {
                "Memory candidate #$id is already ${candidate.status.wireName}"
            }
            // A candidate carrying an intent prefix ("updates|invalidates|
            // confirms memory #N: ...", emitted by MemoryExtractor) is applied
            // to the target so accepting it cannot duplicate the still-live
            // record. The target's current revision is used because the
            // reviewer's approval applies to the record as it stands now; a
            // stale write aborts and keeps the candidate pending instead of
            // inserting a duplicate.
            val intent = INTENT_TARGET_REASON.find(candidate.reason)
            val intentTarget = intent
                ?.groupValues?.get(2)?.toIntOrNull()
                ?.let { memoryDAO.getMemoryById(it) }
            val targetMutable = intentTarget != null &&
                !intentTarget.archived &&
                intentTarget.scope != MemoryScope.CORE.wireName &&
                intentTarget.kind != MemoryKind.TOPIC.wireName
            val record = when (intent?.groupValues?.get(1)) {
                "invalidates" -> if (targetMutable) {
                    archiveInternal(intentTarget!!, intentTarget.revision, candidate.sourceConversationId, TRIGGER_AUTO_EXTRACTION)
                        .toRecord()
                } else {
                    // The target is gone or already archived — the invalidation
                    // outcome holds without a new record.
                    intentTarget?.toRecord() ?: addMemoryInternal(
                        scope = candidate.scope,
                        kind = candidate.kind,
                        content = candidate.content,
                        assistantId = bucketForScope(candidate.scope),
                        sourceConversationId = candidate.sourceConversationId,
                        sourceMessageIds = candidate.sourceMessageIds,
                        expiresAt = candidate.expiresAt,
                        confidence = candidate.confidence,
                    )
                }

                "confirms" -> {
                    // Reinforce only a live mutable target; an archived or
                    // immutable target gets the fact re-added as a new record.
                    if (targetMutable) {
                        memoryDAO.touchMemories(listOf(intentTarget!!.id), System.currentTimeMillis())
                        memoryDAO.getMemoryById(intentTarget.id)!!.toRecord()
                    } else {
                        addMemoryInternal(
                            scope = candidate.scope,
                            kind = candidate.kind,
                            content = candidate.content,
                            assistantId = bucketForScope(candidate.scope),
                            sourceConversationId = candidate.sourceConversationId,
                            sourceMessageIds = candidate.sourceMessageIds,
                            expiresAt = candidate.expiresAt,
                            confidence = candidate.confidence,
                        )
                    }
                }

                else -> if (targetMutable) {
                    // "updates" (or an unrecognized prefix on a valid target)
                    // supersedes: new version linked to the archived old one.
                    supersedeInternal(
                        targetId = intentTarget!!.id,
                        newContent = candidate.content,
                        expectedRevision = intentTarget.revision,
                        confidence = candidate.confidence,
                        expiresAt = candidate.expiresAt,
                        sourceConversationId = candidate.sourceConversationId,
                        sourceMessageIds = candidate.sourceMessageIds,
                        sourceRunId = candidate.sourceConversationId,
                        sourceTrigger = TRIGGER_AUTO_EXTRACTION,
                    )
                } else {
                    addMemoryInternal(
                        scope = candidate.scope,
                        kind = candidate.kind,
                        content = candidate.content,
                        assistantId = bucketForScope(candidate.scope),
                        sourceConversationId = candidate.sourceConversationId,
                        sourceMessageIds = candidate.sourceMessageIds,
                        expiresAt = candidate.expiresAt,
                        confidence = candidate.confidence,
                    )
                }
            }
        updateCandidateInternal(candidate.copy(status = MemoryCandidateStatus.ACCEPTED))
        eventDAO.insert(
            MemoryEvent(
                type = MemoryEventType.CANDIDATE_ACCEPTED,
                conversationId = candidate.sourceConversationId,
                memoryId = record.id,
                candidateId = candidate.id,
                message = "Candidate accepted.",
            ).toEntity()
        )
        return record
    }

    fun getRecentEventsFlow(limit: Int = 100): Flow<List<MemoryEvent>> =
        eventDAO.getRecentEventsFlow(limit).map { list -> list.map { it.toEvent() } }

    suspend fun getRecentEvents(limit: Int = 100): List<MemoryEvent> =
        eventDAO.getRecentEvents(limit).map { it.toEvent() }

    suspend fun countEventsSince(type: MemoryEventType, createdAfter: Long): Int =
        eventDAO.countEventsSince(type.wireName, createdAfter)

    suspend fun addEvent(event: MemoryEvent) = withMemoryWriter {
        eventDAO.insert(event.toEntity())
    }

    private suspend fun <T> withMemoryWriter(block: suspend () -> T): T {
        val gate = restoreWriteGate
        return if (gate == null) block() else gate.withCurrentWriterOrCancel(block)
    }

    private fun MemoryEntity.toAssistantMemory() = AssistantMemory(
        id = id,
        content = content,
        scope = MemoryScope.fromWireName(scope),
        kind = MemoryKind.fromWireName(kind),
        expiresAt = expiresAt,
        confidence = confidence,
        pinned = pinned,
        archived = archived,
        revision = revision,
        sourceRunId = sourceRunId,
        sourceTrigger = sourceTrigger,
        topicTitle = topicTitle,
        memberIds = decodeIntList(memberIdsJson),
        sourceConversationId = sourceConversationId,
        sourceMessageIds = decodeStringList(sourceMessageIdsJson),
        supersedesIds = decodeIntList(supersedesIdsJson),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastUsedAt = lastUsedAt,
        useCount = useCount,
    )

    private fun MemoryRecord.toAssistantMemory() = AssistantMemory(
        id = id,
        content = content,
        scope = scope,
        kind = kind,
        expiresAt = expiresAt,
        confidence = confidence,
        pinned = pinned,
        archived = archived,
        revision = revision,
        sourceRunId = sourceRunId,
        sourceTrigger = sourceTrigger,
        topicTitle = topicTitle,
        memberIds = memberIds,
        sourceConversationId = sourceConversationId,
        sourceMessageIds = sourceMessageIds,
        supersedesIds = supersedesIds,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastUsedAt = lastUsedAt,
        useCount = useCount,
    )

    private fun MemoryEntity.toRecord() = MemoryRecord(
        id = id,
        content = content,
        scope = MemoryScope.fromWireName(scope),
        kind = MemoryKind.fromWireName(kind),
        assistantId = assistantId,
        sourceConversationId = sourceConversationId,
        sourceMessageIds = decodeStringList(sourceMessageIdsJson),
        supersedesIds = decodeIntList(supersedesIdsJson),
        expiresAt = expiresAt,
        confidence = confidence,
        pinned = pinned,
        archived = archived,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastUsedAt = lastUsedAt,
        topicTitle = topicTitle,
        memberIds = decodeIntList(memberIdsJson),
        revision = revision,
        sourceRunId = sourceRunId,
        sourceTrigger = sourceTrigger,
        useCount = useCount,
    )

    private fun MemoryRecord.toEntity() = MemoryEntity(
        id = id,
        assistantId = assistantId.ifBlank { bucketForScope(scope) },
        content = content,
        scope = scope.wireName,
        kind = kind.wireName,
        sourceConversationId = sourceConversationId,
        sourceMessageIdsJson = JsonInstant.encodeToString(sourceMessageIds),
        supersedesIdsJson = JsonInstant.encodeToString(supersedesIds.distinct()),
        expiresAt = expiresAt,
        confidence = confidence.coerceIn(0f, 1f),
        pinned = pinned,
        archived = archived,
        createdAt = createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        // Inserts keep the record's timestamp (imports preserve file state);
        // the update path overrides updatedAt via its own DAO parameter.
        updatedAt = updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        lastUsedAt = lastUsedAt,
        topicTitle = topicTitle,
        memberIdsJson = JsonInstant.encodeToString(memberIds.distinct()),
        revision = revision.takeIf { it > 0 } ?: 1,
        sourceRunId = sourceRunId,
        sourceTrigger = sourceTrigger,
        useCount = useCount,
    )

    private fun MemoryCandidateEntity.toCandidate() = MemoryCandidate(
        id = id,
        content = content,
        scope = MemoryScope.fromWireName(scope),
        kind = MemoryKind.fromWireName(kind),
        sourceConversationId = sourceConversationId,
        sourceMessageIds = decodeStringList(sourceMessageIdsJson),
        expiresAt = expiresAt,
        confidence = confidence,
        reason = reason,
        sensitive = sensitive,
        status = MemoryCandidateStatus.fromWireName(status),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun MemoryCandidate.toEntity() = MemoryCandidateEntity(
        id = id,
        content = content,
        scope = scope.wireName,
        kind = kind.wireName,
        sourceConversationId = sourceConversationId,
        sourceMessageIdsJson = JsonInstant.encodeToString(sourceMessageIds),
        expiresAt = expiresAt,
        confidence = confidence.coerceIn(0f, 1f),
        reason = reason,
        sensitive = sensitive,
        status = status.wireName,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun MemoryEventEntity.toEvent() = MemoryEvent(
        id = id,
        type = MemoryEventType.entries.firstOrNull { it.wireName == eventType } ?: MemoryEventType.EXTRACTION_SKIPPED,
        conversationId = conversationId,
        memoryId = memoryId,
        candidateId = candidateId,
        modelId = modelId,
        message = message,
        durationMs = durationMs,
        messageCount = messageCount,
        createdAt = createdAt,
    )

    private fun MemoryEvent.toEntity() = MemoryEventEntity(
        id = id,
        eventType = type.wireName,
        conversationId = conversationId,
        memoryId = memoryId,
        candidateId = candidateId,
        modelId = modelId,
        message = message.take(2_000),
        durationMs = durationMs,
        messageCount = messageCount,
        createdAt = createdAt,
    )

    private fun decodeStringList(raw: String): List<String> =
        runCatching { JsonInstant.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    private fun decodeIntList(raw: String): List<Int> =
        runCatching { JsonInstant.decodeFromString<List<Int>>(raw) }.getOrDefault(emptyList())

    private fun scopeForBucket(assistantId: String): MemoryScope = when (assistantId) {
        GLOBAL_MEMORY_ID -> MemoryScope.CORE
        SHORT_TERM_MEMORY_ID -> MemoryScope.SHORT_TERM
        LONG_TERM_MEMORY_ID -> MemoryScope.LONG_TERM
        else -> MemoryScope.LONG_TERM
    }
}

fun bucketForScope(scope: MemoryScope): String = when (scope) {
    MemoryScope.CORE -> MemoryRepository.GLOBAL_MEMORY_ID
    MemoryScope.SHORT_TERM -> MemoryRepository.SHORT_TERM_MEMORY_ID
    MemoryScope.LONG_TERM -> MemoryRepository.LONG_TERM_MEMORY_ID
}

/**
 * P2-06: the memory record changed (revision bumped) after the approval was
 * granted. The stale approval must not overwrite the newer version; the caller
 * re-reads and re-generates the diff instead.
 */
class MemoryStaleException(
    val memoryId: Int,
    val expectedRevision: Long,
    val actualRevision: Long,
) : IllegalStateException(
    "Memory record #$memoryId changed (revision $expectedRevision -> $actualRevision) after approval; " +
        "re-read the record and retry with the current revision"
)

/** P2-06: result of a CAS delete; [oldDigest] is a hash only, never content. */
data class MemoryCasDeleteResult(
    val memoryId: Int,
    val oldDigest: String,
)

/** P2-06: result of a CAS update with old/new content digests (hashes only). */
data class MemoryCasUpdateResult(
    val memory: AssistantMemory,
    val oldDigest: String,
    val newDigest: String,
)
