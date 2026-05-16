package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos

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
//            promote every queued entry to revealedHead immediately
//            (alpha jumps to 1f without fade) — guide §15 step 1-2
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

    /** Default. ~200ms — 12 frames @60Hz, matches the original B1 tuning. */
    BALANCED(200L),

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
 * 0.30 means a 200ms preset compresses to 60ms at queueDepth=80 — fast
 * enough to catch up, slow enough that the fade is still visible.
 */
private const val BACKPRESSURE_DURATION_FLOOR = 0.30f

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
) {
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

        // Degrade: catch-up mode. Fast-promote everything queued; the
        // next paint will see all chars at alpha=1 with no fade. Cleaner
        // than dropping frames or tearing.
        if (shouldDegrade()) {
            val tail = revealing.last()
            revealedHead = maxOf(revealedHead, tail.endOffset)
            revealing.clear()
            // Bump nowNanos so the next paint sees the change.
            nowNanos = frameNanos
            return
        }

        nowNanos = frameNanos
        // Promote entries whose age has crossed THEIR OWN reveal
        // window. Each entry was stamped with the effective duration
        // active when it was added (see B5.1 in onContentChanged), so
        // the loop is consistent: each entry uses the duration we
        // promised it, not whatever the queue depth happens to make
        // effective at this frame.
        while (revealing.isNotEmpty()) {
            val head = revealing.first()
            if (frameNanos - head.appearNanos >= head.revealDurationNanos) {
                revealedHead = maxOf(revealedHead, head.endOffset)
                revealing.removeFirst()
            } else {
                break
            }
        }
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
            // about to add. queue depth used = depth before this batch
            // (we add below); a long batch shares one duration, which
            // is the simpler approximation than per-entry "expected
            // final depth" — and it's still correct in spirit (all
            // chars in this chunk arrived at the same moment).
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
                val effective = entry.revealDurationNanos
                if (effective <= 0L) return 1f
                val age = nowNanos - entry.appearNanos
                if (age <= 0L) return 0f
                if (age >= effective) return 1f
                return age.toFloat() / effective.toFloat()
            }
        }
        // Fall-through: shouldn't happen if onContentChanged was called
        // for every chunk, but be defensive — treat as fully revealed
        // rather than blank.
        return 1f
    }

    /** Public for profilers / instrumentation. Cheap. */
    fun hasActiveReveals(): Boolean = revealing.isNotEmpty()

    /** Public for profilers. */
    fun queueDepth(): Int = revealing.size

    /**
     * Highest absolute text offset that no longer needs per-codepoint
     * reveal work. Markdown uses this to append old text in one shot
     * instead of walking the whole streaming answer every frame.
     */
    internal fun stableOffsetExclusive(): Int = revealedHead

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
    )

    private companion object {
        /**
         * Walks `content[startInclusive..endExclusive)` and slices it
         * into reveal entries by these rules:
         *  - whitespace codepoints become their own single-codepoint
         *    entry (a space "fading in" looks weird but it has to be
         *    in the queue or alphaAt would treat it as fully-revealed
         *    after the next promotion).
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
            var i = startInclusive
            while (i < endExclusive) {
                val cp = content.codePointAt(i)
                val cpLen = Character.charCount(cp)
                val isWordBreaker = isWhitespaceCodepoint(cp) || isCjkCodepoint(cp)
                if (isWordBreaker) {
                    // Flush any in-progress word run first.
                    if (wordRunStart >= 0) {
                        into.addLast(RevealEntry(wordRunStart, i, stamp, revealDurationNanos))
                        wordRunStart = -1
                    }
                    into.addLast(RevealEntry(i, i + cpLen, stamp, revealDurationNanos))
                } else {
                    if (wordRunStart < 0) wordRunStart = i
                }
                i += cpLen
            }
            if (wordRunStart >= 0) {
                into.addLast(RevealEntry(wordRunStart, endExclusive, stamp, revealDurationNanos))
            }
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
    }
}

/**
 * Provides a [CharRevealController] to descendants. `null` means
 * "render normally, no reveal" — the typical case for finalized
 * (already-completed) blocks.
 */
val LocalCharRevealController = compositionLocalOf<CharRevealController?> { null }

/**
 * Returns a remembered [CharRevealController] when [streaming] is true,
 * advancing its frame clock automatically. Returns null when not
 * streaming so finalized content takes the fast path.
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
): CharRevealController? {
    if (!streaming) return null
    val controller = remember(preset) {
        CharRevealController(revealDurationNanos = preset.baseRevealDurationMs * 1_000_000L)
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

// Local clone of androidx.compose.ui.util.fastForEach to avoid the
// import cost from this small file. Same semantics, no allocation.
private inline fun <T> ArrayDeque<T>.fastForEach(action: (T) -> Unit) {
    val it = iterator()
    while (it.hasNext()) action(it.next())
}
