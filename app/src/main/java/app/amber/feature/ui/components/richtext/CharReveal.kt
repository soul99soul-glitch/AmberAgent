package app.amber.feature.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import app.amber.agent.PerfFlags
import kotlin.math.pow

// Phase B / B1 + B2 — word-level reveal controller for streaming text.
//
// Architecture (mirrors smooth-streaming-rendering-guide §8 + §15):
//
//   chunk → onContentChanged(newContent)
//          ↓ slice newly-arrived suffix into RevealEntries:
//          • whitespace  → its own entry (so spaces never "fade in")
//          • CJK / Han   → 1 entry per codepoint  (CJK has no word breaks)
//          • Latin run   → 1 entry per word       (whitespace-bounded)
//          ↓
//   onFrame(frameNanos)
//          • EWMA the inter-frame delta to track current FPS
//          • if FPS < 45 OR backlog > BACKLOG_DEGRADE → degraded mode:
//            compress queued entries into a short fade window so they
//            catch up without a one-frame alpha jump
//          • else: promote any entry whose age crossed the fade window
//          • idle short-circuit: if the queue is empty, freeze nowNanos
//            so Paragraph's remember-key stops invalidating
//   alphaAt(absoluteOffset)
//          • below revealedHead → 1f  (fast path, ~99% of finalized text)
//          • above contentLength → 0f (not yet emitted)
//          • inside an entry    → entry-shared alpha based on age
//          (every codepoint inside a word entry shares the same alpha so
//           a word fades as a unit, per guide §8)
//
// Caller integration: LocalCharRevealController. MarkdownBlock installs
// one when streaming=true; MarkdownNode's leaf path queries alphaAt per
// codepoint when building AnnotatedString. Within a word, every codepoint
// returns the same alpha — Compose Text deduplicates adjacent spans
// with identical SpanStyle so this is cheap.

/**
 * Reveal-speed presets, modeled after the smooth-streaming-rendering
 * guide §4 / LobeHub `useSmoothStreamContent`. baseRevealDurationMs is
 * the fade window used when the queue is small (queueDepth ≤
 * SOFT_BACKPRESSURE_BACKLOG); under heavier load the controller
 * linearly shortens the effective duration toward 0 (instant) — see
 * [CharRevealController.effectiveRevealDurationNanos].
 */
enum class StreamRevealPreset(val baseRevealDurationMs: Long) {
    /** Snappy: ~120ms fade. Closest to "no animation but with a soft edge". */
    REALTIME(120L),

    /** Default. Codex-like light float: soft enough to see the glyphs emerge,
     * short enough that the stream tail still feels attached to the model. */
    BALANCED(160L),

    /** Slow + cinematic. ~320ms — feels deliberate, good for long replies. */
    SILKY(320L),
}

/** Active preset for the descendant subtree. */
val LocalStreamRevealPreset = compositionLocalOf { StreamRevealPreset.BALANCED }

/**
 * Backlog threshold where the soft backpressure ramp begins. Below
 * this the controller honors the preset's full reveal duration. Above
 * this the duration scales linearly down toward [BACKLOG_DEGRADE]
 * where it hits 0 (instant).
 */
private const val SOFT_BACKPRESSURE_BACKLOG = 30

/**
 * If the unfinished-reveal queue exceeds this many entries the model is
 * outpacing our render — switch to instant mode (no fade) until we
 * catch up. Tuned for 33ms accumulator flush × ~10 words/flush worst
 * case ≈ 50 entries within one fade window.
 */
private const val BACKLOG_DEGRADE = 80

/** Linear backpressure floor: at BACKLOG_DEGRADE the effective duration
 * is this fraction of the preset's base. Below SOFT_BACKPRESSURE_BACKLOG
 * the factor is 1.0; the ramp lerps between them.
 *
 * Keep the catch-up behavior: when the queue backs up, shorten the fade
 * enough that text does not lag behind the real stream.
 */
private const val BACKPRESSURE_DURATION_FLOOR = 0.45f

private const val MIN_ENTRY_STAGGER_NANOS = 4_000_000L
private const val MAX_ENTRY_STAGGER_NANOS = 10_000_000L
private const val MAX_BATCH_STAGGER_NANOS = 64_000_000L

