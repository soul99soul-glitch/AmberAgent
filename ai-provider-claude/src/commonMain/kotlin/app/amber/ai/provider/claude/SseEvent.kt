package app.amber.ai.provider.claude

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
 * Mirror of `:ai-provider-openai`'s SseEvent (itself adapted from the
 * Android-only `common/.../http/SSE.kt`). Duplicated here so the Claude
 * provider module stays free of a cross-provider dependency. Error handling
 * follows the same simplification: the HTTP status/body stays on the original
 * Ktor exception rather than being enriched here.
 */
sealed class SseEvent {
    data object Open : SseEvent()

    data class Event(val id: String?, val type: String?, val data: String) : SseEvent()

    data object Closed : SseEvent()

    data class Failure(val throwable: Throwable?) : SseEvent()
}

/** Per-collection completion marker for the Anthropic event sequence. */
internal class ClaudeStreamTerminalState {
    private var completed = false

    fun observe(type: String?, data: String) {
        if (type == "message_stop" || data.trim() == "[DONE]") {
            completed = true
        }
    }

    fun requireCompleted() {
        if (!completed) {
            throw IllegalStateException("Claude stream ended before message_stop")
        }
    }
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
    // 全部用挂起 send 而非 trySend:trySend 在缓冲满时静默丢弃事件,下游慢一拍
    // 就会丢掉 content_block_delta,而 Claude 的流是纯增量——丢一个 delta 等于
    // 正文缺一段,且 message_stop 仍会到达,于是残缺答案被当成功交付。
    // 与 `:ai-provider-openai` 的 sseFlow 逐行同构(含 catch 分支)。
    send(SseEvent.Open)
    try {
        this@sseFlow.sse(urlString = url, request = block) {
            incoming.collect { serverSentEvent ->
                send(
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
        send(SseEvent.Failure(e))
        close()
        return@callbackFlow
    }
    send(SseEvent.Closed)
    close()
    awaitClose { }
}
