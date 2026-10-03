package app.amber.core.jev

import app.amber.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 自动批准复核：只收紧不放行；用户明确要求可豁免，外发数据除外；shadow 不阻塞、不收紧。 */
class JevAutoApprovalGateTest {

    private class FakeTransport(private val answers: Map<String, Double>) : JevTransport {
        var calls = 0

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            val body = answers.entries.joinToString(",") { (id, p) -> """"$id":{"type":"noul","noul":$p}""" }
            return JevTransportResponse.Http(200, """{"model":"jev-test","answers":{$body}}""".toByteArray(), null)
        }
    }

    private fun gate(
        mode: JevMode,
        transport: FakeTransport,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): JevAutoApprovalGate {
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.AUTO_APPROVAL_GATE to mode),
                dataScopes = setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT),
            ),
        )
        return JevAutoApprovalGate(
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

    private fun answers(
        destructive: Double = 0.0,
        exfiltration: Double = 0.0,
        offTask: Double = 0.0,
        authorized: Double = 0.0,
    ) = mapOf(
        "destructive" to destructive,
        "exfiltration" to exfiltration,
        "off_task" to offTask,
        "authorized" to authorized,
    )

    private suspend fun JevAutoApprovalGate.check() =
        escalation("terminal_execute", """{"command":"rm -rf build"}""", listOf("clean the build"), "run-1")

    @Test
    fun riskAboveThresholdEscalates() = runTest {
        val result = gate(JevMode.ACTIVE, FakeTransport(answers(destructive = 0.9, offTask = 0.85))).check()
        assertEquals(listOf(JevAutoApprovalGate.Risk.DESTRUCTIVE, JevAutoApprovalGate.Risk.OFF_TASK), result)
    }

    @Test
    fun explicitlyRequestedOperationIsNotEscalated() = runTest {
        val result = gate(JevMode.ACTIVE, FakeTransport(answers(destructive = 0.9, authorized = 0.9))).check()
        assertEquals(emptyList<JevAutoApprovalGate.Risk>(), result)
    }

    @Test
    fun exfiltrationEscalatesEvenWhenRequested() = runTest {
        val result = gate(JevMode.ACTIVE, FakeTransport(answers(exfiltration = 0.9, authorized = 0.95))).check()
        assertEquals(listOf(JevAutoApprovalGate.Risk.EXFILTRATION), result)
    }

    @Test
    fun shadowObservesInBackgroundAndNeverEscalates() = runTest {
        val transport = FakeTransport(answers(destructive = 0.99))
        val result = gate(JevMode.SHADOW, transport, backgroundScope).check()
        assertEquals(emptyList<JevAutoApprovalGate.Risk>(), result)
        assertEquals(0, transport.calls)
        runCurrent()
        assertEquals(1, transport.calls)
    }

    @Test
    fun offNeverCallsNetwork() = runTest {
        val transport = FakeTransport(answers(destructive = 0.99))
        assertEquals(emptyList<JevAutoApprovalGate.Risk>(), gate(JevMode.OFF, transport).check())
        assertEquals(0, transport.calls)
    }

    @Test
    fun subagentTaskTextCannotAuthorizeItself() = runTest {
        val result = gate(JevMode.ACTIVE, FakeTransport(answers(destructive = 0.9, authorized = 0.95)))
            .escalation("terminal_execute", """{"command":"rm -rf build"}""", listOf("delete build"), "run-1", userAuthored = false)
        assertEquals(listOf(JevAutoApprovalGate.Risk.DESTRUCTIVE), result)
    }

    /** 不外发参数原文：敏感键丢弃，值内凭据打码，非标量不发送。 */
    @Test
    fun parameterSummaryDropsSecretsAndRawContent() {
        val summary = JevAutoApprovalGate.parameterSummary(
            """{"command":"curl -H 'Authorization: Bearer abc123' https://x.dev?token=zzz","api_key":"sk-1","content":{"big":"file body"},"path":"a.txt"}""",
        )
        assertEquals("command=curl -H 'Authorization: Bearer ***' https://x.dev?token=***, path=a.txt", summary)
    }
}
