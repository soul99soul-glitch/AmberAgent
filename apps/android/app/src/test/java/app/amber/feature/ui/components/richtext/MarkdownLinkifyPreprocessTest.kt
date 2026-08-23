package app.amber.feature.ui.components.richtext

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused pins for the bare-URL linkify step of [preProcess] (exercised through
 * the [preProcessForTest] seam). The regression these guard: the bare-URL scanner
 * used to match a domain-looking token INSIDE an explicit link's destination
 * (`[guides](https://developer.android.com/guide)` → the `android.com/guide` run
 * got wrapped into a nested link, corrupting the href) or inside the link LABEL
 * (`[see example.com](...)`). The fix pre-scans `[label](dest)` spans and skips
 * candidates whose start falls inside one, while still linkifying genuinely bare
 * URLs and leaving URLs in inline code untouched.
 *
 * Robolectric is required because [preProcess] calls `android.util.Log` while
 * probing the (absent on host) native preprocess library; with the `.so` missing
 * the native path returns unavailable and the Kotlin reference path under test
 * runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MarkdownLinkifyPreprocessTest {

    @Test
    fun explicitLinkDestinationStaysIntact() {
        val input = "[Android developer guides](https://developer.android.com/guide) are canonical."
        val out = preProcessForTest(input)
        // The destination must be untouched — no nested `[android.com/guide](...)`.
        assertEquals(input, out)
        assertFalse(
            "destination must not be re-linkified into a nested link",
            out.contains("[android.com/guide]"),
        )
    }

    @Test
    fun multipleExplicitLinksOnOneLineStayIntact() {
        val input =
            "For [Maven Central](https://search.maven.org) and [Google Maven](https://maven.google.com)."
        val out = preProcessForTest(input)
        assertEquals(input, out)
        assertFalse(out.contains("[maven.org]"))
        assertFalse(out.contains("[google.com]"))
    }

    @Test
    fun domainInsideLinkLabelStaysIntact() {
        // GFM does not re-linkify text inside an explicit link — its label included.
        val input = "[see example.com](https://example.com)"
        val out = preProcessForTest(input)
        assertEquals(input, out)
        // No nested link around the label's `example.com`.
        assertFalse(out.contains("[example.com]("))
    }

    @Test
    fun bareUrlStillLinkifies() {
        val out = preProcessForTest("visit example.com today")
        assertEquals("visit [example.com](https://example.com) today", out)
    }

    @Test
    fun bareUrlWithPathStillLinkifies() {
        val out = preProcessForTest("see github.com/user/repo for details")
        assertTrue(out.contains("[github.com/user/repo](https://github.com/user/repo)"))
    }

    @Test
    fun bareUrlAfterExplicitLinkOnSameLineStillLinkifies() {
        // The explicit link is skipped; the trailing bare domain is still linkified.
        val input = "See [the docs](https://example.com/docs) or visit kotlinlang.org for more."
        val out = preProcessForTest(input)
        assertTrue(
            "trailing bare URL must still linkify",
            out.contains("[kotlinlang.org](https://kotlinlang.org)"),
        )
        // The explicit link is preserved verbatim.
        assertTrue(out.contains("[the docs](https://example.com/docs)"))
    }

    @Test
    fun urlInsideInlineCodeIsUntouched() {
        val input = "run `curl example.com` then visit example.com"
        val out = preProcessForTest(input)
        // Inside the inline-code span: untouched.
        assertTrue("inline code preserved", out.contains("`curl example.com`"))
        // Outside: linkified.
        assertTrue(out.contains("visit [example.com](https://example.com)"))
    }

    @Test
    fun balancedParensInDestinationStayIntact() {
        // Balanced parens in a URL (Wikipedia) must not confuse the destination scan
        // or trigger re-linkification. (An ESCAPED-paren destination like `path\(1\)`
        // is converted to `path$1$` by the inline-LaTeX pass — a separate, pre-existing
        // preprocess behavior unrelated to linkify — so it is not asserted here.)
        val balanced = "[Wikipedia](https://en.wikipedia.org/wiki/Kotlin_(programming_language))"
        assertEquals(balanced, preProcessForTest(balanced))
    }

    @Test
    fun linkFollowedByBareDomainPunctuationCaseStaysIntact() {
        // Sample 28's `Visit [example.com](https://example.com).` — both the label
        // and destination domains must remain inside the single explicit link.
        val input = "Visit [example.com](https://example.com). The period is not part of the URL."
        val out = preProcessForTest(input)
        assertEquals(input, out)
        assertFalse(out.contains("[example.com]([example.com]"))
    }
}
