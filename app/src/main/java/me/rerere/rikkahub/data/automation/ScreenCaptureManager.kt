package me.rerere.rikkahub.data.automation

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.Instant

data class ScreenCaptureResult(
    val file: File,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val createdAt: Instant,
)

class ScreenCaptureManager(private val context: Context) {
    private val mutex = Mutex()

    @Volatile
    private var pendingCapture: CompletableDeferred<ScreenCaptureResult>? = null

    @Volatile
    var lastResult: ScreenCaptureResult? = null
        private set

    suspend fun capture(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): ScreenCaptureResult {
        val deferred = mutex.withLock {
            check(pendingCapture == null) { "Screen capture is already in progress" }
            CompletableDeferred<ScreenCaptureResult>().also { pending ->
                pendingCapture = pending
                context.startActivity(
                    Intent(context, ScreenCapturePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (error: Throwable) {
            mutex.withLock {
                if (pendingCapture === deferred) pendingCapture = null
            }
            throw error
        }
    }

    internal fun complete(result: ScreenCaptureResult) {
        lastResult = result
        pendingCapture?.complete(result)
        pendingCapture = null
    }

    internal fun fail(error: Throwable) {
        pendingCapture?.completeExceptionally(error)
        pendingCapture = null
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
