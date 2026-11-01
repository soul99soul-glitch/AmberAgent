package app.amber.feature.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPerfMarkerStateTest {
    @Test
    fun disabledStateRecordsNothing() {
        var now = 0L
        val state = ChatPerfMarkerState(enabled = false) { now }
        state.reset()
        now = 10
        state.mark("EntryRequested")
        assertFalse(state.has("EntryRequested"))
        assertTrue(state.relativeMs().isEmpty())
    }

    @Test
    fun phasesAreMonotonicDeduplicatedAndMissingStayMissing() {
        var now = 1_000_000L
        val state = ChatPerfMarkerState(enabled = true) { now }
        state.reset()
        state.mark("EntryRequested")
        now += 2_000_000L
        state.mark("DataReady")
        state.mark("DataReady")
        now += 3_000_000L
        state.mark("FirstReadableDraw")

        assertEquals(
            listOf("EntryRequested", "DataReady", "FirstReadableDraw"),
            state.names(),
        )
        assertEquals(2.0, state.relativeMs().getValue("DataReady"), 0.001)
        assertEquals(5.0, state.relativeMs().getValue("FirstReadableDraw"), 0.001)
        assertFalse(state.has("FirstInteractive"))
    }

    @Test
    fun resetIsolatesAReentryRun() {
        var now = 0L
        val state = ChatPerfMarkerState(enabled = true) { now }
        state.reset()
        state.mark("EntryRequested")
        now = 5_000_000L
        state.mark("DataReady")
        now = 7_000_000L
        state.reset()
        state.mark("EntryRequested")
        now = 8_000_000L
        state.mark("DataReady")

        assertEquals(1.0, state.relativeMs().getValue("DataReady"), 0.001)
        assertEquals(0.0, state.relativeMs().getValue("EntryRequested"), 0.001)
    }
}
