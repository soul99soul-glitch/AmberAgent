package app.amber.feature.chat.impl

/**
 * Decides when a streaming turn deserves a durable stream checkpoint.
 *
 * Coalescing policy (both conditions require the tail content to have
 * actually changed since the last emitted checkpoint):
 *  - at most one checkpoint per [minIntervalMs] (default 1s), OR
 *  - immediately once at least [charThreshold] (default 512) new chars
 *    accumulated on the streaming tail since the last checkpoint.
 *
 * The first [offer] only arms the time window (no instant checkpoint for
 * the very first delta) — the first emit lands at +1s or +512 chars,
 * whichever comes first. Not thread-safe; call from the single collector
 * coroutine of one generation.
 */
class StreamCheckpointCoalescer(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val charThreshold: Long = DEFAULT_CHAR_THRESHOLD,
) {
    private var lastEmitAt = Long.MIN_VALUE
    private var lastEmittedHash: String? = null
    private var emittedChars = 0L
    private var tailMessageId: String? = null

    fun offer(nowMs: Long, messageId: String, partsHash: String, charCount: Long): Boolean {
        if (lastEmitAt == Long.MIN_VALUE) {
            lastEmitAt = nowMs
        }
        if (messageId != tailMessageId) {
            // New streaming tail: char baseline restarts, but the global
            // 1s pace cap carries over so message switches can't burst.
            tailMessageId = messageId
            emittedChars = 0L
        }
        if (partsHash == lastEmittedHash) return false
        val due = nowMs - lastEmitAt >= minIntervalMs ||
            charCount - emittedChars >= charThreshold
        if (!due) return false
        lastEmitAt = nowMs
        lastEmittedHash = partsHash
        emittedChars = charCount
        return true
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 1_000L
        const val DEFAULT_CHAR_THRESHOLD = 512L
    }
}
