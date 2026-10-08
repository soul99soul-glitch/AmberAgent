package app.amber.core.service

import app.amber.core.model.Conversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class NotificationApprovalDispatchTest {
    @Test
    fun `acceptance waits for persistence then returns while owned generation continues`() = runTest {
        val session = session(backgroundScope)
        val previous = backgroundScope.launch { awaitCancellation() }
        session.setJob(previous)
        val persistence = CompletableDeferred<Unit>()
        var ownerInstalledBeforeDecision = false
        var continuationCancelled = false
        val acceptance = async {
            session.dispatchNotificationApproval(backgroundScope, onError = { throw it }) { persisted ->
                ownerInstalledBeforeDecision = session.getJob() == currentCoroutineContext()[Job]
                persistence.await()
                persisted()
                try {
                    awaitCancellation()
                } finally {
                    continuationCancelled = true
                }
            }
        }
        runCurrent()
        assertTrue(previous.isCancelled)
        assertTrue(ownerInstalledBeforeDecision)
        assertFalse(acceptance.isCompleted)

        persistence.complete(Unit)
        runCurrent()
        assertTrue(acceptance.await())
        assertTrue(session.isGenerating)

        // Same path as the conversation-scoped Stop: cancel the registered owner.
        session.getJob()!!.cancel()
        runCurrent()
        assertTrue(continuationCancelled)
        assertFalse(session.isGenerating)
    }

    @Test
    fun `a later session operation cancels resumed generation without cancelling its acceptance caller`() = runTest {
        val session = session(backgroundScope)
        var continuationCancelled = false
        val acceptance = async {
            session.dispatchNotificationApproval(backgroundScope, onError = { throw it }) { persisted ->
                persisted()
                try {
                    awaitCancellation()
                } finally {
                    continuationCancelled = true
                }
            }
        }
        runCurrent()
        assertTrue(acceptance.await())
        val nextOperation = backgroundScope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        session.setJob(nextOperation)
        nextOperation.start()
        runCurrent()

        assertTrue(continuationCancelled)
        assertFalse(acceptance.isCancelled)
        assertTrue(session.getJob() === nextOperation)
    }

    @Test
    fun `decision failure reports rejection and preserves the actual error`() = runTest {
        val session = session(backgroundScope)
        val errors = mutableListOf<Exception>()
        val failure = IllegalStateException("approval persistence failed")
        val acceptance = async {
            session.dispatchNotificationApproval(backgroundScope, onError = { errors += it }) {
                throw failure
            }
        }
        runCurrent()
        assertFalse(acceptance.await())
        assertTrue(errors.single() === failure)
        assertFalse(session.isGenerating)
    }

    @Test
    fun `generation failure after persistence does not retract an accepted answer`() = runTest {
        val session = session(backgroundScope)
        val generation = CompletableDeferred<Unit>()
        val errors = mutableListOf<Exception>()
        val failure = IllegalStateException("provider failed after approval")
        val acceptance = async {
            session.dispatchNotificationApproval(backgroundScope, onError = { errors += it }) { persisted ->
                persisted()
                generation.await()
                throw failure
            }
        }
        runCurrent()
        assertTrue(acceptance.await())
        assertTrue(session.isGenerating)
        generation.complete(Unit)
        runCurrent()

        assertTrue(acceptance.await())
        assertTrue(errors.single() === failure)
        assertFalse(session.isGenerating)
    }

    @Test
    fun `cancellation before persistence rejects rather than accepting a decision`() = runTest {
        val session = session(backgroundScope)
        val acceptance = async {
            session.dispatchNotificationApproval(backgroundScope, onError = { throw it }) {
                awaitCancellation()
            }
        }
        runCurrent()
        session.getJob()!!.cancel()
        runCurrent()
        assertFalse(acceptance.await())
        assertNull(session.getJob())
    }

    @Test
    fun `cancelled scope before lazy job starts still settles the acceptance`() = runTest {
        val scope = CoroutineScope(SupervisorJob().apply { cancel() } + StandardTestDispatcher(testScheduler))
        val session = session(backgroundScope)
        var decisionRan = false
        val acceptance = async {
            session.dispatchNotificationApproval(scope, onError = { throw it }) {
                decisionRan = true
            }
        }
        runCurrent()
        assertFalse(acceptance.await())
        assertFalse(decisionRan)
    }

    private fun session(scope: CoroutineScope): ConversationSession = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(Uuid.random()),
        scope = scope,
        onIdle = {},
    )
}
