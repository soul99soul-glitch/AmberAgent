package app.amber.feature.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-conversation activity center: four-kind mapping, priority, handoff,
 * watermarks, current-conversation exclusion, title fallback, cancel.
 */
class ConversationActivityCenterTest {

    @Test
    fun reviewOpenConversationConsumesNewRunBehindAlreadySeenHigherPriorityRun() = runTest {
        val store = FakeRunTerminalStore()
        val waiting = terminal("waiting", "conv-1", RunTerminalState.WAITING_USER, 50)
        store.setTerminals(waiting)
        val center = center(store = store, now = { 10 }) { "t" }
        val job = backgroundScope.launch { center.consumeWhileOpen("conv-1") }
        testScheduler.runCurrent()
        store.setTerminals(waiting, terminal("done", "conv-1", RunTerminalState.COMPLETED, 90))
        testScheduler.runCurrent()
        assertTrue("new completed run in open chat must be seen", store.seenRows().any { it.runId == "done" })
        job.cancel()
    }

    @Test
    fun registryProjectionTracksRegisterAndUnregister() = runTest {
        val registry = RunOwnershipRegistry()
        val jobA = Job()
        assertTrue(registry.activeRuns.value.isEmpty())
        registry.register("a", "conv-1", "run-1", jobA)
        assertEquals(listOf("conv-1"), registry.activeRuns.value.map { it.conversationId })
        registry.unregister("run-1")
        assertTrue(registry.activeRuns.value.isEmpty())
    }

    @Test
    fun refusedRegistrationDoesNotPublishAnotherOwner() = runTest {
        val registry = RunOwnershipRegistry()
        registry.register("a", "conv-1", "run-1", Job())
        registry.register("a", "conv-2", "run-1", Job())
        assertEquals(listOf("conv-1"), registry.activeRuns.value.map { it.conversationId })
    }

    @Test
    fun mismatchedCancelLeavesTheProjectionUntouched() = runTest {
        val registry = registryWith(Triple("a", "conv-1", Job()))
        assertEquals(false, registry.cancel("run-conv-1", "conv-other"))
        assertEquals(listOf("conv-1"), registry.activeRuns.value.map { it.conversationId })
    }

    @Test
    fun noticesSkipTheCurrentConversation() = runTest {
        val registry = registryWith(
            Triple("a", "conv-current", Job()),
            Triple("a", "conv-other", Job()),
        )
        val center = center(registry) { id -> "标题-$id" }
        val notices = center.notices(excluding = "conv-current").first()
        assertEquals(listOf("conv-other"), notices.map { it.conversationId })
        assertEquals("标题-conv-other", notices.single().title)
        assertEquals(ConversationActivityKind.RUNNING, notices.single().kind)
    }

    @Test
    fun oneNoticePerConversationKeepsTheNewestRun() = runTest {
        val registry = RunOwnershipRegistry()
        registry.register("a", "conv-1", "run-old", Job())
        Thread.sleep(5)
        registry.register("a", "conv-1", "run-retry", Job())
        val center = center(registry) { null }
        val notices = center.notices(excluding = null).first()
        assertEquals(1, notices.size)
        assertEquals(
            registry.activeRuns.value.maxOf { it.registeredAtMs },
            notices.single().sinceMs,
        )
        assertEquals("run-retry", notices.single().runId)
    }

    @Test
    fun missingOrBlankTitleFallsBackToIdPrefix() = runTest {
        val registry = registryWith(Triple("a", "0123456789abcdef", Job()))
        val nullTitle = center(registry) { null }.notices(excluding = null).first()
        assertEquals("01234567", nullTitle.single().title)

        val blankTitle = center(registry) { "  " }.notices(excluding = null).first()
        assertEquals("01234567", blankTitle.single().title)
    }

    @Test
    fun throwingTitleProviderDoesNotBreakTheNotice() = runTest {
        val registry = registryWith(Triple("a", "conv-1", Job()))
        val center = center(registry) { error("db gone") }
        val notices = center.notices(excluding = null).first()
        assertEquals("conv-1", notices.single().title)
    }

