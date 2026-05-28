package me.rerere.rikkahub.data.ai.generative

import app.amber.core.settings.GenerativeUiSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GenerativeWidgetSanitizerTest {
    @Test
    fun removesDangerousScriptsEventsAndLinks() {
        val result = GenerativeWidgetSanitizer.sanitize(
            """
            <div onclick="alert(1)" style="position:fixed;background:url(https://example.com/bg.png)">
              <style>@import url("https://example.com/x.css"); @font-face { src: url(https://example.com/f.woff2); }</style>
              <script>alert(1)</script>
              <iframe src="https://example.com"></iframe>
              <img src="https://example.com/chart.png">
              <a href="javascript:alert(1)">open</a>
              <svg onload="alert(1)"></svg>
              <foreignObject><div>bad</div></foreignObject>
              <a href="data:text/html;base64,xxx">bad</a>
            </div>
            """.trimIndent(),
            GenerativeUiSetting(),
        )

        assertEquals(GenerativeWidgetSanitizeStatus.READY, result.status)
        assertFalse(result.html.contains("script", ignoreCase = true))
        assertFalse(result.html.contains("onclick", ignoreCase = true))
        assertFalse(result.html.contains("onload", ignoreCase = true))
        assertFalse(result.html.contains("iframe", ignoreCase = true))
        assertFalse(result.html.contains("javascript:", ignoreCase = true))
        assertFalse(result.html.contains("https://example.com/chart.png", ignoreCase = true))
        assertFalse(result.html.contains("position:fixed", ignoreCase = true))
        assertFalse(result.html.contains("https://example.com/bg.png", ignoreCase = true))
        assertFalse(result.html.contains("@import", ignoreCase = true))
        assertFalse(result.html.contains("@font-face", ignoreCase = true))
        assertFalse(result.html.contains("foreignObject", ignoreCase = true))
        assertFalse(result.html.contains("data:text/html", ignoreCase = true))
    }

    @Test
    fun removesFontFaceBlocksWithoutCrashing() {
        val result = GenerativeWidgetSanitizer.sanitize(
            """
            <style>
              @font-face { font-family: Test; src: url(https://example.com/test.woff2); }
              .title { color: #111; }
            </style>
            <div class="title">Timeline</div>
            """.trimIndent(),
            GenerativeUiSetting(),
        )

        assertEquals(GenerativeWidgetSanitizeStatus.READY, result.status)
        assertFalse(result.html.contains("@font-face", ignoreCase = true))
        assertFalse(result.html.contains("https://example.com/test.woff2", ignoreCase = true))
    }

    @Test
    fun rejectsOversizedWidget() {
        val result = GenerativeWidgetSanitizer.sanitize(
            code = "x".repeat(12_001),
            setting = GenerativeUiSetting(maxWidgetCodeChars = 12_000),
        )

        assertEquals(GenerativeWidgetSanitizeStatus.TOO_LARGE, result.status)
    }

    @Test
    fun rejectsTooManyTagsAfterSanitizing() {
        val result = GenerativeWidgetSanitizer.sanitize(
            code = buildString {
                repeat(430) { append("<span>x</span>") }
            },
            setting = GenerativeUiSetting(maxWidgetCodeChars = 20_000),
        )

        assertEquals(GenerativeWidgetSanitizeStatus.TOO_LARGE, result.status)
    }

    @Test
    fun rejectsSvgDataImagesSrcsetAndVendorSticky() {
        val result = GenerativeWidgetSanitizer.sanitize(
            """
            <div style="position:-webkit-sticky;background:url(data:image/svg+xml,<svg></svg>)">
              <img src="data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=" srcset="https://example.com/a.png 1x">
            </div>
            """.trimIndent(),
            GenerativeUiSetting(),
        )

        assertEquals(GenerativeWidgetSanitizeStatus.UNSAFE, result.status)
        assertEquals("svg data image", result.reason)
    }

    @Test
    fun allowsRasterBase64DataImages() {
        val result = GenerativeWidgetSanitizer.sanitize(
            """<img src="data:image/png;base64,iVBORw0KGgo=">""",
            GenerativeUiSetting(),
        )

        assertEquals(GenerativeWidgetSanitizeStatus.READY, result.status)
    }
}
