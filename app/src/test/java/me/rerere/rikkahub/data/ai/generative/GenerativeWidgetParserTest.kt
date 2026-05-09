package me.rerere.rikkahub.data.ai.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeWidgetParserTest {
    @Test
    fun parsesWidgetBetweenMarkdownText() {
        val segments = GenerativeWidgetParser.parse(
            """
            Before
            ```show-widget
            {"title":"Flow","widget_code":"<svg viewBox=\"0 0 680 240\"></svg>"}
            ```
            After
            """.trimIndent(),
            streaming = false,
        )

        assertEquals(3, segments.size)
        assertEquals("Before", (segments[0] as GenerativeWidgetSegment.Text).content)
        val widget = segments[1] as GenerativeWidgetSegment.Widget
        assertEquals("Flow", widget.title)
        assertEquals("<svg viewBox=\"0 0 680 240\"></svg>", widget.widgetCode)
        assertTrue(widget.complete)
        assertEquals("After", (segments[2] as GenerativeWidgetSegment.Text).content)
    }

    @Test
    fun extractsPartialWidgetCodeWhileStreaming() {
        val segments = GenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg><rect
            """.trimIndent(),
            streaming = true,
        )

        assertEquals(2, segments.size)
        val widget = segments[1] as GenerativeWidgetSegment.Widget
        assertEquals("Draft", widget.title)
        assertEquals("<svg><rect", widget.widgetCode)
        assertFalse(widget.complete)
    }

    @Test
    fun parsesWidgetFenceAliases() {
        listOf("widget", "generative-ui").forEach { fence ->
            val segments = GenerativeWidgetParser.parse(
                """
                ```$fence
                {"title":"Alias","widget_code":"<svg viewBox=\"0 0 20 20\"><circle cx=\"10\" cy=\"10\" r=\"4\"/></svg>"}
                ```
                """.trimIndent(),
                streaming = false,
            )

            val widget = segments.single() as GenerativeWidgetSegment.Widget
            assertEquals("Alias", widget.title)
            assertTrue(widget.widgetCode.contains("<circle"))
        }
    }

    @Test
    fun malformedFinalWidgetFallsBackToText() {
        val segments = GenerativeWidgetParser.parse(
            """
            ```show-widget
            not-json
            """.trimIndent(),
            streaming = false,
        )

        assertEquals(1, segments.size)
        assertTrue((segments.single() as GenerativeWidgetSegment.Text).content.contains("show-widget"))
    }

    @Test
    fun inlineShowWidgetTextDoesNotTriggerWidgetParsing() {
        val content = "这里解释一下 `show-widget` 协议本身，不应该显示生成中。"

        assertFalse(GenerativeWidgetParser.containsWidgetFence(content))
        val segments = GenerativeWidgetParser.parse(content, streaming = true)

        assertEquals(1, segments.size)
        assertEquals(content, (segments.single() as GenerativeWidgetSegment.Text).content)
    }

    @Test
    fun rejectsPlaceholderWidgetAsRenderableWidget() {
        val content = """
            ```show-widget
            {"title":"Human readable title","widget_code":"<svg or static HTML/CSS>"}
            ```
        """.trimIndent()

        assertFalse(GenerativeWidgetParser.hasRenderableWidget(content))

        val segments = GenerativeWidgetParser.parse(content, streaming = false)
        assertTrue(segments.none { it is GenerativeWidgetSegment.Widget })
        assertTrue((segments.single() as GenerativeWidgetSegment.Text).content.contains("show-widget"))
    }

    @Test
    fun detectsRenderableWidget() {
        val content = """
            ```show-widget
            {"title":"Real","widget_code":"<svg viewBox=\"0 0 20 20\"><text x=\"2\" y=\"12\">A</text></svg>"}
            ```
        """.trimIndent()

        assertTrue(GenerativeWidgetParser.hasRenderableWidget(content))
    }

    @Test
    fun parsesNativeActionsAndFiltersUnsafeActions() {
        val segments = GenerativeWidgetParser.parse(
            """
            ```show-widget
            {"title":"Risk","widget_code":"<svg viewBox=\"0 0 20 20\"><text x=\"2\" y=\"12\">R</text></svg>","actions":[{"id":"explain","label":"解释","instruction":"解释图中的关键风险"},{"id":"bad","label":"打开链接","instruction":"https://example.com"},{"id":"next","label":"下一步","instruction":"给出下一步建议"}]}
            ```
            """.trimIndent(),
            streaming = false,
        )

        val widget = segments.single() as GenerativeWidgetSegment.Widget
        assertEquals(2, widget.actions.size)
        assertEquals("解释", widget.actions.first().label)
        assertTrue(widget.actions.first().toUserPrompt(widget.title).contains("关键风险"))
    }

    @Test
    fun rendersStructuredChartSpec() {
        val segments = GenerativeWidgetParser.parse(
            """
            ```show-widget
            {"title":"Trend","renderer":"chart","spec":{"type":"line","x":["Mon","Tue"],"series":[{"name":"Count","data":[1,3]}]}}
            ```
            """.trimIndent(),
            streaming = false,
        )

        val widget = segments.single() as GenerativeWidgetSegment.Widget
        assertEquals("chart", widget.renderer)
        assertTrue(widget.widgetCode.contains("<svg"))
        assertTrue(widget.widgetCode.contains("Count"))
    }

    @Test
    fun clampsStructuredChartNumbersToFiniteNonNegativeValues() {
        val segments = GenerativeWidgetParser.parse(
            """
            ```show-widget
            {"title":"Trend","renderer":"chart","spec":{"type":"bar","x":["A","B","C"],"series":[{"name":"Count","data":[-10,1e309,5]}]}}
            ```
            """.trimIndent(),
            streaming = false,
        )

        val widget = segments.single() as GenerativeWidgetSegment.Widget
        assertFalse(widget.widgetCode.contains("NaN"))
        assertFalse(widget.widgetCode.contains("Infinity"))
        assertFalse(widget.widgetCode.contains("""height="-"""))
    }

    @Test
    fun rendersStructuredFlowWithinViewBox() {
        val segments = GenerativeWidgetParser.parse(
            """
            ```show-widget
            {"title":"Flow","renderer":"diagram","spec":{"type":"flow","nodes":[{"label":"A"},{"label":"B"},{"label":"C"},{"label":"D"}]}}
            ```
            """.trimIndent(),
            streaming = false,
        )

        val widget = segments.single() as GenerativeWidgetSegment.Widget
        assertEquals("diagram", widget.renderer)
        assertTrue(widget.widgetCode.contains("viewBox=\"0 0 680 220\""))
        assertFalse(widget.widgetCode.contains("x=\"562\""))
    }
}
