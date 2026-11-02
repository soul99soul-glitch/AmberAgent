package app.amber.core.jev

import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SCRIPT_JUDGMENT：只评估既有终端输出，失败回落原文，截断外发。 */
class JevScriptJudgmentTest {

    private class FakeTransport(
        private val choice: String?,
        private val confidence: Double = 0.9,
        private val fail: Boolean = false,
    ) : JevTransport {
        var calls = 0
        val requests = mutableListOf<String>()

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            requests += request.body
            if (fail) return JevTransportResponse.Failure("offline")
            val answer = if (choice == null) {
                """"verdict":{"type":"noul","noul":0.9}"""
            } else {
                """"verdict":{"type":"choice","choice":"$choice","confidence":$confidence}"""
            }
            return JevTransportResponse.Http(
                200,
                """{"model":"jev-test","answers":{$answer}}""".toByteArray(),
                null,
            )
        }
    }

    private fun judgment(
        mode: JevMode,
        transport: FakeTransport,
        scopes: Set<JevDataScope> = setOf(JevDataScope.TOOL_OUTPUT),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): JevScriptJudgment {
        val settings = Settings(
            jev = JevSetting(
                enabled = mode != JevMode.OFF,
                purposes = if (mode == JevMode.OFF) emptyMap() else mapOf(JevPurpose.SCRIPT_JUDGMENT to mode),
                dataScopes = scopes,
            ),
        )
        return JevScriptJudgment(
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

    private fun tool(
        name: String = "terminal_execute",
        output: String = "exit 0\nhello",
        id: String = "call-1",
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = """{"command":"ls"}""",
        output = listOf(UIMessagePart.Text(output)),
    )

    @Test
    fun activeAttachesVerdictWithoutChangingOutput() = runTest {
        val original = tool()
        val judged = judgment(JevMode.ACTIVE, FakeTransport("ok")).annotate(listOf(original), "run-script")
        val next = judged.single()
        assertEquals(original.output, next.output)
        assertEquals(
            "ok",
            next.metadata?.get(JevScriptJudgment.METADATA_KEY)?.jsonObject?.get("verdict")?.jsonPrimitive?.content,
        )
        assertEquals("ok", JevScriptJudgment.noticeFor(next)?.verdict)
    }

    @Test
    fun noticeForReadsValidVerdictsAndIgnoresInvalid() {
        assertEquals("ok", JevScriptJudgment.noticeFor(toolWithMeta("ok"))?.verdict)
        assertEquals("failed", JevScriptJudgment.noticeFor(toolWithMeta("failed"))?.verdict)
        assertEquals("needs_attention", JevScriptJudgment.noticeFor(toolWithMeta("needs_attention"))?.verdict)
        assertNull(JevScriptJudgment.noticeFor(toolWithMeta("maybe")))
        assertNull(JevScriptJudgment.noticeFor(tool()))
    }

    @Test
    fun invalidChoiceLeavesOutputUnchanged() = runTest {
        val original = tool()
        val judged = judgment(JevMode.ACTIVE, FakeTransport("maybe")).annotate(listOf(original), "run-script")
        assertNull(judged.single().metadata?.get(JevScriptJudgment.METADATA_KEY))
        assertEquals(original.output, judged.single().output)
    }

    @Test
    fun lowConfidenceLeavesOutputUnchanged() = runTest {
        val original = tool()
        val judged = judgment(JevMode.ACTIVE, FakeTransport("failed", confidence = 0.2))
            .annotate(listOf(original), "run-script")
        assertNull(judged.single().metadata?.get(JevScriptJudgment.METADATA_KEY))
    }

    @Test
    fun failedDecisionLeavesOutputUnchanged() = runTest {
        val original = tool()
        val judged = judgment(JevMode.ACTIVE, FakeTransport("ok", fail = true))
            .annotate(listOf(original), "run-script")
        assertEquals(original, judged.single())
    }

    @Test
    fun offNeverCallsNetwork() = runTest {
        val transport = FakeTransport("ok")
        val original = tool()
        val judged = judgment(JevMode.OFF, transport).annotate(listOf(original), "run-script")
        assertEquals(listOf(original), judged)
        assertEquals(0, transport.calls)
        assertNull(JevScriptJudgment.noticeFor(judged.single()))
    }

    @Test
    fun shadowObservesInBackgroundAndLeavesToolsUnchanged() = runTest {
        val transport = FakeTransport("failed")
        val original = tool()
        val judged = judgment(JevMode.SHADOW, transport, scope = backgroundScope)
            .annotate(listOf(original), "run-script")
        assertEquals(listOf(original), judged)
        assertEquals(0, transport.calls)
        assertNull(JevScriptJudgment.noticeFor(judged.single()))
        runCurrent()
        assertEquals(1, transport.calls)
        assertNull(JevScriptJudgment.noticeFor(judged.single()))
    }

    @Test
    fun missingScopeSkipsWithoutNetwork() = runTest {
        val transport = FakeTransport("ok")
        val judged = judgment(JevMode.ACTIVE, transport, scopes = emptySet())
            .annotate(listOf(tool()), "run-script")
        assertNull(judged.single().metadata?.get(JevScriptJudgment.METADATA_KEY))
        assertEquals(0, transport.calls)
    }

    @Test
    fun nonScriptToolsAreIgnored() = runTest {
        val transport = FakeTransport("ok")
        val fileTool = tool(name = "file_read")
        val judged = judgment(JevMode.ACTIVE, transport).annotate(listOf(fileTool), "run-script")
        assertEquals(listOf(fileTool), judged)
        assertEquals(0, transport.calls)
    }

    @Test
    fun outboundSampleIsTruncated() = runTest {
        val transport = FakeTransport("needs_attention")
        val longOutput = "HEAD-" + "A".repeat(800) + "-TAIL-" + "Z".repeat(50)
        judgment(JevMode.ACTIVE, transport).annotate(listOf(tool(output = longOutput)), "run-script")
        val body = transport.requests.single()
        assertFalse(body.contains("A".repeat(800)))
        assertTrue(body.contains("HEAD-"))
        assertTrue(body.contains("Z".repeat(50)))
    }

    @Test
    fun budgetExhaustedLeavesOutputUnchanged() = runTest {
        val transport = FakeTransport("ok")
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.SCRIPT_JUDGMENT to JevMode.ACTIVE),
                dataScopes = setOf(JevDataScope.TOOL_OUTPUT),
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
        val checker = JevScriptJudgment(runtime)
        repeat(JevLimits.PER_RUN_MAX_REQUESTS) { index ->
            runtime.decide(
                purpose = JevPurpose.SCRIPT_JUDGMENT,
                runKey = "run-script",
                state = buildJsonObject { put("n", index) },
                questions = mapOf(
                    "verdict" to JevQuestion.Choice("classify", mapOf("ok" to "ok", "failed" to "f", "needs_attention" to "n")),
                ),
                requiredScopes = JevPurpose.SCRIPT_JUDGMENT.requiredScopes,
            )
        }
        val before = transport.calls
        val original = tool()
        val judged = checker.annotate(listOf(original), "run-script")
        assertEquals(original, judged.single())
        assertEquals(before, transport.calls)
        assertNull(JevScriptJudgment.noticeFor(judged.single()))
    }

    private fun toolWithMeta(verdict: String) = tool().copy(
        metadata = JsonObject(
            mapOf(
                JevScriptJudgment.METADATA_KEY to buildJsonObject {
                    put("verdict", verdict)
                    put("confidence", 0.9)
                },
            ),
        ),
    )
}
