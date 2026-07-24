package app.amber.ai.provider.providers

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTerminationGuardTest {

    @Test
    fun `chat clean EOF requires DONE marker`() {
        val guard = StreamTerminationGuard(StreamProtocol.OPENAI_CHAT)

        assertTrue(guard.cleanEofCause() is IncompleteStreamException)
        guard.observe(type = null, data = "[DONE]")
        assertNull(guard.cleanEofCause())
    }

    @Test
    fun `responses clean EOF requires completed or incomplete event`() {
        val guard = StreamTerminationGuard(StreamProtocol.OPENAI_RESPONSES)

        guard.observe(type = null, data = "[DONE]")
        assertTrue(guard.cleanEofCause() is IncompleteStreamException)
        guard.observe(type = "response.completed", data = "{}")
        assertNull(guard.cleanEofCause())
    }

    @Test
    fun `claude clean EOF requires message stop event`() {
        val guard = StreamTerminationGuard(StreamProtocol.CLAUDE)

        guard.observe(type = null, data = "[DONE]")
        assertTrue(guard.cleanEofCause() is IncompleteStreamException)
        guard.observe(type = "message_stop", data = "{}")
        assertNull(guard.cleanEofCause())
    }
}
