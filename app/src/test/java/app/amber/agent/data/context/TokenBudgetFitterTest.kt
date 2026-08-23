package app.amber.agent.data.context

import app.amber.ai.core.InputSchema
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.context.ContextTooLargeException
import app.amber.core.context.TokenBudgetFitter
import app.amber.core.context.TokenFitProvenance
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P1-04 — final token budget hard fit.
 *
 * Covers the plan's test list: OCR-expanded (over-budget) requests trimmed,
 * large tool schemas counted, mailbox/steer counted last, current user
 * message + tool results preserved, and one provider-specific multimodal
 * estimation difference.
 */
class TokenBudgetFitterTest {

    private val json = Json { ignoreUnknownKeys = true }

    // window 8000 → hard budget = max(8000 - 4096, 4000) = 4000 tokens
    private val model = Model(modelId = "fit-model", contextWindowTokens = 8_000)

    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
    private fun assistant(text: String) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))
    private fun system(text: String) = UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(text)))

    private fun toolResultMessage(
        toolCallId: String = "call-1",
        output: String = "search result payload",
    ): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = toolCallId,
                toolName = "search_web",
                input = "{\"query\":\"test\"}",
                output = listOf(UIMessagePart.Text(output)),
                approvalState = ToolApprovalState.Approved,
            )
        ),
    )

    private fun ids(messages: List<UIMessage>): List<String> = messages.map { it.id.toString() }

    private fun assertNoThrow(block: () -> Unit) {
        try {
            block()
        } catch (e: ContextTooLargeException) {
            fail("unexpected ContextTooLargeException: ${e.message}")
        }
    }

    @Test
    fun ocrExpandedRequestOverBudgetTrimsHistoryAndKeepsCurrentUser() {
        // Simulates: history + system below budget before injection, then the
        // current user message is expanded by the OCR transformer → total over
        // the hard budget. The fit trims old history but keeps the current
        // user request and the system prompt.
        val history = buildList {
            repeat(4) {
                add(user("old user message $it " + "x".repeat(6_000)))
                add(assistant("old assistant reply $it " + "y".repeat(6_000)))
            }
        }
        val currentUser = user("OCR 识别出的本轮内容 " + "z".repeat(2_000))
        val messages = listOf(system("system prompt rules")) + history + listOf(currentUser)

        val result = TokenBudgetFitter.fit(
            messages = messages,
            tools = emptyList(),
            model = model,
            provider = ProviderSetting.OpenAI(),
            maxTokens = null,
            json = json,
        )

        val fittedIds = ids(result.messages)
        assertTrue("current user must survive", fittedIds.contains(currentUser.id.toString()))
        assertTrue("system must survive", result.messages.any { it.role == MessageRole.SYSTEM })
        assertFalse(result.receipt.contextTooLarge)
        // 裁剪是"最小够用"的：只裁到预算内为止，旧历史优先被裁。
        assertTrue(
            "old history must be trimmed first",
            result.receipt.trimmedMessages.any { it.provenance == TokenFitProvenance.HISTORY },
        )
        assertTrue(result.receipt.estimatedBefore > result.receipt.estimatedAfter)
        assertTrue(result.receipt.estimatedAfter <= result.receipt.budgetTokens)
    }

    @Test
    fun largeToolSchemaIsCountedAndTriggersContextTooLarge() {
        val messages = listOf(
            system("system prompt"),
            user("a small current request"),
        )
        val bigTool = Tool(
            name = "search_web",
            description = "huge schema description " + "d".repeat(24_000),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject { put("type", "string") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    }
                )
            },
            execute = { emptyList() },
        )

        // Same messages, no tools: fits comfortably.
        assertNoThrow {
            TokenBudgetFitter.fit(messages, emptyList(), model, ProviderSetting.OpenAI(), null, json)
        }

        // With the large tool schema counted into the budget: over after
        // trimming everything trimmable → ContextTooLarge, request not sent.
        try {
            TokenBudgetFitter.fit(messages, listOf(bigTool), model, ProviderSetting.OpenAI(), null, json)
            fail("expected ContextTooLargeException for oversized tool schema")
        } catch (e: ContextTooLargeException) {
            assertTrue("tool schema tokens must be counted", e.receipt.toolSchemaTokens > 0)
            assertTrue(e.receipt.contextTooLarge)
            assertTrue(e.receipt.estimatedAfter > e.receipt.budgetTokens)
        }
    }

    @Test
    fun mailboxSteerCountedLastAndTrimmedAfterHistory() {
        // [system, history(user+assistant), tool-result assistant, steer1, steer2(current)]
        val historyUser = user("old plain user message " + "h".repeat(6_000))
        val historyAssistant = assistant("old plain assistant reply " + "a".repeat(6_000))
        val toolMessage = toolResultMessage()
        val steer1 = user("queued during generation " + "s".repeat(20_000))
        val currentUser = user("latest steer (current request) " + "c".repeat(1_000))
        val messages = listOf(
            system("system prompt"),
            historyUser,
            historyAssistant,
            toolMessage,
            steer1,
            currentUser,
        )

        val result = TokenBudgetFitter.fit(
            messages = messages,
            tools = emptyList(),
            model = model,
            provider = ProviderSetting.OpenAI(),
            maxTokens = null,
            json = json,
        )

        val fittedIds = ids(result.messages)
        assertTrue("current user (last steer) must survive", fittedIds.contains(currentUser.id.toString()))
        assertTrue("tool result must survive", fittedIds.contains(toolMessage.id.toString()))
        assertTrue("system must survive", result.messages.any { it.role == MessageRole.SYSTEM })
        assertFalse("old history must be trimmed", fittedIds.contains(historyUser.id.toString()))
        assertFalse("old history must be trimmed", fittedIds.contains(historyAssistant.id.toString()))
        assertFalse("steer must be trimmed after history", fittedIds.contains(steer1.id.toString()))
        // Trim order: HISTORY first, STEER last (mailbox/steer counted last).
        assertEquals(
            listOf(TokenFitProvenance.HISTORY, TokenFitProvenance.STEER),
            result.receipt.trimmedMessages.map { it.provenance },
        )
        assertFalse(result.receipt.contextTooLarge)
    }

    @Test
    fun toolResultAndCurrentUserSurviveTrim() {
        val history = buildList {
            repeat(3) {
                add(user("history $it " + "x".repeat(6_000)))
                add(assistant("history reply $it " + "y".repeat(6_000)))
            }
        }
        val toolMessage = toolResultMessage(output = "unresolved tool result that must be preserved")
        val currentUser = user("current request " + "z".repeat(500))
        val messages = listOf(system("system prompt")) + history + listOf(toolMessage, currentUser)

        val result = TokenBudgetFitter.fit(
            messages = messages,
            tools = emptyList(),
            model = model,
            provider = ProviderSetting.OpenAI(),
            maxTokens = null,
            json = json,
        )

        val fittedIds = ids(result.messages)
        assertTrue("current user must survive", fittedIds.contains(currentUser.id.toString()))
        assertTrue("tool result must survive", fittedIds.contains(toolMessage.id.toString()))
        assertTrue("system must survive", result.messages.any { it.role == MessageRole.SYSTEM })
        assertFalse(result.receipt.contextTooLarge)
        // 旧历史被裁掉至少一条，而 tool result 与当前用户消息原样保留。
        assertTrue(result.receipt.trimmedMessages.any { it.provenance == TokenFitProvenance.HISTORY })
        assertTrue(result.receipt.estimatedBefore > result.receipt.estimatedAfter)
        assertTrue(result.receipt.estimatedAfter <= result.receipt.budgetTokens)
    }

    @Test
    fun multimodalProviderEstimateDiffers() {
        // One covered provider difference: Claude (w*h)/750 pricing counts a
        // 1024² image higher than tile-based vision on OpenAI/Google.
        val multimodal = listOf(
            system("system"),
            UIMessage(
                role = MessageRole.USER,
                parts = List(4) { index ->
                    UIMessagePart.Image("file:///tmp/img$index.png")
                },
            ),
        )
        val openAiEstimate = TokenBudgetFitter.estimateTokens(multimodal, ProviderSetting.OpenAI())
        val claudeEstimate = TokenBudgetFitter.estimateTokens(multimodal, ProviderSetting.Claude())
        assertTrue("Claude multimodal estimate must exceed OpenAI's", claudeEstimate > openAiEstimate)

        // Fit-level consequence: with the budget between the two estimates,
        // the OpenAI request fits but the Claude request is rejected.
        val middleBudget = (openAiEstimate + claudeEstimate) / 2
        val tightModel = Model(modelId = "tight", contextWindowTokens = middleBudget + 4_096)
        assertNoThrow {
            TokenBudgetFitter.fit(multimodal, emptyList(), tightModel, ProviderSetting.OpenAI(), null, json)
        }
        try {
            TokenBudgetFitter.fit(multimodal, emptyList(), tightModel, ProviderSetting.Claude(), null, json)
            fail("expected ContextTooLargeException for Claude multimodal over budget")
        } catch (e: ContextTooLargeException) {
            assertNotNull(e.receipt)
        }
    }
}
