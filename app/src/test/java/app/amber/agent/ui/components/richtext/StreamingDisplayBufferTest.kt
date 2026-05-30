package app.amber.feature.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingDisplayBufferTest {

    @Test
    fun immediate_display_returns_full_complete_content() {
        val content = "Streaming content ".repeat(80)

        assertEquals(content, streamingImmediateDisplayText(content))
    }

    @Test
    fun immediate_display_trims_dangling_high_surrogate() {
        val content = "hello " + '\uD83D'

        assertEquals("hello ", streamingImmediateDisplayText(content))
    }

    @Test
    fun immediate_display_trims_terminal_zwj_boundary() {
        val manEmoji = String(Character.toChars(0x1F468))
        val content = manEmoji + "\u200D"

        assertEquals(manEmoji, streamingImmediateDisplayText(content))
    }

    @Test
    fun immediate_display_trims_isolated_terminal_combining_mark() {
        assertEquals("", streamingImmediateDisplayText("\u0301"))
    }

    @Test
    fun streaming_backlog_can_catch_up_faster_than_one_char_per_frame() {
        assertTrue(
            "Fast model backlogs must not be capped at one visible char per frame.",
            streamingDisplayMaxCharsPerEmit(streaming = true) > 1,
        )
    }

    @Test
    fun streaming_target_speed_reaches_fast_model_range_before_completion() {
        val speed = streamingDisplayTargetSpeed(backlog = 240, streaming = true)
        assertTrue(
            "A 240-char backlog should drain during streaming instead of waiting for final flush.",
            speed >= 800f,
        )
    }

    @Test
    fun final_drain_is_faster_than_streaming_catch_up() {
        assertTrue(
            streamingDisplayMaxCharsPerEmit(streaming = false) >
                streamingDisplayMaxCharsPerEmit(streaming = true),
        )
        assertTrue(
            streamingDisplayTargetSpeed(backlog = 240, streaming = false) >
                streamingDisplayTargetSpeed(backlog = 240, streaming = true),
        )
    }

    @Test
    fun streaming_backlog_has_hard_lag_bound() {
        val maxBacklog = streamingDisplayMaxBacklogChars()
        val targetLength = 2_000
        val visibleLength = 1_000
        val catchUpEnd = streamingDisplayBacklogCatchUpEnd(
            visibleLength = visibleLength,
            targetLength = targetLength,
        )

        assertTrue(
            "Backlog cap should advance visible text when fast models outrun the comfort speed.",
            catchUpEnd > visibleLength,
        )
        assertTrue(
            "Display buffer should leave at most the configured backlog after a hard catch-up.",
            targetLength - catchUpEnd <= maxBacklog,
        )
    }

    @Test
    fun streaming_backlog_cap_is_inactive_inside_lag_bound() {
        val maxBacklog = streamingDisplayMaxBacklogChars()
        val targetLength = 1_000
        val visibleLength = targetLength - maxBacklog + 12

        assertTrue(targetLength - visibleLength < maxBacklog)
        assertTrue(
            "Backlog cap should not disturb normal smooth drain when lag is already bounded.",
            streamingDisplayBacklogCatchUpEnd(
                visibleLength = visibleLength,
                targetLength = targetLength,
            ) == visibleLength,
        )
    }

    @Test
    fun streaming_speed_cap_does_not_throttle_below_the_lag_bound() {
        // The deadline-drain must be able to reach the speed needed to hold the
        // backlog at its cap (MAX_BACKLOG_CHARS / TARGET_DRAIN_SECONDS). If the
        // speed cap is lower, the drain can never hold the lag and the hard
        // backlog catch-up (snap) fires on every fast model instead of acting as
        // a rare backstop — the cause of the "20-char wave" stutter.
        // TARGET_DRAIN_SECONDS is 0.28f.
        val maxBacklog = streamingDisplayMaxBacklogChars()
        val speedNeededToHoldLag = maxBacklog / 0.28f
        assertTrue(
            "Speed cap throttles the deadline-drain below the lag bound; raise " +
                "STREAM_DISPLAY_MAX_CHARS_PER_SEC to >= MAX_BACKLOG_CHARS / TARGET_DRAIN_SECONDS.",
            streamingDisplayTargetSpeed(backlog = maxBacklog, streaming = true) >= speedNeededToHoldLag,
        )
    }
}
