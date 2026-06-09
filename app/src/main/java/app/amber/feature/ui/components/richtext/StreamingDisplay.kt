package app.amber.feature.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.amber.agent.PerfFlags

/**
 * Marker object: the markdown subtree is rendering an actively growing
 * streaming tail. Batch reveal (suffix fade, block motion) gates on
 * [LocalStreamingTailActive] being non-null — not on any per-codepoint state.
 */
@Stable
object StreamingTailActive

/**
 * Non-null while the active streaming block should run batch reveal motion.
 * Finalized/stable blocks receive `null` so they stay on the fast path.
 */
val LocalStreamingTailActive = compositionLocalOf<StreamingTailActive?> { null }

/**
 * Returns [StreamingTailActive] while [streaming] is true, otherwise null.
 * No frame loop or content slicing — purely a composition-local sentinel.
 */
fun streamingTailActiveWhen(streaming: Boolean): StreamingTailActive? =
    if (streaming) StreamingTailActive else null

@Composable
fun rememberStreamingDisplayText(
    content: String,
    streaming: Boolean,
    onVisibleFrame: (() -> Unit)? = null,
): String {
    if (PerfFlags.STREAMING_IMMEDIATE_CONTENT_REVEAL && streaming) {
        val visible = streamingImmediateDisplayText(content)
        val updatedOnVisibleFrame by rememberUpdatedState(onVisibleFrame)
        var previousVisible by remember { mutableStateOf<String?>(null) }
        SideEffect {
            if (previousVisible != visible) {
                previousVisible = visible
                updatedOnVisibleFrame?.invoke()
            }
        }
        return visible
    }

    var visible by remember { mutableStateOf(content) }
    val updatedContent by rememberUpdatedState(content)
    val updatedStreaming by rememberUpdatedState(streaming)
    val updatedOnVisibleFrame by rememberUpdatedState(onVisibleFrame)
    val drainingAfterStream = !streaming && visible != content && content.startsWith(visible)
    var previousStreaming by remember { mutableStateOf(streaming) }

    DisposableEffect(Unit) {
        onDispose {
            StreamingRenderProbe.dump("streaming_display_disposed")
        }
    }

    SideEffect {
        if (previousStreaming && !streaming) {
            StreamingRenderProbe.dump("streaming_display_completed")
        }
        previousStreaming = streaming
    }

    if (!streaming && visible != content && !content.startsWith(visible)) {
        SideEffect {
            visible = content
        }
    }

    LaunchedEffect(streaming, drainingAfterStream) {
        if (!streaming && !drainingAfterStream) return@LaunchedEffect
        var lastFrameNanos = 0L
        var lastEmitNanos = 0L
        var speed = STREAM_DISPLAY_BASE_CHARS_PER_SEC
        var budget = 0f
        var lastTarget = updatedContent
        while (true) {
            val frameNanos = withFrameNanos { it }
            val target = updatedContent
            val shouldDrain = updatedStreaming || (visible != target && target.startsWith(visible))
            if (!shouldDrain) {
                if (visible != target) {
                    visible = target
                    updatedOnVisibleFrame?.invoke()
                }
                return@LaunchedEffect
            }
            if (target !== lastTarget) {
                lastTarget = target
                if (visible.length > target.length || !target.startsWith(visible)) {
                    visible = target
                    speed = STREAM_DISPLAY_BASE_CHARS_PER_SEC
                    budget = 0f
                    lastEmitNanos = frameNanos
                    updatedOnVisibleFrame?.invoke()
                    lastFrameNanos = frameNanos
                    continue
                }
            }
            val backlog = target.length - visible.length
            StreamingRenderProbe.displayBacklog = backlog
            if (backlog <= 0) {
                lastFrameNanos = frameNanos
                continue
            }
            val catchUpEnd = streamingDisplayBacklogCatchUpEnd(
                visibleLength = visible.length,
                targetLength = target.length,
            )
            if (catchUpEnd > visible.length) {
                val safeEnd = target.safeStreamingDisplayEnd(catchUpEnd)
                if (safeEnd > visible.length) {
                    val skipped = safeEnd - visible.length
                    visible = target.substring(0, safeEnd.coerceAtMost(target.length))
                    budget = 0f
                    lastEmitNanos = frameNanos
                    StreamingRenderProbe.record {
                        "display_backlog_cap backlog=$backlog skipped=$skipped visible=${visible.length} target=${target.length}"
                    }
                    updatedOnVisibleFrame?.invoke()
                    continue
                }
            }
            val deltaSeconds = if (lastFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameNanos - lastFrameNanos).coerceIn(1_000_000L, 100_000_000L)) / 1_000_000_000f
            }
            lastFrameNanos = frameNanos
            val maxCharsPerSecond = if (updatedStreaming) {
                STREAM_DISPLAY_MAX_CHARS_PER_SEC
            } else {
                STREAM_DISPLAY_FINAL_MAX_CHARS_PER_SEC
            }
            val targetSpeed = streamingDisplayTargetSpeed(
                backlog = backlog,
                streaming = updatedStreaming,
                maxCharsPerSecond = maxCharsPerSecond,
            )
            speed += (targetSpeed - speed) * STREAM_DISPLAY_SPEED_ALPHA
            val maxCharsPerEmit = streamingDisplayMaxCharsPerEmit(updatedStreaming)
            budget = (budget + speed * deltaSeconds).coerceAtMost(maxCharsPerEmit.toFloat())
            val releaseCount = budget.toInt().coerceAtMost(maxCharsPerEmit)
            if (releaseCount <= 0) continue
            if (
                lastEmitNanos != 0L &&
                frameNanos - lastEmitNanos < STREAM_DISPLAY_MIN_EMIT_INTERVAL_NANOS
            ) {
                continue
            }
            val nextEnd = target.safeStreamingDisplayEnd(visible.length + releaseCount)
            val safeEnd = if (nextEnd <= visible.length) {
                target.nextCodePointEnd(visible.length)
            } else {
                nextEnd
            }
            visible = target.substring(0, safeEnd.coerceAtMost(target.length))
            budget -= releaseCount
            lastEmitNanos = frameNanos
            StreamingRenderProbe.record {
                "display_emit backlog=$backlog release=$releaseCount visible=${visible.length} target=${target.length} speed=${"%.1f".format(speed)}"
            }
            updatedOnVisibleFrame?.invoke()
        }
    }

    return if (streaming || drainingAfterStream) visible else content
}

