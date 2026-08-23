package app.amber.core.automation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class ScreenCaptureResult(
    val file: File,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val createdAt: Instant,
)

class ScreenCaptureManager(private val context: Context) {
    private val mutex = Mutex()

    private data class PendingCapture(
        val requestId: String,
        val deferred: CompletableDeferred<ScreenCaptureResult>,
    )

    private val pendingCapture = AtomicReference<PendingCapture?>(null)

    @Volatile
    private var sessionActive: Boolean = false

    @Volatile
    var lastResult: ScreenCaptureResult? = null
        private set

    suspend fun capture(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): ScreenCaptureResult {
        val pending = PendingCapture(UUID.randomUUID().toString(), CompletableDeferred())
        try {
            mutex.withLock {
                check(pendingCapture.get() == null) { "Screen capture is already in progress" }
                check(pendingCapture.compareAndSet(null, pending)) { "Screen capture is already in progress" }
                if (sessionActive) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ScreenCaptureService::class.java)
                            .setAction(ScreenCaptureService.ACTION_CAPTURE_EXISTING)
                            .putExtra(EXTRA_REQUEST_ID, pending.requestId)
                    )
                } else {
                    context.startActivity(
                        Intent(context, ScreenCapturePermissionActivity::class.java)
                            .putExtra(EXTRA_REQUEST_ID, pending.requestId)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                }
            }
        } catch (error: Throwable) {
            pendingCapture.compareAndSet(pending, null)
            throw error
        }
        return try {
            withTimeout(timeoutMillis) { pending.deferred.await() }
        } catch (error: Throwable) {
            pendingCapture.compareAndSet(pending, null)
            throw error
        }
    }

    internal fun complete(requestId: String, result: ScreenCaptureResult) {
        val pending = pendingCapture.get() ?: return
        if (pending.requestId != requestId || !pendingCapture.compareAndSet(pending, null)) return
        lastResult = result
        pending.deferred.complete(result)
    }

    internal fun fail(requestId: String, error: Throwable) {
        val pending = pendingCapture.get() ?: return
        if (pending.requestId != requestId || !pendingCapture.compareAndSet(pending, null)) return
        pending.deferred.completeExceptionally(error)
    }

    internal fun markSessionActive(active: Boolean) {
        sessionActive = active
    }

    fun releaseSession() {
        if (!sessionActive) return
        sessionActive = false
        ContextCompat.startForegroundService(
            context,
            Intent(context, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_STOP_SESSION)
        )
    }

    companion object {
        const val EXTRA_REQUEST_ID = "screen_capture_request_id"
        private const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
