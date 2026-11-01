package app.amber.core.jev

import app.amber.core.settings.Settings
import app.amber.feature.subagent.SubAgentDefinition
import app.amber.feature.subagent.SubAgentIntentAlignment
import app.amber.feature.subagent.SubAgentTaskSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SUBAGENT_INTENT：只做 alignment，失败回落继续 spawn，不外发 systemPrompt。 */
class JevSubagentIntentTest {

    private class FakeTransport(
        private val probability: Double?,
        private val fail: Boolean = false,
    ) : JevTransport {
        var calls = 0
        val requests = mutableListOf<String>()

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            requests += request.body
            if (fail) return JevTransportResponse.Failure("offline")
            val answer = if (probability == null) {
                """"aligned":{"type":"choice","choice":"bogus","confidence":0.9}"""
            } else {
                """"aligned":{"type":"noul","noul":$probability}"""
            }
            return JevTransportResponse.Http(
                200,
                """{"model":"jev-test","answers":{$answer}}""".toByteArray(),
                null,
            )
        }
    }

    private val secretPrompt = "SECRET_SYSTEM_PROMPT_MUST_NOT_LEAVE"

    private fun definition() = SubAgentDefinition(
        id = "explorer",
        name = "Explorer",
        description = "Reads files and reports findings.",
        systemPrompt = secretPrompt,
        toolAllowlist = setOf("file_read"),
        routingHint = "Use for repository exploration.",
    )

    private fun task(objective: String = "Summarize README.md") = SubAgentTaskSpec(
        objective = objective,
        outputFormat = "markdown",
        toolsAndSources = "file_read",
        boundaries = "read only",
    )

    private fun intent(
        mode: JevMode,
        transport: FakeTransport,
        scopes: Set<JevDataScope> = setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): JevSubagentIntent {
        val settings = Settings(
            jev = JevSetting(
                enabled = mode != JevMode.OFF,
                purposes = if (mode == JevMode.OFF) emptyMap() else mapOf(JevPurpose.SUBAGENT_INTENT to mode),
                dataScopes = scopes,
            ),
        )
        return JevSubagentIntent(
            JevRuntime(
                coordinator = JevDecisionCoordinator(
                    client = JevClient(transport = transport),
                    apiKeyProvider = { "key" },
                    clock = { 1_000_000L },
                ),
                settingsProvider = { settings },
                backgroundScope = scope,
            ),
        )
    }

    private suspend fun JevSubagentIntent.check() =
        assess(definition(), task(), "run-intent")

    @Test
    fun activeAlignedAllowsSpawn() = runTest {
        val transport = FakeTransport(0.95)
        val result = intent(JevMode.ACTIVE, transport).check()
        assertTrue(result.judged)
        assertTrue(result.aligned)
        assertEquals(1, transport.calls)
        transport.requests.forEach { body ->
            assertFalse(body.contains(secretPrompt))
            assertTrue(body.contains("Explorer"))
        }
    }

    @Test
    fun activeMisalignedRefusesWithoutGrantingTools() = runTest {
        val result = intent(JevMode.ACTIVE, FakeTransport(0.1)).check()
        assertTrue(result.judged)
        assertFalse(result.aligned)
        assertTrue(result.reason!!.contains("explorer"))
    }

    @Test
    fun invalidAnswerFailsOpen() = runTest {
        val result = intent(JevMode.ACTIVE, FakeTransport(null)).check()
        assertEquals(SubAgentIntentAlignment.Result.NotJudged, result)
    }

    @Test
    fun failedDecisionFailsOpen() = runTest {
        val result = intent(JevMode.ACTIVE, FakeTransport(0.1, fail = true)).check()
        assertEquals(SubAgentIntentAlignment.Result.NotJudged, result)
    }

    @Test
    fun offNeverCallsNetwork() = runTest {
        val transport = FakeTransport(0.1)
        val result = intent(JevMode.OFF, transport).check()
        assertEquals(SubAgentIntentAlignment.Result.NotJudged, result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun shadowObservesInBackgroundAndFailsOpen() = runTest {
        val transport = FakeTransport(0.1)
        val result = intent(JevMode.SHADOW, transport, scope = backgroundScope).check()
        assertEquals(SubAgentIntentAlignment.Result.NotJudged, result)
        assertEquals(0, transport.calls)
        runCurrent()
        assertEquals(1, transport.calls)
        transport.requests.forEach { assertFalse(it.contains(secretPrompt)) }
    }

    @Test
    fun missingScopeFailsOpenWithoutNetwork() = runTest {
        val transport = FakeTransport(0.1)
        val result = intent(JevMode.ACTIVE, transport, scopes = setOf(JevDataScope.TASK_TEXT)).check()
        assertEquals(SubAgentIntentAlignment.Result.NotJudged, result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun budgetExhaustedFailsOpen() = runTest {
        val transport = FakeTransport(0.1)
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.SUBAGENT_INTENT to JevMode.ACTIVE),
                dataScopes = setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT),
            ),
        )
        val runtime = JevRuntime(
            coordinator = JevDecisionCoordinator(
                client = JevClient(transport = transport),
                apiKeyProvider = { "key" },
                clock = { 1_000_000L },
            ),
            settingsProvider = { settings },
        )
        val checker = JevSubagentIntent(runtime)
        repeat(JevLimits.PER_RUN_MAX_REQUESTS) { index ->
            runtime.decide(
                purpose = JevPurpose.SUBAGENT_INTENT,
                runKey = "run-intent",
                state = buildJsonObject { put("n", index) },
                questions = mapOf("aligned" to JevQuestion.Noul("align?")),
                requiredScopes = JevPurpose.SUBAGENT_INTENT.requiredScopes,
            )
        }
        val before = transport.calls
        val result = checker.check()
        assertEquals(SubAgentIntentAlignment.Result.NotJudged, result)
        assertEquals(before, transport.calls)
    }
}
