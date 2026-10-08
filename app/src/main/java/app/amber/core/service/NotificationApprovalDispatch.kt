package app.amber.core.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The receiver waits only for the persisted decision. The same session-owned
 * job then continues the turn so Stop and later session operations cancel it.
 */
internal suspend fun ConversationSession.dispatchNotificationApproval(
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    onError: (Exception) -> Unit,
    process: suspend (decisionPersisted: () -> Unit) -> Unit,
): Boolean {
    val accepted = CompletableDeferred<Boolean>()
    val job = scope.launch(context, start = CoroutineStart.LAZY) {
        try {
            process { accepted.complete(true) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError(error)
        }
    }
    // Also settles when an already-cancelled scope prevents the lazy body
    // from running at all; its body/finally cannot handle that case.
    job.invokeOnCompletion { accepted.complete(false) }
    setJob(job)
    job.start()
    return accepted.await()
}
