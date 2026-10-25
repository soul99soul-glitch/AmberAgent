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

        fun fromInput(ocrModeRaw: String?, includeOcrRaw: Boolean? = null): ScreenOcrMode =
            parse(ocrModeRaw, includeOcrRaw)
    }
}

/**
 * Where an OCR observation / block actually came from.
 *
 * Callers must not flatten [BITMAP_OCR] and [EXTENDED_ACCESSIBILITY] into one
 * "OCR succeeded" signal: only the former is real pixel recognition (works on
 * WebView / Canvas / custom-drawn pages), the latter is extra metadata read
 * from Accessibility nodes and cannot see unlabelled pixels.
 */
enum class ScreenOcrSource {
    /** Text recognized from a captured screen bitmap by the on-device recognizer. */
    BITMAP_OCR,

    /** Text recovered from extra Accessibility node attributes (hint/tooltip/stateDescription/semantics). */
    EXTENDED_ACCESSIBILITY,

    /** Nothing usable was produced. */
    UNAVAILABLE,
    ;

    fun wireName(): String = name.lowercase(Locale.ROOT)

    val isBitmapOcr: Boolean get() = this == BITMAP_OCR
}

/**
 * Honest outcome of one observation pass.
 *
 * - [NOT_TRIGGERED]: tree coverage was sufficient (or OCR was disabled); zero screenshot/OCR.
 * - [RECOGNIZED]: bitmap OCR produced text.
 * - [DEGRADED]: bitmap OCR was unavailable/failed/timed out, only Accessibility metadata was found.
 * - [UNAVAILABLE]: triggered but no text and a diagnosable failure reason.
 */
enum class ScreenOcrOutcome {
    NOT_TRIGGERED,
    RECOGNIZED,
    DEGRADED,
    UNAVAILABLE,
    ;

    fun wireName(): String = name.lowercase(Locale.ROOT)

    val isBitmapOcr: Boolean get() = this == RECOGNIZED
}

/**
 * Scripts the **bundled, compiled-in** recognizer actually supports.
 *
 * The ML Kit `text-recognition-chinese` model recognizes Chinese (Simplified and
 * Traditional) plus Latin script, which covers the app's Chinese/English screen
 * automation paths. Japanese, Korean, Cyrillic and Devanagari are intentionally
 * not claimed — they would need their own ML Kit model artifacts.
 */
object ScreenOcrCapabilities {
    /** Stable identifier surfaced in diagnostics/receipts. */
    const val RECOGNIZER_ID: String = "mlkit_text_recognition_chinese"

    val supportedScripts: List<String> = listOf("zh-Hans", "zh-Hant", "latin")

    val unsupportedScripts: List<String> = listOf("ja", "ko", "ru", "devanagari")

    fun describe(): String = "ML Kit bundled text recognition (Chinese + Latin)"
}

/**
 * Classifies and redacts sensitive text (passwords, OTP codes, payment/card numbers)
 * so OCR summaries, model-facing text and step receipts never leak raw secrets.
 *
 * Lives in the shared automation API so every consumer (engine, tools, receipts,
 * Jev runner) applies the exact same rules.
 */
object SensitiveScreenTextClassifier {
    const val MASKED_SENSITIVE_TEXT: String = "[已隐藏敏感内容]"

    private val SENSITIVE_KEYWORDS = Regex(
        "密码|验证码|校验码|动态码|安全码|支付密码|银行卡|身份证|cvv|pin|password|passcode|otp|verification\\s*code|security\\s*code|secret|token",
        RegexOption.IGNORE_CASE,
    )
    private val OTP_OR_CARD_DIGITS = Regex("\\b\\d{6,19}\\b")
    private val STANDALONE_DIGITS_4_TO_8 = Regex("^\\d{4,8}$")
    private val SECRET_TOKEN = Regex("^[A-Za-z0-9@#$%^&*._-]{4,16}$")
    private val DIGIT_RUN_4_TO_19 = Regex("\\d{4,19}")
    private val KEY_VALUE_SECRET = Regex(
        "((?:密码|验证码|校验码|动态码|安全码|支付密码|银行卡|身份证|cvv|pin|password|passcode|otp|verification\\s*code|security\\s*code|secret|token)\\s*(?:[:=：]|\\s+)\\s*)([^\\s\"',;，；]+)",
        RegexOption.IGNORE_CASE,
    )

