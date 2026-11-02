package app.amber.feature.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room query + persisted watermark CAS for the activity center.
 * Robolectric / in-memory DB; device migration stays for the unified last round.
 */
class ConversationActivityWatermarkStoreTest : DurableRuntimeTestBase() {

    @Test
    fun reviewWrongConversationCannotMarkExistingRunSeen() = runBlocking {
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { 50L })
        store.begin("real-run", "real-conversation", "a")
        store.pause("real-run", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
        assertFalse(store.markActivitySeen("real-run", "wrong-conversation", 50L))
        val center = ConversationActivityCenter(RunOwnershipRegistry(), store, now = { 10L }) { "title" }
        assertEquals("real-run", center.notices(excluding = null).first().single().runId)
    }

    @Test
    fun observeActivityOmitsCancelledAndKeepsAttentionStates() = runBlocking {
        var now = 1_000L
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { now })
        store.begin("run_live", "conv_1", "a")
        now = 1_100L
        store.begin("run_wait", "conv_1", "a")
        now = 1_200L
        store.pause("run_wait", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)

        store.begin("run_done", "conv_2", "a")
        now = 1_300L
        store.finish("run_done", RunTerminalState.COMPLETED)

        store.begin("run_fail", "conv_3", "a")
        now = 1_400L
        store.finish("run_fail", RunTerminalState.STEP_LIMIT, PauseReason.STEP_LIMIT_EXHAUSTED)

        store.begin("run_cancel", "conv_4", "a")
        now = 1_500L
        store.finish("run_cancel", RunTerminalState.CANCELLED, PauseReason.USER_STOP)

