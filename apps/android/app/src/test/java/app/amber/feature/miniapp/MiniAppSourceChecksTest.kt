package app.amber.feature.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-05 tests (plan §P3-05 测试 "编辑→校验"): basic HTML tag pairing and
 * JS/CSS brace balance on top of the existing security validator; the
 * 未保存状态 helper.
 */
class MiniAppSourceChecksTest {

    private val balancedHtml = """
        <!DOCTYPE html>
        <html>
          <head><style>body { color: red; }</style></head>
          <body>
            <div id="app"><span>hi</span></div>
            <img src="data:image/png;base64,AAAA">
            <script>
              function add(a, b) { return a + b; }
              const items = [1, 2, 3].map(function (x) { return x * 2; });
            </script>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun balancedHtmlPassesAllChecks() {
        assertTrue(MiniAppSourceChecks.isSavable(balancedHtml))
        assertEquals(emptyList<MiniAppSourceChecks.Issue>(), MiniAppSourceChecks.issues(balancedHtml))
    }

    @Test
    fun unbalancedClosingTagIsReported() {
        val html = "<html><body><div>text</span></body></html>"
        val issues = MiniAppSourceChecks.issues(html)
        assertTrue(issues.any { it.message.contains("标签不配对") })
        assertFalse(MiniAppSourceChecks.isSavable(html))
    }

    @Test
    fun unclosedTagIsReported() {
        val html = "<html><body><div>text</div><section>no close</section></body>"
        val issues = MiniAppSourceChecks.issues(html)
        assertTrue(issues.any { it.message.contains("未闭合的标签") })
    }

    @Test
    fun jsStringContainingHtmlTagIsNotFlagged() {
        // `<div>` inside a JS string is script content, not an HTML tag —
        // it must not be reported as an unclosed tag.
        val html = """
            <html><body>
              <script>
                const tag = "<div>";
                document.title = tag;
              </script>
            </body></html>
        """.trimIndent()
        assertEquals(emptyList<MiniAppSourceChecks.Issue>(), MiniAppSourceChecks.issues(html))
        assertTrue(MiniAppSourceChecks.isSavable(html))
    }

    @Test
    fun unbalancedScriptBracesAreReported() {
        val html = "<html><body><script>function f() { return 1; </script></body></html>"
        val issues = MiniAppSourceChecks.issues(html)
        assertTrue(issues.any { it.message.contains("未闭合的花括号") })
    }

    @Test
    fun unbalancedStyleBracesAreReported() {
        val html = "<html><head><style>body { color: red; </style></head></html>"
        val issues = MiniAppSourceChecks.issues(html)
        assertTrue(issues.any { it.message.contains("花括号") })
    }

    @Test
    fun securityRulesStillEnforced() {
        // The structural checks layer on top of the existing validator.
        val html = "<html><body><script>eval('x')</script></body></html>"
        val issues = MiniAppSourceChecks.issues(html)
        assertTrue(issues.any { it.message.contains("eval") })
    }

    @Test
    fun unsavedIndicatorHelper() {
        assertFalse(MiniAppSourceChecks.hasUnsavedChanges("a", "a"))
        assertTrue(MiniAppSourceChecks.hasUnsavedChanges("a", "b"))
    }
}
