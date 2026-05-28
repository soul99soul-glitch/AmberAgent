package app.amber.feature.board.collector

import app.amber.agent.data.db.entity.BoardSignalEntity

/**
 * Raw, pre-persistence shape of a captured signal. Collectors emit these; the aggregator
 * is responsible for turning them into [BoardSignalEntity] (assigning UUIDs, computing
 * content hashes, deciding on dedup) before insertion.
 *
 * Kept separate from the entity so collectors don't need to know about Room or hashing
 * conventions. This also makes collectors trivial to unit-test.
 */
data class RawBoardSignal(
    val sourceType: String,
    val sourceRef: String,
    val title: String,
    val content: String,
    val signalTime: Long,
    val metadataJson: String = "{}",
)

/**
 * Common interface for everything that captures signals. Collectors run when invoked by
 * the BoardScheduler / aggregator — none of them maintain their own loops. Real-time
 * sources (notifications) bridge into this model by writing through the aggregator from
 * a system callback.
 *
 * The collector layer is intentionally synchronous-pull: each implementation describes
 * "what's happened since I last reported", and the caller decides when to ask. This keeps
 * battery and concurrency stories simple.
 */
interface BoardSignalCollector {
    /** Stable identifier. Matches [BoardSignalSourceType] constants. */
    val sourceType: String

    /**
     * Pull signals up to [limit]. [limit] is a hard cap — collectors must not exceed it.
     *
     * Returning an empty list is fine and expected when nothing has changed.
     *
     * Collectors must NOT mutate any external state during collect (e.g. flipping a
     * "consumed" flag in another table). State transitions belong in [onIngested], which
     * the aggregator calls only after a signal is actually persisted.
     */
    suspend fun collect(limit: Int = 50): List<RawBoardSignal>

    /**
     * Called by the aggregator after each [RawBoardSignal] from this collector has been
     * successfully persisted (i.e. dedup didn't drop it). Default no-op; collectors that
     * need to mark upstream sources as consumed override this.
     */
    suspend fun onIngested(signal: RawBoardSignal) {}
}
