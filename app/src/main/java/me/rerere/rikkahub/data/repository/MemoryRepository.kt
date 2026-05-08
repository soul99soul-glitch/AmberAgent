package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.MemoryCandidateDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryEventDAO

class MemoryRepository(
    memoryDAO: MemoryDAO,
    candidateDAO: MemoryCandidateDAO,
    eventDAO: MemoryEventDAO,
) : me.rerere.rikkahub.data.memory.store.MemoryRepository(memoryDAO, candidateDAO, eventDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = me.rerere.rikkahub.data.memory.store.MemoryRepository.GLOBAL_MEMORY_ID
        const val SHORT_TERM_MEMORY_ID = me.rerere.rikkahub.data.memory.store.MemoryRepository.SHORT_TERM_MEMORY_ID
        const val LONG_TERM_MEMORY_ID = me.rerere.rikkahub.data.memory.store.MemoryRepository.LONG_TERM_MEMORY_ID
    }
}
