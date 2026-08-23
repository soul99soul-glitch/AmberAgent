package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.amber.core.utils.stripReasoningMarkdown
import kotlinx.coroutines.delay

/**
 * Plain-text streaming renderer for the thinking box.
 *
 * Reasoning is human prose, not document markdown — a `##` headline or bold
 * run inside a 13.5sp card reads as visual noise. This composable strips
 * markdown markers on the DISPLAY layer only (the stored/exported reasoning
 * keeps its original text), then reuses the exact same reveal machinery as
 * the answer body: the word-quantized display buffer
 * ([rememberStreamingDisplayText]) for pacing and the per-character fade
 * clock ([StreamingCharRevealClock]) so characters materialize with the same
 * light sweep as the answer text.
 *
 * Settled instances (export page, reopened history) never run the reveal:
 * the clock only renders through [applyStreamingCharRevealPlain] once a frame
 * callback has actually advanced it — stamping against the frozen seed time
 * would paint every character at alpha 0 (invisible text).
 *
 * Streaming inputs are marker-repaired before stripping so a half-typed
 * `**bol` doesn't flash raw asterisks: an unpaired closer is appended first
 * (display-only), the pair is stripped, and the visible text stays a stable
 * prefix as the marker completes upstream.
 */
@Composable
fun StreamingPlainText(
    text: String,
    streaming: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val displaySource = remember(text) {
        repairUnpairedInlineMarkers(text).stripReasoningMarkdown()
    }
    val visible = rememberStreamingDisplayText(
        content = displaySource,
        streaming = streaming,
    )
    val clock = remember { StreamingCharRevealClock() }
    // Seeded with System.nanoTime (same timebase as Choreographer frame nanos)
    // so pre-first-frame characters don't read as ages old and skip the fade.
    var revealNowNanos by remember { mutableLongStateOf(System.nanoTime()) }
    // True once a frame callback has run — the only state that gates whether
    // the reveal path is allowed to render. Instances composed in the settled
    // state never flip it and render plain text.
    var framesAdvanced by remember { mutableStateOf(false) }
    val updatedStreaming by rememberUpdatedState(streaming)
    val updatedSource by rememberUpdatedState(displaySource)
    val updatedVisible by rememberUpdatedState(visible)
    LaunchedEffect(displaySource) {
        if (!updatedStreaming && updatedVisible == updatedSource) {
            // Composed settled (export / history): no pacing, no reveal.
            return@LaunchedEffect
        }
        // Frame clock: runs through streaming AND the post-stream drain, then
        // holds ~300ms past the last text change so the final stamped
        // characters complete their fade before the loop exits. Exiting on
        // the settle flip alone would freeze them mid-sweep.
        var lastChangeNanos = System.nanoTime()
        var lastLength = -1
        while (true) {
            withFrameNanos { frameNanos ->
                revealNowNanos = frameNanos
                framesAdvanced = true
            }
            val length = updatedVisible.length
            if (length != lastLength) {
                lastLength = length
                lastChangeNanos = System.nanoTime()
            }
            val settled = !updatedStreaming && length == updatedSource.length
            if (settled && System.nanoTime() - lastChangeNanos > 300_000_000L) break
            if (settled) delay(16)
        }
    }
    val baseColor = style.color.takeOrElse { Color.Black }
    // The reveal rendering is only taken once frames have actually advanced.
    // By the time the loop exits, every stamp is ≥300ms old = fully opaque,
    // so the last annotated frame is visually identical to plain text — no
    // alpha snap at the transition.
    val annotated = if (framesAdvanced) {
        remember(visible, revealNowNanos) {
            applyStreamingCharRevealPlain(
                text = visible,
                suffixSourceOffset = 0,
                clock = clock,
                nowNanos = revealNowNanos,
                baseColor = baseColor,
            )
        }
    } else {
        null
    }
    if (annotated != null) {
        BasicText(
            text = annotated,
            modifier = modifier.padding(start = 4.dp),
            style = style,
        )
    } else {
        BasicText(
            text = visible,
            modifier = modifier.padding(start = 4.dp),
            style = style,
        )
    }
}

/**
 * Appends missing closers for unpaired inline markers (display-only repair).
 * Fence lines are excluded from the backtick count: an OPEN code fence
 * (```kotlin … still streaming) must not gain a stray inline backtick on its
 * content's last line.
 */
internal fun repairUnpairedInlineMarkers(text: String): String {
    var repaired = text
    if (countOccurrences(repaired, "**") % 2 == 1) {
        // `***bold-italic***` parses as nested pairs; the approximation is
        // fine — one extra closer only affects the still-growing tail.
        repaired += "**"
    }
    if (countOccurrences(repaired, "~~") % 2 == 1) {
        repaired += "~~"
    }
    val fenceLineCount = FENCE_LINE_PREFIX.findAll(repaired).count()
    val inlineBackticks = repaired.count { it == '`' } - 3 * fenceLineCount
    if (inlineBackticks % 2 == 1) {
        repaired += "`"
    }
    return repaired
}

private val FENCE_LINE_PREFIX = Regex("(?m)^\\s*```")

private fun countOccurrences(text: String, token: String): Int {
    var index = text.indexOf(token)
    var count = 0
    while (index >= 0) {
        count++
        index = text.indexOf(token, index + token.length)
    }
    return count
}
