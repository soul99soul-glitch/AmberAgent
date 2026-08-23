package app.amber.common.oauth

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class LoopbackOAuthCallbackServerTest {

    @Test
    fun `cancelling awaitCallback closes an already accepted client socket`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        LoopbackOAuthCallbackServer(port).use { server ->
            val awaiting = async { server.awaitCallback() }
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
            val awaiting = async { server.awaitCallback() }
            Socket("127.0.0.1", port).use {
                val result = withTimeout(1_000) { awaiting.await() }

                assertEquals("loopback_accept_failed", result.error)
            }
        }
    }
}
