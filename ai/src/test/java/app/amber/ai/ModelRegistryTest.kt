package app.amber.ai

import app.amber.ai.provider.Modality
import app.amber.ai.provider.ModelAbility
import app.amber.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun testGPT5() {
        assertTrue(ModelRegistry.GPT_5.match("gpt-5"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5-chat"))
        assertTrue(ModelRegistry.GPT_5.match("gpt-5-mini"))
        assertFalse(ModelRegistry.GPT_5.match("deepseek-v3"))
        assertFalse(ModelRegistry.GPT_5.match("gemini-2.0-flash"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.1"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-4o"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.0"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-6"))
    }

    @Test
    fun testGemini25() {
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-flash-latest"))
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-pro-latest"))
        assertTrue(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-pro"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash-image-preview"))
        assertTrue(ModelRegistry.GEMINI_2_5_IMAGE.match("gemini-2.5-flash-image"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash-image")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash")
        )
    }

    @Test
    fun testClaudeSeries() {
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4.5-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4.5-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-3.5-sonnet"))
    }

    @Test
    fun testSpecificityPriority() {
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2.5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2")
        )
    }

    @Test
    fun testOpenAIOModels() {
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o1"))
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o3-mini"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini")
        )
    }

    @Test
    fun testGlm5AndMinimaxM25() {
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("minimax-m2.5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("glm-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("minimax-m2.5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("MiniMax-M3")
        )
    }

    @Test
    fun testDeepseekV4() {
        val reasonerAbilities = ModelRegistry.MODEL_ABILITIES.getData("deepseek-reasoner")
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash")
        )
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-pro")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash")
        )
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4.1-flash")
        )
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-flash")
        )
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("deepseek-v4.1-flash"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("deepseek-flash"))
    }

    @Test
    fun testFrontierAndDomestic2026Specs() {
        val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)
        val vision = listOf(Modality.TEXT, Modality.IMAGE)
        val audioVision = listOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO)

        for (id in listOf("gpt-5.4", "gpt-5.5", "gpt-5.6")) {
            assertEquals(1_050_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData(id))
            assertEquals(vision, ModelRegistry.MODEL_INPUT_MODALITIES.getData(id))
            assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData(id))
        }
        for (id in listOf("gpt-6", "gpt-6.2", "gpt-6-astra", "gpt-6-sol", "gpt-6-terra", "gpt-6-luna")) {
            assertEquals(1_047_576, ModelRegistry.MODEL_CONTEXT_WINDOW.getData(id))
            assertEquals(vision, ModelRegistry.MODEL_INPUT_MODALITIES.getData(id))
            assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData(id))
            assertEquals("openai_reasoning", ModelRegistry.sessionDefaultGroupForModel(id)?.id)
        }

        for (id in listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-2", "claude-mythos-5-2")) {
            assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData(id))
            assertEquals(vision, ModelRegistry.MODEL_INPUT_MODALITIES.getData(id))
            assertTrue(ModelRegistry.CLAUDE_SERIES.match(id))
        }
        assertEquals(200_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("claude-haiku-4-5-20251001"))
        assertFalse(ModelRegistry.CLAUDE_SERIES.match("claude-3.5-haiku"))

        for (id in listOf("gemini-4-carbon", "gemini-4-neon", "gemini-3.8-pro", "gemini-3.5-flash", "gemini-3.1-pro", "gemini-3.1-flash")) {
            assertEquals(1_048_576, ModelRegistry.MODEL_CONTEXT_WINDOW.getData(id))
            assertEquals(audioVision, ModelRegistry.MODEL_INPUT_MODALITIES.getData(id))
            assertTrue(ModelRegistry.GEMINI_SERIES.match(id))
        }
        assertEquals(256_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("gemini-decision-1"))

        assertEquals(500_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("grok-4.7"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("grok-4.20-0309-reasoning"))
        assertEquals(131_072, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("grok-3"))
        assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("grok-3"))

        for (id in listOf("qwen4-max", "qwen3.8-max", "qwen3.7-max")) {
            assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData(id))
            assertEquals(vision, ModelRegistry.MODEL_INPUT_MODALITIES.getData(id))
            assertEquals("qwen_reasoning", ModelRegistry.sessionDefaultGroupForModel(id)?.id)
        }
        assertEquals(65_536, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("qwen3.8-omni"))
        assertEquals(audioVision, ModelRegistry.MODEL_INPUT_MODALITIES.getData("qwen3.8-omni"))

        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("glm-5.3-flash"))
        assertEquals(vision, ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5.3-flashx"))
        assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5.3"))
        assertEquals(200_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("glm-5v-turbo"))
        assertEquals(vision, ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5v-turbo"))

        assertEquals(1_048_576, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("kimi-k3.1"))
        assertEquals(1_048_576, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("k3"))
        assertEquals(262_144, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("kimi-k2.5"))

        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("MiniMax-M3.1"))
        assertEquals(vision, ModelRegistry.MODEL_INPUT_MODALITIES.getData("MiniMax-M3.1"))
        assertEquals(204_800, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("minimax-m2.7"))

        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("mimo-v2.6-pro"))
        assertEquals(audioVision, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.6-pro"))

        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("doubao-seed-2.1-pro"))
        assertEquals(audioVision, ModelRegistry.MODEL_INPUT_MODALITIES.getData("doubao-seed-2.1-pro"))
        assertEquals(256_000, ModelRegistry.MODEL_CONTEXT_WINDOW.getData("seed-2.1-turbo"))
        assertEquals(vision, ModelRegistry.MODEL_INPUT_MODALITIES.getData("seed-2.1-turbo"))
    }
}
