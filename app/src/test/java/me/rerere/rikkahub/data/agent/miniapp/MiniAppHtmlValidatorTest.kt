package me.rerere.rikkahub.data.agent.miniapp

import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAppHtmlValidatorTest {
    @Test
    fun allowsOfflineSingleFileHtml() {
        MiniAppHtmlValidator.validate(
            """
            <!DOCTYPE html>
            <html>
              <head><style>body { font-family: sans-serif; }</style></head>
              <body><button onclick="Amber.toast('ok')">OK</button></body>
            </html>
            """.trimIndent()
        )
        MiniAppHtmlValidator.validate(
            """<!DOCTYPE html><html><body><svg viewBox="0 0 10 10"><rect width="10" height="10"/></svg><img src="data:image/png;base64,iVBORw0KGgo="></body></html>"""
        )
        MiniAppHtmlValidator.validate(
            """<!DOCTYPE html><html><body><img src="https://example.com/a.png"><script>Amber.fetch({url:'https://example.com/api'}); Amber.search({query:'AI', limit:3});</script></body></html>"""
        )
    }

    @Test
    fun rejectsExternalAndDangerousApis() {
        listOf(
            """<!DOCTYPE html><html><script src="https://example.com/a.js"></script></html>""",
            """<!DOCTYPE html><html><script src = 'https://example.com/a.js'></script></html>""",
            """<!DOCTYPE html><html><img src="http://example.com/a.png"></html>""",
            """<!DOCTYPE html><html><img src="/images/a.png"></html>""",
            """<!DOCTYPE html><html><img src="file:///sdcard/a.png"></html>""",
            """<!DOCTYPE html><html><source srcset="https://example.com/a.png 1x, http://example.com/a.png 2x"></html>""",
            """<!DOCTYPE html><html><style>@import "https://example.com/a.css";</style></html>""",
            """<!DOCTYPE html><html><iframe srcdoc="x"></iframe></html>""",
            """<!DOCTYPE html><html><script>fetch('/x')</script></html>""",
            """<!DOCTYPE html><html><script>window['fetch']('/x')</script></html>""",
            """<!DOCTYPE html><html><script>XMLHttpRequest</script></html>""",
            """<!DOCTYPE html><html><script>localStorage.setItem('a','b')</script></html>""",
        ).forEach { html ->
            val failed = runCatching { MiniAppHtmlValidator.validate(html) }.isFailure
            assertTrue("Expected validation failure for $html", failed)
        }
    }

    @Test
    fun rejectsOversizedHtml() {
        val html = "<!DOCTYPE html><html>${"x".repeat(512 * 1024)}</html>"
        assertTrue(runCatching { MiniAppHtmlValidator.validate(html) }.isFailure)
    }
}
