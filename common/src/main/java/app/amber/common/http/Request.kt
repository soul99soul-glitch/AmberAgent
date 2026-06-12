package app.amber.common.http

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import kotlin.coroutines.resumeWithException

// region OkHttp (legacy)

/**
 * Suspend extension to execute an OkHttp [Call] asynchronously.
 */
suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
        continuation.invokeOnCancellation {
            runCatching { cancel() }
        }
    }
}

// endregion

// region Ktor

/**
 * Perform an HTTP request with optional timeout and return the response body as text.
 */
suspend fun HttpClient.await(
    url: String,
    timeoutMs: Long = 30_000,
    block: HttpRequestBuilder.() -> Unit = {},
): String = withTimeout(timeoutMs) {
    request(url) {
        block()
    }.bodyAsText()
}

// endregion
