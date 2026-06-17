package app.amber.feature.modelcouncil

import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.core.infra.AppScope
import app.amber.core.settings.Settings
import app.amber.feature.task.AgentTaskSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Coroutine-based tests for the Council Room generation path.
 *
 * These tests verify the PR2 wiring: host actions launch generation, streaming
 * updates merge into the room state, synthesis finalizes the room, and close()
 * behaves correctly (graceful vs cancel) while respecting the closing gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CouncilRoomManagerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        // Main dispatcher will be set per-test so AppScope captures the test
        // dispatcher and launched generation jobs participate in the test scheduler.
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `inviteNext launches guest generation and creates streaming message`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val modelId = Uuid.random()
        val runner = FakeModelCouncilTextRunner(responseChunks = listOf("Hello"), delayPerChunkMs = 10L)
        val env = createEnv(runner = runner, modelId = modelId)

        val guest = env.guest(modelId = modelId)
        env.openWithGuest(guest)

        val result = env.manager.hostAction(
            env.conversationId,
            HostAction.InviteNext(guest.id),
        )
        assertTrue(result is CouncilRoomOpResult.Ok)

        advanceUntilIdle()

        val room = env.store.peekRoom(env.conversationId)
        assertNotNull(room)
        val guestMessage = room!!.messages.firstOrNull { it.authorId == guest.id }
        assertNotNull(guestMessage)
        assertEquals("Hello", guestMessage!!.text)
        assertEquals(CouncilMessageStatus.COMPLETED, guestMessage.status)
        assertEquals(CouncilParticipantStatus.SPOKEN, room.participantById(guest.id)!!.status)

        val call = runner.calls.single()
        assertEquals(modelId, call.modelId)
        assertTrue(call.userPrompt.contains(guest.name))
    }

    @Test
    fun `streaming chunks merge into a single message`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val modelId = Uuid.random()
        val chunks = listOf("One ", "two ", "three")
        val runner = FakeModelCouncilTextRunner(responseChunks = chunks, delayPerChunkMs = 10L)
        val env = createEnv(runner = runner, modelId = modelId)

        val guest = env.guest(modelId = modelId)
        env.openWithGuest(guest)

        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))

        // Let the first chunk land and check the streaming merge.
        advanceTimeBy(15L)
        val midRoom = env.store.peekRoom(env.conversationId)!!
        val midMessage = midRoom.messages.single { it.authorId == guest.id }
        assertEquals(CouncilMessageStatus.STREAMING, midMessage.status)
        assertEquals("One ", midMessage.text)

        advanceUntilIdle()

        val finalRoom = env.store.peekRoom(env.conversationId)!!
        val finalMessage = finalRoom.messages.single { it.authorId == guest.id }
        assertEquals(CouncilMessageStatus.COMPLETED, finalMessage.status)
        assertEquals("One two three", finalMessage.text)
        // Only one message row was created (no duplicates per chunk).
        assertEquals(1, finalRoom.messages.count { it.authorId == guest.id })
    }

    @Test
    fun `synthesize launches host generation and finalizes room`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val guestModelId = Uuid.random()
        val hostModelId = Uuid.random()
        // Single runner, like production; guest and host responses are selected
        // by modelId.
        val runner = FakeModelCouncilTextRunner(
            responseChunks = listOf("Guest opinion"),
            delayPerChunkMs = 0L,
            byModelId = mapOf(
                guestModelId to FakeModelCouncilTextRunner.FakeResponse(listOf("Guest opinion")),
                hostModelId to FakeModelCouncilTextRunner.FakeResponse(listOf("Final verdict"), delayPerChunkMs = 10L),
            ),
        )
        val env = createEnv(runner = runner, modelId = guestModelId, hostModelId = hostModelId)

        val guest = env.guest(modelId = guestModelId)
        env.openWithGuest(guest)
        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))
        advanceUntilIdle()

        val synthResult = env.manager.synthesize(env.conversationId)
        assertTrue(synthResult is CouncilRoomOpResult.Ok)
        assertEquals(CouncilRoomStatus.FINALIZING, env.store.peekRoom(env.conversationId)!!.status)

        advanceUntilIdle()

        val room = env.store.peekRoom(env.conversationId)!!
        assertEquals(CouncilRoomStatus.FINALIZED, room.status)
        assertEquals("Final verdict", room.synthesis)

        // Two calls: guest turn + host synthesis.
        assertEquals(2, runner.calls.size)
        val hostCall = runner.calls.last()
        assertEquals(hostModelId, hostCall.modelId)
        assertTrue(hostCall.userPrompt.contains("Guest opinion"))
    }

    @Test
    fun `close cancel=true cancels guest generation and marks cancelled`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val modelId = Uuid.random()
        val runner = FakeModelCouncilTextRunner(
            responseChunks = listOf("Never", " finishes"),
            delayPerChunkMs = 60_000L,
        )
        val env = createEnv(runner = runner, modelId = modelId)

        val guest = env.guest(modelId = modelId)
        env.openWithGuest(guest)

        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))
        // Start the generation but don't let it finish.
        advanceTimeBy(10L)

        val closeResult = env.manager.close(env.conversationId, cancel = true)
        assertTrue(closeResult is CouncilRoomOpResult.Ok)
        assertEquals(CouncilRoomStatus.CANCELLED, (closeResult as CouncilRoomOpResult.Ok).room.status)

        advanceUntilIdle()
        assertNull(env.store.peekRoom(env.conversationId))
    }

    @Test
    fun `close cancel=false during FINALIZING waits for synthesis`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val guestModelId = Uuid.random()
        val hostModelId = Uuid.random()
        val runner = FakeModelCouncilTextRunner(
            responseChunks = listOf("Guest opinion"),
            byModelId = mapOf(
                guestModelId to FakeModelCouncilTextRunner.FakeResponse(listOf("Guest opinion")),
                hostModelId to FakeModelCouncilTextRunner.FakeResponse(listOf("Synthesis ", "complete"), delayPerChunkMs = 50L),
            ),
        )
        val env = createEnv(runner = runner, modelId = guestModelId, hostModelId = hostModelId)

        val guest = env.guest(modelId = guestModelId)
        env.openWithGuest(guest)
        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))
        advanceUntilIdle()

        env.manager.synthesize(env.conversationId)
        // Synthesis is running but not done yet.
        advanceTimeBy(10L)

        // Graceful close should wait for synthesis rather than cancelling it.
        val closeResult = env.manager.close(env.conversationId, cancel = false)
        assertTrue(closeResult is CouncilRoomOpResult.Ok)
        val closedRoom = (closeResult as CouncilRoomOpResult.Ok).room
        assertEquals(CouncilRoomStatus.FINALIZED, closedRoom.status)
        assertEquals("Synthesis complete", closedRoom.synthesis)
    }

    @Test
    fun `closing gate prevents new generation jobs from starting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val modelId = Uuid.random()
        val runner = FakeModelCouncilTextRunner(
            responseChunks = listOf("Never finishes"),
            delayPerChunkMs = 60_000L,
        )
        val env = createEnv(runner = runner, modelId = modelId)

        val guest = env.guest(modelId = modelId)
        env.openWithGuest(guest)

        // Start a long-running generation and begin closing before it finishes.
        env.manager.hostAction(env.conversationId, HostAction.InviteNext(guest.id))
        advanceTimeBy(10L)

        // Launch close in the background so the gate is set while we try to start
        // another guest turn.
        val closeJob = launch {
            env.manager.close(env.conversationId, cancel = true)
        }
        advanceTimeBy(5L)

        // A new InviteNext while closing should not launch another job. The result
        // is either Ok (gate blocked the launch, plan returned) or Err (room was
        // already evicted by close) — either is acceptable; the invariant we care
        // about is "no second generation call".
        val second = env.guest(id = "guest-2", modelId = modelId)
        env.manager.inviteParticipant(env.conversationId, second)
        env.manager.hostAction(
            env.conversationId,
            HostAction.InviteNext(second.id),
        )

        advanceUntilIdle()
        closeJob.join()

        // Only one generation call was made (the first guest).
        assertEquals(1, runner.calls.size)
        assertNull(env.store.peekRoom(env.conversationId))
    }

    // ── helpers ──────────────────────────────────────────────────────────────


    private fun TestScope.createEnv(
        runner: ModelCouncilTextRunner,
        modelId: Uuid,
        hostModelId: Uuid = modelId,
    ): TestEnv {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        val appScope = AppScope()
        val settingsFlow = MutableStateFlow(settingsWithModel(modelId, hostModelId))
        val store = FakeCouncilRoomStore()
        val reporter = FakeTaskReporter()
        val manager = CouncilRoomManager(
            appScope = appScope,
            settingsFlow = settingsFlow,
            json = json,
            modelRunner = runner,
            externalCliRunner = FakeExternalCliModelCouncilRunner(),
            store = store,
            taskReporter = reporter,
            dispatcher = testDispatcher,
        )
        return TestEnv(manager, store, appScope, modelId, hostModelId)
    }

    private fun settingsWithModel(guestModelId: Uuid, hostModelId: Uuid): Settings {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test Provider",
            models = listOf(
                Model(id = guestModelId, displayName = "Guest Model"),
                Model(id = hostModelId, displayName = "Host Model"),
            ),
            brand = OpenAIBrand.GENERIC,
        )
        return Settings(
            chatModelId = hostModelId,
            providers = listOf(provider),
        )
    }

    private class TestEnv(
        val manager: CouncilRoomManager,
        val store: FakeCouncilRoomStore,
        val appScope: AppScope,
        val guestModelId: Uuid,
        val hostModelId: Uuid,
    ) {
        val conversationId: Uuid = Uuid.random()

        fun guest(id: String = "guest-1", modelId: Uuid = guestModelId) = CouncilParticipant(
            id = id,
            name = "Guest ${id.last()}",
            role = "analyst",
            kind = CouncilParticipantKind.GUEST,
            modelId = modelId,
            runnerType = ModelCouncilSeatRunner.PROVIDER_MODEL,
        )

        suspend fun openWithGuest(guest: CouncilParticipant) {
            val result = manager.openRoom(
                conversationId = conversationId,
                hostAssistantId = Uuid.random(),
                hostName = "Host",
                objective = "Test objective",
                initialGuests = listOf(guest),
            )
            assertTrue(result is CouncilRoomOpResult.Ok)
        }
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    /**
     * Mirrors the production shape: a single runner serves both guest turns and
     * host synthesis — the two are distinguished only by [modelId]. [byModelId]
     * overrides per-model responses so a test can hand back "Guest opinion" for
     * the guest model and "Final verdict" for the host model from one instance.
     */
    private class FakeModelCouncilTextRunner(
        private val responseChunks: List<String>,
        private val delayPerChunkMs: Long = 0L,
        private val byModelId: Map<Uuid, FakeResponse> = emptyMap(),
    ) : ModelCouncilTextRunner {
        data class Call(
            val modelId: Uuid,
            val systemPrompt: String,
            val userPrompt: String,
        )

        data class FakeResponse(
            val chunks: List<String>,
            val delayPerChunkMs: Long = 0L,
        )

        private val _calls = mutableListOf<Call>()
        val calls: List<Call> get() = _calls

        override suspend fun generate(
            settings: Settings,
            modelId: Uuid,
            systemPrompt: String,
            userPrompt: String,
            outputBudgetChars: Int,
            reasoningLevel: ReasoningLevel?,
            temperature: Float?,
            userImageParts: List<UIMessagePart.Image>,
            onChunk: (String) -> Unit,
        ): ModelCouncilTextResult {
            _calls.add(Call(modelId, systemPrompt, userPrompt))
            val resp = byModelId[modelId]
            val chunks = resp?.chunks ?: responseChunks
            val chunkDelay = resp?.delayPerChunkMs ?: delayPerChunkMs
            var cumulative = ""
            chunks.forEach { chunk ->
                if (chunkDelay > 0) delay(chunkDelay)
                cumulative += chunk
                onChunk(cumulative)
            }
            return ModelCouncilTextResult(cumulative)
        }
    }

    private class FakeExternalCliModelCouncilRunner : ModelCouncilExternalCliRunner {
        override suspend fun generate(
            seat: ModelCouncilSeat,
            systemPrompt: String,
            userPrompt: String,
            timeoutMs: Long,
            outputBudgetChars: Int,
            onChunk: (String) -> Unit,
        ): String = error("Unexpected external CLI call in test")
    }

    private class FakeCouncilRoomStore : CouncilRoomStore {
        private val lock = Mutex()
        private val rooms = mutableMapOf<Uuid, MutableStateFlow<CouncilRoom?>>()

        override suspend fun observeRoom(conversationId: Uuid): StateFlow<CouncilRoom?> =
            lock.withLock {
                rooms.getOrPut(conversationId) { MutableStateFlow(null) }
            }

        override fun peekRoom(conversationId: Uuid): CouncilRoom? =
            rooms[conversationId]?.value

        override suspend fun upsertRoom(room: CouncilRoom) {
            lock.withLock {
                rooms.getOrPut(room.conversationId) { MutableStateFlow(null) }.value = room
            }
        }

        override suspend fun closeAndEvict(room: CouncilRoom) {
            lock.withLock {
                rooms[room.conversationId]?.value = room
                rooms.remove(room.conversationId)
            }
        }

        override suspend fun evict(conversationId: Uuid) {
            lock.withLock { rooms.remove(conversationId) }
        }

        override suspend fun flush(conversationId: Uuid) = Unit

        override suspend fun deleteRoom(conversationId: Uuid) {
            lock.withLock { rooms.remove(conversationId) }
        }
    }

    private class FakeTaskReporter : CouncilRoomTaskReporter {
        val registered = mutableListOf<AgentTaskSnapshot>()
        val upserted = mutableListOf<AgentTaskSnapshot>()

        override suspend fun register(
            snapshot: AgentTaskSnapshot,
            cancel: (suspend () -> Boolean)?,
        ) {
            registered.add(snapshot)
        }

        override suspend fun upsert(snapshot: AgentTaskSnapshot) {
            upserted.add(snapshot)
        }
    }
}
