package app.amber.feature.modelcouncil

/**
 * Coalesces cumulative streaming-text emissions to a frame-scale cadence.
 * Providers deliver chunks far faster than Compose can render; this gate
 * forwards at most one emission per [LIVE_EMIT_INTERVAL_NANOS] (plus forced
 * final emissions), deduping unchanged text. [text] is materialized lazily so
 * a throttled tick costs nothing.
 *
 * Shared by [ProviderModelCouncilTextRunner] (member/host turns) and the
 * app-layer host tool turn, which previously had no throttle at all.
 */
class CumulativeTextThrottle(
    private val onEmit: (String) -> Unit,
) {
    private var lastEmitNanos = 0L
    private var lastEmittedText: String = ""

    var skippedByThrottle = 0
        private set
    var skippedNoChange = 0
        private set
    var forwarded = 0
        private set

    fun offer(force: Boolean = false, text: () -> String) {
        if (!force && System.nanoTime() - lastEmitNanos < LIVE_EMIT_INTERVAL_NANOS) {
            skippedByThrottle++
            return
        }
        val value = text()
        if (value == lastEmittedText) {
            skippedNoChange++
            return
        }
        lastEmittedText = value
        lastEmitNanos = System.nanoTime()
        forwarded++
        onEmit(value)
    }

    companion object {
        /** Live-emit cadence for council streams (~one emission per frame). */
        const val LIVE_EMIT_INTERVAL_NANOS = 32_000_000L
    }
}
