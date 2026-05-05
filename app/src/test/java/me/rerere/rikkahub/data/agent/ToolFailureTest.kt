package me.rerere.rikkahub.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFailureTest {
    @Test
    fun toolFailureJsonDoesNotExposeStackFrames() {
        val error = IllegalStateException("boom")

        val payload = error.toAgentToolFailureJson()

        assertTrue(payload.contains("\"status\":\"failed\""))
        assertTrue(payload.contains("\"message\":\"boom\""))
        assertFalse(payload.contains("at me.rerere."))
        assertFalse(payload.contains("at java."))
    }
}
