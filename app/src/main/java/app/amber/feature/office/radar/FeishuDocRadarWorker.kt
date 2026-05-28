package app.amber.feature.office.radar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Legacy worker stub — kept so WorkManager doesn't crash when trying to
 * instantiate a pending "feishu_doc_radar" job scheduled before the refactor.
 * DocRadar.schedulePeriodicCheck() cancels this work name, so this class
 * should never actually run on new installs.
 */
class FeishuDocRadarWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.success()
}
