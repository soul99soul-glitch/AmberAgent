package app.amber.ai.provider.providers.openai

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import app.amber.common.http.await
import okhttp3.Call
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

internal class CancellableCallScope(
) {
    private val activeCall = AtomicReference<Call?>()

    suspend fun awaitResponse(call: Call): Response {
        activeCall.set(call)
        currentCoroutineContext().ensureActive()
        return call.await()
    }

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }
}

@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> withCancellableCall(
    block: suspend CancellableCallScope.() -> T,
): T {
    val context = currentCoroutineContext()
    val job = checkNotNull(context[Job])
    val callScope = CancellableCallScope()
    // Cancel during the cancelling phase so a blocking response-body read can finish.
    val completionHandle = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
        callScope.cancel()
    }
    return try {
        callScope.block()
    } finally {
        completionHandle.dispose()
        context.ensureActive()
    }
}
