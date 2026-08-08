package app.amber.core.ai.generative

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.GenerativeUiSetting
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerativeUiPlannerTest {
    @Test
    fun diagramRouteRequiresFinalWidgetAndInjectsPrompt() {
        val messages = listOf(userMessage("画一个流程图解释这件事"))
        val setting = GenerativeUiSetting(enabled = true)

        assertTrue(GenerativeUiPlanner.widgetRequirement(setting, messages).required)
        assertTrue(GenerativeUiPlanner.buildPrompt(setting, messages).contains("show-widget SVG"))
    }

    @Test
    fun externalContextStillRequiresFinalWidget() {
        val messages = listOf(userMessage("搜索资料后画一个流程图"))
        val setting = GenerativeUiSetting(enabled = true)

        assertTrue(GenerativeUiPlanner.widgetRequirement(setting, messages).required)
    }

    @Test
    fun diagramSubjectWordsDoNotPretendToBeToolDelegation() {
        val messages = listOf(userMessage("画一个 AI agent 的工具调用流程图"))
        val setting = GenerativeUiSetting(enabled = true)

        assertTrue(GenerativeUiPlanner.widgetRequirement(setting, messages).required)
    }

    @Test
    fun explicitlyNegatedDiagramStaysProse() {
        val setting = GenerativeUiSetting(enabled = true)
        for (text in listOf("不要画流程图，只用文字解释", "plain text only, no diagram")) {
            val messages = listOf(userMessage(text))

            assertFalse(GenerativeUiPlanner.widgetRequirement(setting, messages).required)
        }
    }

    @Test
    fun negatedDiagramDoesNotHidePositiveImageAlternative() {
        val messages = listOf(userMessage("不要流程图，改生成一张图片"))
        val setting = GenerativeUiSetting(enabled = true)

        assertFalse(GenerativeUiPlanner.widgetRequirement(setting, messages).required)
        val prompt = GenerativeUiPlanner.buildPrompt(
            setting = setting,
            messages = messages,
            hasImageGenTool = true,
        )
        assertTrue(prompt.contains("generate_image"))
        assertFalse(prompt.contains("Answer in normal Markdown"))
    }

    @Test
    fun imageRouteKeepsImageTool() {
        val messages = listOf(userMessage("[ROUTE:image]\n画一只猫"))
        val prompt = GenerativeUiPlanner.buildPrompt(
            setting = GenerativeUiSetting(enabled = true),
            messages = messages,
            hasImageGenTool = true,
        )

        assertTrue(prompt.contains("Call the `generate_image` tool"))
    }

    @Test
    fun slidesRequireFullHtmlDeck() {
        val messages = listOf(userMessage("做一份 5 页 PPT"))
        val requirement = GenerativeUiPlanner.widgetRequirement(GenerativeUiSetting(), messages)

        assertTrue(requirement.required)
        assertTrue(requirement.expectSlides)
        assertTrue(requirement.expectFullHtmlDeck)
        assertTrue(GenerativeUiPlanner.buildPrompt(GenerativeUiSetting(), messages).contains("renderer \"full_html\""))
    }

    @Test
    fun routeMetadataCanBeHiddenFromTimeline() {
        val text = GenerativeUiPlanner.stripVisualRouteTagsForDisplay("[ROUTE:svg]\n画一只猫")

        assertFalse(text.contains("[ROUTE:"))
        assertTrue(text.contains("画一只猫"))
    }

    @Test
    fun promptCatalogIsDisabledWithSettingAndAddsModelGuidance() {
        assertTrue(GenerativeUiPromptCatalog.build(GenerativeUiSetting(enabled = false), null).isEmpty())

        val prompt = GenerativeUiPromptCatalog.build(
            GenerativeUiSetting(enabled = true),
            Model(modelId = "claude-sonnet", displayName = "Claude Sonnet"),
        )

        assertTrue(prompt.contains("show-widget"))
        assertTrue(prompt.contains("polished, self-contained SVG widget"))
        assertTrue(prompt.contains(GenerativeUiProtocol.LOCAL_MOTION_URL))
    }

    /**
     * G6 contract: keyword routing injects prompt guidance only — the planner
     * no longer exposes any "suppress tools" predicate, so no caller can clear
     * the tool catalog for diagram requests. The final-widget requirement and
     * the route prompt remain.
     */
    @Test
    fun keywordRoutingNeverSuppressesToolsByItself() {
        val setting = GenerativeUiSetting(enabled = true)
        for (text in listOf("画一个流程图解释这件事", "做一份 5 页 PPT", "搜索资料后画一个流程图")) {
            val messages = listOf(userMessage(text))
            val prompt = GenerativeUiPlanner.buildPrompt(setting = setting, messages = messages)

            assertTrue(prompt.isNotBlank())
            assertTrue(GenerativeUiPlanner.widgetRequirement(setting, messages).required)
        }
    }

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
