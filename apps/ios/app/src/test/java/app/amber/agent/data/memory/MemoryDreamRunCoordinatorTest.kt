package app.amber.core.memory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import app.amber.core.memory.dream.MemoryDreamPlan
import app.amber.core.memory.dream.MemoryDreamPlanProvider
import app.amber.core.memory.dream.MemoryDreamPlanSource
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
import org.junit.Test

class MemoryDreamRunCoordinatorTest {
    @Test
    fun autoRunSavesPendingReviewPlanWithoutApplying() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        val notifier = FakeDreamNotifier()
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(MemoryDreamPlan(promoteMemoryIds = listOf(7))),
            planStore = store,
            notifier = notifier,
        )

        val outcome = coordinator.run(settings(), isManualRun = false, now = day("2026-06-05"))

        assertEquals(MemoryDreamRunOutcome.PENDING_REVIEW, outcome)
        assertEquals(MemoryDreamPlanSource.AUTO, store.getPendingPlan()?.source)
        assertEquals(listOf("running", "pending"), notifier.events)
        assertEquals(1, store.countAutoPlansSince(day("2026-06-05")))
    }

    @Test
    fun emptyAutoRunRecordsRunButDoesNotSavePendingOrNotifyReview() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        val notifier = FakeDreamNotifier()
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(MemoryDreamPlan()),
            planStore = store,
            notifier = notifier,
        )

        val outcome = coordinator.run(settings(), isManualRun = false, now = day("2026-06-05"))

        assertEquals(MemoryDreamRunOutcome.EMPTY, outcome)
        assertNull(store.getPendingPlan())
        assertEquals(listOf("running", "cancel"), notifier.events)
        assertEquals(1, store.countAutoPlansSince(day("2026-06-05")))
    }

    @Test
    fun manualRunBypassesAutoDailyLimitAndReplacesPendingPlan() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        val first = store.savePending(
            plan = MemoryDreamPlan(archiveMemoryIds = listOf(1)),
            source = MemoryDreamPlanSource.AUTO,
            now = day("2026-06-05"),
        )
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(MemoryDreamPlan(promoteMemoryIds = listOf(2))),
            planStore = store,
            notifier = FakeDreamNotifier(),
        )

        val outcome = coordinator.run(settings(dreamMaxDailyRuns = 1), isManualRun = true, now = day("2026-06-05"))

        assertEquals(MemoryDreamRunOutcome.PENDING_REVIEW, outcome)
        assertEquals(MemoryDreamPlanSource.MANUAL, store.getPendingPlan()?.source)
        assertEquals("dismissed", dao.find(first.id)?.status)
        assertEquals(1, store.countAutoPlansSince(day("2026-06-05")))
    }

    @Test
    fun autoRunStopsAtDailyLimitButManualCanStillRun() = runBlocking {
        val dao = FakeMemoryDreamPlanDao()
        val store = MemoryDreamPlanStore(dao, Json)
        store.recordAutoRun(MemoryDreamPlan(), now = day("2026-06-05"))
        val coordinator = MemoryDreamRunCoordinator(
            planner = FakeDreamPlanner(MemoryDreamPlan(promoteMemoryIds = listOf(2))),
            planStore = store,
            notifier = FakeDreamNotifier(),
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
            planner = FakeDreamPlanner(MemoryDreamPlan(promoteMemoryIds = listOf(2))),
            planStore = MemoryDreamPlanStore(FakeMemoryDreamPlanDao(), Json),
            notifier = notifier,
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
        private val plan: MemoryDreamPlan,
    ) : MemoryDreamPlanProvider {
        override suspend fun plan(): MemoryDreamPlan = plan
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
