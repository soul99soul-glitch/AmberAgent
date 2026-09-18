package app.amber.core.jev

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevClientCodecTest {

    private val client = JevClient(transport = { throw IllegalStateException("not under test") })

    @Test
    fun encodeProducesMapKeyedQuestions() {
        val body = client.encodeRequestBody(
            model = "jev-latest",
            state = buildJsonObject { put("task", "t") },
            questions = mapOf(
                "q1" to JevQuestion.Noul("Is it relevant?", trueCriteria = "relevant to the task"),
                "q2" to JevQuestion.Choice("Pick one", options = mapOf("a" to "option a", "b" to null)),
                "q3" to JevQuestion.Score("Grade it", levels = listOf("low", "mid", "high")),
            ),
        )
        assertTrue(body.contains("\"questions\":{\"q1\""))
        assertTrue(body.contains("\"type\":\"noul\""))
        assertTrue(body.contains("\"criteria\":{\"true\":\"relevant to the task\"}"))
        assertTrue(body.contains("\"type\":\"choice\""))
        assertTrue(body.contains("\"criteria\":{\"a\":\"option a\",\"b\":null}"))
        assertTrue(body.contains("\"type\":\"score\""))
        assertTrue(body.contains("\"criteria\":[\"low\",\"mid\",\"high\"]"))
    }

    @Test
    fun decodeValidAnswersOfAllTypes() {
        val body = """
            {"model":"jev-1","usage":{"input_tokens":3,"output_tokens":2},"answers":{
              "q1":{"type":"noul","noul":0.8},
              "q2":{"type":"choice","choice":"a","probabilities":{"a":0.7,"b":0.3},"confidence":0.6},
              "q3":{"type":"score","score":1.5,"confidence":0.9}
            }}
        """.trimIndent()
        val decoded = client.decodeResponse(
            body,
            mapOf(
                "q1" to JevQuestion.Noul("q"),
                "q2" to JevQuestion.Choice("q", mapOf("a" to null, "b" to null)),
                "q3" to JevQuestion.Score("q", listOf("low", "mid", "high")),
            ),
        )
        assertEquals(0.8, (decoded.answers["q1"] as JevAnswer.Noul).probability, 0.0)
        assertEquals("a", (decoded.answers["q2"] as JevAnswer.Choice).selected)
        assertEquals(1.5, (decoded.answers["q3"] as JevAnswer.Score).score, 0.0)
        assertEquals(3, decoded.usage?.inputTokens)
        assertEquals("jev-1", decoded.model)
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsMissingAnswer() {
        client.decodeResponse("""{"answers":{}}""", mapOf("q1" to JevQuestion.Noul("q")))
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsTypeMismatch() {
        client.decodeResponse(
            """{"answers":{"q1":{"type":"choice","choice":"a"}}}""",
            mapOf("q1" to JevQuestion.Noul("q")),
        )
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsUnknownChoiceOption() {
        client.decodeResponse(
            """{"answers":{"q1":{"type":"choice","choice":"zzz"}}}""",
            mapOf("q1" to JevQuestion.Choice("q", mapOf("a" to null))),
        )
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsNoulOutOfRange() {
        client.decodeResponse(
            """{"answers":{"q1":{"type":"noul","noul":1.5}}}""",
            mapOf("q1" to JevQuestion.Noul("q")),
        )
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsNonFiniteNoul() {
        client.decodeResponse(
            """{"answers":{"q1":{"type":"noul","noul":1e999}}}""",
            mapOf("q1" to JevQuestion.Noul("q")),
        )
    }

    @Test(expected = JevDecodeException::class)
    fun decodeRejectsConfidenceOutOfRange() {
        client.decodeResponse(
            """{"answers":{"q1":{"type":"choice","choice":"a","confidence":1.2}}}""",
            mapOf("q1" to JevQuestion.Choice("q", mapOf("a" to null))),
        )
    }

    @Test
    fun parseRetryAfterSecondsOnly() {
        assertEquals(2_000L, "2".parseRetryAfterMs())
        assertNull("Wed, 21 Oct 2015 07:28:00 GMT".parseRetryAfterMs())
    }
}

private class FakeTransport(
    var responder: suspend (JevHttpRequest) -> JevTransportResponse,
) : JevTransport {
    val requests = mutableListOf<JevHttpRequest>()

    override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
        requests += request
        return responder(request)
    }
}

private fun httpOk(body: String) = JevTransportResponse.Http(200, body.toByteArray(Charsets.UTF_8), null)
private fun httpError(code: Int, retryAfter: String? = null) = JevTransportResponse.Http(code, null, retryAfter)

private fun noulSuccess(vararg probabilities: Pair<String, Double>) = httpOk(
    buildString {
        append("""{"model":"jev-test","usage":{"input_tokens":1,"output_tokens":1},"answers":{""")
        probabilities.joinTo(this) { (id, p) -> """"$id":{"type":"noul","noul":$p}""" }
        append("}}")
    },
)

private val activeConfig = JevRuntimeConfig(
    mode = JevMode.ACTIVE,
    allowedScopes = JevDataScope.entries.toSet(),
    model = "jev-test",
    policyVersion = 1,
)

private val taskState: JsonElement = buildJsonObject { put("task", "test") }

private fun noulQuestions(vararg ids: String): Map<String, JevQuestion> =
    ids.associateWith { JevQuestion.Noul("relevant?") }

class JevDecisionCoordinatorTest {

    private fun coordinator(
        transport: FakeTransport,
        key: String? = "k",
        clock: () -> Long = { 1_000_000L },
    ) = JevDecisionCoordinator(
        client = JevClient(transport = transport),
        apiKeyProvider = { key },
        usageStore = JevUsageStore.IN_MEMORY,
        clock = clock,
    )

    @Test
    fun offModeShortCircuitsWithoutNetworkOrMetrics() = runTest {
        val transport = FakeTransport { httpOk("{}") }
        val coordinator = coordinator(transport)
        val decision = coordinator.decide(
            purpose = JevPurpose.MEMORY_RECALL,
            config = activeConfig.copy(mode = JevMode.OFF),
            runKey = "run-1",
            state = taskState,
            questions = noulQuestions("q1"),
            requiredScopes = setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Skipped && decision.reason == JevSkipReason.OFF)
        assertEquals(0, transport.requests.size)
        assertEquals(0, coordinator.metrics.snapshot().size)
    }

    @Test
    fun missingScopeSkips() = runTest {
        val transport = FakeTransport { httpOk("{}") }
        val decision = coordinator(transport).decide(
            purpose = JevPurpose.MEMORY_RECALL,
            config = activeConfig.copy(allowedScopes = setOf(JevDataScope.TOOL_METADATA)),
            runKey = null,
            state = taskState,
            questions = noulQuestions("q1"),
            requiredScopes = setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Skipped && decision.reason == JevSkipReason.SCOPE_NOT_ALLOWED)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun successIsEvaluatedAndCached() = runTest {
        val transport = FakeTransport { noulSuccess("q1" to 0.9) }
        val coordinator = coordinator(transport)
        val first = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY), cacheAnchor = "anchor",
        )
        val second = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY), cacheAnchor = "anchor",
        )
        assertTrue(first is JevDecision.Evaluated && !first.fromCache)
        assertTrue(second is JevDecision.Evaluated && second.fromCache)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun authErrorPausesUntilConnectionTestResets() = runTest {
        val transport = FakeTransport { httpError(401) }
        val coordinator = coordinator(transport)
        val first = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(first is JevDecision.Failed && first.reason == JevFailureReason.AUTH)
        val second = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(second is JevDecision.Skipped && second.reason == JevSkipReason.AUTH_PAUSED)
        transport.responder = { noulSuccess("prime" to 0.99) }
        val test = coordinator.connectionTest()
        assertTrue(test.ok)
        transport.responder = { noulSuccess("q1" to 0.9) }
        val third = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(third is JevDecision.Evaluated)
    }

    @Test
    fun rateLimitWithoutRetryAfterFails() = runTest {
        val transport = FakeTransport { httpError(429) }
        val decision = coordinator(transport).decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Failed && decision.reason == JevFailureReason.RATE_LIMITED)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun transientFailureRetriesOnceThenSucceeds() = runTest {
        var calls = 0
        val transport = FakeTransport { _ ->
            calls++
            if (calls == 1) JevTransportResponse.Failure("socket reset") else noulSuccess("q1" to 0.7)
        }
        val decision = coordinator(transport).decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Evaluated)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun threeConsecutiveTransientFailuresEnterCooldown() = runTest {
        val transport = FakeTransport { JevTransportResponse.Failure("down") }
        val coordinator = coordinator(transport)
        // 第 1 次调用内部重试一次积累 2 个暂时失败；第 2 次调用的首个失败
        // 达到 3 连，进入 60s 冷却并返回失败。
        val first = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(first is JevDecision.Failed)
        val second = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-2", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(second is JevDecision.Failed)
        val cooled = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-3", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(cooled is JevDecision.Skipped && cooled.reason == JevSkipReason.COOLDOWN)
    }

    @Test
    fun decodeFailureIsTerminalWithoutRetry() = runTest {
        // 响应缺题 → 解码异常：确定性失败，不重试，报告 INVALID_RESPONSE
        val transport = FakeTransport { httpOk("""{"model":"jev-test","answers":{}}""") }
        val decision = coordinator(transport).decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Failed && decision.reason == JevFailureReason.INVALID_RESPONSE)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun perRunBudgetExhaustsAfterSixRequests() = runTest {
        val transport = FakeTransport { noulSuccess("q1" to 0.9) }
        val coordinator = coordinator(transport)
        repeat(6) {
            val decision = coordinator.decide(
                JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState,
                noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
            )
            assertTrue(decision is JevDecision.Evaluated)
        }
        val seventh = coordinator.decide(
            JevPurpose.MEMORY_RECALL, activeConfig, "run-1", taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(seventh is JevDecision.Skipped && seventh.reason == JevSkipReason.BUDGET_EXHAUSTED)
        assertEquals(6, transport.requests.size)
    }

    @Test
    fun deadlineExpiryFailsWithTimeout() = runTest {
        val transport = FakeTransport { delay(60_000); httpOk("{}") }
        val decision = coordinator(transport).decide(
            JevPurpose.MEMORY_RECALL, activeConfig, null, taskState,
            noulQuestions("q1"), setOf(JevDataScope.PERSONAL_MEMORY),
        )
        assertTrue(decision is JevDecision.Failed && decision.reason == JevFailureReason.TIMEOUT)
    }
}
