package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.amber.core.utils.stripReasoningMarkdown

/** Thinking prose uses the answer renderer's bounded, self-drying tail reveal. */
@Composable
fun StreamingPlainText(
    text: String,
    streaming: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxVisibleChars: Int = Int.MAX_VALUE,
    omittedPrefixTemplate: String = "",
    scrollState: ScrollState? = null,
    followTail: Boolean = true,
) {
    val displaySource = remember(text) {
        repairUnpairedInlineMarkers(text).stripReasoningMarkdown()
    }
    // Pace the stable source BEFORE cropping. A moving window is not an append.
    val visible = rememberStreamingDisplayText(content = displaySource, streaming = streaming)
    val window = remember { PlainTextDisplayWindow() }
    val displayed = remember(visible, maxVisibleChars, followTail, omittedPrefixTemplate) {
        window.update(visible, maxVisibleChars, followTail, omittedPrefixTemplate)
    }
    val releaseRate = remember { ReleaseRateTracker() }
    var lastAppendMs by remember { mutableLongStateOf(0L) }
    var settleNowMs by remember { mutableLongStateOf(0L) }
    // Static exports/history stay opaque. A tracked stream drains and dries even
    // if loading ends without one final text change.
    var trackedStream by remember { mutableStateOf(streaming) }
    SideEffect { if (streaming) trackedStream = true }
    LaunchedEffect(visible, streaming) {
        if (!streaming && !trackedStream) return@LaunchedEffect
        val now = withFrameNanos { it / 1_000_000L }
        releaseRate.record(now, visible.length)
        lastAppendMs = now
        do {
            settleNowMs = withFrameNanos { it / 1_000_000L }
        } while (revealWindowChars(releaseRate.ratePerSecond(settleNowMs), settleNowMs - now) > 0)
    }
    val revealChars = if (streaming || trackedStream) {
        revealWindowChars(
            releaseRate.ratePerSecond(settleNowMs),
            (settleNowMs - lastAppendMs).coerceAtLeast(0L),
        )
    } else 0
    val baseColor = style.color.takeOrElse { Color.Black }
    val annotated = remember(displayed, revealChars, baseColor) {
        applyStreamingWindowReveal(AnnotatedString(displayed), revealChars, baseColor)
    }
    BasicText(
        text = annotated,
        modifier = modifier.padding(start = 4.dp)
            .onGloballyPositioned { window.applyScrollAdjustment(scrollState) },
        style = style,
        onTextLayout = { window.onTextLayout(it, scrollState) },
    )
}

/**
 * Keep the existing text budget, but remove complete measured lines rather than
 * shifting every character on every chunk. The retained line keeps its screen
 * position. While reading back we retain the window instead of deleting text
 * underneath the user's finger.
 */
private class PlainTextDisplayWindow {
    private var previous = ""
    private var start = 0
    private var prefixLength = 0
    private var limit = Int.MAX_VALUE
    private var layout: TextLayoutResult? = null
    private var retainedLineTop: Float? = null
    private var retainedSourceOffset = 0
    private var scrollAdjustment = 0f

    fun update(text: String, maxChars: Int, followTail: Boolean, prefixTemplate: String): String {
        if (!text.startsWith(previous)) {
            start = 0
            layout = null
            retainedLineTop = null
        } else if (maxChars > limit && start > 0) {
            // Expanding the completed thought restores its longer history without
            // moving the line that was visible in the streaming window.
            retainedLineTop = layout?.let { it.getLineTop(it.getLineForOffset(prefixLength)) }
            retainedSourceOffset = start
            start = 0
            layout = null
        }
        val currentLayout = layout
        val excess = text.length - start - maxChars
        if (followTail && excess > 0) {
            if (currentLayout != null && currentLayout.layoutInput.text.isNotEmpty()) {
                val offset = (prefixLength + excess).coerceAtMost(currentLayout.layoutInput.text.lastIndex)
                val line = currentLayout.getLineForOffset(offset)
                val removedChars = currentLayout.getLineStart(line) - prefixLength
                if (removedChars > 0) {
                    start += removedChars
                    retainedLineTop = currentLayout.getLineTop(line)
                    retainedSourceOffset = start
                }
            } else {
                // First layout / settled history has no existing reading anchor.
                start = text.length - maxChars
                if (start > 0 && Character.isLowSurrogate(text[start])) start--
            }
        }
        previous = text
        limit = maxChars
        val prefix = if (start > 0 && prefixTemplate.isNotEmpty()) prefixTemplate.format(start) + "\n\n" else ""
        prefixLength = prefix.length
        return prefix + text.substring(start)
    }

    fun onTextLayout(result: TextLayoutResult, scrollState: ScrollState?) {
        retainedLineTop?.let { oldTop ->
            val newTop = result.getLineTop(result.getLineForOffset(prefixLength + retainedSourceOffset - start))
            val delta = newTop - oldTop
            if (delta < 0f) {
                // Remove rows before verticalScroll clamps to its smaller range;
                // applying this after the clamp would subtract the height twice.
                scrollState?.dispatchRawDelta(delta)
            } else {
                scrollAdjustment = delta
            }
            retainedLineTop = null
        }
        layout = result
    }

    fun applyScrollAdjustment(scrollState: ScrollState?) {
        val delta = scrollAdjustment
        scrollAdjustment = 0f
        // Apply after verticalScroll has measured its new range, before drawing.
        // This preserves geometry; it is not an auto-follow scroll mutation.
        if (delta != 0f) scrollState?.dispatchRawDelta(delta)
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