private const val STREAM_DISPLAY_BASE_CHARS_PER_SEC = 72f
// MUST be >= STREAM_DISPLAY_MAX_BACKLOG_CHARS / STREAM_DISPLAY_TARGET_DRAIN_SECONDS
// (1200 / 0.90 = 1333). Below that, the deadline-drain can never reach the speed
// needed to hold lag at the backlog cap, so the hard catch-up (snap) fires on
// every fast model instead of acting as a rare backstop — that premature snap is
// the "20-char wave" stutter. Do NOT chase faster models by bumping this; raise
// STREAM_DISPLAY_MAX_BACKLOG_CHARS or lower STREAM_DISPLAY_TARGET_DRAIN_SECONDS.
// (StreamingDisplayBufferTest pins this relationship.)
private const val STREAM_DISPLAY_MAX_CHARS_PER_SEC = 1_500f
private const val STREAM_DISPLAY_FINAL_MAX_CHARS_PER_SEC = 2_400f
private const val STREAM_DISPLAY_SPEED_ALPHA = 0.08f
private const val STREAM_DISPLAY_TARGET_DRAIN_SECONDS = 0.90f
private const val STREAM_DISPLAY_FINAL_TARGET_DRAIN_SECONDS = 0.18f
// Fixed speed caps are a soft comfort target, not a correctness guarantee.
// When a model bursts faster than the display buffer can comfortably reveal,
// keep lag bounded so completion never has a whole answer left to dump.
private const val STREAM_DISPLAY_MAX_BACKLOG_CHARS = 1_200
// The display buffer runs from Compose's frame clock, so one loop iteration is
// already bounded by the device vsync. Keep a small guard for very high refresh
// panels, but do not cap 120Hz devices at the old 16ms / ~60Hz cadence.
private const val STREAM_DISPLAY_MIN_EMIT_INTERVAL_NANOS = 8_000_000L
private const val STREAM_DISPLAY_MAX_CHARS_PER_EMIT = 8
private const val STREAM_DISPLAY_FINAL_MAX_CHARS_PER_EMIT = 20

