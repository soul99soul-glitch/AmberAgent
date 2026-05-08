package me.rerere.rikkahub.data.memory.dream

import me.rerere.rikkahub.data.memory.model.MemoryEventType
import me.rerere.rikkahub.data.memory.model.MemoryCandidateStatus
import me.rerere.rikkahub.data.memory.model.MemoryScope
import me.rerere.rikkahub.data.memory.store.MemoryRepository
import me.rerere.rikkahub.data.memory.telemetry.MemoryEventLogger

class MemoryDreamApplier(
    private val memoryRepository: MemoryRepository,
    private val eventLogger: MemoryEventLogger,
) {
    suspend fun apply(plan: MemoryDreamPlan) {
        val records = memoryRepository.getAllRecords().associateBy { it.id }

        plan.mergeSuggestions.forEach { suggestion ->
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

        plan.promoteMemoryIds.forEach { id ->
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

        plan.archiveMemoryIds.forEach { id ->
            val record = records[id] ?: return@forEach
            memoryRepository.upsertRecord(record.copy(archived = true))
            eventLogger.log(
                type = MemoryEventType.MEMORY_ARCHIVED,
                memoryId = id,
                message = "Archived by dream cleanup.",
            )
        }

        val candidates = memoryRepository.getAllCandidates().associateBy { it.id }
        plan.ignoreCandidateIds.forEach { id ->
            val candidate = candidates[id] ?: return@forEach
            memoryRepository.updateCandidate(candidate.copy(status = MemoryCandidateStatus.IGNORED))
        }

        eventLogger.log(
            type = MemoryEventType.DREAM_APPLIED,
            message = "Applied dream diff.",
        )
    }
}
