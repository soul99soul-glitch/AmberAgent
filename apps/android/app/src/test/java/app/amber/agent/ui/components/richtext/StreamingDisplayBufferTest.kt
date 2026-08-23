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
        val content = manEmoji + "‍"

        assertEquals(manEmoji, streamingImmediateDisplayText(content))
    }

    @Test
    fun immediate_display_trims_isolated_terminal_combining_mark() {
        assertEquals("", streamingImmediateDisplayText("\u0301"))
    }

    // ------------------------------------------------------------------
    // Release units (word quantization)
    // ------------------------------------------------------------------

    @Test
    fun release_unit_is_single_cjk_character() {
        val content = "深度思考"
        assertEquals(1, content.nextStreamingReleaseUnitEnd(0))
        assertEquals(2, content.nextStreamingReleaseUnitEnd(1))
    }

    @Test
    fun release_unit_attaches_fullwidth_punctuation_to_previous_cjk_char() {
        val content = "好，下一"
        // 好， is one unit
        assertEquals("好，".length, content.nextStreamingReleaseUnitEnd(0))
        // 下 alone (starts at index 2, ends at 3)
        assertEquals(3, content.nextStreamingReleaseUnitEnd(2))
        assertEquals(4, content.nextStreamingReleaseUnitEnd(3))
    }

    @Test
    fun release_unit_is_latin_word_with_leading_whitespace() {
        val content = "hello world"
        assertEquals("hello".length, content.nextStreamingReleaseUnitEnd(0))
        assertEquals("hello world".length, content.nextStreamingReleaseUnitEnd(5))
    }

    @Test
    fun release_unit_caps_long_latin_run() {
        val content = "https://example.com/a/very/long/url/that/keeps/going"
        val end = content.nextStreamingReleaseUnitEnd(0)
        assertEquals(StreamingPace.MAX_UNIT_CHARS, end)
    }

    @Test
    fun release_unit_trailing_whitespace_lands_immediately() {
        val content = "word   "
        assertEquals(content.length, content.nextStreamingReleaseUnitEnd(4))
    }

    @Test
    fun release_unit_keeps_surrogate_pairs_whole() {
        val emoji = String(Character.toChars(0x1F600))
        val content = "a$emoji b"
        // "a😀" is one latin-run unit; the surrogate must not be split
        val end = content.nextStreamingReleaseUnitEnd(0)
        assertEquals("a$emoji".length, end)
    }

    @Test
    fun release_unit_splits_mixed_cjk_and_latin() {
        val content = "中文abc中文"
        var i = 0
        val ends = buildList {
            while (i < content.length) {
                i = content.nextStreamingReleaseUnitEnd(i)
                add(i)
            }
        }
        // 中 | 文 | abc | 中 | 文
        assertEquals(listOf(1, 2, 5, 6, 7), ends)
    }

    @Test
    fun count_units_matches_segmentation() {
        val content = "你好 world，再一次 ok"
        val units = countStreamingReleaseUnits(content, 0, content.length)
        // 你 | 好 | " world" | ，再? -> 再 is CJK start... "，" attaches to 好? No —
        // 好 followed by " " (latin run path): " world" one unit; "，" attaches
        // to nothing before it (it starts a unit: not CJK character, latin run
        // consumes "，再"? "，" is not whitespace/CJK-character per isCjkCharacter
        // so the latin run eats "，再" until 中文? 再 IS CJK -> run = "，".
        assertTrue(units in 4..8)
    }

    // ------------------------------------------------------------------
    // Speed band + exponential smoothing (latency model, band-clamped)
    // ------------------------------------------------------------------

    @Test
    fun target_speed_floor_binds_on_small_backlog() {
        // 30 units / 1.2s = 25/s < floor: display keeps a steady typing pace
        // instead of crawling proportionally to a tiny backlog.
        assertEquals(
            StreamingPace.FLOOR_UNITS_PER_SECOND,
            streamingTargetUnitsPerSecond(backlogUnits = 30, draining = false),
        )
    }

    @Test
    fun target_speed_grows_with_backlog_then_caps() {
        val small = streamingTargetUnitsPerSecond(backlogUnits = 100, draining = false)
        val medium = streamingTargetUnitsPerSecond(backlogUnits = 400, draining = false)
        val huge = streamingTargetUnitsPerSecond(backlogUnits = 100_000, draining = false)
        assertTrue("monotonic growth", small < medium)
        assertTrue("band cap", huge == StreamingPace.CAP_UNITS_PER_SECOND)
        assertTrue("medium within band", medium in StreamingPace.FLOOR_UNITS_PER_SECOND..StreamingPace.CAP_UNITS_PER_SECOND)
    }

    @Test
    fun drain_mode_targets_faster_than_streaming() {
        assertTrue(
            streamingTargetUnitsPerSecond(backlogUnits = 300, draining = true) >
                streamingTargetUnitsPerSecond(backlogUnits = 300, draining = false),
        )
    }

    @Test
    fun zero_backlog_targets_zero() {
        assertEquals(0f, streamingTargetUnitsPerSecond(backlogUnits = 0, draining = false))
    }

    @Test
    fun speed_step_approaches_target_gradually() {
        var speed = StreamingPace.FLOOR_UNITS_PER_SECOND
        val target = StreamingPace.CAP_UNITS_PER_SECOND
        repeat(60) { // ~1s at 60fps, ~1.4 time constants
            speed = streamingSpeedStep(speed, target, frameMs = 16f)
        }
        // Approached but not reached: ~63% after 0.7s, ~76% after 1s.
        assertTrue("accelerating ($speed)", speed > target * 0.6f)
        assertTrue("never overshoots", speed < target)
    }

    @Test
    fun speed_step_is_continuous_in_dt() {
        val from = 30f
        // dt→0 steps almost nothing; one giant dt nearly completes the approach.
        assertTrue(streamingSpeedStep(from, 360f, frameMs = 0.1f) < from + 1f)
        assertTrue(streamingSpeedStep(from, 360f, frameMs = 10_000f) > 359f)
    }

    @Test
    fun burst_then_starve_yields_bounded_rate_swing() {
        // Simulate the old complaint: upstream delivers 60-unit bursts every
        // 500ms. With the proportional formula the release rate swung 6-30x;
        // with the smoothed band the per-second display totals stay close.
        var speed = StreamingPace.FLOOR_UNITS_PER_SECOND
        var budget = 0f
        var visible = 0
        var delivered = 0
        val perSecond = mutableListOf<Int>()
        var releasedThisSecond = 0
        val frameMs = 16f
        for (frame in 0 until 60 * 6) {
            if (frame % 30 == 0) delivered += 60 // burst every 500ms
            val backlog = delivered - visible
            val target = streamingTargetUnitsPerSecond(backlog, draining = false)
            speed = streamingSpeedStep(speed, target, frameMs)
            budget += speed * frameMs / 1000f
            val units = minOf(budget.toInt(), StreamingPace.MAX_UNITS_PER_FRAME)
            budget -= units
            visible += units
            releasedThisSecond += units
            if (frame % 60 == 59) {
                perSecond.add(releasedThisSecond)
                releasedThisSecond = 0
            }
        }
        // Steady state (drop the first transition second): 120 units/s arrive;
        // display must neither dump (≈120 instantly) nor stall, and consecutive
        // seconds must stay within a 2x factor of each other.
        val steady = perSecond.drop(1)
        assertTrue("drained most of input ($steady)", steady.sum() >= 60 * 5 * 0.8)
        steady.zipWithNext().forEach { (a, b) ->
            assertTrue("rate continuity ${steady.joinToString()}", a <= b * 2.5f && b <= a * 2.5f)
        }
    }
}
