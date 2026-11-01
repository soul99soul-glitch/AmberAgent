package app.amber.feature.ui.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPerfCommandBridgeTest {
    @Test
    fun fixtureIdentityAndInteractionAckDoNotDependOnTraceFlag() {
        ChatPerfCommandBridge.prepareFixtureRun("WID-1")
        assertFalse(ChatPerfCommandBridge.beginInteraction("RID", "swipe"))
        assertTrue(ChatPerfCommandBridge.beginInteraction("WID-1", "swipe"))
        assertFalse(ChatPerfCommandBridge.reportInteraction("input-tap", "input-focus"))
        assertTrue(ChatPerfCommandBridge.reportInteraction("swipe", "lazy-scroll"))
        assertFalse("second response for the same request is ignored",
            ChatPerfCommandBridge.reportInteraction("swipe", "lazy-scroll"))

        ChatPerfCommandBridge.prepareFixtureRun("WID-2")
        assertFalse("old run response cannot satisfy a new run",
            ChatPerfCommandBridge.reportInteraction("swipe", "lazy-scroll"))
        assertTrue(ChatPerfCommandBridge.beginInteraction("WID-2", "input-tap"))
        assertTrue(ChatPerfCommandBridge.reportInteraction("input-tap", "input-focus"))
    }
}
