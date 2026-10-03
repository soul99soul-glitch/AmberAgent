package app.amber.core.memory.dream

import app.amber.core.memory.model.MemoryEventType
import app.amber.core.memory.model.MemoryCandidateStatus
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.safety.isSensitiveMemoryContent
import app.amber.core.memory.store.MemoryProfile
import app.amber.core.memory.store.MemoryProfileStore
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.telemetry.MemoryEventLogger
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Applies a dream plan; abstraction lets run coordination fake it in tests. */
fun interface MemoryDreamPlanApplier {
    suspend fun apply(plan: MemoryDreamPlan): MemoryDreamPlan
}

class MemoryDreamApplier(
    private val memoryRepository: MemoryRepository,
    private val eventLogger: MemoryEventLogger,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
    private val profileStore: MemoryProfileStore? = null,
) : MemoryDreamPlanApplier {
    private val applyMutex = Mutex()

    override suspend fun apply(plan: MemoryDreamPlan): MemoryDreamPlan =
        withContext(captureWriteContext()) { applyInternal(plan) }

    private suspend fun applyInternal(plan: MemoryDreamPlan): MemoryDreamPlan = applyMutex.withLock {
        val records = memoryRepository.getAllRecords().associateBy { it.id }.toMutableMap()
        val applicablePlan = plan.onlyApplicableToManagedMemories(records)
        if (!applicablePlan.hasChanges) return@withLock applicablePlan

        // Every suggestion is applied in isolation: a raced CAS revision or a
        // record that changed underneath the plan drops that op only, never
        // the whole run.
        val appliedMerges = mutableListOf<MemoryMergeSuggestion>()
        val appliedPromotes = mutableListOf<Int>()
        val appliedArchives = mutableListOf<Int>()
        val appliedSupersedes = mutableListOf<MemorySupersedeSuggestion>()
        val appliedTopics = mutableListOf<MemoryTopicSuggestion>()
        val appliedIgnores = mutableListOf<String>()

        applicablePlan.mergeSuggestions.forEach { suggestion ->
            try {
                val target = records[suggestion.targetMemoryId] ?: return@forEach
                val mergedContent = suggestion.mergedContent
                    ?.trim()
                    ?.takeIf { it.length >= 8 }
                    ?: target.content
                records[target.id] = memoryRepository.upsertRecord(target.copy(content = mergedContent))
                eventLogger.log(
                    type = MemoryEventType.MEMORY_UPDATED,
                    memoryId = target.id,
                    message = "Updated by dream merge.",
                )
                suggestion.duplicateMemoryIds.forEach { duplicateId ->
                    val duplicate = records[duplicateId] ?: return@forEach
                    records[duplicate.id] = memoryRepository.upsertRecord(duplicate.copy(archived = true))
                    eventLogger.log(
                        type = MemoryEventType.MEMORY_ARCHIVED,
                        memoryId = duplicateId,
                        message = "Archived duplicate by dream merge.",
                    )
                }
                appliedMerges += suggestion
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Op dropped; the rest of the plan still applies.
            }
        }

        applicablePlan.promoteMemoryIds.forEach { id ->
            try {
                val record = records[id] ?: return@forEach
                if (record.scope == MemoryScope.SHORT_TERM) {
                    records[id] = memoryRepository.upsertRecord(
                        record.copy(
                            scope = MemoryScope.LONG_TERM,
                            assistantId = MemoryRepository.LONG_TERM_MEMORY_ID,
                            expiresAt = null,
                        )
                    )
                    eventLogger.log(
                        type = MemoryEventType.MEMORY_UPDATED,
                        memoryId = id,
                        message = "Promoted by dream cleanup.",
                    )
                    appliedPromotes += id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }

        applicablePlan.archiveMemoryIds.forEach { id ->
            try {
                val record = records[id] ?: return@forEach
                records[id] = memoryRepository.upsertRecord(record.copy(archived = true))
                eventLogger.log(
                    type = MemoryEventType.MEMORY_ARCHIVED,
                    memoryId = id,
                    message = "Archived by dream cleanup.",
                )
                appliedArchives += id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }

        applicablePlan.supersedeSuggestions.forEach { suggestion ->
            try {
                val oldRecords = suggestion.oldMemoryIds.mapNotNull { records[it] }
                if (oldRecords.isEmpty()) return@forEach
                val supersededIds = oldRecords.map { it.id }.distinct()
                val newRecord = records.values.firstOrNull { existing ->
                    !existing.archived &&
                        existing.supersedesIds.toSet() == supersededIds.toSet() &&
                        existing.content == suggestion.newContent
                } ?: memoryRepository.addMemory(
                    scope = suggestion.scope,
                    kind = suggestion.kind,
                    content = suggestion.newContent,
                    sourceConversationId = oldRecords.firstNotNullOfOrNull { it.sourceConversationId },
                    sourceMessageIds = oldRecords.flatMap { it.sourceMessageIds }.distinct(),
                    supersedesIds = supersededIds,
                    confidence = suggestion.confidence,
                ).also { created ->
                    records[created.id] = created
                    eventLogger.log(
                        type = MemoryEventType.MEMORY_CREATED,
                        memoryId = created.id,
                        message = "Superseded memories: ${oldRecords.joinToString(",") { it.id.toString() }}.",
                    )
                }
                oldRecords.forEach { oldRecord ->
                    val currentRecord = records[oldRecord.id] ?: oldRecord
                    records[oldRecord.id] = memoryRepository.upsertRecord(currentRecord.copy(archived = true))
                    eventLogger.log(
                        type = MemoryEventType.MEMORY_ARCHIVED,
                        memoryId = oldRecord.id,
                        message = "Archived by dream supersede -> new #${newRecord.id}.",
                    )
                }
                appliedSupersedes += suggestion
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }

        applicablePlan.topicSuggestions.forEach { suggestion ->
            try {
                val titleKey = normalizeTopicTitle(suggestion.title)
                val existing = records.values.firstOrNull { record ->
                    record.kind == MemoryKind.TOPIC && !record.archived &&
                        record.topicTitle?.let(::normalizeTopicTitle) == titleKey
                }
                if (existing != null) {
                    records[existing.id] = memoryRepository.upsertRecord(
                        existing.copy(
                            content = suggestion.content,
                            topicTitle = suggestion.title,
                            memberIds = suggestion.memberMemoryIds,
                        )
                    )
                    eventLogger.log(
                        type = MemoryEventType.MEMORY_UPDATED,
                        memoryId = existing.id,
                        message = "Topic updated by dream review.",
                    )
                } else {
                    val created = memoryRepository.addMemory(
                        scope = MemoryScope.LONG_TERM,
                        kind = MemoryKind.TOPIC,
                        content = suggestion.content,
                        topicTitle = suggestion.title,
                        memberIds = suggestion.memberMemoryIds,
                        confidence = 0.8f,
                        sourceTrigger = MemoryRepository.TRIGGER_DREAM,
                    )
                    records[created.id] = created
                    eventLogger.log(
                        type = MemoryEventType.MEMORY_CREATED,
                        memoryId = created.id,
                        message = "Topic created by dream review: ${suggestion.title}.",
                    )
                }
                appliedTopics += suggestion
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }

        // The profile is a derived view written only on plan approval. Source
        // ids stamp the durable records it drew on so recall can drop it once
        // the underlying library drifts too far.
        val appliedProfile = applicablePlan.userProfile?.let { suggestion ->
            val store = profileStore ?: return@let null
            try {
                val sourceIds = records.values
                    .filter { record ->
                        !record.archived &&
                            (record.scope == MemoryScope.CORE ||
                                record.kind == MemoryKind.USER ||
                                record.kind == MemoryKind.FEEDBACK)
                    }
                    .map { it.id }
                store.put(
                    MemoryProfile(
                        content = suggestion.content.trim(),
                        generatedAt = System.currentTimeMillis(),
                        sourceMemoryIds = sourceIds,
                    )
                )
                eventLogger.log(
                    type = MemoryEventType.MEMORY_UPDATED,
                    message = "User profile updated by dream review.",
                )
                suggestion
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        val candidates = memoryRepository.getAllCandidates().associateBy { it.id }
        applicablePlan.ignoreCandidateIds.forEach { id ->
            try {
                val candidate = candidates[id] ?: return@forEach
                // A candidate accepted between planning and applying must not be
                // flipped back to ignored — its memory was already written.
                if (candidate.status != MemoryCandidateStatus.PENDING) return@forEach
                memoryRepository.updateCandidate(candidate.copy(status = MemoryCandidateStatus.IGNORED))
                appliedIgnores += id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }

        val appliedPlan = applicablePlan.copy(
            mergeSuggestions = appliedMerges,
            promoteMemoryIds = appliedPromotes,
            archiveMemoryIds = appliedArchives,
            supersedeSuggestions = appliedSupersedes,
            topicSuggestions = appliedTopics,
            ignoreCandidateIds = appliedIgnores,
            userProfile = appliedProfile,
        )
        eventLogger.log(
            type = MemoryEventType.DREAM_APPLIED,
            message = appliedPlan.summaryText("Applied dream diff"),
        )
        appliedPlan
    }

    private suspend fun captureWriteContext(): CoroutineContext {
        coroutineContext[SyncRestoreWriteEpoch]?.let { return it }
        val gate = restoreWriteGate ?: return EmptyCoroutineContext
        // Re-read the records after an in-flight restore has completed, then
        // carry the resulting generation through every short repository write.
        gate.withWriter { Unit }
        return SyncRestoreWriteEpoch(gate.currentEpoch())
    }

    private fun MemoryDreamPlan.onlyApplicableToManagedMemories(
        records: Map<Int, MemoryRecord>,
    ): MemoryDreamPlan {
        val supersedeSuggestions = supersedeSuggestions.mapNotNull { suggestion ->
            if (suggestion.scope == MemoryScope.CORE) return@mapNotNull null
            if (suggestion.kind == MemoryKind.NOTE) return@mapNotNull null
            if (suggestion.kind == MemoryKind.TOPIC) return@mapNotNull null
            if (suggestion.confidence < 0.70f) return@mapNotNull null
            if (suggestion.newContent.trim().length < 8) return@mapNotNull null
            if (isSensitiveMemoryContent(suggestion.newContent)) return@mapNotNull null
            val oldRecords = suggestion.oldMemoryIds
                .mapNotNull { records[it] }
                .filter { it.canBeSuperseded() }
                .distinctBy { it.id }
            if (oldRecords.isEmpty()) return@mapNotNull null
            suggestion.copy(
                oldMemoryIds = oldRecords.map { it.id },
                newContent = suggestion.newContent.trim(),
                confidence = suggestion.confidence.coerceIn(0f, 1f),
            )
        }
        val supersededIds = supersedeSuggestions.flatMap { it.oldMemoryIds }.toSet()
        val mergeSuggestions = mergeSuggestions.mapNotNull { suggestion ->
            val candidates = (listOf(suggestion.targetMemoryId) + suggestion.duplicateMemoryIds)
                .mapNotNull { records[it] }
                .filter { it.isManagedByDream() && it.id !in supersededIds }
                .distinctBy { it.id }
            if (candidates.size < 2) return@mapNotNull null

            val target = candidates.sortedWith(managedMemoryComparator).first()
            val duplicateIds = candidates
                .filterNot { it.id == target.id }
                .map { it.id }
            if (duplicateIds.isEmpty()) return@mapNotNull null
            suggestion.copy(
                targetMemoryId = target.id,
                duplicateMemoryIds = duplicateIds,
            )
        }
        val mergeIds = mergeSuggestions.flatMap { listOf(it.targetMemoryId) + it.duplicateMemoryIds }.toSet()
        val now = System.currentTimeMillis()
        val promoteIds = promoteMemoryIds
            .mapNotNull { records[it] }
            .filter {
                it.scope == MemoryScope.SHORT_TERM &&
                    it.kind != MemoryKind.TOPIC &&
                    !it.archived &&
                    it.id !in supersededIds
            }
            .map { it.id }
            .distinct()
        val promoteIdSet = promoteIds.toSet()
        val archiveMemoryIds = archiveMemoryIds
            .mapNotNull { records[it] }
            .filter {
                // Deterministic maintenance archives expired records in any
                // scope (recall can't see them anyway); model-suggested
                // archives stay limited to short_term. A record promoted in
                // the same pass is never archived — reinforcement wins.
                (it.scope == MemoryScope.SHORT_TERM ||
                    it.expiresAt?.let { expiry -> expiry <= now } == true) &&
                    it.kind != MemoryKind.TOPIC &&
                    !it.archived &&
                    !it.pinned &&
                    it.id !in mergeIds &&
                    it.id !in supersededIds &&
                    it.id !in promoteIdSet
            }
            .map { it.id }
            .distinct()
        val archivedThisPass =
            mergeSuggestions.flatMap { it.duplicateMemoryIds }.toSet() +
                supersededIds + archiveMemoryIds
        val topicSuggestions = topicSuggestions.mapNotNull { suggestion ->
            val memberIds = suggestion.memberMemoryIds
                .mapNotNull { records[it] }
                .filter { it.isManagedByDream() && it.id !in archivedThisPass }
                .map { it.id }
                .distinct()
            if (memberIds.size < 2) return@mapNotNull null
            suggestion.copy(memberMemoryIds = memberIds)
        }
        val userProfile = userProfile?.takeUnless { suggestion ->
            suggestion.content.trim().length < MIN_PROFILE_CHARS ||
                isSensitiveMemoryContent(suggestion.content)
        }
        return copy(
            mergeSuggestions = mergeSuggestions,
            promoteMemoryIds = promoteIds,
            archiveMemoryIds = archiveMemoryIds,
            ignoreCandidateIds = ignoreCandidateIds.distinct(),
            supersedeSuggestions = supersedeSuggestions,
            topicSuggestions = topicSuggestions,
            userProfile = userProfile,
        )
    }

    private companion object {
        const val MIN_PROFILE_CHARS = 16
    }

    private fun MemoryRecord.isManagedByDream(): Boolean =
        !archived && scope != MemoryScope.CORE && kind != MemoryKind.TOPIC

    private fun MemoryRecord.canBeSuperseded(): Boolean =
        isManagedByDream() && !pinned && !isSensitiveMemoryContent(content)

    private fun MemoryDreamPlan.summaryText(prefix: String): String =
        "$prefix: merge=${mergeSuggestions.size}, promote=${promoteMemoryIds.size}, " +
            "archive=${archiveMemoryIds.size}, supersede=${supersedeSuggestions.size}, " +
            "ignore=${ignoreCandidateIds.size}, topics=${topicSuggestions.size}"

    private val managedMemoryComparator = compareByDescending<MemoryRecord> { it.scope == MemoryScope.LONG_TERM }
        .thenByDescending { it.pinned }
        .thenByDescending { it.confidence }
        .thenByDescending { it.updatedAt }
}

// Fuzzy upsert key on purpose: "A B" and "AB" collapse to one topic —
// near-duplicate titles should update, not fork. Shared with the importer so
// file matching uses the same normalization as dream review.
internal fun normalizeTopicTitle(title: String): String =
    title.lowercase().filter { it.isLetterOrDigit() }
