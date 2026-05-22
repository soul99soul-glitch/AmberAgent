package me.rerere.rikkahub.data.agent.board.hotlist

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadDiagram
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadDiagramEdge
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadDiagramNode
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadImageAsset
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.IMAGE_CONFIDENCE_HERO
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.ReadingLink
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.TimelineEvent
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateRenderer
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateDraftGuard
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplatePackage
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateRepository
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadTemplateValidatorTest {
    @Test
    fun acceptsStaticHtmlAndCss() {
        val result = DeepReadTemplateValidator.validate(
            """
            <!doctype html>
            <html>
              <head><style>body{margin:0}.hero{clip-path:polygon(0 0,100% 0,100% 80%,0 100%)}</style></head>
              <body><article><h1>中文深读</h1><p>静态模板内容</p></article></body>
            </html>
            """.trimIndent(),
        )

        assertTrue(result.ok)
    }

    @Test
    fun rejectsScriptAndEventHandlers() {
        assertFalse(DeepReadTemplateValidator.validate("<html><body><script>alert(1)</script></body></html>").ok)
        assertFalse(DeepReadTemplateValidator.validate("<html><body><div onclick=\"x()\">tap</div></body></html>").ok)
        assertFalse(DeepReadTemplateValidator.validate("<html><body><div onclick='x()'>tap</div></body></html>").ok)
        assertFalse(DeepReadTemplateValidator.validate("<html><body><div onclick=x()>tap</div></body></html>").ok)
    }

    @Test
    fun rejectsEmbeddedAndExternalResources() {
        assertFalse(DeepReadTemplateValidator.validate("<html><body><iframe src=\"https://example.com\"></iframe></body></html>").ok)
        assertFalse(DeepReadTemplateValidator.validate("<html><body><svg><path d=\"M0 0\"/></svg></body></html>").ok)
        assertFalse(DeepReadTemplateValidator.validate("<html><body><canvas></canvas></body></html>").ok)
        assertFalse(DeepReadTemplateValidator.validate("<html><body><video poster=\"cover.jpg\"></video></body></html>").ok)
        assertFalse(DeepReadTemplateValidator.validate("<html><body><img src=\"{{hero_image_url}}\" srcset=\"x.jpg 2x\"/></body></html>").ok)
        assertFalse(
            DeepReadTemplateValidator.validate(
                "<html><head><link rel=\"stylesheet\" href=\"https://example.com/a.css\"></head><body></body></html>",
            ).ok,
        )
        assertFalse(
            DeepReadTemplateValidator.validate(
                "<html><head><style>.x{background:url(https://example.com/a.png)}</style></head><body></body></html>",
            ).ok,
        )
        assertFalse(
            DeepReadTemplateValidator.validate(
                "<html><head><style>.x{background:url('/local.png')}</style></head><body></body></html>",
            ).ok,
        )
    }

    @Test
    fun sourceEditKeepsLastValidDraftWhenInvalid() {
        val validHtml = """
            <!doctype html><html><body>
            <h1>{{title}}</h1><p>{{summary}}</p>
            <section>{{narrative_html}}</section>
            <section>{{analysis_html}}</section>
            <nav>{{extended_reading_html}}</nav>
            </body></html>
        """.trimIndent()
        val current = DeepReadTemplatePackage(
            id = "draft",
            name = "有效模板",
            html = validHtml,
            createdByAi = true,
        )

        val result = DeepReadTemplateDraftGuard.applySourceEdit(
            currentDraft = current,
            name = "有效模板",
            html = validHtml.replace("{{summary}}", "<script>alert(1)</script>"),
        )

        assertEquals(current, result.validDraft)
        assertTrue(result.validationError?.isNotBlank() == true)
    }

    @Test
    fun rendererUsesOnlyVerifiedImageAssets() {
        val verified = "https://news.example.com/source.jpg"
        val invented = "https://tracker.example.com/invented.jpg"
        val invalid = "data:image/png;base64,abc"
        val rendered = DeepReadTemplateRenderer.renderEditorialSlant(
            title = "中文深读",
            output = DeepReadOutput(
                summary = "这是中文摘要",
                heroImageUrl = invented,
                timeline = listOf(
                    TimelineEvent(
                        date = "2026-05-20",
                        event = "事件节点",
                        imageUrl = invented,
                        imageCaption = "不应展示",
                    )
                ),
                analysis = DeepAnalysis(implications = "影响分析"),
                imageAssets = listOf(
                    DeepReadImageAsset(url = invalid, caption = "不应作为 fallback"),
                    DeepReadImageAsset(url = verified, caption = "来源图片"),
                ),
            ),
        )

        assertEquals(setOf(verified), rendered.allowedImageUrls)
        assertFalse(rendered.html.contains(invented))
        assertFalse(rendered.html.contains(invalid))
        assertTrue(rendered.html.contains(verified))
    }

    @Test
    fun customRendererFillsPlaceholdersWithoutInventedImages() {
        val verified = "https://news.example.com/source.jpg"
        val template = """
            <!doctype html><html><head><style>{{font_css}} body{margin:0}</style></head><body>
            <h1>{{title}}</h1>
            <p>{{summary}}</p>
            <img src="{{hero_image_url}}"/>
            <section>{{timeline_html}}</section>
            <section>{{analysis_html}}</section>
            <nav>{{extended_reading_html}}</nav>
            </body></html>
        """.trimIndent()

        val rendered = DeepReadTemplateRenderer.renderCustom(
            title = "标题 <script>",
            output = DeepReadOutput(
                summary = "中文摘要",
                heroImageUrl = "https://invented.example.com/hero.jpg",
                timeline = listOf(TimelineEvent(date = "今天", event = "节点", imageUrl = verified)),
                analysis = DeepAnalysis(implications = "分析"),
                extendedReading = listOf(ReadingLink("来源", "https://example.com/a", "example")),
                imageAssets = listOf(DeepReadImageAsset(url = verified, caption = "来源图")),
            ),
            templateHtml = template,
        )

        assertEquals(setOf(verified), rendered.allowedImageUrls)
        assertTrue(rendered.html.contains("标题 &lt;script&gt;"))
        assertFalse(rendered.html.contains("invented.example.com"))
        assertTrue(rendered.html.contains(verified))
        assertEquals(setOf("https://example.com/a"), rendered.allowedLinkUrls)
    }

    @Test
    fun customRendererUsesHeroOnlyWhenConfidenceIsHero() {
        val verified = "https://news.example.com/source.jpg"
        val template = """
            <!doctype html><html><body>
            <h1>{{title}}</h1><p>{{summary}}</p><img src="{{hero_image_url}}"/>
            <section>{{narrative_html}}</section><section>{{diagram_html}}</section>
            <section>{{analysis_html}}</section><nav>{{extended_reading_html}}</nav>
            </body></html>
        """.trimIndent()

        val rendered = DeepReadTemplateRenderer.renderCustom(
            title = "中文深读",
            output = DeepReadOutput(
                summary = "中文摘要",
                heroImageUrl = verified,
                heroImageConfidence = IMAGE_CONFIDENCE_HERO,
                analysis = DeepAnalysis(implications = "分析"),
                imageAssets = listOf(
                    DeepReadImageAsset(url = verified, caption = "来源图", confidence = IMAGE_CONFIDENCE_HERO),
                ),
            ),
            templateHtml = template,
        )

        assertTrue(rendered.html.contains(verified))
    }

    @Test
    fun diagramRendererUsesReadableHtmlInsteadOfRawSvg() {
        val rendered = DeepReadTemplateRenderer.renderEditorialSlant(
            title = "国产模型适配国产算力",
            output = DeepReadOutput(
                summary = "这是中文摘要",
                diagram = DeepReadDiagram(
                    type = "process_flow",
                    title = "国产大模型适配国产算力",
                    nodes = listOf(
                        DeepReadDiagramNode("n1", "政策定调", "国家政策给出方向"),
                        DeepReadDiagramNode("n2", "行业跟进", "发改委等部门推动落地"),
                        DeepReadDiagramNode("n3", "模型适配", "模型绑定国产算力环境"),
                    ),
                    edges = listOf(
                        DeepReadDiagramEdge("n1", "n2", "推动"),
                        DeepReadDiagramEdge("n2", "n3", "落地"),
                    ),
                ),
                analysis = DeepAnalysis(implications = "分析"),
            ),
        )

        assertFalse(rendered.html.contains("<svg"))
        assertFalse(rendered.html.contains("diagram-edge"))
        assertTrue(rendered.html.contains("diagram-step"))
        assertTrue(rendered.html.contains("政策定调"))
    }

    @Test
    fun customTemplateRequiresTitleAndSummaryPlaceholders() {
        val template = "<!doctype html><html><body><h1>{{title}}</h1></body></html>"

        val error = runCatching {
            DeepReadTemplateRepository.validateCustomTemplate(template)
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("summary") == true)
    }

    @Test
    fun customTemplateRejectsHardcodedExternalLinksAndResources() {
        val hardLink = """
            <!doctype html><html><body>
            <h1>{{title}}</h1><p>{{summary}}</p><section>{{timeline_html}}</section>
            <section>{{analysis_html}}</section><nav>{{extended_reading_html}}</nav>
            <a href="https://phishing.example.com">bad</a>
            </body></html>
        """.trimIndent()
        val hardImage = hardLink.replace(
            """<a href="https://phishing.example.com">bad</a>""",
            """<img src="https://tracker.example.com/a.png"/>""",
        )

        assertFalse(DeepReadTemplateValidator.validate(hardLink).ok)
        assertFalse(DeepReadTemplateValidator.validate(hardImage).ok)
        assertFalse(DeepReadTemplateValidator.validate(hardLink.replace("href=\"https://", "href=https://")).ok)
        assertFalse(DeepReadTemplateValidator.validate(hardImage.replace("src=\"https://", "src=https://").replace("\"/>", "/>")).ok)
    }

    @Test
    fun customTemplateRejectsHeroImagePlaceholderOutsideImgSrc() {
        val linkPreload = """
            <!doctype html><html><head>
            <link rel="preload" as="image" href="{{hero_image_url}}">
            </head><body>
            <h1>{{title}}</h1><p>{{summary}}</p>
            <section>{{narrative_html}}</section><section>{{analysis_html}}</section>
            <nav>{{extended_reading_html}}</nav><img src="{{hero_image_url}}"/>
            </body></html>
        """.trimIndent()
        val cssImageSet = linkPreload
            .replace("""<link rel="preload" as="image" href="{{hero_image_url}}">""", "")
            .replace("</head>", """<style>.hero{background-image:-webkit-image-set("{{hero_image_url}}" 1x)}</style></head>""")
        val dataAttribute = linkPreload
            .replace("""<link rel="preload" as="image" href="{{hero_image_url}}">""", "")
            .replace("""<img src="{{hero_image_url}}"/>""", """<img src="{{hero_image_url}}" data-hero="{{hero_image_url}}"/>""")

        assertFalse(DeepReadTemplateValidator.validate(linkPreload).ok)
        assertFalse(DeepReadTemplateValidator.validate(cssImageSet).ok)
        assertFalse(DeepReadTemplateValidator.validate(dataAttribute).ok)
    }

    @Test
    fun customTemplateRejectsPlaceholderLinksAndStyleAttributes() {
        val placeholderLink = """
            <!doctype html><html><body>
            <h1>{{title}}</h1><p>{{summary}}</p>
            <section>{{narrative_html}}</section><section>{{analysis_html}}</section>
            <nav>{{extended_reading_html}}</nav><a href="{{extended_reading_html}}">bad</a>
            </body></html>
        """.trimIndent()
        val styleAttribute = placeholderLink
            .replace("""<a href="{{extended_reading_html}}">bad</a>""", """<div style="color:{{title}}">bad</div>""")

        assertFalse(DeepReadTemplateValidator.validate(placeholderLink).ok)
        assertFalse(DeepReadTemplateValidator.validate(styleAttribute).ok)
    }

    @Test
    fun customTemplateRejectsBlockPlaceholderInsideAttributes() {
        val template = """
            <!doctype html><html><body>
            <h1>{{title}}</h1><p>{{summary}}</p>
            <div data-items="{{diagram_html}}"></div>
            <section>{{analysis_html}}</section><nav>{{extended_reading_html}}</nav>
            </body></html>
        """.trimIndent()

        assertFalse(DeepReadTemplateValidator.validate(template).ok)
    }

    @Test
    fun customTemplateRejectsBlockPlaceholderInsideStyle() {
        val template = """
            <!doctype html><html><head><style>
            .x::after{content:"{{narrative_html}}"}
            </style></head><body>
            <h1>{{title}}</h1><p>{{summary}}</p>
            <section>{{timeline_html}}</section><nav>{{extended_reading_html}}</nav>
            </body></html>
        """.trimIndent()

        assertFalse(DeepReadTemplateValidator.validate(template).ok)
    }
}
