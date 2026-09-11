package app.amber.ai.provider.providers.openai

import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.ResponsesResumeRequest
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.providers.StreamProtocol
import app.amber.ai.provider.providers.StreamTerminationGuard
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

/** In-memory ResponseResumeStore for transport-level tests. */
private class InMemoryResumeStore : ResponseResumeStore {
    private var row: Pair<String, ResponseCursor>? = null

    override suspend fun save(runId: String, responseId: String, sequence: Long, providerId: String) {
        row = runId to ResponseCursor(responseId, sequence, providerId)
    }

    override suspend fun load(runId: String): ResponseCursor? = row?.takeIf { it.first == runId }?.second

    override suspend fun clear(runId: String) {
        if (row?.first == runId) row = null
    }
}

private class FakeEventSource(private val request: Request) : EventSource {
    var cancelled = false
    override fun request(): Request = request
    override fun cancel() {
        cancelled = true
    }
}

/** Scripted SSE transport: records every streamed request+listener for the test to drive. */
private class FakeSseTransport {
    val streams = mutableListOf<Pair<Request, EventSourceListener>>()
    val sources = mutableListOf<FakeEventSource>()

    operator fun invoke(request: Request, listener: EventSourceListener): EventSource {
        val source = FakeEventSource(request)
        streams += request to listener
        sources += source
        return source
    }
}

/**
 * P6-01 — reconnect + dedup + cursor semantics of the stored-response stream,
 * driven through a fake SSE transport (no network).
 */
class ResponseAPIResumeStreamTest {

