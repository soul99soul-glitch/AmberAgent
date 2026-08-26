package app.amber.core.settings.secret

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.ModelType
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.settings.LegacyAssistantProfile
import app.amber.core.settings.selectLegacyAssistantProfile
import app.amber.core.settings.canonicalizedForAmberAgent
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.WebDavConfig
import app.amber.core.settings.prefs.decodeJsonOrNull
import app.amber.core.settings.prefs.decodeSearchServicesDroppingLegacy
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.sync.s3.S3Config

private const val TAG = "SettingsSecretMigrator"
private val LEGACY_TTS_PROVIDERS_KEY = stringPreferencesKey("tts_providers")
private val LEGACY_SELECTED_TTS_PROVIDER_KEY = stringPreferencesKey("selected_tts_provider")

/**
 * P1-01 幂等迁移：把 Preferences DataStore 中的明文字段迁移进 SecretStore，
 * 设置里只留 reference + 掩码位。
 *
 * 步骤（严格按计划）：
 * 1. 先写 SecretStore（upsert，幂等）。
 * 2. 验证可读：每个新建 reference 都能读回真实值；任一读不回 → 中止，不写 DataStore。
 * 3. 再把 DataStore 中明文替换为掩码 + reference。
 * 4. 成功后持久化迁移版本标记；迁移中断后重跑是 no-op 或继续完成。
 * 失败不删旧值：写 SecretStore 失败的字段保持明文落盘（短期兼容旧明文字段）。
 */