    @Test
    fun titleProviderCancellationStopsCollection() = runTest {
        val registry = registryWith(Triple("a", "conv-1", Job()))
        val center = center(registry) { throw CancellationException("title cancelled") }
        val result = runCatching { center.notices(excluding = null).first() }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun mapsFourKindsAndAbnormalTerminals() {
        assertEquals(ConversationActivityKind.RUNNING, conversationActivityKindFor(RunTerminalState.RUNNING))
        assertEquals(ConversationActivityKind.RUNNING, conversationActivityKindFor(RunTerminalState.WAITING_EXTERNAL))
        assertEquals(ConversationActivityKind.RUNNING, conversationActivityKindFor(RunTerminalState.RESUMABLE))
        assertEquals(ConversationActivityKind.AWAITING_USER, conversationActivityKindFor(RunTerminalState.WAITING_USER))
        assertEquals(ConversationActivityKind.AWAITING_USER, conversationActivityKindFor(RunTerminalState.OUTCOME_UNKNOWN))
        assertEquals(ConversationActivityKind.FAILED, conversationActivityKindFor(RunTerminalState.FAILED))
        assertEquals(ConversationActivityKind.FAILED, conversationActivityKindFor(RunTerminalState.STEP_LIMIT))
        assertEquals(ConversationActivityKind.FAILED, conversationActivityKindFor(RunTerminalState.OUTPUT_LIMIT))
        assertEquals(ConversationActivityKind.FAILED, conversationActivityKindFor(RunTerminalState.GUARD_STOPPED))
        assertEquals(ConversationActivityKind.FAILED, conversationActivityKindFor(RunTerminalState.INTERRUPTED))
        assertEquals(ConversationActivityKind.COMPLETED, conversationActivityKindFor(RunTerminalState.COMPLETED))
        assertNull(conversationActivityKindFor(RunTerminalState.CANCELLED))
    }

    @Test
    fun waitOutranksFailOutranksCompleteOutranksRunning() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(
            terminal("run-run", "conv-1", RunTerminalState.RUNNING, 400),
            terminal("run-done", "conv-1", RunTerminalState.COMPLETED, 300),
            terminal("run-fail", "conv-1", RunTerminalState.FAILED, 200),
            terminal("run-wait", "conv-1", RunTerminalState.WAITING_USER, 100),
        )
        val notices = center(store = store, now = { 10 }).notices(excluding = null).first()
        assertEquals(1, notices.size)
        assertEquals(ConversationActivityKind.AWAITING_USER, notices.single().kind)
        assertEquals("run-wait", notices.single().runId)
    }

