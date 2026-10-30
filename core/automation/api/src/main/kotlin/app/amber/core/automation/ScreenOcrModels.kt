package app.amber.core.automation

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Controls whether on-device OCR is used during screen observation or text verification.
 *
 * - [AUTO]: Pure Accessibility UI tree first (zero screenshot/OCR overhead when tree coverage
 *   is sufficient); automatically falls back to on-device OCR when tree text coverage is low
 *   (WebView, Canvas, custom-drawn UI, unlabeled interactive nodes) or when post-action
 *   verification cannot find expected text in the Accessibility tree.
 * - [ON]: Explicitly enabled by the caller/model; always runs on-device OCR and attaches
 *   recognized text blocks with normalized `[0, 1]` coordinates.
 * - [OFF]: Explicitly disabled by the caller/model; never runs screenshot/OCR fallback.
 */
enum class ScreenOcrMode {
    AUTO,
    ON,
    OFF,
    ;

    fun wireName(): String = name.lowercase(Locale.ROOT)

    companion object {
        fun parse(ocrModeRaw: String?, includeOcrRaw: Boolean? = null): ScreenOcrMode {
            val normalized = ocrModeRaw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when (normalized) {
                "on", "true", "force", "always", "enabled" -> ON
                "off", "false", "none", "never", "disabled" -> OFF
                "auto", "fallback", "default" -> AUTO
                else -> when (includeOcrRaw) {
                    true -> ON
                    false -> OFF
                    null -> AUTO
                }
            }
        }
    }
}

/**
 * Normalized `[0.0, 1.0]` screen coordinates aligned to the active display dimensions.
 */
data class NormalizedScreenBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val centerX: Float,
    val centerY: Float,
) {
    fun toPixelCenter(displayWidth: Int, displayHeight: Int): Pair<Float, Float> {
        val w = displayWidth.coerceAtLeast(1)
        val h = displayHeight.coerceAtLeast(1)
        return (centerX.coerceIn(0f, 1f) * w) to (centerY.coerceIn(0f, 1f) * h)
    }

    companion object {
        fun fromPixelBounds(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            displayWidth: Int,
            displayHeight: Int,
        ): NormalizedScreenBounds {
            val w = displayWidth.coerceAtLeast(1).toFloat()
            val h = displayHeight.coerceAtLeast(1).toFloat()
            val normLeft = (left / w).coerceIn(0f, 1f).round4()
            val normTop = (top / h).coerceIn(0f, 1f).round4()
            val normRight = (right / w).coerceIn(normLeft, 1f).round4()
            val normBottom = (bottom / h).coerceIn(normTop, 1f).round4()
            val normCenterX = ((normLeft + normRight) / 2f).coerceIn(0f, 1f).round4()
            val normCenterY = ((normTop + normBottom) / 2f).coerceIn(0f, 1f).round4()
            return NormalizedScreenBounds(
                left = normLeft,
                top = normTop,
                right = normRight,
                bottom = normBottom,
                centerX = normCenterX,
                centerY = normCenterY,
            )
        }

        private fun Float.round4(): Float = (this * 10_000f).roundToInt() / 10_000f
    }
}

/**
 * One recognized text region from on-device OCR with both pixel bounds and normalized `[0, 1]` coordinates.
 */
