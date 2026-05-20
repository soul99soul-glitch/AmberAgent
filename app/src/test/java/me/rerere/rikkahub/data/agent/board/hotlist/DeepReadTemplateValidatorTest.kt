package me.rerere.rikkahub.data.agent.board.hotlist

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadImageAsset
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.TimelineEvent
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateRenderer
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
    }

    @Test
    fun rendererAllowsOnlyVerifiedImageAssets() {
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
}
