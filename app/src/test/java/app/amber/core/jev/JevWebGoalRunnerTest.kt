package app.amber.core.jev

import app.amber.core.settings.Settings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 有界网页目标循环：完成核验、unknown 终止、shadow 不执行、步数耗尽。 */
class JevWebGoalRunnerTest {

    private open class FakeDriver : WebGoalDriver {
        var performed = 0
        var lastAction: WebGoalAction? = null
        var state: JsonObject = buildJsonObject {
            put("title", "Example")
            put("url", "https://example.com")
            put("snapshot_id", "snap-1")
        }

        override suspend fun pageState(): JsonObject = state

        override suspend fun interactiveElements(max: Int): List<WebGoalElement> = listOf(
            WebGoalElement(ref = "e1", snapshotId = "snap-1", label = "Open article"),
            WebGoalElement(ref = "e2", snapshotId = "snap-1", label = "Search input"),
        )

        open suspend fun performActionImpl(): JsonObject = buildJsonObject {
            put("dispatched", true)
            put("status", "ok")
            put("snapshot_id_after", "snap-2")
            put("page_changed", true)
        }

        override suspend fun performAction(action: WebGoalAction): JsonObject {
            performed++
            lastAction = action
            return performActionImpl()
        }
    }

    private class ScriptedTransport(private val script: MutableList<String>, val safetyProbability: Double = 0.99) : JevTransport {
        var calls = 0

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            if (request.body.contains("\"safe_action\"")) {
                return JevTransportResponse.Http(200, """{"answers":{"safe_action":{"type":"noul","noul":$safetyProbability}}}""".toByteArray(), null)
            }
            val body = script.removeFirstOrNull() ?: error("no scripted response for call $calls")
            return JevTransportResponse.Http(200, body.toByteArray(), null)
        }
    }

    private fun runtime(transport: JevTransport, mode: JevMode): JevRuntime {
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.WEB_AUTOMATION to mode),
                dataScopes = setOf(JevDataScope.WEB_CONTENT, JevDataScope.TASK_TEXT),
            ),
        )
        return JevRuntime(
            coordinator = JevDecisionCoordinator(
                client = JevClient(transport = transport),
                apiKeyProvider = { "key" },
                clock = { 1_000_000L },
            ),
            settingsProvider = { settings },
        )
    }

    /** action/targets/done_check 全量答案（解码缺题即失败）。 */
    private fun response(action: String, doneCheck: Double, target0: Double = 0.0, target1: Double = 0.0): String =
        """{"model":"jev-test","answers":{
            "action":{"type":"choice","choice":"$action","confidence":0.8},
            "target_0":{"type":"noul","noul":$target0},
            "target_1":{"type":"noul","noul":$target1},
            "done_check":{"type":"noul","noul":$doneCheck}
        }}"""

    @Test
    fun doneWithVerifiedCheckCompletes() = runTest {
        val transport = ScriptedTransport(mutableListOf(response("done", 0.9)))
        val driver = FakeDriver()
        val outcome = JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
            driver = driver,
            goal = "open the sign-in page",
            texts = emptyList(),
            dryRun = false,
            runKey = "run-1",
        )
        assertEquals("completed", outcome.status)
        assertEquals(0, driver.performed)
        assertEquals(1, transport.calls)
    }

    @Test
    fun doneWithoutVerificationHandsBack() = runTest {
        val transport = ScriptedTransport(mutableListOf(response("done", 0.2)))
        val outcome = JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
            FakeDriver(), "goal", emptyList(), dryRun = false, runKey = null,
        )
        assertEquals("handback", outcome.status)
        assertEquals("done_not_verified", outcome.reason)
    }

    @Test
    fun unknownReceiptStopsWithoutReplay() = runTest {
        val transport = ScriptedTransport(
            mutableListOf(
                response("click", 0.1, target0 = 0.9),
                response("click", 0.1, target0 = 0.9),
            ),
        )
        val driver = object : FakeDriver() {
            override suspend fun performActionImpl(): JsonObject = buildJsonObject {
                put("dispatched", true)
                put("status", "unknown")
                put("may_have_applied", true)
            }
        }
        val outcome = JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
            driver, "goal", emptyList(), dryRun = false, runKey = null,
        )
        assertEquals("outcome_unknown", outcome.status)
        assertEquals(1, driver.performed)
    }

    @Test
    fun shadowModeProducesTraceOnly() = runTest {
        val transport = ScriptedTransport(mutableListOf(response("click", 0.1, target0 = 0.9)))
        val driver = FakeDriver()
        val outcome = JevWebGoalRunner(runtime(transport, JevMode.SHADOW)).run(
            driver, "goal", emptyList(), dryRun = false, runKey = null,
        )
        assertEquals("shadow_trace", outcome.status)
        assertEquals(0, driver.performed)
        assertTrue(outcome.steps.single().detail!!.contains("shadow"))
    }

    @Test
    fun stepsExhaustedAfterBound() = runTest {
        val responses = (1..3).map { response("scroll_down", 0.1) }
        val transport = ScriptedTransport(responses.toMutableList())
        val driver = FakeDriver()
        val outcome = JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
            driver, "goal", emptyList(), maxSteps = 2, maxDurationMs = 15_000, dryRun = false, runKey = null,
        )
        assertEquals("steps_exhausted", outcome.status)
        assertEquals(2, driver.performed)
        assertEquals(2, transport.calls)
    }

    @Test
    fun requiresHumanStopsImmediately() = runTest {
        val driver = FakeDriver()
        driver.state = buildJsonObject {
            put("title", "t")
            put("requires_human", true)
            put("resume_condition", "resolve_js_dialog:1")
        }
        val transport = ScriptedTransport(mutableListOf(response("done", 0.9)))
        val outcome = JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
            driver, "goal", emptyList(), dryRun = false, runKey = null,
        )
        assertEquals("needs_user_action", outcome.status)
        assertEquals(0, transport.calls)
    }

    @Test
    fun offModeDisabledWithoutNetwork() = runTest {
        val transport = ScriptedTransport(mutableListOf(response("done", 0.9)))
        val outcome = JevWebGoalRunner(runtime(transport, JevMode.OFF)).run(
            FakeDriver(), "goal", emptyList(), dryRun = false, runKey = null,
        )
        assertEquals("disabled", outcome.status)
        assertEquals(0, transport.calls)
    }
    @Test
    fun riskyClickTargetsHandBackEvenWhenJevRanksThemHighest() = runTest {
        for (label in listOf("Send", "Pay", "Delete", "Sign in", "Account settings")) {
            val driver = object : FakeDriver() {
                override suspend fun interactiveElements(max: Int) = listOf(
                    WebGoalElement("danger", "snap-1", label),
                    WebGoalElement("safe", "snap-1", "Open article"),
                )
            }
            val transport = ScriptedTransport(mutableListOf(response("click", 0.0, target0 = 0.99)))
            val outcome = JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
                driver, "inspect this page", emptyList(), maxSteps = 1, dryRun = false, runKey = null,
            )
            assertEquals("blocked target: $label", 0, driver.performed)
            assertTrue(outcome.status in setOf("handback", "needs_user_action"))
        }
    }

    @Test
    fun switchingActiveToShadowBetweenStepsStopsDispatch() = runTest {
        val purpose = JevPurpose.WEB_AUTOMATION
        var settings = Settings(jev = JevSetting(
            enabled = true, purposes = mapOf(purpose to JevMode.ACTIVE),
            dataScopes = purpose.requiredScopes,
        ))
        val transport = ScriptedTransport(MutableList(2) { response("click", 0.0, target0 = 0.99) })
        val changingRuntime = JevRuntime(
            coordinator = JevDecisionCoordinator(JevClient(transport), { "key" }, clock = { 1_000_000L }),
            settingsProvider = { settings },
        )
        val driver = object : FakeDriver() {
            override suspend fun performActionImpl(): JsonObject {
                settings = settings.copy(jev = settings.jev.copy(purposes = mapOf(purpose to JevMode.SHADOW)))
                return super.performActionImpl()
            }
        }
        val outcome = JevWebGoalRunner(changingRuntime).run(
            driver, "inspect page", emptyList(), maxSteps = 2, dryRun = false, runKey = null,
        )
        assertEquals(1, driver.performed)
        assertEquals("handback", outcome.status)
        assertEquals("configuration_changed", outcome.reason)
    }

    @Test
    fun shadowAndDryRunDoneOnlyProduceTrace() = runTest {
        for ((mode, dryRun) in listOf(JevMode.SHADOW to false, JevMode.ACTIVE to true)) {
            val driver = FakeDriver()
            val transport = ScriptedTransport(mutableListOf(response("done", 0.99)))
            val outcome = JevWebGoalRunner(runtime(transport, mode)).run(
                driver, "goal", emptyList(), dryRun = dryRun, runKey = null,
            )
            assertEquals("shadow_trace", outcome.status)
            assertEquals(0, driver.performed)
        }
    }

    @Test
    fun successfulUnchangedScrollingStopsAtNoProgressBudget() = runTest {
        val transport = ScriptedTransport(MutableList(12) { response("scroll_down", 0.0) })
        val driver = object : FakeDriver() {
            override suspend fun performActionImpl() = buildJsonObject {
                put("status", "ok")
                put("dispatched", true)
                put("page_changed", false)
                put("snapshot_id_before", "snap-1")
                put("snapshot_id_after", "snap-1")
            }
        }
        val outcome = JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
            driver, "scroll to next content", emptyList(), maxSteps = 12, dryRun = false, runKey = null,
        )
        assertEquals(JevWebGoalRunner.MAX_NO_PROGRESS, driver.performed)
        assertEquals("handback", outcome.status)
        assertEquals("no_progress", outcome.reason)
    }

    @Test
    fun selectedNavigationActionWithUnclearSafetyHandsBack() = runTest {
        val driver = FakeDriver()
        val transport = ScriptedTransport(mutableListOf(response("click", 0.0, target0 = 0.99)), safetyProbability = 0.1)
        val outcome = JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
            driver, "read article", emptyList(), maxSteps = 1, dryRun = false, runKey = null,
        )
        assertEquals(0, driver.performed)
        assertEquals("action_not_read_only", outcome.reason)
    }

    @Test
    fun suppliedSearchTextCanBeTypedIntoAnEditableTarget() = runTest {
        val driver = object : FakeDriver() {
            override suspend fun interactiveElements(max: Int) = listOf(
                WebGoalElement("search", "snap-1", "Search", role = "searchbox", tag = "input", inputType = "search"),
                WebGoalElement("article", "snap-1", "Open article", role = "link", tag = "a"),
            )
        }
        val typeResponse = response("type", 0.0, target0 = 0.99).replace(
            "\"done_check\"", "\"text\":{\"type\":\"choice\",\"choice\":\"t0\",\"confidence\":0.99},\"done_check\"",
        )
        val transport = ScriptedTransport(mutableListOf(typeResponse))
        JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
            driver, "fill search", listOf("Amber"), maxSteps = 1, dryRun = false, runKey = null,
        )
        assertEquals(1, driver.performed)
        assertEquals(2, transport.calls)
    }

    @Test
    fun typeCannotChooseANonEditableButton() = runTest {
        val transport = ScriptedTransport(mutableListOf(response("type", 0.0, target0 = 0.99).replace(
            "\"done_check\"", "\"text\":{\"type\":\"choice\",\"choice\":\"t0\",\"confidence\":0.99},\"done_check\"",
        )))
        val driver = FakeDriver()
        val outcome = JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
            driver, "fill draft", listOf("Amber"), maxSteps = 1, dryRun = false, runKey = null,
        )
        assertEquals(0, driver.performed)
        assertEquals("unsafe_target", outcome.reason)
    }

    @Test
    fun ordinaryInputButtonCanNavigateAfterSelectedSafetyPasses() = runTest {
        val driver = object : FakeDriver() {
            override suspend fun interactiveElements(max: Int) = listOf(
                WebGoalElement("next", "snap-1", "Next", role = "button", tag = "input", inputType = "button"),
                WebGoalElement("article", "snap-1", "Open article", role = "link", tag = "a"),
            )
        }
        val transport = ScriptedTransport(mutableListOf(response("click", 0.0, target0 = 0.99)))
        JevWebGoalRunner(runtime(transport, JevMode.ACTIVE)).run(
            driver, "open the next article", emptyList(), maxSteps = 1, dryRun = false, runKey = null,
        )
        assertEquals(1, driver.performed)
        assertEquals("next", (driver.lastAction as WebGoalAction.Click).element.ref)
        assertEquals("choice and selected safety were both evaluated", 2, transport.calls)
    }

}
