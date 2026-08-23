package app.amber.feature.runtime

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-05 — Run Ownership & scoped cancellation.
 *
 * Cancellation key = assistantId + conversationId + runId. Covers the plan's
 * test list: two concurrent conversations, stopping one leaves the other
 * untouched; a cancel whose runId does not match its conversationId is
 * rejected; stale/finished runs cancel nothing.
 */
class RunOwnershipRegistryTest {

    @Test
    fun cancelOneConversationRunLeavesOtherRunUntouched() {
        val registry = RunOwnershipRegistry()
        val jobA = Job()
        val jobB = Job()
        registry.register(assistantId = "assistant-1", conversationId = "conversation-A", runId = "run-A", job = jobA)
        registry.register(assistantId = "assistant-1", conversationId = "conversation-B", runId = "run-B", job = jobB)

        assertTrue(registry.cancel(runId = "run-A", conversationId = "conversation-A"))
        assertTrue("run A job must be cancelled", jobA.isCancelled)
        assertFalse("run B job must be untouched", jobB.isCancelled)
    }

    @Test
    fun cancelWithMismatchedConversationIdIsRejected() {
        val registry = RunOwnershipRegistry()
        val job = Job()
        registry.register(assistantId = "assistant-1", conversationId = "conversation-A", runId = "run-A", job = job)

        // Notification deep link with a runId that does not belong to the
        // conversation it claims → cancel is invalid, job stays alive.
        assertFalse(registry.cancel(runId = "run-A", conversationId = "conversation-B"))
        assertFalse(job.isCancelled)
    }

    @Test
    fun cancelUnknownOrFinishedRunIdIsRejected() {
        val registry = RunOwnershipRegistry()
        assertFalse(registry.cancel(runId = "missing-run", conversationId = "conversation-A"))

        val job = Job()
        registry.register(assistantId = "assistant-1", conversationId = "conversation-A", runId = "run-A", job = job)
        registry.unregister("run-A") // run finished → ownership released

        // A stale notification carrying the finished runId cancels nothing.
        assertFalse(registry.cancel(runId = "run-A", conversationId = "conversation-A"))
        assertFalse(job.isCancelled)
    }

    @Test
    fun registerRefusesDifferentOwnerForSameRunId() {
        val registry = RunOwnershipRegistry()
        val job = Job()
        assertTrue(registry.register("assistant-1", "conversation-A", "run-A", job))

        // Same runId resuming under a different owner must be refused.
        val otherJob = Job()
        assertFalse(registry.register("assistant-2", "conversation-B", "run-A", otherJob))
        assertFalse("the would-be stolen job must not be replaced or cancelled", otherJob.isCancelled)
        assertFalse(job.isCancelled)
    }

    @Test
    fun resumeReRegistersSameOwner() {
        val registry = RunOwnershipRegistry()
        val firstJob = Job()
        assertTrue(registry.register("assistant-1", "conversation-A", "run-A", firstJob))
        registry.unregister("run-A")

        // WAITING_USER resume re-registers the same run under the same owner.
        val resumedJob = Job()
        assertTrue(registry.register("assistant-1", "conversation-A", "run-A", resumedJob))
        assertTrue(registry.cancel("run-A", "conversation-A"))
        assertTrue(resumedJob.isCancelled)
    }
}
