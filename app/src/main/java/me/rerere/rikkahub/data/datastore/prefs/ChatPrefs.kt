package me.rerere.rikkahub.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.ModelGroupSessionDefault
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import kotlin.uuid.Uuid

data class ChatPrefsData(
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = DEFAULT_AUTO_MODEL_ID,
    val titleModelId: Uuid = DEFAULT_AUTO_MODEL_ID,
    val translateModeId: Uuid = DEFAULT_AUTO_MODEL_ID,
    val suggestionModelId: Uuid = DEFAULT_AUTO_MODEL_ID,
    val compressModelId: Uuid = DEFAULT_AUTO_MODEL_ID,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val ocrModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val modelGroupSessionDefaults: List<ModelGroupSessionDefault> = emptyList(),
)

class ChatPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
) {
    val flow: StateFlow<ChatPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, ChatPrefsData())

    suspend fun update(transform: (ChatPrefsData) -> ChatPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): ChatPrefsData = ChatPrefsData(
        enableWebSearch = p[SettingsStore.ENABLE_WEB_SEARCH] == true,
        favoriteModels = p[SettingsStore.FAVORITE_MODELS]?.let {
            JsonInstant.decodeFromString<List<Uuid>>(it)
        } ?: emptyList(),
        chatModelId = p[SettingsStore.SELECT_MODEL]?.let { Uuid.parse(it) }
            ?: DEFAULT_AUTO_MODEL_ID,
        titleModelId = p[SettingsStore.TITLE_MODEL]?.let { Uuid.parse(it) }
            ?: DEFAULT_AUTO_MODEL_ID,
        translateModeId = p[SettingsStore.TRANSLATE_MODEL]?.let { Uuid.parse(it) }
            ?: DEFAULT_AUTO_MODEL_ID,
        suggestionModelId = p[SettingsStore.SUGGESTION_MODEL]?.let { Uuid.parse(it) }
            ?: DEFAULT_AUTO_MODEL_ID,
        compressModelId = p[SettingsStore.COMPRESS_MODEL]?.let { Uuid.parse(it) }
            ?: DEFAULT_AUTO_MODEL_ID,
        imageGenerationModelId = p[SettingsStore.IMAGE_GENERATION_MODEL]
            ?.let { Uuid.parse(it) } ?: Uuid.random(),
        ocrModelId = p[SettingsStore.OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
        titlePrompt = p[SettingsStore.TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
        translatePrompt = p[SettingsStore.TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
        translateThinkingBudget = p[SettingsStore.TRANSLATE_THINKING_BUDGET] ?: 0,
        suggestionPrompt = p[SettingsStore.SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
        ocrPrompt = p[SettingsStore.OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
        compressPrompt = p[SettingsStore.COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
        modelGroupSessionDefaults = p[SettingsStore.MODEL_GROUP_SESSION_DEFAULTS]?.let {
            JsonInstant.decodeFromString<List<ModelGroupSessionDefault>>(it)
        } ?: emptyList(),
    )

    private fun writeTo(p: MutablePreferences, data: ChatPrefsData) {
        p[SettingsStore.ENABLE_WEB_SEARCH] = data.enableWebSearch
        p[SettingsStore.FAVORITE_MODELS] = JsonInstant.encodeToString(data.favoriteModels)
        p[SettingsStore.SELECT_MODEL] = data.chatModelId.toString()
        p[SettingsStore.TITLE_MODEL] = data.titleModelId.toString()
        p[SettingsStore.TRANSLATE_MODEL] = data.translateModeId.toString()
        p[SettingsStore.SUGGESTION_MODEL] = data.suggestionModelId.toString()
        p[SettingsStore.COMPRESS_MODEL] = data.compressModelId.toString()
        p[SettingsStore.IMAGE_GENERATION_MODEL] = data.imageGenerationModelId.toString()
        p[SettingsStore.OCR_MODEL] = data.ocrModelId.toString()
        p[SettingsStore.TITLE_PROMPT] = data.titlePrompt
        p[SettingsStore.TRANSLATION_PROMPT] = data.translatePrompt
        p[SettingsStore.TRANSLATE_THINKING_BUDGET] = data.translateThinkingBudget
        p[SettingsStore.SUGGESTION_PROMPT] = data.suggestionPrompt
        p[SettingsStore.OCR_PROMPT] = data.ocrPrompt
        p[SettingsStore.COMPRESS_PROMPT] = data.compressPrompt
        p[SettingsStore.MODEL_GROUP_SESSION_DEFAULTS] =
            JsonInstant.encodeToString(data.modelGroupSessionDefaults)
    }
}
