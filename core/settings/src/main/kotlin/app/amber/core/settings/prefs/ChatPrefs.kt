package app.amber.core.settings.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.amber.core.infra.AppScope
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.CustomHeader
import app.amber.ai.ui.UIMessage
import app.amber.core.ai.prompts.DEFAULT_COMPRESS_PROMPT
import app.amber.core.ai.prompts.DEFAULT_OCR_PROMPT
import app.amber.core.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import app.amber.core.ai.prompts.DEFAULT_TITLE_PROMPT
import app.amber.core.settings.DEFAULT_AUTO_MODEL_ID
import app.amber.core.settings.DEFAULT_AMBER_SYSTEM_PROMPT
import app.amber.core.settings.ModelGroupSessionDefault
import app.amber.core.settings.PreferencesKeys
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.toMutableStateFlow
import app.amber.core.model.AssistantRegex
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import kotlin.uuid.Uuid

data class ChatPrefsData(
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = DEFAULT_AUTO_MODEL_ID,
    val titleModelId: Uuid = DEFAULT_AUTO_MODEL_ID,
    val suggestionModelId: Uuid = DEFAULT_AUTO_MODEL_ID,
    val compressModelId: Uuid = DEFAULT_AUTO_MODEL_ID,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val ocrModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val modelGroupSessionDefaults: List<ModelGroupSessionDefault> = emptyList(),
    val systemPrompt: String = DEFAULT_AMBER_SYSTEM_PROMPT,
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageSize: Int = 0,
    val streamOutput: Boolean = true,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val rememberedReasoningLevelsByModelId: Map<String, ReasoningLevel> = emptyMap(),
)

class ChatPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
    secretStore: SecretStore? = null,
) {
    private val redactor = secretStore?.let(::SecretRedactor)
    internal val rawFlow: Flow<ChatPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()

    val flow: StateFlow<ChatPrefsData> = rawFlow
        .toMutableStateFlow(scope, ChatPrefsData())

    suspend fun update(transform: (ChatPrefsData) -> ChatPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): ChatPrefsData {
        val refs = redactor?.readRefs(p).orEmpty()
        val customHeaders = p[PreferencesKeys.AMBER_CUSTOM_HEADERS]?.let {
            it.decodeJsonOrNull<List<CustomHeader>>()
        } ?: emptyList()
        return ChatPrefsData(
        enableWebSearch = p[PreferencesKeys.ENABLE_WEB_SEARCH] == true,
        favoriteModels = p[PreferencesKeys.FAVORITE_MODELS]?.let {
            it.decodeJsonOrNull<List<Uuid>>()
        } ?: emptyList(),
        chatModelId = p[PreferencesKeys.SELECT_MODEL]?.let { it.parseUuidOrNull() }
            ?: DEFAULT_AUTO_MODEL_ID,
        titleModelId = p[PreferencesKeys.TITLE_MODEL]?.let { it.parseUuidOrNull() }
            ?: DEFAULT_AUTO_MODEL_ID,
        suggestionModelId = p[PreferencesKeys.SUGGESTION_MODEL]?.let { it.parseUuidOrNull() }
            ?: DEFAULT_AUTO_MODEL_ID,
        compressModelId = p[PreferencesKeys.COMPRESS_MODEL]?.let { it.parseUuidOrNull() }
            ?: DEFAULT_AUTO_MODEL_ID,
        imageGenerationModelId = p[PreferencesKeys.IMAGE_GENERATION_MODEL]
            ?.let { it.parseUuidOrNull() } ?: Uuid.random(),
        ocrModelId = p[PreferencesKeys.OCR_MODEL]?.let { it.parseUuidOrNull() } ?: Uuid.random(),
        titlePrompt = p[PreferencesKeys.TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
        suggestionPrompt = p[PreferencesKeys.SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
        ocrPrompt = p[PreferencesKeys.OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
        compressPrompt = p[PreferencesKeys.COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
        modelGroupSessionDefaults = p[PreferencesKeys.MODEL_GROUP_SESSION_DEFAULTS]?.let {
            it.decodeJsonOrNull<List<ModelGroupSessionDefault>>()
        } ?: emptyList(),
            systemPrompt = p[PreferencesKeys.AMBER_SYSTEM_PROMPT] ?: DEFAULT_AMBER_SYSTEM_PROMPT,
            temperature = p[PreferencesKeys.AMBER_TEMPERATURE]?.toFloatOrNull(),
            topP = p[PreferencesKeys.AMBER_TOP_P]?.toFloatOrNull(),
            contextMessageSize = p[PreferencesKeys.AMBER_CONTEXT_MESSAGE_SIZE] ?: 0,
            streamOutput = p[PreferencesKeys.AMBER_STREAM_OUTPUT] ?: true,
            messageTemplate = p[PreferencesKeys.AMBER_MESSAGE_TEMPLATE] ?: "{{ message }}",
            presetMessages = p[PreferencesKeys.AMBER_PRESET_MESSAGES]?.let {
                it.decodeJsonOrNull<List<UIMessage>>()
            } ?: emptyList(),
            regexes = p[PreferencesKeys.AMBER_REGEXES]?.let {
                it.decodeJsonOrNull<List<AssistantRegex>>()
            } ?: emptyList(),
            reasoningLevel = p[PreferencesKeys.AMBER_REASONING_LEVEL]?.let {
                it.decodeJsonOrNull<ReasoningLevel>()
            } ?: ReasoningLevel.AUTO,
            maxTokens = p[PreferencesKeys.AMBER_MAX_TOKENS],
            customHeaders = redactor?.rehydrateCustomHeaders(customHeaders, refs) ?: customHeaders,
            customBodies = p[PreferencesKeys.AMBER_CUSTOM_BODIES]?.let {
                it.decodeJsonOrNull<List<CustomBody>>()
            } ?: emptyList(),
            rememberedReasoningLevelsByModelId = p[PreferencesKeys.AMBER_REMEMBERED_REASONING_LEVELS]
                ?.let { it.decodeJsonOrNull<Map<String, ReasoningLevel>>() }
                ?: emptyMap(),
        )
    }

    private fun writeTo(p: MutablePreferences, data: ChatPrefsData) {
        val existingRefs = redactor?.readRefsStrict(p).orEmpty()
        val newRefs = mutableMapOf<String, app.amber.core.settings.secret.SecretReference>()
        val persistedHeaders = redactor?.redactCustomHeaders(
            data.customHeaders,
            existingRefs,
            newRefs,
        ) ?: data.customHeaders
        p[PreferencesKeys.ENABLE_WEB_SEARCH] = data.enableWebSearch
        p[PreferencesKeys.FAVORITE_MODELS] = JsonInstant.encodeToString(data.favoriteModels)
        p[PreferencesKeys.SELECT_MODEL] = data.chatModelId.toString()
        p[PreferencesKeys.TITLE_MODEL] = data.titleModelId.toString()
        p[PreferencesKeys.SUGGESTION_MODEL] = data.suggestionModelId.toString()
        p[PreferencesKeys.COMPRESS_MODEL] = data.compressModelId.toString()
        p[PreferencesKeys.IMAGE_GENERATION_MODEL] = data.imageGenerationModelId.toString()
        p[PreferencesKeys.OCR_MODEL] = data.ocrModelId.toString()
        p[PreferencesKeys.TITLE_PROMPT] = data.titlePrompt
        p[PreferencesKeys.SUGGESTION_PROMPT] = data.suggestionPrompt
        p[PreferencesKeys.OCR_PROMPT] = data.ocrPrompt
        p[PreferencesKeys.COMPRESS_PROMPT] = data.compressPrompt
        p[PreferencesKeys.MODEL_GROUP_SESSION_DEFAULTS] =
            JsonInstant.encodeToString(data.modelGroupSessionDefaults)
        p[PreferencesKeys.AMBER_SYSTEM_PROMPT] = data.systemPrompt
        data.temperature?.let { p[PreferencesKeys.AMBER_TEMPERATURE] = it.toString() }
            ?: p.remove(PreferencesKeys.AMBER_TEMPERATURE)
        data.topP?.let { p[PreferencesKeys.AMBER_TOP_P] = it.toString() }
            ?: p.remove(PreferencesKeys.AMBER_TOP_P)
        p[PreferencesKeys.AMBER_CONTEXT_MESSAGE_SIZE] = data.contextMessageSize
        p[PreferencesKeys.AMBER_STREAM_OUTPUT] = data.streamOutput
        p[PreferencesKeys.AMBER_MESSAGE_TEMPLATE] = data.messageTemplate
        p[PreferencesKeys.AMBER_PRESET_MESSAGES] = JsonInstant.encodeToString(data.presetMessages)
        p[PreferencesKeys.AMBER_REGEXES] = JsonInstant.encodeToString(data.regexes)
        p[PreferencesKeys.AMBER_REASONING_LEVEL] = JsonInstant.encodeToString(data.reasoningLevel)
        data.maxTokens?.let { p[PreferencesKeys.AMBER_MAX_TOKENS] = it }
            ?: p.remove(PreferencesKeys.AMBER_MAX_TOKENS)
        p[PreferencesKeys.AMBER_CUSTOM_HEADERS] = JsonInstant.encodeToString(persistedHeaders)
        p[PreferencesKeys.AMBER_CUSTOM_BODIES] = JsonInstant.encodeToString(data.customBodies)
        p[PreferencesKeys.AMBER_REMEMBERED_REASONING_LEVELS] =
            JsonInstant.encodeToString(data.rememberedReasoningLevelsByModelId)
        if (redactor != null) {
            redactor.writeRefs(p, existingRefs + newRefs)
        }
    }
}
