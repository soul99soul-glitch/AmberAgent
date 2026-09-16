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
    private val applier: MemoryDreamPlanApplier,
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
        val split = planner.plan()
        val source = if (isManualRun) MemoryDreamPlanSource.MANUAL else MemoryDreamPlanSource.AUTO

        // Deterministic maintenance applies without review when enabled. The
        // model plan was computed against the pre-maintenance snapshot; the
        // applier re-filters stale ids at apply time, so that is safe.
        val appliedMaintenance = if (worker.autoApplyMaintenance && split.maintenance.hasChanges) {
            applier.apply(split.maintenance).takeIf { it.hasChanges }
        } else {
            null
        }
        if (appliedMaintenance != null) {
            planStore.recordAppliedRun(appliedMaintenance, source, now)
        }

        // With auto-apply on, only the model part still needs review; with it
        // off, merge both sources into one pending plan (legacy behavior).
        val reviewPlan = if (worker.autoApplyMaintenance) split.model else split.merged()
        if (reviewPlan?.hasChanges == true) {
            planStore.savePending(reviewPlan, source, now)
            notifier.notifyPendingReview(reviewPlan)
            return MemoryDreamRunOutcome.PENDING_REVIEW
        }

        if (appliedMaintenance != null) {
            notifier.cancel()
            return MemoryDreamRunOutcome.AUTO_APPLIED
        }

        if (!isManualRun) {
            planStore.recordAutoRun(MemoryDreamPlan(), now)
        }
        notifier.cancel()
        return MemoryDreamRunOutcome.EMPTY
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
    AUTO_APPLIED,
    PENDING_REVIEW,
}
