package app.amber.core.sync.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Generation token carried by a background owner while it performs durable
 * work.  A token is intentionally separate from the gate itself so a DAO
 * owner can reject a pre-restore callback without holding a global lock around
 * its network or model work.
 */
class SyncRestoreWriteEpoch(val value: Long) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<SyncRestoreWriteEpoch>

    override val key: CoroutineContext.Key<SyncRestoreWriteEpoch>
        get() = Key
}

/** A pre-restore callback reached a durable writer after its epoch expired. */
class SyncRestoreWriteRejectedException : CancellationException(
    "恢复已开始，丢弃恢复前的后台写入",
)

/**
 * Coordinates a restore with the short durable writes that can be emitted by
 * a running chat session.
 *
 * The mutex is deliberately held only while a writer touches durable state;
 * network generation is never put behind it. A restore raises the epoch before
 * waiting for the current short write to finish. Writers belonging to an older
 * generation therefore get rejected when they reach the mutex after restore,
 * instead of replaying stale in-memory state over the imported snapshot.
 */
class SyncRestoreWriteGate {
    private val restoreMutex = Mutex()
    private val writeMutex = Mutex()
    private val epoch = AtomicLong(0L)
    private val restoreStarted = CopyOnWriteArrayList<() -> Unit>()
    private val restoreFinished = CopyOnWriteArrayList<(succeeded: Boolean, dataCommitted: Boolean) -> Unit>()

    @Volatile
    private var restoring: Boolean = false

    @Volatile
    private var restoreDataCommitted: Boolean = false

    fun currentEpoch(): Long = epoch.get()

    fun isRestoring(): Boolean = restoring

    /**
     * Returns false for an in-flight restore or for a writer carrying an old
     * generation token. A writer without a token represents a new user action;
     * it may wait for the restore and then continue against the new snapshot.
     */
    fun isWriteAllowed(expectedEpoch: Long? = null): Boolean =
        !restoring && (expectedEpoch == null || expectedEpoch == epoch.get())

    /** Register the lifecycle callbacks used by actual background writers. */
    fun addRestoreLifecycleListener(
        onStarted: () -> Unit,
        onFinished: (succeeded: Boolean, dataCommitted: Boolean) -> Unit,
    ): AutoCloseable {
        restoreStarted += onStarted
        restoreFinished += onFinished
        return AutoCloseable {
            restoreStarted -= onStarted
            restoreFinished -= onFinished
        }
    }

    /** Record that the restore's SQLite/file data set has committed. */
    fun markDataCommitted() {
        if (restoring) restoreDataCommitted = true
    }

    /**
     * Runs one restore while excluding durable writers. The epoch is changed
     * both when the restore starts and when it ends, so an old callback cannot
     * become valid merely because the restore failed or was cancelled.
     */
    suspend fun <T> withRestore(block: suspend (restoreEpoch: Long) -> T): T = restoreMutex.withLock {
        val restoreEpoch = epoch.incrementAndGet()
        restoring = true
        restoreDataCommitted = false
        restoreStarted.forEach { listener -> runCatching(listener) }
        var succeeded = false
        try {
            writeMutex.withLock {
                block(restoreEpoch).also { succeeded = true }
            }
        } finally {
            val dataCommitted = restoreDataCommitted
            restoreFinished.forEach { listener -> runCatching { listener(succeeded, dataCommitted) } }
            epoch.incrementAndGet()
            restoreDataCommitted = false
            restoring = false
        }
    }

    /**
     * Serializes one short write. A null result means the writer carried a
     * stale epoch or raced with restore and must drop its stale payload.
     */
    suspend fun <T> withWriter(
        expectedEpoch: Long? = null,
        block: suspend () -> T,
    ): T? = restoreMutex.withLock {
        writeMutex.withLock {
            if (!isWriteAllowed(expectedEpoch)) null else block()
        }
    }

    /**
     * Protect a real owner write and make stale-epoch handling explicit.  A
     * caller without an epoch is a new user action and waits for restore; a
     * callback carrying an old epoch is cancelled rather than reported as a
     * successful no-op.
     */
    suspend fun <T> withCurrentWriterOrCancel(block: suspend () -> T): T =
        restoreMutex.withLock {
            writeMutex.withLock {
                if (!isWriteAllowed(currentCoroutineContext()[SyncRestoreWriteEpoch]?.value)) {
                    throw SyncRestoreWriteRejectedException()
                }
                block()
            }
        }
}
