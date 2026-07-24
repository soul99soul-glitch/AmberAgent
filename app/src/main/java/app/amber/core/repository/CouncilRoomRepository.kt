package app.amber.core.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.feature.modelcouncil.CouncilMessageStatus
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomStatus
import app.amber.feature.modelcouncil.CouncilRoomStore
import app.amber.feature.modelcouncil.running
import app.amber.feature.modelcouncil.terminal
import app.amber.core.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * Concrete [CouncilRoomStore] implementation backed by ConversationEntity.council_state.
 *
 * Lives in `:app` (which owns Room/DAO); implements the `:api` interface so
 * [app.amber.feature.modelcouncil.CouncilRoomManager] in `:feature:modelcouncil`
 * can depend on the abstraction without a circular module dep.
 *
 * Storage strategy — two-tier, mirrors how the rest of the app handles hot
 * streaming state:
 *  - **In-memory [MutableStateFlow]** is the source of truth while the Room is
 *    active; the manager/UI read it for instant updates (every streaming chunk).
 *  - **SQLite (ConversationEntity.council_state)** is the durable checkpoint,
 *    written on a debounce (200ms) so we don't hit SQLite per token.
 *
 * On first [observeRoom] for a conversation, we lazy-load from SQLite and seed
 * the in-memory flow. On [upsertRoom], we update memory immediately and schedule
 * a debounced write. On [evict], we flush + drop the in-memory entry to bound
 * memory usage (the manager decides when a Room is no longer active).
 */
