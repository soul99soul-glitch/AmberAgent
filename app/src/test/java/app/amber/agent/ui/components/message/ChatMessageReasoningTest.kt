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

    @Test
    fun follow_step_eases_a_single_line_instead_of_jumping() {
        val density = 4f
        val line = 92f
        // 旧口径 540dp/s：单行第一帧（1/60s）就走 36px，3 帧走完——急起急停。
        val first = reasoningFollowStep(line, 1f / 60f, density)
        assertTrue("first=$first", first < 36f * 0.3f)
        var remaining = line
        var frames = 0
        var lastStep = Float.MAX_VALUE
        while (remaining > 0f && frames < 600) {
            val step = reasoningFollowStep(remaining, 1f / 60f, density)
            assertTrue("临近底部只减速不加速", step <= lastStep + 1e-3f)
            lastStep = step
            remaining -= step
            frames++
        }
        assertTrue("有限时间收住 frames=$frames", frames in 17..120)
    }

    @Test
    fun follow_step_keeps_the_existing_speed_cap_for_large_gaps() {
        val density = 4f
        val step = reasoningFollowStep(10_000f, 1f / 60f, density)
        assertEquals(540f * density / 60f, step, 0.01f)
    }
}
