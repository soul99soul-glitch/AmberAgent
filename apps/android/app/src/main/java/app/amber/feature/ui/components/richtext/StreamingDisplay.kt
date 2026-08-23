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
import kotlin.math.exp

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
    // Paced reveal is only ever legitimate for a stream THIS instance has
    // tracked. A composable that is (re)created in the settled state and then
    // receives different content is looking at a content CORRECTION — e.g. the
    // end-of-stream path switch briefly feeding a stale snapshot, then the full
    // text. Draining that correction re-revealed an entire already-read message
    // over seconds (the "answer collapses to its first characters, then slowly
    // re-types" end-of-stream jump). Settled corrections must render instantly.
    var sawStreaming by remember { mutableStateOf(streaming) }
    if (streaming && !sawStreaming) {
        sawStreaming = true
    }
    val drainingAfterStream = sawStreaming &&
        !streaming &&
        visible != content &&
        content.startsWith(visible)
    var previousStreaming by remember { mutableStateOf(streaming) }
    // Instance-tagged birth/death probes: several display buffers run at once
    // (reasoning + text blocks) and their emits interleave in the ring buffer.
    // The birth event captures what content length a FRESH instance started
    // from — the key evidence for "settled text collapsed then re-revealed".
    val probeInstanceId = remember {
        val id = StreamingRenderProbe.nextInstanceId()
        StreamingRenderProbe.record {
            "display_init id=$id visible=${content.length} streaming=$streaming"
        }
        id
    }

    DisposableEffect(Unit) {
        onDispose {
            StreamingRenderProbe.record {
                "display_disposed id=$probeInstanceId visible=${visible.length}"
            }
            StreamingRenderProbe.dump("streaming_display_disposed")
        }
    }

    SideEffect {
        if (previousStreaming && !streaming) {
            StreamingRenderProbe.dump("streaming_display_completed")
        }
        previousStreaming = streaming
    }

    // Settled-state sync: keep `visible` pinned to the latest content whenever
    // we are NOT pacing it — on prefix mismatch (content replaced underneath)
    // and on any correction reaching a never-streamed instance. Leaving the
    // stale `visible` around would poison a later streaming start (the loop
    // would treat the stale prefix as a huge backlog and re-pace it).
    if (!streaming && visible != content && (!content.startsWith(visible) || !sawStreaming)) {
        SideEffect {
            StreamingRenderProbe.record {
                "display_snap_settled id=$probeInstanceId visible=${visible.length} " +
                    "content=${content.length} prefix=${content.startsWith(visible)} sawStreaming=$sawStreaming"
            }
            visible = content
        }
    }

    // Word-quantized releaser (the smoothStream / GetStream StreamingText
    // pattern): the buffer always releases WHOLE units — a CJK character, or a
    // Latin word with its leading whitespace — never a partial word. The
    // display speed targets `backlog / latency` CLAMPED into a narrow band and
    // approaches it through an exponential smoother, so upstream burstiness
    // moves the backlog, not the visible rate (the old proportional-rate
    // formula turned every burst into a visible speed swing — "乱出").
    // Fractional credit accumulates across frames instead of a per-frame ceil,
    // so the release cadence is frame-rate independent.
    LaunchedEffect(streaming, drainingAfterStream) {
        if (!streaming && !drainingAfterStream) return@LaunchedEffect
        StreamingRenderProbe.record {
            "display_loop_start id=$probeInstanceId streaming=$streaming draining=$drainingAfterStream visible=${visible.length} target=${updatedContent.length}"
        }
        var lastFrameNanos = 0L
        var lastTarget = updatedContent
        var speedUnitsPerSecond = StreamingPace.FLOOR_UNITS_PER_SECOND
        var budgetUnits = 0f
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
                    updatedOnVisibleFrame?.invoke()
                    lastFrameNanos = frameNanos
                    // Content replaced underneath (regenerate / branch switch):
                    // pacing credit is meaningless against new text.
                    budgetUnits = 0f
                    continue
                }
            }
            val backlog = target.length - visible.length
            StreamingRenderProbe.displayBacklog = backlog
            if (backlog <= 0) {
                lastFrameNanos = frameNanos
                // Starved: drop accumulated credit so the next arrival shows
                // promptly instead of dumping saved-up units at once.
                budgetUnits = 0f
                continue
            }
            val frameMs = if (lastFrameNanos == 0L) {
                STREAM_PACE_FRAME_MS_DEFAULT
            } else {
                ((frameNanos - lastFrameNanos) / 1_000_000f).coerceIn(
                    STREAM_PACE_FRAME_MS_MIN,
                    STREAM_PACE_FRAME_MS_MAX,
                )
            }
            lastFrameNanos = frameNanos
            val backlogUnits = countStreamingReleaseUnits(target, visible.length, target.length)
            val targetSpeed = streamingTargetUnitsPerSecond(
                backlogUnits = backlogUnits,
                draining = !updatedStreaming,
            )
            speedUnitsPerSecond = streamingSpeedStep(
                currentUnitsPerSecond = speedUnitsPerSecond,
                targetUnitsPerSecond = targetSpeed,
                frameMs = frameMs,
            )
            budgetUnits += speedUnitsPerSecond * frameMs / 1000f
            val maxUnits = minOf(budgetUnits.toInt(), StreamingPace.MAX_UNITS_PER_FRAME)
            budgetUnits -= maxUnits
            var releasedUnits = 0
            var releasedChars = 0
            while (releasedUnits < maxUnits) {
                val start = visible.length
                val unitEnd = target.nextStreamingReleaseUnitEnd(start)
                if (unitEnd <= start) break
                visible = target.substring(0, unitEnd.coerceAtMost(target.length))
                releasedChars += visible.length - start
                releasedUnits++
            }
            if (releasedUnits > 0) {
                StreamingRenderProbe.record {
                    "display_emit id=$probeInstanceId backlog=$backlog backlogUnits=$backlogUnits " +
                        "speed=$speedUnitsPerSecond units=$releasedUnits chars=$releasedChars " +
                        "visible=${visible.length} target=${target.length}"
                }
                updatedOnVisibleFrame?.invoke()
            }
        }
    }

    return if (streaming || drainingAfterStream) visible else content
}

