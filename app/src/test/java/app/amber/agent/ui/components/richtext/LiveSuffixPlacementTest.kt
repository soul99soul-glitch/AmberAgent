package app.amber.feature.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSuffixPlacementTest {
    @Test
    fun inline_suffix_stays_in_paragraph() {
        assertEquals(LiveSuffixPlacement.Inline, liveSuffixPlacementFor("hello"))
    }

    @Test
    fun heading_suffix_uses_plain_tail() {
        assertEquals(LiveSuffixPlacement.Plain, liveSuffixPlacementFor("## title"))
    }

    @Test
    fun list_suffix_uses_plain_tail() {
        assertEquals(LiveSuffixPlacement.Plain, liveSuffixPlacementFor("- item"))
    }

    @Test
    fun blank_line_suffix_uses_plain_tail() {
        assertEquals(LiveSuffixPlacement.Plain, liveSuffixPlacementFor("\n\nnext block"))
    }

    @Test
    fun table_suffix_uses_plain_tail() {
        assertEquals(LiveSuffixPlacement.Plain, liveSuffixPlacementFor("| a | b |"))
    }

    @Test
    fun split_without_blank_line_keeps_everything_inline() {
        assertEquals("heading tail" to "", splitLiveSuffixAtBlockBoundary("heading tail"))
    }

    @Test
    fun split_at_blank_line_separates_following_block() {
        assertEquals(
            "heading" to "\n\nbody text",
            splitLiveSuffixAtBlockBoundary("heading\n\nbody text"),
        )
    }

    @Test
    fun split_handles_windows_blank_line() {
        assertEquals(
            "heading" to "\r\n\r\nbody",
            splitLiveSuffixAtBlockBoundary("heading\r\n\r\nbody"),
        )
    }

    @Test
    fun split_at_leading_blank_line_yields_empty_inline() {
        assertEquals("" to "\n\nbody", splitLiveSuffixAtBlockBoundary("\n\nbody"))
    }
}
