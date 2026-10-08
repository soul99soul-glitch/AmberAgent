package app.amber.common.oauth

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class LoopbackOAuthCallbackServerTest {
    @Test
    fun `default listener keeps redirect behavior without enabling browser fetch`() = runBlocking {
        val port = freePort()
        LoopbackOAuthCallbackServer(port).use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            val preflight = sendRequest(port, "OPTIONS /callback HTTP/1.1\r\nOrigin: https://accounts.x.ai\r\nAccess-Control-Request-Method: GET\r\n\r\n")
            val response = sendRequest(port, "GET /callback?code=abc HTTP/1.1\r\nOrigin: https://accounts.x.ai\r\n\r\n")
            assertEquals("abc", withTimeout(1_000) { awaiting.await() }.code)
            assertTrue(preflight.startsWith("HTTP/1.1 404 Not Found"))
            assertTrue(!response.contains("Access-Control-Allow-Origin:"))
        }
    }

    @Test
    fun `accounts app preflight allows private network GET without consuming callback`() = runBlocking {
        val port = freePort()
        LoopbackOAuthCallbackServer(port, allowedOrigin = "https://accounts.x.ai").use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            val preflight = sendRequest(
                port,
                "OPTIONS /callback?code=abc&state=state-1 HTTP/1.1\r\n" +
                    "Origin: https://accounts.x.ai\r\n" +
                    "Access-Control-Request-Method: GET\r\n" +
                    "Access-Control-Request-Private-Network: true\r\n\r\n",
            )
            val response = sendRequest(
                port,
                "GET /callback?code=abc&state=state-1 HTTP/1.1\r\n" +
                    "Origin: https://accounts.x.ai\r\n\r\n",
            )
            assertEquals("abc", withTimeout(1_000) { awaiting.await() }.code)
            assertTrue(preflight.startsWith("HTTP/1.1 200 OK"))
            assertTrue(preflight.contains("Access-Control-Allow-Origin: https://accounts.x.ai\r\n"))
            assertTrue(preflight.contains("Access-Control-Allow-Methods: GET\r\n"))
            assertTrue(preflight.contains("Access-Control-Allow-Private-Network: true\r\n"))
            assertTrue(response.contains("Access-Control-Allow-Origin: https://accounts.x.ai\r\n"))
        }
    }

    @Test
    fun `untrusted origin cannot consume accounts app callback`() = runBlocking {
        val port = freePort()
        LoopbackOAuthCallbackServer(port, allowedOrigin = "https://accounts.x.ai").use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            val rejected = sendRequest(
                port,
                "GET /callback?code=wrong HTTP/1.1\r\nOrigin: https://other.example\r\n\r\n",
            )
            sendRequest(port, "GET /callback?code=abc&state=state-1 HTTP/1.1\r\n\r\n")
            assertEquals("abc", withTimeout(1_000) { awaiting.await() }.code)
            assertTrue(rejected.startsWith("HTTP/1.1 403 Forbidden"))
            assertTrue(!rejected.contains("Access-Control-Allow-Origin:"))
        }
    }


    @Test
    fun `parses successful callback and returns success html`() = runBlocking {
        val port = freePort()
        LoopbackOAuthCallbackServer(port).use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            val response = sendRequest(
                port,
                "GET /callback?code=abc%20123&state=state-1 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n\r\n",
            )
            val result = withTimeout(1_000) { awaiting.await() }

            assertEquals("abc 123", result.code)
            assertEquals("state-1", result.state)
            assertEquals(null, result.error)
            assertTrue(response.contains("Authorization complete"))
        }
    }

    @Test
    fun `accepts a provider-specific callback path`() = runBlocking {
        val port = freePort()
        LoopbackOAuthCallbackServer(port = port, callbackPath = "/oauth-callback").use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            sendRequest(
                port,
                "GET /oauth-callback?code=abc&state=state-1 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n\r\n",
            )

            val result = withTimeout(1_000) { awaiting.await() }
            assertEquals("abc", result.code)
            assertEquals("state-1", result.state)
        }
    }

    @Test
    fun `returns provider error and escapes it in failure html`() = runBlocking {
        val port = freePort()
        LoopbackOAuthCallbackServer(port).use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            val response = sendRequest(
                port,
                "GET /callback?error=access_denied&error_description=%3Cdenied%3E HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n\r\n",
            )
            val result = withTimeout(1_000) { awaiting.await() }

            assertEquals("access_denied", result.error)
            assertEquals("<denied>", result.errorDescription)
            assertTrue(response.contains("&lt;denied&gt;"))
        }
    }

    @Test
    fun `rejects malformed request`() = runBlocking {
        val port = freePort()
        LoopbackOAuthCallbackServer(port).use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            val response = sendRequest(port, "not an HTTP request\r\n\r\n")
            val result = withTimeout(1_000) { awaiting.await() }

            assertEquals("invalid_request", result.error)
            assertTrue(response.contains("400 Bad Request"))
        }
    }

    @Test
    fun `rejects oversized request line`() = runBlocking {
        val port = freePort()
        LoopbackOAuthCallbackServer(port).use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            val oversized = "GET /callback?code=${"x".repeat(9_000)} HTTP/1.1\r\n\r\n"
            val response = sendRequest(port, oversized)
            val result = withTimeout(1_000) { awaiting.await() }

            assertEquals("request_too_large", result.error)
            assertTrue(response.contains("400 Bad Request"))
        }
    }

    @Test
    fun `cancelling awaitCallback closes an already accepted client socket`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        LoopbackOAuthCallbackServer(port).use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            Socket("127.0.0.1", port).use {
                delay(100)

                withTimeout(1_000) {
                    awaiting.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `accepted client that never finishes headers times out`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        LoopbackOAuthCallbackServer(
            port = port,
            acceptedSocketReadTimeoutMillis = 100,
        ).use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            Socket("127.0.0.1", port).use {
                val result = withTimeout(1_000) { awaiting.await() }

                assertEquals("loopback_accept_failed", result.error)
            }
        }
    }

    @Test
    fun `closing server unblocks awaitCallback`() = runBlocking {
        val port = freePort()
        LoopbackOAuthCallbackServer(port).use { server ->
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback() }
            delay(100)
            server.close()

            val result = withTimeout(1_000) { awaiting.await() }
            assertEquals("loopback_accept_failed", result.error)
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun sendRequest(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
}
