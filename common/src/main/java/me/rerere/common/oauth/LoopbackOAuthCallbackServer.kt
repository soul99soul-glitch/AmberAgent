package me.rerere.common.oauth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.coroutines.resume

/**
 * Provider-agnostic OAuth callback payload. Mirrors the standard authorization-code
 * response shape: `code` / `state` / `error` / `error_description`. Lives in `common`
 * so both the WebMount layer (`app` module) and the AI provider OAuth clients (`ai`
 * module — Codex / Gemini etc.) can speak the same type without one depending on the
 * other.
 *
 * Callers map this into their own domain types if needed (e.g. WebMount's
 * `OAuthCallback` carries an extra `provider` tag for dispatcher routing).
 */
data class OAuthCallbackResult(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
) {
    val isSuccess: Boolean get() = error == null && !code.isNullOrBlank()
}

/**
 * Tiny single-shot HTTP server bound to `127.0.0.1:<port>`, used to capture an OAuth
 * provider's authorization redirect when the provider refuses custom-scheme deep
 * links. Pattern follows RFC 8252 §7.3 ("OAuth 2.0 for Native Apps — Loopback
 * Interface Redirection") and matches what the Lark / GitHub / AWS CLIs do natively.
 *
 * Why: Feishu's developer console silently rejects `amberagent://oauth/feishu` —
 * after registration the redirect URL list stays empty and Feishu's authorize
 * endpoint returns error 20029 "redirect_uri 请求不合法" on every authorization
 * attempt. Google's installed-app OAuth client (used by gemini-cli's public
 * client_id) likewise only allows loopback redirects. Both providers can be
 * satisfied with a `http://127.0.0.1:<port>/callback` redirect.
 *
 * Lifecycle:
 *   1. Construct on a fixed [port] — binds immediately, throws on conflict.
 *      Use [port] to assemble the redirect_uri before launching the browser.
 *   2. [awaitCallback] suspends on `ServerSocket.accept()` until the provider
 *      redirects back. Cancellation closes the socket and unblocks accept().
 *   3. [close] (or `use { }`) tears down the socket on exit.
 *
 * Single-shot: we accept exactly one OAuth callback (browser probes for favicon /
 * HEAD prefetch are filtered as 404s and we keep listening), parse the GET request
 * line, write a static HTML "you can close this tab" page, then stop. Any browser
 * follow-up requests after the real callback hit a closed port — fine.
 *
 * Port choice: 53682 lines up with what `gh` (GitHub CLI) and several gemini-cli
 * forks use; not IANA-reserved, unlikely to collide with real services. We do
 * NOT try a port range — Feishu requires exact-string redirect_uri match in its
 * console, so the user has to register one URL with one port; offering a range
 * would force them to add three URLs to the allowlist.
 */
