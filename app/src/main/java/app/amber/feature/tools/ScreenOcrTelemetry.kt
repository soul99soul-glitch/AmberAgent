package app.amber.feature.tools

import app.amber.core.automation.ScreenOcrBlock
import app.amber.core.automation.ScreenOcrObservation
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tool-facing OCR serialization.
 *
 * Uses [ScreenOcrBlock.redactedText] (already redacted by the engine, including
 * password-bounds and standalone-OTP cases) so no raw password / OTP / card value
 * can reach model context, receipts, or Jev.
 */
internal fun ScreenOcrBlock.toMatchJson(index: Int) = buildJsonObject {
    put("index", index)
    put("source", source.wireName())
    put("id", id)
    put("text", redactedText)
    put("content_description", "")
    put("class_name", if (source.isBitmapOcr) "ocr.text_block" else "accessibility.extended_metadata")
    put("view_id", "")
    put("clickable", true)
    put("enabled", true)
    put("confidence", confidence.toDouble())
    put("sensitive", isSensitive)
    put("bounds", buildJsonObject {
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
        put("center_x", centerX.toDouble())
        put("center_y", centerY.toDouble())
    })
    put("normalized_bounds", buildJsonObject {
        put("left", normalizedBounds.left.toDouble())
        put("top", normalizedBounds.top.toDouble())
        put("right", normalizedBounds.right.toDouble())
        put("bottom", normalizedBounds.bottom.toDouble())
        put("center_x", normalizedBounds.centerX.toDouble())
        put("center_y", normalizedBounds.centerY.toDouble())
    })
}

internal fun JsonObjectBuilder.appendOcrTelemetry(
    observation: ScreenOcrObservation,
) {
    put("ocr_mode", observation.mode.wireName())
    put("ocr_triggered", observation.triggered)
    put("ocr_reason", observation.triggerReason)
    put("ocr_block_count", observation.blocks.size)
    put("ocr_source", observation.source.wireName())
    put("ocr_outcome", observation.outcome.wireName())
    observation.recognizer?.let { put("ocr_recognizer", it) }
    observation.diagnosticSummary?.let { put("ocr_diagnostics", it) }
    if (observation.triggered) {
        put("ocr_text", observation.redactedText(maxChars = 4_000))
        put("ocr_blocks", buildJsonArray {
            observation.blocks.forEachIndexed { index, block ->
                add(block.toMatchJson(index))
            }
        })
    }
    observation.coverage.let { cov ->
        put("tree_coverage", buildJsonObject {
            put("total_nodes", cov.totalNodes)
            put("labeled_nodes", cov.labeledNodes)
            put("interactive_nodes", cov.interactiveNodes)
            put("unlabeled_interactive_nodes", cov.unlabeledInteractiveNodes)
            put("custom_canvas_or_webview_nodes", cov.customCanvasOrWebViewNodes)
            put("coverage_ratio", cov.coverageRatio.toDouble())
            put("is_low_coverage", cov.isLowCoverage)
            put("reason", cov.reason)
        })
    }
}
