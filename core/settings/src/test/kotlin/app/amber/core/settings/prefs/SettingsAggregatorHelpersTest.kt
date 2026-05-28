package app.amber.core.settings.prefs

import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.core.settings.DEFAULT_ASSISTANTS
import app.amber.core.model.DEFAULT_ASSISTANT_ID
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.core.settings.DEFAULT_SYSTEM_TTS_ID
import app.amber.core.settings.DEFAULT_TTS_PROVIDERS
import app.amber.core.settings.GeminiProviderIdRef
import app.amber.core.settings.OpenAIProviderIdRef
import app.amber.core.settings.SeedGeminiImageModel
import app.amber.core.settings.SeedGeminiImageModelId
import app.amber.core.settings.SeedOpenAIImageModel
import app.amber.core.settings.SeedOpenAIImageModelId
import app.amber.core.settings.SeedRoutingQuickMessages
import app.amber.core.settings.Settings
import app.amber.core.model.QuickMessage
import app.amber.core.settings.DEFAULT_PRESET_THEME_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * M1.1.8b — Snapshot tests for the 3 pure helpers that drive
 * [SettingsAggregator.settingsFlow]:
 *
 * - [composeRawSettings] — assemble Settings from 7 PrefsData
 * - [applyBackfillAndSeed] — backfill defaults / seed image-models / seed
 *   routing quick-messages / TTS clamp / branding
 * - [applyCrossDomainConsistency] — dedup + filter stale refs + search
 *   reader cleanup (M1.1.8a B1 patch)
 *
 * These are the pure functions that decide what [Settings] object
 * [SettingsAggregator.settingsFlow] emits. M1.1.8a reviewer verified them
 * byte-equivalent to [app.amber.core.settings.SettingsStore]
 * reader at the source level; this file locks the runtime output so any
 * future change that drifts away from current behaviour fails fast.
 */
class SettingsAggregatorHelpersTest {

    // ---------------- composeRawSettings ----------------

    @Test
    fun `composeRawSettings — field mapping smoke`() {
        val themeId = "theme_test"
        val ui = UIPrefsData(themeId = themeId, launchCount = 42)
        val out = composeRawSettings(
            ui = ui,
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        // init flag forced to false (real data, not dummy)
        assertFalse(out.init)
        assertEquals(themeId, out.themeId)
        assertEquals(42, out.launchCount)
        // 7 PrefsData default propagation
        assertEquals(DEFAULT_PRESET_THEME_ID, UIPrefsData().themeId)
        assertEquals(DEFAULT_ASSISTANT_ID, out.assistantId)
        assertEquals(DEFAULT_PROVIDERS, out.providers)
    }

    // ---------------- applyBackfillAndSeed ----------------

    @Test
    fun `applyBackfillAndSeed — REMOVED provider filtered`() {
        val removedProvider = (DEFAULT_PROVIDERS.first() as ProviderSetting.OpenAI).copy(
            id = app.amber.core.settings.REMOVED_DEFAULT_PROVIDER_IDS.first(),
        )
        val keepProvider = DEFAULT_PROVIDERS.first()
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(providers = listOf(removedProvider, keepProvider)),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        val out = applyBackfillAndSeed(input)
        assertFalse(out.providers.any { it.id == removedProvider.id })
        assertTrue(out.providers.any { it.id == keepProvider.id })
    }

    @Test
    fun `applyBackfillAndSeed — image-model seeded when version less than 1`() {
        // Construct an OpenAI provider matching OpenAIProviderIdRef with no image model
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
            provider = ProviderPrefsData(
                providers = listOf(openai, gemini),
                imageModelsSeededVersion = 0,
            ),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        val out = applyBackfillAndSeed(input)
        val outOpenAi = out.providers.first { it.id == OpenAIProviderIdRef } as ProviderSetting.OpenAI
        val outGemini = out.providers.first { it.id == GeminiProviderIdRef } as ProviderSetting.Google
        assertTrue(outOpenAi.models.any { it.id == SeedOpenAIImageModelId })
        assertTrue(outGemini.models.any { it.id == SeedGeminiImageModelId })
        // version flag flipped to 1
        assertEquals(1, out.imageModelsSeededVersion)
    }

    @Test
    fun `applyBackfillAndSeed — image-model NOT seeded when version already 1`() {
        val openai = ProviderSetting.OpenAI(
            id = OpenAIProviderIdRef,
            name = "OpenAI",
            apiKey = "",
            baseUrl = "",
            models = emptyList<Model>(),
        )
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(
                providers = listOf(openai),
                imageModelsSeededVersion = 1,
            ),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        val out = applyBackfillAndSeed(input)
        val outOpenAi = out.providers.first { it.id == OpenAIProviderIdRef } as ProviderSetting.OpenAI
        assertFalse(outOpenAi.models.any { it.id == SeedOpenAIImageModelId })
        assertEquals(1, out.imageModelsSeededVersion)
    }

    @Test
    fun `applyBackfillAndSeed — image-model dedupe by modelId`() {
        // Pre-existing gpt-image-2 with a DIFFERENT UUID but same modelId
        val existingImage = SeedOpenAIImageModel.copy(id = Uuid.random())
        val openai = ProviderSetting.OpenAI(
            id = OpenAIProviderIdRef,
            name = "OpenAI",
            apiKey = "",
            baseUrl = "",
            models = listOf(existingImage),
        )
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(
                providers = listOf(openai),
                imageModelsSeededVersion = 0,
            ),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        val out = applyBackfillAndSeed(input)
        val outOpenAi = out.providers.first { it.id == OpenAIProviderIdRef } as ProviderSetting.OpenAI
        // Only one model with that modelId, NOT two
        assertEquals(1, outOpenAi.models.count { it.modelId == SeedOpenAIImageModel.modelId })
    }

    @Test
    fun `applyBackfillAndSeed — DEFAULT_ASSISTANTS injected when missing`() {
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(assistants = emptyList()),
        )
        val out = applyBackfillAndSeed(input)
        DEFAULT_ASSISTANTS.forEach { defaultA ->
            assertTrue(
                "DEFAULT_ASSISTANTS id ${defaultA.id} should be injected when input is empty",
                out.assistants.any { it.id == defaultA.id }
            )
        }
    }

