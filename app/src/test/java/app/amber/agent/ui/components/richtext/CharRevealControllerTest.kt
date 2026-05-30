package app.amber.feature.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharRevealControllerTest {

    @Test
    fun default_controller_does_not_apply_immediate_tail_trim() {
        val content = buildString {
            repeat(220) {
                append('x')
                append(' ')
            }
        }
        val controller = CharRevealController(revealDurationNanos = 10_000_000_000L)

        controller.onContentChanged(content)

        assertTrue(controller.queueDepth() > 150)
        assertEquals(0, controller.stableOffsetExclusive())
    }

    @Test
    fun small_tail_is_not_trimmed_inside_time_window() {
        val controller = CharRevealController(
            revealDurationNanos = 10_000_000_000L,
            trimRevealTail = true,
        )
        controller.onContentChanged("alpha beta gamma")
        val before = controller.queueDepth()

        controller.onFrame(System.nanoTime() + 100_000_000L)

        assertTrue(before > 0)
        assertEquals(before, controller.queueDepth())
    }

    @Test
    fun trim_reveal_window_promotes_entries_older_than_time_window() {
        val content = "alpha beta gamma"
        val controller = CharRevealController(
            revealDurationNanos = 10_000_000_000L,
            trimRevealTail = true,
        )
        controller.onContentChanged(content)

        controller.onFrame(System.nanoTime() + 500_000_000L)

        assertEquals(0, controller.queueDepth())
        assertEquals(content.length, controller.stableOffsetExclusive())
    }

    @Test
    fun trim_reveal_window_applies_entry_cap() {
        val content = buildString {
            repeat(220) {
                append('x')
                append(' ')
            }
        }
        val controller = CharRevealController(
            revealDurationNanos = 10_000_000_000L,
            trimRevealTail = true,
        )

        controller.onContentChanged(content)

        assertTrue(controller.queueDepth() <= 150)
    }

    @Test
    fun trim_reveal_window_applies_char_span_cap() {
        val content = buildString {
            repeat(40) {
                append("abcdefghijabcdefghij ")
            }
        }
        val controller = CharRevealController(
            revealDurationNanos = 10_000_000_000L,
            trimRevealTail = true,
        )

        controller.onContentChanged(content)

        assertTrue(content.length - controller.stableOffsetExclusive() <= 400)
    }
}
