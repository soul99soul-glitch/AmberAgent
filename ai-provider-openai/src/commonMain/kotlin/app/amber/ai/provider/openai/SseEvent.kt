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
    trySend(SseEvent.Open)
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
                trySend(SseEvent.Failure(Exception(detail)))
                return@execute
            }

            val channel = response.bodyAsChannel()
            var id: String? = null
            var type: String? = null
            val dataLines = mutableListOf<String>()

            fun flushEvent() {
                if (id != null || type != null || dataLines.isNotEmpty()) {
                    trySend(
                        SseEvent.Event(
                            id = id,
                            type = type,
                            data = dataLines.joinToString("\n"),
                        ),
                    )
                }
                id = null
                type = null
                dataLines.clear()
            }

            while (true) {
                val rawLine = channel.readUTF8Line() ?: break
                val line = rawLine.trimEnd('\r')
                if (line.isEmpty()) {
                    flushEvent()
                    continue
                }
                if (line.startsWith(":")) continue

                val colonIndex = line.indexOf(':')
                val field = if (colonIndex >= 0) line.substring(0, colonIndex) else line
                val rawValue = if (colonIndex >= 0) line.substring(colonIndex + 1) else ""
                val value = rawValue.removePrefix(" ")
                when (field) {
                    "id" -> id = value
                    "event" -> type = value
                    "data" -> dataLines += value
                }
            }
            flushEvent()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        val status = e.response.status.value
        val body = runCatching { e.response.bodyAsText() }.getOrDefault("")
        val detail = if (body.isBlank()) "HTTP $status" else "HTTP $status: ${body.take(1200)}"
        trySend(SseEvent.Failure(Exception(detail, e)))
        close()
        return@callbackFlow
    } catch (e: Exception) {
        trySend(SseEvent.Failure(e))
        close()
        return@callbackFlow
    }
    trySend(SseEvent.Closed)
    close()
    awaitClose { }
}
