package me.rerere.rikkahub.data.agent.webmount.primitives

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-session ring buffer of network events captured by `bridge.js`'s
 * XHR/fetch monkey patches. The agent polls these via `wm_state(since_seq=N)`
 * to learn what requests fired since its last check — handy for
 * adapter authors who need to map page actions to backend endpoints.
 *
 * The ring is bounded by [capacity]; older entries are silently dropped.
 * Each event gets a monotonically-increasing sequence number assigned
 * server-side (Kotlin), so the agent can pass back the last `seq` it saw
 * to receive only newer entries.
 */
class NetworkLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private data class Entry(val seq: Long, val type: String, val payload: JsonObject)

    private val seqCounter = AtomicLong(0L)
    private val buffer = ArrayDeque<Entry>()
    private val lock = Any()

    /** Total events ever recorded for this session (including dropped). */
    val totalEvents: Long get() = seqCounter.get()

    fun record(rawEvent: JsonObject) {
        val type = (rawEvent["type"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
        val seq = seqCounter.incrementAndGet()
        val enriched = buildJsonObject {
            put("seq", JsonPrimitive(seq))
            rawEvent.forEach { (k, v) -> put(k, v) }
        }
        synchronized(lock) {
            buffer.addLast(Entry(seq, type, enriched))
            while (buffer.size > capacity) buffer.removeFirst()
        }
    }

    /**
     * Return events whose `seq > sinceSeq`, oldest first. Pass `sinceSeq=0`
     * (or omit) to see everything still in the buffer.
     *
     * The returned `last_event_seq` is the seq of the LAST event included in
     * `events` — so the agent can pass it back as `sinceSeq` for the next
     * poll and not skip anything. If the caller hit [maxEntries] before
     * draining the buffer, `more_available` is true and `buffer_newest_seq`
     * tells them how many they still missed.
     */
    fun snapshot(sinceSeq: Long = 0L, maxEntries: Int = capacity): JsonElement {
        val (entries, bufferNewest) = synchronized(lock) {
            val take = buffer.asSequence()
                .filter { it.seq > sinceSeq }
                .take(maxEntries)
                .toList()
            take to (buffer.lastOrNull()?.seq ?: 0L)
        }
        val lastReturned = entries.lastOrNull()?.seq ?: sinceSeq
        return buildJsonObject {
            put("last_event_seq", JsonPrimitive(lastReturned))
            put("buffer_newest_seq", JsonPrimitive(bufferNewest))
            put("more_available", JsonPrimitive(lastReturned < bufferNewest))
            put("count", JsonPrimitive(entries.size))
            put("events", buildJsonArray { entries.forEach { add(it.payload) } })
        }
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
