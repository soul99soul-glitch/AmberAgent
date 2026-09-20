package app.amber.feature.ui.components.message

import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.settings.GenerativeUiSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeWidgetCardTest {
    @Test
    fun fullHtmlAndSlidesRenderWithoutInteractiveChartSwitch() {
        val setting = GenerativeUiSetting(enableInteractiveCharts = false)

        assertTrue(shouldRenderRichWidgetRenderer("slides", setting))
        assertTrue(shouldRenderRichWidgetRenderer(GuizangHtmlDeckValidator.RENDERER, setting))
        assertFalse(shouldRenderRichWidgetRenderer("vchart", setting))
    }

    @Test
    fun streamingPreviewStripsSmilButKeepsStaticScene() {
        val partial = """
            <svg viewBox="0 0 680 340">
              <style>.w{animation:spin 2s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}</style>
              <rect width="680" height="340" fill="url(#sky)"/>
              <g class="w"><circle cx="140" cy="260" r="40"/><animateTransform attributeName="transform" type="rotate" from="0 140 260" to="360 140 260" dur="2s" repeatCount="indefinite"/></g>
              <circle r="6"><animateMotion dur="4s" repeatCount="indefinite"><mpath xlink:href="#road"/></animateMotion></circle>
              <rect x="10" y="10" width="8" height="8"><animate attributeName="opacity" values="1;0;1" dur="1s"/></rect>
              <set attributeName="display" to="none" begin="9s"/>
            </svg>
        """.trimIndent()

        val out = partial.stripStreamingAnimations()

        assertFalse(out.contains("<animate"))
        assertFalse(out.contains("animateTransform"))
        assertFalse(out.contains("animateMotion"))
        assertFalse(out.contains("<set "))
        assertFalse(out.contains("<mpath"))
        // Static scene + the animation mute survive untouched.
        assertTrue(out.contains("url(#sky)"))
        assertTrue(out.contains("<circle cx=\"140\""))
        assertTrue(out.contains("animation:none!important"))
    }

    @Test
    fun streamingPreviewMutesCssAnimationWithoutSmil() {
        val partial = "<svg viewBox=\"0 0 10 10\"><style>.a{animation:k 1s infinite}</style><rect class=\"a\" width=\"4\" height=\"4\"/></svg>"

        val out = partial.stripStreamingAnimations()

        assertTrue(out.contains("animation:none!important"))
        assertTrue(out.contains("class=\"a\""))
    }

    @Test
    fun streamingPreviewSelfClosedAnimateDoesNotEatStaticContent() {
        // A self-closed <animate/> followed later by a paired </animate> must not
        // swallow the static siblings in between (void-strip runs before block-strip).
        val partial = "<svg viewBox=\"0 0 10 10\"><animate attributeName=\"opacity\" from=\"0\" to=\"1\" dur=\"1s\"/><rect width=\"3\" height=\"3\"/><animate attributeName=\"x\" from=\"0\" to=\"5\" dur=\"2s\"></animate></svg>"

        val out = partial.stripStreamingAnimations()

        assertTrue(out.contains("<rect"))
        assertFalse(out.contains("<animate"))
    }
}
