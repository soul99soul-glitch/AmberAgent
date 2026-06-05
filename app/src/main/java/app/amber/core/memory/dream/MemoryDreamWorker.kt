package app.amber.core.memory.dream

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.memory.model.MemoryEventType
import app.amber.core.memory.model.MemoryWorkerDreamGate
import app.amber.core.memory.telemetry.MemoryEventLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class MemoryDreamWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        val settings = get<SettingsAggregator>().settingsFlow.filterNot { it.init }.first()
        val workerSetting = settings.agentRuntime.memoryWorker
        val eventLogger = get<MemoryEventLogger>()

        // Always reschedule the next nightly run before doing anything else, so the loop
        // continues even if this run early-exits or fails. (We use OneTimeWorkRequest with
        // initialDelay rather than PeriodicWorkRequest so the next slot lands cleanly inside
        // tomorrow's 00:00–06:00 window in the user's local timezone.) Skip rescheduling for
        // manual runs — those should only fire once.
        val isManualRun = tags.contains(MemoryDreamScheduler.MANUAL_RUN_NAME)
        if (!isManualRun && workerSetting.enabled && MemoryWorkerDreamGate.isAnyDreamEnabled(workerSetting)) {
            get<MemoryDreamScheduler>().scheduleNextNightRun()
        }

        return runCatching {
            get<MemoryDreamRunCoordinator>().run(
                settings = settings,
                isManualRun = isManualRun,
            )
            Result.success()
        }.getOrElse { error ->
            val message = error.message ?: error::class.java.simpleName
            eventLogger.log(
                type = MemoryEventType.DREAM_FAILED,
                message = message.take(500),
            )
            get<MemoryDreamNotifier>().notifyFailed(message)
            Result.failure()
        }
    }
}
