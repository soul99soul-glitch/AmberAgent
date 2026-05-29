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
import app.amber.core.ai.prompts.DEFAULT_COMPRESS_PROMPT
import app.amber.core.ai.prompts.DEFAULT_OCR_PROMPT
import app.amber.core.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import app.amber.core.ai.prompts.DEFAULT_TITLE_PROMPT
import app.amber.core.settings.DEFAULT_AUTO_MODEL_ID
import app.amber.core.settings.ModelGroupSessionDefault
import app.amber.core.settings.PreferencesKeys
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.toMutableStateFlow
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
)

class ChatPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
) {
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

    private fun readFrom(p: Preferences): ChatPrefsData = ChatPrefsData(
        enableWebSearch = p[PreferencesKeys.ENABLE_WEB_SEARCH] == true,
        favoriteModels = p[PreferencesKeys.FAVORITE_MODELS]?.let {
            JsonInstant.decodeFromString<List<Uuid>>(it)
        } ?: emptyList(),
        chatModelId = p[PreferencesKeys.SELECT_MODEL]?.let { Uuid.parse(it) }
            ?: DEFAULT_AUTO_MODEL_ID,
        titleModelId = p[PreferencesKeys.TITLE_MODEL]?.let { Uuid.parse(it) }
            ?: DEFAULT_AUTO_MODEL_ID,
        suggestionModelId = p[PreferencesKeys.SUGGESTION_MODEL]?.let { Uuid.parse(it) }
            ?: DEFAULT_AUTO_MODEL_ID,
        compressModelId = p[PreferencesKeys.COMPRESS_MODEL]?.let { Uuid.parse(it) }
            ?: DEFAULT_AUTO_MODEL_ID,
        imageGenerationModelId = p[PreferencesKeys.IMAGE_GENERATION_MODEL]
            ?.let { Uuid.parse(it) } ?: Uuid.random(),
        ocrModelId = p[PreferencesKeys.OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
        titlePrompt = p[PreferencesKeys.TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
        suggestionPrompt = p[PreferencesKeys.SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
        ocrPrompt = p[PreferencesKeys.OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
        compressPrompt = p[PreferencesKeys.COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
        modelGroupSessionDefaults = p[PreferencesKeys.MODEL_GROUP_SESSION_DEFAULTS]?.let {
            JsonInstant.decodeFromString<List<ModelGroupSessionDefault>>(it)
        } ?: emptyList(),
    )

    private fun writeTo(p: MutablePreferences, data: ChatPrefsData) {
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
    }
}
