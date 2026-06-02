package app.amber.core.ai.generative

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.GenerativeUiSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeUiPlannerTest {
    @Test
    fun needsVisibleStreamingFallbackOnlyForVisualRequests() {
        assertTrue(
            GenerativeUiPlanner.needsVisibleStreamingFallback(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("画一个流程图解释这件事")),
            )
        )
        assertFalse(
            GenerativeUiPlanner.needsVisibleStreamingFallback(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("一句话解释")),
            )
        )
        assertFalse(
            GenerativeUiPlanner.needsVisibleStreamingFallback(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("@Designer 用 guizang 的 skill 做一份瑞士国际主义风的 PPT")),
            )
        )
        assertFalse(
            GenerativeUiPlanner.needsVisibleStreamingFallback(
                setting = GenerativeUiSetting(enabled = false),
                messages = listOf(userMessage("画一个流程图")),
            )
        )
    }

    @Test
    fun directDrawingRequestsDisableToolDetours() {
        assertTrue(
            GenerativeUiPlanner.shouldGenerateDirectWidgetWithoutTools(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("画一下唐代的三省六部制")),
            )
        )
        assertFalse(
            GenerativeUiPlanner.shouldGenerateDirectWidgetWithoutTools(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("搜索资料后画一个流程图")),
            )
        )
        assertFalse(
            GenerativeUiPlanner.shouldGenerateDirectWidgetWithoutTools(
                setting = GenerativeUiSetting(enabled = true),
                messages = listOf(userMessage("@designer 调用 skill 画一个 PPT")),
            )
        )
    }

    @Test
    fun toolMediatedVisualRequestsWarnAgainstProgressWidgets() {
        val prompt = GenerativeUiPlanner.buildPrompt(
            setting = GenerativeUiSetting(enabled = true),
            messages = listOf(userMessage("@Designer 用 guizang 的 skill 做一份瑞士国际主义风的 PPT")),
        )

        assertTrue(prompt.contains("Do NOT create a widget for routing, progress, plan, or status summaries."))
        assertTrue(prompt.contains("final artifact must be one full_html show-widget deck preview"))
        assertTrue(prompt.contains("Do NOT turn the deck into a MiniApp"))
        assertTrue(prompt.contains("renderer \"full_html\""))
    }

    @Test
    fun guizangRequestsRouteToDeckEvenWithoutPptKeyword() {
        val prompt = GenerativeUiPlanner.buildPrompt(
            setting = GenerativeUiSetting(enabled = true),
            messages = listOf(userMessage("用 guizang skill 做一个演示")),
        )
        val requirement = GenerativeUiPlanner.widgetRequirement(
            setting = GenerativeUiSetting(enabled = true),
            messages = listOf(userMessage("用 guizang skill 做一个演示")),
        )

        assertTrue(prompt.contains("renderer \"full_html\""))
        assertTrue(prompt.contains("Do NOT turn the deck into a MiniApp"))
        assertTrue(requirement.required)
        assertTrue(requirement.expectSlides)
        assertTrue(requirement.expectFullHtmlDeck)
    }

    @Test
    fun slidesRouteDoesNotDependOnStructuredOrChartSwitches() {
        val setting = GenerativeUiSetting(
            enabled = true,
            enableStructuredRenderers = false,
            enableInteractiveCharts = false,
        )
        val messages = listOf(userMessage("做一份 5 页 PPT"))

        val prompt = GenerativeUiPlanner.buildPrompt(setting = setting, messages = messages)
        val requirement = GenerativeUiPlanner.widgetRequirement(setting = setting, messages = messages)

        assertTrue(prompt.contains("renderer \"full_html\""))
        assertTrue(prompt.contains("""<div id="deck">"""))
        assertTrue(requirement.required)
        assertTrue(requirement.expectSlides)
        assertTrue(requirement.expectFullHtmlDeck)
    }

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