    fun isSensitive(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "[password]" || trimmed == MASKED_SENSITIVE_TEXT) return true
        return SENSITIVE_KEYWORDS.containsMatchIn(trimmed) || OTP_OR_CARD_DIGITS.containsMatchIn(trimmed)
    }

    /**
     * Conservative OTP candidate: a block that is only 4-8 digits, with no
     * surrounding label in the same string. "验证码" and "8492" often arrive as
     * two ML Kit blocks, and a 4/5 digit code has no keyword of its own.
     *
     * This over-redacts short standalone numbers (years, prices, counts) rather
     * than leak an OTP. It does not identify every secret: a password such as
     * `hunter2` still needs password/sensitive node bounds or a nearby label.
     */
    fun isStandaloneNumericSecret(text: String): Boolean =
        STANDALONE_DIGITS_4_TO_8.matches(text.trim())

    /** Token-like (single word, contains digits) blocks near a sensitive label. */
    fun looksLikeSecretToken(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() &&
            SECRET_TOKEN.matches(trimmed) &&
            trimmed.any { it.isDigit() } &&
            !isSensitive(trimmed)
    }

    fun redactIfSensitive(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (!isSensitive(trimmed)) return trimmed
        var redacted = OTP_OR_CARD_DIGITS.replace(trimmed) { match ->
            val len = match.value.length
            if (len <= 6) "******" else "${match.value.take(2)}****${match.value.takeLast(2)}"
        }
        redacted = KEY_VALUE_SECRET.replace(redacted) { match ->
            val prefix = match.groupValues[1]
            val value = match.groupValues[2]
            if (value.all { it == '*' } || SENSITIVE_KEYWORDS.matches(value)) {
                match.value
            } else {
                "$prefix******"
            }
        }
        return redacted
    }

    /**
     * Model/receipt-facing redaction for one OCR block.
     *
     * - [maskEntirely]: drop the whole block (password field bounds / contextual secret).
     * - standalone 4-8 digit block: mask the digits (no keyword required).
     * - keyword/6+ digit block: keep the label, mask the value ([redactIfSensitive]).
     */
    fun redactForDisplay(text: String, maskEntirely: Boolean = false): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (maskEntirely) return MASKED_SENSITIVE_TEXT
        if (isStandaloneNumericSecret(trimmed)) return DIGIT_RUN_4_TO_19.replace(trimmed) { "******" }
        return redactIfSensitive(trimmed)
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
    val source: ScreenOcrSource = ScreenOcrSource.BITMAP_OCR,
    /**
     * Model/receipt-facing text. Sensitive blocks are already redacted here so no
     * consumer has to remember a second call; [text] stays raw for in-engine matching.
     */
    val redactedText: String = SensitiveScreenTextClassifier.redactForDisplay(text),
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
    /** Real source of the content: bitmap OCR vs extended Accessibility vs nothing. */
    val source: ScreenOcrSource = ScreenOcrSource.UNAVAILABLE,
    /** Honest outcome; [ScreenOcrOutcome.DEGRADED] must never be presented as bitmap OCR. */
    val outcome: ScreenOcrOutcome = ScreenOcrOutcome.NOT_TRIGGERED,
    /** Machine-readable failure/degradation codes (screenshot_unavailable, model_not_found, ocr_no_text, ...). */
    val diagnostics: List<String> = emptyList(),
    /** Which compiled recognizer produced bitmap text (null when it never ran). */
    val recognizer: String? = null,
) {
    val isBitmapOcr: Boolean get() = source.isBitmapOcr

    val degraded: Boolean get() = outcome == ScreenOcrOutcome.DEGRADED

    val diagnosticSummary: String?
        get() = diagnostics.takeIf { it.isNotEmpty() }?.joinToString(";")

    fun matchesQuery(query: String): List<ScreenOcrBlock> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return blocks.filter { block -> block.text.contains(needle, ignoreCase = true) }
    }

    fun findMatches(query: String): List<ScreenOcrBlock> = matchesQuery(query)

    fun combinedVisibleText(maxChars: Int = 4_000): String =
        blocks.map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .take(maxChars)

    fun mergedText(maxChars: Int = 4_000): String = combinedVisibleText(maxChars)

    /**
     * Model/receipt-facing text: sensitive blocks are redacted block-by-block, so
     * OTP codes / passwords / card numbers never leak while bounds stay usable.
     */
    fun redactedText(maxChars: Int = 4_000): String =
        blocks
            .map { it.redactedText.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .take(maxChars)
}

/**
 * Pluggable on-device OCR engine abstraction used by [ScreenAutomationTools] and [JevScreenGoalRunner].
 */
interface ScreenOcrEngine : AutoCloseable {
    suspend fun observe(
        snapshot: ScreenSnapshot?,
        uiTreeDump: String = "",
        mode: ScreenOcrMode = ScreenOcrMode.AUTO,
        forceReason: String? = null,
    ): ScreenOcrObservation

    override fun close() = Unit
}
