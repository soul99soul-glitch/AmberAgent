package app.amber.tts.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TtsHttpCallTest {

    @Test
    fun `cancelling body read cancels the synchronous TTS call`() = runBlocking {
        val server = ServerSocket(0)
        val headersSent = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val serverThread = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                val out = socket.getOutputStream()
                out.write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\nx"
                        .toByteArray()
                )
                out.flush()
                headersSent.countDown()
                releaseServer.await(5, TimeUnit.SECONDS)
            }
        }.apply { start() }

        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/speech")
                .build()
            val reading = async(Dispatchers.IO) {
                OkHttpClient().newCall(request).awaitAndUseCancellable { response ->
                    response.body.source().readByteArray()
                }
            }
            assertTrue(headersSent.await(1, TimeUnit.SECONDS))

            withTimeout(1_000) {
                reading.cancelAndJoin()
            }
        } finally {
            releaseServer.countDown()
            server.close()
            serverThread.join(1_000)
        }
    }
}
