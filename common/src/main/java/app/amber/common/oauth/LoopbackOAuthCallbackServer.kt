package app.amber.common.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

data class OAuthCallbackResult(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
) {
    val isSuccess: Boolean get() = error == null && !code.isNullOrBlank()
}

/** Human-readable strings supplied by the Android app's current locale. */
data class LoopbackOAuthCopy(
    val successMessage: String,
    val failureMessage: (String) -> String,
    val notFoundMessage: String,
    val bindFailure: String,
    val serverClosed: String,
    val requestLineEmpty: String,
    val headersUnterminated: String,
    val requestTooLarge: String,
    val invalidRequest: String,
    val waitingForCallback: String,
    val missingQuery: String,
    val requestLineUnterminated: String,
) {
    companion object {
        val ENGLISH = LoopbackOAuthCopy(
            successMessage = "Authorization complete. You may close this page.",
            failureMessage = { reason -> "Authorization failed: $reason" },
            notFoundMessage = "Not found.",
            bindFailure = "Unable to bind the local callback port.",
            serverClosed = "The local callback server was closed.",
            requestLineEmpty = "The request line was empty.",
            headersUnterminated = "The request headers were not terminated.",
            requestTooLarge = "The request exceeded the size limit.",
            invalidRequest = "The HTTP request could not be parsed.",
            waitingForCallback = "Waiting for GET /callback.",
            missingQuery = "The callback did not include query parameters.",
            requestLineUnterminated = "The request line was not terminated.",
        )
    }
}

