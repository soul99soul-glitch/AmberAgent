package app.amber.ai.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
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
    } catch (e: Exception) {
        trySend(SseEvent.Failure(e))
        close()
        return@callbackFlow
    }
    trySend(SseEvent.Closed)
    close()
    awaitClose { }
}