private const val REVEAL_TAIL_WINDOW_NANOS = 350_000_000L
private const val REVEAL_TAIL_MAX_ENTRIES = 150
private const val REVEAL_TAIL_MAX_CHARS = 400

private const val STREAM_DISPLAY_BASE_CHARS_PER_SEC = 54f
// MUST be >= STREAM_DISPLAY_MAX_BACKLOG_CHARS / STREAM_DISPLAY_TARGET_DRAIN_SECONDS
// (420 / 0.28 = 1500). Below that, the deadline-drain can never reach the speed
// needed to hold lag at the backlog cap, so the hard catch-up (snap) fires on
// every fast model instead of acting as a rare backstop — that premature snap is
// the "20-char wave" stutter. Do NOT chase faster models by bumping this; raise
// STREAM_DISPLAY_MAX_BACKLOG_CHARS or lower STREAM_DISPLAY_TARGET_DRAIN_SECONDS.
// (StreamingDisplayBufferTest pins this relationship.)
private const val STREAM_DISPLAY_MAX_CHARS_PER_SEC = 1_500f
private const val STREAM_DISPLAY_FINAL_MAX_CHARS_PER_SEC = 2_400f
private const val STREAM_DISPLAY_SPEED_ALPHA = 0.12f
private const val STREAM_DISPLAY_TARGET_DRAIN_SECONDS = 0.28f
private const val STREAM_DISPLAY_FINAL_TARGET_DRAIN_SECONDS = 0.10f
// Fixed speed caps are a soft comfort target, not a correctness guarantee.
// When a model bursts faster than the display buffer can comfortably reveal,
// keep lag bounded so completion never has a whole answer left to dump.
private const val STREAM_DISPLAY_MAX_BACKLOG_CHARS = 420
// The display buffer runs from Compose's frame clock, so one loop iteration is
// already bounded by the device vsync. Keep a small guard for very high refresh
// panels, but do not cap 120Hz devices at the old 16ms / ~60Hz cadence.
private const val STREAM_DISPLAY_MIN_EMIT_INTERVAL_NANOS = 8_000_000L
private const val STREAM_DISPLAY_MAX_CHARS_PER_EMIT = 18
private const val STREAM_DISPLAY_FINAL_MAX_CHARS_PER_EMIT = 32
private const val HARD_DEGRADE_REVEAL_DURATION_NANOS = 72_000_000L

/**
 * EWMA-smoothed FPS below which we drop the fade animation entirely.
 * Aligns with guide §15: "fps < 45 → revealMode = 'batch'".
 *
 * Hysteresis: once degraded we don't re-engage the fade until FPS
 * recovers above [RECOVER_FPS], so a brief recovery to 46 doesn't
 * snap fade back on and immediately re-degrade — that ping-pong
 * would itself be jankier than staying degraded.
 */
private const val DEGRADE_FPS = 45f
private const val RECOVER_FPS = 55f

/** EWMA alpha for the FPS smoother. 1/8 ≈ ~125ms half-life. */
private const val FPS_EWMA_NUMERATOR = 7
private const val FPS_EWMA_DENOMINATOR = 8