// ---------------------------------------------------------------------------
// Pacing constants — one place to tune on a real device.
// ---------------------------------------------------------------------------

internal object StreamingPace {
    /** How far (in time) the reveal may lag upstream while streaming. */
    const val STREAM_LATENCY_MS = 1_200f

    /**
     * Post-stream drain: model finished, but the buffered tail should keep
     * "writing the last stroke" at close to the streaming pace — fast models
     * (deepseek-flash measured ~870 cps) leave a ~1000-unit backlog at stream
     * end, and dumping it in 350ms read as coarse 48-unit bursts. Clearing
     * over ~1s (mildly faster than stream latency) lands the ending gently.
     */
    const val DRAIN_LATENCY_MS = 1_000f

    /**
     * 显示速率钳制带（units/s）。旧公式速率 = 积压/延迟，正比于瞬时积压——
     * 上游 burst 让积压锯齿振荡，显示速率随之 6-30 倍来回摆（"乱出/忽快忽
     * 忽慢"的根因）。钳进窄带后，burst 只表现为积压的缓涨缓消，速率由
     * [SPEED_SMOOTHING_MS] 平滑自适应。下限同时是收尾的"打字节奏"保底
     * （末段不再指数滴答）。
     */
    const val FLOOR_UNITS_PER_SECOND = 30f
    const val CAP_UNITS_PER_SECOND = 360f

    /**
     * 速率自适应时间常数：目标速率变化按指数逼近——"模型突然变快时*逐渐*
     * 加速，不忽快忽慢"的产品契约。
     */
    const val SPEED_SMOOTHING_MS = 700f

    /** Safety per-frame ceiling regardless of speed (protects the parse layer). */
    const val MAX_UNITS_PER_FRAME = 24

    /** Latin word/URL unit cap so a long token never waits for its tail. */
    const val MAX_UNIT_CHARS = 24
}

internal const val STREAM_PACE_FRAME_MS_DEFAULT = 16f
internal const val STREAM_PACE_FRAME_MS_MIN = 4f
internal const val STREAM_PACE_FRAME_MS_MAX = 100f

/**
 * Target display speed (units/s) for the current backlog: the latency model
 * `backlog / latency` clamped into the [StreamingPace.FLOOR_UNITS_PER_SECOND,
 * StreamingPace.CAP_UNITS_PER_SECOND] band. Zero backlog targets zero (the
 * caller keeps whatever speed it has until text arrives; there is nothing to
 * release).
 */
