package app.amber.feature.ui.components.message

import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.settings.GenerativeUiSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GenerativeWidgetCardTest {
    @Test
    fun fullHtmlAndSlidesRenderWithoutInteractiveChartSwitch() {
        val setting = GenerativeUiSetting(enableInteractiveCharts = false)

        assertTrue(shouldRenderRichWidgetRenderer("slides", setting))
        assertTrue(shouldRenderRichWidgetRenderer(GuizangHtmlDeckValidator.RENDERER, setting))
        assertFalse(shouldRenderRichWidgetRenderer("vchart", setting))
    }

    @Test
    fun inlineWidgetHeightSnapsWhileStreamingOrUnmeasured() {
        val source = repoFile("src/main/java/app/amber/feature/ui/components/message/GenerativeWidgetCard.kt").readText()

        assertTrue(
            source.contains(
                "animationSpec = if (streaming || !hasMeasuredHeight) snap() else tween(durationMillis = 180)"
            )
        )
    }

    @Test
    fun expandedRichSandboxSnapsStreamingAndDoesNotKeyHeightBySpec() {
        val source = repoFile("src/main/java/app/amber/feature/ui/components/message/GenerativeWidgetCard.kt").readText()

        assertTrue(source.contains("streaming: Boolean = false"))
        assertTrue(source.contains("streaming = !widget.complete"))
        assertTrue(source.contains("var heightDp by remember(renderer) { mutableStateOf(minHeightDp) }"))
        assertTrue(source.contains("var hasMeasuredHeight by remember(renderer) { mutableStateOf(false) }"))
        assertTrue(source.contains("var postCompleteSnapPending by remember(renderer) { mutableStateOf(false) }"))
        assertTrue(source.contains("val postCompleteSnapPendingState = rememberUpdatedState(postCompleteSnapPending)"))
        assertTrue(source.contains("LaunchedEffect(maxHeightDp)"))
        assertTrue(source.contains("RICH_SANDBOX_POST_COMPLETE_SNAP_WINDOW_MS"))
        assertTrue(source.contains("delay(RICH_SANDBOX_POST_COMPLETE_SNAP_WINDOW_MS)"))
        assertTrue(source.contains("LaunchedEffect(activeWebView, pageReady, safeJson, fontCss, renderFunction)"))
        assertTrue(source.contains("if (streaming) delay(STREAM_WIDGET_DEBOUNCE_MS)"))
        assertTrue(source.contains("target.renderRichSandboxSpec("))
        assertTrue(source.contains("key(renderer)"))
        assertTrue(source.contains("withFrameNanos { }"))
        assertTrue(source.contains("if (streaming || !hasMeasuredHeight || postCompleteSnapPending)"))
        assertTrue(source.contains("postCompleteSnapPendingState.value && !streamingState.value"))
        assertTrue(source.contains("heightDp = height.coerceIn(minHeightDp, maxHeightState.value)"))
        assertFalse(source.contains("var heightDp by remember(maxHeightDp)"))
        assertFalse(source.contains("var heightDp by remember(specJson"))
    }

    private fun repoFile(pathInAppModule: String): File {
        return listOf(
            File(pathInAppModule),
            File("app/$pathInAppModule"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot locate $pathInAppModule")
    }
}
