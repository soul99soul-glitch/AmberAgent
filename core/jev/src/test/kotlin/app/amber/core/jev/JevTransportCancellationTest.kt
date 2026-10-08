package app.amber.core.jev

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/** A bounded local transport probe: headers arrive immediately, then the body blocks briefly. */
class JevTransportCancellationTest {
    @Test
    fun deadlineCancelsTheHttpCallWhileItsResponseBodyIsBeingRead() = runBlocking {
        val cancelled = AtomicBoolean()
        val bodyReadStarted = AtomicBoolean()
        val source = object : Source {
            val content = Buffer().writeUtf8("{}")
            var firstRead = true
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (firstRead) {
                    firstRead = false
                    bodyReadStarted.set(true)
                    Thread.sleep(300) // finite; the test cannot leave a stuck reader behind
                }
                return content.read(sink, byteCount)
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val transport = transportFor(source, cancelled)
        try {
            withTimeout(50) {
                transport.execute(JevHttpRequest("https://example.invalid/jev", "test-key", "{}", 50))
            }
        } catch (_: TimeoutCancellationException) {
            // Expected. The contract must cancel both header wait and blocking body read.
        }
        assertTrue("probe must reach response body read before its deadline", bodyReadStarted.get())
        assertTrue("deadline after headers must still propagate to Call.cancel", cancelled.get())
    }
    @Test
    fun oversizedUnknownLengthBodyStopsReadingAtResponseBudget() = runBlocking {
        val limit = JevLimits.MAX_RESPONSE_BODY_BYTES.toLong()
        val payload = Buffer().write(ByteArray((limit * 4).toInt()) { 'x'.code.toByte() })
        var delivered = 0L
        var closed = false
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = payload.read(sink, byteCount).also {
                if (it > 0) delivered += it
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        val result = transportFor(source).execute(
            JevHttpRequest("https://example.invalid/jev", "test-key", "{}", 1_200),
        )
        assertTrue("read must stop at budget plus one buffered source segment", delivered <= limit + 8_192)
        assertTrue("oversize input must not become a successful HTTP payload", result is JevTransportResponse.Failure)
        assertEquals("response body too large", (result as JevTransportResponse.Failure).message)
        assertTrue("rejected response still closes its source", closed)
    }

    private fun transportFor(source: BufferedSource, cancelled: AtomicBoolean = AtomicBoolean()) =
        OkHttpJevTransport(callFactory = { request ->
            object : Call {
                override fun request() = request
                override fun enqueue(responseCallback: Callback) {
                    responseCallback.onResponse(this, Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(object : ResponseBody() {
                            override fun contentType(): MediaType? = null
                            override fun contentLength() = -1L
                            override fun source(): BufferedSource = source
                        }).build())
                }
                override fun execute(): Response = error("enqueue only")
                override fun cancel() { cancelled.set(true) }
                override fun isExecuted() = true
                override fun isCanceled() = cancelled.get()
                override fun timeout() = Timeout.NONE
                override fun clone(): Call = error("unused")
                override fun <T : Any> tag(type: KClass<T>): T? = null
                override fun <T> tag(type: Class<out T>): T? = null
                override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
                override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
            }
        })
    @Test
    fun responseExactlyAtBudgetIsReturnedAndClosed() = runBlocking {
        val payload = ByteArray(JevLimits.MAX_RESPONSE_BODY_BYTES) { 'a'.code.toByte() }
        val source = Buffer().write(payload)
        val result = transportFor(source).execute(JevHttpRequest("https://example.invalid/jev", "key", "{}", 1_200))
        assertTrue(result is JevTransportResponse.Http)
        assertEquals(payload.size, (result as JevTransportResponse.Http).body?.size)
    }

}
