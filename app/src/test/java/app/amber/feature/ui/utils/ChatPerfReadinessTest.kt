package app.amber.feature.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPerfReadinessTest {
    private fun sample(
        alpha: Float = 0.95f,
        visible: Boolean = true,
        formatted: Boolean = true,
        mediaSettled: Boolean = true,
        draw: Boolean = true,
    ) = ChatPerfReadinessSample(
        draw = draw,
        alpha = alpha,
        visible = visible,
        formatted = formatted,
        mediaSettled = mediaSettled,
    )

    private fun messageGate() = ChatPerfReadinessGate().apply {
        reset(ChatPerfTargetKind.MESSAGE)
    }

    @Test
    fun alphaZeroAndBelowThresholdKeepContentOnly() {
        val gate = messageGate()
        assertEquals(
            ChatPerfReadinessDecision.CONTENT,
            gate.onSample(sample(alpha = 0f)),
        )
        assertEquals(
            ChatPerfReadinessDecision.NONE,
            gate.onSample(sample(alpha = 0.94f)),
        )
        // Content was already recorded; 0.95 is the first readable sample.
        assertEquals(
            ChatPerfReadinessDecision.READABLE,
            gate.onSample(sample(alpha = 0.95f)),
        )
    }

    @Test
    fun placeholderCannotRecordEitherMarkerUntilFormatted() {
        val gate = messageGate()
        assertEquals(
            ChatPerfReadinessDecision.NONE,
            gate.onSample(sample(formatted = false)),
        )
        assertEquals(
            ChatPerfReadinessDecision.CONTENT_AND_READABLE,
            gate.onSample(sample(formatted = true)),
        )
        // Repeat draws after the decision are no-ops (they must not re-trace).
        assertEquals(
            ChatPerfReadinessDecision.NONE,
            gate.onSample(sample()),
        )
    }

    @Test
    fun offscreenTargetCannotRecordEitherMarker() {
        val gate = messageGate()
        assertEquals(
            ChatPerfReadinessDecision.NONE,
            gate.onSample(sample(visible = false)),
        )
        assertEquals(
            ChatPerfReadinessDecision.CONTENT_AND_READABLE,
            gate.onSample(sample(visible = true)),
        )
    }

    @Test
    fun pendingImageBlocksReadableButNotContent() {
        val gate = messageGate()
        assertEquals(
            ChatPerfReadinessDecision.CONTENT,
            gate.onSample(sample(mediaSettled = false)),
        )
        assertEquals(
            ChatPerfReadinessDecision.READABLE,
            gate.onSample(sample(mediaSettled = true)),
        )
    }

    @Test
    fun cacheMissPlaceholderThenReadyRecordsOnlyOnReady() {
        val gate = messageGate()
        var decision = ChatPerfReadinessDecision.NONE
        repeat(5) {
            decision = gate.onSample(sample(formatted = false))
            assertEquals(ChatPerfReadinessDecision.NONE, decision)
        }
        assertEquals(
            ChatPerfReadinessDecision.CONTENT_AND_READABLE,
            gate.onSample(sample(formatted = true)),
        )
    }

    @Test
    fun emptyHeroUsesItsOwnContractAndIgnoresMedia() {
        val gate = ChatPerfReadinessGate().apply {
            reset(ChatPerfTargetKind.EMPTY_HERO)
        }
        assertEquals(
            ChatPerfReadinessDecision.CONTENT,
            gate.onSample(sample(alpha = 0.9f, mediaSettled = false)),
        )
        assertEquals(
            ChatPerfReadinessDecision.NONE,
            gate.onSample(sample(alpha = 0.94f, mediaSettled = false)),
        )
        assertEquals(
            ChatPerfReadinessDecision.READABLE,
            gate.onSample(sample(alpha = 0.95f, mediaSettled = false)),
        )
    }

    @Test
    fun newTargetGenerationCannotBeFooledByThePreviousOne() {
        val gate = messageGate()
        assertEquals(
            ChatPerfReadinessDecision.CONTENT_AND_READABLE,
            gate.onSample(sample()),
        )
        gate.reset(ChatPerfTargetKind.MESSAGE)
        assertEquals(
            ChatPerfReadinessDecision.CONTENT_AND_READABLE,
            gate.onSample(sample()),
        )
    }

    @Test
    fun trackerRequiresAFormattedDocumentAndSettledImages() {
        val tracker = ChatPerfReadinessTracker()
        assertFalse("no probe must not fake readiness", tracker.formatted("node"))
        assertTrue("no images means media is settled", tracker.mediaSettled("node"))

        tracker.markMarkdown("node", 1, true)
        tracker.markMedia("node", 11, true)
        assertFalse(tracker.formatted("node"))
        assertFalse(tracker.mediaSettled("node"))

        tracker.markMarkdown("node", 2, false)
        tracker.markMedia("node", 12, false)
        assertFalse("one pending document blocks readiness", tracker.formatted("node"))
        assertFalse("one pending image blocks media readiness", tracker.mediaSettled("node"))

        tracker.markMarkdown("node", 1, false)
        assertTrue(tracker.formatted("node"))
        tracker.markMedia("node", 11, false)
        assertTrue(tracker.mediaSettled("node"))

        // A different message cannot satisfy the target's contract.
        tracker.clear()
        tracker.markMarkdown("other", 3, false)
        tracker.markMedia("other", 13, false)
        assertFalse(tracker.formatted("node"))

        tracker.clear()
        assertFalse(tracker.formatted("node"))
        assertTrue(tracker.mediaSettled("node"))
    }
}

class ChatPerfVisibilityGateTest {
    @Test
    fun staleVisibilityFromBeforeTargetInitCannotFlipNewTarget() {
        val gate = ChatPerfVisibilityGate()
        val oldGeneration = gate.beginTarget()
        assertTrue(gate.report(oldGeneration, true))
        val newGeneration = gate.beginTarget()
        assertFalse(gate.visible)
        assertFalse("old generation report must be ignored",
            gate.report(oldGeneration, true))
        assertFalse(gate.visible)
        assertTrue(gate.report(newGeneration, true))
        assertTrue(gate.visible)
    }

    @Test
    fun repeatedVisibilityDoesNotInvalidateAgain() {
        val gate = ChatPerfVisibilityGate()
        val generation = gate.beginTarget()
        assertTrue(gate.report(generation, true))
        assertFalse(gate.report(generation, true))
    }

    @Test
    fun removedPendingTokenCannotBlockReadinessForever() {
        val tracker = ChatPerfReadinessTracker()
        tracker.markMarkdown("node", 7, true)
        tracker.markMedia("node", 9, true)
        assertFalse(tracker.formatted("node"))
        assertFalse(tracker.mediaSettled("node"))
        assertTrue(tracker.removeMarkdown("node", 7))
        assertTrue(tracker.removeMedia("node", 9))
        assertFalse("removing the last formatted token must not fake readiness",
            tracker.formatted("node"))
        assertTrue("removing the last media token means no composed media",
            tracker.mediaSettled("node"))
        tracker.markMarkdown("node", 8, false)
        assertTrue(tracker.formatted("node"))
    }
}
