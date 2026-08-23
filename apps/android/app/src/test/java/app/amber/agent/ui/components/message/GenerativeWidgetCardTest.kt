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
}
