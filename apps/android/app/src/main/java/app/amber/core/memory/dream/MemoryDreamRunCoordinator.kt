package app.amber.core.memory.dream

import app.amber.core.memory.model.MemoryWorkerDreamGate
import app.amber.core.settings.Settings
import java.time.Instant
import java.time.ZoneId

class MemoryDreamRunCoordinator(
    private val planner: MemoryDreamPlanProvider,
    private val planStore: MemoryDreamPlanStore,
    private val notifier: MemoryDreamReviewNotifier,
) {
    suspend fun run(
        settings: Settings,
        isManualRun: Boolean,
        now: Long = System.currentTimeMillis(),
    ): MemoryDreamRunOutcome {
        val worker = settings.agentRuntime.memoryWorker
        if (!worker.enabled || !MemoryWorkerDreamGate.isAnyDreamEnabled(worker)) {
            notifier.cancel()
            return MemoryDreamRunOutcome.DISABLED
        }
        if (!isManualRun) {
            val todayStart = Instant.ofEpochMilli(now)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val autoRunsToday = planStore.countAutoPlansSince(todayStart)
            if (autoRunsToday >= worker.dreamMaxDailyRuns.coerceAtLeast(1)) {
                notifier.cancel()
                return MemoryDreamRunOutcome.AUTO_DAILY_LIMIT
            }
        }

        notifier.notifyRunning()
        val plan = planner.plan()
        if (!plan.hasChanges) {
            if (!isManualRun) {
                planStore.recordAutoRun(plan, now)
            }
            notifier.cancel()
            return MemoryDreamRunOutcome.EMPTY
        }

        val source = if (isManualRun) MemoryDreamPlanSource.MANUAL else MemoryDreamPlanSource.AUTO
        planStore.savePending(plan, source, now)
        notifier.notifyPendingReview(plan)
        return MemoryDreamRunOutcome.PENDING_REVIEW
    }
}

enum class MemoryDreamRunOutcome {
    DISABLED,
    AUTO_DAILY_LIMIT,
    EMPTY,
    PENDING_REVIEW,
}
