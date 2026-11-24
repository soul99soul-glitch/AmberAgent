package app.amber.core.service

import android.app.Application
import android.content.ContextWrapper
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConversationSessionQueueTest {
    @get:Rule val directory = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queues = mutableListOf<PendingMessageQueue>()

    @After
    fun closeQueues() {
        queues.forEach { it.cleanup() }
        scope.cancel()
    }

    @Test
    fun pendingMessagesAreDequeuedInFifoOrder() = runBlocking {
        val fixture = queue()
        val id = Uuid.random()
        val pending = fixture.queue.flow(id)
        assertTrue(fixture.queue.enqueuePendingUserMessage(id, pending("first")))
        assertTrue(fixture.queue.enqueuePendingUserMessage(id, pending("second")))
        fixture.queue.persistCurrentPendingMessagesNow(id)
        assertEquals(listOf("first", "second"), fixture.store.load(id).map { it.id })
        assertEquals("first", fixture.queue.dequeueNextPendingUserMessageDurably(id)?.id)
        assertEquals(listOf("second"), fixture.store.load(id).map { it.id })
        assertEquals("second", fixture.queue.dequeueNextPendingUserMessageDurably(id)?.id)
        assertTrue(fixture.store.load(id).isEmpty())
        assertNull(fixture.queue.dequeueNextPendingUserMessageDurably(id))
        assertTrue(pending.value.isEmpty())
    }

    @Test
    fun steerMessagesAreInsertedBeforeFollowupMessages() {
        val queue = queue().queue
        val id = Uuid.random()
        queue.enqueuePendingUserMessage(id, pending("steer-1", PendingUserMessageMode.STEER))
        queue.enqueuePendingUserMessage(id, pending("steer-2", PendingUserMessageMode.STEER))
        queue.enqueuePendingUserMessage(id, pending("followup", PendingUserMessageMode.FOLLOWUP))
        queue.enqueuePendingUserMessage(id, pending("steer-3", PendingUserMessageMode.STEER))
        val consumed = queue.consumeSteerMessagesForRun(id)
        assertEquals(listOf("steer-1", "steer-2", "steer-3"), consumed.map { (it.parts.single() as UIMessagePart.Text).text })
        assertEquals(listOf("followup"), queue.flow(id).value.map { it.id })
    }

    @Test
    fun queueAcceptsUnboundedMessagesAndCancelIsStable() {
        val queue = queue().queue
        val id = Uuid.random()
        repeat(MAX_PENDING_USER_MESSAGES + 5) { index ->
            assertTrue(queue.enqueuePendingUserMessage(id, pending("message-$index")))
        }
        assertTrue(queue.cancelPendingUserMessage(id, "message-3"))
        assertFalse(queue.cancelPendingUserMessage(id, "missing"))
        assertEquals(MAX_PENDING_USER_MESSAGES + 4, queue.flow(id).value.size)
    }

    @Test
    fun movePendingMessageReordersQueue() {
        val queue = queue().queue
        val id = Uuid.random()
        queue.enqueuePendingUserMessage(id, pending("first"))
        queue.enqueuePendingUserMessage(id, pending("second"))
        queue.enqueuePendingUserMessage(id, pending("third"))
        assertTrue(queue.movePendingUserMessage(id, "third", -2))
        assertEquals(listOf("third", "first", "second"), queue.flow(id).value.map { it.id })
        assertFalse(queue.movePendingUserMessage(id, "third", -1))
        assertFalse(queue.movePendingUserMessage(id, "missing", 1))
    }

    @Test
    fun stopSemanticsPreserveQueueWithoutDowngrade() {
        val queue = queue().queue
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), queue.flow(id), scope, onIdle = {})
        queue.enqueuePendingUserMessage(id, pending("steer", PendingUserMessageMode.STEER))
        queue.enqueuePendingUserMessage(id, pending("followup", PendingUserMessageMode.FOLLOWUP))
        queue.enqueuePendingUserMessage(id, pending("attachment", PendingUserMessageMode.FOLLOWUP))
        assertSame(queue.flow(id), session.pendingUserMessages)
        assertEquals(
            listOf(PendingUserMessageMode.STEER, PendingUserMessageMode.FOLLOWUP, PendingUserMessageMode.FOLLOWUP),
            session.pendingUserMessages.value.map { it.mode },
        )
        assertEquals(listOf("steer", "followup", "attachment"), session.pendingUserMessages.value.map { it.id })
        assertTrue(session.isInUse)
        assertFalse(session.isGenerating)
    }

    @Test
    fun collectedPendingMessagesKeepStableMarkers() {
        val collected = buildCollectedPendingUserMessage(
            listOf(pending("first", PendingUserMessageMode.COLLECT), pending("second", PendingUserMessageMode.COLLECT)),
        )
        assertEquals(PendingUserMessageMode.FOLLOWUP, collected.mode)
        val text = collected.previewText(maxChars = 1_000)
        assertTrue(text.contains("Queued #1"))
        assertTrue(text.contains("first"))
        assertTrue(text.contains("Queued #2"))
        assertTrue(text.contains("second"))
    }

    @Test
    fun collectDrainIsDurableBeforePreparedInputIsReturned() = runBlocking {
        val fixture = queue()
        val id = Uuid.random()
        fixture.queue.enqueuePendingUserMessage(id, pending("first", PendingUserMessageMode.COLLECT))
        fixture.queue.enqueuePendingUserMessage(id, pending("second", PendingUserMessageMode.COLLECT))
        fixture.queue.enqueuePendingUserMessage(id, pending("later"))
        val first = fixture.queue.dequeueNextPendingUserMessageDurably(id)!!
        val prepared = fixture.queue.preparePendingMessageForDispatch(id, first, PendingUserMessageDisplayCopy.ENGLISH)
        assertTrue(prepared.previewText(maxChars = 1_000).contains("second"))
        assertEquals(listOf("later"), fixture.store.load(id).map { it.id })
        assertEquals(listOf("later"), fixture.queue.flow(id).value.map { it.id })
    }

    @Test
    fun restoredQueueUsesTheSameFlowAndNeverWritesTheOldPendingSnapshotBack() = runBlocking {
        val fixture = queue()
        val id = Uuid.random()
        fixture.queue.enqueuePendingUserMessage(id, pending("old"))
        fixture.queue.persistCurrentPendingMessagesNow(id)
        val flow = fixture.queue.flow(id)
        fixture.gate.withRestore { fixture.gate.markDataCommitted() }
        fixture.queue.persistCurrentPendingMessagesNow(id)
        assertSame(flow, fixture.queue.flow(id))
        assertTrue(flow.value.isEmpty())
        assertTrue(fixture.store.load(id).isEmpty())
    }

    @Test
    fun existingPendingInputLoadsSynchronouslyAndMoveToInputCommitsRemoval() = runBlocking {
        val fixture = queue()
        val id = Uuid.random()
        fixture.store.persistBlocking(id, listOf(pending("retained")))
        assertEquals(listOf("retained"), fixture.queue.flow(id).value.map { it.id })
        assertEquals(listOf(UIMessagePart.Text("retained")), fixture.queue.takePendingMessageForInput(id))
        assertTrue(fixture.store.load(id).isEmpty())
    }

    private fun queue(): Fixture {
        val root = directory.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = root
        }
        val store = PendingMessageStore(context, Json)
        val gate = SyncRestoreWriteGate()
        val queue = PendingMessageQueue(scope, store, gate).also(queues::add)
        return Fixture(queue, store, gate)
    }

    private data class Fixture(val queue: PendingMessageQueue, val store: PendingMessageStore, val gate: SyncRestoreWriteGate)

    private fun pending(id: String, mode: PendingUserMessageMode = PendingUserMessageMode.FOLLOWUP) = PendingUserMessage(
        id = id,
        parts = listOf(UIMessagePart.Text(id)),
        mode = mode,
    )
}