class LoopbackOAuthCallbackServer(
    val port: Int = DEFAULT_PORT,
) : Closeable {
    private val serverSocket: ServerSocket = try {
        ServerSocket(port, /* backlog */ 1, InetAddress.getByName("127.0.0.1"))
    } catch (error: Exception) {
        throw IllegalStateException(
            "无法绑定 127.0.0.1:$port — 另一个 app 可能正在使用此端口。" +
                "如果你刚跑过 gh / gemini-cli 之类的命令行 OAuth 工具，请先结束它们再重试。",
            error,
        )
    }

    /** Block on accept() until the provider redirects back. Returns an
     *  [OAuthCallbackResult] with code/state/error parsed from the query string.
     *
     *  Threading note: `accept()` is a blocking syscall that runs on Dispatchers.IO via
     *  the surrounding `withContext`. The IO pool is sized to 64 threads by default, so
     *  parking one for the typical 5-min OAuth timeout ceiling is fine; if we ever need
     *  more concurrent loopback OAuth flows in flight, swap to a dedicated single-thread
     *  executor or NIO Selector. */
    suspend fun awaitCallback(): OAuthCallbackResult = withContext(Dispatchers.IO) {
        // suspendCancellableCoroutine wires the coroutine cancellation through to a
        // serverSocket.close() — accept() then throws SocketException and we resume
        // with cancellation. Without this, a coroutine cancel (timeout, user aborts)
        // would leave accept() blocking indefinitely on a daemon thread.
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { close() }
            try {
                // Browsers often pre-fetch favicon.ico / send HEAD probes before they
                // actually navigate to the OAuth redirect. If the first accept burns on
                // one of those, we'd miss the real callback. Loop until we see a GET
                // request whose path starts with `/callback` (or until accept throws
                // because we got cancelled). Treat any other request as noise: respond
                // 404, close, and accept the next one.
                while (true) {
                    val client = serverSocket.accept()
                    val handled = client.use { handleConnection(it) }
                    if (handled.isCallbackPath) {
                        if (cont.isActive) {
                            cont.resume(handled.callback)
                        } else {
                            // The callback arrived but the coroutine was cancelled (timeout
                            // or caller abort) between accept() returning and us resuming.
                            // Log loudly so a user who sees "OAuth timed out" right after
                            // a successful provider redirect can still diagnose from logcat
                            // instead of silently being told "try again".
                            Log.w(
                                TAG,
                                "Loopback OAuth callback received after coroutine cancel; " +
                                    "dropping code (state=${handled.callback.state?.take(6)}…)",
                            )
                        }
                        return@suspendCancellableCoroutine
                    }
                    // Non-callback request (favicon, prefetch, probe). handled.callback is
                    // already a synthetic error stub; ignore it and accept the next one.
                }
            } catch (error: Throwable) {
                if (cont.isActive) {
                    cont.resume(
                        OAuthCallbackResult(
                            code = null,
                            state = null,
                            error = "loopback_accept_failed",
                            errorDescription = error.message,
                        )
                    )
                }
            }
        }
    }

    /** Result of one accept() iteration. [isCallbackPath] gates whether we keep listening
     *  or return the parsed callback to the caller. */
    private data class HandledRequest(val callback: OAuthCallbackResult, val isCallbackPath: Boolean)

    override fun close() {
        runCatching { serverSocket.close() }
    }

    // ----------------------------------------------------------------------

    private fun handleConnection(socket: Socket): HandledRequest {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine().orEmpty()
        // Drain headers — must consume until the empty line, otherwise some browsers
        // log "ERR_EMPTY_RESPONSE" in their console even though the user got our HTML.
        while (true) {
            val header = reader.readLine().orEmpty()
            if (header.isEmpty()) break
        }
        val handled = parseRequestLine(requestLine)
        writeResponse(socket, handled)
        return handled
    }

    private fun parseRequestLine(line: String): HandledRequest {
        // "GET /callback?code=xxx&state=yyy HTTP/1.1"
        val parts = line.split(" ")
        if (parts.size < 2) {
            return HandledRequest(
                callback = OAuthCallbackResult(
                    code = null,
                    state = null,
                    error = "invalid_request_line",
                    errorDescription = "Loopback server received an unparseable request: $line",
                ),
                isCallbackPath = false,
            )
        }
        val method = parts[0]
        val pathQuery = parts[1]
        // Only GET to /callback is a real OAuth redirect. Anything else is a browser
        // probe / favicon / HEAD prefetch — return a 404 stub, signal caller to keep
        // listening.
        val pathOnly = pathQuery.substringBefore('?')
        val isCallbackPath = method == "GET" && pathOnly == "/callback"
        if (!isCallbackPath) {
            return HandledRequest(
                callback = OAuthCallbackResult(
                    code = null,
                    state = null,
                    error = "ignored_non_callback_path",
                    errorDescription = "Loopback ignored $method $pathOnly (waiting for GET /callback)",
                ),
                isCallbackPath = false,
            )
        }
        val queryStart = pathQuery.indexOf('?')
        if (queryStart < 0) {
            return HandledRequest(
                callback = OAuthCallbackResult(
                    code = null,
                    state = null,
                    error = "missing_query",
                    errorDescription = "Loopback callback path had no query string: $pathQuery",
                ),
                isCallbackPath = true,
            )
        }
        val query = pathQuery.substring(queryStart + 1)
        val params = parseQuery(query)
        // Duplicate-key note: OAuth requires single code / state so we take last-wins.
        // Not an injection vector — the URL is constructed by the IDP on the user's
        // own browser navigating to 127.0.0.1, no external party can shape it.
        return HandledRequest(
            callback = OAuthCallbackResult(
                code = params["code"],
                state = params["state"],
                error = params["error"],
                errorDescription = params["error_description"],
            ),
            isCallbackPath = true,
        )
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { kv ->
            val eq = kv.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = runCatching { URLDecoder.decode(kv.substring(0, eq), "UTF-8") }.getOrNull()
                ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(kv.substring(eq + 1), "UTF-8") }.getOrNull()
                ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    private fun writeResponse(socket: Socket, handled: HandledRequest) {
        // 404 for non-callback paths so favicon / probes get a sensible status; 200 for
        // anything we actually parsed (success OR provider-side error like denied auth).
        val statusLine = if (handled.isCallbackPath) "HTTP/1.1 200 OK" else "HTTP/1.1 404 Not Found"
        val callback = handled.callback
        val html = when {
            !handled.isCallbackPath -> NOT_FOUND_HTML
            callback.isSuccess -> SUCCESS_HTML
            else -> FAILURE_HTML.replace(
                "{ERROR}",
                (callback.error ?: "unknown") +
                    (callback.errorDescription?.let { " — $it" } ?: ""),
            )
        }
        val bodyBytes = html.toByteArray(Charsets.UTF_8)
        val response = buildString {
            append(statusLine).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching {
            val out = socket.getOutputStream()
            out.write(response.toByteArray(Charsets.US_ASCII))
            out.write(bodyBytes)
            out.flush()
        }.onFailure { Log.w(TAG, "Failed writing loopback response", it) }
    }

    companion object {
        private const val TAG = "LoopbackOAuthServer"
        const val DEFAULT_PORT = 53682
        const val DEFAULT_REDIRECT_URI = "http://127.0.0.1:$DEFAULT_PORT/callback"

        private const val SUCCESS_HTML =
            "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>" +
                "<title>AmberAgent OAuth</title>" +
                "<style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;" +
                "display:flex;align-items:center;justify-content:center;height:100vh;" +
                "margin:0;background:#fafaf9;color:#1f1f1e;text-align:center}" +
                "main{max-width:380px;padding:24px}" +
                "h1{font-size:18px;margin:0 0 8px}" +
                "p{font-size:14px;color:#6a6a66;margin:0;line-height:1.5}</style>" +
                "</head><body><main><h1>授权完成</h1>" +
                "<p>可以关闭这个标签页回到 AmberAgent。</p></main></body></html>"

        private const val FAILURE_HTML =
            "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>" +
                "<title>AmberAgent OAuth</title>" +
                "<style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;" +
                "display:flex;align-items:center;justify-content:center;height:100vh;" +
                "margin:0;background:#fafaf9;color:#1f1f1e;text-align:center}" +
                "main{max-width:380px;padding:24px}" +
                "h1{font-size:18px;margin:0 0 8px;color:#b00020}" +
                "p{font-size:14px;color:#6a6a66;margin:0;line-height:1.5}</style>" +
                "</head><body><main><h1>授权失败</h1><p>{ERROR}</p></main></body></html>"

        private const val NOT_FOUND_HTML =
            "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>" +
                "<title>AmberAgent OAuth</title></head><body>" +
                "<p style='font-family:sans-serif;color:#6a6a66;padding:24px;'>" +
                "Not found.</p></body></html>"
    }
}
