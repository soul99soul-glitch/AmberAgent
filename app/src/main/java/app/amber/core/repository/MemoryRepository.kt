package app.amber.core.repository

import app.amber.agent.data.db.dao.MemoryCandidateDAO
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.dao.MemoryEventDAO
import app.amber.agent.data.db.AppDatabase

class MemoryRepository(
    memoryDAO: MemoryDAO,
    candidateDAO: MemoryCandidateDAO,
    eventDAO: MemoryEventDAO,
    appDatabase: AppDatabase,
) : app.amber.core.memory.store.MemoryRepository(memoryDAO, candidateDAO, eventDAO, appDatabase) {
    companion object {
        const val GLOBAL_MEMORY_ID = app.amber.core.memory.store.MemoryRepository.GLOBAL_MEMORY_ID
        const val SHORT_TERM_MEMORY_ID = app.amber.core.memory.store.MemoryRepository.SHORT_TERM_MEMORY_ID
        const val LONG_TERM_MEMORY_ID = app.amber.core.memory.store.MemoryRepository.LONG_TERM_MEMORY_ID
    }
}
