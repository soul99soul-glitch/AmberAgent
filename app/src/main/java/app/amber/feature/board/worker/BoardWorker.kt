package app.amber.feature.board.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import app.amber.core.ai.GenerationFailureClassifier
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.board.BoardRepository
import app.amber.feature.board.TodayBoardBackgroundStrategy
import app.amber.feature.board.agent.BoardAgent
import app.amber.feature.board.agent.BoardRunResult
import app.amber.feature.board.agent.DailyReviewAgent
import app.amber.feature.board.aggregator.SignalAggregator
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.appLocale
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Worker that executes one full board update cycle:
 *   1. Collect fresh signals from all pull-based collectors (via [SignalAggregator.collectAll]).
 *   2. Read unprocessed + scored signals from the aggregator.
 *   3. Invoke [BoardAgent] to generate items for today's board.
 *   4. Mark used signals as processed so the next run doesn't reconsider them.
 *   5. Prune stale items from previous days.
 *   6. Reschedule the next anchor run (only when dispatched as an anchor; manual and
 *      incremental runs don't reschedule themselves to avoid drift).
 */
class BoardWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    override suspend fun doWork(): Result {
        val restoreWriteEpoch = get<SyncRestoreWriteGate>().currentEpoch()
        val settings = get<SettingsAggregator>().settingsFlow.filterNot { it.init }.first()
        val board = settings.agentRuntime.todayBoard
        if (!board.enabled) return Result.success()
        if (
            board.backgroundStrategy == TodayBoardBackgroundStrategy.FOREGROUND_ONLY &&
            !tags.contains(BoardScheduler.TAG_MANUAL)
        ) {
            return Result.success()
        }

        val aggregator = get<SignalAggregator>()
        val repository = get<BoardRepository>()
        val agent = get<BoardAgent>()
        val notifier = get<BoardNotifier>()
        val scheduler = get<BoardScheduler>()
        val locale = applicationContext.appLocale()

        // Manual / incremental runs shouldn't touch the anchor cadence. The next
        // anchor is re-enqueued in the finally below — doing it upfront with
        // REPLACE on the same unique work name cancels this very run.
        val isAnchor = tags.contains(BoardScheduler.TAG_ANCHOR)
        return withContext(SyncRestoreWriteEpoch(restoreWriteEpoch)) {
            try {
                runCycle(aggregator, repository, agent, notifier, locale)
            } finally {
                if (isAnchor && !isStopped) {
                    withContext(NonCancellable) {
                        runCatching { scheduler.rescheduleNextAnchor() }
                    }
                }
            }
        }
    }

    private suspend fun runCycle(
        aggregator: SignalAggregator,
        repository: BoardRepository,
        agent: BoardAgent,
        notifier: BoardNotifier,
        locale: java.util.Locale,
    ): Result {
        val boardDate = repository.resolveBoardDate()

        try {
            aggregator.collectAll()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            android.util.Log.w("BoardWorker", "collectAll failed", error)
        }

        val batch = aggregator.getFilteredSignalBatch(limit = 200)
        val scored = batch.surfaced
        if (scored.isEmpty()) {
            repository.markSignalsProcessed(batch.consideredSignalIds)
            pruneOldItems(repository, boardDate)
            maybeRunDailyReview(boardDate, locale)
            return Result.success()
        }

        val rules = repository.getActiveFocusRules()
        val result = agent.run(
            scoredSignals = scored,
            focusRules = rules,
            boardDate = boardDate,
            locale = locale,
        )

        when (result) {
            is BoardRunResult.Success -> {
                repository.markSignalsProcessed(batch.consideredSignalIds)
                notifier.notifySuccess(
                    itemCount = result.itemCount,
                    summary = result.summary,
                )
                pruneOldItems(repository, boardDate)
                maybeRunDailyReview(boardDate, locale)
                return Result.success()
            }

            BoardRunResult.Empty -> {
                // Mark as processed: Empty means the agent reviewed the signals and
                // found nothing board-worthy. Not marking them would cause infinite
                // re-processing + unbounded table growth (pruneProcessedSignalsBefore
                // only deletes processed=1 rows).
                repository.markSignalsProcessed(batch.consideredSignalIds)
                pruneOldItems(repository, boardDate)
                maybeRunDailyReview(boardDate, locale)
                return Result.success()
            }

            is BoardRunResult.Failed -> {
                notifier.notifyFailure(result.reason)
                val modelFailure = result.cause
                if (modelFailure != null) {
                    val retryable = GenerationFailureClassifier.classify(modelFailure).retryable
                    if (retryable && runAttemptCount < 3) return Result.retry()
                    return Result.failure()
                }
                // Preserve the existing parse-failure retry behavior. A model call that
                // returned no text has no Throwable and remains a permanent failure.
                if (result.reason.contains("model call failed")) return Result.failure()
                if (runAttemptCount >= 3) return Result.failure()
                return Result.retry()
            }
        }
    }

    /**
     * Run the daily review agent if the current time falls within a review window.
     * - 12:00–14:59 → noon phase (first generation)
     * - 18:00–23:59 → evening phase (append to noon)
     */
    private suspend fun maybeRunDailyReview(boardDate: String, locale: java.util.Locale) {
        val now = ZonedDateTime.now()
        val hour = now.hour
        val phase = when (hour) {
            in 12..14 -> DailyReviewAgent.PHASE_NOON
            in 18..23 -> DailyReviewAgent.PHASE_EVENING
            else -> return // outside review windows
        }
        try {
            val agent = get<DailyReviewAgent>()
            agent.run(boardDate, phase, locale)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            android.util.Log.w("BoardWorker", "daily review failed", error)
        }
    }

    private suspend fun pruneOldItems(
        repository: BoardRepository,
        currentBoardDate: String,
    ) {
        try {
            // Keep only today's board - earlier dates are archived at 04:00 cutoff.
            val today = LocalDate.parse(currentBoardDate)
            repository.purgeItemsBefore(today.toString())
            // Also prune old processed signals (> 7 days) to bound signal table growth.
            val weekAgoMs = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
            repository.pruneProcessedSignalsBefore(weekAgoMs)
            // Prune daily reviews older than 30 days.
            val cutoffDate = today.minusDays(30).toString()
            repository.pruneDailyReviews(cutoffDate)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
        }
    }
}
