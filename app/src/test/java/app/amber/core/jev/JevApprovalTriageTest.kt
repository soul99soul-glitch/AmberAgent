package app.amber.core.jev

import app.amber.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** APPROVAL_TRIAGE：任意有效答案只能维持或收紧，不能把 ASK/高风险改成 ALLOW。 */
class JevApprovalTriageTest {

    private class FakeTransport(
        private val choice: String?,
        private val highRisk: Double = 0.0,
        private val fail: Boolean = false,
    ) : JevTransport {
        var calls = 0
        val requests = mutableListOf<String>()

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            requests += request.body
            if (fail) return JevTransportResponse.Http(500, "boom".toByteArray(), null)
            val selected = choice?.let { """"posture":{"type":"choice","choice":"$it","confidence":0.9},""" } ?: ""
            val body = """{"model":"jev-test","answers":{$selected"high_risk":{"type":"noul","noul":$highRisk}}}"""
            return JevTransportResponse.Http(200, body.toByteArray(), null)
        }
    }

    private fun triage(
        mode: JevMode,
        transport: FakeTransport,
        scopes: Set<JevDataScope> = setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): JevApprovalTriage {
        val settings = Settings(
            jev = JevSetting(
                enabled = mode != JevMode.OFF,
                purposes = if (mode == JevMode.OFF) emptyMap() else mapOf(JevPurpose.APPROVAL_TRIAGE to mode),
                dataScopes = scopes,
            ),
        )
        return JevApprovalTriage(
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

    private suspend fun JevApprovalTriage.check(
        original: JevApprovalTriage.Posture = JevApprovalTriage.Posture.ALLOW,
    ) = assess("terminal_execute", """{"command":"ls"}""", listOf("list files"), original, "run-triage")

    @Test
    fun appliedPostureNeverRelaxesAnyOriginal() {
        val postures = JevApprovalTriage.Posture.entries
        for (original in postures) {
            val none = JevApprovalTriage.appliedPosture(original, null)
            assertEquals(original, none)
            for (suggested in postures) {
                val applied = JevApprovalTriage.appliedPosture(original, suggested)
                assertTrue(
                    "original=$original suggested=$suggested applied=$applied",
                    applied.rank >= original.rank,
                )
                if (original == JevApprovalTriage.Posture.ASK) {
                    assertTrue(applied != JevApprovalTriage.Posture.ALLOW)
                }
                if (original == JevApprovalTriage.Posture.DENY) {
                    assertEquals(JevApprovalTriage.Posture.DENY, applied)
                }
                if (original != JevApprovalTriage.Posture.DENY && suggested == JevApprovalTriage.Posture.DENY) {
                    assertEquals(JevApprovalTriage.Posture.ASK, applied)
                }
            }
        }
    }

    @Test
    fun anyValidAnswerCannotSuggestAllowWhenHighRisk() {
        val choices = listOf(null, "allow", "ask", "deny", "ALLOW", "bogus", "")
        val risks = listOf(null, 0.0, 0.79, 0.8, 0.99)
        for (choice in choices) {
            for (risk in risks) {
                val suggested = JevApprovalTriage.suggestedPosture(choice, risk, 0.8)
                if (choice.isNullOrBlank() || choice.lowercase() !in setOf("allow", "ask", "deny")) {
                    assertNull(suggested)
                    continue
                }
                if ((risk ?: 0.0) >= 0.8) {
                    assertTrue(
                        "choice=$choice risk=$risk suggested=$suggested",
                        suggested != JevApprovalTriage.Posture.ALLOW,
                    )
                }
            }
        }
    }

    @Test
    fun activeAskSuggestionTightensAllow() = runTest {
        val result = triage(JevMode.ACTIVE, FakeTransport("ask", highRisk = 0.1))
            .check(JevApprovalTriage.Posture.ALLOW)
        assertEquals(JevApprovalTriage.Posture.ASK, result.applied)
        assertTrue(result.writeMetadata)
    }

    @Test
    fun activeAllowSuggestionCannotRelaxAsk() = runTest {
        val result = triage(JevMode.ACTIVE, FakeTransport("allow", highRisk = 0.0))
            .check(JevApprovalTriage.Posture.ASK)
        assertEquals(JevApprovalTriage.Posture.ASK, result.applied)
        assertEquals(JevApprovalTriage.Posture.ALLOW, result.suggested)
    }

    @Test
    fun highRiskAllowBecomesAsk() = runTest {
        val result = triage(JevMode.ACTIVE, FakeTransport("allow", highRisk = 0.9))
            .check(JevApprovalTriage.Posture.ALLOW)
        assertEquals(JevApprovalTriage.Posture.ASK, result.applied)
        assertEquals(JevApprovalTriage.Posture.ASK, result.suggested)
    }

    @Test
    fun denySuggestionOnAllowBecomesAskNotDeny() = runTest {
        val result = triage(JevMode.ACTIVE, FakeTransport("deny", highRisk = 0.1))
            .check(JevApprovalTriage.Posture.ALLOW)
        assertEquals(JevApprovalTriage.Posture.ASK, result.applied)
    }

    @Test
    fun invalidChoiceFallsBackToOriginal() = runTest {
        val result = triage(JevMode.ACTIVE, FakeTransport("nope"))
            .check(JevApprovalTriage.Posture.ALLOW)
        assertEquals(JevApprovalTriage.Posture.ALLOW, result.applied)
        assertNull(result.suggested)
        assertFalse(result.writeMetadata)
    }

    @Test
    fun failedDecisionFallsBackToOriginal() = runTest {
        val result = triage(JevMode.ACTIVE, FakeTransport("deny", fail = true))
            .check(JevApprovalTriage.Posture.ALLOW)
        assertEquals(JevApprovalTriage.Posture.ALLOW, result.applied)
        assertFalse(result.writeMetadata)
    }

    @Test
    fun offNeverCallsNetwork() = runTest {
        val transport = FakeTransport("deny", highRisk = 0.99)
        val result = triage(JevMode.OFF, transport).check()
        assertEquals(JevApprovalTriage.Posture.ALLOW, result.applied)
        assertEquals(0, transport.calls)
    }

    @Test
    fun shadowObservesInBackgroundAndDoesNotTighten() = runTest {
        val transport = FakeTransport("deny", highRisk = 0.99)
        val result = triage(JevMode.SHADOW, transport, scope = backgroundScope).check()
        assertEquals(JevApprovalTriage.Posture.ALLOW, result.applied)
        assertEquals(0, transport.calls)
        runCurrent()
        assertEquals(1, transport.calls)
    }

    @Test
    fun missingScopeFallsBackWithoutNetwork() = runTest {
        val transport = FakeTransport("deny")
        val result = triage(JevMode.ACTIVE, transport, scopes = setOf(JevDataScope.TASK_TEXT)).check()
        assertEquals(JevApprovalTriage.Posture.ALLOW, result.applied)
        assertEquals(0, transport.calls)
    }

    @Test
    fun budgetExhaustedFallsBackToOriginal() = runTest {
        val transport = FakeTransport("deny")
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.APPROVAL_TRIAGE to JevMode.ACTIVE),
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
        val gate = JevApprovalTriage(runtime)
        repeat(JevLimits.PER_RUN_MAX_REQUESTS) { index ->
            runtime.decide(
                purpose = JevPurpose.APPROVAL_TRIAGE,
                runKey = "run-triage",
                state = buildJsonObject { put("n", index) },
                questions = mapOf(
                    "posture" to JevQuestion.Choice("pick", mapOf("allow" to "a", "ask" to "b", "deny" to "c")),
                ),
                requiredScopes = JevPurpose.APPROVAL_TRIAGE.requiredScopes,
            )
        }
        val before = transport.calls
        val result = gate.check()
        assertEquals(JevApprovalTriage.Posture.ALLOW, result.applied)
        assertEquals(before, transport.calls)
    }
}
