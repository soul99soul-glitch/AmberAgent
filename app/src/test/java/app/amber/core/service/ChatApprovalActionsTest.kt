package app.amber.core.service

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.runtime.RunTerminalState
import app.amber.review.ReflectionFixture
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatApprovalActionsTest : DurableRuntimeTestBase() {
    @get:Rule val directory = TemporaryFolder()
    private val fixtures = mutableListOf<Fixture>()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun closeOwners() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun `approval durably updates full history and returns pause while another tool waits`() = runBlocking {
        val fixture = fixture()
        val approved = tool("call_screen", "screen_click")
        val waiting = tool("call_question", "ask_user")
        val earlier = List(100) { MessageNode.of(UIMessage.user("Earlier $it")) }
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = earlier + assistantTools(approved, waiting),
        )
        fixture.repo.insertConversation(conversation)
        fixture.store.ensureInitialized(conversation.id)
        assertEquals(80, fixture.store.getConversationFlow(conversation.id).value.messageNodes.size)

        val result = fixture.actions.applyToolApprovalDecision(
            conversation.id, approved.toolCallId, approved = true, reason = "", answer = null,
        )

        assertFalse(result.shouldResume)
        val persisted = fixture.repo.getConversationById(conversation.id)!!
        assertEquals(earlier, persisted.messageNodes.dropLast(1))
        assertEquals(ToolApprovalState.Approved, persisted.currentMessages.last().getTools().first().approvalState)
        assertTrue(persisted.currentMessages.last().getTools().last().isPending)
        assertTrue("approval creates trust only for the owning conversation", "screen_click" in fixture.actions.trustedToolNames(conversation.id))
        assertTrue(fixture.actions.trustedToolNames(Uuid.random()).isEmpty())
        assertNull("persisting approval must not launch a run", fixture.store.session(conversation.id).getJob())

        val answered = fixture.actions.applyToolApprovalDecision(
            conversation.id, waiting.toolCallId, approved = false, reason = "", answer = "Use the first option",
        )
        assertTrue(answered.shouldResume)
        assertEquals(
            ToolApprovalState.Answered("Use the first option"),
            fixture.repo.getConversationById(conversation.id)!!.currentMessages.last().getTools().last().approvalState,
        )
        fixture.actions.clearTrust(conversation.id)
        assertTrue(fixture.actions.trustedToolNames(conversation.id).isEmpty())
    }

    @Test
    fun `a decision already applied cannot resume again or be overwritten by a stale approval`() = runBlocking {
        val fixture = fixture()
        val waiting = tool("once", "file_write")
        val conversation = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(assistantTools(waiting)))
        fixture.repo.insertConversation(conversation)

        assertTrue(fixture.actions.applyToolApprovalDecision(
            conversation.id, waiting.toolCallId, approved = true, reason = "", answer = null,
        ).shouldResume)
        val duplicate = fixture.actions.applyToolApprovalDecision(
            conversation.id, waiting.toolCallId, approved = true, reason = "", answer = null,
        )
        assertFalse(duplicate.applied)
        assertFalse(duplicate.shouldResume)
        val stale = fixture.actions.applyToolApprovalDecision(
            conversation.id, waiting.toolCallId, approved = false, reason = "stale", answer = null,
        )
        assertFalse(stale.applied)
        assertFalse(stale.shouldResume)
        assertEquals(
            ToolApprovalState.Approved,
            fixture.repo.getConversationById(conversation.id)!!.currentMessages.last().getTools().single().approvalState,
        )
    }

    @Test
    fun `batch approval keeps human and Jev decisions pending`() = runBlocking {
        val fixture = fixture()
        val ordinary = tool("ordinary", "file_write")
        val ask = tool("ask", "ask_user")
        val jev = tool("jev", "terminal_execute").copy(metadata = buildJsonObject {
            put(app.amber.core.jev.JevAutoApprovalGate.METADATA_KEY, true)
        })
        val triage = tool("triage", "terminal_execute").copy(metadata = buildJsonObject {
            put(app.amber.core.jev.JevApprovalTriage.METADATA_KEY, true)
        })
        val conversation = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(assistantTools(ordinary, ask, jev, triage)))
        fixture.repo.insertConversation(conversation)

        val decision = fixture.actions.approvePendingAutoApprovableTools(conversation.id)!!

        assertFalse(decision.shouldResume)
        val persisted = fixture.repo.getConversationById(conversation.id)!!.currentMessages.last().getTools()
        assertEquals(ToolApprovalState.Approved, persisted[0].approvalState)
        assertTrue(persisted[1].isPending)
        assertTrue(persisted[2].isPending)
        assertTrue(persisted[3].isPending)
        assertNull(fixture.actions.approvePendingAutoApprovableTools(conversation.id))
        assertNull(fixture.store.session(conversation.id).getJob())
    }

    @Test
    fun `settings resume requires the matching live run and leaves tools unmodified`() = runBlocking {
        val fixture = fixture()
        val runId = "approval-run"
        val waiting = tool("waiting", "terminal_execute").copy(metadata = buildJsonObject { put("run_id", runId) })
        val conversation = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(assistantTools(waiting)))
        fixture.repo.insertConversation(conversation)
        runTerminalStore.begin(runId, conversation.id.toString(), null)
        runTerminalStore.pause(runId, RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)

        assertNull(fixture.actions.pendingRunForCurrentApprovalSettings(conversation.id, false))
        assertEquals(AgentRunId(runId), fixture.actions.pendingRunForCurrentApprovalSettings(conversation.id, true))
        assertTrue(fixture.repo.getConversationById(conversation.id)!!.currentMessages.last().getTools().single().isPending)
        assertNull(fixture.store.session(conversation.id).getJob())

        val foreign = waiting.copy(metadata = buildJsonObject { put("run_id", "other-run") })
        fixture.store.saveConversation(conversation.id, conversation.copy(messageNodes = listOf(assistantTools(foreign))))
        assertNull(fixture.actions.pendingRunForCurrentApprovalSettings(conversation.id, true))

        fixture.store.saveConversation(conversation.id, conversation)
        runTerminalStore.cancelWaitingUser(runId, conversation.id.toString())
        assertNull(fixture.actions.pendingRunForCurrentApprovalSettings(conversation.id, true))
    }

    @Test
    fun `pre restore approval is rejected before overwriting restored tool state`() = runBlocking {
        val fixture = fixture()
        val waiting = tool("waiting", "file_write")
        val conversation = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(assistantTools(waiting)))
        fixture.repo.insertConversation(conversation)
        fixture.store.ensureInitialized(conversation.id)
        val oldEpoch = fixture.gate.currentEpoch()
        fixture.gate.withRestore {
            fixture.repo.updateConversation(conversation.copy(title = "Restored"))
            fixture.gate.markDataCommitted()
        }

        val failure = runCatching {
            withContext(SyncRestoreWriteEpoch(oldEpoch)) {
                fixture.actions.applyToolApprovalDecision(conversation.id, waiting.toolCallId, true, "", null)
            }
        }.exceptionOrNull()

        assertTrue(failure is SyncRestoreWriteRejectedException)
        val persisted = fixture.repo.getConversationById(conversation.id)!!
        assertEquals("Restored", persisted.title)
        assertTrue(persisted.currentMessages.last().getTools().single().isPending)
        assertNull(fixture.store.session(conversation.id).getJob())
    }

    private fun tool(callId: String, name: String) = UIMessagePart.Tool(
        toolCallId = callId, toolName = name, input = "{}", approvalState = ToolApprovalState.Pending,
    )

    private fun assistantTools(vararg tools: UIMessagePart.Tool): MessageNode = MessageNode.of(
        UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList()),
    )

    private fun fixture(): Fixture {
        val root = directory.newFolder()
        val fileContext = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val scope = AppScope()
        val gate = SyncRestoreWriteGate()
        val files = FilesManager(fileContext, FilesRepository(database.managedFileDao()), scope, gate)
        val repo = ConversationRepository(
            database.conversationDao(), database.messageNodeDao(), database.messageStatsDao(), database.favoriteDao(),
            database, files, MessageFtsManager(database), gate,
        )
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(Settings()))
        val queue = PendingMessageQueue(scope, PendingMessageStore(fileContext, Json), gate)
        val store = ConversationStore(repo, settings, scope, files, queue, ChatErrorStore(), gate)
        val flags = CapabilityFlags(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        })
        val recovery = RunRecoveryService(ledger, runTerminalStore, repo, Json)
        val actions = ChatApprovalActions(
            context, Json, store, ledger, runTerminalStore, recovery, flags, restoreWriteGate = gate,
        )
        return Fixture(actions, repo, store, queue, gate, scope).also(fixtures::add)
    }

    private class Fixture(
        val actions: ChatApprovalActions,
        val repo: ConversationRepository,
        val store: ConversationStore,
        val queue: PendingMessageQueue,
        val gate: SyncRestoreWriteGate,
        val scope: AppScope,
    ) {
        fun close() {
            store.cleanup()
            queue.cleanup()
            scope.cancel()
        }
    }
}