    @Test
    fun explicitResumeUsesGetAndReplacesPartialForStreamingAndNonStreaming() = runBlocking {
        for (streaming in listOf(true, false)) {
            val transport = FakeSseTransport()
            val store = InMemoryResumeStore()
            val setting = providerSetting()
            val cursor = ResponseCursor("resp_old", 5, setting.id.toString())
            store.save("run_1", cursor.responseId, cursor.sequence, cursor.providerId)
            val partial = UIMessage.assistant("ab") // the cursor is ahead of the durable text
            val accumulator = MessageStreamAccumulator(listOf(partial))
            val api = ResponseAPI(client = okhttp3.OkHttpClient(), transport = transport::invoke)
            val requestParams = params(ResponsesResumeRequest("run_1", store, resumeFrom = cursor))
            val job = launch {
                withTimeout(10_000) {
                    if (streaming) {
                        api.streamText(setting, listOf(partial), requestParams).collect(accumulator::append)
                    } else {
                        accumulator.append(api.generateText(setting, listOf(partial), requestParams))
                    }
                }
            }
            withTimeout(10_000) { while (transport.streams.isEmpty()) yield() }
            val (request, listener) = transport.streams.single()
            assertEquals("GET", request.method)
            assertEquals("https://api.openai.com/v1/responses/resp_old", request.url.toString())
            listener.onEvent(transport.sources.single(), null, "response.created", createdEvent(0, "resp_old"))
            for ((index, char) in "abcdef".withIndex()) {
                listener.onEvent(transport.sources.single(), null, "response.output_text.delta",
                    deltaEvent(index + 1L, "resp_old", "msg_old", char.toString()))
            }
            listener.onEvent(transport.sources.single(), null, "response.completed", completedEvent(7, "resp_old",
                """[{"type":"message","id":"msg_old","role":"assistant","content":[{"type":"output_text","text":"abcdef"}]}]"""))
            job.join()

            val recovered = accumulator.snapshot().single()
            assertEquals(partial.id, recovered.id)
            assertEquals("abcdef", recovered.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
            assertEquals(ResponseCursor("resp_old", 7, setting.id.toString()), store.load("run_1"))
        }
    }

    @Test
    fun storedCursorWithoutExplicitContinuationStartsANewModelRound() = runBlocking {
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val setting = providerSetting()
        store.save("run_1", "resp_previous_round", 5, setting.id.toString())
        val api = ResponseAPI(client = okhttp3.OkHttpClient(), transport = transport::invoke)
        val job = launch {
            withTimeout(10_000) {
                api.streamText(setting, listOf(UIMessage.user("next round")),
                    params(ResponsesResumeRequest("run_1", store))).toList()
            }
        }
        withTimeout(10_000) { while (transport.streams.isEmpty()) yield() }
        val (request, listener) = transport.streams.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.openai.com/v1/responses", request.url.toString())
        listener.onEvent(transport.sources.single(), null, "response.created", createdEvent(0, "resp_new"))
        listener.onEvent(transport.sources.single(), null, "response.completed", completedEvent(1, "resp_new", "[]"))
        job.join()
        assertEquals("resp_new", store.load("run_1")?.responseId)
    }

    private fun providerSetting(
        baseUrl: String = "https://api.openai.com/v1",
        authMode: OpenAIAuthMode = OpenAIAuthMode.API_KEY,
        useResponseApi: Boolean = true,
        enableResponsesResume: Boolean = true,
    ) = ProviderSetting.OpenAI(
        id = Uuid.random(),
        baseUrl = baseUrl,
        useResponseApi = useResponseApi,
        enableResponsesResume = enableResponsesResume,
        authMode = authMode,
        name = "OpenAI",
    )

    private fun params(resume: ResponsesResumeRequest?): TextGenerationParams = TextGenerationParams(
        model = Model(modelId = "gpt-5.4", displayName = "gpt-5.4"),
        responsesResume = resume,
    )

    private fun createdEvent(sequence: Long, responseId: String): String = buildJsonObject {
        put("type", "response.created")
        put("sequence_number", sequence)
        put("response", buildJsonObject {
            put("id", responseId)
            put("object", "response")
            put("status", "in_progress")
        })
    }.toString()

    private fun deltaEvent(sequence: Long, responseId: String, itemId: String, delta: String): String =
        buildJsonObject {
            put("type", "response.output_text.delta")
            put("sequence_number", sequence)
            put("item_id", itemId)
            put("delta", delta)
        }.toString()

    private fun completedEvent(sequence: Long, responseId: String, outputJson: String): String =
        buildJsonObject {
            put("type", "response.completed")
            put("sequence_number", sequence)
            put("response", buildJsonObject {
                put("id", responseId)
                put("status", "completed")
                put("output", json.parseToJsonElement(outputJson))
            })
        }.toString()

    private fun deltaText(chunk: MessageChunk): String =
        chunk.choices.firstOrNull()
            ?.delta?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text }
            ?: chunk.choices.firstOrNull()
                ?.message?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text }
            ?: ""

    @Test
    fun disconnectReconnectsToStoredResponseAndDedupsEvents() = runBlocking {
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val api = ResponseAPI(
            client = okhttp3.OkHttpClient(),
            transport = transport::invoke,
        )
        val setting = providerSetting()
        val resume = ResponsesResumeRequest(runId = "run_1", store = store)

        val chunks = mutableListOf<MessageChunk>()
        val job = launch {
            withTimeout(10_000) {
                api.streamText(setting, emptyList(), params(resume)).toList(chunks)
            }
        }
        // Wait for the POST stream to be registered.
        while (transport.streams.isEmpty()) yield()

        // First stream: created + deltas 1..5, then the connection drops.
        transport.streams[0].second.onEvent(transport.sources[0], null, "response.created", createdEvent(0, "resp_1"))
        for (i in 1..5) {
            transport.streams[0].second.onEvent(
                transport.sources[0], null, "response.output_text.delta", deltaEvent(i.toLong(), "resp_1", "msg_1", "abcde"[i - 1].toString())
            )
        }
        yield()
        // write-ahead cursor at the last emitted event
        assertEquals(5L, store.load("run_1")!!.sequence)
        transport.streams[0].second.onFailure(transport.sources[0], IOException("connection reset"), null)

        // The drain reconnects via GET /responses/resp_1 (replay from cursor).
        while (transport.streams.size < 2) yield()
        val (replayRequest, replayListener) = transport.streams[1]
        assertEquals("GET", replayRequest.method)
        assertTrue(replayRequest.url.toString().endsWith("/responses/resp_1"))

        // Server replays the FULL event history (0..10) + terminal.
        replayListener.onEvent(transport.sources[1], null, "response.created", createdEvent(0, "resp_1"))
        for (i in 1..10) {
            replayListener.onEvent(
                transport.sources[1], null, "response.output_text.delta", deltaEvent(i.toLong(), "resp_1", "msg_1", "fghijklmno"[i - 1].toString())
            )
        }
        replayListener.onEvent(
            transport.sources[1], null, "response.completed",
            buildJsonObject {
                put("type", "response.completed")
                put("sequence_number", 11)
                put("response", buildJsonObject {
                    put("id", "resp_1")
                    put("status", "completed")
                    put("output", json.parseToJsonElement(
                        """[{"type":"message","id":"msg_1","status":"completed","role":"assistant","content":[{"type":"output_text","text":"abcdefghijklmno"}]}]"""
                    ))
                })
            }.toString()
        )

        job.join()

        // 1..5 from the first stream + 6..10 from the replay (dedup skipped
        // the replayed 1..5) + the terminal chunk = 11 chunks, no duplicates.
        assertEquals(11, chunks.size)
        val deltaTexts = chunks.take(10).joinToString("") { deltaText(it) }
        // replay deltas are f..o; dedup drops f..j (seq 1..5 replayed) and
        // keeps k..o (seq 6..10) — nothing delivered twice.
        assertEquals("abcdeklmno", deltaTexts)
        assertEquals(10, deltaTexts.toSet().size)
        // The chat owner clears the cursor after its durable final
        // conversation checkpoint; this transport-level test has no owner,
        // so the terminal cursor remains available for recovery.
        assertEquals(11L, store.load("run_1")?.sequence)
    }

    @Test
    fun resumeDisabledKeepsTodayBehaviorNoCursorAndStoreFalse() = runBlocking {
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val api = ResponseAPI(
            client = okhttp3.OkHttpClient(),
            transport = transport::invoke,
        )
        val setting = providerSetting(enableResponsesResume = true)

        // store=false unless a resume request is carried (plan: store=false
        // keeps the exact pre-P6-01 behavior).
        val bodyWithoutResume = api.buildRequestBody(
            providerSetting = setting,
            messages = emptyList(),
            params = params(resume = null),
            stream = true,
        )
        assertEquals(false, bodyWithoutResume["store"]?.jsonPrimitive?.contentOrNull?.toBoolean())

        val bodyWithResume = api.buildRequestBody(
            providerSetting = setting,
            messages = emptyList(),
            params = params(resume = ResponsesResumeRequest(runId = "run_1", store = store)),
            stream = true,
            resume = ResponsesResumeRequest(runId = "run_1", store = store),
        )
        assertEquals(true, bodyWithResume["store"]?.jsonPrimitive?.contentOrNull?.toBoolean())

        // A stream without a resume request never touches the cursor store.
        val chunks = mutableListOf<MessageChunk>()
        val job = launch {
            withTimeout(10_000) {
                api.streamText(setting, emptyList(), params(resume = null)).toList(chunks)
            }
        }
        while (transport.streams.isEmpty()) yield()
        transport.streams[0].second.onEvent(transport.sources[0], null, "response.created", createdEvent(0, "resp_1"))
        transport.streams[0].second.onEvent(
            transport.sources[0], null, "response.output_text.delta", deltaEvent(1, "resp_1", "msg_1", "x")
        )
        transport.streams[0].second.onEvent(
            transport.sources[0], null, "response.completed",
            buildJsonObject {
                put("type", "response.completed")
                put("sequence_number", 2)
                put("response", buildJsonObject {
                    put("id", "resp_1")
                    put("status", "completed")
                    put("output", json.parseToJsonElement("""[]"""))
                })
            }.toString()
        )
        job.join()
        assertNull(store.load("run_1"))
        // delta + terminal chunk.
        assertEquals(2, chunks.size)
        // A network failure without resume fails the flow (no reconnect).
        assertEquals(1, transport.streams.size)
    }

    @Test
    fun userToggleOffNeverSendsStoreTrueEvenWithResumeRequest() = runBlocking {
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val api = ResponseAPI(
            client = okhttp3.OkHttpClient(),
            transport = transport::invoke,
        )
        // Capability side fully on (strict official-endpoint match) but the
        // user switch is off: the provider must ignore the resume request —
        // store=false, no cursor writes, no reconnect (pre-P6-01 behavior).
        val setting = providerSetting(enableResponsesResume = false)
        val resumeRequest = ResponsesResumeRequest(runId = "run_1", store = store)

        val chunks = mutableListOf<MessageChunk>()
        val job = launch {
            runCatching {
                withTimeout(10_000) {
                    api.streamText(setting, emptyList(), params(resume = resumeRequest)).toList(chunks)
                }
            }
        }
        while (transport.streams.isEmpty()) yield()
        // The wire body must carry store=false — the resume request was dropped.
        val wireBody = json.parseToJsonElement(
            Buffer().also { transport.streams[0].first.body!!.writeTo(it) }.readUtf8()
        ).jsonObject
        assertEquals(false, wireBody["store"]?.jsonPrimitive?.contentOrNull?.toBoolean())
        transport.streams[0].second.onEvent(transport.sources[0], null, "response.created", createdEvent(0, "resp_1"))
        transport.streams[0].second.onEvent(
            transport.sources[0], null, "response.output_text.delta", deltaEvent(1, "resp_1", "msg_1", "x")
        )
        yield()
        // The cursor store is never touched.
        assertNull(store.load("run_1"))
        // A mid-stream failure does not reconnect: resume is not honored.
        transport.streams[0].second.onFailure(transport.sources[0], IOException("connection reset"), null)
        job.join()
        assertEquals(1, transport.streams.size)
        // The delta was still delivered; the failure ended the stream.
        assertEquals("x", deltaText(chunks.single()))
    }

    @Test
    fun replayAfterCursorSkipsAlreadyDeliveredEvents() = runBlocking {
        // Directly exercise streamStored with a persisted cursor: only events
        // above the cursor are delivered (server replays from sequence 0).
        val transport = FakeSseTransport()
        val store = InMemoryResumeStore()
        val api = ResponseAPI(
            client = okhttp3.OkHttpClient(),
            transport = transport::invoke,
        )
        val setting = providerSetting()

        val chunks = mutableListOf<MessageChunk>()
        val job = launch {
            withTimeout(10_000) {
                api.streamStored(
                    providerSetting = setting,
                    responseId = "resp_1",
                    cursor = ResponseCursor("resp_1", sequence = 3, providerId = setting.id.toString()),
                    store = store,
                    runId = "run_1",
                ).toList(chunks)
            }
        }
        while (transport.streams.isEmpty()) yield()
        val listener = transport.streams[0].second
        listener.onEvent(transport.sources[0], null, "response.created", createdEvent(0, "resp_1"))
        for (i in 0..5) {
            listener.onEvent(
                transport.sources[0], null, "response.output_text.delta", deltaEvent(i.toLong(), "resp_1", "msg_1", "abcdef"[i].toString())
            )
        }
        listener.onEvent(
            transport.sources[0], null, "response.completed",
            buildJsonObject {
                put("type", "response.completed")
                put("sequence_number", 6)
                put("response", buildJsonObject {
                    put("id", "resp_1")
                    put("status", "completed")
                    put("output", json.parseToJsonElement("""[]"""))
                })
            }.toString()
        )
        job.join()

        // seq 0..3 skipped (already delivered), 4..5 emitted + terminal.
        assertEquals(3, chunks.size)
        assertEquals("ef", chunks.take(2).joinToString("") { deltaText(it) })
    }
}
