package me.rerere.rikkahub.data.datastore.prefs

import android.content.Context
import androidx.datastore.core.IOException
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
import me.rerere.rikkahub.data.datastore.settingsStore
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
    context: Context,
    scope: AppScope,
) {
    private val dataStore = context.settingsStore

    val flow: StateFlow<ChatPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
            ChatPrefsData(
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
        }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, ChatPrefsData())

    suspend fun update(transform: (ChatPrefsData) -> ChatPrefsData) {
        val current = flow.value
        val next = transform(current)
        if (next == current) return
        dataStore.edit { p ->
            p[SettingsStore.ENABLE_WEB_SEARCH] = next.enableWebSearch
            p[SettingsStore.FAVORITE_MODELS] = JsonInstant.encodeToString(next.favoriteModels)
            p[SettingsStore.SELECT_MODEL] = next.chatModelId.toString()
            p[SettingsStore.TITLE_MODEL] = next.titleModelId.toString()
            p[SettingsStore.TRANSLATE_MODEL] = next.translateModeId.toString()
            p[SettingsStore.SUGGESTION_MODEL] = next.suggestionModelId.toString()
            p[SettingsStore.COMPRESS_MODEL] = next.compressModelId.toString()
            p[SettingsStore.IMAGE_GENERATION_MODEL] = next.imageGenerationModelId.toString()
            p[SettingsStore.OCR_MODEL] = next.ocrModelId.toString()
            p[SettingsStore.TITLE_PROMPT] = next.titlePrompt
            p[SettingsStore.TRANSLATION_PROMPT] = next.translatePrompt
            p[SettingsStore.TRANSLATE_THINKING_BUDGET] = next.translateThinkingBudget
            p[SettingsStore.SUGGESTION_PROMPT] = next.suggestionPrompt
            p[SettingsStore.OCR_PROMPT] = next.ocrPrompt
            p[SettingsStore.COMPRESS_PROMPT] = next.compressPrompt
            p[SettingsStore.MODEL_GROUP_SESSION_DEFAULTS] =
                JsonInstant.encodeToString(next.modelGroupSessionDefaults)
        }
    }
}
