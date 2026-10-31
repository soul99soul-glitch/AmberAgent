package app.amber.core.context

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3: `must_not_lose` is copied verbatim into the handoff and into the Room compact row, so a
 * credential the model echoed into a fact ends up persisted. The filter must catch credential
 * shapes while leaving the 12 factual needle classes (paths, exit codes, hashes, numbers) intact.
 */
class ContextFactRedactionTest {

    @Test
    fun providerKeysBearerTokensAndCloudKeysAreMasked() {
        val cases = listOf(
            "设置 API key 为 sk-proj-AbCd1234EfGh5678IjKl",
            "Header: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig",
            "git token ghp_16C7e42F292c6912E7710c838347Ae178B4a",
            "AWS key AKIAIOSFODNN7EXAMPLE",
            "GET https://api.example.com?api_key=abcd1234efgh5678",
            "password=hunter2secret",
        )
        cases.forEach { raw ->
            val redacted = ContextFactRedaction.redact(raw)
            assertFalse("credential survived redaction: $raw -> $redacted", redacted == raw)
            assertTrue("mask marker missing: $redacted", redacted.contains("******"))
        }
    }

    @Test
    fun longHexAndBase64BlobsAreMasked() {
        val hex = ContextFactRedaction.redact("session fingerprint 3f9a2b7c1d4e5f60718293a4b5c6d7e8")
        assertFalse(hex.contains("3f9a2b7c1d4e5f60718293a4b5c6d7e8"))

        val blob = "token blob " + "AbCdEf0123456789".repeat(4)
        val base64 = ContextFactRedaction.redact(blob)
        assertFalse(base64.contains("AbCdEf0123456789".repeat(4)))
    }

    @Test
    fun verificationCodesAreMaskedOnlyNextToACodeKeyword() {
        val code = ContextFactRedaction.redact("验证码：483920，5 分钟内有效")
        assertFalse("otp survived", code.contains("483920"))
        assertTrue(code.contains("验证码"))

        // Bare numbers that carry operational meaning must survive.
        val budget = "max_steps=30, budget=180000ms, keepRecentTurns=12"
        assertEquals(budget, ContextFactRedaction.redact(budget))
    }

    @Test
    fun contentHashMarkersKeepTheHash() {
        // The needle baseline treats `sha256:<hex>` as a fact worth keeping verbatim.
        val fact = "JevScreenGoalRunner.kt @ a2a159aa1 sha256:9f2c1d0b7a3e4f5061728394a5b6c7d8"
        assertEquals(fact, ContextFactRedaction.redact(fact))
    }

    @Test
    fun ordinaryFactsAreNotTouched() {
        val facts = listOf(
            "java.lang.IllegalStateException: Screen control was ended by screen_stop",
            "./gradlew :app:testFullDebugUnitTest exited 1",
            "用户批准原文：允许本次屏幕操作",
            "https://api.example.com/v1/chat/completions",
            "deepseek-v3.2 via chat.completions",
            "2026-10-31 23:59 前完成",
            "不得修改 iOS 仓库",
            "决定：保持 content-hash snapshot id",
            "TODO: Gateway 配对未验证",
            "高风险动作只能手机拒绝，批准须回 Mac",
        )
        facts.forEach { fact ->
            assertEquals("fact was altered: $fact", fact, ContextFactRedaction.redact(fact))
        }
    }

    @Test
    fun redactionIsIdempotent() {
        val once = ContextFactRedaction.redact("key sk-proj-AbCd1234EfGh5678")
        assertEquals(once, ContextFactRedaction.redact(once))
    }

