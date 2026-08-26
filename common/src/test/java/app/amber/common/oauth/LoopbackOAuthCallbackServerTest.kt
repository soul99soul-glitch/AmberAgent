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
            assertTrue(response.contains("授权完成"))
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
