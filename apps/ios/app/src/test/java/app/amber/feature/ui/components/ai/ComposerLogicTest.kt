package app.amber.feature.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Targeted tests for the composer logic (design §6.2 InputBar / criterion 5):
 * the `+`→capsule open/close state machine and the "send activates on draft" behavior.
 * UI animation/rendering is verified separately on a device; this covers the pure logic.
 */
class ComposerLogicTest {

    @Test fun plus_opensCapsule_thenCollapses() {
        // tap + while collapsed → capsule opens (Files)
        assertEquals(ExpandState.Files, nextExpandState(ExpandState.Collapsed, ExpandState.Files))
        // tap + again while open → collapses (× back to +)
        assertEquals(ExpandState.Collapsed, nextExpandState(ExpandState.Files, ExpandState.Files))
    }

    @Test fun send_dimWhenEmpty() {
        assertFalse(composerSendEnabled(isEmpty = true, loading = false))
    }

    @Test fun send_litOnDraft() {
        assertTrue(composerSendEnabled(isEmpty = false, loading = false))
    }

    @Test fun send_enabledWhileStreamingForStop() {
        assertTrue(composerSendEnabled(isEmpty = true, loading = true))
    }
}
