package app.amber.core.ai.generative

import app.amber.core.settings.GenerativeUiSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                repeat(610) { append("<span>x</span>") }
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

    @Test
    fun keepsDeclarativeSvgAnimation() {
        val result = GenerativeWidgetSanitizer.sanitize(
            """
            <svg viewBox="0 0 680 340" xmlns="http://www.w3.org/2000/svg">
              <style>
                .wheel { animation: spin 2s linear infinite; transform-origin: center; transform-box: fill-box; }
                @keyframes spin { to { transform: rotate(360deg); } }
              </style>
              <defs>
                <linearGradient id="sky"><stop offset="0" stop-color="#bae6fd"/></linearGradient>
                <path id="road" d="M0 300 H680"/>
              </defs>
              <rect width="680" height="340" fill="url(#sky)"/>
              <g class="wheel">
                <circle cx="140" cy="260" r="40" fill="none" stroke="#111827" stroke-width="6"/>
                <animateTransform attributeName="transform" type="rotate" from="0 140 260" to="360 140 260" dur="2s" repeatCount="indefinite"/>
              </g>
              <circle r="6" fill="#dc2626">
                <animateMotion dur="4s" repeatCount="indefinite"><mpath xlink:href="#road"/></animateMotion>
              </circle>
              <rect x="300" y="200" width="60" height="20" fill="#16a34a">
                <animate attributeName="opacity" values="1;0.2;1" dur="3s" repeatCount="indefinite"/>
              </rect>
              <set attributeName="display" to="none" begin="10s"/>
            </svg>
            """.trimIndent(),
            GenerativeUiSetting(),
        )

        assertEquals(GenerativeWidgetSanitizeStatus.READY, result.status)
        listOf(
            "<animateTransform", "repeatCount=\"indefinite\"", "<animateMotion",
            "<mpath", "xlink:href=\"#road\"", "<animate ", "<set ", "@keyframes",
            "animation:", "url(#sky)", "transform-box",
        ).forEach { needle ->
            assertTrue("sanitized output lost: $needle", result.html.contains(needle))
        }
    }

    @Test
    fun stripsJsDrivenAnimationHooks() {
        val result = GenerativeWidgetSanitizer.sanitize(
            """
            <svg viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg">
              <circle id="c" cx="50" cy="50" r="10" onbegin="alert(1)">
                <animate attributeName="r" from="10" to="20" dur="1s" begin="indefinite"/>
              </circle>
              <script>c.beginElement()</script>
            </svg>
            """.trimIndent(),
            GenerativeUiSetting(),
        )

        assertEquals(GenerativeWidgetSanitizeStatus.READY, result.status)
        assertFalse(result.html.contains("script", ignoreCase = true))
        assertFalse(result.html.contains("onbegin", ignoreCase = true))
        // The declarative <animate> itself survives even though its indefinite
        // begin can never fire — harmless, and stripping it would be over-reach.
        assertTrue(result.html.contains("<animate"))
    }

    @Test
    fun dropsAnimateElementsTargetingRuntimeDangerousAttributes() {
        val result = GenerativeWidgetSanitizer.sanitize(
            """
            <svg viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg">
              <set attributeName="onclick" to="alert(1)"/>
              <animate attributeName="href" to="javascript:alert(1)"/>
              <animate attributeName="src" to="x" begin="0s"/>
              <a><set attributeName="onmouseover" to="alert(2)"/>x</a>
              <circle cx="10" cy="10" r="5">
                <animate attributeName="cx" from="10" to="90" dur="2s" repeatCount="indefinite"/>
                <animateTransform attributeName="transform" type="rotate" from="0" to="360" dur="3s"/>
              </circle>
            </svg>
            """.trimIndent(),
            GenerativeUiSetting(),
        )

        assertEquals(GenerativeWidgetSanitizeStatus.READY, result.status)
        assertFalse("attributeName=onclick leaked", result.html.contains("attributeName=\"onclick\"", ignoreCase = true))
        assertFalse("attributeName=href leaked", result.html.contains("attributeName=\"href\"", ignoreCase = true))
        assertFalse("attributeName=src leaked", result.html.contains("attributeName=\"src\"", ignoreCase = true))
        assertFalse("attributeName=onmouseover leaked", result.html.contains("onmouseover", ignoreCase = true))
        // Paint/geometry animation targets are untouched.
        assertTrue(result.html.contains("attributeName=\"cx\""))
        assertTrue(result.html.contains("attributeName=\"transform\""))
    }

    @Test
    fun dropsDangerousAnimationTargetAfterDecoyAttributes() {
        val attributes = listOf(
            """data-note='attributeName="fill"' attributeName="href"""",
            """data-note="attributeName='fill'" attributeName=href""",
            """data-attributeName="fill" attributeName="href"""",
        )
        attributes.forEach { attrs ->
            val result = GenerativeWidgetSanitizer.sanitize(
                """<svg><a href="#go"><rect width="20" height="20"/><set $attrs to="java&#x73;cript:document.body.dataset.pwn=1" begin="0s"/></a></svg>""",
                GenerativeUiSetting(),
            )

            assertEquals(GenerativeWidgetSanitizeStatus.READY, result.status)
            assertFalse("dangerous animation survived: $attrs", result.html.contains("<set"))
            assertTrue(result.html.contains("<rect"))
        }
    }

    @Test
    fun preservesGeometryAnimationWithDangerousTargetOnlyInAttributeText() {
        val code = """<svg><circle><animate data-note='attributeName="href"' attributeName="cx" from="10" to="20" dur="1s"/></circle></svg>"""
        val result = GenerativeWidgetSanitizer.sanitize(code, GenerativeUiSetting())

        assertEquals(GenerativeWidgetSanitizeStatus.READY, result.status)
        assertEquals(code, result.html)
    }

    @Test
    fun dropsAnimateTargetHiddenBehindQuotedGtAndEntities() {
        val result = GenerativeWidgetSanitizer.sanitize(
            """
            <svg viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg">
              <set data-x="a>b" attributeName="href" to="https://example.com/"/>
              <animate attributeName="&#104;ref" to="javascript:alert(1)"/>
              <circle cx="10" cy="10" r="5">
                <animate attributeName="cx" from="10" to="90" dur="2s"/>
              </circle>
            </svg>
            """.trimIndent(),
            GenerativeUiSetting(),
        )

        assertEquals(GenerativeWidgetSanitizeStatus.READY, result.status)
        assertFalse("href hidden behind quoted > leaked", result.html.contains("attributeName=\"href\"", ignoreCase = true))
        assertFalse("entity-encoded target leaked", result.html.contains("&#104;ref", ignoreCase = true))
        assertTrue(result.html.contains("attributeName=\"cx\""))
    }
}
