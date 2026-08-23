package app.amber.feature.runtime

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.ResponsesResumeRequest
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.openai.ResponseAPI
import app.amber.ai.provider.providers.openai.StoredResponseApi
import app.amber.ai.provider.providers.openai.StoredResponseCancelResult
import app.amber.ai.provider.providers.openai.StoredResponseState
import app.amber.ai.provider.providers.openai.StoredResponseStatus
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.DEFAULT_ASSISTANT_ID
import app.amber.core.model.MessageNode
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * P6-01 cold-start recovery — runs with a stored server-side OpenAI Response
 * are resolved against the server: completed responses fetch only the missing
 * events and finish COMPLETED with the SAME runId; in-progress responses stay
 * RESUMABLE; server unreachable keeps pause states and falls back for RUNNING.
 */
class RunRecoveryServiceResumeTest : DurableRuntimeTestBase() {

    private class FakeStoredResponseApi : StoredResponseApi {
        var status: StoredResponseStatus = StoredResponseStatus(StoredResponseState.IN_PROGRESS, "resp_1")
        var statusError: Throwable? = null
        var cancelResult: StoredResponseCancelResult = StoredResponseCancelResult.CancelledDecided
        /** The cursor the recovery asked for (only-missing-events contract). */
        var requestedCursor: ResponseCursor? = null
        /** (sequence, delta text) pairs replayed by the fake (already deduped). */
        var missingEvents: List<Pair<Long, String>> = emptyList()
        var finalText: String = ""

        override suspend fun fetchStatus(
            providerSetting: ProviderSetting.OpenAI,
            responseId: String,
        ): StoredResponseStatus {
            statusError?.let { throw it }
            return status
        }

        override suspend fun streamStored(
            providerSetting: ProviderSetting.OpenAI,
            responseId: String,
            cursor: ResponseCursor?,
            store: ResponseResumeStore,
            runId: String,
        ): Flow<MessageChunk> {
            requestedCursor = cursor
            return flow {
                missingEvents.forEach { (seq, text) ->
                    emit(
                        MessageChunk(
                            id = "msg_1",
                            model = "",
                            choices = listOf(
                                UIMessageChoice(
                                    index = 0,
                                    delta = UIMessage.assistant(text),
                                    message = null,
                                    finishReason = null,
                                )
                            ),
                        )
                    )
                }
                if (finalText.isNotEmpty()) {
                    emit(
                        MessageChunk(
                            id = "resp_1",
                            model = "",
                            choices = listOf(
                                UIMessageChoice(
                                    index = 0,
                                    delta = null,
                                    message = UIMessage.assistant(finalText),
                                    finishReason = "completed",
                                )
                            ),
                        )
                    )
                }
            }
        }

        override suspend fun cancel(
            providerSetting: ProviderSetting.OpenAI,
            responseId: String,
        ): StoredResponseCancelResult = cancelResult
    }

    private class FakeStoredResponseGateway : StoredResponseGateway {
        var session: StoredResponseGateway.StoredResponseSession? = null
        override suspend fun resolve(runId: String): StoredResponseGateway.StoredResponseSession? = session
    }

    private fun openAiSetting() = ProviderSetting.OpenAI(
        id = Uuid.random(),
        baseUrl = "https://api.openai.com/v1",
        useResponseApi = true,
        enableResponsesResume = true,
        name = "OpenAI",
    )

    private fun capabilityFlags(enabled: Boolean): CapabilityFlags {
        val flags = CapabilityFlags(
            PreferenceDataStoreFactory.create {
                File(context.cacheDir, "capability-flags-${Uuid.random()}.preferences_pb")
            }
        )
        runBlocking { flags.setEnabled(Capability.OpenAIResponsesResume, enabled) }
        return flags
    }

    private fun recoveryService(
        gateway: StoredResponseGateway? = null,
        flags: CapabilityFlags? = null,
        resumeStore: ResponseResumeStore? = null,
    ) = RunRecoveryService(
        ledger = ledger,
        runTerminalStore = runTerminalStore,
        conversationRepo = conversationRepository(),
        json = kotlinx.serialization.json.Json,
        storedResponseGateway = gateway,
        capabilityFlags = flags,
        resumeStore = resumeStore,
    )