/** A single-use RFC 8252 loopback redirect listener for the Google and Feishu OAuth flows. */
class LoopbackOAuthCallbackServer(
    val port: Int = DEFAULT_PORT,
    private val acceptedSocketReadTimeoutMillis: Int = DEFAULT_ACCEPTED_SOCKET_READ_TIMEOUT_MILLIS,
    private val copy: LoopbackOAuthCopy = LoopbackOAuthCopy.ENGLISH,
) : Closeable {
    private val serverSocket: ServerSocket = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
    } catch (error: Exception) {
        throw IllegalStateException(
            copy.bindFailure,
            error,
        )
    }
    private val activeSocket = AtomicReference<Socket?>()

    suspend fun awaitCallback(): OAuthCallbackResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            try {
                while (continuation.isActive) {
                    val client = serverSocket.accept()
                    activeSocket.set(client)
                    if (!continuation.isActive || serverSocket.isClosed) {
                        runCatching { client.close() }
                        if (continuation.isActive) {
                            continuation.resume(failure("loopback_accept_failed", copy.serverClosed))
                        }
                        return@suspendCancellableCoroutine
                    }

                    val request = client.use {
                        it.soTimeout = acceptedSocketReadTimeoutMillis
                        handleConnection(it)
                    }
                    activeSocket.compareAndSet(client, null)
                    if (request.terminal) {
                        close()
                        if (continuation.isActive) continuation.resume(request.result)
                        return@suspendCancellableCoroutine
                    }
                }
            } catch (error: Exception) {
                close()
                if (continuation.isActive) {
                    continuation.resume(
                        failure("loopback_accept_failed", error.message ?: copy.serverClosed),
                    )
                }
            }
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        runCatching { activeSocket.getAndSet(null)?.close() }
    }

    private fun handleConnection(socket: Socket): HandledRequest {
        val handled = try {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input, MAX_REQUEST_LINE_BYTES)
                ?: throw MalformedRequestException(copy.requestLineEmpty)
            var headerBytes = 0
            while (true) {
                val header = readLine(input, MAX_REQUEST_LINE_BYTES)
                    ?: throw MalformedRequestException(copy.headersUnterminated)
                headerBytes += header.length + 2
                if (headerBytes > MAX_HEADER_BYTES) throw RequestTooLargeException()
                if (header.isEmpty()) break
            }
            parseRequestLine(requestLine)
        } catch (_: RequestTooLargeException) {
            HandledRequest(
                result = failure("request_too_large", copy.requestTooLarge),
                terminal = true,
                statusCode = 400,
            )
        } catch (error: SocketTimeoutException) {
            HandledRequest(
                result = failure("loopback_accept_failed", error.message),
                terminal = true,
                statusCode = 400,
            )
        } catch (error: MalformedRequestException) {
            HandledRequest(
                result = failure("invalid_request", error.message),
                terminal = true,
                statusCode = 400,
            )
        }
        writeResponse(socket, handled)
        return handled
    }

    private fun parseRequestLine(line: String): HandledRequest {
        val parts = line.split(' ', limit = 3)
        if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank() || !parts[2].startsWith("HTTP/")) {
            return HandledRequest(
                result = failure("invalid_request", copy.invalidRequest),
                terminal = true,
                statusCode = 400,
            )
        }

        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        if (method != "GET" || path != "/callback") {
            return HandledRequest(
                result = failure("ignored_non_callback_path", copy.waitingForCallback),
                terminal = false,
                statusCode = 404,
            )
        }

        val queryStart = target.indexOf('?')
        if (queryStart < 0) {
            return HandledRequest(
                result = failure("missing_query", copy.missingQuery),
                terminal = true,
                statusCode = 200,
            )
        }
        val params = parseQuery(target.substring(queryStart + 1))
        return HandledRequest(
            result = OAuthCallbackResult(
                code = params["code"],
                state = params["state"],
                error = params["error"],
                errorDescription = params["error_description"],
            ),
            terminal = true,
            statusCode = 200,
        )
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val equals = pair.indexOf('=')
            if (equals <= 0) return@mapNotNull null
            val key = runCatching { URLDecoder.decode(pair.substring(0, equals), "UTF-8") }
                .getOrNull() ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(pair.substring(equals + 1), "UTF-8") }
                .getOrNull() ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    private fun writeResponse(socket: Socket, request: HandledRequest) {
        val body = when {
            request.statusCode == 404 -> pageHtml(copy.notFoundMessage)
            request.result.isSuccess -> pageHtml(copy.successMessage)
            else -> pageHtml(
                copy.failureMessage(
                    request.result.error.orEmpty().ifBlank { "unknown" } +
                        (request.result.errorDescription?.let { ": $it" } ?: ""),
                )
            )
        }.toByteArray(Charsets.UTF_8)
        val response = buildString {
            append("HTTP/1.1 ")
            append(if (request.statusCode == 200) "200 OK" else if (request.statusCode == 404) "404 Not Found" else "400 Bad Request")
            append("\r\nContent-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size)
            append("\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        runCatching {
            socket.getOutputStream().apply {
                write(response)
                write(body)
                flush()
            }
        }
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            when (val value = input.read()) {
                -1 -> {
                    if (bytes.size() == 0) return null
                    throw MalformedRequestException(copy.requestLineUnterminated)
                }
                '\n'.code -> return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
                else -> {
                    if (bytes.size() >= maxBytes) throw RequestTooLargeException()
                    bytes.write(value)
                }
            }
        }
    }

    private data class HandledRequest(
        val result: OAuthCallbackResult,
        val terminal: Boolean,
        val statusCode: Int,
    )

    private class RequestTooLargeException : IOException()

    private class MalformedRequestException(message: String) : IOException(message)

    companion object {
        private const val DEFAULT_ACCEPTED_SOCKET_READ_TIMEOUT_MILLIS = 10_000
        private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024

        const val DEFAULT_PORT = 53682
        const val DEFAULT_REDIRECT_URI = "http://127.0.0.1:$DEFAULT_PORT/callback"

        private fun failure(error: String, description: String?): OAuthCallbackResult =
            OAuthCallbackResult(null, null, error, description)

        private fun pageHtml(message: String): String =
            "<!doctype html><html><body>${htmlEscape(message)}</body></html>"

        private fun htmlEscape(value: String): String = buildString(value.length) {
            for (char in value) {
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(char)
                }
            }
        }
    }
}
