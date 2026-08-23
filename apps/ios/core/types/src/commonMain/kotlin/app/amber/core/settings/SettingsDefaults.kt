@file:Suppress("unused")

package app.amber.core.settings

import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting

// Pure-Kotlin seed/consistency passes copied from
// core/settings/src/main/.../prefs/SettingsAggregator.kt (applyBackfillAndSeed /
// applyCrossDomainConsistency / withMigratedMemoryDreamLegacy). The originals are
// pure Kotlin but live in the Android-only :core:settings module; copying them
// here into the KMP :core:types module exposes them to the iOS app via the Shared
// framework (:core:types is already in shared/build.gradle.kts export list) so
// iOS can build a real seeded Settings snapshot WITHOUT depending on the Android
// DataStore layer. The originals in :core:settings are intentionally left
// untouched (Android path is unchanged); these are the shared iOS-facing copies.
//
// Every type/constant these functions touch already lives in commonMain:
// Settings, DEFAULT_PROVIDERS, DEFAULT_TTS_PROVIDERS, DEFAULT_ASSISTANTS,
// REMOVED_DEFAULT_PROVIDER_IDS, OpenAIProviderIdRef, GeminiProviderIdRef,
// SeedOpenAIImageModel, SeedGeminiImageModel, SeedRoutingQuickMessages,
// DEFAULT_SYSTEM_TTS_ID, REMOVED_DEFAULT_TTS_PROVIDER_IDS,
// withAmberAgentAssistantBranding, ProviderSetting, OpenAIBrand,
// TTSProviderSetting.copyProvider, AgentRuntimeSetting.memoryWorker.

/**
 * Phase 2 — per-load backfill / seed / branding.
 *
 * Mirrors SettingsAggregator.applyBackfillAndSeed in :core:settings. Applies the
 * same provider metadata sync, image-model seeding, assistant backfill, routing
 * quick-message seeding, TTS provider backfill, and AmberAgent branding to a
 * Settings instance. Used by [IosSettingsDefaults] to produce a real seeded
 * Settings snapshot for the iOS app.
 */
public fun applyBackfillAndSeedShared(it: Settings): Settings {
    val shouldSeedImageModels = it.imageModelsSeededVersion < 1
    val providers = it.providers
        .filterNot { provider -> provider.id in REMOVED_DEFAULT_PROVIDER_IDS }
        .map { provider ->
            val defaultProvider = DEFAULT_PROVIDERS.find { dp -> dp.id == provider.id }
            if (defaultProvider != null) {
                val withMeta = provider.copyProvider(
                    builtIn = defaultProvider.builtIn,
                    descriptionText = defaultProvider.descriptionText,
                    shortDescriptionText = defaultProvider.shortDescriptionText,
                )
                val withBrand = if (
                    withMeta is ProviderSetting.OpenAI &&
                    defaultProvider is ProviderSetting.OpenAI &&
                    withMeta.brand == OpenAIBrand.GENERIC &&
                    defaultProvider.brand != OpenAIBrand.GENERIC
                ) {
                    withMeta.copy(brand = defaultProvider.brand)
                } else {
                    withMeta
                }
                if (!shouldSeedImageModels) withBrand
                else when {
                    withBrand is ProviderSetting.OpenAI &&
                        withBrand.id == OpenAIProviderIdRef &&
                        withBrand.models.none { m ->
                            m.id == SeedOpenAIImageModelId || m.modelId == SeedOpenAIImageModel.modelId
                        } -> {
                        withBrand.copy(models = withBrand.models + SeedOpenAIImageModel)
                    }
                    withBrand is ProviderSetting.Google &&
                        withBrand.id == GeminiProviderIdRef &&
                        withBrand.models.none { m ->
                            m.id == SeedGeminiImageModelId || m.modelId == SeedGeminiImageModel.modelId
                        } -> {
                        withBrand.copy(models = withBrand.models + SeedGeminiImageModel)
                    }
                    else -> withBrand
                }
            } else provider
        }
    val assistantsRaw = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
    DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
        if (assistantsRaw.none { a -> a.id == defaultAssistant.id }) {
            assistantsRaw.add(defaultAssistant.copy())
        }
    }

    val routingQuickMessagesToSeed = SeedRoutingQuickMessages.filter { qm ->
        it.routingQuickMessagesSeededVersion < if (qm.id == SeedSvgQuickMessageId) 2 else 1
    }
    val shouldSeedRoutingQuickMessages = routingQuickMessagesToSeed.isNotEmpty()
    val routingSeedIds = routingQuickMessagesToSeed.map { qm -> qm.id }.toSet()
    val nextQuickMessages = if (shouldSeedRoutingQuickMessages) {
        val existingIds = it.quickMessages.map { qm -> qm.id }.toSet()
        it.quickMessages + routingQuickMessagesToSeed.filter { qm -> qm.id !in existingIds }
    } else it.quickMessages
    val assistants = if (shouldSeedRoutingQuickMessages) {
        assistantsRaw.map { assistant ->
            if (assistant.id !in DEFAULT_ASSISTANTS_IDS) return@map assistant
            val missing = routingSeedIds - assistant.quickMessageIds
            if (missing.isEmpty()) assistant
            else assistant.copy(quickMessageIds = assistant.quickMessageIds + missing)
        }.toMutableList()
    } else assistantsRaw
    val ttsProviders = it.ttsProviders
        .filterNot { provider -> provider.id in REMOVED_DEFAULT_TTS_PROVIDER_IDS }
        .ifEmpty { DEFAULT_TTS_PROVIDERS }
        .toMutableList()
    DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
        if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
            ttsProviders.add(defaultTTSProvider.copyProvider())
        }
    }
    return it.copy(
        providers = providers,
        assistants = assistants.withAmberAgentAssistantBranding(),
        quickMessages = nextQuickMessages,
        ttsProviders = ttsProviders,
        selectedTTSProviderId = if (ttsProviders.any { provider -> provider.id == it.selectedTTSProviderId }) {
            it.selectedTTSProviderId
        } else {
            DEFAULT_SYSTEM_TTS_ID
        },
        imageModelsSeededVersion = if (shouldSeedImageModels) 1 else it.imageModelsSeededVersion,
        routingQuickMessagesSeededVersion =
            if (shouldSeedRoutingQuickMessages) 2 else it.routingQuickMessagesSeededVersion,
    )
}

