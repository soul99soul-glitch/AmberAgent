package app.amber.feature.miniapp

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
        MiniAppHtmlValidator.validate(
            """<!DOCTYPE html><html><body><script>Amber.ai.generate({prompt:'hi'}); Amber.host.getConversationContext({mode:'summary'}); Amber.clipboard.read(); Amber.location.getCurrent({accuracy:'coarse'});</script></body></html>"""
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
            """<!DOCTYPE html><html><script>EventSource</script></html>""",
            """<!DOCTYPE html><html><script>localStorage.setItem('a','b')</script></html>""",
            """<!DOCTYPE html><html><script>indexedDB.open('x')</script></html>""",
            """<!DOCTYPE html><html><script>navigator.mediaDevices.getUserMedia({audio:true})</script></html>""",
            """<!DOCTYPE html><html><script>navigator['geolocation'].getCurrentPosition(()=>{})</script></html>""",
            """<!DOCTYPE html><html><script>navigator['mediaDevices'].getUserMedia({audio:true})</script></html>""",
            """<!DOCTYPE html><html><script>navigator.clipboard.readText()</script></html>""",
            """<!DOCTYPE html><html><script>globalThis['fetch']('/x')</script></html>""",
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