    @Test
    fun outcomeUnknownMapsToAwaitingUser() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-u", "conv-1", RunTerminalState.OUTCOME_UNKNOWN, 50))
        val notices = center(store = store, now = { 10 }).notices(excluding = null).first()
        assertEquals(ConversationActivityKind.AWAITING_USER, notices.single().kind)
    }

    @Test
    fun cancelledTerminalIsHidden() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-c", "conv-1", RunTerminalState.CANCELLED, 50))
        val notices = center(store = store, now = { 10 }).notices(excluding = null).first()
        assertTrue(notices.isEmpty())
    }

    @Test
    fun sameConversationKeepsNewestAmongEqualKind() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(
            terminal("run-old", "conv-1", RunTerminalState.FAILED, 40),
            terminal("run-new", "conv-1", RunTerminalState.STEP_LIMIT, 90),
        )
        val notices = center(store = store, now = { 10 }).notices(excluding = null).first()
        assertEquals("run-new", notices.single().runId)
        assertEquals(ConversationActivityKind.FAILED, notices.single().kind)
    }

    @Test
    fun activeTerminalHandoffDoesNotDuplicate() = runTest {
        val registry = RunOwnershipRegistry()
        val store = FakeRunTerminalStore()
        val job = Job()
        registry.register("a", "conv-1", "run-1", job)
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.RUNNING, 20))
        val center = center(registry, store, now = { 10 }) { "t" }
        val emissions = mutableListOf<List<Pair<String, ConversationActivityKind>>>()
        val collectJob = launch {
            center.notices(excluding = null).collect { notices ->
                emissions += notices.map { it.runId to it.kind }
            }
        }
        testScheduler.runCurrent()
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.COMPLETED, 50))
        testScheduler.runCurrent()
        registry.unregister("run-1")
        testScheduler.runCurrent()
        collectJob.cancel()

        assertTrue(emissions.isNotEmpty())
        assertTrue(emissions.none { it.size > 1 })
        assertEquals(listOf("run-1" to ConversationActivityKind.RUNNING), emissions.first())
        assertEquals(listOf("run-1" to ConversationActivityKind.COMPLETED), emissions.last())
        assertTrue(emissions.none { it.isEmpty() })
    }

    @Test
    fun historicalCompletedIsBaselinedAway() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-old", "conv-1", RunTerminalState.COMPLETED, 5))
        val notices = center(store = store, now = { 100 }).notices(excluding = null).first()
        assertTrue(notices.isEmpty())
    }

    @Test
    fun newTerminalAfterBaselineAlerts() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-old", "conv-1", RunTerminalState.COMPLETED, 5))
        val center = center(store = store, now = { 100 }) { "t" }
        assertTrue(center.notices(excluding = null).first().isEmpty())
        store.setTerminals(
            terminal("run-old", "conv-1", RunTerminalState.COMPLETED, 5),
            terminal("run-new", "conv-2", RunTerminalState.FAILED, 150),
        )
        val notices = center.notices(excluding = null).first()
        assertEquals(listOf("conv-2"), notices.map { it.conversationId })
        assertEquals(ConversationActivityKind.FAILED, notices.single().kind)
    }

    @Test
    fun openingTargetClearsOnlyThatRunVersion() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(
            terminal("run-a", "conv-a", RunTerminalState.COMPLETED, 50),
            terminal("run-b", "conv-b", RunTerminalState.FAILED, 60),
        )
        val center = center(store = store, now = { 10 }) { id -> id }
        val before = center.notices(excluding = null).first()
        assertEquals(setOf("conv-a", "conv-b"), before.map { it.conversationId }.toSet())
        val target = before.first { it.conversationId == "conv-a" }
        assertTrue(center.markSeen(target))
        val after = center.notices(excluding = null).first()
        assertEquals(listOf("conv-b"), after.map { it.conversationId })
    }

    @Test
    fun mismatchedRunIdDoesNotClearOtherNotice() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-real", "conv-1", RunTerminalState.FAILED, 50))
        val center = center(store = store, now = { 10 }) { "t" }
        center.markSeen("run-other", "conv-1", 99)
        val notices = center.notices(excluding = null).first()
        assertEquals("run-real", notices.single().runId)
    }

    @Test
    fun casOlderVersionDoesNotHideNewerNotice() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.COMPLETED, 80))
        val center = center(store = store, now = { 10 }) { "t" }
        assertTrue(center.markSeen("run-1", "conv-1", 50))
        val notices = center.notices(excluding = null).first()
        assertEquals(80L, notices.single().versionMs)
        assertTrue(center.markSeen("run-1", "conv-1", 80))
        assertTrue(center.notices(excluding = null).first().isEmpty())
    }

    @Test
    fun newRunOnSameConversationAlertsAgain() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.COMPLETED, 50))
        val center = center(store = store, now = { 10 }) { "t" }
        center.markSeen("run-1", "conv-1", 50)
        assertTrue(center.notices(excluding = null).first().isEmpty())
        store.setTerminals(
            terminal("run-1", "conv-1", RunTerminalState.COMPLETED, 50),
            terminal("run-2", "conv-1", RunTerminalState.COMPLETED, 90),
        )
        val notices = center.notices(excluding = null).first()
        assertEquals("run-2", notices.single().runId)
    }

    @Test
    fun seenForWrongConversationDoesNotClearUnread() {
        val seen = mapOf(
            "run-1" to ConversationActivitySeen("run-1", "other-conv", 50),
        )
        assertTrue(
            isConversationActivityUnread(
                kind = ConversationActivityKind.COMPLETED,
                runId = "run-1",
                conversationId = "conv-1",
                versionMs = 50,
                seenByRunId = seen,
                baselineMs = 0,
            ),
        )
    }

    @Test
    fun consumeWhileOpenDirectOpenMarksFirstSnapshot() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.COMPLETED, 50))
        val center = center(store = store, now = { 10 }) { "t" }
        val job = launch { center.consumeWhileOpen("conv-1") }
        testScheduler.runCurrent()
        assertEquals(50L, store.seenRows().single().versionMs)
        job.cancel()
    }

    @Test
    fun consumeWhileOpenNavigationDoesNotMarkUnseenNewerVersion() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.COMPLETED, 80))
        val center = center(store = store, now = { 10 }) { "t" }
        center.rememberOpenedNotice(
            ConversationActivityNotice(
                conversationId = "conv-1",
                runId = "run-1",
                title = "t",
                kind = ConversationActivityKind.COMPLETED,
                sinceMs = 50,
                versionMs = 50,
            ),
        )
        val job = launch { center.consumeWhileOpen("conv-1") }
        testScheduler.runCurrent()
        assertEquals(50L, store.seenRows().single().versionMs)
        val others = center.notices(excluding = "conv-other").first()
        assertEquals(80L, others.single().versionMs)
        job.cancel()
    }

    @Test
    fun consumeWhileOpenMarksUpdatesAfterThePageIsShowing() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.WAITING_USER, 50))
        val center = center(store = store, now = { 10 }) { "t" }
        val job = launch { center.consumeWhileOpen("conv-1") }
        testScheduler.runCurrent()
        assertEquals(50L, store.seenRows().single().versionMs)
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.FAILED, 90))
        testScheduler.runCurrent()
        assertEquals(90L, store.seenRows().single().versionMs)
        job.cancel()
    }

    @Test
    fun consumeWhileOpenDirectOpenMarksEveryPendingRunOnFirstSnapshot() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(
            terminal("waiting", "conv-1", RunTerminalState.WAITING_USER, 50),
            terminal("done", "conv-1", RunTerminalState.COMPLETED, 90),
        )
        val center = center(store = store, now = { 10 }) { "t" }
        val job = launch { center.consumeWhileOpen("conv-1") }
        testScheduler.runCurrent()
        assertEquals(setOf("waiting", "done"), store.seenRows().map { it.runId }.toSet())
        job.cancel()
    }

    @Test
    fun consumeWhileOpenNavigationConsumesNewRunAfterFirstFrame() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.COMPLETED, 80))
        val center = center(store = store, now = { 10 }) { "t" }
        center.rememberOpenedNotice(
            ConversationActivityNotice(
                conversationId = "conv-1",
                runId = "run-1",
                title = "t",
                kind = ConversationActivityKind.COMPLETED,
                sinceMs = 50,
                versionMs = 50,
            ),
        )
        val job = launch { center.consumeWhileOpen("conv-1") }
        testScheduler.runCurrent()
        assertEquals(50L, store.seenRows().single { it.runId == "run-1" }.versionMs)
        store.setTerminals(
            terminal("run-1", "conv-1", RunTerminalState.COMPLETED, 80),
            terminal("run-2", "conv-1", RunTerminalState.FAILED, 100),
        )
        testScheduler.runCurrent()
        assertEquals(50L, store.seenRows().single { it.runId == "run-1" }.versionMs)
        assertTrue(store.seenRows().any { it.runId == "run-2" && it.versionMs == 100L })
        job.cancel()
    }

    @Test
    fun consumeWhileOpenNavigationConsumesLaterVersionOfSameRun() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.COMPLETED, 80))
        val center = center(store = store, now = { 10 }) { "t" }
        center.rememberOpenedNotice(
            ConversationActivityNotice(
                conversationId = "conv-1",
                runId = "run-1",
                title = "t",
                kind = ConversationActivityKind.COMPLETED,
                sinceMs = 50,
                versionMs = 50,
            ),
        )
        val job = launch { center.consumeWhileOpen("conv-1") }
        testScheduler.runCurrent()
        assertEquals(50L, store.seenRows().single().versionMs)
        store.setTerminals(terminal("run-1", "conv-1", RunTerminalState.FAILED, 120))
        testScheduler.runCurrent()
        assertEquals(120L, store.seenRows().single().versionMs)
        job.cancel()
    }

    @Test
    fun consumeWhileOpenStopsAfterCancelAndResumeFirstFrameMarksVisibleEvents() = runTest {
        val store = FakeRunTerminalStore()
        val waiting = terminal("waiting", "conv-1", RunTerminalState.WAITING_USER, 50)
        store.setTerminals(waiting)
        val center = center(store = store, now = { 10 }) { "t" }
        val first = launch { center.consumeWhileOpen("conv-1") }
        testScheduler.runCurrent()
        assertTrue(store.seenRows().any { it.runId == "waiting" })
        first.cancel()
        testScheduler.runCurrent()
        store.setTerminals(waiting, terminal("done", "conv-1", RunTerminalState.COMPLETED, 90))
        testScheduler.runCurrent()
        assertFalse(store.seenRows().any { it.runId == "done" })
        val resumed = launch { center.consumeWhileOpen("conv-1") }
        testScheduler.runCurrent()
        assertTrue(store.seenRows().any { it.runId == "done" && it.versionMs == 90L })
        resumed.cancel()
    }

    @Test
    fun currentConversationExclusionLeavesOtherNotices() = runTest {
        val store = FakeRunTerminalStore()
        store.setTerminals(
            terminal("run-a", "conv-a", RunTerminalState.FAILED, 40),
            terminal("run-b", "conv-b", RunTerminalState.COMPLETED, 50),
        )
        val notices = center(store = store, now = { 10 }).notices(excluding = "conv-a").first()
        assertEquals(listOf("conv-b"), notices.map { it.conversationId })
    }

    private fun registryWith(vararg runs: Triple<String, String, Job>): RunOwnershipRegistry {
        val registry = RunOwnershipRegistry()
        runs.forEach { (assistantId, conversationId, job) ->
            registry.register(assistantId, conversationId, "run-$conversationId", job)
        }
        return registry
    }

    private fun center(
        registry: RunOwnershipRegistry = RunOwnershipRegistry(),
        store: RunTerminalStore = FakeRunTerminalStore(),
        now: () -> Long = { 1_000L },
        titleProvider: suspend (String) -> String? = { it },
    ) = ConversationActivityCenter(registry, store, now, titleProvider)

    private fun terminal(
        runId: String,
        conversationId: String,
        state: RunTerminalState,
        updatedAtMs: Long,
    ) = RunTerminal(
        runId = runId,
        conversationId = conversationId,
        assistantId = "a",
        state = state,
        pauseReason = null,
        startedAtMs = updatedAtMs,
        updatedAtMs = updatedAtMs,
        finishedAtMs = if (state.isTerminal) updatedAtMs else null,
    )
}

