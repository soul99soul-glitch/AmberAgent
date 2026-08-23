package app.amber.core.service

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceNotificationTest {
    private val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")

    @Test
    fun `completion notification id is stable and run scoped`() {
        val first = generationDoneNotificationId(conversationId, "run-1")
        assertEquals(first, generationDoneNotificationId(conversationId, "run-1"))
        assertNotEquals(first, generationDoneNotificationId(conversationId, "run-2"))
        assertNotEquals(first, generationDoneNotificationId(Uuid.parse("22222222-2222-2222-2222-222222222222"), "run-1"))
        assertTrue(first > 0)
    }

    @Test
    fun `pending intent request code is also run scoped`() {
        val first = generationNotificationPendingIntentRequestCode(conversationId, "run-1")
        assertEquals(first, generationNotificationPendingIntentRequestCode(conversationId, "run-1"))
        assertNotEquals(first, generationNotificationPendingIntentRequestCode(conversationId, "run-2"))
    }
}
