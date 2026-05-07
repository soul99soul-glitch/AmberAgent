package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionQueueTest {
    @Test
    fun pendingMessagesAreDequeuedInFifoOrder() {
        val persistedSizes = mutableListOf<Int>()
        val session = session { _, messages -> persistedSizes += messages.size }

        assertTrue(session.enqueuePendingUserMessage(pending("first")))
        assertTrue(session.enqueuePendingUserMessage(pending("second")))

        assertEquals("first", session.dequeueNextPendingUserMessage()?.id)
        assertEquals("second", session.dequeueNextPendingUserMessage()?.id)
        assertNull(session.dequeueNextPendingUserMessage())
        assertEquals(listOf(1, 2, 1, 0), persistedSizes)
    }

    @Test
    fun steerMessagesAreInsertedBeforeFollowupMessages() {
        val session = session()
        session.enqueuePendingUserMessage(pending("steer-1", PendingUserMessageMode.STEER))
        session.enqueuePendingUserMessage(pending("steer-2", PendingUserMessageMode.STEER))
        session.enqueuePendingUserMessage(pending("followup", PendingUserMessageMode.FOLLOWUP))
        session.enqueuePendingUserMessage(pending("steer-3", PendingUserMessageMode.STEER))

        val consumed = session.dequeueSteerPendingUserMessages()

        assertEquals(listOf("steer-1", "steer-2", "steer-3"), consumed.map { it.id })
        assertEquals(listOf("followup"), session.pendingUserMessages.value.map { it.id })
    }

    @Test
    fun queueLimitAndCancelAreStable() {
        val session = session()
        repeat(MAX_PENDING_USER_MESSAGES) { index ->
            assertTrue(session.enqueuePendingUserMessage(pending("message-$index")))
        }

        assertFalse(session.enqueuePendingUserMessage(pending("overflow")))
        assertTrue(session.cancelPendingUserMessage("message-3"))
        assertFalse(session.cancelPendingUserMessage("missing"))
        assertEquals(MAX_PENDING_USER_MESSAGES - 1, session.pendingUserMessages.value.size)
    }

    @Test
    fun movePendingMessageReordersQueue() {
        val session = session()
        session.enqueuePendingUserMessage(pending("first"))
        session.enqueuePendingUserMessage(pending("second"))
        session.enqueuePendingUserMessage(pending("third"))

        assertTrue(session.movePendingUserMessage("third", -2))
        assertEquals(listOf("third", "first", "second"), session.pendingUserMessages.value.map { it.id })
        assertFalse(session.movePendingUserMessage("third", -1))
        assertFalse(session.movePendingUserMessage("missing", 1))
    }

    @Test
    fun steerMessagesCanDowngradeToFollowup() {
        val session = session()
        session.enqueuePendingUserMessage(pending("steer", PendingUserMessageMode.STEER))
        session.enqueuePendingUserMessage(pending("followup"))

        assertTrue(session.convertPendingSteerMessagesToFollowup())
        assertEquals(
            listOf(PendingUserMessageMode.FOLLOWUP, PendingUserMessageMode.FOLLOWUP),
            session.pendingUserMessages.value.map { it.mode },
        )
        assertFalse(session.convertPendingSteerMessagesToFollowup())
    }

    @Test
    fun collectedPendingMessagesKeepStableMarkers() {
        val collected = buildCollectedPendingUserMessage(
            listOf(
                pending("first", PendingUserMessageMode.COLLECT),
                pending("second", PendingUserMessageMode.COLLECT),
            )
        )

        assertEquals(PendingUserMessageMode.FOLLOWUP, collected.mode)
        val text = collected.previewText(maxChars = 1_000)
        assertTrue(text.contains("Queued #1"))
        assertTrue(text.contains("first"))
        assertTrue(text.contains("Queued #2"))
        assertTrue(text.contains("second"))
    }

    private fun session(
        onPendingMessagesChanged: (Uuid, List<PendingUserMessage>) -> Unit = { _, _ -> },
    ) = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(Uuid.random()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onIdle = {},
        onPendingMessagesChanged = onPendingMessagesChanged,
    )

    private fun pending(
        id: String,
        mode: PendingUserMessageMode = PendingUserMessageMode.FOLLOWUP,
    ) = PendingUserMessage(
        id = id,
        parts = listOf(UIMessagePart.Text(id)),
        mode = mode,
    )
}
