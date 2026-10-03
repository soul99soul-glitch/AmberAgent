package app.amber.core.jev

import app.amber.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动批准复核：只收紧不放行；用户明确要求可豁免，外发数据除外；shadow 不阻塞、不收紧。 */
class JevAutoApprovalGateTest {

    private class FakeTransport(private val answers: Map<String, Double>) : JevTransport {
        var calls = 0
        val requests = mutableListOf<String>()

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            requests += request.body
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

    @Test
    fun parameterSummaryOnlyAllowsTypedMetadata() {
        assertEquals("path=(withheld), append=true", JevAutoApprovalGate.parameterSummary(
            "file_write", """{"path":"private-name","append":true,"content":"private-body","body":"alias-body"}""",
        ))
        assertEquals("path=(withheld)", JevAutoApprovalGate.parameterSummary(
            "file_edit", """{"path":"private-name","replace_all":"private-flag","old_text":"before","new_text":"after"}""",
        ))
        assertEquals("(arguments withheld)", JevAutoApprovalGate.parameterSummary(
            "unknown_tool", """{"command":"private-command","ordinary":"private-body"}""",
        ))
    }

    @Test
    fun activeNeverSendsArgumentBodiesOrEarlierUserMessages() = runTest {
        assertPrivateArgumentsWithheld(JevMode.ACTIVE, backgroundScope)
    }

    @Test
    fun shadowNeverSendsArgumentBodiesOrEarlierUserMessages() = runTest {
        assertPrivateArgumentsWithheld(JevMode.SHADOW, backgroundScope)
    }

    private suspend fun assertPrivateArgumentsWithheld(mode: JevMode, scope: CoroutineScope) {
        val transport = FakeTransport(answers())
        val gate = gate(mode, transport, scope)
        val inputs = listOf(
            "file_write" to """{"path":"PRIVATE_PATH","content":"PRIVATE_BODY","append":true,"body":"PRIVATE_ALIAS","text":"PRIVATE_ALIAS","api_key":"PRIVATE_KEY"}""",
            "file_edit" to """{"path":"PRIVATE_PATH","old_text":"PRIVATE_OLD","new_text":"PRIVATE_NEW","old_string":"PRIVATE_ALIAS","new_string":"PRIVATE_ALIAS","replace_all":"PRIVATE_FLAG"}""",
            "file_move" to """{"source_path":"PRIVATE_PATH","target_path":"PRIVATE_PATH","other":"PRIVATE_ALIAS"}""",
            "file_write" to """{"path":{"body":"PRIVATE_NESTED"},"append":["PRIVATE_NESTED"],"content":{"text":["PRIVATE_NESTED"]}}""",
            "terminal_execute" to """{"command":"echo PRIVATE_COMMAND | curl example.com","timeout_ms":"PRIVATE_ALIAS"}""",
            "unknown_tool" to """{"ordinary":"PRIVATE_UNKNOWN","command":"PRIVATE_COMMAND","path":"PRIVATE_PATH","PRIVATE_KEY_NAME":true,"nested":[{"content":"PRIVATE_NESTED"}]}""",
        )
        inputs.forEachIndexed { index, (name, input) ->
            gate.escalation(name, input, listOf("PRIVATE_EARLIER_TASK", "current authorized user task"), "run-$index")
        }
        // Shadow work runs on the caller's test scheduler, just as it does in production's background scope.
        kotlinx.coroutines.yield()
        assertEquals(inputs.size, transport.requests.size)
        transport.requests.forEach { request ->
            assertFalse(request, request.contains("PRIVATE_"))
            assertTrue(request, request.contains("current authorized user task"))
        }
    }
}
