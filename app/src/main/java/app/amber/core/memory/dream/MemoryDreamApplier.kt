package app.amber.core.memory.dream

import app.amber.core.memory.model.MemoryEventType
import app.amber.core.memory.model.MemoryCandidateStatus
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.safety.isSensitiveMemoryContent
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.telemetry.MemoryEventLogger
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
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
) : MemoryDreamPlanApplier {
    private val applyMutex = Mutex()

    override suspend fun apply(plan: MemoryDreamPlan): MemoryDreamPlan =
        withContext(captureWriteContext()) { applyInternal(plan) }

    private suspend fun applyInternal(plan: MemoryDreamPlan): MemoryDreamPlan = applyMutex.withLock {
        val records = memoryRepository.getAllRecords().associateBy { it.id }.toMutableMap()
        val applicablePlan = plan.onlyApplicableToManagedMemories(records)
        if (!applicablePlan.hasChanges) return@withLock applicablePlan

        applicablePlan.mergeSuggestions.forEach { suggestion ->
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
        }

        applicablePlan.promoteMemoryIds.forEach { id ->
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
            }
        }

        applicablePlan.archiveMemoryIds.forEach { id ->
            val record = records[id] ?: return@forEach
            records[id] = memoryRepository.upsertRecord(record.copy(archived = true))
            eventLogger.log(
                type = MemoryEventType.MEMORY_ARCHIVED,
                memoryId = id,
                message = "Archived by dream cleanup.",
            )
        }

        applicablePlan.supersedeSuggestions.forEach { suggestion ->
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
        }

        applicablePlan.topicSuggestions.forEach { suggestion ->
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
        }

        val candidates = memoryRepository.getAllCandidates().associateBy { it.id }
        applicablePlan.ignoreCandidateIds.forEach { id ->
            val candidate = candidates[id] ?: return@forEach
            // A candidate accepted between planning and applying must not be
            // flipped back to ignored — its memory was already written.
            if (candidate.status != MemoryCandidateStatus.PENDING) return@forEach
            memoryRepository.updateCandidate(candidate.copy(status = MemoryCandidateStatus.IGNORED))
        }

        eventLogger.log(
            type = MemoryEventType.DREAM_APPLIED,
            message = applicablePlan.summaryText("Applied dream diff"),
        )
        applicablePlan
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
        val archiveMemoryIds = archiveMemoryIds
            .mapNotNull { records[it] }
            .filter {
                it.scope == MemoryScope.SHORT_TERM &&
                    !it.archived &&
                    !it.pinned &&
                    it.id !in mergeIds &&
                    it.id !in supersededIds
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
        return copy(
            mergeSuggestions = mergeSuggestions,
            promoteMemoryIds = promoteMemoryIds
                .mapNotNull { records[it] }
                .filter { it.scope == MemoryScope.SHORT_TERM && !it.archived && it.id !in supersededIds }
                .map { it.id }
                .distinct(),
            archiveMemoryIds = archiveMemoryIds,
            ignoreCandidateIds = ignoreCandidateIds.distinct(),
            supersedeSuggestions = supersedeSuggestions,
            topicSuggestions = topicSuggestions,
        )
    }

    private fun MemoryRecord.isManagedByDream(): Boolean =
        !archived && scope != MemoryScope.CORE && kind != MemoryKind.TOPIC

    // Fuzzy upsert key on purpose: "A B" and "AB" collapse to one topic —
    // near-duplicate titles should update, not fork.
    private fun normalizeTopicTitle(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

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