/**
 * Phase 3 — cross-domain consistency.
 *
 * Mirrors SettingsAggregator.applyCrossDomainConsistency in :core:settings.
 * Dedups providers/models/assistants/ttsProviders/modeInjections/lorebooks/
 * quickMessages, filters stale references and favoriteModels, and re-applies the
 * reader-path search cleanups. Used by [IosSettingsDefaults] to finalize the
 * seeded Settings snapshot for iOS.
 */
public fun applyCrossDomainConsistencyShared(settings: Settings): Settings {
    val migratedSettings = settings.withMigratedMemoryDreamLegacyShared()
    val validMcpServerIds = migratedSettings.mcpServers.map { it.id }.toSet()
    val validModeInjectionIds = migratedSettings.modeInjections.map { it.id }.toSet()
    val validLorebookIds = migratedSettings.lorebooks.map { it.id }.toSet()
    val validQuickMessageIds = migratedSettings.quickMessages.map { it.id }.toSet()
    val cleanedSearchSelected = if (migratedSettings.searchServices.isEmpty()) {
        0
    } else {
        migratedSettings.searchServiceSelected.coerceIn(0, migratedSettings.searchServices.lastIndex)
    }
    val cleanedSearchEnabledIds = migratedSettings.searchEnabledServiceIds
        .filter { id -> migratedSettings.searchServices.any { service -> service.id == id } }
        .ifEmpty {
            migratedSettings.searchServices.getOrNull(cleanedSearchSelected)
                ?.let { listOf(it.id) }
                .orEmpty()
        }
    return migratedSettings.copy(
        searchServiceSelected = cleanedSearchSelected,
        searchEnabledServiceIds = cleanedSearchEnabledIds,
        providers = migratedSettings.providers.distinctBy { it.id }.map { provider ->
            when (provider) {
                is ProviderSetting.OpenAI -> provider.copy(
                    models = provider.models.distinctBy { model -> model.id }
                )

                is ProviderSetting.Google -> provider.copy(
                    models = provider.models.distinctBy { model -> model.id }
                )

                is ProviderSetting.Claude -> provider.copy(
                    models = provider.models.distinctBy { model -> model.id }
                )
            }
        },
        assistants = migratedSettings.assistants.distinctBy { it.id }.map { assistant ->
            assistant.copy(
                mcpServers = assistant.mcpServers.filter { serverId ->
                    serverId in validMcpServerIds
                }.toSet(),
                modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                    id in validModeInjectionIds
                }.toSet(),
                lorebookIds = assistant.lorebookIds.filter { id ->
                    id in validLorebookIds
                }.toSet(),
                quickMessageIds = assistant.quickMessageIds.filter { id ->
                    id in validQuickMessageIds
                }.toSet()
            )
        },
        ttsProviders = migratedSettings.ttsProviders.distinctBy { it.id },
        favoriteModels = migratedSettings.favoriteModels.filter { uuid ->
            migratedSettings.providers.flatMap { it.models }.any { m -> m.id == uuid }
        },
        modeInjections = migratedSettings.modeInjections.distinctBy { it.id },
        lorebooks = migratedSettings.lorebooks.distinctBy { it.id },
        quickMessages = migratedSettings.quickMessages.distinctBy { it.id },
    )
}

private fun Settings.withMigratedMemoryDreamLegacyShared(): Settings {
    val worker = agentRuntime.memoryWorker
    if (!worker.dreamEnabled) return this
    return copy(
        agentRuntime = agentRuntime.copy(
            memoryWorker = worker.copy(
                dreamModelEnabled = worker.dreamModelEnabled || worker.dreamEnabled,
                dreamEnabled = false,
            )
        )
    )
}

/**
 * iOS-facing entry point for a real, seeded [Settings] snapshot.
 *
 * Builds a Settings from its default constructor (which already carries the
 * commonMain seed defaults — DEFAULT_PROVIDERS, DEFAULT_TTS_PROVIDERS,
 * DEFAULT_ASSISTANTS, a default SearchServiceOptions, a default DisplaySetting),
 * then runs the same backfill/seed + cross-domain consistency passes the Android
 * SettingsAggregator applies, so the iOS app reads a structurally complete,
 * honestly-seeded Settings instead of a handful of hardcoded placeholder fields.
 *
 * This is a READ-ONLY seed snapshot. It is NOT wired to the Android DataStore
 * (iOS and Android are separate apps and do not share on-device settings), and
 * it is NOT a writable persistence layer — writes from iOS are out of scope for
 * this slice. Every call returns a freshly-computed snapshot.
 */
public object IosSettingsDefaults {
    public fun defaultSeededSettings(): Settings =
        applyCrossDomainConsistencyShared(applyBackfillAndSeedShared(Settings()))
}
