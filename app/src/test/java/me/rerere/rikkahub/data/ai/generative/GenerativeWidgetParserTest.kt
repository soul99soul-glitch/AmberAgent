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

    @Test
    fun normalizesWrappedSlidesSpecForExpandedPreview() {
        val segments = GenerativeWidgetParser.parse(
            """
            ```show-widget
            {"title":"Deck","renderer":"slides","spec":{"schemaVersion":2,"style":"magazine","accent":"#123456","fontPack":"source-han-serif-sc-regular","slides":[{"layout":"cover","title":"A","content":["B"]}]}}
            ```
            """.trimIndent(),
            streaming = false,
        )

        val widget = segments.single() as GenerativeWidgetSegment.Widget
        assertEquals("slides", widget.renderer)
        assertEquals(
            """{"schemaVersion":2,"style":"magazine","accent":"#123456","fontPack":"source-han-serif-sc-regular","slides":[{"layout":"cover","title":"A","content":["B"]}]}""",
            widget.specJson,
        )
        assertTrue(widget.widgetCode.contains("A"))
    }

    @Test
    fun normalizesPagesWrappedSlidesSpecForExpandedPreview() {
        val segments = GenerativeWidgetParser.parse(
            """
            ```show-widget
            {"title":"Deck","renderer":"slides","spec":{"pages":[{"title":"Mobile","content":["Readable"]}]}}
            ```
            """.trimIndent(),
            streaming = false,
        )

        val widget = segments.single() as GenerativeWidgetSegment.Widget
        assertEquals("slides", widget.renderer)
        assertEquals("""{"schemaVersion":2,"style":"system","slides":[{"title":"Mobile","content":["Readable"]}]}""", widget.specJson)
        assertTrue(widget.widgetCode.contains("Mobile"))
    }

    @Test
    fun parsesGuizangHtmlRendererAndPreservesSpecHtml() {
        val html = """
            <!DOCTYPE html>
            <html><body><div id="deck"><section class="slide dark">A</section></div></body></html>
        """.trimIndent()
        val content = """
            ```show-widget
            {"title":"Live Deck","renderer":"guizang_html","widget_code":"<svg viewBox=\"0 0 20 20\"><text x=\"2\" y=\"12\">G</text></svg>","spec":{"html":${jsonString(html)},"source":"guizang-ppt-skill","allowRemoteImages":true,"allowRemoteFonts":false}}
            ```
        """.trimIndent()

        val widget = GenerativeWidgetParser.parse(content, streaming = false).single() as GenerativeWidgetSegment.Widget

        assertEquals("guizang_html", widget.renderer)
        assertTrue(widget.specJson!!.contains("<section class=\\\"slide dark\\\">A</section>"))
        assertTrue(widget.specJson.contains("guizang-ppt-skill"))
        assertTrue(widget.widgetCode.contains("<svg"))
    }

    private fun jsonString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""

    @Test
    fun validatesWrappedSlidesSpec() {
        val result = VChartSpecValidator.validateSlidesSpec("""{"slides":[{"title":"A","content":["B"]}]}""")

        assertTrue(result.valid)
        assertEquals("""[{"title":"A","content":["B"]}]""", VChartSpecValidator.normalizeSlidesSpecJson("""{"slides":[{"title":"A","content":["B"]}]}"""))
        assertEquals(
            """{"schemaVersion":2,"style":"system","slides":[{"title":"A","content":["B"]}]}""",
            VChartSpecValidator.normalizeSlidesDeckSpecJson("""{"slides":[{"title":"A","content":["B"]}]}"""),
        )
    }

    @Test
    fun validatesSlidesSpecV2LayoutAndFontPack() {
        val spec = """
            {"schemaVersion":2,"style":"swiss","accent":"#1F5EFF","fontPack":"source-han-sans-sc-regular","slides":[{"layout":"metrics","title":"A","metrics":[{"label":"速度","value":"2x"}]}]}
        """.trimIndent()

        assertTrue(VChartSpecValidator.validateSlidesSpec(spec).valid)
        assertTrue(VChartSpecValidator.normalizeSlidesDeckSpecJson(spec)!!.contains("source-han-sans-sc-regular"))
    }

    @Test
    fun rejectsUnsupportedSlidesLayout() {
        val result = VChartSpecValidator.validateSlidesSpec(
            """{"slides":[{"layout":"desktop-html","title":"A"}]}"""
        )

        assertFalse(result.valid)
    }

    @Test
    fun rejectsDangerousSlidesObjectKeys() {
        val result = VChartSpecValidator.validateSlidesSpec(
            """{"slides":[{"title":"A","__proto__":{"polluted":true}}]}"""
        )

        assertFalse(result.valid)
    }
}