@Stable
class CharRevealController internal constructor(
    private val revealDurationNanos: Long,
    private val trimRevealTail: Boolean,
) {
    internal constructor(revealDurationNanos: Long) : this(
        revealDurationNanos = revealDurationNanos,
        trimRevealTail = false,
    )

    /**
     * Linearly shortens the fade window when the queue is backed up,
     * borrowed from LobeHub `useStreamQueue`'s `1 + queueLength × 0.3`
     * acceleration. Returns:
     *  - revealDurationNanos      when depth ≤ SOFT_BACKPRESSURE_BACKLOG
     *  - revealDurationNanos × FLOOR  when depth ≥ BACKLOG_DEGRADE
     *  - linear interp in between
     *
     * `shouldDegrade` still trips at BACKLOG_DEGRADE and bypasses the
     * fade entirely (alpha jumps to 1f); this function exists for the
     * smooth ramp before that hard cliff.
     */
    private fun effectiveRevealDurationNanos(): Long {
        val depth = revealing.size
        if (depth <= SOFT_BACKPRESSURE_BACKLOG) return revealDurationNanos
        if (depth >= BACKLOG_DEGRADE) {
            return (revealDurationNanos * BACKPRESSURE_DURATION_FLOOR).toLong()
        }
        val ratio = (depth - SOFT_BACKPRESSURE_BACKLOG).toFloat() /
            (BACKLOG_DEGRADE - SOFT_BACKPRESSURE_BACKLOG).toFloat()
        val scale = 1f - ratio * (1f - BACKPRESSURE_DURATION_FLOOR)
        return (revealDurationNanos * scale).toLong()
    }

    // Highest offset whose codepoint is fully revealed (alpha == 1f).
    // Below this is the fast path — no per-codepoint alpha computation.
    private var revealedHead: Int = 0

    // Total chars seen so far. Anything >= this hasn't been emitted yet
    // and renders alpha=0 (effectively invisible).
    private var contentLength: Int = 0

    // Word/grapheme entries currently inside the fade window.
    // Each entry covers a [startOffset, endOffset) range that shares one
    // alpha — so an English word or a single CJK codepoint fades as a
    // unit, not as individual letters.
    private val revealing: ArrayDeque<RevealEntry> = ArrayDeque()

    // Frame clock. Reads of this in a Composable's remember-key force
    // a rebuild every frame, which is exactly what we want for paragraphs
    // currently being revealed.
    internal var nowNanos: Long by mutableLongStateOf(0L)
        private set

    // FPS smoother — used to detect "we're falling behind, drop the
    // animation" per guide §15.
    private var prevFrameNanos: Long = 0L
    private var avgFrameDeltaNanos: Long = 16_666_666L  // 60Hz default
    private var degraded: Boolean = false

    private var activeRevealCount: Int by mutableIntStateOf(0)

    /** Smoothed FPS. Used by [shouldDegrade] and exposed for profiler use. */
    val currentFps: Float
        get() = if (avgFrameDeltaNanos <= 0L) 60f
        else 1_000_000_000f / avgFrameDeltaNanos.toFloat()

    private fun shouldDegrade(): Boolean {
        // Backlog overflow always trips degrade (model is outpacing us
        // regardless of FPS) — clears once we drain.
        if (revealing.size > BACKLOG_DEGRADE) {
            degraded = true
            return true
        }
        // Hysteresis on FPS: enter degraded mode below DEGRADE_FPS,
        // exit only above RECOVER_FPS.
        val fps = currentFps
        if (degraded) {
            if (fps >= RECOVER_FPS) degraded = false
        } else {
            if (fps < DEGRADE_FPS) degraded = true
        }
        return degraded
    }

    internal fun onFrame(frameNanos: Long) {
        // Always update the FPS EWMA, even when idle — gives us a fresh
        // signal for the next reveal burst.
        if (prevFrameNanos > 0L) {
            val delta = frameNanos - prevFrameNanos
            // Guard against pathological deltas (process pause, debugger,
            // background return) corrupting the EWMA.
            if (delta in 1_000_000L..200_000_000L) {
                avgFrameDeltaNanos = (
                    avgFrameDeltaNanos * FPS_EWMA_NUMERATOR + delta
                    ) / FPS_EWMA_DENOMINATOR
            }
        }
        prevFrameNanos = frameNanos

        // Idle short-circuit: nothing in the fade window means there's
        // no alpha to advance. Holding nowNanos at its previous value
        // keeps Paragraph's remember-key stable so finalized
        // paragraphs in a still-streaming message stop rebuilding
        // their AnnotatedString every frame. onContentChanged will
        // bump nowNanos when fresh chars arrive; until then we let
        // the frame go.
        if (revealing.isEmpty()) return

        if (trimRevealTail) {
            trimRevealWindow(frameNanos)
            if (revealing.isEmpty()) {
                nowNanos = frameNanos
                return
            }
        }

        // Degrade: catch-up mode. Keep the reveal curve continuous instead
        // of clearing the queue to alpha=1 in one frame. The old clear()
        // path was efficient, but it read as a black flash exactly when the
        // UI was already under pressure.
        if (shouldDegrade()) {
            nowNanos = frameNanos
            compressRevealQueue(frameNanos)
            drainCompletedEntries(frameNanos)
            StreamingRenderProbe.record {
                "reveal_degrade fps=${"%.1f".format(currentFps)} queue=${revealing.size}"
            }
            return
        }

        nowNanos = frameNanos
        // Promote entries whose age has crossed THEIR OWN reveal
        // window. Each entry was stamped with the effective duration
        // active when it was added (see B5.1 in onContentChanged), so
        // the loop is consistent: each entry uses the duration we
        // promised it, not whatever the queue depth happens to make
        // effective at this frame.
        drainCompletedEntries(frameNanos)
    }

    /**
     * Called when the upstream content string changes. Walks the new
     * tail (chars beyond what we've seen before), splits it into
     * word/CJK/whitespace entries, and stamps each entry with the
     * current wall clock so reveal starts from "now" — even if onFrame
     * had been quiescent.
     */
    internal fun onContentChanged(newContent: String) {
        val newLength = newContent.length
        if (newLength < contentLength) {
            // Truncation — reset.
            revealing.clear()
            revealedHead = 0
            contentLength = 0
            syncRevealCount()
        }
        if (newLength <= contentLength) return
        val stamp = System.nanoTime()

        // B2.1 fix for "chunk splits a word/emoji-cluster mid-glyph"
        // visual tear (B2 reviewer P0/P1):
        //
        //   When the assistant emits "Hel" then "lo World" in two
        //   chunks, B2 would create a separate entry for "lo" with a
        //   later stamp than "Hel" — the back half of the word would
        //   start its fade ~50ms after the front half, producing a
        //   visible split. Same problem hits ZWJ-joined emoji
        //   sequences split across chunks: each ZWJ component would
        //   independently fade in.
        //
        //   Detection: the new tail's first codepoint is a word-runner
        //   (not whitespace, not CJK) AND the previous trailing entry
        //   in the queue ends exactly at contentLength AND that entry
        //   is itself a word-run (its first codepoint is also a
        //   word-runner). In that case, extend the existing entry
        //   forward to the next word boundary in the new content
        //   instead of allocating a fresh entry — so the whole word
        //   keeps one shared appearNanos.
        //
        //   The chars that "extend forward" inherit the older stamp,
        //   so the back half of the word is at most one chunk-flush
        //   (~33-50ms) older than the rest — small enough that the
        //   200ms fade overlaps and the word still looks unified.
        var sliceFrom = contentLength
        if (
            sliceFrom < newLength &&
            revealing.isNotEmpty() &&
            revealing.last().endOffset == sliceFrom
        ) {
            val firstNewCp = newContent.codePointAt(sliceFrom)
            val newIsWordRunner =
                !isWhitespaceCodepoint(firstNewCp) && !isCjkCodepoint(firstNewCp)
            if (newIsWordRunner) {
                val tail = revealing.last()
                val tailFirstCp = newContent.codePointAt(tail.startOffset)
                val tailIsWordRun =
                    !isWhitespaceCodepoint(tailFirstCp) && !isCjkCodepoint(tailFirstCp)
                if (tailIsWordRun) {
                    // Walk forward to the next word boundary in new content.
                    var i = sliceFrom
                    while (i < newLength) {
                        val cp = newContent.codePointAt(i)
                        if (isWhitespaceCodepoint(cp) || isCjkCodepoint(cp)) break
                        i += Character.charCount(cp)
                    }
                    revealing.removeLast()
                    revealing.addLast(tail.copy(endOffset = i))
                    sliceFrom = i
                }
            }
        }

        if (sliceFrom < newLength) {
            // B5.1: lock the effective duration NOW for everything we're
            // about to add. Entries inside the same chunk get a tiny capped
            // stagger so large flushes do not pulse in as one block.
            val effectiveAtStamp = effectiveRevealDurationNanos()
            sliceTailIntoEntries(
                content = newContent,
                startInclusive = sliceFrom,
                endExclusive = newLength,
                stamp = stamp,
                revealDurationNanos = effectiveAtStamp,
                into = revealing,
            )
        }
        contentLength = newLength
        if (trimRevealTail) {
            trimRevealWindow(stamp)
        }
        syncRevealCount()
        StreamingRenderProbe.record {
            "reveal_content len=$newLength added=${newLength - sliceFrom} queue=${revealing.size}"
        }
        // Bump nowNanos so the next frame's alphaAt sees a sane (≈0)
        // delta — prevents the "first chunk after a pause renders fully
        // revealed" glitch when the idle-short-circuit had paused us.
        nowNanos = stamp
    }

    /**
     * Returns the alpha [0f..1f] for the codepoint starting at
     * [absoluteOffset]. All codepoints inside the same word/CJK
     * entry share one alpha so a word fades as a unit.
     *
     * Reads `nowNanos` so that any Composable invoking this from
     * within a `remember(...)` key participates in per-frame invalidation.
     */
    fun alphaAt(absoluteOffset: Int): Float {
        if (absoluteOffset < revealedHead) return 1f
        if (absoluteOffset >= contentLength) return 0f
        // Linear search within the active queue. Entries are appended
        // in monotonically-increasing offset order, so once we pass an
        // entry whose startOffset already exceeds the lookup, nothing
        // further in the queue can contain the offset — early break.
        // Each entry uses ITS OWN revealDurationNanos (B5.1) so the
        // alpha curve never reverses if the queue depth changes
        // mid-fade.
        revealing.fastForEach { entry ->
            if (entry.startOffset > absoluteOffset) return 1f
            if (absoluteOffset in entry.startOffset until entry.endOffset) {
                return entry.alphaAt(nowNanos)
            }
        }
        // Fall-through: shouldn't happen if onContentChanged was called
        // for every chunk, but be defensive — treat as fully revealed
        // rather than blank.
        return 1f
    }

    /** Public for profilers / instrumentation. Cheap. */
    fun hasActiveReveals(): Boolean = activeRevealCount > 0

    /** Public for profilers. */
    fun queueDepth(): Int = activeRevealCount

    /**
     * Highest absolute text offset that no longer needs per-codepoint
     * reveal work. Markdown uses this to append old text in one shot
     * instead of walking the whole streaming answer every frame.
     */
    internal fun stableOffsetExclusive(): Int = revealedHead

    private fun drainCompletedEntries(frameNanos: Long) {
        var changed = false
        while (revealing.isNotEmpty()) {
            val head = revealing.first()
            if (head.alphaAt(frameNanos) >= 1f) {
                promoteFirstRevealEntry()
                changed = true
            } else {
                break
            }
        }
        if (changed) syncRevealCount()
    }

    internal fun trimRevealWindow(frameNanos: Long) {
        if (revealing.isEmpty()) return
        val oldestAllowedAppear = frameNanos - REVEAL_TAIL_WINDOW_NANOS
        var promoted = 0
        while (
            revealing.isNotEmpty() &&
            revealing.first().appearNanos < oldestAllowedAppear
        ) {
            promoteFirstRevealEntry()
            promoted++
        }
        while (revealing.size > REVEAL_TAIL_MAX_ENTRIES) {
            promoteFirstRevealEntry()
            promoted++
        }
        while (revealing.isNotEmpty() && activeRevealCharSpan() > REVEAL_TAIL_MAX_CHARS) {
            promoteFirstRevealEntry()
            promoted++
        }
        if (promoted > 0) {
            syncRevealCount()
            StreamingRenderProbe.record {
                "reveal_tail_trim promoted=$promoted queue=${revealing.size}"
            }
        }
    }

    private fun compressRevealQueue(frameNanos: Long) {
        if (revealing.isEmpty()) return
        val compressedDuration = minOf(revealDurationNanos, HARD_DEGRADE_REVEAL_DURATION_NANOS)
        val next = ArrayDeque<RevealEntry>(revealing.size)
        while (revealing.isNotEmpty()) {
            val entry = revealing.removeFirst()
            val alpha = entry.alphaAt(frameNanos).coerceIn(0f, 1f)
            if (alpha >= 1f) {
                next.addLast(entry.copy(appearNanos = frameNanos - compressedDuration, revealDurationNanos = compressedDuration))
            } else {
                val age = ageForEaseOutAlpha(alpha, compressedDuration)
                next.addLast(entry.copy(appearNanos = frameNanos - age, revealDurationNanos = compressedDuration))
            }
        }
        revealing.addAll(next)
    }

    private fun activeRevealCharSpan(): Int =
        revealing.last().endOffset - revealing.first().startOffset

    private fun promoteFirstRevealEntry() {
        val head = revealing.removeFirst()
        revealedHead = maxOf(revealedHead, head.endOffset)
    }

    private fun syncRevealCount() {
        activeRevealCount = revealing.size
    }

    private data class RevealEntry(
        val startOffset: Int,  // inclusive
        val endOffset: Int,    // exclusive
        val appearNanos: Long,
        // B5.1: effective fade window LOCKED at this entry's stamp
        // time, computed from queue depth at the moment it was added.
        // Per-entry duration prevents alphaAt() from snapping a char's
        // alpha when queue depth fluctuates after the entry is in
        // flight — without this, a depth shrink (50→35) would push
        // effective duration up (140ms→200ms) and a char already
        // rendering at α=0.71 would jump back to α=0.50 next frame
        // (visible alpha regression). Stamping at entry time ties
        // the fade rate to "what we promised when this char arrived".
        val revealDurationNanos: Long,
    ) {
        fun alphaAt(nowNanos: Long): Float {
            if (revealDurationNanos <= 0L) return 1f
            val age = nowNanos - appearNanos
            if (age <= 0L) return 0f
            if (age >= revealDurationNanos) return 1f
            val t = age.toFloat() / revealDurationNanos.toFloat()
            val inv = 1f - t
            return 1f - inv * inv * inv
        }
    }

    private companion object {
        /**
         * Walks `content[startInclusive..endExclusive)` and slices it
         * into reveal entries by these rules:
         *  - whitespace codepoints become their own single-codepoint
         *    entry. They share the current visible-entry delay but do not
         *    advance the stagger because a space has no visible glyph.
         *  - CJK / Hangul / kana codepoints become 1 entry each (no
         *    word breaks in those scripts).
         *  - runs of other codepoints (Latin, digits, punctuation
         *    attached to words) coalesce into one entry per word run,
         *    bounded by the next whitespace/CJK or end-of-tail.
         */
        fun sliceTailIntoEntries(
            content: String,
            startInclusive: Int,
            endExclusive: Int,
            stamp: Long,
            revealDurationNanos: Long,
            into: ArrayDeque<RevealEntry>,
        ) {
            // wordRunStart tracks the start of an in-progress Latin/digit
            // word. -1 means we're not currently inside one.
            var wordRunStart = -1
            var visibleEntryCount = 0
            var i = startInclusive

            fun appearNanos(advanceVisibleEntry: Boolean): Long {
                val delay = staggerDelayNanos(
                    revealDurationNanos = revealDurationNanos,
                    visibleEntryIndex = visibleEntryCount,
                )
                if (advanceVisibleEntry) visibleEntryCount++
                return stamp + delay
            }

            while (i < endExclusive) {
                val cp = content.codePointAt(i)
                val cpLen = Character.charCount(cp)
                val isWhitespace = isWhitespaceCodepoint(cp)
                val isWordBreaker = isWhitespace || isCjkCodepoint(cp)
                if (isWordBreaker) {
                    // Flush any in-progress word run first.
                    if (wordRunStart >= 0) {
                        into.addLast(RevealEntry(wordRunStart, i, appearNanos(true), revealDurationNanos))
                        wordRunStart = -1
                    }
                    into.addLast(
                        RevealEntry(
                            i,
                            i + cpLen,
                            appearNanos(advanceVisibleEntry = !isWhitespace),
                            revealDurationNanos,
                        ),
                    )
                } else {
                    if (wordRunStart < 0) wordRunStart = i
                }
                i += cpLen
            }
            if (wordRunStart >= 0) {
                into.addLast(RevealEntry(wordRunStart, endExclusive, appearNanos(true), revealDurationNanos))
            }
        }

        private fun staggerDelayNanos(
            revealDurationNanos: Long,
            visibleEntryIndex: Int,
        ): Long {
            if (revealDurationNanos <= 0L || visibleEntryIndex <= 0) return 0L
            val perEntry = (revealDurationNanos / 16L)
                .coerceIn(MIN_ENTRY_STAGGER_NANOS, MAX_ENTRY_STAGGER_NANOS)
            val maxBatch = minOf(MAX_BATCH_STAGGER_NANOS, revealDurationNanos / 2L)
            return (visibleEntryIndex * perEntry).coerceAtMost(maxBatch)
        }

        private fun isWhitespaceCodepoint(cp: Int): Boolean =
            Character.isWhitespace(cp) || cp == ' '.code

        private fun isCjkCodepoint(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF        // CJK Unified Ideographs
                || cp in 0x3400..0x4DBF // CJK Extension A
                || cp in 0x20000..0x2A6DF // CJK Extension B
                || cp in 0x2A700..0x2B73F // CJK Extension C
                || cp in 0x3040..0x309F // Hiragana
                || cp in 0x30A0..0x30FF // Katakana
                || cp in 0xAC00..0xD7AF // Hangul syllables
                || cp in 0xFF00..0xFFEF // Halfwidth/Fullwidth forms

        private fun ageForEaseOutAlpha(alpha: Float, durationNanos: Long): Long {
            if (alpha <= 0f || durationNanos <= 0L) return 0L
            if (alpha >= 1f) return durationNanos
            val t = 1.0 - (1.0 - alpha.toDouble()).pow(1.0 / 3.0)
            return (durationNanos * t).toLong().coerceIn(0L, durationNanos)
        }
    }
}

/**
 * Provides a [CharRevealController] to descendants. `null` means
 * "render normally, no reveal" — the typical case for finalized
 * (already-completed) blocks.
 */
val LocalCharRevealController = compositionLocalOf<CharRevealController?> { null }

/**
 * Returns a remembered [CharRevealController] after streaming starts,
 * advancing its frame clock automatically. Once upstream streaming ends,
 * the controller stays installed only until its tail fade finishes.
 *
 * The returned controller is keyed on the consumer Composable's
 * identity, so re-keying the consumer (e.g. switching MessageNode)
 * resets the reveal state.
 */
@Composable
fun rememberCharRevealController(
    streaming: Boolean,
    content: String,
    preset: StreamRevealPreset = LocalStreamRevealPreset.current,
    immediateMode: Boolean = false,
): CharRevealController? {
    if (!immediateMode && !streaming) return null
    var hasSeenStreaming by remember(preset, immediateMode) { mutableStateOf(streaming) }
    if (immediateMode && !streaming && !hasSeenStreaming) return null
    val controller = remember(preset, immediateMode) {
        CharRevealController(
            revealDurationNanos = preset.baseRevealDurationMs * 1_000_000L,
            trimRevealTail = immediateMode,
        )
    }
    if (immediateMode && streaming && !hasSeenStreaming) {
        SideEffect {
            hasSeenStreaming = true
        }
    }
    if (immediateMode && !streaming && !controller.hasActiveReveals()) {
        SideEffect {
            hasSeenStreaming = false
        }
        return null
    }
    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(controller) {
        // Whenever content grows, slice the new tail into word/grapheme
        // entries and stamp them with wall-clock so reveal starts now.
        snapshotFlow { updatedContent }
            .collect { latest ->
                controller.onContentChanged(latest)
            }
    }
    LaunchedEffect(controller) {
        while (true) {
            withFrameNanos { frame ->
                controller.onFrame(frame)
            }
        }
    }
    return controller
}

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
            budget += speed * deltaSeconds
            val maxCharsPerEmit = streamingDisplayMaxCharsPerEmit(updatedStreaming)
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

// Local clone of androidx.compose.ui.util.fastForEach to avoid the
// import cost from this small file. Same semantics, no allocation.
private inline fun <T> ArrayDeque<T>.fastForEach(action: (T) -> Unit) {
    val it = iterator()
    while (it.hasNext()) action(it.next())
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
