package app.amber.feature.modelcouncil

import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStore

/**
 * Minimal surface [CouncilRoomManager] needs from a task store. Extracted as an
 * interface so unit tests can substitute a no-op/fake reporter without needing
 * a real Android [Context] (required by [AgentTaskStore]).
 */
interface CouncilRoomTaskReporter {
    suspend fun register(
        snapshot: AgentTaskSnapshot,
        cancel: (suspend () -> Boolean)? = null,
    )

    suspend fun upsert(snapshot: AgentTaskSnapshot)
}

/**
 * Adapter that wires [CouncilRoomTaskReporter] to the real [AgentTaskStore].
 */
class AgentTaskStoreReporter(
    private val store: AgentTaskStore,
) : CouncilRoomTaskReporter {
    override suspend fun register(
        snapshot: AgentTaskSnapshot,
        cancel: (suspend () -> Boolean)?,
    ) {
        store.register(snapshot, cancel)
    }

    override suspend fun upsert(snapshot: AgentTaskSnapshot) {
        store.upsert(snapshot)
    }
}