    @Test
    fun credentialsNeverReachThePersistedSummary() {
        val payload = buildString {
            append("""{"schema_version":2,"timeline_summary":"""")
            append(kotlinx.serialization.json.JsonPrimitive("Summary of work."))
            append(""","handoff_markdown":"""")
            append(kotlinx.serialization.json.JsonPrimitive("## Goal\n- continue"))
            append(""","must_not_lose":[""")
            append(
                listOf(
                    "api_key=sk-live-AbCd1234EfGh5678IjKl",
                    "Authorization: Bearer abcDEF123456ghiJKL789",
                ).joinToString(",") { kotlinx.serialization.json.JsonPrimitive(it).toString() }
            )
            append("]}")
        }
        val normalized = CompactSummaryNormalizer.normalizeOrNull(
            json = Json { ignoreUnknownKeys = true },
            summary = payload,
            sourceMessageIds = listOf("m1"),
            coveredCompactIds = emptyList(),
            createdAt = 1_000L,
        )!!
        assertFalse(normalized.contains("sk-live-AbCd1234"))
        assertFalse(normalized.contains("abcDEF123456ghiJKL789"))

        val injected = CompactSummaryPayloads.injectionText(
            id = "c1",
            summary = normalized,
            sourceMessageIds = listOf("m1"),
        )
        assertFalse(injected.contains("sk-live-AbCd1234"))
        assertFalse(injected.contains("abcDEF123456ghiJKL789"))
    }

    @Test
    fun timelineSummaryAndHandoffMarkdownAreRedactedBeforeFitHandoff() {
        val payload = """
            {
              "schema_version": 2,
              "timeline_summary": "Sentence one uses key sk-proj-TimelineSecret1234567890. Sentence two continues. Sentence three continues. Sentence four concludes.",
              "handoff_markdown": "## Goal\n- Use Authorization: Bearer HandoffSecretToken9876543210\n\n## Constraints\n- Keep safe",
              "must_not_lose": [
                "Use Authorization: Bearer HandoffSecretToken9876543210"
              ]
            }
        """.trimIndent()
        val normalized = CompactSummaryNormalizer.normalizeOrNull(
            json = Json { ignoreUnknownKeys = true },
            summary = payload,
            sourceMessageIds = listOf("m1"),
            coveredCompactIds = emptyList(),
            createdAt = 1_000L,
        )!!
        assertFalse("timeline secret leaked: $normalized", normalized.contains("TimelineSecret1234567890"))
        assertFalse("handoff secret leaked: $normalized", normalized.contains("HandoffSecretToken9876543210"))
    }

    @Test
    fun compactPromptAndRecapRedactionStripToolAndTextSecrets() {
        val msg = app.amber.ai.ui.UIMessage(
            role = app.amber.ai.core.MessageRole.USER,
            parts = listOf(
                app.amber.ai.ui.UIMessagePart.Text("My key is sk-proj-SecretKey1234567890"),
                app.amber.ai.ui.UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "provider_config_apply",
                    input = """{"provider_id":"p1","api_key":"sk-proj-ToolSecret9876543210"}""",
                    output = listOf(app.amber.ai.ui.UIMessagePart.Text("Authorization: Bearer ToolOutputToken1234567890")),
                ),
            ),
        )
        val prompt = ConversationContextPlanner.buildCompressionInput(listOf(msg))
        assertFalse(prompt.contains("SecretKey1234567890"))
        assertFalse(prompt.contains("ToolSecret9876543210"))
        assertFalse(prompt.contains("ToolOutputToken1234567890"))

        val parsedRecap = app.amber.core.recap.ConversationRecapParser.parse(
            raw = """{"overview":"Configured sk-proj-OverviewSecret1234567890","nodes":[{"ref":"m1","kind":"decision","title":"Saved api_key=NodeSecret1234567890"}],"next":["Rotate Authorization: Bearer NextStepToken1234567890"]}""",
            conversationId = "c1",
            sources = listOf(
                app.amber.core.recap.RecapSourceMessage(
                    ref = "m1",
                    nodeId = "n1",
                    messageId = "m1",
                    role = app.amber.ai.core.MessageRole.USER,
                    text = "hello",
                )
            ),
            branchMessageIds = listOf("m1"),
            previous = null,
            nowMillis = 1_000L,
        )!!
        assertFalse(parsedRecap.overview.contains("OverviewSecret1234567890"))
        assertFalse(parsedRecap.nodes.single().title.contains("NodeSecret1234567890"))
        assertFalse(parsedRecap.nextSteps.single().contains("NextStepToken1234567890"))
    }
}
