package app.amber.feature.ui.pages.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Monotonic draw-generation signal for "the timeline has actually been drawn".
 *
 * A one-shot Deferred was wrong for restore/re-entry: after
 * `initialized=false -> true` it stayed completed, so background prewarm could
 * start before the new list had ever been laid out. Capturing [snapshot] before
 * mounting and awaiting [awaitAfter] makes every cycle wait for its own draw.
 */
internal class TimelineFrameSignal {
    private val generation = MutableStateFlow(0L)

    fun snapshot(): Long = generation.value

    fun signal() {
        generation.value += 1
    }

    suspend fun awaitAfter(baseline: Long) {
        generation.first { it > baseline }
    }
}
