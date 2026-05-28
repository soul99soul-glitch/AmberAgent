package app.amber.core.ai.generative

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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
        assertTrue(prompt.contains("final artifact must be a show-widget deck preview"))
        assertTrue(prompt.contains("Do NOT turn the deck into a MiniApp"))
        assertTrue(prompt.contains("use renderer \"guizang_html\" by default"))
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

        assertTrue(prompt.contains("final artifact must be a show-widget deck preview"))
        assertTrue(prompt.contains("Do NOT turn the deck into a MiniApp"))
        assertTrue(prompt.contains("use renderer \"guizang_html\" by default"))
        assertTrue(requirement.required)
        assertTrue(requirement.expectSlides)
        assertTrue(requirement.expectGuizangHtml)
    }

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
