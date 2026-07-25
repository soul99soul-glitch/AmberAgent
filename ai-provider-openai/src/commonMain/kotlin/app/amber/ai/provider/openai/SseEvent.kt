package app.amber.ai.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Represents events in an SSE connection.
 *
 * Adapted from `common/src/main/java/app/amber/common/http/SSE.kt` for the KMP
 * OpenAI provider. Error handling is simplified — the HTTP status/body is left
 * on the original Ktor exception rather than being enriched here.
 */
sealed class SseEvent {
    data object Open : SseEvent()

    data class Event(val id: String?, val type: String?, val data: String) : SseEvent()

    data object Closed : SseEvent()

    data class Failure(val throwable: Throwable?) : SseEvent()
}

/**
 * Incremental SSE line parser with one narrow OpenAI-compatible extension:
 * a complete JSON `data:` line (or `[DONE]`) is an event by itself even when
 * a proxy omits the spec's blank separator. Incomplete/multiline data keeps
 * the standard blank-line behavior.
 */
internal class OpenAISseLineParser {
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    private var id: String? = null
    private var type: String? = null
    private val dataLines = mutableListOf<String>()

    fun consume(rawLine: String): SseEvent.Event? {
        val line = rawLine.trimEnd('\r')
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null

        val trimmed = line.trim()
        if (!trimmed.startsWith("data:") && trimmed.isStandaloneOpenAIPayload()) {
            dataLines += trimmed
            return flush()
        }

        val colonIndex = line.indexOf(':')
        val field = if (colonIndex >= 0) line.substring(0, colonIndex) else line
        val rawValue = if (colonIndex >= 0) line.substring(colonIndex + 1) else ""
        val value = rawValue.removePrefix(" ")
        when (field) {
            "id" -> id = value
            "event" -> type = value
            "data" -> {
                dataLines += value
                if (value.isStandaloneOpenAIPayload()) return flush()
            }
        }
        return null
    }

    fun finish(): SseEvent.Event? = flush()

    private fun flush(): SseEvent.Event? {
        val event = if (id != null || type != null || dataLines.isNotEmpty()) {
            SseEvent.Event(id = id, type = type, data = dataLines.joinToString("\n"))
        } else {
            null
        }
        id = null
        type = null
        dataLines.clear()
        return event
    }

    private fun String.isStandaloneOpenAIPayload(): Boolean {
        var payload = trim()
        while (payload.startsWith("data:")) {
            payload = payload.removePrefix("data:").trimStart()
        }
        return payload == "[DONE]" ||
            runCatching { json.parseToJsonElement(payload) is JsonObject }.getOrDefault(false)
    }
}

internal enum class OpenAIStreamKind {
    CHAT_COMPLETIONS,
    RESPONSES,
}

/** Per-collection protocol completion check. It deliberately has no global state. */
internal class OpenAIStreamTerminalState(
    private val kind: OpenAIStreamKind,
) {
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    private var completed = false

    fun observe(data: String) {
        data.lineSequence()
            .map { it.trim().withoutSseDataPrefix() }
            .filter { it.isNotBlank() }
            .forEach { payload ->
                if (payload == "[DONE]") {
                    completed = true
                    return@forEach
                }
                val objectPayload = runCatching { json.parseToJsonElement(payload) as? JsonObject }
                    .getOrNull() ?: return@forEach
                completed = completed || when (kind) {
                    OpenAIStreamKind.CHAT_COMPLETIONS ->
                        (objectPayload["choices"] as? JsonArray).orEmpty().any { choice ->
                            val choiceObject = choice as? JsonObject ?: return@any false
                            (choiceObject["finish_reason"] as? JsonPrimitive)?.contentOrNull != null
                        }

                    // `response.incomplete` 同样是协议终态(输出写满上限 / 内容过滤),
                    // 只是内容未写完。把它排除在终态之外,会让一次正常的上限截断在
                    // 连接关闭时再被误报成"流在终态前断开"。
                    OpenAIStreamKind.RESPONSES -> {
                        val eventType = (objectPayload["type"] as? JsonPrimitive)?.contentOrNull
                        eventType == "response.completed" || eventType == "response.incomplete"
                    }
                }
            }
    }

    fun requireCompleted() {
        if (!completed) {
            throw IllegalStateException("OpenAI ${kind.name.lowercase()} stream ended before a terminal event")
        }
    }

    private fun String.withoutSseDataPrefix(): String {
        var value = this
        while (value.startsWith("data:")) {
            value = value.removePrefix("data:").trimStart()
        }
        return value
    }
}

/**
 * Wraps Ktor's SSE client into a reactive [Flow] of [SseEvent].
 *
 * Ktor's SSE plugin is flaky with Darwin in this app: successful HTTP 200
 * streams can be surfaced as SSEClientException with a buffered body, which
 * destroys real-time streaming. Read the response channel directly instead.
 */
fun HttpClient.sseFlow(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {},
): Flow<SseEvent> = callbackFlow {
    send(SseEvent.Open)
    try {
        this@sseFlow.prepareRequest(url) {
            block()
            header(HttpHeaders.Accept, "text/event-stream")
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                val detail = if (body.isBlank()) {
                    "HTTP ${response.status.value}"
                } else {
                    "HTTP ${response.status.value}: ${body.take(1200)}"
                }
                send(SseEvent.Failure(Exception(detail)))
                return@execute
            }

            val channel = response.bodyAsChannel()
            val parser = OpenAISseLineParser()

            while (true) {
                val rawLine = channel.readUTF8Line() ?: break
                parser.consume(rawLine)?.let { send(it) }
            }
            parser.finish()?.let { send(it) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        val status = e.response.status.value
        val body = runCatching { e.response.bodyAsText() }.getOrDefault("")
        val detail = if (body.isBlank()) "HTTP $status" else "HTTP $status: ${body.take(1200)}"
        send(SseEvent.Failure(Exception(detail, e)))
        close()
        return@callbackFlow
    } catch (e: Exception) {
        send(SseEvent.Failure(e))
        close()
        return@callbackFlow
    }
    send(SseEvent.Closed)
    close()
    awaitClose { }
}
