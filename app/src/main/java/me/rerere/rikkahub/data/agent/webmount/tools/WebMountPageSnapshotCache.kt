package me.rerere.rikkahub.data.agent.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal object WebMountPageSnapshotCache {
    private const val TTL_MS = 3_000L
    private const val MAX_ENTRIES = 32
    private val lock = Any()
    private var clock: () -> Long = System::currentTimeMillis
    private val entries = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_ENTRIES
    }

    fun get(sessionId: String, kind: String, pageState: JsonElement): JsonElement? {
        val key = key(sessionId, kind, pageState) ?: return null
        return synchronized(lock) {
            val entry = entries[key] ?: return@synchronized null
            if (clock() - entry.createdAtMs > TTL_MS) {
                entries.remove(key)
                null
            } else {
                entry.payload
            }
        }
    }

    fun put(sessionId: String, kind: String, pageState: JsonElement, payload: JsonElement) {
        val key = key(sessionId, kind, pageState) ?: return
        synchronized(lock) {
            sweepExpiredLocked()
            entries[key] = Entry(clock(), payload)
        }
    }

    fun invalidate(sessionId: String) {
        synchronized(lock) {
            entries.keys
                .filter { it.startsWith("$sessionId|") }
                .forEach(entries::remove)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    internal fun sizeForTest(): Int = synchronized(lock) {
        sweepExpiredLocked()
        entries.size
    }

    internal fun setClockForTest(clockOverride: () -> Long) {
        synchronized(lock) {
            clock = clockOverride
        }
    }

    internal fun resetClockForTest() {
        synchronized(lock) {
            clock = System::currentTimeMillis
        }
    }

    private fun key(sessionId: String, kind: String, pageState: JsonElement): String? {
        val obj = runCatching { pageState.jsonObject }.getOrNull() ?: return null
        val url = obj.string("url") ?: return null
        val fingerprint = obj.string("semantic_fingerprint") ?: return null
        val scroll = (obj["scroll"] as? JsonObject)?.let { scrollObj ->
            "${scrollObj.primitive("x")}:${scrollObj.primitive("y")}"
        }.orEmpty()
        return "$sessionId|$kind|$url|$scroll|$fingerprint"
    }

    private data class Entry(
        val createdAtMs: Long,
        val payload: JsonElement,
    )

    private fun sweepExpiredLocked(now: Long = clock()) {
        entries.entries.removeIf { (_, entry) -> now - entry.createdAtMs > TTL_MS }
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.primitive(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
