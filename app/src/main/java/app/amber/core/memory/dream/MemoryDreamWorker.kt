package app.amber.core.memory.dream

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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

        // Skip rescheduling for manual runs — those should only fire once.
        val isManualRun = tags.contains(MemoryDreamScheduler.MANUAL_RUN_NAME)
        try {
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
        } finally {
            // Re-enqueueing the same unique work name while this run is active would
            // REPLACE-cancel it, so the nightly loop is continued on the way out.
            // skipCurrentWindow targets tomorrow 00:00 — finishing inside tonight's
            // window must not respawn the worker in a tight loop.
            if (!isManualRun && !isStopped && workerSetting.enabled &&
                MemoryWorkerDreamGate.isAnyDreamEnabled(workerSetting)
            ) {
                withContext(NonCancellable) {
                    runCatching {
                        get<MemoryDreamScheduler>().scheduleNextNightRun(skipCurrentWindow = true)
                    }
                }
            }
        }
    }
}