class CouncilRoomRepository(
    private val conversationDao: ConversationDAO,
    private val appScope: CoroutineScope,
) : CouncilRoomStore {
    /** conversationId → active Room state. ConcurrentHashMap so [peekRoom] can read concurrently with locked mutations without a [ConcurrentModificationException] (it is called from the UI thread). Structural put/remove still happen under [lock]. */
    private val active = java.util.concurrent.ConcurrentHashMap<Uuid, RoomSlot>()

    /** Serializes per-conversation repository mutations (load + upsert + evict). */
    private val lock = Mutex()

    /**
     * Observe a Room. Cold on first access: loads from SQLite if not yet in
     * memory. Returns null (and stays null in the flow) if no Room exists for
     * this conversation.
     *
     * The returned StateFlow is stable across calls for the same id — safe to
     * collect from Compose without re-subscription churn.
     */
    override suspend fun observeRoom(conversationId: Uuid): StateFlow<CouncilRoom?> = lock.withLock {
        active.getOrPut(conversationId) { RoomSlot() }.also { slot ->
            if (!slot.loaded) {
                slot.loaded = true
                val stored = runCatching { conversationDao.getCouncilState(conversationId.toString()) }
                    .getOrNull()
                stored?.takeIf { it.isNotBlank() }?.let { json ->
                    slot.flow.value = repairColdLoadedRoom(decodeRoom(json))
                }
            }
        }.flow.asStateFlow()
    }

    /** Synchronous peek at the in-memory value; null if not loaded or absent. */
    override fun peekRoom(conversationId: Uuid): CouncilRoom? = active[conversationId]?.flow?.value

    /**
     * Update the in-memory state immediately and schedule a debounced write.
     * Safe to call on a hot path (per streaming chunk) — the SQLite write is
     * coalesced.
     */
    override suspend fun upsertRoom(room: CouncilRoom) = lock.withLock {
        val slot = active.getOrPut(room.conversationId) { RoomSlot().also { it.loaded = true } }
        slot.flow.value = room
        slot.schedulePersist(appScope, conversationDao)
    }

    /**
     * Atomic close: persist [room] synchronously (no debounce) and remove the
     * in-memory slot under a single [lock] acquisition. This closes the race
     * where a concurrent upsert (e.g. a re-open) could slip between the final
     * flush and the evict, leaving the on-disk state inconsistent with what
     * observers saw last.
     */
    override suspend fun closeAndEvict(room: CouncilRoom) {
        lock.withLock {
            val slot = active[room.conversationId]
            if (slot != null) {
                slot.flow.value = room
                slot.flushNow(conversationDao)
                active.remove(room.conversationId)
            } else {
                // Slot already evicted (e.g. process restart path); still persist directly.
                persistRoomDirect(room)
            }
        }
    }

    /**
     * Drop the in-memory entry after flushing the latest value to SQLite.
     * Called by the manager when a Room leaves the active set (cap reached /
     * conversation closed). Observe calls after eviction will reload from disk.
     */
    override suspend fun evict(conversationId: Uuid) {
        lock.withLock {
            val slot = active.remove(conversationId) ?: return@withLock
            slot.flushNow(conversationDao)
        }
    }

    /** Force-flush any pending write for a conversation (e.g. on app background). */
    override suspend fun flush(conversationId: Uuid) {
        lock.withLock {
            active[conversationId]?.flushNow(conversationDao)
        }
    }

    /** Delete the persisted council_state (and clear memory) for a conversation. */
    override suspend fun deleteRoom(conversationId: Uuid) {
        lock.withLock {
            active.remove(conversationId)
            runCatching {
                conversationDao.updateCouncilState(
                    conversationId.toString(),
                    councilState = null,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun decodeRoom(json: String): CouncilRoom? = runCatching {
        JsonInstant.decodeFromString<CouncilRoom>(json)
    }.getOrNull()

    /**
     * After process death, in-memory ask_user deferreds and generation jobs are gone.
     * Project non-terminal rooms to INTERRUPTED and freeze any streaming rows so the
     * UI has a recoverable exit instead of a permanent "进行中" zombie.
     */
    private fun repairColdLoadedRoom(room: CouncilRoom?): CouncilRoom? {
        if (room == null) return null
        val needsStatusRepair = !room.status.terminal && room.status != CouncilRoomStatus.IDLE &&
            room.status != CouncilRoomStatus.INTERRUPTED
        val hasRunningMessages = room.messages.any { it.status.running }
        val hasSpeaking = room.participants.any { it.status == CouncilParticipantStatus.SPEAKING }
        if (!needsStatusRepair && !hasRunningMessages && !hasSpeaking) return room
        val messages = room.messages.map { m ->
            if (m.status.running) {
                m.copy(status = CouncilMessageStatus.FAILED, error = m.error.ifBlank { "interrupted" })
            } else {
                m
            }
        }
        val participants = room.participants.map { p ->
            if (p.status == CouncilParticipantStatus.SPEAKING) {
                p.copy(status = CouncilParticipantStatus.IDLE)
            } else {
                p
            }
        }
        val status = when {
            needsStatusRepair -> CouncilRoomStatus.INTERRUPTED
            room.status == CouncilRoomStatus.INTERRUPTED -> CouncilRoomStatus.INTERRUPTED
            else -> room.status
        }
        return room.copy(
            status = status,
            messages = messages,
            participants = participants,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    /** Persist a room directly to SQLite, bypassing the in-memory slot. Used by [closeAndEvict] fallback. */
    private suspend fun persistRoomDirect(room: CouncilRoom) {
        val jsonStr = runCatching {
            JsonInstant.encodeToString(CouncilRoom.serializer(), room)
        }.getOrNull() ?: return
        runCatching {
            conversationDao.updateCouncilState(
                id = room.conversationId.toString(),
                councilState = jsonStr,
                updatedAt = room.updatedAtMs,
            )
        }
    }

    /**
     * Per-conversation slot holding the live flow + a debounced persist job.
     */
    private class RoomSlot {
        val flow = MutableStateFlow<CouncilRoom?>(null)
        @Volatile var loaded: Boolean = false
        @Volatile var persistJob: Job? = null

        fun schedulePersist(
            scope: CoroutineScope,
            dao: ConversationDAO,
        ) {
            persistJob?.cancel()
            persistJob = scope.launch(Dispatchers.IO) {
                delay(PERSIST_DEBOUNCE_MS)
                val currentJob = currentCoroutineContext()[Job]
                if (persistJob === currentJob) {
                    persistJob = null
                }
                persistCurrent(dao)
            }
        }

        suspend fun flushNow(dao: ConversationDAO) {
            persistJob?.cancel()
            persistJob = null
            persistCurrent(dao)
        }

        private suspend fun persistCurrent(dao: ConversationDAO) {
            val current = flow.value ?: return
            val json = runCatching { JsonInstant.encodeToString(CouncilRoom.serializer(), current) }
                .getOrNull() ?: return
            dao.updateCouncilState(
                id = current.conversationId.toString(),
                councilState = json,
                updatedAt = current.updatedAtMs,
            )
        }
    }

    companion object {
        /** Debounce window for SQLite writes during streaming. */
        private const val PERSIST_DEBOUNCE_MS = 200L
    }
}
