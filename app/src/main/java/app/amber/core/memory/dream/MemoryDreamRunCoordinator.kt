package app.amber.core.memory.dream

import app.amber.core.memory.model.MemoryWorkerDreamGate
import app.amber.core.settings.Settings
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class MemoryDreamRunCoordinator(
    private val planner: MemoryDreamPlanProvider,
    private val planStore: MemoryDreamPlanStore,
    private val notifier: MemoryDreamReviewNotifier,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    suspend fun run(
        settings: Settings,
        isManualRun: Boolean,
        now: Long = System.currentTimeMillis(),
    ): MemoryDreamRunOutcome = withContext(captureWriteContext()) {
        runInternal(settings, isManualRun, now)
    }

    private suspend fun runInternal(
        settings: Settings,
        isManualRun: Boolean,
        now: Long,
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

    private suspend fun captureWriteContext(): CoroutineContext {
        coroutineContext[SyncRestoreWriteEpoch]?.let { return it }
        val gate = restoreWriteGate ?: return EmptyCoroutineContext
        gate.withWriter { Unit }
        return SyncRestoreWriteEpoch(gate.currentEpoch())
    }
}

enum class MemoryDreamRunOutcome {
    DISABLED,
    AUTO_DAILY_LIMIT,
    EMPTY,
    PENDING_REVIEW,
}
