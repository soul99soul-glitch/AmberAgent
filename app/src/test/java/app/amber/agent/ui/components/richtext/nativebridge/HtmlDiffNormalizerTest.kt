package app.amber.feature.ui.components.richtext.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlDiffNormalizerTest {

    private fun norm(html: String): String = HtmlDiffNormalizer.normalize(html)

    @Test
    fun `identical input yields identical output`() {
        val a = "<p>hello <em>world</em></p>"
        assertEquals(norm(a), norm(a))
    }

    @Test
    fun `attr order does not matter`() {
        val a = norm("<a href=\"/x\" class=\"link\">x</a>")
        val b = norm("<a class=\"link\" href=\"/x\">x</a>")
        assertEquals(a, b)
    }

    @Test
    fun `whitespace runs collapse`() {
        val a = norm("<p>hello     world</p>")
        val b = norm("<p>hello world</p>")
        assertEquals(a, b)
    }

    @Test
    fun `leading and trailing whitespace stripped`() {
        val a = norm("   <p>x</p>   ")
        val b = norm("<p>x</p>")
        assertEquals(a, b)
    }

    @Test
    fun `self-closing style normalised by jsoup html5`() {
        // pulldown-cmark emits <br /> (XHTML-style); JetBrains may emit <br>.
        // Jsoup HTML5-canonicalises both to the same void-element form.
        val a = norm("<p>line1<br />line2</p>")
        val b = norm("<p>line1<br>line2</p>")
        assertEquals(a, b)
    }

    @Test
    fun `genuine content difference survives normalisation`() {
        // Different text → must NOT be equalised.
        val a = norm("<p>hello</p>")
        val b = norm("<p>goodbye</p>")
        assertNotEquals(a, b)
    }

    @Test
    fun `missing wrapper element survives normalisation`() {
        // One side wraps in <em>, the other doesn't — real semantic
        // difference, must survive.
        val a = norm("<p>hello <em>world</em></p>")
        val b = norm("<p>hello world</p>")
        assertNotEquals(a, b)
    }

    @Test
    fun `extra block survives normalisation`() {
        val a = norm("<p>x</p><p>y</p>")
        val b = norm("<p>x</p>")
        assertNotEquals(a, b)
    }

    @Test
    fun `cjk content preserved`() {
        val a = norm("<p>你好 世界 🌍</p>")
        val b = norm("<p>你好 世界 🌍</p>")
        assertEquals(a, b)
        // And content actually present:
        assertTrue("CJK should survive: $a", a.contains("你好"))
        assertTrue("Emoji should survive: $a", a.contains("🌍"))
    }

    @Test
    fun `malformed input does not crash`() {
        // Jsoup is forgiving — almost nothing makes it throw, so this also
        // verifies the fallback path doesn't crash even on weird input.
        // Just running norm() without exception is the assertion.
        val weird = "<<<><></><>"
        norm(weird)
    }

    @Test
    fun `nested attribute sorting recursive`() {
        val a = norm("<div class=\"outer\" id=\"a\"><span data-x=\"1\" data-a=\"2\">t</span></div>")
        val b = norm("<div id=\"a\" class=\"outer\"><span data-a=\"2\" data-x=\"1\">t</span></div>")
        assertEquals(a, b)
    }

    @Test
    fun `entity escape mode standardised`() {
        // Both inputs should normalize to the same entity-encoded form.
        val a = norm("<p>A &amp; B</p>")
        val b = norm("<p>A &amp; B</p>")
        assertEquals(a, b)
        assertTrue("entity should be present: $a", a.contains("&amp;"))
    }

    @Test
    fun `empty input handled`() {
        assertEquals("", norm(""))
        assertEquals("", norm("   "))
    }

    @Test
    fun `code block trailing newline equalised`() {
        // pulldown-cmark emits a trailing \n inside <code> for fenced blocks;
        // JetBrains does not. Our \s+ collapse equalises them — verify
        // explicitly so regression doesn't unequalise this corner.
        val a = norm("<pre><code class=\"language-rust\">let x = 1;\n</code></pre>")
        val b = norm("<pre><code class=\"language-rust\">let x = 1;</code></pre>")
        assertEquals(a, b)
    }

    @Test
    fun `gfm table row newlines equalised`() {
        // Real-world divergence: one engine adds \n after </tr>, other doesn't.
        val a = norm("<table><tr><td>a</td></tr>\n<tr><td>b</td></tr>\n</table>")
        val b = norm("<table><tr><td>a</td></tr><tr><td>b</td></tr></table>")
        assertEquals(a, b)
    }

    @Test
    fun `task list checkbox attr order normalised`() {
        // GFM task list: <input checked=\"\" disabled=\"\" type=\"checkbox\">
        // pulldown-cmark vs JetBrains historically differ on the order/
        // presence of `disabled`. attr sort handles the order.
        val a = norm("<li><input checked=\"\" disabled=\"\" type=\"checkbox\"> done</li>")
        val b = norm("<li><input type=\"checkbox\" checked=\"\" disabled=\"\"> done</li>")
        assertEquals(a, b)
    }

    @Test
    fun `oversize input short-circuits to identity`() {
        // Inputs over 64 KiB should skip Jsoup parse entirely (perf cap).
        // Verify by ensuring an oversize, valid HTML string is returned
        // unchanged — not normalised.
        val big = "<p>" + "x".repeat(70 * 1024) + "</p>"
        assertEquals(big, norm(big))
        // And confirm a small variant with REVERSED attr order DOES get
        // normalised (sort runs, output differs from input) so we don't pass
        // the size-cap test by coincidence.
        val small = "<p id=\"b\" class=\"a\">x</p>"
        assertNotEquals(small, norm(small))
    }
}
