package me.rerere.rikkahub.data.memory.dream

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.memory.model.MemoryEventType
import me.rerere.rikkahub.data.memory.telemetry.MemoryEventLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate
import java.time.ZoneId

class MemoryDreamWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        val settings = get<SettingsStore>().settingsFlow.value
        val workerSetting = settings.agentRuntime.memoryWorker
        val eventLogger = get<MemoryEventLogger>()
        val notifier = get<MemoryDreamNotifier>()

        if (!workerSetting.enabled || !workerSetting.dreamEnabled) {
            notifier.cancel()
            return Result.success()
        }

        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val autoRunsToday = get<MemoryDreamPlanStore>().countAutoPlansSince(todayStart)
        if (autoRunsToday >= workerSetting.dreamMaxDailyRuns.coerceAtLeast(1)) {
            return Result.success()
        }

        notifier.notifyRunning()
        return runCatching {
            val plan = get<MemoryDreamPlanner>().plan()
            val planStore = get<MemoryDreamPlanStore>()
            if (plan.hasChanges) {
                val appliedPlan = get<MemoryDreamApplier>().apply(plan)
                if (appliedPlan.hasChanges) {
                    planStore.saveApplied(appliedPlan, MemoryDreamPlanSource.AUTO)
                    notifier.notifyApplied(appliedPlan)
                } else {
                    planStore.recordAutoRun(plan)
                    notifier.cancel()
                }
            } else {
                planStore.recordAutoRun(plan)
                notifier.cancel()
            }
            Result.success()
        }.getOrElse { error ->
            val message = error.message ?: error::class.java.simpleName
            eventLogger.log(
                type = MemoryEventType.DREAM_FAILED,
                message = message.take(500),
            )
            notifier.notifyFailed(message)
            Result.failure()
        }
    }
}
