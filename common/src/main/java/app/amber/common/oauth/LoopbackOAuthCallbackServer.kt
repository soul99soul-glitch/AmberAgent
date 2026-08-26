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

/** A single-use RFC 8252 loopback redirect listener for the Google and Feishu OAuth flows. */
class LoopbackOAuthCallbackServer(
    val port: Int = DEFAULT_PORT,
    private val acceptedSocketReadTimeoutMillis: Int = DEFAULT_ACCEPTED_SOCKET_READ_TIMEOUT_MILLIS,
) : Closeable {
    private val serverSocket: ServerSocket = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
    } catch (error: Exception) {
        throw IllegalStateException(
            "无法绑定 127.0.0.1:$port — 另一个 app 可能正在使用此端口。",
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
                            continuation.resume(failure("loopback_accept_failed", "服务器已关闭"))
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
                    continuation.resume(failure("loopback_accept_failed", error.message))
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
                ?: throw MalformedRequestException("请求行为空")
            var headerBytes = 0
            while (true) {
                val header = readLine(input, MAX_REQUEST_LINE_BYTES)
                    ?: throw MalformedRequestException("请求头未结束")
                headerBytes += header.length + 2
                if (headerBytes > MAX_HEADER_BYTES) throw RequestTooLargeException()
                if (header.isEmpty()) break
            }
            parseRequestLine(requestLine)
        } catch (_: RequestTooLargeException) {
            HandledRequest(
                result = failure("request_too_large", "请求超过大小限制"),
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
                result = failure("invalid_request", "无法解析 HTTP 请求"),
                terminal = true,
                statusCode = 400,
            )
        }

        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        if (method != "GET" || path != "/callback") {
            return HandledRequest(
                result = failure("ignored_non_callback_path", "等待 GET /callback"),
                terminal = false,
                statusCode = 404,
            )
        }

        val queryStart = target.indexOf('?')
        if (queryStart < 0) {
            return HandledRequest(
                result = failure("missing_query", "回调缺少查询参数"),
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
            request.statusCode == 404 -> NOT_FOUND_HTML
            request.result.isSuccess -> SUCCESS_HTML
            else -> FAILURE_HTML.replace(
                "{ERROR}",
                htmlEscape(
                    request.result.error.orEmpty().ifBlank { "unknown" } +
                        (request.result.errorDescription?.let { ": $it" } ?: ""),
                ),
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
                    throw MalformedRequestException("请求行未结束")
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

        private const val SUCCESS_HTML =
            "<!doctype html><html><body>授权完成，可以关闭此页面。</body></html>"
        private const val FAILURE_HTML =
            "<!doctype html><html><body>授权失败：{ERROR}</body></html>"
        private const val NOT_FOUND_HTML =
            "<!doctype html><html><body>Not found.</body></html>"

        private fun failure(error: String, description: String?): OAuthCallbackResult =
            OAuthCallbackResult(null, null, error, description)

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
