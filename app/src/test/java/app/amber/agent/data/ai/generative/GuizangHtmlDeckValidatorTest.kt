package app.amber.core.ai.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuizangHtmlDeckValidatorTest {
    @Test
    fun acceptsGuizangStyleADeckShape() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
              <canvas id="bg-dark" class="bg"></canvas>
              <div id="deck">
                <section class="slide dark" data-animate="hero">
                  <h1 data-anim="title">Hero</h1>
                </section>
              </div>
              <script src="https://unpkg.com/lucide@latest/dist/umd/lucide.min.js"></script>
              <script type="module">
                let motion = await import('./assets/motion.min.js');
                window.__pipeAdvance = function(){ return false; };
              </script>
            </body>
            </html>
        """.trimIndent()

        assertTrue(GuizangHtmlDeckValidator.validateHtml(html).valid)
        assertTrue(GuizangHtmlDeckValidator.prepareRuntimeHtml(html).contains(GuizangHtmlDeckValidator.LOCAL_LUCIDE_URL))
    }

    @Test
    fun acceptsGuizangStyleBDeckShape() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
              <canvas class="ascii-bg" aria-hidden="true"></canvas>
              <div id="deck">
                <section class="slide accent" data-animate="hero">
                  <div class="canvas-card">Swiss grid</div>
                </section>
              </div>
              <script src="https://unpkg.com/lucide@0.468.0/dist/umd/lucide.min.js"></script>
              <script type="module">
                try {
                  await import('https://cdn.jsdelivr.net/npm/motion@11.11.17/+esm');
                } catch(e) {}
                window.__setLowPowerMode = function(){};
              </script>
            </body>
            </html>
        """.trimIndent()

        assertTrue(GuizangHtmlDeckValidator.validateHtml(html).valid)
    }

    @Test
    fun rejectsBlockedSchemesAndNativeBridgeWords() {
        listOf(
            """<img src="file:///sdcard/a.png">""",
            """<img src="content://media/a.png">""",
            """<a href="intent://scan">x</a>""",
            """<img src="android_asset://secret">""",
            """<script>window.addJavascriptInterface</script>""",
        ).forEach { bad ->
            val html = """<section class="slide">$bad</section>"""

            assertFalse("expected rejection for $bad", GuizangHtmlDeckValidator.validateHtml(html).valid)
        }
    }

    @Test
    fun rejectsOversizedHtmlAndUnknownRemoteScripts() {
        val large = """<section class="slide">""" + "x".repeat(GuizangHtmlDeckValidator.MAX_HTML_BYTES) + "</section>"
        assertFalse(GuizangHtmlDeckValidator.validateHtml(large).valid)

        val remoteScript = """
            <section class="slide">A</section>
            <script src="https://example.com/deck-runtime.js"></script>
        """.trimIndent()
        assertFalse(GuizangHtmlDeckValidator.validateHtml(remoteScript).valid)

        val unquotedRemoteScript = """
            <section class="slide">A</section>
            <script src=https://example.com/deck-runtime.js></script>
        """.trimIndent()
        assertFalse(GuizangHtmlDeckValidator.validateHtml(unquotedRemoteScript).valid)

        val suffixSpoofedRuntime = """
            <section class="slide">A</section>
            <script src="https://example.com/assets/lucide.min.js"></script>
        """.trimIndent()
        assertFalse(GuizangHtmlDeckValidator.validateHtml(suffixSpoofedRuntime).valid)
    }

    @Test
    fun rewritesMotionAndLucideRuntimeUrls() {
        val html = """
            <section class="slide">A</section>
            <script src="https://unpkg.com/lucide@latest/dist/umd/lucide.min.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/lucide@0.468.0/dist/umd/lucide.min.js"></script>
            <script type="module">
              await import('./assets/motion.min.js');
              await import('https://cdn.jsdelivr.net/npm/motion@11.11.17/+esm');
            </script>
        """.trimIndent()

        val rewritten = GuizangHtmlDeckValidator.rewriteRuntimeUrls(html)
        assertTrue(rewritten.contains(GuizangHtmlDeckValidator.LOCAL_LUCIDE_URL))
        assertTrue(rewritten.contains(GuizangHtmlDeckValidator.LOCAL_MOTION_URL))
        assertEquals(
            GuizangHtmlDeckValidator.RuntimeAsset.MOTION,
            GuizangHtmlDeckValidator.runtimeAssetForUrl(GuizangHtmlDeckValidator.LOCAL_MOTION_URL),
        )
        assertEquals(
            GuizangHtmlDeckValidator.RuntimeAsset.LUCIDE,
            GuizangHtmlDeckValidator.runtimeAssetForUrl("https://unpkg.com/lucide@latest"),
        )

        val archiveHtml = GuizangHtmlDeckValidator.rewriteRuntimeUrlsForArchive(html)
        assertTrue(archiveHtml.contains("assets/motion.min.js"))
        assertTrue(archiveHtml.contains("assets/lucide.min.js"))
    }

    @Test
    fun normalizesCommonDeckShapeMistakesBeforeRuntime() {
        val orphanSlides = """
            <!DOCTYPE html>
            <html><body>
              <section class=slide data-animate="hero">One</section>
              <section class="slide dark">Two</section>
            </body></html>
        """.trimIndent()

        assertTrue(GuizangHtmlDeckValidator.validateHtml(orphanSlides).valid)
        val normalized = GuizangHtmlDeckValidator.prepareRuntimeHtml(orphanSlides)
        assertTrue(normalized.contains("""<div id="deck">"""))
        assertTrue(normalized, normalized.contains("""<section class=slide"""))

        val deckClass = """<main class="deck"><section class="slide">One</section></main>"""
        assertTrue(GuizangHtmlDeckValidator.prepareRuntimeHtml(deckClass).contains("id=\"deck\""))

        val revealStyle = """<div class="slides"><section>One</section><section>Two</section></div>"""
        assertTrue(GuizangHtmlDeckValidator.validateHtml(revealStyle).valid)
        val normalizedReveal = GuizangHtmlDeckValidator.prepareRuntimeHtml(revealStyle)
        assertTrue(normalizedReveal.contains("id=\"deck\""))
        assertTrue(normalizedReveal.contains("""<section class="slide">One</section>"""))
        assertTrue(normalizedReveal.contains("""<section class="slide">Two</section>"""))
    }

    @Test
    fun repairsDeckWithPlainSectionsIntoRuntimeVisibleSlides() {
        val html = """<div id="deck"><section>One</section><article class="dark">Two</article></div>"""

        assertTrue(GuizangHtmlDeckValidator.validateHtml(html).valid)
        val normalized = GuizangHtmlDeckValidator.prepareRuntimeHtml(html)

        assertTrue(normalized.contains("""<section class="slide">One</section>"""))
        assertTrue(normalized.contains("""<article class="dark slide">Two</article>"""))
    }

    @Test
    fun onlyRepairsDirectDeckChildrenAsSlides() {
        val html = """
            <div id="deck">
              <section>
                <section class="caption">Nested</section>
              </section>
            </div>
        """.trimIndent()

        assertTrue(GuizangHtmlDeckValidator.validateHtml(html).valid)
        val normalized = GuizangHtmlDeckValidator.prepareRuntimeHtml(html)

        assertTrue(normalized.contains("""<section class="slide">"""))
        assertTrue(normalized.contains("""<section class="caption">Nested</section>"""))
        assertFalse(normalized.contains("class=\"caption slide\""))
    }

    @Test
    fun repairsSocialCardSetWithoutRenamingItsCssAnchor() {
        val html = """
            <div id="card-set">
              <section class="poster xhs">Card</section>
            </div>
        """.trimIndent()

        assertTrue(GuizangHtmlDeckValidator.validateHtml(html).valid)
        val normalized = GuizangHtmlDeckValidator.prepareRuntimeHtml(html)

        assertTrue(normalized.contains("""<div id="card-set" data-guizang-deck>"""))
        assertTrue(normalized.contains("""<section class="poster xhs slide social-card">Card</section>"""))
    }

    @Test
    fun onlyRepairsDirectSocialCardChildrenAsSlides() {
        val html = """
            <div id="card-set">
              <section class="poster xhs">
                <article class="caption">Nested</article>
              </section>
            </div>
        """.trimIndent()

        assertTrue(GuizangHtmlDeckValidator.validateHtml(html).valid)
        val normalized = GuizangHtmlDeckValidator.prepareRuntimeHtml(html)

        assertTrue(normalized.contains("""<section class="poster xhs slide social-card">"""))
        assertTrue(normalized.contains("""<article class="caption">Nested</article>"""))
        assertFalse(normalized.contains("class=\"caption slide social-card\""))
    }

    @Test
    fun wrapsMixedOrphanSlidesAndSocialCardsTogether() {
        val html = """
            <section class="slide">Intro</section>
            <section class="poster xhs">Card</section>
        """.trimIndent()

        assertTrue(GuizangHtmlDeckValidator.validateHtml(html).valid)
        val normalized = GuizangHtmlDeckValidator.prepareRuntimeHtml(html)

        assertTrue(normalized.contains("""<div id="deck">"""))
        assertTrue(normalized.contains("""<section class="slide">Intro</section>"""))
        assertTrue(normalized.contains("""<section class="poster xhs slide social-card">Card</section>"""))
    }

    @Test
    fun doesNotPromotePlainArticleHtmlToDeck() {
        val html = """<main><section>Article</section></main>"""

        assertFalse(GuizangHtmlDeckValidator.validateHtml(html).valid)
    }

    @Test
    fun keepsSecurityBlocksAfterRepairingDeckShape() {
        val unsafeFrame = """<div id="card-set"><section class="poster xhs"><iframe src="x"></iframe></section></div>"""
        val unsafeFetch = """<div id="deck"><section>One<script>fetch('/x')</script></section></div>"""
        val unsafeScript = """
            <div class="slides"><section>One</section></div>
            <script src="https://example.com/deck-runtime.js"></script>
        """.trimIndent()

        assertFalse(GuizangHtmlDeckValidator.validateHtml(unsafeFrame).valid)
        assertFalse(GuizangHtmlDeckValidator.validateHtml(unsafeFetch).valid)
        assertFalse(GuizangHtmlDeckValidator.validateHtml(unsafeScript).valid)
    }
}
