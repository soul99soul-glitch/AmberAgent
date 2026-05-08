package me.rerere.rikkahub.data.memory.dream

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.util.concurrent.TimeUnit

class MemoryDreamScheduler(
    context: Context,
    private val settingsStore: SettingsStore,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun sync(settings: Settings = settingsStore.settingsFlow.value) {
        val worker = settings.agentRuntime.memoryWorker
        if (!worker.enabled || !worker.dreamEnabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val constraintsBuilder = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(worker.runOnlyOnCharging)

        if (worker.runOnlyOnIdle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            constraintsBuilder.setRequiresDeviceIdle(true)
        }

        val request = PeriodicWorkRequestBuilder<MemoryDreamWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraintsBuilder.build())
            .addTag(WORK_NAME)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "memory_dream_review"
    }
}
