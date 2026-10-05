package app.amber.core.service

import android.app.Application
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.ClaudeProvider
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.OpenAIProvider
import app.amber.agent.data.db.dao.MemoryCandidateDAO
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.dao.MemoryEventDAO
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryProfileStore
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.settings.Settings
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatStartSuggestionStreamTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `stream deltas are collected into three complete suggestions`() = runBlocking {
        val streamed = AtomicBoolean()
        val server = server { exchange ->
            streamed.set(isStream(exchange))
            exchange.responseHeaders.set("Content-Type", if (streamed.get()) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                if (streamed.get()) {
                    body.write("data: {\"id\":\"s\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"整理小说\"}}]}\n\n".toByteArray())
                    body.write("data: {\"id\":\"s\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"大纲\\n优化安卓渲染\\n复盘本周计划\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
                } else {
                    body.write(completeBody.toByteArray())
                }
            }
        }
        try {
            val (generator, settings) = generator(server, OkHttpClient())
            assertEquals(listOf("整理小说大纲", "优化安卓渲染", "复盘本周计划"), generator.generate(settings, Locale.CHINESE))
            assertTrue(streamed.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `empty reasoning markers between text deltas do not split suggestion lines`() = runBlocking {
        val server = server { exchange ->
            isStream(exchange)
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write("data: {\"id\":\"s\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"\",\"content\":\"整理小说\"}}]}\n\n".toByteArray())
                body.write("data: {\"id\":\"s\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"\",\"content\":\"大纲\\n优化安卓渲染\\n复盘本周计划\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
            }
        }
        try {
            val (generator, settings) = generator(server, OkHttpClient())
            assertEquals(listOf("整理小说大纲", "优化安卓渲染", "复盘本周计划"), generator.generate(settings, Locale.CHINESE))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cancellation after headers cancels the call without waiting for the body`() = runBlocking {
        val canceled = AtomicInteger()
        val releaseBody = CountDownLatch(1)
        val bodyStarted = AtomicBoolean()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.incrementAndGet() }
        }).build()
        val server = server { exchange ->
            val stream = isStream(exchange)
            exchange.responseHeaders.set("Content-Type", if (stream) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write(if (stream) "data: {\"id\":\"s\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"整理\"}}]}\n\n".toByteArray() else "{".toByteArray())
                body.flush()
                bodyStarted.set(true)
                releaseBody.await(3, TimeUnit.SECONDS)
                if (!stream) body.write(completeBody.drop(1).toByteArray())
            }
        }
        try {
            val (generator, settings) = generator(server, client)
            val startedAt = System.nanoTime()
            try {
                withTimeout(500) { generator.generate(settings, Locale.CHINESE) }
                fail("Cancellation must not publish partial suggestions")
            } catch (_: TimeoutCancellationException) {
                // Same cancellation path as the generator's own 20s deadline.
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("Response headers/body reached the client", bodyStarted.get())
            assertTrue("Call must be canceled after headers", canceled.get() > 0)
            assertTrue("Cancellation waited for body: ${elapsedMs}ms", elapsedMs < 1_500)
        } finally {
            releaseBody.countDown()
            server.stop(0)
        }
    }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> try { handler(exchange) } finally { exchange.close() } }
            start()
        }

    private fun isStream(exchange: HttpExchange): Boolean =
        Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText())
            .jsonObject["stream"]?.jsonPrimitive?.boolean == true

    private val completeBody = """{"id":"s","model":"fixture","choices":[{"index":0,"message":{"role":"assistant","content":"整理小说大纲\n优化安卓渲染\n复盘本周计划"},"finish_reason":"stop"}]}"""

    private fun generator(server: HttpServer, client: OkHttpClient): Pair<ChatStartSuggestionGenerator, Settings> {
        val model = Model(modelId = "fixture")
        val provider = ProviderSetting.OpenAI(
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", apiKey = "local-test-key", models = listOf(model),
        )
        val context = RuntimeEnvironment.getApplication()
        val catalog = ProviderCatalog(OpenAIProvider(client, context), GoogleProvider(client, context), ClaudeProvider(client, context))
        val repository = object : MemoryRepository(dummy(MemoryDAO::class.java), dummy(MemoryCandidateDAO::class.java), dummy(MemoryEventDAO::class.java)) {
            override suspend fun getActiveRecords(scopes: Set<MemoryScope>, now: Long) = listOf(
                MemoryRecord(1, "Interested in Android and novels", MemoryScope.CORE, MemoryKind.USER, "global"),
            )
        }
        return ChatStartSuggestionGenerator(repository, MemoryProfileStore(folder.newFile()), catalog) to
            Settings(chatModelId = model.id, suggestionModelId = model.id, providers = listOf(provider))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> dummy(type: Class<T>): T = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, _ ->
        error("Unexpected memory write/read ${method.name}")
    } as T
}
