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
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.toMutableStateFlow
import app.amber.search.SearchCommonOptions
import app.amber.search.SearchServiceOptions
import kotlin.uuid.Uuid

data class SearchPrefsData(
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val searchEnabledServiceIds: List<Uuid> = emptyList(),
    val searchBuiltinDuckDuckGoEnabled: Boolean = true,
    val searchBuiltinBingEnabled: Boolean = true,
    val searchBuiltinJinaEnabled: Boolean = true,
    val searchBuiltinWikipediaEnabled: Boolean = true,
    val searchBuiltinHackerNewsEnabled: Boolean = true,
    val searchGoogleWebViewFallbackEnabled: Boolean = true,
    val retiredServiceRequiresReconfiguration: Boolean = false,
)

class SearchPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
    private val secretStore: SecretStore,
) {
    private val redactor = SecretRedactor(secretStore)

    internal val rawFlow: Flow<SearchPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()

    val flow: StateFlow<SearchPrefsData> = rawFlow
        .toMutableStateFlow(scope, SearchPrefsData())

    suspend fun update(transform: (SearchPrefsData) -> SearchPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): SearchPrefsData {
        val refs = redactor.readRefs(p)
        val decodedServices = p[PreferencesKeys.SEARCH_SERVICES]
            ?.decodeSearchServicesDroppingLegacy()
        val storedSelected = p[PreferencesKeys.SEARCH_SELECTED] ?: 0
        val storedEnabled = p[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS]?.let {
            it.decodeJsonOrNull<List<Uuid>>()
        }.orEmpty()
        val retainedEnabled = storedEnabled.filterNot {
            it in decodedServices?.legacyServiceIds.orEmpty()
        }
        return SearchPrefsData(
            searchServices = decodedServices?.services?.let {
                redactor.rehydrateSearchServices(it, refs)
            } ?: listOf(SearchServiceOptions.DEFAULT),
            searchCommonOptions = (p[PreferencesKeys.SEARCH_COMMON]?.let {
                it.decodeJsonOrNull<SearchCommonOptions>()
            } ?: SearchCommonOptions()).let { options ->
                options.copy(resultSize = options.resultSize.coerceIn(1, 30))
            },
            searchServiceSelected = decodedServices?.adjustedSelected(storedSelected) ?: storedSelected,
            searchEnabledServiceIds = retainedEnabled,
            searchBuiltinDuckDuckGoEnabled = p[PreferencesKeys.SEARCH_BUILTIN_DUCKDUCKGO_ENABLED] != false,
            searchBuiltinBingEnabled = p[PreferencesKeys.SEARCH_BUILTIN_BING_ENABLED] != false,
            searchBuiltinJinaEnabled = p[PreferencesKeys.SEARCH_BUILTIN_JINA_ENABLED] != false,
            searchBuiltinWikipediaEnabled = p[PreferencesKeys.SEARCH_BUILTIN_WIKIPEDIA_ENABLED] != false,
            searchBuiltinHackerNewsEnabled = p[PreferencesKeys.SEARCH_BUILTIN_HACKERNEWS_ENABLED] != false,
            searchGoogleWebViewFallbackEnabled = p[PreferencesKeys.SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED] != false,
            retiredServiceRequiresReconfiguration = decodedServices?.let { decoded ->
                decoded.removedLegacy && (
                    storedSelected in decoded.legacyIndices ||
                        (storedEnabled.any { it in decoded.legacyServiceIds } && retainedEnabled.isEmpty())
                    )
            } == true,
        )
    }

    private fun writeTo(p: MutablePreferences, data: SearchPrefsData) {
        // P1-01: redaction 由 SettingsAggregator.writeSettings 统一执行
        p[PreferencesKeys.SEARCH_SERVICES] = JsonInstant.encodeToString(data.searchServices)
        p[PreferencesKeys.SEARCH_COMMON] = JsonInstant.encodeToString(
            data.searchCommonOptions.copy(resultSize = data.searchCommonOptions.resultSize.coerceIn(1, 30))
        )
        p[PreferencesKeys.SEARCH_SELECTED] = data.searchServiceSelected
        p[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS] =
            JsonInstant.encodeToString(data.searchEnabledServiceIds)
        p[PreferencesKeys.SEARCH_BUILTIN_DUCKDUCKGO_ENABLED] = data.searchBuiltinDuckDuckGoEnabled
        p[PreferencesKeys.SEARCH_BUILTIN_BING_ENABLED] = data.searchBuiltinBingEnabled
        p[PreferencesKeys.SEARCH_BUILTIN_JINA_ENABLED] = data.searchBuiltinJinaEnabled
        p[PreferencesKeys.SEARCH_BUILTIN_WIKIPEDIA_ENABLED] = data.searchBuiltinWikipediaEnabled
        p[PreferencesKeys.SEARCH_BUILTIN_HACKERNEWS_ENABLED] = data.searchBuiltinHackerNewsEnabled
        p[PreferencesKeys.SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED] =
            data.searchGoogleWebViewFallbackEnabled
    }
}
