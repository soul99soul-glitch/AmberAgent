package app.amber.feature.tools

import app.amber.core.automation.NormalizedScreenBounds
import app.amber.core.automation.ScreenOcrBlock
import app.amber.core.automation.ScreenOcrMode
import app.amber.core.automation.ScreenOcrObservation
import app.amber.core.automation.ScreenOcrOutcome
import app.amber.core.automation.ScreenOcrSource
import app.amber.core.automation.ScreenTreeCoverageAnalyzer
import app.amber.core.automation.SensitiveScreenTextClassifier
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOcrTelemetryTest {

    private fun block(
        id: String,
        text: String,
        sensitive: Boolean,
        redacted: String,
    ) = ScreenOcrBlock(
        id = id,
        text = text,
        left = 100,
        top = 100,
        right = 400,
        bottom = 160,
        normalizedBounds = NormalizedScreenBounds.fromPixelBounds(100, 100, 400, 160, 1000, 2000),
        isSensitive = sensitive,
        source = ScreenOcrSource.BITMAP_OCR,
        redactedText = redacted,
    )

    @Test
    fun ocrTelemetryNeverEmitsRawOtpOrPasswordValues() {
        val blocks = listOf(
            block(
                id = "ocr_0",
                text = "验证码",
                sensitive = true,
                redacted = SensitiveScreenTextClassifier.redactForDisplay("验证码"),
            ),
            block(
                id = "ocr_1",
                text = "8492",
                sensitive = true,
                redacted = SensitiveScreenTextClassifier.redactForDisplay("8492"),
            ),
            block(
                id = "ocr_1b",
                text = "38491",
                sensitive = true,
                redacted = SensitiveScreenTextClassifier.redactForDisplay("38491"),
            ),
            block(
                id = "ocr_2",
                text = "hunter2",
                sensitive = true,
                redacted = SensitiveScreenTextClassifier.MASKED_SENSITIVE_TEXT,
            ),
        )
        val observation = ScreenOcrObservation(
            mode = ScreenOcrMode.AUTO,
            triggered = true,
            triggerReason = "no_visible_text",
            coverage = ScreenTreeCoverageAnalyzer.analyzeUiTreeDump(""),
            blocks = blocks,
            displayWidth = 1000,
            displayHeight = 2000,
            source = ScreenOcrSource.BITMAP_OCR,
            outcome = ScreenOcrOutcome.RECOGNIZED,
            recognizer = "mlkit_text_recognition_chinese",
        )

        val json = buildJsonObject { appendOcrTelemetry(observation) }.toString()

        assertFalse(json.contains("8492"))
        assertFalse(json.contains("38491"))
        assertFalse(json.contains("hunter2"))
        assertTrue(json.contains("******"))
        assertTrue(json.contains(SensitiveScreenTextClassifier.MASKED_SENSITIVE_TEXT))
        // Block-level source is preserved for consumers that must not confuse a11y metadata.
        assertTrue(json.contains("\"source\":\"bitmap_ocr\""))
    }
}