private class FakeRunTerminalStore : RunTerminalStore {
    private val terminals = MutableStateFlow<List<RunTerminal>>(emptyList())
    private val seen = MutableStateFlow<List<ConversationActivitySeen>>(emptyList())
    private var baselineMs: Long? = null

    fun setTerminals(vararg items: RunTerminal) {
        terminals.value = items.toList()
    }

    fun seenRows(): List<ConversationActivitySeen> = seen.value

    override suspend fun begin(runId: String, conversationId: String, assistantId: String?) =
        unused()

    override suspend fun pause(runId: String, state: RunTerminalState, reason: PauseReason?) =
        unused()

    override suspend fun finish(runId: String, state: RunTerminalState, reason: PauseReason?) =
        unused()

    override suspend fun cancelWaitingUser(runId: String, conversationId: String): Boolean =
        unused()

    override suspend fun get(runId: String): RunTerminal? = unused()

    override suspend fun activeForConversation(conversationId: String): RunTerminal? = unused()

    override suspend fun unfinished(): List<RunTerminal> = unused()

    override fun observeActivity(): Flow<List<RunTerminal>> = terminals

    override fun observeActivitySeen(): Flow<List<ConversationActivitySeen>> = seen

    override suspend fun ensureActivityBaseline(nowMs: Long): Long {
        if (baselineMs == null) baselineMs = nowMs
        return baselineMs!!
    }

    override suspend fun markActivitySeen(
        runId: String,
        conversationId: String,
        versionMs: Long,
    ): Boolean {
        val owner = terminals.value.find { it.runId == runId } ?: return false
        if (owner.conversationId != conversationId) return false
        if (versionMs > owner.activityVersionMs) return false
        val existing = seen.value.find { it.runId == runId }
        if (existing == null) {
            seen.value = seen.value + ConversationActivitySeen(runId, conversationId, versionMs)
            return true
        }
        if (existing.conversationId != conversationId) return false
        if (existing.versionMs < versionMs) {
            seen.value = seen.value.map { row ->
                if (row.runId == runId) {
                    row.copy(conversationId = conversationId, versionMs = versionMs)
                } else {
                    row
                }
            }
            return true
        }
        return false
    }

    private fun unused(): Nothing = error("unused in activity-center tests")
}
