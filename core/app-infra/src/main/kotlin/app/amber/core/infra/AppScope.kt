package app.amber.core.infra

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-scoped CoroutineScope. Lives for the entire app process lifetime;
 * cancelled in Application.onTerminate(). Use for fire-and-forget work that must
 * survive Activity lifecycle (notifications, background sync, etc.).
 *
 * Moved from me.rerere.rikkahub.AppScope so feature modules can depend on it
 * without pulling in the entire :app module.
 */
class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
            Log.e("AppScope", "AppScope exception", e)
        }
)
