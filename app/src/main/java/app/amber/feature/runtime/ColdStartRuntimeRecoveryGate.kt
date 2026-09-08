package app.amber.feature.runtime

import kotlinx.coroutines.CompletableDeferred

/**
 * One-shot barrier between cold-start recovery and new durable agent runs.
 *
 * A run may append its RUNNING row only after recovery has finished marking
 * the previous process's unfinished rows. A recovery failure is deliberately
 * visible to waiters so a new run cannot race ahead of incomplete recovery.
 */
class ColdStartRuntimeRecoveryGate {
    private val ready = CompletableDeferred<Unit>()

    suspend fun awaitReady() {
        ready.await()
    }

    fun complete() {
        ready.complete(Unit)
    }

    fun fail(error: Throwable) {
        ready.completeExceptionally(error)
    }
}