class SettingsSecretMigrator(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
    private val redactor: SecretRedactor,
) {
    /**
     * 返回当前迁移版本。先幂等清理已下线的搜索类型；已迁移的其余密钥不重写。
     * 任何 legacy assistant 迁移或密钥迁移失败都返回 [MIGRATION_FAILED]，
     * 而不是返回旧版本，避免启动 rescue 把失败状态误当成成功。
     */
    suspend fun migrateIfNeeded(): Int {
        if (!migrateLegacyAssistantSettingsIfPresent()) {
            return MIGRATION_FAILED
        }
        if (!removeLegacySearchServicesIfPresent()) {
            return MIGRATION_FAILED
        }
        if (!removeLegacyTtsSettingsIfPresent()) {
            return MIGRATION_FAILED
        }
        if (secretStore.migrationVersion() >= MIGRATION_VERSION) {
            return secretStore.migrationVersion()
        }
        var changed = false
        var aborted = false
        try {
            dataStore.edit { p ->
                redactor.resetWriteFailures()
                // A present but malformed refs payload is a migration failure, not an
                // empty reference set. Keep the DataStore transaction untouched.
                val existingRefs = redactor.readRefsStrict(p)
                val out = mutableMapOf<String, SecretReference>()

                val providersJson = p[PreferencesKeys.PROVIDERS]
                val providers = if (providersJson == null) {
                    emptyList()
                } else {
                    providersJson.decodeJsonOrNull<List<ProviderSetting>>() ?: run {
                        Log.e(TAG, "Secret migration aborted; keeping unreadable provider settings")
                        aborted = true
                        return@edit
                    }
                }
                val redactedProviders = redactor.redactProviders(providers, existingRefs, out)
                if (providersJson != null && JsonInstant.encodeToString(redactedProviders) != providersJson) {
                    changed = true
                }

                val customHeadersJson = p[PreferencesKeys.AMBER_CUSTOM_HEADERS]
                val customHeaders = customHeadersJson?.decodeJsonOrNull<List<CustomHeader>>() ?: emptyList()
                val redactedCustomHeaders = redactor.redactCustomHeaders(customHeaders, existingRefs, out)
                if (
                    customHeadersJson != null &&
                    JsonInstant.encodeToString(redactedCustomHeaders) != customHeadersJson
                ) {
                    changed = true
                }

                val searchJson = p[PreferencesKeys.SEARCH_SERVICES]
                val searchServices = searchJson
                    ?.decodeSearchServicesDroppingLegacy()
                    ?.services
                    ?: emptyList()
                val redactedSearch = redactor.redactSearchServices(searchServices, existingRefs, out)
                if (searchJson != null && JsonInstant.encodeToString(redactedSearch) != searchJson) {
                    changed = true
                }

                val mcpJson = p[PreferencesKeys.MCP_SERVERS]
                val mcpServers = mcpJson?.decodeJsonOrNull<List<McpServerConfig>>() ?: emptyList()
                val redactedMcp = redactor.redactMcpServers(mcpServers, existingRefs, out)
                if (mcpJson != null && JsonInstant.encodeToString(redactedMcp) != mcpJson) {
                    changed = true
                }

                val webDavJson = p[PreferencesKeys.WEBDAV_CONFIG]
                val webDav = webDavJson?.decodeJsonOrNull<WebDavConfig>() ?: WebDavConfig()
                val redactedWebDav = redactor.redactWebDav(webDav, existingRefs, out)
                if (webDavJson != null && JsonInstant.encodeToString(redactedWebDav) != webDavJson) {
                    changed = true
                }

                val s3Json = p[PreferencesKeys.S3_CONFIG]
                val s3 = s3Json?.decodeJsonOrNull<S3Config>() ?: S3Config()
                val redactedS3 = redactor.redactS3(s3, existingRefs, out)
                if (s3Json != null && JsonInstant.encodeToString(redactedS3) != s3Json) {
                    changed = true
                }

                // 验证可读：任一新建 reference 读不回真实值 → 中止，旧明文原样保留
                val writeFailures = redactor.takeWriteFailures()
                val unreadable = out.values
                    .filter { it.descriptor().key !in existingRefs }
                    .filter { secretStore.read(it.descriptor()) == null }
                if (writeFailures.isNotEmpty() || unreadable.isNotEmpty()) {
                    Log.e(
                        TAG,
                        "Secret migration aborted: writeFailures=$writeFailures, unreadable=${unreadable.map { it.descriptor().key }}; keeping legacy plaintext"
                    )
                    changed = false
                    aborted = true
                    return@edit
                }

                if (changed) {
                    p[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(redactedProviders)
                    if (customHeadersJson != null) {
                        p[PreferencesKeys.AMBER_CUSTOM_HEADERS] = JsonInstant.encodeToString(redactedCustomHeaders)
                    }
                    p[PreferencesKeys.SEARCH_SERVICES] = JsonInstant.encodeToString(redactedSearch)
                    p[PreferencesKeys.MCP_SERVERS] = JsonInstant.encodeToString(redactedMcp)
                    p[PreferencesKeys.WEBDAV_CONFIG] = JsonInstant.encodeToString(redactedWebDav)
                    p[PreferencesKeys.S3_CONFIG] = JsonInstant.encodeToString(redactedS3)
                    redactor.writeRefs(p, out)
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "Secret migration aborted; keeping existing settings and references", error)
            return MIGRATION_FAILED
        }
        if (aborted) {
            // 失败不删旧值、不标记完成：下次启动重跑
            return MIGRATION_FAILED
        }
        if (changed) {
            // Validate the committed refs before any destructive orphan sweep or version mark.
            val refs = try {
                dataStore.data.first().let { redactor.readRefsStrict(it) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Secret migration aborted; committed refs are malformed", error)
                return MIGRATION_FAILED
            }
            secretStore.deleteOrphans(refs.values.map { it.descriptor() }.toSet())
            secretStore.markMigrated(MIGRATION_VERSION)
            Log.i(TAG, "Secret migration to version $MIGRATION_VERSION completed")
        } else {
            // DataStore 已是迁移后形态（或没有敏感字段）：仅补齐版本标记
            secretStore.markMigrated(MIGRATION_VERSION)
        }
        return secretStore.migrationVersion()
    }

    /**
     * One-way conversion of the legacy selected-id/list container into direct Amber keys.
     * The chosen profile is rehydrated before redaction so custom-header references survive.
     * New writes never recreate the old profile/list keys; any failure leaves every legacy
     * key intact so the next launch can retry safely.
     */
    private suspend fun migrateLegacyAssistantSettingsIfPresent(): Boolean {
        var succeeded = true
        var migrated = false
        try {
            dataStore.edit { p ->
                val rawCurrent = p[PreferencesKeys.AMBER_PROFILE]
                val currentProfile = rawCurrent?.decodeJsonOrNull<LegacyAssistantProfile>()
                val hasLegacyData = rawCurrent != null ||
                    p[PreferencesKeys.LEGACY_SELECTED_ASSISTANT] != null ||
                    p[PreferencesKeys.LEGACY_ASSISTANTS] != null ||
                    p[PreferencesKeys.LEGACY_ASSISTANT_TAGS] != null
                if (!hasLegacyData) return@edit

                if (rawCurrent != null && currentProfile == null) {
                    succeeded = false
                    Log.e(TAG, "Amber settings migration aborted; keeping malformed current assistant profile")
                    return@edit
                }

                // Do not interpret malformed refs as an empty set: a masked legacy header
                // must retain its exact owner/key reference for a safe retry.
                val existingRefs = redactor.readRefsStrict(p)
                val unreadableLegacyRefs = existingRefs.values
                    .filter { it.scope == "assistant" }
                    .filter { reference ->
                        runCatching { secretStore.read(reference.descriptor()) }.getOrNull() == null
                    }
                if (unreadableLegacyRefs.isNotEmpty()) {
                    succeeded = false
                    Log.e(
                        TAG,
                        "Amber settings migration aborted; keeping unreadable legacy secret references: " +
                            unreadableLegacyRefs.map { it.descriptor().key },
                    )
                    return@edit
                }
                val selected = if (currentProfile != null) {
                    val missingRefs = missingLegacyHeaderRefs(listOf(currentProfile), existingRefs)
                    if (missingRefs.isNotEmpty()) {
                        succeeded = false
                        Log.e(
                            TAG,
                            "Amber settings migration aborted; masked legacy headers have no matching refs: $missingRefs",
                        )
                        return@edit
                    }
                    redactor.rehydrateLegacyAssistantProfile(currentProfile, existingRefs)
                } else {
                    val legacyJson = p[PreferencesKeys.LEGACY_ASSISTANTS]
                    val decodedLegacy = legacyJson?.decodeJsonOrNull<List<LegacyAssistantProfile>>()
                    if (legacyJson != null && decodedLegacy == null) {
                        succeeded = false
                        Log.e(TAG, "Amber settings migration aborted; keeping malformed legacy assistant settings")
                        return@edit
                    }
                    val missingRefs = missingLegacyHeaderRefs(decodedLegacy.orEmpty(), existingRefs)
                    if (missingRefs.isNotEmpty()) {
                        succeeded = false
                        Log.e(
                            TAG,
                            "Amber settings migration aborted; masked legacy headers have no matching refs: $missingRefs",
                        )
                        return@edit
                    }
                    val legacyAssistants = decodedLegacy
                        ?.let { redactor.rehydrateLegacyAssistants(it, existingRefs) }
                        .orEmpty()
                    val selectedId = p[PreferencesKeys.LEGACY_SELECTED_ASSISTANT]
                        ?.let { raw -> runCatching { kotlin.uuid.Uuid.parse(raw) }.getOrNull() }
                    selectLegacyAssistantProfile(selectedId, legacyAssistants)
                }.canonicalizedForAmberAgent()

                redactor.resetWriteFailures()
                val profileRefs = mutableMapOf<String, SecretReference>()
                val redactedHeaders = redactor.redactCustomHeaders(
                    selected.customHeaders,
                    existingRefs,
                    profileRefs,
                )
                val writeFailures = redactor.takeWriteFailures()
                val unreadable = profileRefs.values
                    .filter { it.descriptor().key !in existingRefs }
                    .filter { secretStore.read(it.descriptor()) == null }
                if (writeFailures.isNotEmpty() || unreadable.isNotEmpty()) {
                    succeeded = false
                    Log.e(TAG, "Amber settings migration aborted; keeping legacy profile settings")
                    return@edit
                }

                writeLegacyAmberSettings(p, selected, redactedHeaders)
                val retainedRefs = existingRefs.filterValues { it.scope != "assistant" } + profileRefs
                redactor.writeRefs(p, retainedRefs)
                p.remove(PreferencesKeys.AMBER_PROFILE)
                p.remove(PreferencesKeys.LEGACY_SELECTED_ASSISTANT)
                p.remove(PreferencesKeys.LEGACY_ASSISTANTS)
                p.remove(PreferencesKeys.LEGACY_ASSISTANT_TAGS)
                migrated = true
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            succeeded = false
            Log.e(TAG, "Amber settings migration aborted; keeping legacy settings and references", error)
        }
        if (succeeded && migrated) {
            val active = try {
                dataStore.data.first().let { redactor.readRefsStrict(it) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Amber settings migration aborted; committed refs are malformed", error)
                return false
            }
            secretStore.deleteOrphans(active.values.map { it.descriptor() }.toSet())
        }
        return succeeded
    }

    private fun missingLegacyHeaderRefs(
        profiles: List<LegacyAssistantProfile>,
        refs: Map<String, SecretReference>,
    ): List<String> = profiles.flatMap { profile ->
        profile.customHeaders.mapNotNull { header ->
            if (!header.value.startsWith(SecretRedactor.MASK_STRING)) return@mapNotNull null
            val descriptor = SecretDescriptor(
                scope = "assistant",
                ownerId = profile.id.toString(),
                fieldName = "customHeader:${header.name}",
            )
            descriptor.key.takeUnless { it in refs }
        }
    }

    private fun writeLegacyAmberSettings(
        p: androidx.datastore.preferences.core.MutablePreferences,
        profile: LegacyAssistantProfile,
        customHeaders: List<CustomHeader>,
    ) {
        p[PreferencesKeys.AMBER_SYSTEM_PROMPT] = profile.systemPrompt
        profile.temperature?.let { p[PreferencesKeys.AMBER_TEMPERATURE] = it.toString() }
            ?: p.remove(PreferencesKeys.AMBER_TEMPERATURE)
        profile.topP?.let { p[PreferencesKeys.AMBER_TOP_P] = it.toString() }
            ?: p.remove(PreferencesKeys.AMBER_TOP_P)
        p[PreferencesKeys.AMBER_CONTEXT_MESSAGE_SIZE] = profile.contextMessageSize
        p[PreferencesKeys.AMBER_STREAM_OUTPUT] = profile.streamOutput
        p[PreferencesKeys.AMBER_MESSAGE_TEMPLATE] = profile.messageTemplate
        p[PreferencesKeys.AMBER_PRESET_MESSAGES] = JsonInstant.encodeToString(profile.presetMessages)
        p[PreferencesKeys.AMBER_REGEXES] = JsonInstant.encodeToString(profile.regexes)
        p[PreferencesKeys.AMBER_REASONING_LEVEL] = JsonInstant.encodeToString(profile.reasoningLevel)
        profile.maxTokens?.let { p[PreferencesKeys.AMBER_MAX_TOKENS] = it }
            ?: p.remove(PreferencesKeys.AMBER_MAX_TOKENS)
        p[PreferencesKeys.AMBER_CUSTOM_HEADERS] = JsonInstant.encodeToString(customHeaders)
        p[PreferencesKeys.AMBER_CUSTOM_BODIES] = JsonInstant.encodeToString(profile.customBodies)
        p[PreferencesKeys.AMBER_REMEMBERED_REASONING_LEVELS] =
            JsonInstant.encodeToString(profile.rememberedReasoningLevelsByModelId)
        p[PreferencesKeys.AMBER_ENABLED_SKILLS] = JsonInstant.encodeToString(profile.enabledSkills)
        p[PreferencesKeys.AMBER_ENABLED_MCP_SERVER_IDS] = JsonInstant.encodeToString(profile.mcpServers)
        p[PreferencesKeys.AMBER_ENABLED_MODE_INJECTION_IDS] = JsonInstant.encodeToString(profile.modeInjectionIds)
        p[PreferencesKeys.AMBER_ENABLED_LOREBOOK_IDS] = JsonInstant.encodeToString(profile.lorebookIds)
        // A profile-specific chat model wins over the existing direct selection.
        profile.chatModelId?.let { p[PreferencesKeys.SELECT_MODEL] = it.toString() }
        // Promote the legacy image model only when the provider graph confirms it is an image model.
        val imageModelId = profile.legacyImageGenerationModelId
        if (imageModelId != null) {
            val providers = p[PreferencesKeys.PROVIDERS]?.decodeJsonOrNull<List<ProviderSetting>>()
                .orEmpty()
            val isImage = providers.any { provider ->
                provider.models.any { model -> model.id == imageModelId && model.type == ModelType.IMAGE }
            }
            if (isImage) p[PreferencesKeys.IMAGE_GENERATION_MODEL] = imageModelId.toString()
        }
    }

    /**
     * Retire the removed Amber search service directly in persisted JSON. Its API key is
     * intentionally discarded, never decoded into or copied to another provider. Presence
     * of the legacy discriminator is the idempotent migration marker.
     */
    private suspend fun removeLegacySearchServicesIfPresent(): Boolean {
        var removed = false
        try {
            dataStore.edit { p ->
                val raw = p[PreferencesKeys.SEARCH_SERVICES] ?: return@edit
                val decoded = raw.decodeSearchServicesDroppingLegacy() ?: return@edit
                if (!decoded.removedLegacy) return@edit

                // Parse before touching the retired search payload so malformed refs
                // cannot be replaced with a filtered empty map.
                val existingRefs = redactor.readRefsStrict(p)

                removed = true
                val selectedBefore = p[PreferencesKeys.SEARCH_SELECTED] ?: 0
                val enabledBefore = p[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS]
                    ?.decodeJsonOrNull<List<kotlin.uuid.Uuid>>()
                    .orEmpty()
                val retainedEnabled = enabledBefore.filterNot { it in decoded.legacyServiceIds }
                p[PreferencesKeys.SEARCH_SERVICES] = JsonInstant.encodeToString(decoded.services)
                p[PreferencesKeys.SEARCH_SELECTED] = decoded.adjustedSelected(selectedBefore)
                if (p[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS] != null) {
                    p[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS] = JsonInstant.encodeToString(retainedEnabled)
                }
                if (
                    selectedBefore in decoded.legacyIndices ||
                    (enabledBefore.any { it in decoded.legacyServiceIds } && retainedEnabled.isEmpty())
                ) {
                    p[PreferencesKeys.ENABLE_WEB_SEARCH] = false
                }

                val legacyOwnerIds = decoded.legacyServiceIds.mapTo(mutableSetOf()) { it.toString() }
                val retainedRefs = existingRefs.filterValues { ref ->
                    ref.scope != "search" || ref.ownerId !in legacyOwnerIds
                }
                redactor.writeRefs(p, retainedRefs)
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "Retired search cleanup aborted; keeping settings and references", error)
            return false
        }
        if (removed) {
            val active = try {
                dataStore.data.first().let { redactor.readRefsStrict(it) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Retired search cleanup aborted; committed refs are malformed", error)
                return false
            }
            secretStore.deleteOrphans(active.values.map { it.descriptor() }.toSet())
            Log.i(TAG, "Removed retired amber_agent search configuration; a new service must be configured")
        }
        return true
    }

    /**
     * Retire the removed TTS settings without decoding or rehydrating their
     * provider payload. Old credentials are detached from the reference map
     * and then removed by the existing orphan cleanup path.
     */
    private suspend fun removeLegacyTtsSettingsIfPresent(): Boolean {
        var removed = false
        try {
            dataStore.edit { p ->
                // Parse before removing retired keys or detaching their references.
                val refs = redactor.readRefsStrict(p)
                val retained = refs.filterValues { it.scope != "tts" }
                val hasLegacyKeys =
                    p[LEGACY_TTS_PROVIDERS_KEY] != null ||
                        p[LEGACY_SELECTED_TTS_PROVIDER_KEY] != null
                if (!hasLegacyKeys && retained.size == refs.size) return@edit

                if (p[LEGACY_TTS_PROVIDERS_KEY] != null) {
                    p.remove(LEGACY_TTS_PROVIDERS_KEY)
                    removed = true
                }
                if (p[LEGACY_SELECTED_TTS_PROVIDER_KEY] != null) {
                    p.remove(LEGACY_SELECTED_TTS_PROVIDER_KEY)
                    removed = true
                }
                if (retained.size != refs.size) {
                    redactor.writeRefs(p, retained)
                    removed = true
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "Retired TTS cleanup aborted; keeping settings and references", error)
            return false
        }
        if (removed) {
            val active = try {
                dataStore.data.first().let { redactor.readRefsStrict(it) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Retired TTS cleanup aborted; committed refs are malformed", error)
                return false
            }
            secretStore.deleteOrphans(active.values.map { it.descriptor() }.toSet())
            Log.i(TAG, "Removed retired TTS settings and secrets")
        }
        return true
    }

    companion object {
        const val MIGRATION_VERSION = 1
        const val MIGRATION_FAILED = -1
    }
}
