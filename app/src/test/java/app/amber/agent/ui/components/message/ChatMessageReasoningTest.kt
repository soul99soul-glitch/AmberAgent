package app.amber.feature.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageReasoningTest {
    @Test
    fun `loading reasoning preview is capped from the tail`() {
        val raw = "a".repeat(2_100) + "TAIL"

        val display = raw.toDisplayReasoningText(loading = true, expanded = false)

        assertTrue(display.startsWith("… 已省略前"))
        assertTrue(display.endsWith("TAIL"))
        assertTrue(display.length < raw.length)
    }

    @Test
    fun `short reasoning is unchanged`() {
        assertEquals(
            "thinking",
            "thinking".toDisplayReasoningText(loading = true, expanded = false),
        )
    }

    @Test
    fun `reasoning tail trim only starts past active limit`() {
        assertEquals(
            false,
            "a".repeat(2_000).isReasoningTailTrimmed(loading = true, expanded = false),
        )
        assertEquals(
            true,
            "a".repeat(2_001).isReasoningTailTrimmed(loading = true, expanded = false),
        )
        assertTrue(
            "a".repeat(2_001)
                .toDisplayReasoningText(loading = true, expanded = true)
                .startsWith("… 已省略前"),
        )
    }

    @Test
    fun `reasoning widget source extracts only fenced widget tail`() {
        val raw = """
            private thinking
            ```show-widget
            {"title":"Flow","widget_code":"<svg></svg>"}
        """.trimIndent()

        val source = raw.reasoningWidgetSource()

        assertTrue(source!!.startsWith("```show-widget"))
        assertTrue(source.contains("<svg></svg>"))
        assertTrue(!source.contains("private thinking"))
    }

    @Test
    fun `reasoning widget source ignores inline marker`() {
        assertEquals(
            null,
            "thinking before ```show-widget".reasoningWidgetSource(),
        )
    }

    @Test
    fun `reasoning widget source accepts fence aliases`() {
        val raw = """
            private thinking
            ```widget
            {"title":"Flow","widget_code":"<svg></svg>"}
        """.trimIndent()

        val source = raw.reasoningWidgetSource()

        assertTrue(source!!.startsWith("```widget"))
        assertTrue(!source.contains("private thinking"))
    }
}
