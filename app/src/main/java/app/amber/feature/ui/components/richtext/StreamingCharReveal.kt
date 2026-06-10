package app.amber.feature.ui.components.richtext

/**
 * L4-private appearance clock for the streaming live suffix.
 *
 * Layer contract: this class consumes nothing beyond what L4 already receives
 * from L3 — the live suffix text and its source offset. It observes its own
 * input across frames to learn when each character first became visible, so
 * every character can run a full fade/lift curve anchored to ITS appearance
 * instead of the parse tick that created the batch. Batch anchoring made
 * characters that surfaced late in a tick window start 60-90% of the way
 * through the shared curve — they popped in nearly opaque, which read as
 * "动画缺失". L1/L2/L3 are untouched.
 *
 * Keys are absolute source offsets (suffix source offset + UTF-16 index), so a
 * character keeps its own timeline when a parse tick shrinks the suffix —
 * carried-over characters no longer restart their fade on tick boundaries.
 */
internal class StreamingCharRevealClock {
    private val appearNanos = HashMap<Int, Long>()
    private var pruneFloor = Int.MIN_VALUE

    /**
     * Stamps appear times for not-yet-seen characters of [suffixText] and drops
     * entries already absorbed into settled text. Characters surfacing in the
     * same frame cascade by [staggerNanos] each (capped at [maxCascadeNanos]
     * total) so a chunk that lands in one frame still sweeps in left-to-right.
     * When a catch-up burst exceeds [maxPending] characters, the oldest
     * overflow is stamped as already finished so the reveal never trails far
     * behind the display buffer.
     */
    fun stamp(
        suffixText: String,
        suffixSourceOffset: Int,
        nowNanos: Long,
        fadeNanos: Long,
        staggerNanos: Long,
        maxCascadeNanos: Long,
        maxPending: Int,
    ) {
        if (suffixSourceOffset < pruneFloor) {
            // Source offset moved backwards: the stream content was replaced
            // (regenerate / branch switch). Old stamps are meaningless.
            appearNanos.clear()
        }
        if (suffixSourceOffset > pruneFloor) {
            appearNanos.keys.removeAll { it < suffixSourceOffset }
        }
        pruneFloor = suffixSourceOffset

        var newCount = 0
        var i = 0
        while (i < suffixText.length) {
            val codePoint = suffixText.codePointAt(i)
            if (!appearNanos.containsKey(suffixSourceOffset + i)) newCount++
            i += Character.charCount(codePoint)
        }
        if (newCount == 0) return

        val step = if (newCount <= 1) {
            0L
        } else {
            minOf(staggerNanos, maxCascadeNanos / (newCount - 1))
        }
        val overflow = (newCount - maxPending).coerceAtLeast(0)
        var newIndex = 0
        i = 0
        while (i < suffixText.length) {
            val codePoint = suffixText.codePointAt(i)
            val key = suffixSourceOffset + i
            if (!appearNanos.containsKey(key)) {
                appearNanos[key] = if (newIndex < overflow) {
                    nowNanos - fadeNanos
                } else {
                    nowNanos + (newIndex - overflow) * step
                }
                newIndex++
            }
            i += Character.charCount(codePoint)
        }
    }

    /**
     * Progress [0,1] of the character at [absOffset]'s own fade. Characters
     * the clock has never seen render as settled (1f) — only stamped suffix
     * characters animate.
     */
    fun progressAt(absOffset: Int, nowNanos: Long, fadeNanos: Long): Float {
        val appear = appearNanos[absOffset] ?: return 1f
        if (fadeNanos <= 0L) return 1f
        if (nowNanos <= appear) return 0f
        val progress = (nowNanos - appear).toFloat() / fadeNanos.toFloat()
        return if (progress >= 1f) 1f else progress
    }
}
