package app.amber.core.service

import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.RoomRunTerminalStore
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.terminalForFlowEnd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGenerationFinalizationTest : DurableRuntimeTestBase() {
    @Test
    fun cancelledGenerationStillPersistsItsTerminal() = runBlocking {
        runTerminalStore.begin("run_cancelled", "conversation", null)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            runCatching {
                flow<Unit> {
                    started.complete(Unit)
                    awaitCancellation()
                }.onCompletion { cause ->
                    finalizeChatGeneration {
                        val (state, reason) = terminalForFlowEnd(cause, null)
                        runTerminalStore.finish("run_cancelled", state, reason)
                    }
                }.collect()
            }
        }
        started.await()
        job.cancelAndJoin()

        val persisted = runTerminalStore.get("run_cancelled")!!
        assertEquals(RunTerminalState.CANCELLED, persisted.state)
        assertNotNull(persisted.finishedAtMs)
    }

    @Test
    fun cancellationCleanupStillRejectsPreRestoreWrites() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val store = RoomRunTerminalStore(database.runTerminalDao(), restoreWriteGate = gate)
        store.begin("run_restored", "conversation", null)
        val started = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        val job = launch(SyncRestoreWriteEpoch(gate.currentEpoch())) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                failure = runCatching {
                    finalizeChatGeneration {
                        store.finish("run_restored", RunTerminalState.CANCELLED, PauseReason.USER_STOP)
                    }
                }.exceptionOrNull()
            }
        }
        started.await()
        gate.withRestore { }
        job.cancelAndJoin()

        assertTrue(failure is SyncRestoreWriteRejectedException)
        assertEquals(RunTerminalState.RUNNING, store.get("run_restored")!!.state)
    }
}
