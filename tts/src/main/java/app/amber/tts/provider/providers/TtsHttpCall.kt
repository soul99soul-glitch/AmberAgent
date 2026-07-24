package app.amber.tts.provider.providers

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import app.amber.common.http.await
import okhttp3.Call
import okhttp3.Response

internal suspend fun <T> Call.awaitAndUseCancellable(
    block: suspend (Response) -> T,
): T {
    val context = currentCoroutineContext()
    val job = checkNotNull(context[Job])
    val completionHandle = job.invokeOnCompletion { cancel() }
    return try {
        context.ensureActive()
        await().use { response -> block(response) }
    } finally {
        completionHandle.dispose()
        context.ensureActive()
    }
}
