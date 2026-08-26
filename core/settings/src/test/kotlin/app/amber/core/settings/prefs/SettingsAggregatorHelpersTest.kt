package app.amber.core.settings.prefs

import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.settings.LegacyAssistantProfile
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.core.settings.GeminiProviderIdRef
import app.amber.core.settings.OpenAIProviderIdRef
import app.amber.core.settings.SeedGeminiImageModelId
import app.amber.core.settings.SeedOpenAIImageModelId
import app.amber.core.settings.SeedRoutingQuickMessages
import app.amber.core.settings.SeedSvgQuickMessageId
import app.amber.core.settings.getAmberQuickMessages
import app.amber.core.settings.selectLegacyAssistantProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsAggregatorHelpersTest {

    @Test
    fun `legacy selection keeps the chosen profile and canonical Amber owner`() {
        val selected = LegacyAssistantProfile(
            id = Uuid.random(),
            systemPrompt = "Keep this prompt",
            mcpServers = setOf(Uuid.random()),
            enabledSkills = setOf("custom-skill"),
        )
        val result = selectLegacyAssistantProfile(selected.id, listOf(selected))
        assertEquals(AMBER_AGENT_ID, result.id)
        assertEquals("Keep this prompt", result.systemPrompt)
        assertEquals(selected.mcpServers, result.mcpServers)
        assertTrue("custom-skill" in result.enabledSkills)
    }

    @Test
    fun `compose maps direct Amber runtime fields from ChatPrefs and ExtensionPrefs`() {
        val out = composeRawSettings(
            ui = UIPrefsData(themeId = "theme_test"),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(systemPrompt = "Amber prompt", contextMessageSize = 123),
            ext = ExtensionPrefsData(enabledSkills = setOf("skill-a")),
        )
        assertEquals("Amber prompt", out.systemPrompt)
        assertEquals(123, out.contextMessageSize)
        assertEquals(setOf("skill-a"), out.enabledSkills)
        assertEquals(DEFAULT_PROVIDERS, out.providers)
        assertFalse(out.init)
    }

    @Test
    fun `backfill seeds image models and global routing quick messages`() {
        val openai = ProviderSetting.OpenAI(
            id = OpenAIProviderIdRef,
            name = "OpenAI",
            apiKey = "",
            baseUrl = "",
            models = emptyList<Model>(),
        )
        val gemini = ProviderSetting.Google(
            id = GeminiProviderIdRef,
            name = "Gemini",
            apiKey = "",
            models = emptyList<Model>(),
        )
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(providers = listOf(openai, gemini)),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
        )
        val out = applyBackfillAndSeed(input)
        assertTrue(out.providers.first { it.id == OpenAIProviderIdRef }
            .models.any { it.id == SeedOpenAIImageModelId })
        assertTrue(out.providers.first { it.id == GeminiProviderIdRef }
            .models.any { it.id == SeedGeminiImageModelId })
        assertEquals(SeedRoutingQuickMessages.map { it.id }, out.quickMessages.map { it.id })
        assertEquals(1, out.imageModelsSeededVersion)
        assertEquals(2, out.routingQuickMessagesSeededVersion)
        assertEquals(out.quickMessages, out.getAmberQuickMessages())
    }

    @Test
    fun `backfill v1 only adds the svg routing message`() {
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(imageModelsSeededVersion = 1),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(routingQuickMessagesSeededVersion = 1),
        )
        val out = applyBackfillAndSeed(input)
        assertEquals(listOf(SeedSvgQuickMessageId), out.quickMessages.map { it.id })
        assertEquals(2, out.routingQuickMessagesSeededVersion)
    }

    @Test
    fun `cross domain consistency filters direct enabled references`() {
        val validServer = Uuid.random()
        val validMode = Uuid.random()
        val validLorebook = Uuid.random()
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(
                mcpServers = listOf(
                    app.amber.core.ai.mcp.McpServerConfig.StreamableHTTPServer(id = validServer),
                ),
                modeInjections = listOf(app.amber.core.model.PromptInjection.ModeInjection(id = validMode)),
                lorebooks = listOf(app.amber.core.model.Lorebook(id = validLorebook)),
                enabledMcpServerIds = setOf(validServer, Uuid.random()),
                enabledModeInjectionIds = setOf(validMode, Uuid.random()),
                enabledLorebookIds = setOf(validLorebook, Uuid.random()),
            ),
        )
        val out = applyCrossDomainConsistency(input)
        assertEquals(setOf(validServer), out.enabledMcpServerIds)
        assertEquals(setOf(validMode), out.enabledModeInjectionIds)
        assertEquals(setOf(validLorebook), out.enabledLorebookIds)
    }

    @Test
    fun `backfill and consistency are idempotent`() {
        val raw = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(imageModelsSeededVersion = 1),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(routingQuickMessagesSeededVersion = 1),
        )
        val pass1 = applyCrossDomainConsistency(applyBackfillAndSeed(raw))
        val pass2 = applyCrossDomainConsistency(applyBackfillAndSeed(pass1))
        assertEquals(pass1, pass2)
    }
}
