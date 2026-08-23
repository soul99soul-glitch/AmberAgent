package app.amber.core.memory.dream

import app.amber.core.memory.model.MemoryEventType
import app.amber.core.memory.model.MemoryCandidateStatus
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.safety.isSensitiveMemoryContent
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.telemetry.MemoryEventLogger

class MemoryDreamApplier(
    private val memoryRepository: MemoryRepository,
    private val eventLogger: MemoryEventLogger,
) {
    suspend fun apply(plan: MemoryDreamPlan): MemoryDreamPlan {
        val records = memoryRepository.getAllRecords().associateBy { it.id }
        val applicablePlan = plan.onlyApplicableToManagedMemories(records)
        if (!applicablePlan.hasChanges) return applicablePlan

        applicablePlan.mergeSuggestions.forEach { suggestion ->
            val target = records[suggestion.targetMemoryId] ?: return@forEach
            val mergedContent = suggestion.mergedContent
                ?.trim()
                ?.takeIf { it.length >= 8 }
                ?: target.content
            memoryRepository.upsertRecord(target.copy(content = mergedContent))
            eventLogger.log(
                type = MemoryEventType.MEMORY_UPDATED,
                memoryId = target.id,
                message = "Updated by dream merge.",
            )
            suggestion.duplicateMemoryIds.forEach { duplicateId ->
                val duplicate = records[duplicateId] ?: return@forEach
                memoryRepository.upsertRecord(duplicate.copy(archived = true))
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
                memoryRepository.upsertRecord(
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
            memoryRepository.upsertRecord(record.copy(archived = true))
            eventLogger.log(
                type = MemoryEventType.MEMORY_ARCHIVED,
                memoryId = id,
                message = "Archived by dream cleanup.",
            )
        }

        applicablePlan.supersedeSuggestions.forEach { suggestion ->
            val oldRecords = suggestion.oldMemoryIds.mapNotNull { records[it] }
            if (oldRecords.isEmpty()) return@forEach
            val newRecord = memoryRepository.addMemory(
                scope = suggestion.scope,
                kind = suggestion.kind,
                content = suggestion.newContent,
                sourceConversationId = oldRecords.firstNotNullOfOrNull { it.sourceConversationId },
                sourceMessageIds = oldRecords.flatMap { it.sourceMessageIds }.distinct(),
                supersedesIds = oldRecords.map { it.id }.distinct(),
                confidence = suggestion.confidence,
            )
            eventLogger.log(
                type = MemoryEventType.MEMORY_CREATED,
                memoryId = newRecord.id,
                message = "Superseded memories: ${oldRecords.joinToString(",") { it.id.toString() }}.",
            )
            oldRecords.forEach { oldRecord ->
                memoryRepository.upsertRecord(oldRecord.copy(archived = true))
                eventLogger.log(
                    type = MemoryEventType.MEMORY_ARCHIVED,
                    memoryId = oldRecord.id,
                    message = "Archived by dream supersede -> new #${newRecord.id}.",
                )
            }
        }

        val candidates = memoryRepository.getAllCandidates().associateBy { it.id }
        applicablePlan.ignoreCandidateIds.forEach { id ->
            val candidate = candidates[id] ?: return@forEach
            memoryRepository.updateCandidate(candidate.copy(status = MemoryCandidateStatus.IGNORED))
        }

        eventLogger.log(
            type = MemoryEventType.DREAM_APPLIED,
            message = applicablePlan.summaryText("Applied dream diff"),
        )
        return applicablePlan
    }

    private fun MemoryDreamPlan.onlyApplicableToManagedMemories(
        records: Map<Int, MemoryRecord>,
    ): MemoryDreamPlan {
        val supersedeSuggestions = supersedeSuggestions.mapNotNull { suggestion ->
            if (suggestion.scope == MemoryScope.CORE) return@mapNotNull null
            if (suggestion.kind == MemoryKind.NOTE) return@mapNotNull null
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
        return copy(
            mergeSuggestions = mergeSuggestions,
            promoteMemoryIds = promoteMemoryIds
                .mapNotNull { records[it] }
                .filter { it.scope == MemoryScope.SHORT_TERM && !it.archived && it.id !in supersededIds }
                .map { it.id }
                .distinct(),
            archiveMemoryIds = archiveMemoryIds
                .mapNotNull { records[it] }
                .filter {
                    it.scope == MemoryScope.SHORT_TERM &&
                        !it.archived &&
                        !it.pinned &&
                        it.id !in mergeIds &&
                        it.id !in supersededIds
                }
                .map { it.id }
                .distinct(),
            ignoreCandidateIds = ignoreCandidateIds.distinct(),
            supersedeSuggestions = supersedeSuggestions,
        )
    }

    private fun MemoryRecord.isManagedByDream(): Boolean =
        !archived && scope != MemoryScope.CORE

    private fun MemoryRecord.canBeSuperseded(): Boolean =
        isManagedByDream() && !pinned && !isSensitiveMemoryContent(content)

    private fun MemoryDreamPlan.summaryText(prefix: String): String =
        "$prefix: merge=${mergeSuggestions.size}, promote=${promoteMemoryIds.size}, " +
            "archive=${archiveMemoryIds.size}, supersede=${supersedeSuggestions.size}, " +
            "ignore=${ignoreCandidateIds.size}"

    private val managedMemoryComparator = compareByDescending<MemoryRecord> { it.scope == MemoryScope.LONG_TERM }
        .thenByDescending { it.pinned }
        .thenByDescending { it.confidence }
        .thenByDescending { it.updatedAt }
}
