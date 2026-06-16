package app.amber.feature.modelcouncil

import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/**
 * Persistence boundary for [CouncilRoom].
 *
 * Lives in the `:api` module so [CouncilRoomManager] (in `:feature:modelcouncil`)
 * can depend on it without pulling in `:app` (which owns Room/DAO). The concrete
 * implementation [app.amber.core.repository.CouncilRoomRepository] lives in `:app`
 * and is wired via Koin.
 *
 * Contract:
 *  - [observeRoom] is the source of truth for active rooms; cold-loads from
 *    durable storage on first access for a given id.
 *  - [upsertRoom] updates the in-memory state immediately and persists on a
 *    debounce — safe to call per streaming chunk.
 *  - [evict] flushes + drops the in-memory entry when the Room leaves the
 *    active set (bounds memory).
 */
interface CouncilRoomStore {
    /**
     * Observe the Room for a conversation. The returned StateFlow is stable
     * across calls for the same id. Emits null if no Room exists.
     */
    suspend fun observeRoom(conversationId: Uuid): StateFlow<CouncilRoom?>

    /** Synchronous peek at the in-memory value; null if not loaded or absent. */
    fun peekRoom(conversationId: Uuid): CouncilRoom?

    /** Update in-memory + schedule debounced persist. */
    suspend fun upsertRoom(room: CouncilRoom)

    /**
     * Atomically persist [room] (synchronous flush, no debounce) AND evict the
     * in-memory entry under a single lock. Used by Room close: guarantees no
     * concurrent upsert can slip in between the final persist and the evict
     * (which would otherwise race with a re-open of the same conversation).
     */
    suspend fun closeAndEvict(room: CouncilRoom)

    /** Flush + drop the in-memory entry. */
    suspend fun evict(conversationId: Uuid)

    /** Force-flush any pending write (e.g. on app background). */
    suspend fun flush(conversationId: Uuid)

    /** Delete persisted + in-memory state. */
    suspend fun deleteRoom(conversationId: Uuid)
}