internal fun streamingDisplayTargetSpeed(
    backlog: Int,
    streaming: Boolean,
    maxCharsPerSecond: Float = if (streaming) {
        STREAM_DISPLAY_MAX_CHARS_PER_SEC
    } else {
        STREAM_DISPLAY_FINAL_MAX_CHARS_PER_SEC
    },
): Float {
    val drainSeconds = if (streaming) {
        STREAM_DISPLAY_TARGET_DRAIN_SECONDS
    } else {
        STREAM_DISPLAY_FINAL_TARGET_DRAIN_SECONDS
    }
    return (backlog / drainSeconds)
        .coerceAtLeast(STREAM_DISPLAY_BASE_CHARS_PER_SEC)
        .coerceAtMost(maxCharsPerSecond)
}

internal fun streamingDisplayMaxCharsPerEmit(streaming: Boolean): Int =
    if (streaming) {
        STREAM_DISPLAY_MAX_CHARS_PER_EMIT
    } else {
        STREAM_DISPLAY_FINAL_MAX_CHARS_PER_EMIT
    }

internal fun streamingDisplayMaxBacklogChars(): Int = STREAM_DISPLAY_MAX_BACKLOG_CHARS

internal fun streamingDisplayBacklogCatchUpEnd(
    visibleLength: Int,
    targetLength: Int,
): Int {
    if (targetLength <= visibleLength) return targetLength.coerceAtLeast(0)
    val oldestAllowedVisibleEnd = targetLength - STREAM_DISPLAY_MAX_BACKLOG_CHARS
    return maxOf(visibleLength, oldestAllowedVisibleEnd).coerceIn(0, targetLength)
}

internal fun streamingImmediateDisplayText(content: String): String {
    val safeEnd = content.safeStreamingTerminalEnd()
    return if (safeEnd == content.length) {
        content
    } else {
        content.substring(0, safeEnd)
    }
}

private fun String.safeStreamingDisplayEnd(candidate: Int): Int {
    var end = candidate.coerceIn(0, length)
    if (end == 0 || end >= length) return end
    if (Character.isLowSurrogate(this[end])) {
        end++
    }
    while (end < length) {
        val previousCodePoint = codePointBefore(end)
        if (previousCodePoint == ZERO_WIDTH_JOINER) {
            end = nextCodePointEnd(end)
            continue
        }
        val nextCodePoint = codePointAt(end)
        if (nextCodePoint == ZERO_WIDTH_JOINER) {
            end = nextCodePointEnd(nextCodePointEnd(end))
            continue
        }
        if (!nextCodePoint.isAttachedMark()) break
        end += Character.charCount(nextCodePoint)
    }
    return end.coerceAtMost(length)
}

private fun String.safeStreamingTerminalEnd(): Int {
    var end = length
    while (end > 0) {
        val last = this[end - 1]
        if (Character.isHighSurrogate(last)) {
            end--
            continue
        }
        if (
            Character.isLowSurrogate(last) &&
            (end == 1 || !Character.isHighSurrogate(this[end - 2]))
        ) {
            end--
            continue
        }
        if (codePointBefore(end) == ZERO_WIDTH_JOINER) {
            end--
            continue
        }
        val attachedRunStart = terminalAttachedMarkRunStart(end)
        if (
            attachedRunStart < end &&
            (attachedRunStart == 0 || codePointBefore(attachedRunStart) == ZERO_WIDTH_JOINER)
        ) {
            end = attachedRunStart
            continue
        }
        break
    }
    return end
}

private fun String.terminalAttachedMarkRunStart(endExclusive: Int): Int {
    var start = endExclusive
    while (start > 0) {
        val cp = codePointBefore(start)
        if (!cp.isAttachedMark()) break
        start -= Character.charCount(cp)
    }
    return start
}

private fun String.nextCodePointEnd(offset: Int): Int {
    if (offset >= length) return length
    return (offset + Character.charCount(codePointAt(offset))).coerceAtMost(length)
}

private fun Int.isAttachedMark(): Boolean {
    if (this in 0xFE00..0xFE0F) return true
    return when (Character.getType(this)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }
}

private const val ZERO_WIDTH_JOINER = 0x200D
