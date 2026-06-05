package app.amber.core.memory.dream

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.amber.core.memory.model.MemoryWorkerDreamGate
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class MemoryDreamScheduler(
    context: Context,
    private val settingsStore: SettingsAggregator,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun sync(settings: Settings = settingsStore.settingsFlow.value) {
        val worker = settings.agentRuntime.memoryWorker
        if (!worker.enabled || !MemoryWorkerDreamGate.isAnyDreamEnabled(worker)) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        scheduleNextNightRun()
    }

    /**
     * Schedule the next Daydream run for the user's local night window (00:00–06:00).
     * Implemented as a OneTimeWorkRequest with initialDelay; the worker self-reschedules at
     * the end of doWork() so the loop continues night after night without losing the slot to
     * a periodic worker's flex window.
     *
     * If the user is currently inside the night window we run immediately (subject to idle
     * constraint); otherwise we wait until tonight 00:00 (or tomorrow 00:00 if 06:00 has
     * already passed today).
     */
    fun scheduleNextNightRun() {
        val worker = settingsStore.settingsFlow.value.agentRuntime.memoryWorker
        val constraintsBuilder = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
        if (worker.runOnlyOnIdle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // User explicitly asked: "when the device is sitting around". Idle is the cleanest
            // proxy — Android only flags it when screen is off + non-interactive for a while.
            constraintsBuilder.setRequiresDeviceIdle(true)
        }
        val request = OneTimeWorkRequestBuilder<MemoryDreamWorker>()
            .setConstraints(constraintsBuilder.build())
            .setInitialDelay(computeDelayUntilNextNightWindowMs(), TimeUnit.MILLISECONDS)
            .addTag(WORK_NAME)
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun computeDelayUntilNextNightWindowMs(
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Long {
        val zone = now.zone
        val today = now.toLocalDate()
        val nightStart = today.atTime(NIGHT_START).atZone(zone)
        val nightEnd = today.atTime(NIGHT_END).atZone(zone)

        // Already inside today's window → run as soon as constraints allow.
        if (!now.isBefore(nightStart) && now.isBefore(nightEnd)) return 0L

        // Window starts in the future today (effectively only when LocalTime.now() < NIGHT_START,
        // which is impossible because NIGHT_START is 00:00, but kept for clarity).
        val target = if (now.isBefore(nightStart)) nightStart else today.plusDays(1).atTime(NIGHT_START).atZone(zone)
        return Duration.between(now, target).toMillis().coerceAtLeast(0L)
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    /**
     * Run the Daydream worker once on demand. Skips charging / idle / battery-not-low
     * constraints so the user can manually trigger from settings; still requires network
     * because model dream may need an LLM. The worker itself respects the maintenance/model
     * toggles, so this is a real "run now" not "force-ignore-everything".
     */
    fun runOnce() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<MemoryDreamWorker>()
            .setConstraints(constraints)
            .addTag(MANUAL_RUN_NAME)
            .build()
        workManager.enqueueUniqueWork(
            MANUAL_RUN_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "memory_dream_review"
        const val MANUAL_RUN_NAME = "memory_dream_review_manual"
        private val NIGHT_START: LocalTime = LocalTime.of(0, 0)
        private val NIGHT_END: LocalTime = LocalTime.of(6, 0)
    }
}
