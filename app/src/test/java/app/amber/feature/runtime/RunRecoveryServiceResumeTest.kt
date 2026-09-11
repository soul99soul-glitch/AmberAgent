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
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.MessageNode
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
        var statusEntered: CompletableDeferred<Unit>? = null
        var releaseStatus: CompletableDeferred<Unit>? = null
        var statusReturned: CompletableDeferred<Unit>? = null
        var streamCompleted: CompletableDeferred<Unit>? = null

        override suspend fun fetchStatus(
            providerSetting: ProviderSetting.OpenAI,
            responseId: String,
        ): StoredResponseStatus {
            statusEntered?.complete(Unit)
            releaseStatus?.await()
            statusError?.let { throw it }
            statusReturned?.complete(Unit)
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
                streamCompleted?.complete(Unit)
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
        eventStore: app.amber.core.agent.runtime.AgentEventStore? = null,
    ) = RunRecoveryService(
        ledger = ledger,
        runTerminalStore = runTerminalStore,
        conversationRepo = conversationRepository(),
        json = kotlinx.serialization.json.Json,
        storedResponseGateway = gateway,
        capabilityFlags = flags,
        resumeStore = resumeStore,
        agentEventStore = eventStore,
    )

    /** Seed a protocol run row and walk it legally to [status]. */
    private suspend fun seedAgentRun(
        store: app.amber.core.agent.runtime.InMemoryAgentEventStore,
        runId: String,
        status: app.amber.core.agent.runtime.RunStatus,
    ) {
        store.appendRun(
            app.amber.core.agent.runtime.AgentRunRecord(
                runId = runId,
                parentRunId = null,
                agentDescriptorId = "chat_turn",
                agentVersion = "1.0.0",
                conversationId = null,
                messageNodeId = null,
                producesMessageId = null,
                assistantId = null,
                status = app.amber.core.agent.runtime.RunStatus.CREATED,
                inputDigest = "digest",
                inputSnapshotRef = null,
                inputSchemaVersion = 1,
                startedAt = System.currentTimeMillis(),
                finishedAt = null,
                interruptedReason = null,
            ),
        )
        store.transitionRun(
            app.amber.core.agent.runtime.AgentRunId(runId),
            emptySet(),
            app.amber.core.agent.runtime.RunStatus.RUNNING,
        )
        if (status != app.amber.core.agent.runtime.RunStatus.RUNNING) {
            store.transitionRun(
                app.amber.core.agent.runtime.AgentRunId(runId),
                emptySet(),
                status,
            )
        }
    }

    private fun conversationWithPartial(
        conversationId: Uuid,
        partialText: String,
        partialId: Uuid = Uuid.random(),
    ): Conversation = Conversation(
        id = conversationId,
        assistantId = AMBER_AGENT_ID,
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
    fun completedCursorAtTerminalUsesCompleteGetBeforeClearingCursor() = runBlocking {
        val conversationId = Uuid.random()
        val runId = "run_terminal_cursor"
        runTerminalStore.begin(runId, conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        val setting = openAiSetting()
        val repo = conversationRepository()
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "partial"))

        // Drive the production write-ahead path up to the terminal event, and
        // stop immediately after its cursor save. The collector therefore
        // never receives the terminal message or persists a conversation
        // checkpoint, matching a process death in that window.
        val transport = FakeSseTransport()
        val writeAheadStore = ThrowAfterSequenceSaveStore(
            delegate = resumeStore,
            terminalSequence = 2L,
        )
        val streamApi = ResponseAPI(
            client = OkHttpClient(),
            transport = transport::invoke,
            bearerResolver = { _, _ -> "test-token" },
        )
        val generationJob = launch {
            runCatching {
                streamApi.streamText(
                    setting,
                    emptyList(),
                    TextGenerationParams(
                        model = Model(modelId = "gpt-5.4", displayName = "gpt-5.4"),
                        responsesResume = ResponsesResumeRequest(runId, writeAheadStore),
                    ),
                ).collect { }
            }
        }
        while (transport.streams.isEmpty()) yield()
        transport.streams[0].second.onEvent(
            transport.sources[0],
            null,
            "response.created",
            createdEvent(0, "resp_terminal"),
        )
        transport.streams[0].second.onEvent(
            transport.sources[0],
            null,
            "response.completed",
            buildJsonObject {
                put("type", "response.completed")
                put("sequence_number", 2)
                put("response", buildJsonObject {
                    put("id", "resp_terminal")
                    put("status", "completed")
                    put("output", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("type", "message")
                            put("id", "msg_terminal")
                            put("role", "assistant")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "output_text")
                                    put("text", "complete")
                                })
                            }
                        })
                    })
                })
            }.toString(),
        )
        withTimeout(10_000) {
            while (resumeStore.load(runId)?.sequence != 2L) yield()
        }
        generationJob.join()
        assertEquals(
            ResponseCursor("resp_terminal", 2, setting.id.toString()),
            resumeStore.load(runId),
        )

        var statusRequests = 0
        val responseBody = """
            {
              "id": "resp_terminal",
              "model": "gpt-5.4",
              "status": "completed",
              "output": [{
                "type": "message",
                "id": "msg_terminal",
                "role": "assistant",
                "content": [{"type": "output_text", "text": "complete"}]
              }]
            }
        """.trimIndent()
        val api = ResponseAPI(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    statusRequests += 1
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(responseBody.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            bearerResolver = { _, _ -> "test-token" },
        )
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = resumeStore.load(runId)!!,
                providerSetting = setting,
                api = api,
            )
        }

        val completionAwareStore = object : ResponseResumeStore by resumeStore {
            override suspend fun clear(runId: String) {
                // A crash after cursor deletion must still find a durable terminal.
                assertEquals(RunTerminalState.COMPLETED, runTerminalStore.get(runId)!!.state)
                resumeStore.clear(runId)
            }
        }
        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = completionAwareStore).recover()

        assertEquals(1, statusRequests)
        val updated = repo.getConversationById(conversationId)!!
        val lastAssistant = updated.messageNodes.last { it.role == MessageRole.ASSISTANT }
        assertEquals("complete", (lastAssistant.currentMessage.parts.single() as UIMessagePart.Text).text)
        assertEquals(RunTerminalState.COMPLETED, runTerminalStore.get(runId)!!.state)
        assertNull(resumeStore.load(runId))
    }

    @Test
    fun completedStatusWithoutOutputDoesNotPromoteSeedPartialToFinal() = runBlocking {
        val conversationId = Uuid.random()
        val runId = "run_missing_output"
        runTerminalStore.begin(runId, conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        val setting = openAiSetting()
        resumeStore.save(runId, "resp_missing_output", 7, setting.id.toString())
        val repo = conversationRepository()
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "partial"))
        val api = FakeStoredResponseApi().apply {
            status = StoredResponseStatus(StoredResponseState.COMPLETED, "resp_missing_output")
        }
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = resumeStore.load(runId)!!,
                providerSetting = setting,
                api = api,
            )
        }

        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = resumeStore).recover()

        // The terminal cursor is retained for a later retry; the partial
        // conversation is never treated as the server's final output.
        assertEquals(RunTerminalState.RUNNING, runTerminalStore.get(runId)!!.state)
        assertNull(runTerminalStore.get(runId)!!.finishedAtMs)
        assertEquals(ResponseCursor("resp_missing_output", 7, setting.id.toString()), resumeStore.load(runId))
        assertEquals(ResponseCursor("resp_missing_output", 7, setting.id.toString()), api.requestedCursor)
        val unchanged = repo.getConversationById(conversationId)!!
        val lastAssistant = unchanged.messageNodes.last { it.role == MessageRole.ASSISTANT }
        assertEquals("partial", (lastAssistant.currentMessage.parts.single() as UIMessagePart.Text).text)
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
    fun completedStoredResponseAlsoSettlesTheProtocolRunRow() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_1", "resp_1", 3, "provider_1")
        val repo = conversationRepository()
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "abc"))
        val eventStore = app.amber.core.agent.runtime.InMemoryAgentEventStore()
        seedAgentRun(eventStore, "run_1", app.amber.core.agent.runtime.RunStatus.RUNNING)

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

        recoveryService(
            gateway = gateway,
            flags = capabilityFlags(true),
            resumeStore = resumeStore,
            eventStore = eventStore,
        ).recover()

        // Step 3-4 convergence: without the mirrored CAS the protocol row
        // would later be stomped INTERRUPTED by replayUnfinished while
        // run_terminal says COMPLETED.
        assertEquals(RunTerminalState.COMPLETED, runTerminalStore.get("run_1")!!.state)
        assertEquals(
            app.amber.core.agent.runtime.RunStatus.COMPLETED,
            eventStore.runs["run_1"]!!.status,
        )
    }

    @Test
    fun inProgressStoredResponseParksTheProtocolRunRowResumable() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_1", "resp_1", 7, "provider_1")
        val repo = conversationRepository()
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "abc"))
        val eventStore = app.amber.core.agent.runtime.InMemoryAgentEventStore()
        seedAgentRun(eventStore, "run_1", app.amber.core.agent.runtime.RunStatus.RUNNING)

        val api = FakeStoredResponseApi() // IN_PROGRESS by default
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = ResponseCursor("resp_1", 7, "provider_1"),
                providerSetting = openAiSetting(),
                api = api,
            )
        }

        recoveryService(
            gateway = gateway,
            flags = capabilityFlags(true),
            resumeStore = resumeStore,
            eventStore = eventStore,
        ).recover()

        // replayUnfinished skips pause states, so recovery itself must park
        // the protocol row RESUMABLE — otherwise it would be marked
        // INTERRUPTED while run_terminal keeps the run resumable.
        assertEquals(RunTerminalState.RESUMABLE, runTerminalStore.get("run_1")!!.state)
        assertEquals(
            app.amber.core.agent.runtime.RunStatus.RESUMABLE,
            eventStore.runs["run_1"]!!.status,
        )
    }

    @Test
    fun completedStoredResponseSettlesAPausedProtocolRunRow() = runBlocking {
        // Checker path A: cold start 1 parked the protocol row RESUMABLE via
        // server_in_progress, the process died again before the user resumed,
        // and cold start 2 now sees the server response COMPLETED. No pause
        // state transitions straight to COMPLETED, so recovery must unpark to
        // RUNNING before settling — a direct CAS would be rejected illegal
        // and the row would sit in `resumable` forever.
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        runTerminalStore.pause("run_1", RunTerminalState.RESUMABLE, PauseReason.PROCESS_RESTART)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_1", "resp_1", 3, "provider_1")
        val repo = conversationRepository()
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "abc"))
        val eventStore = app.amber.core.agent.runtime.InMemoryAgentEventStore()
        seedAgentRun(eventStore, "run_1", app.amber.core.agent.runtime.RunStatus.RESUMABLE)

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

        recoveryService(
            gateway = gateway,
            flags = capabilityFlags(true),
            resumeStore = resumeStore,
            eventStore = eventStore,
        ).recover()

        assertEquals(RunTerminalState.COMPLETED, runTerminalStore.get("run_1")!!.state)
        assertEquals(
            app.amber.core.agent.runtime.RunStatus.COMPLETED,
            eventStore.runs["run_1"]!!.status,
        )
    }

    @Test
    fun inProgressStoredResponseUnparksAWaitingUserProtocolRow() = runBlocking {
        // A crash between the two approval-park writes can leave the protocol
        // row WAITING_USER while run_terminal is still RUNNING. Pause ->
        // RESUMABLE is not a legal direct transition, so recovery must unpark
        // first or the row would stay diverged from run_terminal.
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_1", "resp_1", 7, "provider_1")
        val repo = conversationRepository()
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "abc"))
        val eventStore = app.amber.core.agent.runtime.InMemoryAgentEventStore()
        seedAgentRun(eventStore, "run_1", app.amber.core.agent.runtime.RunStatus.WAITING_USER)

        val api = FakeStoredResponseApi() // IN_PROGRESS by default
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = ResponseCursor("resp_1", 7, "provider_1"),
                providerSetting = openAiSetting(),
                api = api,
            )
        }

        recoveryService(
            gateway = gateway,
            flags = capabilityFlags(true),
            resumeStore = resumeStore,
            eventStore = eventStore,
        ).recover()

        assertEquals(RunTerminalState.RESUMABLE, runTerminalStore.get("run_1")!!.state)
        assertEquals(
            app.amber.core.agent.runtime.RunStatus.RESUMABLE,
            eventStore.runs["run_1"]!!.status,
        )
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
    fun recoveryNetworkResultCannotOverwriteConversationCursorOrRuntimeAfterRestore() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val gatedLedger = RoomToolEffectLedger(
            dao = database.toolEffectDao(),
            runTerminalDao = database.runTerminalDao(),
            json = kotlinx.serialization.json.Json,
            restoreWriteGate = gate,
        )
        val gatedTerminal = RoomRunTerminalStore(
            dao = database.runTerminalDao(),
            restoreWriteGate = gate,
        )
        val gatedResumeStore = RoomResponseResumeStore(
            dao = database.runResumeDao(),
            restoreWriteGate = gate,
        )
        val gatedRepo = conversationRepository(gate)
        val conversationId = Uuid.random()
        val runId = "run_restore_race"
        val oldSetting = openAiSetting()

        gatedTerminal.begin(runId, conversationId.toString(), null)
        gatedResumeStore.save(runId, "resp_old", 3, oldSetting.id.toString())
        gatedRepo.insertConversation(conversationWithPartial(conversationId, partialText = "old-partial"))

        val statusEntered = CompletableDeferred<Unit>()
        val releaseStatus = CompletableDeferred<Unit>()
        val statusReturned = CompletableDeferred<Unit>()
        val streamCompleted = CompletableDeferred<Unit>()
        val api = FakeStoredResponseApi().apply {
            status = StoredResponseStatus(StoredResponseState.COMPLETED, "resp_old")
            finalText = "old-network-result"
            this.statusEntered = statusEntered
            this.releaseStatus = releaseStatus
            this.statusReturned = statusReturned
            this.streamCompleted = streamCompleted
        }
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = ResponseCursor("resp_old", 3, oldSetting.id.toString()),
                providerSetting = oldSetting,
                api = api,
            )
        }
        val service = RunRecoveryService(
            ledger = gatedLedger,
            runTerminalStore = gatedTerminal,
            conversationRepo = gatedRepo,
            json = kotlinx.serialization.json.Json,
            storedResponseGateway = gateway,
            capabilityFlags = capabilityFlags(true),
            resumeStore = gatedResumeStore,
            restoreWriteGate = gate,
        )

        val recoveryJob = launch { service.recover() }
        statusEntered.await()

        val restoredConversation = conversationWithPartial(conversationId, partialText = "restored-content")
        val restoreTerminalStore = RoomRunTerminalStore(dao = database.runTerminalDao())
        val restoreResumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        val restoreStarted = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        val restoreJob = launch {
            gate.withRestore {
                restoreTerminalStore.pause(runId, RunTerminalState.RESUMABLE, PauseReason.PROCESS_RESTART)
                restoreResumeStore.save(runId, "resp_new", 42, "provider_new")
                gatedRepo.upsertConversationsForExchange(listOf(restoredConversation))
                restoreStarted.complete(Unit)
                releaseRestore.await()
            }
        }
        restoreStarted.await()

        // The provider result returns while restore still owns the durable
        // write section. Recovery must keep its pre-restore epoch and drop
        // every later terminal, cursor, and conversation write.
        releaseStatus.complete(Unit)
        statusReturned.await()
        withTimeout(10_000) { streamCompleted.await() }
        releaseRestore.complete(Unit)
        restoreJob.join()
        recoveryJob.join()

        val conversation = gatedRepo.getConversationById(conversationId)!!
        val lastAssistant = conversation.messageNodes.last { it.role == MessageRole.ASSISTANT }
        assertEquals(
            "restored-content",
            (lastAssistant.currentMessage.parts.single() as UIMessagePart.Text).text,
        )
        assertEquals(
            ResponseCursor("resp_new", 42, "provider_new"),
            gatedResumeStore.load(runId),
        )
        val run = gatedTerminal.get(runId)!!
        assertEquals(RunTerminalState.RESUMABLE, run.state)
        assertEquals(PauseReason.PROCESS_RESTART, run.pauseReason)
        assertNull(run.finishedAtMs)
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

    /**
     * Q08 混合状态（Phase 6）：同一个 run 同时存在服务端 COMPLETED 的 stored
     * response 和本地未决 tool effects。这是 handoff 指出的未闭合缺口——
     * 之前只有单独的 server-completed 和单独的 local-started 测试。
     */
    private suspend fun mixedEffect(
        runId: String,
        toolCallId: String,
        effectClass: app.amber.feature.tools.ToolEffectClass,
    ): ToolEffect {
        val effect = ledger.prepare(
            runId = runId,
            turnId = 0,
            toolCallId = toolCallId,
            toolName = "post_message",
            input = """{"text":"hello"}""",
            effectClass = effectClass,
        )
        ledger.markStarted(effect.effectId, approvalDigest(runId, toolCallId, effect.argsDigest))
        return effect
    }

    @Test
    fun serverCompletedRunWithStartedEffectsKeepsEffectsHonestWithoutReExecution() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_mix", conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_mix", "resp_mix", 2, "provider_1")
        val repo = conversationRepository()
        repo.insertConversation(conversationWithPartial(conversationId, partialText = "ab", partialId = Uuid.random()))

        // 同一 run 的三类本地 effect：非幂等（不确定副作用）、只读（可安全重试）、幂等写。
        val risky = mixedEffect("run_mix", "call_risky", app.amber.feature.tools.ToolEffectClass.NON_IDEMPOTENT_WRITE)
        val readOnly = mixedEffect("run_mix", "call_read", app.amber.feature.tools.ToolEffectClass.READ_ONLY)
        val idempotent = mixedEffect("run_mix", "call_idem", app.amber.feature.tools.ToolEffectClass.IDEMPOTENT_WRITE)

        val api = FakeStoredResponseApi().apply {
            status = StoredResponseStatus(StoredResponseState.COMPLETED, "resp_mix")
            missingEvents = listOf(3L to "cd")
            finalText = "abcd"
        }
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = ResponseCursor("resp_mix", 2, "provider_1"),
                providerSetting = openAiSetting(),
                api = api,
            )
        }

        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = resumeStore).recover()

        // 模型侧已完成：最终消息合并、terminal COMPLETED、cursor 清除。
        val updated = repo.getConversationById(conversationId)!!
        val lastAssistant = updated.messageNodes.last { it.role == MessageRole.ASSISTANT }
        assertEquals("abcd", (lastAssistant.currentMessage.parts.single() as UIMessagePart.Text).text)
        val run = runTerminalStore.get("run_mix")!!
        assertEquals(RunTerminalState.COMPLETED, run.state)
        assertNull(resumeStore.load("run_mix"))

        // 工具侧保持诚实：非幂等 STARTED 升级 OUTCOME_UNKNOWN（不重试、不假装成功）；
        // 只读/幂等写保持 STARTED（安全可重试）。任何 effect 都没有被重新执行
        //（重放只作用于 FINISHED 效果，这里全部不是 FINISHED）。
        assertEquals(ToolEffectStatus.OUTCOME_UNKNOWN, ledger.get(risky.effectId)!!.status)
        assertEquals("interrupted_mid_execution", ledger.get(risky.effectId)!!.errorCategory)
        assertEquals(ToolEffectStatus.STARTED, ledger.get(readOnly.effectId)!!.status)
        assertEquals(ToolEffectStatus.STARTED, ledger.get(idempotent.effectId)!!.status)
        // 不确定副作用对用户可见：outcome-unknown 查询能找到它。
        assertEquals(listOf(risky.effectId), ledger.listOutcomeUnknown().map { it.effectId })
    }

    @Test
    fun serverCancelledRunWithStartedNonIdempotentEffectMarksOutcomeUnknown() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_cancel_mix", conversationId.toString(), null)
        val resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())
        resumeStore.save("run_cancel_mix", "resp_c", 1, "provider_1")
        repoInsertPlainConversation(conversationId)

        val risky = mixedEffect("run_cancel_mix", "call_risky", app.amber.feature.tools.ToolEffectClass.NON_IDEMPOTENT_WRITE)

        val api = FakeStoredResponseApi().apply {
            status = StoredResponseStatus(StoredResponseState.CANCELLED, "resp_c")
        }
        val gateway = FakeStoredResponseGateway().apply {
            session = StoredResponseGateway.StoredResponseSession(
                cursor = ResponseCursor("resp_c", 1, "provider_1"),
                providerSetting = openAiSetting(),
                api = api,
            )
        }

        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = resumeStore).recover()

        // 服务端 CANCELLED：terminal CANCELLED；非幂等 effect 同样不确定、不可重试。
        assertEquals(RunTerminalState.CANCELLED, runTerminalStore.get("run_cancel_mix")!!.state)
        assertEquals(ToolEffectStatus.OUTCOME_UNKNOWN, ledger.get(risky.effectId)!!.status)
        assertNull(resumeStore.load("run_cancel_mix"))
    }

    @Test
    fun missingCursorWithStartedEffectsFallsBackToPhase1Rules() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_no_cursor", conversationId.toString(), null)
        repoInsertPlainConversation(conversationId)
        val risky = mixedEffect("run_no_cursor", "call_risky", app.amber.feature.tools.ToolEffectClass.NON_IDEMPOTENT_WRITE)

        // 有 gateway 但该 run 没有 stored response（resolve 返回 null）→ Phase 1 规则。
        val gateway = FakeStoredResponseGateway().apply { session = null }
        recoveryService(gateway = gateway, flags = capabilityFlags(true), resumeStore = RoomResponseResumeStore(dao = database.runResumeDao())).recover()

        assertEquals(RunTerminalState.OUTCOME_UNKNOWN, runTerminalStore.get("run_no_cursor")!!.state)
        assertEquals(ToolEffectStatus.OUTCOME_UNKNOWN, ledger.get(risky.effectId)!!.status)
    }

    private suspend fun repoInsertPlainConversation(conversationId: Uuid) {
        conversationRepository().insertConversation(
            conversationWithPartial(conversationId, partialText = "x", partialId = Uuid.random())
        )
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

/** Simulates process death immediately after the terminal write-ahead save. */
private class ThrowAfterSequenceSaveStore(
    private val delegate: ResponseResumeStore,
    private val terminalSequence: Long,
) : ResponseResumeStore {
    override suspend fun save(runId: String, responseId: String, sequence: Long, providerId: String) {
        delegate.save(runId, responseId, sequence, providerId)
        if (sequence == terminalSequence) {
            throw CancellationException("simulated process death after terminal cursor save")
        }
    }

    override suspend fun load(runId: String): ResponseCursor? = delegate.load(runId)

    override suspend fun clear(runId: String) = delegate.clear(runId)
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
