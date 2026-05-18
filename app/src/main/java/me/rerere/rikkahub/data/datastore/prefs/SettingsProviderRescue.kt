package me.rerere.rikkahub.data.datastore.prefs

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.GoogleAuthMode
import me.rerere.ai.provider.OpenAIAuthMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.uuid.Uuid

private const val TAG = "SettingsProviderRescue"

/**
 * Best-effort repair for the M1.1.8 prefs-split startup race.
 *
 * The race could persist default Settings over a user's real settings. When a
 * richer local sync payload is still cached, recover the user-visible settings
 * layer from it. This never touches conversations, DB tables, or files.
 */
class SettingsProviderRescue(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val json: Json,
) {
    suspend fun rescueIfNeeded() = withContext(Dispatchers.IO) {
        runCatching {
            val current = settingsStore.settingsFlow.filterNot { it.init }.first()
            val backup = findBestCachedSettingsBackup() ?: return@runCatching
            if (!current.needsSettingsRescueFrom(backup)) {
                return@runCatching
            }

            val recovered = current.recoverSettingsFrom(backup)
            settingsStore.update(recovered)
            Log.w(
                TAG,
                "Recovered settings from cached sync payload: providers=${backup.providers.size}, models=${backup.providers.sumOf { it.models.size }}, searchServices=${backup.searchServices.size}"
            )
        }.onFailure {
            Log.e(TAG, "Settings rescue failed", it)
        }
    }

    private fun findBestCachedSettingsBackup(): Settings? {
        val syncDir = File(context.cacheDir, "sync")
        val candidates = syncDir.listFiles { file ->
            file.isFile && file.extension.equals("zip", ignoreCase = true)
        }.orEmpty()

        return candidates
            .mapNotNull { file -> readSettingsFromPayload(file)?.let { file to it } }
            .filter { (_, settings) -> settings.providers.isNotEmpty() }
            .maxWithOrNull(
                compareBy<Pair<File, Settings>> { (_, settings) -> settings.configRecoveryScore() }
                    .thenBy { (file, _) -> file.lastModified() }
            )
            ?.second
    }

    private fun readSettingsFromPayload(file: File): Settings? = runCatching {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == "settings.json") {
                    return@runCatching json.decodeFromString<Settings>(
                        zip.readBytes().decodeToString()
                    )
                }
                zip.closeEntry()
            }
        }
        null
    }.getOrElse {
        Log.d(TAG, "Skipping unreadable sync payload ${file.name}: ${it.message}")
        null
    }

    private fun Settings.needsSettingsRescueFrom(backup: Settings): Boolean {
        if (looksLikeWipedProviderDefaults() && backup.providerRecoveryScore() > providerRecoveryScore()) {
            return true
        }
        if (searchRecoveryScore() < backup.searchRecoveryScore()) {
            return true
        }
        if (displaySetting.looksLikeDefaultIdentity() && !backup.displaySetting.looksLikeDefaultIdentity()) {
            return true
        }
        if (assistants.size < backup.assistants.size || quickMessages.size < backup.quickMessages.size) {
            return true
        }
        if (mcpServers.size < backup.mcpServers.size || modeInjections.size < backup.modeInjections.size) {
            return true
        }
        return false
    }

    private fun Settings.recoverSettingsFrom(backup: Settings): Settings {
        val validModelIds = backup.providers.flatMap { it.models }.map { it.id }.toSet()
        return copy(
            dynamicColor = backup.dynamicColor,
            themeId = backup.themeId,
            developerMode = backup.developerMode,
            displaySetting = backup.displaySetting,
            enableWebSearch = backup.enableWebSearch,
            favoriteModels = backup.favoriteModels.filter { it in validModelIds },
            chatModelId = backup.chatModelId.takeIfValidModel(validModelIds, chatModelId),
            titleModelId = backup.titleModelId.takeIfValidModel(validModelIds, titleModelId),
            translateModeId = backup.translateModeId.takeIfValidModel(validModelIds, translateModeId),
            suggestionModelId = backup.suggestionModelId.takeIfValidModel(validModelIds, suggestionModelId),
            imageGenerationModelId = backup.imageGenerationModelId.takeIfValidModel(
                validModelIds,
                imageGenerationModelId
            ),
            titlePrompt = backup.titlePrompt,
            translatePrompt = backup.translatePrompt,
            translateThinkingBudget = backup.translateThinkingBudget,
            suggestionPrompt = backup.suggestionPrompt,
            ocrModelId = backup.ocrModelId.takeIfValidModel(validModelIds, ocrModelId),
            ocrPrompt = backup.ocrPrompt,
            compressModelId = backup.compressModelId.takeIfValidModel(validModelIds, compressModelId),
            compressPrompt = backup.compressPrompt,
            modelGroupSessionDefaults = backup.modelGroupSessionDefaults.filter { it.groupId.isNotBlank() },
            providers = backup.providers,
            imageModelsSeededVersion = backup.imageModelsSeededVersion,
            assistantId = backup.assistantId,
            assistants = backup.assistants,
            assistantTags = backup.assistantTags,
            searchServices = backup.searchServices,
            searchCommonOptions = backup.searchCommonOptions,
            searchServiceSelected = backup.searchServiceSelected,
            searchEnabledServiceIds = backup.searchEnabledServiceIds,
            searchBuiltinDuckDuckGoEnabled = backup.searchBuiltinDuckDuckGoEnabled,
            searchBuiltinBingEnabled = backup.searchBuiltinBingEnabled,
            searchBuiltinJinaEnabled = backup.searchBuiltinJinaEnabled,
            searchBuiltinWikipediaEnabled = backup.searchBuiltinWikipediaEnabled,
            searchBuiltinHackerNewsEnabled = backup.searchBuiltinHackerNewsEnabled,
            searchGoogleWebViewFallbackEnabled = backup.searchGoogleWebViewFallbackEnabled,
            agentRuntime = backup.agentRuntime,
            mcpServers = backup.mcpServers,
            webDavConfig = backup.webDavConfig,
            s3Config = backup.s3Config,
            ttsProviders = backup.ttsProviders,
            selectedTTSProviderId = backup.selectedTTSProviderId,
            modeInjections = backup.modeInjections,
            lorebooks = backup.lorebooks,
            quickMessages = backup.quickMessages,
            webServerEnabled = backup.webServerEnabled,
            webServerPort = backup.webServerPort,
            webServerJwtEnabled = backup.webServerJwtEnabled,
            webServerAccessPassword = backup.webServerAccessPassword,
            webServerLocalhostOnly = backup.webServerLocalhostOnly,
            backupReminderConfig = backup.backupReminderConfig,
            sponsorAlertDismissedAt = backup.sponsorAlertDismissedAt,
            routingQuickMessagesSeededVersion = backup.routingQuickMessagesSeededVersion,
            // Keep current-device sync identity/revision and launch count.
            syncSettings = syncSettings,
            launchCount = launchCount,
        )
    }

    private fun Settings.looksLikeWipedProviderDefaults(): Boolean {
        val defaultIds = DEFAULT_PROVIDERS.map { it.id }.toSet()
        val providerIds = providers.map { it.id }.toSet()
        if (providerIds != defaultIds) return false
        if (providers.any { it.hasCredentialOrManagedAuth() }) return false
        val currentModels = providers.sumOf { it.models.size }
        val defaultModels = DEFAULT_PROVIDERS.sumOf { it.models.size }
        return currentModels <= defaultModels + 2
    }

    private fun Settings.configRecoveryScore(): Int =
        providerRecoveryScore() +
            searchRecoveryScore() +
            if (!displaySetting.looksLikeDefaultIdentity()) 80 else 0 +
            assistants.size * 5 +
            quickMessages.size * 3 +
            mcpServers.size * 5 +
            modeInjections.size * 3 +
            lorebooks.size * 3

    private fun Settings.providerRecoveryScore(): Int {
        val defaultIds = DEFAULT_PROVIDERS.map { it.id }.toSet()
        return providers.sumOf { provider ->
            val userProviderBonus = if (provider.id !in defaultIds) 50 else 0
            val credentialBonus = if (provider.hasCredentialOrManagedAuth()) 100 else 0
            val enabledBonus = if (provider.enabled) 5 else 0
            userProviderBonus + credentialBonus + enabledBonus + provider.models.size
        }
    }

    private fun Settings.searchRecoveryScore(): Int {
        val serviceCount = searchServices.size * 10
        val enabledCount = searchEnabledServiceIds.size * 3
        val configuredServices = searchServices.count { service ->
            val encoded = json.encodeToString(service)
            encoded.contains("\"apiKey\":\"") && !encoded.contains("\"apiKey\":\"\"")
        } * 20
        return serviceCount + enabledCount + configuredServices
    }

    private fun ProviderSetting.hasCredentialOrManagedAuth(): Boolean = when (this) {
        is ProviderSetting.OpenAI ->
            apiKey.isNotBlank() || authMode != OpenAIAuthMode.API_KEY

        is ProviderSetting.Google ->
            apiKey.isNotBlank() ||
                privateKey.isNotBlank() ||
                serviceAccountEmail.isNotBlank() ||
                authMode != GoogleAuthMode.API_KEY

        is ProviderSetting.Claude ->
            apiKey.isNotBlank()
    }

    private fun DisplaySetting.looksLikeDefaultIdentity(): Boolean =
        userNickname.isBlank() && userAvatar == DisplaySetting().userAvatar

    private fun Uuid.takeIfValidModel(validModelIds: Set<Uuid>, fallback: Uuid): Uuid =
        if (this in validModelIds) this else fallback
}
