package me.rerere.rikkahub.data.memory.telemetry

import me.rerere.rikkahub.data.memory.model.MemoryEvent
import me.rerere.rikkahub.data.memory.model.MemoryEventType
import me.rerere.rikkahub.data.memory.store.MemoryRepository

class MemoryEventLogger(
    private val memoryRepository: MemoryRepository,
) {
    suspend fun log(
        type: MemoryEventType,
        conversationId: String? = null,
        memoryId: Int? = null,
        candidateId: String? = null,
        modelId: String? = null,
        message: String = "",
        durationMs: Long? = null,
        messageCount: Int? = null,
    ) {
        memoryRepository.addEvent(
            MemoryEvent(
                type = type,
                conversationId = conversationId,
                memoryId = memoryId,
                candidateId = candidateId,
                modelId = modelId,
                message = message,
                durationMs = durationMs,
                messageCount = messageCount,
            )
        )
    }
}
