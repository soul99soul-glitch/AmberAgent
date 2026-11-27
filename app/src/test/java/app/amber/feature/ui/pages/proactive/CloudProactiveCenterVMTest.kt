package app.amber.feature.ui.pages.proactive

import app.amber.agent.data.db.dao.ProactiveCardDao
import app.amber.agent.data.db.entity.ProactiveCardEntity
import app.amber.feature.macgateway.CloudProactiveMonitorRecord
import app.amber.feature.macgateway.CloudProactiveMutedTopic
import app.amber.feature.macgateway.CloudProactiveRepository
import app.amber.feature.macgateway.CloudProactiveRestraintPatch
import app.amber.feature.macgateway.CloudProactiveRestraintSnapshot
import app.amber.feature.macgateway.CloudProactiveRunHistoryEntry
import app.amber.feature.macgateway.CloudProactiveSummary
import app.amber.feature.macgateway.CloudProactiveWakeupRecord
import app.amber.feature.macgateway.CloudProactiveWebhookRecord
import app.amber.feature.macgateway.MacGatewayClient
import app.amber.feature.macgateway.MacGatewayConnection
import app.amber.feature.macgateway.MacGatewayException
import app.amber.feature.macgateway.ProactiveContract
import app.amber.feature.macgateway.ProactiveDeepLinkInbox
import app.amber.feature.macgateway.ProactiveFeedbackAction
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudProactiveCenterVMTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeCenterGateway(
        var summaryResponse: CloudProactiveSummary = sampleSummary(),
    ) : CloudProactiveCenterGateway {
        var waitingSummary: CompletableDeferred<CloudProactiveSummary>? = null
        var summaryReturns = 0
        var summaryCalls = 0
        var summaryError: Throwable? = null
        var restraintError: Throwable? = null
        var unmuteError: Throwable? = null
        var cancelWakeupError: Throwable? = null
        var deliveryModeError: Throwable? = null
        var createGithubError: Throwable? = null
        var ignoreP2RestraintPatch = false
        var ignoreDeliveryModePatch = false

        val appliedRestraintPatches = mutableListOf<CloudProactiveRestraintPatch>()
        val unmutedTopics = mutableListOf<String>()
        val cancelledWakeups = mutableListOf<String>()
        val createdGithubRepos = mutableListOf<Triple<String, String, String>>()
        val deletedMonitors = mutableListOf<String>()
        val deletedWebhooks = mutableListOf<String>()

        override suspend fun summary(): CloudProactiveSummary {
            summaryCalls++
            summaryError?.let { throw it }
            val response = waitingSummary?.let { withContext(NonCancellable) { it.await() } } ?: summaryResponse
            summaryReturns++
            return response
        }

        override suspend fun unmute(topicKey: String): CloudProactiveRestraintSnapshot {
            unmuteError?.let { throw it }
            unmutedTopics += topicKey
            val nextMuted = summaryResponse.restraint.mutedTopics.filterNot { it.topicKey == topicKey }
            val next = summaryResponse.restraint.copy(
                mutedTopics = nextMuted,
                mutedTopicsCount = nextMuted.size,
            )
            summaryResponse = summaryResponse.copy(restraint = next)
            return next
        }

        override suspend fun patchRestraint(patch: CloudProactiveRestraintPatch): CloudProactiveRestraintSnapshot {
            restraintError?.let { throw it }
            appliedRestraintPatches += patch
            val current = summaryResponse.restraint
            val next = current.copy(
                quietHoursEnabled = patch.quietHoursEnabled ?: current.quietHoursEnabled,
                quietHoursStart = patch.quietHoursStart ?: current.quietHoursStart,
                quietHoursEnd = patch.quietHoursEnd ?: current.quietHoursEnd,
                dailyMaxPushes = patch.dailyMaxPushes ?: current.dailyMaxPushes,
                pulseEnabled = if (ignoreP2RestraintPatch) current.pulseEnabled else (patch.pulseEnabled ?: current.pulseEnabled),
                pulseTime = if (ignoreP2RestraintPatch) current.pulseTime else (patch.pulseTime ?: current.pulseTime),
                commitmentExtractionEnabled = if (ignoreP2RestraintPatch) {
                    current.commitmentExtractionEnabled
                } else {
                    patch.commitmentExtractionEnabled ?: current.commitmentExtractionEnabled
                },
            )
            summaryResponse = summaryResponse.copy(restraint = next)
            return next
        }

        override suspend fun setMonitorEnabled(id: String, enabled: Boolean): CloudProactiveMonitorRecord {
            val target = summaryResponse.monitors.first { it.id == id }
            val updated = target.copy(enabled = enabled)
            summaryResponse = summaryResponse.copy(
                monitors = summaryResponse.monitors.map { if (it.id == id) updated else it },
            )
            return updated
        }

        override suspend fun deleteMonitor(id: String) {
            deletedMonitors += id
            summaryResponse = summaryResponse.copy(
                monitors = summaryResponse.monitors.filterNot { it.id == id },
            )
        }

        override suspend fun setMonitorDeliveryMode(id: String, mode: String): CloudProactiveMonitorRecord {
            deliveryModeError?.let { throw it }
            val target = summaryResponse.monitors.first { it.id == id }
            val updated = if (ignoreDeliveryModePatch) target else target.copy(deliveryMode = mode)
            summaryResponse = summaryResponse.copy(
                monitors = summaryResponse.monitors.map { if (it.id == id) updated else it },
            )
            return updated
        }

        override suspend fun createGithubReleaseMonitor(
            owner: String,
            repo: String,
            deliveryMode: String,
        ): CloudProactiveMonitorRecord {
            createGithubError?.let { throw it }
            createdGithubRepos += Triple(owner, repo, deliveryMode)
            val created = CloudProactiveMonitorRecord(
                id = "mon_${owner}_$repo",
                title = "$owner/$repo Releases",
                url = "https://github.com/$owner/$repo/releases.atom",
                deliveryMode = deliveryMode,
            )
            summaryResponse = summaryResponse.copy(monitors = listOf(created) + summaryResponse.monitors)
            return created
        }

        override suspend fun setWebhookEnabled(id: String, enabled: Boolean): CloudProactiveWebhookRecord {
            val target = summaryResponse.webhooks.first { it.id == id }
            val updated = target.copy(enabled = enabled)
            summaryResponse = summaryResponse.copy(
                webhooks = summaryResponse.webhooks.map { if (it.id == id) updated else it },
            )
            return updated
        }

        override suspend fun deleteWebhook(id: String) {
            deletedWebhooks += id
            summaryResponse = summaryResponse.copy(
                webhooks = summaryResponse.webhooks.filterNot { it.id == id },
            )
        }

        override suspend fun cancelWakeup(id: String) {
            cancelWakeupError?.let { throw it }
            cancelledWakeups += id
            summaryResponse = summaryResponse.copy(
                wakeups = summaryResponse.wakeups.filterNot { it.id == id },
            )
        }
    }

    private val pairing = ProactiveCenterPairing("gw-1", "dev-1", 1, "My Cloud")

    private fun createVm(gateway: FakeCenterGateway) = ProactiveCenterSession(
        CoroutineScope(testDispatcher), gateway, pairing, testDispatcher,
    ).also { it.loadSummary() }

    @Test
    fun newPairingDropsDraftsAndLateSummaryAndOldActionsStayWithTheirOriginalSession() = runTest(testDispatcher) {
        val pairings = MutableStateFlow<ProactiveCenterPairing?>(pairing)
        val late = CompletableDeferred<CloudProactiveSummary>()
        val old = FakeCenterGateway().apply { waitingSummary = late }
        val current = FakeCenterGateway(sampleSummary().copy(monitors = emptyList()))
        val vm = CloudProactiveCenterVM(pairings, { if (it == pairing) old else current }, testDispatcher)
        advanceUntilIdle()
        val originalPage = vm.session.value
        originalPage.onGithubRepoInputChanged("old/secret")
        // Same IDs and label after re-pair: generation alone invalidates the old page.
        pairings.value = pairing.copy(generation = 2)
        advanceUntilIdle()
        val newPage = vm.session.value
        late.complete(sampleSummary())
        originalPage.deleteMonitor("mon_1")
        advanceUntilIdle()

        assertEquals(1, old.summaryReturns)
        assertEquals("", newPage.uiState.value.githubRepoInput)
        assertEquals(emptyList<String>(), newPage.uiState.value.summary!!.monitors.map { it.id })
        assertNull(newPage.uiState.value.notice)
        assertTrue(current.deletedMonitors.isEmpty())
        assertTrue(old.deletedMonitors.isEmpty())
    }

    @Test
    fun loadsSummaryAndSupportsRefreshAndPreservesUnknownRunOutcome() = runTest(testDispatcher) {
        val gateway = FakeCenterGateway()
        val vm = createVm(gateway)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, gateway.summaryCalls)
        assertNotNull(state.summary)
        assertEquals(2, state.summary!!.wakeups.size)
        assertEquals("future_outcome_v4", state.summary!!.recentRuns.first().outcome)
        assertEquals("23:00", state.quietStartDraft)
        assertEquals("08:00", state.quietEndDraft)
        assertEquals("08:30", state.pulseTimeDraft)

        gateway.summaryResponse = gateway.summaryResponse.copy(
            restraint = gateway.summaryResponse.restraint.copy(pushesToday = 3),
        )
        vm.refresh()
        advanceUntilIdle()

        assertEquals(2, gateway.summaryCalls)
        assertEquals(3, vm.uiState.value.summary?.restraint?.pushesToday)
        assertFalse(vm.uiState.value.refreshing)
    }

    @Test
    fun updatesRestraintIncludingP2FieldsAndValidatesScheduleTimes() = runTest(testDispatcher) {
        val gateway = FakeCenterGateway()
        val vm = createVm(gateway)
        advanceUntilIdle()

        vm.setQuietHoursEnabled(false)
        vm.setDailyMaxPushes(150) // clamped to 100
        vm.setPulseEnabled(false)
        vm.setCommitmentExtractionEnabled(false)
        advanceUntilIdle()

        val restraint = vm.uiState.value.summary!!.restraint
        assertFalse(restraint.quietHoursEnabled)
        assertEquals(100, restraint.dailyMaxPushes)
        assertFalse(restraint.pulseEnabled)
        assertFalse(restraint.commitmentExtractionEnabled)

        // Invalid time is rejected locally without network patch.
        val patchesBefore = gateway.appliedRestraintPatches.size
        vm.onPulseTimeChanged("25:99")
        vm.saveScheduleTimes()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.scheduleValidationError)
        assertEquals(patchesBefore, gateway.appliedRestraintPatches.size)

        // Valid time updates server.
        vm.onQuietStartChanged("22:30")
        vm.onQuietEndChanged("07:15")
        vm.onPulseTimeChanged("09:00")
        vm.saveScheduleTimes()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.scheduleValidationError)
        assertEquals("22:30", vm.uiState.value.summary?.restraint?.quietHoursStart)
        assertEquals("07:15", vm.uiState.value.summary?.restraint?.quietHoursEnd)
        assertEquals("09:00", vm.uiState.value.summary?.restraint?.pulseTime)
    }

    @Test
    fun unmutesMutedTopicAndCancelsWakeupsWithSourceClassification() = runTest(testDispatcher) {
        assertEquals(WakeupSourceKind.COMMITMENT, ProactiveCenterSession.classifyWakeupSource("commitment"))
        assertEquals(WakeupSourceKind.SNOOZE, ProactiveCenterSession.classifyWakeupSource("snooze"))
        assertEquals(WakeupSourceKind.AGENT, ProactiveCenterSession.classifyWakeupSource("agent"))
        assertEquals(WakeupSourceKind.UNKNOWN, ProactiveCenterSession.classifyWakeupSource("future_kind"))

        val gateway = FakeCenterGateway()
        val vm = createVm(gateway)
        advanceUntilIdle()

        vm.unmuteTopic("watchtower:noisy")
        vm.cancelWakeup("wk_1")
        advanceUntilIdle()

        assertEquals(listOf("watchtower:noisy"), gateway.unmutedTopics)
        assertTrue(vm.uiState.value.summary!!.restraint.mutedTopics.isEmpty())
        assertEquals(listOf("wk_1"), gateway.cancelledWakeups)
        assertEquals(listOf("wk_2"), vm.uiState.value.summary!!.wakeups.map { it.id })
    }

    @Test
    fun switchesMonitorDeliveryModeTogglesAndDeletesMonitorsAndWebhooks() = runTest(testDispatcher) {
        val gateway = FakeCenterGateway()
        val vm = createVm(gateway)
        advanceUntilIdle()

        vm.setMonitorDeliveryMode("mon_1", ProactiveContract.DELIVERY_MODE_DIGEST)
        vm.setMonitorEnabled("mon_1", false)
        vm.setWebhookEnabled("wh_1", false)
        advanceUntilIdle()

        val mon = vm.uiState.value.summary!!.monitors.first { it.id == "mon_1" }
        assertEquals(ProactiveContract.DELIVERY_MODE_DIGEST, mon.deliveryMode)
        assertFalse(mon.enabled)
        assertFalse(vm.uiState.value.summary!!.webhooks.first { it.id == "wh_1" }.enabled)

        vm.deleteMonitor("mon_1")
        vm.deleteWebhook("wh_1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.summary!!.monitors.isEmpty())
        assertTrue(vm.uiState.value.summary!!.webhooks.isEmpty())
    }

    @Test
    fun rejectsInvalidGithubOwnerRepoInputsAndSubmitsValidSubscription() = runTest(testDispatcher) {
        val gateway = FakeCenterGateway()
        val vm = createVm(gateway)
        advanceUntilIdle()

        val invalidInputs = listOf(
            "",
            "onlyowner",
            "https://github.com/JetBrains/kotlin",
            "owner/repo/extra",
            "../kotlin",
            "JetBrains/..",
            "bad--owner/kotlin",
            "JetBrains/repo?x=1",
            "JetBrains / kotlin",
        )
        for (bad in invalidInputs) {
            vm.onGithubRepoInputChanged(bad)
            vm.submitGithubReleaseMonitor()
            advanceUntilIdle()
            assertNotNull("Expected validation error for '$bad'", vm.uiState.value.githubValidationError)
        }
        assertTrue("Invalid inputs must never hit gateway", gateway.createdGithubRepos.isEmpty())

        vm.onGithubRepoInputChanged("JetBrains/kotlin")
        vm.onGithubDeliveryModeChanged(ProactiveContract.DELIVERY_MODE_DIGEST)
        assertNull(vm.uiState.value.githubValidationError)
        vm.submitGithubReleaseMonitor()
        advanceUntilIdle()

        assertEquals(
            listOf(Triple("JetBrains", "kotlin", ProactiveContract.DELIVERY_MODE_DIGEST)),
            gateway.createdGithubRepos,
        )
        assertEquals("", vm.uiState.value.githubRepoInput)
        assertEquals(
            ProactiveCenterNotice.GithubSubscribed("JetBrains/kotlin"),
            vm.uiState.value.notice,
        )
        assertTrue(vm.uiState.value.summary!!.monitors.any { it.title == "JetBrains/kotlin Releases" })
    }

    @Test
    fun oldServer404OrIgnoredFieldsAreReportedHonestlyWithoutFakingSuccess() = runTest(testDispatcher) {
        // 1. Summary 404 -> UnsupportedOnServer
        val summary404Gateway = FakeCenterGateway().apply {
            summaryError = MacGatewayException.NotFound("not_found")
        }
        val vm404 = createVm(summary404Gateway)
        advanceUntilIdle()
        assertEquals(ProactiveCenterError.UnsupportedOnServer, vm404.uiState.value.loadError)
        assertTrue(ProactiveFeature.SUMMARY in vm404.uiState.value.unsupportedFeatures)

        // 2. P2/P3 endpoint 404s -> marked unsupported and local list NOT mutated falsely
        val partialGateway = FakeCenterGateway().apply {
            cancelWakeupError = MacGatewayException.NotFound("HTTP 404")
            deliveryModeError = MacGatewayException.NotFound("HTTP 404")
            createGithubError = MacGatewayException.NotFound("HTTP 404")
            ignoreP2RestraintPatch = true
        }
        val vmPartial = createVm(partialGateway)
        advanceUntilIdle()

        vmPartial.cancelWakeup("wk_1")
        advanceUntilIdle()
        assertTrue(ProactiveFeature.WAKEUPS in vmPartial.uiState.value.unsupportedFeatures)
        assertEquals(
            ProactiveCenterNotice.UnsupportedFeature(ProactiveFeature.WAKEUPS),
            vmPartial.uiState.value.notice,
        )
        assertEquals(2, vmPartial.uiState.value.summary!!.wakeups.size)

        vmPartial.setMonitorDeliveryMode("mon_1", ProactiveContract.DELIVERY_MODE_DIGEST)
        advanceUntilIdle()
        assertTrue(ProactiveFeature.MONITOR_DELIVERY_MODE in vmPartial.uiState.value.unsupportedFeatures)
        assertEquals(
            ProactiveContract.DELIVERY_MODE_IMMEDIATE,
            vmPartial.uiState.value.summary!!.monitors.first { it.id == "mon_1" }.deliveryMode,
        )

        vmPartial.onGithubRepoInputChanged("JetBrains/kotlin")
        vmPartial.submitGithubReleaseMonitor()
        advanceUntilIdle()
        assertTrue(ProactiveFeature.CREATE_GITHUB_MONITOR in vmPartial.uiState.value.unsupportedFeatures)

        // Old server returning 200 but ignoring P2 pulseEnabled field -> detected honestly
        vmPartial.setPulseEnabled(false)
        advanceUntilIdle()
        assertTrue(ProactiveFeature.RESTRAINT_P2 in vmPartial.uiState.value.unsupportedFeatures)
        assertTrue(vmPartial.uiState.value.summary!!.restraint.pulseEnabled)
    }

    @Test
    fun detailSheetLoadsRemoteSignalReportsOpenedOnceAndNeverInsertsIntoDao() = runBlocking {
        val feedbackCalls = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.bufferedReader().readText()
            val (status, resp) = when {
                path == "/v2/proactive/signals/sig_remote_detail" ->
                    200 to """{"event":{"seq":91,"id":"proactive:sig_remote_detail","kind":"proactive_message","role":"assistant","text":"远端详情正文","topicId":"proactive:sig_remote_detail","proactive":{"signalId":"sig_remote_detail","topicKey":"watchtower:x","category":"WATCHTOWER","title":"远端详情","urgency":"urgent","actions":["act","snooze"]}}}"""
                path == "/v2/proactive/signals/sig_remote_detail/feedback" -> {
                    feedbackCalls += body
                    200 to """{"ok":true,"action":"opened","applied":true}"""
                }
                path == "/v2/proactive/signals/sig_404" ->
                    404 to """{"error":"signal_not_found"}"""
                else -> 500 to """{"error":"unexpected"}"""
            }
            val bytes = resp.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val dao = InMemoryProactiveDao()
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            val repo = CloudProactiveRepository(
                dao = dao,
                gateway = null,
                clientProviderOverride = { client },
                connectionProviderOverride = { conn },
                generationProviderOverride = { 1L },
                activeConversationIdProviderOverride = { "conv-cloud-main" },
                sendCloudMainMessageOverride = { _, _ -> true },
            )

            val inbox = ProactiveDeepLinkInbox()
            inbox.submit("sig_remote_detail")
            assertEquals("sig_remote_detail", inbox.pending.value?.signalId)

            val session = ProactiveDetailSession(
                loadSignal = repo::loadSignal,
                sendOpenedFeedback = { card ->
                    repo.sendFeedback(card.gatewayId, card.signalId, ProactiveFeedbackAction.OPENED, card.deviceId)
                },
            )

            // First load succeeds, sends opened once, does NOT insert into DAO / timeline.
            val first = session.load("sig_remote_detail")
            assertTrue(first is ProactiveDetailLoadState.Loaded)
            assertEquals("远端详情", (first as ProactiveDetailLoadState.Loaded).card.meta.title)
            assertTrue(feedbackCalls.isEmpty())
            session.opened(first.card)
            assertEquals(1, feedbackCalls.size)
            assertTrue(feedbackCalls.first().contains("\"opened\""))
            assertTrue("Detail sheet load must never insert into DAO", dao.rows.value.isEmpty())

            // Reloading the same signal in the same detail session does NOT duplicate opened feedback.
            val second = session.load("sig_remote_detail")
            assertTrue(second is ProactiveDetailLoadState.Loaded)
            session.opened((second as ProactiveDetailLoadState.Loaded).card)
            assertEquals(1, feedbackCalls.size)

            // Missing signal returns NotFound cleanly.
            val missing = session.load("sig_404")
            assertEquals(ProactiveDetailLoadState.NotFound, missing)

            // Closing consumes pending inbox slot.
            inbox.consume(app.amber.feature.macgateway.ProactiveDetailRequest("sig_remote_detail", null, null))
            assertNull(inbox.pending.value)
        } finally {
            server.stop(0)
        }
    }

    private class InMemoryProactiveDao : ProactiveCardDao {
        val rows = MutableStateFlow<List<ProactiveCardEntity>>(emptyList())

        override suspend fun insert(entity: ProactiveCardEntity): Long {
            rows.value = rows.value + entity
            return rows.value.size.toLong()
        }

        override fun observeForGateway(gatewayId: String, deviceId: String): Flow<List<ProactiveCardEntity>> =
            rows.map { list -> list.filter { it.gatewayId == gatewayId && it.deviceId == deviceId } }

        override suspend fun findBySignal(gatewayId: String, deviceId: String, signalId: String): ProactiveCardEntity? =
            rows.value.firstOrNull { it.gatewayId == gatewayId && it.deviceId == deviceId && it.signalId == signalId }

        override suspend fun markNotified(gatewayId: String, deviceId: String, signalId: String, notifiedAt: Long): Int = 0
        override suspend fun setFeedback(gatewayId: String, deviceId: String, signalId: String, feedbackState: String): Int = 0
    }

    companion object {
        private fun sampleSummary() = CloudProactiveSummary(
            monitors = listOf(
                CloudProactiveMonitorRecord(
                    id = "mon_1",
                    title = "Amber Releases",
                    url = "https://github.com/example/amber/releases.atom",
                    intervalMinutes = 30,
                    enabled = true,
                    deliveryMode = ProactiveContract.DELIVERY_MODE_IMMEDIATE,
                ),
            ),
            webhooks = listOf(
                CloudProactiveWebhookRecord(
                    id = "wh_1",
                    name = "CI Failure",
                    enabled = true,
                    instruction = "Summarize failing build",
                    triggerCount = 4,
                ),
            ),
            restraint = CloudProactiveRestraintSnapshot(
                quietHoursEnabled = true,
                quietHoursStart = "23:00",
                quietHoursEnd = "08:00",
                dailyMaxPushes = 5,
                pushesToday = 1,
                remainingToday = 4,
                bufferedPulseCount = 2,
                mutedTopicsCount = 1,
                pulseEnabled = true,
                pulseTime = "08:30",
                commitmentExtractionEnabled = true,
                mutedTopics = listOf(
                    CloudProactiveMutedTopic(topicKey = "watchtower:noisy", untilEpochMs = 1_800_000_000_000L),
                ),
            ),
            recentRuns = listOf(
                CloudProactiveRunHistoryEntry(
                    id = "run_1",
                    signalId = "sig_1",
                    category = "WATCHTOWER",
                    topicKey = "watchtower:1",
                    title = "Release Check",
                    outcome = "future_outcome_v4",
                    summary = "Detected new tag",
                    timestampEpochMs = 1_700_000_000_000L,
                ),
            ),
            wakeups = listOf(
                CloudProactiveWakeupRecord(
                    id = "wk_1",
                    kind = "commitment",
                    title = "Follow up PR review",
                    triggerAtEpochMs = 1_750_000_000_000L,
                ),
                CloudProactiveWakeupRecord(
                    id = "wk_2",
                    kind = "snooze",
                    title = "Snoozed alert",
                    triggerAtEpochMs = 1_750_003_600_000L,
                ),
            ),
        )
    }
}
