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
}
