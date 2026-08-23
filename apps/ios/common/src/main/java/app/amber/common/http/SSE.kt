package app.amber.common.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 代表 SSE 连接中的各种事件
 */
sealed class SseEvent {
    /**
     * 连接成功打开
     */
    data object Open : SseEvent()

    /**
     * 收到一个具体事件
     * @param id 事件ID
     * @param type 事件类型
     * @param data 事件数据
     */
    data class Event(val id: String?, val type: String?, val data: String) : SseEvent()

    /**
     * 连接被关闭
     */
    data object Closed : SseEvent()

    /**
     * 发生错误
     * @param throwable 异常信息
     */
    data class Failure(val throwable: Throwable?) : SseEvent()
}

/**
 * 为 Ktor HttpClient 创建 SSE (Server-Sent Events) 连接的扩展函数
 *
 * 将 Ktor 的 SSE 客户端封装成 Kotlin Flow，提供响应式的 SSE 事件流
 * 复用 [SseEvent] 体系保持 API 一致性
 *
 * 注意：调用方需确保 HttpClient 已安装 SSE 插件（[SSE]）
 *
 * @param url SSE 端点 URL
 * @param block 请求配置（headers、parameters 等）
 * @return Flow<SseEvent> 包含 SSE 事件的响应式流
 */
fun HttpClient.sseFlow(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {},
): Flow<SseEvent> = callbackFlow {
    trySend(SseEvent.Open)
    try {
        this@sseFlow.sse(urlString = url, request = block) {
            incoming.collect { serverSentEvent ->
                trySend(SseEvent.Event(
                    id = serverSentEvent.id,
                    type = serverSentEvent.event,
                    data = serverSentEvent.data ?: ""
                ))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ClientRequestException) {
        // 4xx errors: extract status + response body for user-friendly messages
        val body = readResponseBody(e.response)
        val enriched = Exception("HTTP ${e.response.status.value} ${e.response.status.description}: $body", e)
        trySend(SseEvent.Failure(enriched))
        close()
        return@callbackFlow
    } catch (e: ServerResponseException) {
        // 5xx errors: extract status + response body
        val body = readResponseBody(e.response)
        val enriched = Exception("HTTP ${e.response.status.value} ${e.response.status.description}: $body", e)
        trySend(SseEvent.Failure(enriched))
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

/**
 * Safely read the response body from a Ktor HttpResponse, capping length.
 * Returns empty string if reading fails (body already consumed, etc.).
 */
private suspend fun readResponseBody(response: io.ktor.client.statement.HttpResponse): String {
    return try {
        response.bodyAsText().take(2048)
    } catch (_: Exception) {
        ""
    }
}
