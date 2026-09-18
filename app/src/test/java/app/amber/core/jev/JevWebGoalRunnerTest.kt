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
        var state: JsonObject = buildJsonObject {
            put("title", "Example")
            put("url", "https://example.com")
            put("snapshot_id", "snap-1")
        }

        override suspend fun pageState(): JsonObject = state

        override suspend fun interactiveElements(max: Int): List<WebGoalElement> = listOf(
            WebGoalElement(ref = "e1", snapshotId = "snap-1", label = "Sign in button"),
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
            return performActionImpl()
        }
    }

    private class ScriptedTransport(private val script: MutableList<String>) : JevTransport {
        var calls = 0

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
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
}
