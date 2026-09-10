package app.amber.agent.data.sync

import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRestoreWriteGateTest {

    @Test
    fun `writer carrying pre-restore epoch is dropped after commit`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val staleEpoch = gate.currentEpoch()
        var writes = 0

        gate.withRestore {
            gate.markDataCommitted()
            writes += 1
        }

        assertNull(gate.withWriter(staleEpoch) { writes += 1 })
        assertEquals(1, writes)
    }

    @Test
    fun `new writer waits for restore and lifecycle reports data commit`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val outcomes = mutableListOf<Pair<Boolean, Boolean>>()
        gate.addRestoreLifecycleListener(
            onStarted = { assertTrue(gate.isRestoring()) },
            onFinished = { succeeded, dataCommitted -> outcomes += succeeded to dataCommitted },
        )

        gate.withRestore { gate.markDataCommitted() }
        var writes = 0
        gate.withWriter { writes += 1 }

        assertEquals(1, writes)
        assertEquals(listOf(true to true), outcomes)
    }

    @Test
    fun `new writer is admitted only after restore releases its lock`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0

        supervisorScope {
            val restore = async {
                gate.withRestore {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val writer = async { gate.withWriter { writes += 1 } }
            delay(10)
            assertTrue(!writer.isCompleted)
            release.complete(Unit)
            restore.await()
            writer.await()
        }

        assertEquals(1, writes)
    }

    @Test
    fun `orCancel preserves a legitimate nullable writer result`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val result: String? = gate.withCurrentWriterOrCancel { null }
        assertNull(result)
    }

    @Test
    fun `failed post-commit restore is reported as partial and stale writer is rejected`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val outcomes = mutableListOf<Pair<Boolean, Boolean>>()
        gate.addRestoreLifecycleListener(
            onStarted = {},
            onFinished = { succeeded, dataCommitted -> outcomes += succeeded to dataCommitted },
        )

        runCatching {
            gate.withRestore {
                gate.markDataCommitted()
                error("settings write failed")
            }
        }

        assertEquals(listOf(false to true), outcomes)
    }

    @Test
    fun `pre-restore board owner is rejected after model work completes`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val ownerEpoch = gate.currentEpoch()
        val modelReturned = CompletableDeferred<Unit>()
        val persistNow = CompletableDeferred<Unit>()
        var persisted = false

        supervisorScope {
            val owner = async {
                withContext(SyncRestoreWriteEpoch(ownerEpoch)) {
                    modelReturned.complete(Unit)
                    persistNow.await()
                    gate.withCurrentWriterOrCancel { persisted = true }
                }
            }

            modelReturned.await()
            gate.withRestore { gate.markDataCommitted() }
            assertEquals(2L, gate.currentEpoch())

            persistNow.complete(Unit)
            val failure = runCatching { owner.await() }.exceptionOrNull()
            assertTrue(failure is SyncRestoreWriteRejectedException)
            assertEquals(false, persisted)
        }
    }
}
