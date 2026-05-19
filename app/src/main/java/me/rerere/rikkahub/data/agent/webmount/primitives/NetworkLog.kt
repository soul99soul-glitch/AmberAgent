package me.rerere.rikkahub.data.agent.webmount.primitives

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
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

    data class RequestTemplate(
        val id: String,
        val method: String,
        val rawUrl: String,
        val firstSeenSeq: Long,
        val lastSeenSeq: Long,
        val lastStatus: Int?,
        val count: Int,
    )

    private val seqCounter = AtomicLong(0L)
    private val buffer = ArrayDeque<Entry>()
    private val templates = linkedMapOf<String, RequestTemplate>()
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
            updateTemplateLocked(seq, type, enriched)
            buffer.addLast(Entry(seq, type, sanitizeForSnapshot(enriched)))
            while (buffer.size > capacity) buffer.removeFirst()
            while (templates.size > capacity) {
                val first = templates.keys.firstOrNull() ?: break
                templates.remove(first)
            }
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

    fun inspect(baseUrl: String?, maxTemplates: Int = 50): JsonElement {
        val origin = baseUrl?.let(::originOf)
        val (snapshot, totalTemplates) = synchronized(lock) {
            templates.values
                .sortedByDescending { it.lastSeenSeq }
                .take(maxTemplates.coerceIn(0, 200))
                .toList() to templates.size
        }
        return buildJsonObject {
            put("count", snapshot.size)
            put("total_observed_templates", totalTemplates)
            put("templates", buildJsonArray {
                snapshot.forEach { template ->
                    val absolute = resolveUrl(baseUrl, template.rawUrl)
                    val templateOrigin = originOf(absolute)
                    add(buildJsonObject {
                        put("request_template_id", template.id)
                        put("method", template.method)
                        put("host", hostOf(absolute).orEmpty())
                        put("path", redactedPath(absolute))
                        put("same_origin", origin != null && origin == templateOrigin)
                        put(
                            "replayable",
                            template.method in SAFE_REPLAY_METHODS &&
                                origin != null &&
                                origin == templateOrigin &&
                                !isProbablyMutatingReplayUrl(absolute),
                        )
                        template.lastStatus?.let { put("last_status", it) }
                        put("count", template.count)
                        put("first_seen_seq", template.firstSeenSeq)
                        put("last_seen_seq", template.lastSeenSeq)
                    })
                }
            })
        }
    }

    fun template(id: String): RequestTemplate? = synchronized(lock) { templates[id] }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            templates.clear()
        }
    }

    private fun updateTemplateLocked(seq: Long, type: String, event: JsonObject) {
        if (type !in TEMPLATE_EVENT_TYPES) return
        val method = event.string("method")?.uppercase(Locale.ROOT) ?: return
        val rawUrl = event.string("url") ?: return
        if (rawUrl.isBlank()) return
        val id = templateId(method, rawUrl)
        val previous = templates[id]
        templates[id] = RequestTemplate(
            id = id,
            method = method,
            rawUrl = rawUrl,
            firstSeenSeq = previous?.firstSeenSeq ?: seq,
            lastSeenSeq = seq,
            lastStatus = event.int("status") ?: previous?.lastStatus,
            count = (previous?.count ?: 0) + if (type.endsWith("_send")) 1 else 0,
        )
    }

    private fun sanitizeForSnapshot(event: JsonObject): JsonObject {
        val method = event.string("method")?.uppercase(Locale.ROOT)
        val rawUrl = event.string("url")
        val responsePreview = event.string("response_preview")
        return buildJsonObject {
            event["seq"]?.let { put("seq", it) }
            event["type"]?.let { put("type", it) }
            method?.let { put("method", it) }
            rawUrl?.let { url ->
                put("url", redactedUrl(url))
                hostOf(url)?.let { put("host", it) }
                put("path", redactedPath(url))
                method?.let { put("request_template_id", templateId(it, url)) }
            }
            event.int("status")?.let { put("status", it) }
            event.int("response_chars")?.let { put("response_chars", it) }
            event.string("body_preview")?.let { put("body_chars", it.length) }
            responsePreview?.let {
                put("response_kind", contentKind(it))
                responseShape(it)?.let { shape -> put("response_shape", shape) }
            }
            event.string("error")?.let { put("error", it.take(500)) }
            event["ts"]?.let { put("ts", it) }
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
        private val TEMPLATE_EVENT_TYPES = setOf("xhr_send", "xhr_done", "fetch_send", "fetch_done")
        private val SAFE_REPLAY_METHODS = setOf("GET", "HEAD")
        private val MUTATING_PATH_HINTS = listOf(
            "logout",
            "signout",
            "delete",
            "remove",
            "mark_read",
            "mark-read",
            "vote",
            "like",
            "follow",
            "unfollow",
            "subscribe",
            "unsubscribe",
            "create",
            "update",
            "edit",
            "publish",
            "send",
            "archive",
            "cancel",
            "join",
            "leave",
            "star",
            "pin",
            "enable",
            "disable",
            "mutate",
            "mutation",
            "write",
            "save",
            "checkout",
            "purchase",
            "payment",
            "pay",
            "submit",
            "confirm",
        )
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun resolveUrl(baseUrl: String?, rawUrl: String): String {
            return runCatching {
                val raw = URI(rawUrl)
                if (raw.isAbsolute) raw.toString() else URI(baseUrl ?: "").resolve(raw).toString()
            }.getOrDefault(rawUrl)
        }

        fun originOf(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return runCatching {
                val uri = URI(url)
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                val host = uri.host?.lowercase(Locale.ROOT)
                if ((scheme == "http" || scheme == "https") && host != null) {
                    val port = if (uri.port >= 0) ":${uri.port}" else ""
                    "$scheme://$host$port"
                } else {
                    null
                }
            }.getOrNull()
        }

        fun redactedPath(url: String): String {
            return runCatching {
                val uri = URI(url)
                val path = uri.rawPath?.ifBlank { "/" } ?: "/"
                val queryNames = uri.rawQuery
                    ?.split("&")
                    ?.mapNotNull { pair -> pair.substringBefore("=", "").takeIf { it.isNotBlank() } }
                    ?.distinct()
                    .orEmpty()
                if (queryNames.isEmpty()) {
                    path
                } else {
                    "$path?${queryNames.joinToString("&") { "$it=<redacted>" }}"
                }
            }.getOrDefault(url.substringBefore("?"))
        }

        fun redactedUrl(url: String): String {
            return runCatching {
                val uri = URI(url)
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                val host = uri.host?.lowercase(Locale.ROOT)
                if ((scheme == "http" || scheme == "https") && host != null) {
                    val port = if (uri.port >= 0) ":${uri.port}" else ""
                    "$scheme://$host$port${redactedPath(url)}"
                } else {
                    redactedPath(url)
                }
            }.getOrDefault(url.substringBefore("?"))
        }

        fun isProbablyMutatingReplayUrl(url: String): Boolean {
            val haystack = runCatching {
                val uri = URI(url)
                "${uri.rawPath.orEmpty()}?${uri.rawQuery.orEmpty()}".lowercase(Locale.ROOT)
            }.getOrDefault(url.lowercase(Locale.ROOT))
            val tokens = Regex("[a-z0-9_-]+")
                .findAll(haystack)
                .map { it.value }
                .toList()
            if (tokens.any { token -> MUTATING_PATH_HINTS.any { hint -> hint in token } }) return true
            return runCatching {
                val uri = URI(url)
                uri.rawQuery
                    ?.split("&")
                    ?.map { pair -> pair.substringBefore("=") to pair.substringAfter("=", "") }
                    ?.any { (key, value) ->
                        val k = key.lowercase(Locale.ROOT)
                        val v = value.lowercase(Locale.ROOT)
                        k in setOf("action", "op", "cmd", "command", "mutation", "method") &&
                            MUTATING_PATH_HINTS.any { hint -> hint in v }
                    } == true
            }.getOrDefault(false)
        }

        private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull()

        private fun templateId(method: String, rawUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$method $rawUrl".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            return "wmreq_$digest"
        }

        private fun contentKind(text: String): String {
            val trimmed = text.trim()
            return when {
                trimmed.isBlank() -> "none"
                trimmed.startsWith("{") -> "json_object"
                trimmed.startsWith("[") -> "json_array"
                trimmed.startsWith("<") -> "html_or_xml"
                else -> "text"
            }
        }

        private fun responseShape(text: String): JsonObject? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
            return runCatching {
                when (val parsed = JSON.parseToJsonElement(trimmed)) {
                    is JsonObject -> objectShape(parsed)
                    is JsonArray -> buildJsonObject {
                        put("kind", "array")
                        put("size", parsed.size)
                        (parsed.firstOrNull() as? JsonObject)?.let { first ->
                            put("first_keys", keyArray(first.keys))
                        }
                    }
                    else -> null
                }
            }.getOrNull()
        }

        private fun objectShape(obj: JsonObject): JsonObject = buildJsonObject {
            put("kind", "object")
            put("top_keys", keyArray(obj.keys))
            (obj["data"] as? JsonObject)?.let { data ->
                put("data_keys", keyArray(data.keys))
            }
        }

        private fun keyArray(keys: Set<String>) = buildJsonArray {
            keys.take(20).forEach { add(JsonPrimitive(it)) }
        }
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull
