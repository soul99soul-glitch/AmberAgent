package me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.util.Locale

internal object FeishuDocsNetworkSummary {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tokenLike = Regex("""[A-Za-z0-9_-]{16,}""")

    fun summarize(sessionId: String, snapshot: JsonElement): JsonObject {
        val root = snapshot as? JsonObject ?: return errorJson("invalid_network_snapshot", "Network snapshot is not an object")
        val events = runCatching { root["events"]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
        val summarized = events.mapNotNull { event ->
            summarizeEvent(event as? JsonObject ?: return@mapNotNull null)
        }
        return buildJsonObject {
            put("ok", true)
            put("session_id", sessionId)
            put("last_event_seq", root["last_event_seq"] ?: JsonPrimitive(0))
            put("buffer_newest_seq", root["buffer_newest_seq"] ?: JsonPrimitive(0))
            put("more_available", root["more_available"] ?: JsonPrimitive(false))
            put("count", summarized.size)
            put("ignored_non_feishu_events", events.size - summarized.size)
            put("events", buildJsonArray { summarized.forEach { add(it) } })
            put(
                "privacy",
                "sanitized: no cookies, authorization headers, full query strings, request bodies, or raw response bodies",
            )
        }
    }

    private fun summarizeEvent(event: JsonObject): JsonObject? {
        val url = event.s("url").orEmpty()
        val uri = parseUri(url)
        val host = uri?.host.orEmpty()
        if (!isFeishuHost(host)) return null
        val path = uri?.rawPath ?: url.takeWhile { it != '?' }.take(160)
        val responsePreview = event.s("response_preview")
        val shape = responseShape(responsePreview)
        val candidate = isDocCandidate(host, path)
        return buildJsonObject {
            put("event_ref", "wm_seq_${event.s("seq") ?: "unknown"}")
            put("type", event.s("type") ?: "unknown")
            put("method", event.s("method") ?: "GET")
            put("host", host)
            put("endpoint_key", endpointKey(host, path))
            event.i("status")?.let { put("status", it) }
            put("size_bytes", event.i("response_chars") ?: 0)
            put("content_kind", contentKind(responsePreview))
            put("response_shape", shape ?: JsonNull)
            put("is_doc_candidate", candidate)
            hintFor(host, path, shape, candidate)?.let { put("hint", it) } ?: put("hint", JsonNull)
        }
    }

    private fun endpointKey(host: String, path: String): String {
        val safePath = path
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/", prefix = "/") { segment ->
                when {
                    segment.all { it.isDigit() } -> "{id}"
                    tokenLike.matches(segment) -> "{token}"
                    else -> segment.take(48)
                }
            }
            .ifBlank { "/" }
        return "${host.lowercase(Locale.ROOT)}$safePath"
    }

    private fun isDocCandidate(host: String, path: String): Boolean {
        val p = path.lowercase(Locale.ROOT)
        val docPath = listOf("doc", "docx", "wiki", "drive", "suite", "space", "block", "raw_content")
            .any { it in p }
        return isFeishuHost(host) && docPath
    }

    private fun isFeishuHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        return h == "feishu.cn" || h.endsWith(".feishu.cn") ||
            h == "larksuite.com" || h.endsWith(".larksuite.com") ||
            h == "larkoffice.com" || h.endsWith(".larkoffice.com") ||
            h == "feishu.net" || h.endsWith(".feishu.net")
    }

    private fun hintFor(host: String, path: String, shape: JsonObject?, candidate: Boolean): String? {
        if (!candidate) return null
        val p = path.lowercase(Locale.ROOT)
        return when {
            "raw_content" in p -> "official_docx_raw_content"
            "/blocks" in p || "block" in p -> "doc_block_structure_candidate"
            "wiki" in p -> "wiki_resolution_candidate"
            "search" in p -> "docs_search_candidate"
            shape?.array("top_keys")?.any { it.jsonPrimitive.contentOrNull == "data" } == true -> "feishu_json_envelope"
            else -> "feishu_doc_candidate"
        }
    }

    private fun contentKind(text: String?): String {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isBlank()) return "none"
        return when {
            trimmed.startsWith("{") -> "json_object"
            trimmed.startsWith("[") -> "json_array"
            trimmed.startsWith("<") -> "html_or_xml"
            else -> "text"
        }
    }

    private fun responseShape(text: String?): JsonObject? {
        val trimmed = text?.trim().orEmpty()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return runCatching {
            when (val parsed = json.parseToJsonElement(trimmed)) {
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

    private fun keyArray(keys: Set<String>): JsonArray = buildJsonArray {
        keys.take(20).forEach { add(JsonPrimitive(it)) }
    }

    private fun parseUri(url: String): URI? = runCatching {
        URI(url)
    }.getOrNull()?.takeIf { !it.host.isNullOrBlank() }

    private fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
            put("next_action", "Call wm_state to verify the WebMount session network log.")
        })
    }

    private fun JsonObject.s(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.i(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.array(name: String): JsonArray =
        runCatching { this[name]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
}
