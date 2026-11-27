package app.amber.core.service

import android.content.ContextWrapper
import android.net.Uri
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.core.files.FileFolders
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import app.amber.feature.macgateway.ConversationStoreCloudDialogueSink
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.review.ReflectionFixture
import kotlin.uuid.Uuid
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationStoreTest : DurableRuntimeTestBase() {
    @get:Rule val queueDirectory = TemporaryFolder()
    private val fixtures = mutableListOf<Fixture>()

    @After
    fun closeStores() {
        fixtures.forEach { it.close() }
    }

    @Test
    fun `tail checkpoint preserves unloaded history and latest window edits`() = runBlocking {
        val fixture = fixture()
        val original = conversation(125)
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val flow = fixture.store.getConversationFlow(original.id)
        assertEquals(80, flow.value.messageNodes.size)
        assertEquals(45, fixture.store.getTimelineLoadStateFlow(original.id).value.oldestLoadedIndex)
        val edited = flow.value.messageNodes.first().copy(messages = listOf(UIMessage.user("edited tail")))
        val appended = MessageNode.of(UIMessage.user("new input"))
        val changed = flow.value.copy(
            messageNodes = listOf(edited) + flow.value.messageNodes.drop(1) + appended,
        )
        fixture.store.updateConversation(original.id, changed)
        assertTrue(fixture.store.checkpointConversation(original.id, changed, force = true))
        val persisted = fixture.repo.getConversationById(original.id)!!
        assertEquals(original.messageNodes.take(45), persisted.messageNodes.take(45))
        assertEquals(126, persisted.messageNodes.size)
        assertEquals(edited, persisted.messageNodes[45])
        assertEquals(appended, persisted.messageNodes.last())
        assertEquals(81, flow.value.messageNodes.size)
    }

    @Test
    fun `append returns only after its window is durable without expanding the UI history`() = runBlocking {
        val fixture = fixture()
        val original = conversation(125)
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val appended = MessageNode.of(UIMessage.user("New input"))
        fixture.store.appendUserNode(original.id, appended)
        assertEquals(original.messageNodes + appended, fixture.repo.getConversationById(original.id)!!.messageNodes)
        assertEquals(81, fixture.store.getConversationFlow(original.id).value.messageNodes.size)
        assertEquals(45, fixture.store.getTimelineLoadStateFlow(original.id).value.oldestLoadedIndex)
    }

    @Test
    fun `generation reads full history with fresh window without publishing it to UI`() = runBlocking {
        val fixture = fixture()
        val original = conversation(125)
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val flow = fixture.store.getConversationFlow(original.id)
        val appended = MessageNode.of(UIMessage.user("not checkpointed yet"))
        fixture.store.updateConversation(original.id, flow.value.copy(messageNodes = flow.value.messageNodes + appended))
        val generatedFrom = fixture.store.loadFullConversationForGeneration(original.id)
        assertEquals(original.messageNodes + appended, generatedFrom.messageNodes)
        assertEquals(81, flow.value.messageNodes.size)
        assertEquals(45, fixture.store.getTimelineLoadStateFlow(original.id).value.oldestLoadedIndex)
        assertEquals(original, fixture.repo.getConversationById(original.id))
    }

    @Test
    fun `full save from tail retains unloaded nodes while applying latest metadata`() = runBlocking {
        val fixture = fixture()
        val original = conversation(125)
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val changed = fixture.store.getConversationFlow(original.id).value.copy(title = "New title", isPinned = true)
        fixture.store.saveConversation(original.id, changed)
        val persisted = fixture.repo.getConversationById(original.id)!!
        assertEquals(original.messageNodes, persisted.messageNodes)
        assertEquals("New title", persisted.title)
        assertTrue(persisted.isPinned)
        assertTrue(fixture.store.getTimelineLoadStateFlow(original.id).value.isFullyLoaded)
    }

    @Test
    fun `concurrent initialization cannot overwrite the first accepted message`() = runBlocking {
        val settings = MutableStateFlow(Settings.dummy())
        val fixture = fixture(settings = settings)
        val id = Uuid.random()
        val firstMessage = MessageNode.of(UIMessage.user("first"))
        val firstInit = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.store.ensureInitialized(id)
            fixture.store.updateConversation(id, fixture.store.getConversationFlow(id).value.copy(messageNodes = listOf(firstMessage)))
        }
        val secondInit = async(start = CoroutineStart.UNDISPATCHED) { fixture.store.ensureInitialized(id) }
        settings.value = Settings()
        firstInit.await()
        secondInit.await()
        fixture.store.ensureInitialized(id)
        assertEquals(listOf(firstMessage), fixture.store.getConversationFlow(id).value.messageNodes)
    }

    @Test
    fun `tombstone prevents checkpoint and full save from resurrecting deleted history until undo`() = runBlocking {
        val fixture = fixture()
        val original = conversation(2)
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        fixture.store.markDeleted(original.id, fixture.gate.currentEpoch())
        fixture.store.deletePersisted(original, deferCleanup = true, expectedRestoreEpoch = fixture.gate.currentEpoch())
        fixture.store.checkpointConversation(original.id, original, force = true)
        fixture.store.saveConversation(original.id, original.copy(title = "stale writer"))
        assertNull(fixture.repo.getConversationById(original.id))
        fixture.store.markConversationRestored(original.id)
        fixture.store.saveConversation(original.id, original)
        assertEquals(original, fixture.repo.getConversationById(original.id))
    }

    @Test
    fun `local cloud echo requires a committed node and late remote echo is ignored on a tombstone`() = runBlocking {
        val original = conversation(0)
        val fixture = fixture()
        val sink = ConversationStoreCloudDialogueSink(fixture.store)
        val id = original.id.toString()
        val acceptedId = Uuid.random()
        sink.appendLocalUserMessage(id, "accepted", null, acceptedId.toString())
        val saved = fixture.repo.getConversationById(original.id)!!
        assertEquals(acceptedId, saved.currentMessages.single().id)
        assertEquals("accepted", saved.title)
        val flow = fixture.store.getConversationFlow(original.id)
        val before = flow.value
        val epoch = fixture.gate.currentEpoch()
        fixture.store.markDeleted(original.id, epoch)
        fixture.store.deletePersisted(saved, deferCleanup = false, expectedRestoreEpoch = epoch)

        val rejected = runCatching { sink.appendLocalUserMessage(id, "new", null, Uuid.random().toString()) }
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
        sink.appendUserMessage(id, "late remote echo", "old-event")
        assertSame(before, flow.value)
        assertNull(fixture.repo.getConversationById(original.id))
    }

    @Test
    fun `deleted main session reopens empty and saves new input without resurrecting old files or index`() = runBlocking {
        val seeded = conversation(90)
        val fixture = fixture(isMainConversation = { it == seeded.id })
        val attachment = context.filesDir.resolve(FileFolders.UPLOAD).resolve("${seeded.id}.txt")
        attachment.parentFile!!.mkdirs()
        attachment.writeText("old attachment")
        val old = UIMessage.user("old text").let { message ->
            message.copy(parts = message.parts + UIMessagePart.Document(Uri.fromFile(attachment).toString(), attachment.name))
        }
        val original = seeded.copy(messageNodes = listOf(MessageNode.of(old)) + seeded.messageNodes)
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val displayed = fixture.store.getConversationFlow(original.id)
        val tail = displayed.value
        assertTrue(tail.messageNodes.size < original.messageNodes.size)
        val fts = MessageFtsManager(database)
        assertNotNull(fts.findNodeIdForMessage(original.id.toString(), old.id.toString()))

        val epoch = fixture.gate.currentEpoch()
        fixture.store.markDeleted(original.id, epoch)
        fixture.store.deletePersisted(tail, deferCleanup = false, expectedRestoreEpoch = epoch)
        fixture.store.ensureInitialized(original.id)
        assertTrue(displayed.value.messageNodes.isEmpty())
        assertFalse(attachment.exists())
        assertNull(fts.findNodeIdForMessage(original.id.toString(), old.id.toString()))
        assertNull(fixture.repo.getConversationById(original.id))

        val first = MessageNode.of(UIMessage.user("first after delete"))
        fixture.store.appendUserNode(original.id, first)
        fixture.store.updateConversation(original.id, tail)
        fixture.store.checkpointConversation(original.id, tail, force = true)
        fixture.store.saveConversation(original.id, original)
        assertEquals(listOf(first), displayed.value.messageNodes)
        assertEquals(listOf(first), fixture.repo.getConversationById(original.id)!!.messageNodes)
    }

    @Test
    fun `new main input waits for deferred attachment cleanup before becoming durable`() = runBlocking {
        val original = conversation(2)
        val fixture = fixture(isMainConversation = { it == original.id })
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val epoch = fixture.gate.currentEpoch()
        fixture.store.markDeleted(original.id, epoch)
        fixture.store.deletePersisted(original, deferCleanup = true, expectedRestoreEpoch = epoch)
        val first = MessageNode.of(UIMessage.user("sent while leaving History"))
        val write = async(start = CoroutineStart.UNDISPATCHED) { fixture.store.appendUserNode(original.id, first) }
        assertFalse(write.isCompleted)
        fixture.store.purgeDeletedConversation(original)
        write.await()
        assertEquals(listOf(first), fixture.repo.getConversationById(original.id)!!.messageNodes)
    }

    @Test
    fun `undo of a deleted main conversation reloads its original timeline`() = runBlocking {
        val original = conversation(2)
        val fixture = fixture(isMainConversation = { it == original.id })
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val epoch = fixture.gate.currentEpoch()
        fixture.store.markDeleted(original.id, epoch)
        fixture.store.deletePersisted(original, deferCleanup = true, expectedRestoreEpoch = epoch)
        fixture.store.ensureInitialized(original.id)
        fixture.repo.insertConversation(original)
        fixture.store.markConversationRestored(original.id)
        fixture.store.ensureInitialized(original.id)
        assertEquals(original.messageNodes, fixture.store.getConversationFlow(original.id).value.messageNodes)
    }

    @Test
    fun `committed restore invalidates the same subscriptions and rejects old writers`() = runBlocking {
        val fixture = fixture()
        val original = conversation(2)
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val flow = fixture.store.getConversationFlow(original.id)
        val timeline = fixture.store.getTimelineLoadStateFlow(original.id)
        val session = fixture.store.session(original.id)
        fixture.queue.enqueuePendingUserMessage(original.id, PendingUserMessage("old pending", UIMessage.user("old").parts))
        val oldEpoch = fixture.gate.currentEpoch()
        fixture.gate.withRestore {
            fixture.repo.updateConversation(original.copy(title = "Restored"))
            fixture.gate.markDataCommitted()
        }
        assertSame(flow, fixture.store.getConversationFlow(original.id))
        assertSame(timeline, fixture.store.getTimelineLoadStateFlow(original.id))
        assertFalse(timeline.value.initialized)
        assertTrue(flow.value.messageNodes.isEmpty())
        assertTrue(session.pendingUserMessages.value.isEmpty())
        val failure = runCatching {
            withContext(SyncRestoreWriteEpoch(oldEpoch)) { fixture.store.saveConversation(original.id, original) }
        }.exceptionOrNull()
        assertTrue(failure is SyncRestoreWriteRejectedException)
        fixture.store.ensureInitialized(original.id)
        assertEquals("Restored", flow.value.title)
    }

    @Test
    fun `failed restore without data commit keeps loaded state and tombstone`() = runBlocking {
        val fixture = fixture()
        val original = conversation(2)
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val flow = fixture.store.getConversationFlow(original.id)
        fixture.store.markDeleted(original.id, fixture.gate.currentEpoch())
        runCatching { fixture.gate.withRestore { error("restore failed before commit") } }
        assertEquals(original, flow.value)
        fixture.store.saveConversation(original.id, original.copy(title = "stale"))
        assertEquals(original, fixture.repo.getConversationById(original.id))
    }

    @Test
    fun `idle timeout retains pending input and evicts only an unused empty session`() = runTest {
        val fixture = fixture(scope = backgroundScope)
        val queuedId = Uuid.random()
        val idleId = Uuid.random()
        val queued = fixture.store.session(queuedId)
        fixture.queue.enqueuePendingUserMessage(queuedId, PendingUserMessage("queued", UIMessage.user("keep").parts))
        fixture.store.addConversationReference(queuedId)
        fixture.store.removeConversationReference(queuedId)
        fixture.store.addConversationReference(idleId)
        fixture.store.removeConversationReference(idleId)
        advanceTimeBy(5_001)
        runCurrent()
        assertSame(queued, fixture.store.sessionOrNull(queuedId))
        assertNotNull(fixture.store.getConversationFlowOrNull(queuedId))
        assertNull(fixture.store.getConversationFlowOrNull(idleId))
    }

    private fun fixture(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        settings: MutableStateFlow<Settings> = MutableStateFlow(Settings()),
        isMainConversation: (Uuid) -> Boolean = { false },
    ): Fixture {
        val gate = SyncRestoreWriteGate()
        val repo = conversationRepository(gate)
        val aggregator = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(aggregator, "_settingsFlow", settings)
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), AppScope(), gate)
        val pendingDirectory = queueDirectory.newFolder()
        val pendingStore = PendingMessageStore(object : ContextWrapper(context) {
            override fun getFilesDir() = pendingDirectory
        }, Json)
        val queue = PendingMessageQueue(scope, pendingStore, gate)
        val store = ConversationStore(repo, aggregator, scope, files, queue, ChatErrorStore(), gate, isMainConversation)
        return Fixture(store, queue, repo, gate, scope).also(fixtures::add)
    }

    private fun conversation(size: Int): Conversation = Conversation.ofId(Uuid.random()).copy(
        title = "Conversation",
        createAt = Instant.ofEpochMilli(1_000),
        updateAt = Instant.ofEpochMilli(2_000),
        messageNodes = List(size) { MessageNode.of(UIMessage.user("Message $it")) },
    )

    private class Fixture(
        val store: ConversationStore,
        val queue: PendingMessageQueue,
        val repo: ConversationRepository,
        val gate: SyncRestoreWriteGate,
        val scope: CoroutineScope,
    ) {
        fun close() {
            store.cleanup()
            queue.cleanup()
            scope.cancel()
        }
    }
}
