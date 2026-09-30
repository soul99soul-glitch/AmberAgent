package app.amber.feature.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageReasoningTest {
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