    @Test
    fun `applyBackfillAndSeed — routing quick-messages seeded and subscribed`() {
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(routingQuickMessagesSeededVersion = 0),
            assistant = AssistantPrefsData(assistants = DEFAULT_ASSISTANTS),
        )
        val out = applyBackfillAndSeed(input)
        // QM appended to global pool
        SeedRoutingQuickMessages.forEach { qm ->
            assertTrue(out.quickMessages.any { it.id == qm.id })
        }
        // DEFAULT_ASSISTANTS subscribe these QM ids
        val seedIds = SeedRoutingQuickMessages.map(QuickMessage::id).toSet()
        out.assistants
            .filter { it.id in DEFAULT_ASSISTANTS.map { d -> d.id } }
            .forEach { assistant ->
                assertTrue(
                    "Default assistant ${assistant.id} should subscribe all seed QM ids",
                    seedIds.all { id -> id in assistant.quickMessageIds }
                )
            }
        assertEquals(1, out.routingQuickMessagesSeededVersion)
    }

    @Test
    fun `applyBackfillAndSeed — TTS DEFAULT backfill when empty + selectedId clamp`() {
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(
                ttsProviders = emptyList(),
                selectedTTSProviderId = Uuid.random(), // stale, not in ttsProviders
            ),
            assistant = AssistantPrefsData(),
        )
        val out = applyBackfillAndSeed(input)
        DEFAULT_TTS_PROVIDERS.forEach { tts ->
            assertTrue(out.ttsProviders.any { it.id == tts.id })
        }
        // selectedTTSProviderId clamped to DEFAULT_SYSTEM_TTS_ID since stale wasn't in backfilled list
        assertEquals(DEFAULT_SYSTEM_TTS_ID, out.selectedTTSProviderId)
    }

    @Test
    fun `applyBackfillAndSeed — branding applied to default assistant`() {
        val amberagentNamedDefault = DEFAULT_ASSISTANTS.first().copy(name = "Amberagent")
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(
                assistants = listOf(amberagentNamedDefault),
            ),
        )
        val out = applyBackfillAndSeed(input)
        // The DEFAULT assistant's name should be re-stamped to AmberAgent
        val branded = out.assistants.first { it.id == DEFAULT_ASSISTANT_ID }
        assertEquals("AmberAgent", branded.name)
    }

    // ---------------- applyCrossDomainConsistency ----------------

    @Test
    fun `applyCrossDomainConsistency — duplicate providers deduped by id`() {
        val provider = DEFAULT_PROVIDERS.first()
        val dup = provider.copyProvider(name = "duplicate")
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(providers = listOf(provider, dup)),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        val out = applyCrossDomainConsistency(input)
        assertEquals(1, out.providers.count { it.id == provider.id })
    }

    @Test
    fun `applyCrossDomainConsistency — searchServiceSelected coerceIn bounds`() {
        val searchPrefs = SearchPrefsData(searchServiceSelected = 99)
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = searchPrefs,
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        // searchServices.size = 1 (default SEARCH_SERVICES_DEFAULT), so lastIndex = 0
        val out = applyCrossDomainConsistency(input)
        assertEquals(0, out.searchServiceSelected)
    }

    @Test
    fun `applyCrossDomainConsistency — empty searchEnabledIds gets derived default`() {
        // searchServices has 1 service (SearchServiceOptions.DEFAULT), enabledIds is empty
        // expect: enabledIds gets [searchServices[0].id] derived
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(searchEnabledServiceIds = emptyList()),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        val out = applyCrossDomainConsistency(input)
        assertEquals(1, out.searchEnabledServiceIds.size)
        assertEquals(out.searchServices[0].id, out.searchEnabledServiceIds.first())
    }

    @Test
    fun `applyCrossDomainConsistency — stale searchEnabledIds filtered + ifEmpty derived`() {
        // searchEnabledIds contains an id NOT in searchServices → filter empties → ifEmpty derives default
        val staleId = Uuid.random()
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(
                searchEnabledServiceIds = listOf(staleId),
            ),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        val out = applyCrossDomainConsistency(input)
        assertFalse(staleId in out.searchEnabledServiceIds)
        // Derived default applied since list was fully stale
        assertEquals(1, out.searchEnabledServiceIds.size)
        assertEquals(out.searchServices[0].id, out.searchEnabledServiceIds.first())
    }

    @Test
    fun `applyCrossDomainConsistency — favoriteModels filters stale uuid`() {
        // favoriteModels contains a uuid that doesn't exist in any provider's models
        val staleModelId = Uuid.random()
        val input = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(favoriteModels = listOf(staleModelId)),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        val out = applyCrossDomainConsistency(input)
        assertFalse(staleModelId in out.favoriteModels)
    }

    // ---------------- end-to-end pipeline ----------------

    @Test
    fun `pipeline — empty prefs produce sane defaults`() {
        // Simulates fresh install: all empty PrefsData
        val raw = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(),
            assistant = AssistantPrefsData(),
        )
        val backfilled = applyBackfillAndSeed(raw)
        val final = applyCrossDomainConsistency(backfilled)

        // Sanity assertions on the final Settings object — should be ready to ship to caller
        assertFalse(final.init)
        // DEFAULT_ASSISTANTS got injected
        DEFAULT_ASSISTANTS.forEach { defaultA ->
            assertTrue(final.assistants.any { it.id == defaultA.id })
        }
        // Seed flags flipped
        assertEquals(1, final.imageModelsSeededVersion)
        assertEquals(1, final.routingQuickMessagesSeededVersion)
        // TTS backfilled
        assertTrue(final.ttsProviders.any { it.id == DEFAULT_SYSTEM_TTS_ID })
        // Search enabled service derived from default search services
        assertEquals(1, final.searchEnabledServiceIds.size)
        assertEquals(0, final.searchServiceSelected)
        // Branding applied to DEFAULT_ASSISTANT_ID
        val branded = final.assistants.first { it.id == DEFAULT_ASSISTANT_ID }
        assertEquals("AmberAgent", branded.name)
    }

    @Test
    fun `pipeline — idempotent (running twice yields same Settings)`() {
        val raw = composeRawSettings(
            ui = UIPrefsData(),
            search = SearchPrefsData(),
            agent = AgentPrefsData(),
            provider = ProviderPrefsData(imageModelsSeededVersion = 1),
            chat = ChatPrefsData(),
            ext = ExtensionPrefsData(routingQuickMessagesSeededVersion = 1),
            assistant = AssistantPrefsData(assistants = DEFAULT_ASSISTANTS),
        )
        val pass1 = applyCrossDomainConsistency(applyBackfillAndSeed(raw))
        val pass2 = applyCrossDomainConsistency(applyBackfillAndSeed(pass1))
        assertEquals(pass1, pass2)
    }
}
