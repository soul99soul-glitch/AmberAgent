package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.settings.GenerativeUiSetting
import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPromptsTest {
    @Test
    fun soulPromptWrapsAgentsMarkdown() {
        val prompt = buildAgentSoulPrompt("# agents.md\n\nStay agentic.")

        assertTrue(prompt.contains("<agents_md>"))
        assertTrue(prompt.contains("Stay agentic."))
        assertTrue(prompt.contains("</agents_md>"))
    }

    @Test
    fun memoryPromptSkipsEmptyBuckets() {
        val prompt = buildLongTermMemoryPrompt(emptyList())

        assertTrue(prompt.isEmpty())
    }

    @Test
    fun memoryPromptLabelsLayer() {
        val prompt = buildShortTermMemoryPrompt(
            listOf(AssistantMemory(id = 7, content = "Current task is packaging a skill."))
        )

        assertTrue(prompt.contains("Short-Term Memories"))
        assertTrue(prompt.contains("Current task is packaging a skill."))
        assertFalse(prompt.contains("Long-Term Memories"))
    }

    @Test
    fun generativeUiPromptIsControlledBySetting() {
        assertTrue(buildGenerativeUiPrompt(GenerativeUiSetting(enabled = false)).isEmpty())

        val prompt = buildGenerativeUiPrompt(GenerativeUiSetting())

        assertTrue(prompt.contains("show-widget"))
        assertTrue(prompt.contains("widget_code"))
        assertTrue(prompt.contains("Do not use script tags"))
        assertTrue(prompt.contains("external CDNs, fixed positioning, or navigation."))
        assertFalse(prompt.contains("""title":"Human readable title"""))
        assertFalse(prompt.contains("<svg or static HTML/CSS>"))
        assertTrue(prompt.contains("actions"))
        assertTrue(prompt.contains("renderer/spec"))
        assertTrue(prompt.contains("Do not call eval_javascript"))
        assertTrue(prompt.contains("Do NOT create widgets for tool routing"))
        assertTrue(prompt.contains("guizang-ppt-skill"))
        assertTrue(prompt.contains("guizang-ppt-skill DEFAULT"))
        assertFalse(prompt.contains("high-fidelity exception"))
        assertTrue(prompt.contains("guizang_html"))
        assertTrue(prompt.contains("Do NOT generate or save an AmberAgent MiniApp for PPT requests."))
        assertTrue(prompt.contains("""<div id="deck">"""))
        assertTrue(prompt.contains(GuizangHtmlDeckValidator.LOCAL_LUCIDE_URL))
        assertTrue(prompt.contains(GuizangHtmlDeckValidator.LOCAL_MOTION_URL))
        assertTrue(prompt.contains("Slides Spec V2"))
        assertTrue(prompt.contains("cover, section, quote, split, metrics, timeline"))
        assertTrue(prompt.contains("fontPack"))
        assertTrue(prompt.contains("magazine|swiss"))
    }

    @Test
    fun generativeUiPromptAddsDeepSeekVisibleOutputGuidance() {
        val prompt = buildGenerativeUiPrompt(
            setting = GenerativeUiSetting(),
            model = Model(modelId = "deepseek-v4-pro", displayName = "DeepSeek V4 Pro"),
        )

        assertTrue(prompt.contains("DeepSeek"))
        assertTrue(prompt.contains("keep hidden reasoning extremely brief"))
        assertTrue(prompt.contains("start visible content"))
    }

    @Test
    fun generativeUiPromptAddsKimiNoToolDrawingGuidance() {
        val prompt = buildGenerativeUiPrompt(
            setting = GenerativeUiSetting(),
            model = Model(modelId = "moonshotai/kimi-k2.6-20260420", displayName = "Kimi K2.6"),
        )

        assertTrue(prompt.contains("Kimi/Moonshot"))
        assertTrue(prompt.contains("do not use function/tool calls to generate SVG"))
        assertTrue(prompt.contains("Do not call eval_javascript"))
    }

    @Test
    fun generativeUiPromptAddsMiniMaxBoundsGuidance() {
        val prompt = buildGenerativeUiPrompt(
            setting = GenerativeUiSetting(),
            model = Model(modelId = "minimax-m2.7", displayName = "MiniMax M2.7"),
        )

        assertTrue(prompt.contains("MiniMax"))
        assertTrue(prompt.contains("keep all boxes, dashed groups, arrows, and text inside it"))
        assertTrue(prompt.contains("x + width <= 656"))
    }

    @Test
    fun generativeUiPromptKeepsClaudePolishAndActions() {
        val prompt = buildGenerativeUiPrompt(
            setting = GenerativeUiSetting(),
            model = Model(modelId = "claude-sonnet-4-6", displayName = "Claude Sonnet 4.6"),
        )

        assertTrue(prompt.contains("Claude"))
        assertTrue(prompt.contains("polished, self-contained SVG widget"))
        assertTrue(prompt.contains("native actions are welcome"))
        assertTrue(prompt.contains("avoid plan/tool detours"))
    }
}