    private fun conversationWithPartial(
        conversationId: Uuid,
        partialText: String,
        partialId: Uuid = Uuid.random(),
    ): Conversation = Conversation(
        id = conversationId,
        assistantId = DEFAULT_ASSISTANT_ID,
        messageNodes = listOf(
            MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("question")))),
            MessageNode.of(
                UIMessage(
                    id = partialId,
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(partialText)),
                )
            ),
        ),
    )

    @Test
    fun completedStoredResponseFetchesOnlyMissingEventsAndFinishesCompletedWithSameRunId() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_1", "resp_1", 3, "provider_1")
        val repo = conversationRepository()
        val partialId = Uuid.random()
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "abc", partialId = partialId))

        val api = FakeStoredResponseApi().apply {
            status = StoredResponseStatus(StoredResponseState.COMPLETED, "resp_1")
            missingEvents = listOf(4L to "def")
            finalText = "abcdef"
        }
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = ResponseCursor("resp_1", 3, "provider_1"),
                providerSetting = openAiSetting(),
                api = api,
            )
        }

        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = resumeStore).recover()

        // Only the missing events (sequence > cursor 3) were requested.
        assertEquals(3L, api.requestedCursor!!.sequence)
        assertEquals("resp_1", api.requestedCursor!!.responseId)

        // The conversation now carries the final message (partial + missing).
        val updated = repo.getConversationById(conversationId)!!
        val lastAssistant = updated.messageNodes.last { it.role == MessageRole.ASSISTANT }
        assertEquals("abcdef", (lastAssistant.currentMessage.parts.single() as UIMessagePart.Text).text)

        // Same runId, terminal COMPLETED, cursor cleared.
        val run = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.COMPLETED, run.state)
        assertEquals("run_1", run.runId)
        assertNotNull(run.finishedAtMs)
        assertNull(resumeStore.load("run_1"))
    }

    @Test
    fun inProgressStoredResponseStaysResumableWithCursor() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_1", "resp_1", 7, "provider_1")
        val repo = conversationRepository()
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "abc"))

        val api = FakeStoredResponseApi() // IN_PROGRESS by default
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = ResponseCursor("resp_1", 7, "provider_1"),
                providerSetting = openAiSetting(),
                api = api,
            )
        }

        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = resumeStore).recover()

        // Paused RESUMABLE — never terminal — and the cursor stays so the
        // in-process resume path can re-attach to the same response.
        val run = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.RESUMABLE, run.state)
        assertNull(run.finishedAtMs)
        assertEquals(7L, resumeStore.load("run_1")!!.sequence)
        assertNull(api.requestedCursor) // no event fetch for an in-progress response
    }

    @Test
    fun serverUnreachableKeepsPauseStatesAndFallsBackForRunningRuns() = runBlocking {
        val conversationId = Uuid.random()
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        val gateway = FakeStoredResponseGateway()
        val api = FakeStoredResponseApi().apply { statusError = IOException("offline") }
        val repo = conversationRepository()

        // WAITING_EXTERNAL (cancel-unconfirmed) run: stays resumable, cursor kept.
        runTerminalStore.begin("run_waiting", conversationId.toString(), null)
        runTerminalStore.pause("run_waiting", RunTerminalState.WAITING_EXTERNAL, PauseReason.USER_STOP)
        resumeStore.save("run_waiting", "resp_w", 2, "provider_1")
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "ab"))
        gateway.session = StoredResponseGateway.StoredResponseSession(
            cursor = ResponseCursor("resp_w", 2, "provider_1"),
            providerSetting = openAiSetting(),
            api = api,
        )

        // RUNNING run: falls back to Phase 1 INTERRUPTED, cursor cleared.
        runTerminalStore.begin("run_running", conversationId.toString(), null)
        resumeStore.save("run_running", "resp_r", 5, "provider_1")
        gateway.session = StoredResponseGateway.StoredResponseSession(
            cursor = ResponseCursor("resp_r", 5, "provider_1"),
            providerSetting = openAiSetting(),
            api = api,
        )

        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = resumeStore).recover()

        val waiting = runTerminalStore.get("run_waiting")!!
        assertEquals(RunTerminalState.WAITING_EXTERNAL, waiting.state)
        assertNull(waiting.finishedAtMs)
        assertNotNull(resumeStore.load("run_waiting"))

        val running = runTerminalStore.get("run_running")!!
        assertEquals(RunTerminalState.INTERRUPTED, running.state)
        assertNotNull(running.finishedAtMs)
        assertNull(resumeStore.load("run_running"))
    }

    @Test
    fun unresolvableStoredResponseFallsBackToPhase1AndClearsCursor() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_1", "resp_1", 3, "provider_gone")

        // Cursor exists but the provider is gone — api == null.
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = ResponseCursor("resp_1", 3, "provider_gone"),
                providerSetting = null,
                api = null,
            )
        }

        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = resumeStore).recover()

        // Phase 1 semantics apply; the orphaned cursor is cleared.
        assertEquals(RunTerminalState.INTERRUPTED, runTerminalStore.get("run_1")!!.state)
        assertNull(resumeStore.load("run_1"))
    }

    @Test
    fun capabilityFlagOffKeepsPhase1BehaviorForCursorBearingRun() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_1", "resp_1", 3, "provider_1")
        val api = FakeStoredResponseApi()
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = ResponseCursor("resp_1", 3, "provider_1"),
                providerSetting = openAiSetting(),
                api = api,
            )
        }

        // Flag off: the server is never queried; Phase 1 decides and the
        // cursor is cleared (rollback: no zombie state).
        recoveryService(gateway = gateway, flags = capabilityFlags(enabled = false), resumeStore = resumeStore).recover()

        assertEquals(RunTerminalState.INTERRUPTED, runTerminalStore.get("run_1")!!.state)
        assertNull(resumeStore.load("run_1"))
        assertTrue("server must not be queried when the flag is off", api.requestedCursor == null)
    }

    @Test
    fun userToggleOffKeepsPhase1BehaviorForCursorBearingRun() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_1", "resp_1", 3, "provider_1")
        val api = FakeStoredResponseApi()
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = ResponseCursor("resp_1", 3, "provider_1"),
                providerSetting = openAiSetting().copy(enableResponsesResume = false),
                api = api,
            )
        }

        // Capability flag ON but the user switch is off: the server is never
        // queried; Phase 1 decides and the orphaned cursor is cleared.
        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = resumeStore).recover()

        assertEquals(RunTerminalState.INTERRUPTED, runTerminalStore.get("run_1")!!.state)
        assertNull(resumeStore.load("run_1"))
        assertTrue("server must not be queried when the user switch is off", api.requestedCursor == null)
    }

    @Test
    fun realGenerationWritesCursorThenProcessDeathRecoveryRestores() = runBlocking {
        val conversationId = Uuid.random()
        val runId = "run_e2e"
        runTerminalStore.begin(runId, conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        val repo = conversationRepository()
        val partialId = Uuid.random()
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "ab", partialId = partialId))
        val setting = openAiSetting()

        // 1) Real generation through the fake transport — the cursor is
        //    written by the provider's write-ahead path, NOT injected by the
        //    test (P6-01d: fresh run, no pre-seeded cursor).
        val transport = FakeSseTransport()
        val api = ResponseAPI(
            client = okhttp3.OkHttpClient(),
            transport = transport::invoke,
        )
        val job = launch {
            runCatching {
                withTimeout(10_000) {
                    api.streamText(
                        setting,
                        emptyList(),
                        TextGenerationParams(
                            model = Model(modelId = "gpt-5.4", displayName = "gpt-5.4"),
                            responsesResume = ResponsesResumeRequest(runId = runId, store = resumeStore),
                        ),
                    ).collect { }
                }
            }
        }
        while (transport.streams.isEmpty()) yield()
        transport.streams[0].second.onEvent(transport.sources[0], null, "response.created", createdEvent(0, "resp_1"))
        for (i in 1..4) {
            transport.streams[0].second.onEvent(
                transport.sources[0], null, "response.output_text.delta",
                deltaEvent(i.toLong(), "resp_1", "msg_1", "abcd"[i - 1].toString()),
            )
        }
        yield()
        // write-ahead cursor is durable before anything else happens (the
        // Room-backed store hops threads per save, so poll for the last seq).
        withTimeout(10_000) {
            while (resumeStore.load(runId)?.sequence != 4L) yield()
        }
        assertEquals(4L, resumeStore.load(runId)!!.sequence)
        assertEquals("resp_1", resumeStore.load(runId)!!.responseId)
        assertEquals(setting.id.toString(), resumeStore.load(runId)!!.providerId)
        // Process death: the stream dies with the process, cursor survives.
        job.cancel()

        // 2) Cold start: a fresh recovery service resolves the run from the
        //    persisted cursor; the server says COMPLETED and replays only the
        //    missing events.
        val recoveryApi = FakeStoredResponseApi().apply {
            status = StoredResponseStatus(StoredResponseState.COMPLETED, "resp_1")
            missingEvents = listOf(5L to "cdef")
            finalText = "abcdef"
        }
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = resumeStore.load(runId)!!,
                providerSetting = setting,
                api = recoveryApi,
            )
        }
        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = resumeStore).recover()

        // The conversation carries the final message (partial + missing).
        val updated = repo.getConversationById(conversationId)!!
        val lastAssistant = updated.messageNodes.last { it.role == MessageRole.ASSISTANT }
        assertEquals("abcdef", (lastAssistant.currentMessage.parts.single() as UIMessagePart.Text).text)
        // Same runId, terminal COMPLETED, cursor cleared.
        val run = runTerminalStore.get(runId)!!
        assertEquals(RunTerminalState.COMPLETED, run.state)
        assertEquals(runId, run.runId)
        assertNull(resumeStore.load(runId))
    }
}

/** Fake SSE transport for the P6-01d end-to-end test (no network). */
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

private class FakeEventSource(private val request: Request) : EventSource {
    var cancelled = false
    override fun request(): Request = request
    override fun cancel() {
        cancelled = true
    }
}

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
