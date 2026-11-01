package app.amber.feature.ui.components.message

import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenToolReceiptPresentationTest {

    private fun parseJson(raw: String) = JsonInstant.parseToJsonElement(raw)

    private fun makeToolPart(
        toolName: String,
        inputJson: String,
        outputJson: String,
    ): Pair<UIMessagePart.Tool, kotlinx.serialization.json.JsonElement> {
        val content = parseJson(outputJson)
        val part = UIMessagePart.Tool(
            toolCallId = "tc-test",
            toolName = toolName,
            input = inputJson,
            output = listOf(UIMessagePart.Text(outputJson)),
            approvalState = ToolApprovalState.Approved,
        )
        return part to content
    }

    @Test
    fun `parses verified completed screen_run_goal with steps ocr and auto return`() {
        val args = parseJson("""{"package_name":"tv.danmaku.bili","goal":"打开搜索结果页"}""")
        val content = parseJson(
            """
            {
              "status": "completed",
              "snapshot_id": "snap-2",
              "package_name": "tv.danmaku.bili",
              "jev_decisions": 2,
              "actions_dispatched": 1,
              "takeover_count": 0,
              "returned_to_host": true,
              "host_return_reason": "completed_auto_return",
              "snapshot_invalidated": true,
              "ocr_mode": "auto",
              "ocr_triggered": true,
              "ocr_reason": "low_tree_coverage",
              "ocr_block_count": 2,
              "tree_coverage": {"coverage_ratio": 0.12},
              "ocr_blocks": [
                {"text": "热门视频排行"},
                {"text": "验证码 839201 请勿泄露"}
              ],
              "steps": [
                {"choice": "a0", "action": "click", "target": "n0_10_10_100_80", "label": "Click 搜索框", "status": "ok", "dispatched": true}
              ]
            }
            """.trimIndent()
        )

        val receipt = ScreenToolReceiptParser.parse(
            toolName = "screen_run_goal",
            arguments = args,
            content = content,
        )
        assertNotNull(receipt)
        requireNotNull(receipt)
        assertEquals(ScreenReceiptTier.VERIFIED_COMPLETED, receipt.tier)
        assertEquals("打开搜索结果页", receipt.goal)
        assertEquals("tv.danmaku.bili", receipt.packageName)
        assertEquals(1, receipt.executedSteps.size)
        assertEquals("Click 搜索框", receipt.executedSteps.first().summaryLabel)
        assertTrue(receipt.heldSensitiveActions.isEmpty())
        assertTrue(receipt.unmetReasons.isEmpty())
        assertEquals(true, receipt.hostReturnSummary?.returnedToHost)
        assertEquals(true, receipt.ocrSummary?.triggered)
        assertEquals(2, receipt.ocrSummary?.blockCount)
        // Sensitive OTP in OCR block must be redacted
        assertTrue(receipt.ocrSummary?.sampleTexts?.get(1)?.contains("******") == true)
        assertFalse(receipt.ocrSummary?.sampleTexts?.get(1)?.contains("839201") == true)
    }

    @Test
    fun `parses held sensitive final action and maps status to INTERRUPTED instead of SUCCEEDED`() {
        val inputJson = """{"package_name":"com.tencent.mm","goal":"填写草稿并准备发送"}"""
        val outputJson = """
            {
              "status": "needs_user_action",
              "reason": "action_not_read_only",
              "package_name": "com.tencent.mm",
              "actions_dispatched": 1,
              "takeover_count": 1,
              "intercepted_sensitive_action": "Click 发送",
              "returned_to_host": false,
              "host_return_reason": "not_completed_keep_foreground",
              "steps": [
                {"choice": "a0", "action": "type", "target": "n0_10_10_200_80", "label": "Type \"明日会议议程\" into 输入框", "status": "ok", "dispatched": true},
                {"choice": "a1", "action": "click", "target": "n1_200_800_300_860", "label": "Click 发送", "safe_probability": 0.12, "dispatched": false, "reason": "action_not_read_only"}
              ],
              "held_sensitive_actions": [
                {"choice": "a1", "action": "click", "target": "n1_200_800_300_860", "label": "Click 发送", "safe_probability": 0.12, "dispatched": false, "reason": "action_not_read_only"}
              ],
              "blocked_sensitive_candidates": [
                {"action": "click", "target": "n2_250_800_350_860", "label": "确认转账", "reason": "sensitive_commit_button_filtered"}
              ],
              "next": "Required next action is not read-only or needs user confirmation on the device."
            }
        """.trimIndent()

        val (part, content) = makeToolPart("screen_run_goal", inputJson, outputJson)
        val status = toolStatusFromMessagePart(tool = part, loading = false, content = content)
        assertEquals(AgentToolStatus.INTERRUPTED, status)

        val receipt = ScreenToolReceiptParser.parse(
            toolName = "screen_run_goal",
            arguments = parseJson(inputJson),
            content = content,
        )
        assertNotNull(receipt)
        requireNotNull(receipt)
        assertEquals(ScreenReceiptTier.HELD_FOR_USER, receipt.tier)
        // Only the confirmed dispatched step is in executedSteps; the held step is separated into heldSensitiveActions
        assertEquals(1, receipt.executedSteps.size)
        assertEquals("Type \"明日会议议程\" into 输入框", receipt.executedSteps.first().summaryLabel)
        assertEquals(2, receipt.heldSensitiveActions.size)
        assertEquals("Click 发送", receipt.heldSensitiveActions[0].label)
        assertTrue(receipt.heldSensitiveActions[0].isSelectedChoice)
        assertEquals("确认转账", receipt.heldSensitiveActions[1].label)
        assertFalse(receipt.heldSensitiveActions[1].isSelectedChoice)
        assertTrue(receipt.unmetReasons.isNotEmpty())
        assertEquals(false, receipt.hostReturnSummary?.returnedToHost)
    }

    @Test
    fun `redacts sensitive input text in low-level screen_input_text receipt`() {
        val args = parseJson("""{"text":"密码 password=MySecret9988"}""")
        val content = parseJson("""{"status":"ok","verified":true}""")

        val receipt = ScreenToolReceiptParser.parse(
            toolName = "screen_input_text",
            arguments = args,
            content = content,
        )
        assertNotNull(receipt)
        requireNotNull(receipt)
        assertEquals(1, receipt.executedSteps.size)
        val summary = receipt.executedSteps.first().summaryLabel
        assertTrue(summary.contains("******"))
        assertFalse(summary.contains("MySecret9988"))
    }

    @Test
    fun `maps outcome_unknown and unverified low-level actions to non-succeeded statuses`() {
        val (unknownPart, unknownContent) = makeToolPart(
            toolName = "screen_run_goal",
            inputJson = """{"package_name":"tv.danmaku.bili","goal":"Search"}""",
            outputJson = """{"status":"outcome_unknown","reason":"post_action_screen_unavailable"}""",
        )
        assertEquals(
            AgentToolStatus.UNKNOWN,
            toolStatusFromMessagePart(unknownPart, loading = false, content = unknownContent),
        )
        val unknownReceipt = ScreenToolReceiptParser.parse(
            toolName = "screen_run_goal",
            arguments = parseJson(unknownPart.input),
            content = unknownContent,
        )
        assertEquals(ScreenReceiptTier.OUTCOME_UNKNOWN, unknownReceipt?.tier)

        val (unverifiedTapPart, unverifiedTapContent) = makeToolPart(
            toolName = "screen_tap_text",
            inputJson = """{"text":"登录"}""",
            outputJson = """{"status":"ok","verified":false}""",
        )
        assertEquals(
            AgentToolStatus.INTERRUPTED,
            toolStatusFromMessagePart(unverifiedTapPart, loading = false, content = unverifiedTapContent),
        )
        val unverifiedReceipt = ScreenToolReceiptParser.parse(
            toolName = "screen_tap_text",
            arguments = parseJson(unverifiedTapPart.input),
            content = unverifiedTapContent,
        )
        assertEquals(ScreenReceiptTier.PARTIAL_OR_HANDBACK, unverifiedReceipt?.tier)

        val (waitTimeoutPart, waitTimeoutContent) = makeToolPart(
            toolName = "screen_wait_for_text",
            inputJson = """{"text":"支付成功"}""",
            outputJson = """{"found":false,"elapsed_ms":3000}""",
        )
        assertEquals(
            AgentToolStatus.INTERRUPTED,
            toolStatusFromMessagePart(waitTimeoutPart, loading = false, content = waitTimeoutContent),
        )
        val waitTimeoutReceipt = ScreenToolReceiptParser.parse(
            toolName = "screen_wait_for_text",
            arguments = parseJson(waitTimeoutPart.input),
            content = waitTimeoutContent,
        )
        assertEquals(ScreenReceiptTier.PARTIAL_OR_HANDBACK, waitTimeoutReceipt?.tier)

        val (failedClickPart, failedClickContent) = makeToolPart(
            toolName = "screen_click",
            inputJson = """{"x":100,"y":200}""",
            outputJson = """{"success":false}""",
        )
        assertEquals(
            AgentToolStatus.FAILED,
            toolStatusFromMessagePart(failedClickPart, loading = false, content = failedClickContent),
        )
        val failedClickReceipt = ScreenToolReceiptParser.parse(
            toolName = "screen_click",
            arguments = parseJson(failedClickPart.input),
            content = failedClickContent,
        )
        assertEquals(ScreenReceiptTier.FAILED_OR_BLOCKED, failedClickReceipt?.tier)

        // Non-screen tools are ignored by ScreenToolReceiptParser
        assertNull(
            ScreenToolReceiptParser.parse(
                toolName = "file_read",
                arguments = parseJson("""{"path":"README.md"}"""),
                content = parseJson("""{"content":"hello"}"""),
            )
        )
    }

    @Test
    fun `standalone four-digit OTP sample is masked in receipts`() {
        val content = parseJson(
            """
            {
              "status": "ok",
              "ocr_mode": "auto",
              "ocr_triggered": true,
              "ocr_block_count": 1,
              "ocr_source": "bitmap_ocr",
              "ocr_outcome": "recognized",
              "ocr_blocks": [{"text": "8492", "sensitive": true}]
            }
            """.trimIndent()
        )

        val receipt = ScreenToolReceiptParser.parse(
            toolName = "screen_read_ui",
            arguments = parseJson("""{}"""),
            content = content,
        )
        val sample = requireNotNull(receipt?.ocrSummary).sampleTexts.single()
        assertTrue(sample.contains("******"))
        assertFalse(sample.contains("8492"))
    }

    @Test
    fun `standalone five-digit OTP sample is masked in receipts`() {
        val content = parseJson(
            """
            {
              "status": "ok",
              "ocr_mode": "auto",
              "ocr_triggered": true,
              "ocr_block_count": 1,
              "ocr_source": "bitmap_ocr",
              "ocr_outcome": "recognized",
              "ocr_blocks": [{"text": "38491", "sensitive": true}]
            }
            """.trimIndent()
        )

        val receipt = ScreenToolReceiptParser.parse(
            toolName = "screen_read_ui",
            arguments = parseJson("""{}"""),
            content = content,
        )
        val sample = requireNotNull(receipt?.ocrSummary).sampleTexts.single()
        assertTrue(sample.contains("******"))
        assertFalse(sample.contains("38491"))
    }

    @Test
    fun `accessibility-only OCR fallback is not presented as bitmap OCR`() {
        val content = parseJson(
            """
            {
              "status": "ok",
              "ocr_mode": "auto",
              "ocr_triggered": true,
              "ocr_reason": "no_visible_text",
              "ocr_block_count": 1,
              "ocr_source": "extended_accessibility",
              "ocr_outcome": "degraded",
              "ocr_diagnostics": "screenshot_unavailable",
              "ocr_blocks": [{"text": "设置", "source": "extended_accessibility"}]
            }
            """.trimIndent()
        )

        val receipt = ScreenToolReceiptParser.parse(
            toolName = "screen_read_ui",
            arguments = parseJson("""{}"""),
            content = content,
        )
        requireNotNull(receipt)
        val ocr = requireNotNull(receipt.ocrSummary)
        assertEquals("extended_accessibility", ocr.source)
        assertEquals("degraded", ocr.outcome)
        assertEquals("screenshot_unavailable", ocr.diagnostics)
        assertFalse(ocr.isBitmapOcr)
        assertTrue(ocr.isAccessibilityOnly)
    }

    @Test
    fun `bitmap ocr receipt exposes recognizer and keeps bitmap source`() {
        val content = parseJson(
            """
            {
              "status": "ok",
              "ocr_mode": "auto",
              "ocr_triggered": true,
              "ocr_block_count": 2,
              "ocr_source": "bitmap_ocr",
              "ocr_outcome": "recognized",
              "ocr_recognizer": "mlkit_text_recognition_chinese",
              "ocr_blocks": [{"text": "设置"}, {"text": "Wi-Fi"}]
            }
            """.trimIndent()
        )

        val receipt = ScreenToolReceiptParser.parse(
            toolName = "screen_read_ui",
            arguments = parseJson("""{}"""),
            content = content,
        )
        val ocr = requireNotNull(receipt?.ocrSummary)
        assertTrue(ocr.isBitmapOcr)
        assertFalse(ocr.isAccessibilityOnly)
        assertEquals("mlkit_text_recognition_chinese", ocr.recognizer)
    }
}
