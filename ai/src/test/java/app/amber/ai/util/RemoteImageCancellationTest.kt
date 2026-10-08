package app.amber.ai.util

import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

class RemoteImageCancellationTest {
    @Test fun cancellingAfterHeadersCancelsTheSameCallDuringBodyRead() = runBlocking {
        val readingBody = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean()
        val source = object : Source {
            val bytes = Buffer().writeUtf8("finite image fixture")
            var first = true
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (first) {
                    first = false
                    readingBody.complete(Unit)
                    Thread.sleep(300) // finite even if cancellation wiring regresses
                }
                return bytes.read(sink, byteCount)
            }
            override fun close() = Unit
            override fun timeout() = Timeout.NONE
        }.buffer()
        var callbackThread: Thread? = null
        val factory = Call.Factory { request ->
            object : Call {
                override fun request() = request
                override fun enqueue(responseCallback: Callback) {
                    callbackThread = Thread {
                        responseCallback.onResponse(this, Response.Builder().request(request)
                            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(object : ResponseBody() {
                                override fun contentType() = "image/png".toMediaType()
                                override fun contentLength() = -1L
                                override fun source(): BufferedSource = source
                            }).build())
                    }.apply { start() }
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
        }
        try {
            val reading = async(Dispatchers.IO) {
                UIMessagePart.Image("https://example.invalid/image.png").resolveRemoteImage(factory)
            }
            withTimeout(2_000) { readingBody.await() }
            withTimeout(2_000) { reading.cancelAndJoin() }
            assertTrue("Call cancellation must stay attached after response headers", cancelled.get())
        } finally {
            callbackThread?.join(1_000)
        }
    }
}
