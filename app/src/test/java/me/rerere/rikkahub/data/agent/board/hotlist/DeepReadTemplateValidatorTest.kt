package me.rerere.rikkahub.data.agent.board.hotlist

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateValidator
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
}
