package app.amber.core.infra

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-scoped CoroutineScope. Lives for the entire app process lifetime;
 * cancelled in Application.onTerminate(). Use for fire-and-forget work that must
 * survive Activity lifecycle (notifications, background sync, etc.).
 */
class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
            logE("AppScope", "AppScope exception", e)
        }
)

/**
 * Platform-abstract error logger. Android uses android.util.Log; iOS uses
 * NSLog/print. Called from CoroutineExceptionHandler, so it must not throw.
 */
expect fun logE(tag: String, message: String, throwable: Throwable?)
