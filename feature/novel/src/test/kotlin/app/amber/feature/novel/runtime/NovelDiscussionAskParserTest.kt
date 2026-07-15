package app.amber.feature.novel.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelDiscussionAskParserTest {
    @Test
    fun `parses ask_user fence and strips display content`() {
        val content = """
            建议先定视角。

            ```ask_user
            {"questions":[{"id":"pov","question":"用谁的视角推进？","options":["女主","反派","双线"],"selection_type":"single"}]}
            ```
        """.trimIndent()

        val parsed = NovelDiscussionAskParser.parse(content)
        assertNotNull(parsed)
        assertEquals("建议先定视角。", parsed!!.displayContent)
        assertEquals(1, parsed.questions.size)
        assertEquals("pov", parsed.questions[0].id)
        assertEquals(listOf("女主", "反派", "双线"), parsed.questions[0].options)
        assertEquals("single", parsed.questions[0].selectionType)
    }

    @Test
    fun `returns null without fence`() {
        assertNull(NovelDiscussionAskParser.parse("只是普通讨论，没有选项。"))
    }

    @Test
    fun `formatAnswer joins selected options`() {
        val q = NovelDiscussionAskParser.Question(
            id = "q1",
            question = "怎么收？",
            options = listOf("留白", "摊牌"),
            selectionType = "single",
        )
        val text = NovelDiscussionAskParser.formatAnswer(
            listOf(q),
            mapOf("q1" to "摊牌"),
        )
        assertTrue(text.contains("怎么收？"))
        assertTrue(text.contains("摊牌"))
    }

    @Test
    fun `stripFence hides incomplete streaming open fence`() {
        val partial = """
            先看冲突点。

            ```ask_user
            {"questions":[{"id":"q1","question":"选
        """.trimIndent()
        val stripped = NovelDiscussionAskParser.stripFence(partial)
        assertEquals("先看冲突点。", stripped)
        assertTrue(!stripped.contains("ask_user"))
    }

    @Test
    fun `formatAnswerFromToolPayload reads chat ask_user JSON`() {
        val q = NovelDiscussionAskParser.Question(
            id = "pov",
            question = "用谁的视角？",
            options = listOf("女主", "反派"),
            selectionType = "single",
        )
        val text = NovelDiscussionAskParser.formatAnswerFromToolPayload(
            """{"answers":{"pov":"女主"}}""",
            listOf(q),
        )
        assertTrue(text.contains("女主"))
        assertTrue(text.contains("用谁的视角"))
    }

    @Test
    fun `parses camelCase selectionType`() {
        val content = """
            x
            ```ask_user
            {"questions":[{"id":"q1","question":"多选？","options":["A","B"],"selectionType":"multi"}]}
            ```
        """.trimIndent()
        val parsed = NovelDiscussionAskParser.parse(content)
        assertNotNull(parsed)
        assertEquals("multi", parsed!!.questions[0].selectionType)
    }
}
