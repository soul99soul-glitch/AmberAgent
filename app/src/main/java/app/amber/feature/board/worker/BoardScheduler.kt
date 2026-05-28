package app.amber.feature.board.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.amber.feature.board.TodayBoardBackgroundStrategy
import app.amber.feature.board.TodayBoardSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Scheduling logic for the Today Board. Three triggers:
 *  1. Per trigger-hour anchor (08:00 / 12:00 / 18:00 by default) - self-rescheduled.
 *  2. Incremental threshold - enqueued by the aggregator when unprocessed signal count
 *     crosses the configured threshold.
 *  3. Manual / foreground-compensation - one-shot run with minimal constraints.
 *
 * We use [OneTimeWorkRequest] + initialDelay for the anchor loop (same pattern as
 * MemoryDreamScheduler) so each anchor lands precisely inside its window without
 * PeriodicWork's flex drift.
 */
class BoardScheduler(
    context: Context,
    private val settingsStore: SettingsAggregator,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun sync(settings: Settings = settingsStore.settingsFlow.value) {
        val board = settings.agentRuntime.todayBoard
        if (!board.enabled) {
            cancelAll()
            return
        }
        if (board.backgroundStrategy == TodayBoardBackgroundStrategy.FOREGROUND_ONLY) {
            cancelAll()
            return
        }
        scheduleNextAnchorRun(board)
    }

    private fun scheduleNextAnchorRun(board: TodayBoardSetting) {
        val delayMs = computeDelayUntilNextAnchorMs(board.triggerHours)
        val request = OneTimeWorkRequestBuilder<BoardWorker>()
            .setConstraints(buildConstraints(board))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(TAG_ANCHOR)
            .addTag(WORK_ANCHOR)
            .build()
        workManager.enqueueUniqueWork(
            WORK_ANCHOR,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Enqueued by the aggregator when unprocessed signals cross the threshold. */
    fun runIncremental() {
        val board = settingsStore.settingsFlow.value.agentRuntime.todayBoard
        if (!board.enabled) return
        val request = OneTimeWorkRequestBuilder<BoardWorker>()
            .setConstraints(buildConstraints(board))
            .addTag(TAG_INCREMENTAL)
            .build()
        workManager.enqueueUniqueWork(
            WORK_INCREMENTAL,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * One-shot run with minimal constraints. Used by the user's manual pull-to-refresh
     * and by the foreground compensation hook when the app comes back to foreground
     * after a long gap.
     */
    fun runOnce() {
        val request = OneTimeWorkRequestBuilder<BoardWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(TAG_MANUAL)
            .build()
        workManager.enqueueUniqueWork(
            WORK_MANUAL,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_ANCHOR)
        workManager.cancelUniqueWork(WORK_INCREMENTAL)
        workManager.cancelUniqueWork(WORK_MANUAL)
    }

    fun rescheduleNextAnchor() {
        val board = settingsStore.settingsFlow.value.agentRuntime.todayBoard
        if (board.enabled) scheduleNextAnchorRun(board)
    }

    private fun buildConstraints(board: TodayBoardSetting): Constraints {
        val builder = Constraints.Builder()
        when (board.backgroundStrategy) {
            TodayBoardBackgroundStrategy.WIFI_ONLY ->
                builder.setRequiredNetworkType(NetworkType.UNMETERED)

            TodayBoardBackgroundStrategy.SMART -> {
                builder.setRequiredNetworkType(NetworkType.CONNECTED)
                builder.setRequiresBatteryNotLow(true)
            }

            TodayBoardBackgroundStrategy.FOREGROUND_ONLY ->
                builder.setRequiredNetworkType(NetworkType.CONNECTED)
        }
        return builder.build()
    }

    /**
     * Minutes until the next upcoming anchor hour. If all of today's anchors are in the
     * past, target the earliest anchor tomorrow.
     */
    private fun computeDelayUntilNextAnchorMs(
        triggerHours: List<String>,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Long {
        val zone = now.zone
        val today = now.toLocalDate()
        val parsed = triggerHours.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
            .sorted()
        if (parsed.isEmpty()) {
            // No anchors configured - fall back to 6h from now so we still run eventually.
            return Duration.ofHours(6).toMillis()
        }
        val todaySlots = parsed.map { today.atTime(it).atZone(zone) }
        val nextToday = todaySlots.firstOrNull { it.isAfter(now) }
        val target = nextToday ?: today.plusDays(1).atTime(parsed.first()).atZone(zone)
        return Duration.between(now, target).toMillis().coerceAtLeast(1_000L)
    }

    companion object {
        const val WORK_ANCHOR = "today_board_anchor"
        const val WORK_INCREMENTAL = "today_board_incremental"
        const val WORK_MANUAL = "today_board_manual"

        const val TAG_ANCHOR = "today_board_anchor"
        const val TAG_INCREMENTAL = "today_board_incremental"
        const val TAG_MANUAL = "today_board_manual"
    }
}