        val rows = store.observeActivity().first { list -> list.any { it.runId == "run_fail" } }
        val byId = rows.associateBy { it.runId }
        assertNotNull(byId["run_wait"])
        assertNotNull(byId["run_done"])
        assertNotNull(byId["run_fail"])
        assertFalse(byId.containsKey("run_cancel"))
        assertEquals(RunTerminalState.STEP_LIMIT, byId.getValue("run_fail").state)
    }

    @Test
    fun baselineIsWriteOnceAndPersistsAcrossStoreRestart() = runBlocking {
        val dao = database.runTerminalDao()
        val first = RoomRunTerminalStore(dao = dao, now = { 1_000L })
        assertEquals(5_000L, first.ensureActivityBaseline(5_000L))
        assertEquals(5_000L, first.ensureActivityBaseline(9_000L))
        val restarted = RoomRunTerminalStore(dao = dao, now = { 9_000L })
        assertEquals(5_000L, restarted.ensureActivityBaseline(12_000L))
    }

    @Test
    fun markSeenCasMovesForwardAndRejectsOlderVersion() = runBlocking {
        var now = 50L
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { now })
        store.begin("run_1", "conv_1", "a")
        now = 80L
        store.finish("run_1", RunTerminalState.COMPLETED)
        assertTrue(store.markActivitySeen("run_1", "conv_1", 50))
        assertFalse(store.markActivitySeen("run_1", "conv_1", 40))
        assertEquals(50L, database.runTerminalDao().getActivitySeen("run_1")!!.versionMs)
        assertTrue(store.markActivitySeen("run_1", "conv_1", 80))
        assertEquals(80L, database.runTerminalDao().getActivitySeen("run_1")!!.versionMs)
        assertEquals("conv_1", database.runTerminalDao().getActivitySeen("run_1")!!.conversationId)
    }

    @Test
    fun mismatchedRunIdLeavesOtherWatermarkUntouched() = runBlocking {
        var now = 50L
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { now })
        store.begin("run_real", "conv_1", "a")
        store.finish("run_real", RunTerminalState.COMPLETED)
        now = 99L
        store.begin("run_other", "conv_1", "a")
        store.finish("run_other", RunTerminalState.COMPLETED)
        assertTrue(store.markActivitySeen("run_real", "conv_1", 50))
        assertTrue(store.markActivitySeen("run_other", "conv_1", 99))
        assertEquals(50L, database.runTerminalDao().getActivitySeen("run_real")!!.versionMs)
        assertEquals(99L, database.runTerminalDao().getActivitySeen("run_other")!!.versionMs)
    }

    @Test
    fun markSeenRejectsMissingRunAndDoesNotMintWatermark() = runBlocking {
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { 50L })
        assertFalse(store.markActivitySeen("ghost-run", "conv_1", 50L))
        assertNull(database.runTerminalDao().getActivitySeen("ghost-run"))
    }

    @Test
    fun markSeenRejectsFutureVersionAboveDurableRun() = runBlocking {
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { 50L })
        store.begin("run_1", "conv_1", "a")
        store.pause("run_1", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
        assertFalse(store.markActivitySeen("run_1", "conv_1", 9_999L))
        assertNull(database.runTerminalDao().getActivitySeen("run_1"))
        assertTrue(store.markActivitySeen("run_1", "conv_1", 50L))
        assertEquals(50L, database.runTerminalDao().getActivitySeen("run_1")!!.versionMs)
    }

    @Test
    fun markSeenRejectsRegistryOnlyRunningWithoutBegin() = runBlocking {
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { 50L })
        val registry = RunOwnershipRegistry()
        registry.register("a", "conv_1", "run_live", Job())
        assertFalse(store.markActivitySeen("run_live", "conv_1", 50L))
        assertNull(database.runTerminalDao().getActivitySeen("run_live"))
    }

    @Test
    fun markSeenConcurrentCasKeepsHighestOwnedVersion() = runBlocking {
        var now = 50L
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { now })
        store.begin("run_cas", "conv_cas", "a")
        now = 80L
        store.finish("run_cas", RunTerminalState.COMPLETED)
        coroutineScope {
            val first = async(Dispatchers.IO) { store.markActivitySeen("run_cas", "conv_cas", 60L) }
            val second = async(Dispatchers.IO) { store.markActivitySeen("run_cas", "conv_cas", 80L) }
            first.await()
            second.await()
        }
        assertEquals(80L, database.runTerminalDao().getActivitySeen("run_cas")!!.versionMs)
        assertEquals("conv_cas", database.runTerminalDao().getActivitySeen("run_cas")!!.conversationId)
    }

    @Test
    fun persistedWatermarkSurvivesNewStoreAndHidesCompletedUntilNewRun() = runBlocking {
        var now = 1_000L
        val dao = database.runTerminalDao()
        val store = RoomRunTerminalStore(dao = dao, now = { now })
        store.begin("run_1", "conv_1", "a")
        now = 1_500L
        store.finish("run_1", RunTerminalState.COMPLETED)
        val registry = RunOwnershipRegistry()
        val center = ConversationActivityCenter(registry, store, now = { 1_200L }) { "title" }
        val first = center.notices(excluding = null).first()
        assertEquals("run_1", first.single().runId)
        assertTrue(center.markSeen(first.single()))

        val restarted = RoomRunTerminalStore(dao = dao, now = { now })
        val afterRestart = ConversationActivityCenter(
            RunOwnershipRegistry(),
            restarted,
            now = { 1_200L },
        ) { "title" }
        assertTrue(afterRestart.notices(excluding = null).first().isEmpty())

        now = 2_000L
        restarted.begin("run_2", "conv_1", "a")
        now = 2_100L
        restarted.finish("run_2", RunTerminalState.COMPLETED)
        val again = afterRestart.notices(excluding = null).first()
        assertEquals("run_2", again.single().runId)
    }

    @Test
    fun firstUpgradeDoesNotFloodHistoricalCompleted() = runBlocking {
        var now = 500L
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { now })
        store.begin("run_old", "conv_old", "a")
        now = 600L
        store.finish("run_old", RunTerminalState.COMPLETED)
        val center = ConversationActivityCenter(
            RunOwnershipRegistry(),
            store,
            now = { 1_000L },
        ) { "title" }
        assertTrue(center.notices(excluding = null).first().isEmpty())
    }

    @Test
    fun liveWaitingSurfacesEvenIfItPredatesBaseline() = runBlocking {
        var now = 500L
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { now })
        store.begin("run_wait", "conv_w", "a")
        now = 600L
        store.pause("run_wait", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
        val center = ConversationActivityCenter(
            RunOwnershipRegistry(),
            store,
            now = { 1_000L },
        ) { "title" }
        val notices = center.notices(excluding = null).first()
        assertEquals(ConversationActivityKind.AWAITING_USER, notices.single().kind)
        assertEquals("run_wait", notices.single().runId)
    }

    @Test
    fun registryRunningMergesWithoutSecondStore() = runBlocking {
        val store = RoomRunTerminalStore(dao = database.runTerminalDao(), now = { 1_000L })
        store.begin("run_1", "conv_1", "a")
        val registry = RunOwnershipRegistry()
        registry.register("a", "conv_1", "run_1", Job())
        val center = ConversationActivityCenter(registry, store, now = { 1_000L }) { "title" }
        val notices = center.notices(excluding = "conv-other").first()
        assertEquals(1, notices.size)
        assertEquals(ConversationActivityKind.RUNNING, notices.single().kind)
        assertEquals("run_1", notices.single().runId)
    }
}
