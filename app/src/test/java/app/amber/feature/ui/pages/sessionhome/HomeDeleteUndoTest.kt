package app.amber.feature.ui.pages.sessionhome

import app.amber.core.model.Conversation
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class HomeDeleteUndoTest {
    private fun conversation() = Conversation.ofId(Uuid.random())
    private fun TestScope.owner(
        calls: MutableList<Uuid>,
        exists: suspend (Uuid) -> Boolean = { true },
        delete: suspend (Conversation) -> Unit = { calls += it.id },
        scope: CoroutineScope = backgroundScope,
    ) = HomeDeleteUndo(scope, { testScheduler.currentTime }, delete, exists)

    @Test fun `undo just before deadline never calls service and allows repeat swipe`() = runTest {
        val calls = mutableListOf<Uuid>()
        val owner = owner(calls)
        val row = conversation()
        owner.enqueue(row)
        advanceTimeBy(4_999)
        assertEquals(1, owner.state.value.remainingSeconds)
        assertTrue(owner.undo())
        assertTrue(owner.state.value.hiddenIds.isEmpty())
        assertEquals(1L, owner.state.value.rowVersions[row.id])
        advanceTimeBy(10_000)
        assertTrue(calls.isEmpty())
        owner.enqueue(row)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(row.id), calls)
    }

    @Test fun `duplicate pending committing and completed IDs submit once and never extend deadline`() = runTest {
        val calls = mutableListOf<Uuid>()
        val owner = owner(calls)
        val row = conversation()
        owner.enqueue(row)
        advanceTimeBy(3_000)
        owner.enqueue(row)
        assertEquals(5_000L, owner.state.value.deadlineMillis)
        advanceTimeBy(2_000); runCurrent()
        owner.enqueue(row)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(row.id), calls)
        assertTrue(row.id in owner.state.value.hiddenIds)
    }

    @Test fun `valid second delete restarts whole batch and old timer cannot commit it`() = runTest {
        val calls = mutableListOf<Uuid>()
        val owner = owner(calls)
        val first = conversation(); val second = conversation()
        owner.enqueue(first)
        advanceTimeBy(4_000)
        owner.enqueue(second)
        advanceTimeBy(1_001); runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(setOf(first.id, second.id), owner.state.value.pendingIds)
        assertTrue(owner.undo())
        advanceTimeBy(10_000); runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(owner.state.value.hiddenIds.isEmpty())
    }

    @Test fun `expired multi row batch commits each ID once without UI collectors`() = runTest {
        val calls = mutableListOf<Uuid>()
        val owner = owner(calls)
        val rows = List(3) { conversation() }
        rows.forEach(owner::enqueue)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(rows.map { it.id }, calls)
        assertFalse(owner.undo())
        assertTrue(owner.state.value.pendingIds.isEmpty())
        assertEquals(rows.map { it.id }.toSet(), owner.state.value.retainedIds)
    }

    @Test fun `undo at deadline rejects even when timer has not run`() = runTest {
        var clock = 0L
        val calls = mutableListOf<Uuid>()
        val owner = HomeDeleteUndo(backgroundScope, { clock }, { calls += it.id }, { true })
        val row = conversation()
        owner.enqueue(row)
        clock = 5_000
        assertFalse(owner.undo())
        assertEquals(setOf(row.id), owner.state.value.committingIds)
        assertTrue(owner.state.value.pendingIds.isEmpty())
        runCurrent()
        assertEquals(listOf(row.id), calls)
    }

    @Test fun `new delete at elapsed boundary cannot extend expired batch`() = runTest {
        var clock = 0L
        val calls = mutableListOf<Uuid>()
        val owner = HomeDeleteUndo(backgroundScope, { clock }, { calls += it.id }, { true })
        val first = conversation(); val second = conversation()
        owner.enqueue(first)
        clock = 5_000
        owner.enqueue(second)
        assertEquals(setOf(first.id), owner.state.value.committingIds)
        assertEquals(setOf(second.id), owner.state.value.pendingIds)
        assertTrue(owner.undo())
        runCurrent()
        assertEquals(listOf(first.id), calls)
    }

    @Test fun `undo during in flight commit only restores a newer pending batch`() = runTest {
        val calls = mutableListOf<Uuid>()
        val gate = CompletableDeferred<Unit>()
        val owner = owner(calls, delete = { calls += it.id; gate.await() })
        val first = conversation(); val second = conversation()
        owner.enqueue(first)
        advanceTimeBy(5_000); runCurrent()
        owner.enqueue(first)
        owner.enqueue(second)
        assertTrue(owner.undo())
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(first.id), calls)
        assertEquals(setOf(first.id), owner.state.value.hiddenIds)
    }

    @Test fun `durable failure restores only present rows and continues batch`() = runTest {
        val calls = mutableListOf<Uuid>()
        val bad = conversation(); val good = conversation()
        val owner = owner(calls, delete = {
            calls += it.id
            if (it.id == bad.id) error("database write failed")
        })
        owner.enqueue(bad); owner.enqueue(good)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(bad.id, good.id), calls)
        assertEquals(setOf(good.id), owner.state.value.hiddenIds)
        assertEquals(1L, owner.state.value.rowVersions[bad.id])
        assertEquals(HomeDeleteFailureKind.Restored, owner.failure.value?.kind)
        owner.enqueue(bad)
        assertEquals(setOf(bad.id), owner.state.value.pendingIds)
    }

    @Test fun `cleanup failure after durable deletion must not restore or resubmit`() = runTest {
        val calls = mutableListOf<Uuid>()
        val row = conversation()
        val owner = owner(calls, exists = { false }, delete = { calls += it.id; error("cleanup failed") })
        owner.enqueue(row)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(HomeDeleteFailureKind.AlreadyDeleted, owner.failure.value?.kind)
        assertEquals(setOf(row.id), owner.state.value.hiddenIds)
        owner.enqueue(row)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(row.id), calls)
    }

    @Test fun `failed existence check keeps uncertain row hidden and reports uncertainty`() = runTest {
        val owner = owner(mutableListOf(), exists = { error("offline") }, delete = { error("uncertain") })
        val row = conversation()
        owner.enqueue(row)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(HomeDeleteFailureKind.Unverified, owner.failure.value?.kind)
        assertTrue(row.id in owner.state.value.hiddenIds)
        val sequence = owner.failure.value!!.sequence
        owner.acknowledgeFailure(sequence - 1)
        assertNotNull(owner.failure.value)
        owner.acknowledgeFailure(sequence)
        assertNull(owner.failure.value)
    }

    @Test fun `owner cancellation before deadline leaves database untouched`() = runTest {
        val calls = mutableListOf<Uuid>()
        val job = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val owner = owner(calls, scope = scope)
        owner.enqueue(conversation())
        advanceTimeBy(4_999)
        scope.cancel()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(calls.isEmpty())
    }

    @Test fun `owner cancellation lets non cancellable item finish but never starts remaining items`() = runTest {
        val calls = mutableListOf<Uuid>()
        val job = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val owner = owner(calls, scope = scope, delete = {
            withContext(NonCancellable) { calls += it.id; delay(1_000) }
        })
        val first = conversation(); val second = conversation()
        owner.enqueue(first); owner.enqueue(second)
        advanceTimeBy(5_000); runCurrent()
        scope.cancel()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(first.id), calls)
    }
}
