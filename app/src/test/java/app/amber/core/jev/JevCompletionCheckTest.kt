package app.amber.core.jev

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** 完成声明校验：事实门先行（零网络），满足才问 Jev；只有 active 且宣称完成/已验证才续跑。 */
class JevCompletionCheckTest {

    private class FakeTransport(private val claim: Double) : JevTransport {
        var calls = 0

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            val body = """{"model":"jev-test","answers":{"claims_done":{"type":"noul","noul":$claim},"claims_verified":{"type":"noul","noul":0.0}}}"""
            return JevTransportResponse.Http(200, body.toByteArray(), null)
        }
    }

    private fun check(
        mode: JevMode,
        transport: FakeTransport,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): JevCompletionCheck {
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.COMPLETION_CHECK to mode),
                dataScopes = setOf(JevDataScope.TASK_TEXT, JevDataScope.TOOL_METADATA),
            ),
        )
        return JevCompletionCheck(
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

    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun tool(name: String, input: String, output: String = """{"status":"ok"}""") = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call-${name}-${input.hashCode()}",
                toolName = name,
                input = input,
                output = listOf(UIMessagePart.Text(output)),
            ),
        ),
    )

    private fun reply(text: String) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private val isFailure: (List<UIMessagePart>) -> Boolean = { output ->
        output.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("\"failed\"") }
    }

    private val write = tool("file_edit", """{"path":"src/Main.kt"}""")
    private val gradleTest = tool("terminal_execute", """{"command":"./gradlew test"}""")
    private val listing = tool("terminal_execute", """{"command":"ls -la"}""")

    @Test
    fun writeWithoutLaterCheckIsUnverified() {
        assertEquals(
            listOf("src/Main.kt"),
            JevCompletionCheck.unverifiedWrites(listOf(user("fix it"), write, listing, reply("done")), isFailure),
        )
    }

    @Test
    fun checkAfterLastWriteCountsAsVerifiedEvenIfItFailed() {
        val failedTest = tool("terminal_execute", """{"command":"pytest"}""", """{"status":"failed"}""")
        assertNull(JevCompletionCheck.unverifiedWrites(listOf(user("fix it"), write, failedTest, reply("done")), isFailure))
    }

    @Test
    fun writeAfterCheckIsUnverifiedAgain() {
        assertEquals(
            listOf("src/Main.kt"),
            JevCompletionCheck.unverifiedWrites(listOf(user("fix it"), gradleTest, write, reply("done")), isFailure),
        )
    }

    @Test
    fun failedWritesAndEarlierTurnsDoNotCount() {
        val failedWrite = tool("file_write", """{"path":"a.txt"}""", """{"status":"failed"}""")
        assertNull(JevCompletionCheck.unverifiedWrites(listOf(user("fix it"), failedWrite, reply("done")), isFailure))
        assertNull(JevCompletionCheck.unverifiedWrites(listOf(user("fix it"), write, reply("done"), user("thanks"), reply("ok")), isFailure))
    }

    @Test
    fun activeClaimOfCompletionReturnsNudge() = runTest {
        val transport = FakeTransport(claim = 0.95)
        assertTrue(check(JevMode.ACTIVE, transport).shouldContinue(listOf(user("fix it"), write, reply("全部完成")), "run-1", isFailure))
        assertEquals(1, transport.calls)
    }

    @Test
    fun noClaimMeansNoNudge() = runTest {
        assertFalse(
            check(JevMode.ACTIVE, FakeTransport(claim = 0.1))
                .shouldContinue(listOf(user("fix it"), write, reply("改好了，还没测试，你可以跑一下")), "run-1", isFailure),
        )
    }

    @Test
    fun factsNotMetNeverCallsNetwork() = runTest {
        val transport = FakeTransport(claim = 0.95)
        assertFalse(check(JevMode.ACTIVE, transport).shouldContinue(listOf(user("fix it"), write, gradleTest, reply("done")), "run-1", isFailure))
        assertEquals(0, transport.calls)
    }

    @Test
    fun shadowObservesInBackgroundAndNeverNudges() = runTest {
        val transport = FakeTransport(claim = 0.95)
        assertFalse(check(JevMode.SHADOW, transport, backgroundScope).shouldContinue(listOf(user("fix it"), write, reply("done")), "run-1", isFailure))
        assertEquals(0, transport.calls)
        runCurrent()
        assertEquals(1, transport.calls)
    }

    /** 真实形态：一轮 agent 对话合并在同一条 assistant 消息里；最终回复只取最后一个工具之后的文本。 */
    @Test
    fun mergedAgentTurnUsesTextAfterLastToolAsFinalReply() {
        val writePart = write.parts.single()
        val listingPart = listing.parts.single()
        val merged = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(writePart, UIMessagePart.Text("我先改文件"), listingPart, UIMessagePart.Text("全部完成")),
        )
        val messages = listOf(user("fix it"), merged)
        assertEquals("全部完成", JevCompletionCheck.finalReply(messages))
        assertEquals(listOf("src/Main.kt"), JevCompletionCheck.unverifiedWrites(messages, isFailure))
    }

    @Test
    fun fileMoveReportsTargetPath() {
        val move = tool("file_move", """{"source_path":"a.txt","target_path":"b.txt"}""")
        assertEquals(listOf("b.txt"), JevCompletionCheck.unverifiedWrites(listOf(user("move it"), move, reply("done")), isFailure))
    }
    @Test
    fun checkWordsInArgumentsDoNotCountAsRunningChecks() {
        for (command in listOf("cat build.gradle.kts", "ls tests", "echo test", "git diff -- tests")) {
            val observation = tool("terminal_execute", """{"command":"$command"}""")
            assertEquals(command, listOf("src/Main.kt"), JevCompletionCheck.unverifiedWrites(
                listOf(user("fix it"), write, observation, reply("done")), isFailure,
            ))
        }
    }

    @Test
    fun commonCheckRunnersAreRecognizedInExecutionPosition() {
        for (command in listOf("./gradlew test", "pytest", "npm test", "pnpm lint", "cargo check", "python -m pytest")) {
            val checkCommand = tool("terminal_execute", """{"command":"$command"}""")
            assertNull(command, JevCompletionCheck.unverifiedWrites(
                listOf(user("fix it"), write, checkCommand, reply("done")), isFailure,
            ))
        }
    }

    @Test
    fun quotedOrEscapedSuggestedChecksAreNotExecutedChecks() {
        val commands = listOf(
            "echo \"Suggested checks; pytest tests\"",
            "echo 'Suggested checks && pytest tests'",
            "echo 'Suggested checks || pytest tests'",
            "echo \"Suggested checks\\\"; pytest tests\"",
            "echo Suggested\\; pytest tests",
        )
        for (command in commands) {
            val input = kotlinx.serialization.json.buildJsonObject { put("command", command) }.toString()
            val observation = tool("terminal_execute", input)
            assertEquals(command, listOf("src/Main.kt"), JevCompletionCheck.unverifiedWrites(
                listOf(user("fix it"), write, observation, reply("done")), isFailure,
            ))
        }
        val actualCheck = tool("terminal_execute", """{"command":"echo 'Suggested checks; no check yet' && pytest tests"}""")
        assertNull(JevCompletionCheck.unverifiedWrites(listOf(user("fix it"), write, actualCheck, reply("done")), isFailure))
    }

}
