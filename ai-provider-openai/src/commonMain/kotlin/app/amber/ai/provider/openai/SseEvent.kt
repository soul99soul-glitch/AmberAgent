package app.amber.ai.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.sse.ServerSentEvent
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
 * The caller must ensure the [HttpClient] has the SSE plugin installed.
 */
fun HttpClient.sseFlow(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {},
): Flow<SseEvent> = callbackFlow {
    trySend(SseEvent.Open)
    try {
        this@sseFlow.sse(urlString = url, request = block) {
            incoming.collect { serverSentEvent ->
                trySend(
                    SseEvent.Event(
                        id = serverSentEvent.id,
                        type = serverSentEvent.event,
                        data = serverSentEvent.data ?: "",
                    )
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SSEClientException) {
        // ★关键:SSE 握手失败(状态非 200)时 Ktor 抛的是 SSEClientException,不是 ResponseException。
        // 它的消息就是 "Expected status code 200 but was 500",但带着原始 HttpResponse —— 从中读出
        // 状态码与响应体,网关写明的 500 真因就在 body 里(此前一直被丢弃,导致只能瞎猜)。
        val resp = e.response
        val status = resp?.status?.value
        val body = resp?.let { runCatching { it.bodyAsText() }.getOrNull() }.orEmpty()
        val detail = buildString {
            append(status?.let { "HTTP $it" } ?: (e.message ?: "SSE failed"))
            if (body.isNotBlank()) append(": ").append(body.take(1200))
        }
        trySend(SseEvent.Failure(Exception(detail, e)))
        close()
        return@callbackFlow
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
