package app.amber.core.memory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import app.amber.core.memory.dream.MemoryDreamPlan
import app.amber.core.memory.dream.MemoryDreamPlanApplier
import app.amber.core.memory.dream.MemoryDreamPlanProvider
import app.amber.core.memory.dream.MemoryDreamPlanSource
import app.amber.core.memory.dream.MemoryDreamPlanSplit
import app.amber.core.memory.dream.MemoryDreamPlanStore
import app.amber.core.memory.dream.MemoryDreamReviewNotifier
import app.amber.core.memory.dream.MemoryDreamRunCoordinator
import app.amber.core.memory.dream.MemoryDreamRunOutcome
import app.amber.core.memory.model.MemoryWorkerSetting
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDreamRunCoordinatorTest {
    @Test
    fun autoRunAppliesMaintenanceWithoutReviewAndRecordsAppliedRun() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        val notifier = FakeDreamNotifier()
        val applier = RecordingDreamApplier()
        val maintenance = MemoryDreamPlan(promoteMemoryIds = listOf(7))
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(MemoryDreamPlanSplit(maintenance = maintenance)),
            planStore = store,
            notifier = notifier,
            applier = applier,
        )

        val outcome = coordinator.run(settings(), isManualRun = false, now = day("2026-06-05"))

        assertEquals(MemoryDreamRunOutcome.AUTO_APPLIED, outcome)
        assertEquals(listOf(maintenance), applier.applied)
        assertNull(store.getPendingPlan())
        assertEquals(listOf("running", "cancel"), notifier.events)
        assertEquals(1, store.countAutoPlansSince(day("2026-06-05")))
    }

    @Test
    fun modelPlanStillPendsReviewWhenAutoApplyOn() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        val notifier = FakeDreamNotifier()
        val applier = RecordingDreamApplier()
        val model = MemoryDreamPlan(archiveMemoryIds = listOf(9))
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(MemoryDreamPlanSplit(model = model)),
            planStore = store,
            notifier = notifier,
            applier = applier,
        )

        val outcome = coordinator.run(settings(), isManualRun = false, now = day("2026-06-05"))

        assertEquals(MemoryDreamRunOutcome.PENDING_REVIEW, outcome)
        assertEquals(MemoryDreamPlanSource.AUTO, store.getPendingPlan()?.source)
        assertEquals(listOf(9), store.getPendingPlan()?.plan?.archiveMemoryIds)
        assertTrue(applier.applied.isEmpty())
        assertEquals(listOf("running", "pending"), notifier.events)
    }

    @Test
    fun maintenanceAppliesWhileModelPlanPendsInSameRun() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        val applier = RecordingDreamApplier()
        val maintenance = MemoryDreamPlan(ignoreCandidateIds = listOf("stale-candidate"))
        val model = MemoryDreamPlan(archiveMemoryIds = listOf(3))
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(MemoryDreamPlanSplit(maintenance = maintenance, model = model)),
            planStore = store,
            notifier = FakeDreamNotifier(),
            applier = applier,
        )

        val outcome = coordinator.run(settings(), isManualRun = true, now = day("2026-06-05"))

        assertEquals(MemoryDreamRunOutcome.PENDING_REVIEW, outcome)
        assertEquals(listOf(maintenance), applier.applied)
        // Pending card shows only the model plan; the applied maintenance run
        // is kept as an audit row without clobbering review state.
        assertEquals(listOf(3), store.getPendingPlan()?.plan?.archiveMemoryIds)
        assertEquals(0, store.getPendingPlan()?.plan?.ignoreCandidateIds?.size)
    }

    @Test
    fun autoApplyOffMergesBothSourcesIntoSinglePendingPlan() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        val applier = RecordingDreamApplier()
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(
                MemoryDreamPlanSplit(
                    maintenance = MemoryDreamPlan(promoteMemoryIds = listOf(2)),
                    model = MemoryDreamPlan(archiveMemoryIds = listOf(3)),
                )
            ),
            planStore = store,
            notifier = FakeDreamNotifier(),
            applier = applier,
        )

        val outcome = coordinator.run(
            settings(worker = MemoryWorkerSetting(autoApplyMaintenance = false)),
            isManualRun = true,
            now = day("2026-06-05"),
        )

        assertEquals(MemoryDreamRunOutcome.PENDING_REVIEW, outcome)
        assertTrue(applier.applied.isEmpty())
        val pending = store.getPendingPlan()!!.plan
        assertEquals(listOf(2), pending.promoteMemoryIds)
        assertEquals(listOf(3), pending.archiveMemoryIds)
    }

    @Test
    fun pendingPlanSurvivesMaintenanceOnlyAutoRun() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        // Manual pending plan: it must not consume the auto-run daily slot.
        val existing = store.savePending(
            plan = MemoryDreamPlan(archiveMemoryIds = listOf(1)),
            source = MemoryDreamPlanSource.MANUAL,
            now = day("2026-06-05"),
        )
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(
                MemoryDreamPlanSplit(maintenance = MemoryDreamPlan(promoteMemoryIds = listOf(2)))
            ),
            planStore = store,
            notifier = FakeDreamNotifier(),
            applier = RecordingDreamApplier(),
        )

        val outcome = coordinator.run(settings(), isManualRun = false, now = day("2026-06-05"))

        assertEquals(MemoryDreamRunOutcome.AUTO_APPLIED, outcome)
        assertEquals(existing.id, store.getPendingPlan()?.id)
    }

    @Test
    fun emptyAutoRunRecordsRunButDoesNotSavePendingOrNotifyReview() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        val notifier = FakeDreamNotifier()
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(MemoryDreamPlanSplit()),
            planStore = store,
            notifier = notifier,
            applier = RecordingDreamApplier(),
        )

        val outcome = coordinator.run(settings(), isManualRun = false, now = day("2026-06-05"))

        assertEquals(MemoryDreamRunOutcome.EMPTY, outcome)
        assertNull(store.getPendingPlan())
        assertEquals(listOf("running", "cancel"), notifier.events)
        assertEquals(1, store.countAutoPlansSince(day("2026-06-05")))
    }

    @Test
    fun autoRunStopsAtDailyLimitButManualCanStillRun() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        store.recordAutoRun(MemoryDreamPlan(), now = day("2026-06-05"))
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(MemoryDreamPlanSplit(model = MemoryDreamPlan(promoteMemoryIds = listOf(2)))),
            planStore = store,
            notifier = FakeDreamNotifier(),
            applier = RecordingDreamApplier(),
        )
        val settings = settings(dreamMaxDailyRuns = 1)

        val autoOutcome = coordinator.run(settings, isManualRun = false, now = day("2026-06-05"))
        val manualOutcome = coordinator.run(settings, isManualRun = true, now = day("2026-06-05"))

        assertEquals(MemoryDreamRunOutcome.AUTO_DAILY_LIMIT, autoOutcome)
        assertEquals(MemoryDreamRunOutcome.PENDING_REVIEW, manualOutcome)
    }

    @Test
    fun allDreamGatesOffDisablesRun() = runBlocking {
        val notifier = FakeDreamNotifier()
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(MemoryDreamPlanSplit(model = MemoryDreamPlan(promoteMemoryIds = listOf(2)))),
            planStore = MemoryDreamPlanStore(FakeMemoryDreamPlanDao(), Json),
            notifier = notifier,
            applier = RecordingDreamApplier(),
        )

        val outcome = coordinator.run(
            settings = settings(
                worker = MemoryWorkerSetting(
                    dreamMaintenanceEnabled = false,
                    dreamModelEnabled = false,
                    dreamEnabled = false,
                )
            ),
            isManualRun = true,
            now = day("2026-06-05"),
        )

        assertEquals(MemoryDreamRunOutcome.DISABLED, outcome)
        assertEquals(listOf("cancel"), notifier.events)
    }

    private fun settings(
        dreamMaxDailyRuns: Int = 1,
        worker: MemoryWorkerSetting = MemoryWorkerSetting(dreamMaxDailyRuns = dreamMaxDailyRuns),
    ): Settings =
        Settings(
            agentRuntime = AgentRuntimeSetting(memoryWorker = worker),
        )

    private class FakeDreamPlanner(
        private val split: MemoryDreamPlanSplit,
    ) : MemoryDreamPlanProvider {
        override suspend fun plan(): MemoryDreamPlanSplit = split
    }

    private class RecordingDreamApplier : MemoryDreamPlanApplier {
        val applied = mutableListOf<MemoryDreamPlan>()

        override suspend fun apply(plan: MemoryDreamPlan): MemoryDreamPlan {
            applied += plan
            return plan
        }
    }

    private class FakeDreamNotifier : MemoryDreamReviewNotifier {
        val events = mutableListOf<String>()

        override fun notifyRunning() {
            events += "running"
        }

        override fun notifyPendingReview(plan: MemoryDreamPlan) {
            events += "pending"
        }

        override fun notifyFailed(message: String) {
            events += "failed"
        }

        override fun cancel() {
            events += "cancel"
        }
    }

    private companion object {
        fun day(date: String): Long =
            LocalDate.parse(date)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
    }
}