data class ScreenOcrBlock(
    val id: String,
    val text: String,
    val confidence: Float = 0.9f,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val normalizedBounds: NormalizedScreenBounds,
    val isSensitive: Boolean = false,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Coverage diagnostics for an Accessibility UI tree observation.
 */
data class ScreenTreeCoverageReport(
    val totalNodes: Int,
    val labeledNodes: Int,
    val interactiveNodes: Int,
    val unlabeledInteractiveNodes: Int,
    val customCanvasOrWebViewNodes: Int,
    val coverageRatio: Float,
    val isLowCoverage: Boolean,
    val reason: String,
)

/**
 * Evaluates whether an Accessibility UI tree has sufficient visible text coverage or needs
 * on-device OCR fallback (e.g., WebView, Canvas, custom-drawn views, or unlabeled nodes).
 */
object ScreenTreeCoverageAnalyzer {
    private val CUSTOM_SURFACE_CLASS_MARKERS = listOf(
        "webview",
        "canvas",
        "surfaceview",
        "textureview",
        "flutterview",
        "fluttersurfaceview",
        "reactrootview",
        "lynxview",
        "glsurfaceview",
        "skia",
    )

    fun analyze(snapshot: ScreenSnapshot?): ScreenTreeCoverageReport {
        val nodes = snapshot?.nodes.orEmpty()
        if (nodes.isEmpty()) {
            return ScreenTreeCoverageReport(
                totalNodes = 0,
                labeledNodes = 0,
                interactiveNodes = 0,
                unlabeledInteractiveNodes = 0,
                customCanvasOrWebViewNodes = 0,
                coverageRatio = 0f,
                isLowCoverage = true,
                reason = "empty_tree",
            )
        }
        val totalNodes = nodes.size
        val labeledNodes = nodes.count { node ->
            val clean = node.label.trim()
            clean.isNotEmpty() && clean != "[password]"
        }
        val interactiveNodes = nodes.count { it.clickable || it.editable || it.scrollable }
        val unlabeledInteractiveNodes = nodes.count { (it.clickable || it.editable) && it.label.isBlank() }
        val customNodes = nodes.count { node ->
            val lowerClass = node.className.lowercase(Locale.ROOT)
            val lowerId = node.viewId.lowercase(Locale.ROOT)
            CUSTOM_SURFACE_CLASS_MARKERS.any { marker -> marker in lowerClass || marker in lowerId }
        }
        val ratio = (labeledNodes.toFloat() / totalNodes.coerceAtLeast(1).toFloat())
            .let { (it * 10_000f).roundToInt() / 10_000f }

        val (lowCoverage, reason) = when {
            labeledNodes == 0 -> true to "no_visible_text"
            customNodes > 0 && labeledNodes <= 2 -> true to "webview_or_canvas_low_text"
            totalNodes >= 4 && ratio < 0.25f -> true to "low_labeled_node_ratio"
            interactiveNodes >= 3 &&
                unlabeledInteractiveNodes.toFloat() / interactiveNodes.toFloat() >= 0.75f ->
                true to "unlabeled_interactive_nodes"
            else -> false to "tree_coverage_sufficient"
        }

        return ScreenTreeCoverageReport(
            totalNodes = totalNodes,
            labeledNodes = labeledNodes,
            interactiveNodes = interactiveNodes,
            unlabeledInteractiveNodes = unlabeledInteractiveNodes,
            customCanvasOrWebViewNodes = customNodes,
            coverageRatio = ratio,
            isLowCoverage = lowCoverage,
            reason = reason,
        )
    }

    fun analyzeUiTreeDump(uiTreeDump: String, snapshot: ScreenSnapshot? = null): ScreenTreeCoverageReport {
        if (snapshot != null) return analyze(snapshot)
        val lines = uiTreeDump.lineSequence().map { it.trim() }.filter { it.startsWith("-") }.toList()
        if (lines.isEmpty()) {
            return ScreenTreeCoverageReport(
                totalNodes = 0,
                labeledNodes = 0,
                interactiveNodes = 0,
                unlabeledInteractiveNodes = 0,
                customCanvasOrWebViewNodes = 0,
                coverageRatio = 0f,
                isLowCoverage = true,
                reason = "empty_tree",
            )
        }
        var labeled = 0
        var interactive = 0
        var unlabeledInteractive = 0
        var customNodes = 0
        lines.forEach { line ->
            val header = line.substringBefore(" bounds=")
            val parts = header.removePrefix("- ").split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
            val className = parts.firstOrNull().orEmpty().lowercase(Locale.ROOT)
            if (CUSTOM_SURFACE_CLASS_MARKERS.any { it in className }) {
                customNodes++
            }
            // In dumpUiTree, parts after className and optional resource-id (containing ':id/') are text/desc labels.
            val nonMetaParts = parts.drop(1).filterNot { ":id/" in it || it.startsWith("id/") }
            val hasLabel = nonMetaParts.any { it.isNotBlank() }
            val isClickable = "clickable=true" in line
            if (hasLabel) labeled++
            if (isClickable) {
                interactive++
                if (!hasLabel) unlabeledInteractive++
            }
        }
        val total = lines.size
        val ratio = (labeled.toFloat() / total.coerceAtLeast(1).toFloat())
            .let { (it * 10_000f).roundToInt() / 10_000f }
        val (lowCoverage, reason) = when {
            labeled == 0 -> true to "no_visible_text"
            customNodes > 0 && labeled <= 2 -> true to "webview_or_canvas_low_text"
            total >= 4 && ratio < 0.25f -> true to "low_labeled_node_ratio"
            interactive >= 3 && unlabeledInteractive.toFloat() / interactive.toFloat() >= 0.75f ->
                true to "unlabeled_interactive_nodes"
            else -> false to "tree_coverage_sufficient"
        }
        return ScreenTreeCoverageReport(
            totalNodes = total,
            labeledNodes = labeled,
            interactiveNodes = interactive,
            unlabeledInteractiveNodes = unlabeledInteractive,
            customCanvasOrWebViewNodes = customNodes,
            coverageRatio = ratio,
            isLowCoverage = lowCoverage,
            reason = reason,
        )
    }
}

/**
 * Structured result of an on-device OCR observation pass.
 */
data class ScreenOcrObservation(
    val mode: ScreenOcrMode,
    val triggered: Boolean,
    val triggerReason: String,
    val coverage: ScreenTreeCoverageReport,
    val blocks: List<ScreenOcrBlock>,
    val displayWidth: Int,
    val displayHeight: Int,
    val screenshotSource: String? = null,
) {
    fun matchesQuery(query: String): List<ScreenOcrBlock> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return blocks.filter { block -> block.text.contains(needle, ignoreCase = true) }
    }

    fun combinedVisibleText(maxChars: Int = 4_000): String =
        blocks.map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .take(maxChars)
}

/**
 * Pluggable on-device OCR engine abstraction used by [ScreenAutomationTools] and [JevScreenGoalRunner].
 */
interface ScreenOcrEngine {
    suspend fun observe(
        snapshot: ScreenSnapshot?,
        uiTreeDump: String = "",
        mode: ScreenOcrMode = ScreenOcrMode.AUTO,
        forceReason: String? = null,
    ): ScreenOcrObservation
}
