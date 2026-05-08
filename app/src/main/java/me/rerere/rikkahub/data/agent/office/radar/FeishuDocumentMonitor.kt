package me.rerere.rikkahub.data.agent.office.radar

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.util.concurrent.TimeUnit

class FeishuDocumentMonitor(
    context: Context,
    private val settingsStore: SettingsStore,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun sync() {
        val settings = settingsStore.settingsFlow.value
        if (!settings.agentRuntime.feishuOfficeEnhancement.enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val intervalMin = 90L

        val request = PeriodicWorkRequestBuilder<FeishuDocRadarWorker>(
            intervalMin, TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
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

    suspend fun runOnce() {
        val request = androidx.work.OneTimeWorkRequestBuilder<FeishuDocRadarWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(WORK_NAME)
            .build()
        workManager.enqueue(request)
    }

    companion object {
        const val WORK_NAME = "feishu_doc_radar"
    }
}
