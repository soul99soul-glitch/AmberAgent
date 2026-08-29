package app.amber.core.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
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
    fun stopSemanticsPreserveQueueWithoutDowngrade() {
        // P1-06: Stop 后会话进入 idle，队列原样保留 —— STEER 不再降级为
        // FOLLOWUP，内容不丢；下一步由用户显式“继续队列”或“移回输入框”决定。
        val session = session()
        session.enqueuePendingUserMessage(pending("steer", PendingUserMessageMode.STEER))
        session.enqueuePendingUserMessage(pending("followup", PendingUserMessageMode.FOLLOWUP))
        session.enqueuePendingUserMessage(pending("attachment", PendingUserMessageMode.FOLLOWUP))

        assertEquals(
            listOf(PendingUserMessageMode.STEER, PendingUserMessageMode.FOLLOWUP, PendingUserMessageMode.FOLLOWUP),
            session.pendingUserMessages.value.map { it.mode },
        )
        assertEquals(
            listOf("steer", "followup", "attachment"),
            session.pendingUserMessages.value.map { it.id },
        )
        // 队列非空本身不会启动生成（kernel 调度只由显式
        // resumePendingQueue / 新消息发送触发）。
        assertFalse(session.isGenerating)
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
