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

// Phase B / B1 — character-level reveal controller for streaming text.
//
// Goal: each newly-arrived character fades in over a short window
// (default ~200ms) instead of popping with the 33ms accumulator flush.
// Equivalent to the smooth-streaming-rendering-guide §8 "word reveal
// queue" pattern adapted to Compose.
//
// Mechanics:
//  - We don't track every char individually — that's O(content.length)
//    state for a streaming response that can be tens of thousands of
//    chars. Instead we keep a single moving boundary `revealedHead`
//    and an ArrayDeque of (offset, appearNanos) for chars currently
//    inside the fade window only.
//  - Every frame, withFrameNanos updates `nowNanos` (a mutableLongState
//    so reads in build*AnnotatedString keys force a rebuild) and
//    promotes any char whose age has exceeded `revealDurationNanos`
//    out of the active queue into `revealedHead`.
//  - alphaAt(offset) is the only public read API. Below revealedHead →
//    1f (fast path, no map lookup). Above current content length → 0f.
//    In between → linear progress through the fade window.
//
// Caller integration is via LocalCharRevealController. MarkdownBlock
// installs one when `streaming=true`, MarkdownNode's leaf path looks
// it up and wraps each codepoint in withStyle(SpanStyle(alpha=...)).
// finalized blocks see null and skip the per-codepoint split entirely.

/** Default fade-in window for newly-arrived chars. ~12 frames @60Hz. */
private const val DEFAULT_REVEAL_DURATION_MS = 200L

@Stable
class CharRevealController internal constructor(
    private val revealDurationNanos: Long,
) {
    // Highest offset whose char is fully revealed (alpha == 1f). Below
    // this is the fast path — no per-char alpha computation.
    private var revealedHead: Int = 0

    // Total chars seen so far. Anything >= this hasn't been emitted yet
    // and renders alpha=0 (effectively invisible).
    private var contentLength: Int = 0

    // Chars currently inside the fade window: offset → appearNanos.
    // We use an ArrayDeque so promotion-out-of-window is O(k) for the
    // small k = chars-emitted-in-the-last-revealDuration window —
    // typically <100 even on a fast model.
    private val revealing: ArrayDeque<RevealEntry> = ArrayDeque()

    // Frame clock. Reads of this in a Composable's remember-key force
    // a rebuild every frame, which is exactly what we want for paragraphs
    // currently being revealed.
    internal var nowNanos: Long by mutableLongStateOf(0L)
        private set

    internal fun onFrame(frameNanos: Long) {
        // Idle short-circuit: nothing in the fade window means there's
        // no alpha to advance. Holding nowNanos at its previous value
        // keeps Paragraph's `remember` key stable so finalized
        // paragraphs in a still-streaming message stop rebuilding
        // their AnnotatedString every frame. onContentChanged will
        // bump nowNanos when fresh chars arrive; until then we let
        // the frame go.
        if (revealing.isEmpty()) return
        nowNanos = frameNanos
        // Promote chars whose age has crossed the reveal window.
        while (revealing.isNotEmpty()) {
            val head = revealing.first()
            if (frameNanos - head.appearNanos >= revealDurationNanos) {
                revealedHead = maxOf(revealedHead, head.offset + 1)
                revealing.removeFirst()
            } else {
                break
            }
        }
    }

    /**
     * Called when the upstream content string changes. Walks the new
     * tail (chars beyond what we've seen before) and stamps them with
     * the current nowNanos so they begin their fade window from this
     * frame.
     *
     * No attempt to handle content shrinking (truncation) — current
     * streaming pipeline only ever appends. If we ever truncate we
     * just reset.
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
        // Always use the wall clock so freshly-appearing chars start
        // their fade from "now", even if onFrame had been quiescent
        // (idle-short-circuit above held nowNanos at a stale value).
        // Also bump nowNanos to this stamp so the next frame's alphaAt
        // sees a sane (≈0) delta — prevents the "first chunk after a
        // pause renders fully revealed" glitch.
        val stamp = System.nanoTime()
        for (offset in contentLength until newLength) {
            revealing.addLast(RevealEntry(offset, stamp))
        }
        contentLength = newLength
        nowNanos = stamp
    }

    /**
     * Returns the alpha [0f..1f] for the codepoint starting at
     * [absoluteOffset] in the original content string.
     *
     * Reads `nowNanos` so that any Composable invoking this from
     * within a `remember(...)` key (or directly from buildAnnotatedString
     * inside a Composable) participates in the per-frame invalidation.
     */
    fun alphaAt(absoluteOffset: Int): Float {
        if (absoluteOffset < revealedHead) return 1f
        if (absoluteOffset >= contentLength) return 0f
        // Linear search within the active queue. k is small (see above).
        // The list is ordered by offset/time so we could binary search,
        // but linear is faster for k<32.
        revealing.fastForEach { entry ->
            if (entry.offset == absoluteOffset) {
                val age = nowNanos - entry.appearNanos
                if (age <= 0L) return 0f
                if (age >= revealDurationNanos) return 1f
                return age.toFloat() / revealDurationNanos.toFloat()
            }
        }
        // Fall-through: shouldn't happen if onContentChanged was called
        // for every chunk, but be defensive — treat as fully revealed
        // rather than blank.
        return 1f
    }

    private data class RevealEntry(val offset: Int, val appearNanos: Long)
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
    revealDurationMs: Long = DEFAULT_REVEAL_DURATION_MS,
): CharRevealController? {
    if (!streaming) return null
    val controller = remember(revealDurationMs) {
        CharRevealController(revealDurationNanos = revealDurationMs * 1_000_000L)
    }
    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(controller) {
        // 1) Kick the clock every frame so alphaAt() updates.
        // 2) Whenever content grows, stamp the new tail with the current
        //    frame's nanos so reveal starts from "now" for those chars.
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
private inline fun <T> List<T>.fastForEach(action: (T) -> Unit) {
    for (i in indices) action(this[i])
}

private inline fun <T> ArrayDeque<T>.fastForEach(action: (T) -> Unit) {
    val it = iterator()
    while (it.hasNext()) action(it.next())
}
