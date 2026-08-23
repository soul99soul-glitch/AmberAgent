package app.amber.agent

import app.amber.feature.runtime.RunTerminal
import app.amber.feature.runtime.RunTerminalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDeepLinkTest {
    private val conversationId = "11111111-1111-1111-1111-111111111111"
    private val runId = "run-1"

    @Test
    fun `parser normalizes exact conversation identity and preserves run`() {
        assertEquals(
            NotificationDeepLink(conversationId, runId),
            notificationDeepLinkFrom("  $conversationId  ", " $runId "),
        )
    }

    @Test
    fun `malformed notification identity cannot fall back to a chat target`() {
        assertNull(notificationDeepLinkFrom("not-a-uuid", runId))
        assertNull(notificationDeepLinkFrom(null, runId))
    }

    @Test
    fun `run ownership rejects missing mismatched and terminal runs`() {
        val link = NotificationDeepLink(conversationId, runId)
        assertFalse(notificationRunIsOwned(link, null))
        assertFalse(
            notificationRunIsOwned(
                link,
                terminal(conversationId = "22222222-2222-2222-2222-222222222222"),
            ),
        )
        assertFalse(notificationRunIsOwned(link, terminal(conversationId, RunTerminalState.COMPLETED)))
        assertTrue(notificationRunIsOwned(link, terminal(conversationId, RunTerminalState.WAITING_USER)))
    }

    private fun terminal(
        conversationId: String,
        state: RunTerminalState = RunTerminalState.RUNNING,
    ) = RunTerminal(
        runId = runId,
        conversationId = conversationId,
        assistantId = null,
        state = state,
        pauseReason = null,
        startedAtMs = 1L,
        updatedAtMs = 2L,
        finishedAtMs = null,
    )
}