internal fun streamingTargetUnitsPerSecond(
    backlogUnits: Int,
    draining: Boolean,
): Float {
    if (backlogUnits <= 0) return 0f
    val latencyMs = if (draining) StreamingPace.DRAIN_LATENCY_MS else StreamingPace.STREAM_LATENCY_MS
    return (backlogUnits * 1000f / latencyMs).coerceIn(
        StreamingPace.FLOOR_UNITS_PER_SECOND,
        StreamingPace.CAP_UNITS_PER_SECOND,
    )
}

/**
 * Exponential approach of the current speed toward [targetUnitsPerSecond]
 * with the [StreamingPace.SPEED_SMOOTHING_MS] time constant — continuous in
 * dt (dt→0 steps nothing; anomalous dt is just a bigger step), frame-rate
 * independent.
 */
internal fun streamingSpeedStep(
    currentUnitsPerSecond: Float,
    targetUnitsPerSecond: Float,
    frameMs: Float,
): Float {
    val blend = 1f - exp(-frameMs / StreamingPace.SPEED_SMOOTHING_MS)
    return currentUnitsPerSecond + (targetUnitsPerSecond - currentUnitsPerSecond) * blend
}

/**
 * End index (exclusive) of the release unit starting at [from]. Units:
 * - a whitespace run attaches to the word that follows it ("␣word" is one
 *   unit), so line/paragraph breaks land together with the next word;
 * - one CJK character, plus any immediately following fullwidth punctuation
 *   (，。！？…—), is one unit;
 * - otherwise a Latin run up to the next whitespace/CJK character, capped at
 *   [StreamingPace.MAX_UNIT_CHARS] so long URLs never wait for their tail.
 * The result is normalized through the combining-mark / ZWJ safety rules.
 */
internal fun String.nextStreamingReleaseUnitEnd(from: Int): Int {
    if (from >= length) return length
    var end = from
    while (end < length && this[end].isWhitespace()) end++
    val wordStart = end
    if (wordStart >= length) {
        return safeStreamingDisplayEnd(length)
    }
    val cp = codePointAt(wordStart)
    val cpEnd = wordStart + Character.charCount(cp)
    val wordEnd = when {
        cp.isCjkCharacter() -> {
            var e = cpEnd
            while (e < length) {
                val next = codePointAt(e)
                if (!next.isCjkAttachPunctuation()) break
                e += Character.charCount(next)
            }
            e
        }

        else -> {
            var e = wordStart
            val hardStop = wordStart + StreamingPace.MAX_UNIT_CHARS
            while (e < length && e < hardStop) {
                val c = codePointAt(e)
                if (Character.isWhitespace(c) || c.isCjkCharacter()) break
                e += Character.charCount(c)
            }
            e
        }
    }
    return safeStreamingDisplayEnd(wordEnd.coerceAtMost(length))
}

/** Number of release units between [from] and [until]. O(backlog) per frame. */
internal fun countStreamingReleaseUnits(content: String, from: Int, until: Int): Int {
    var index = from.coerceIn(0, content.length)
    val stop = until.coerceIn(index, content.length)
    var units = 0
    while (index < stop) {
        val end = content.nextStreamingReleaseUnitEnd(index)
        if (end <= index) {
            index += Character.charCount(content.codePointAt(index))
            continue
        }
        index = end
        units++
    }
    return units
}

private fun Int.isCjkCharacter(): Boolean {
    if (this in 0x3040..0x30FF) return true // Hiragana / Katakana
    if (this in 0x3400..0x4DBF) return true // CJK Extension A
    if (this in 0x4E00..0x9FFF) return true // CJK Unified Ideographs
    if (this in 0xF900..0xFAFF) return true // CJK Compatibility Ideographs
    if (this in 0xAC00..0xD7AF) return true // Hangul syllables
    if (this in 0x20000..0x2FA1F) return true // CJK Extensions B+
    return false
}

/** Fullwidth / CJK punctuation that attaches to the character before it. */
private fun Int.isCjkAttachPunctuation(): Boolean {
    if (this in 0x3001..0x303F) return true // 。、《》「」…
    if (this in 0xFF01..0xFF60) return true // ！？：； fullwidth forms
    if (this == 0x2014 || this == 0x2026) return true // — …
    return false
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
