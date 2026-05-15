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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
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
)

class SearchPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
) {
    val flow: StateFlow<SearchPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, SearchPrefsData())

    suspend fun update(transform: (SearchPrefsData) -> SearchPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): SearchPrefsData = SearchPrefsData(
        searchServices = p[SettingsStore.SEARCH_SERVICES]?.let {
            JsonInstant.decodeFromString<List<SearchServiceOptions>>(it)
        } ?: listOf(SearchServiceOptions.DEFAULT),
        searchCommonOptions = p[SettingsStore.SEARCH_COMMON]?.let {
            JsonInstant.decodeFromString<SearchCommonOptions>(it)
        } ?: SearchCommonOptions(),
        searchServiceSelected = p[SettingsStore.SEARCH_SELECTED] ?: 0,
        searchEnabledServiceIds = p[SettingsStore.SEARCH_ENABLED_SERVICE_IDS]?.let {
            JsonInstant.decodeFromString<List<Uuid>>(it)
        } ?: emptyList(),
        searchBuiltinDuckDuckGoEnabled = p[SettingsStore.SEARCH_BUILTIN_DUCKDUCKGO_ENABLED] != false,
        searchBuiltinBingEnabled = p[SettingsStore.SEARCH_BUILTIN_BING_ENABLED] != false,
        searchBuiltinJinaEnabled = p[SettingsStore.SEARCH_BUILTIN_JINA_ENABLED] != false,
        searchBuiltinWikipediaEnabled = p[SettingsStore.SEARCH_BUILTIN_WIKIPEDIA_ENABLED] != false,
        searchBuiltinHackerNewsEnabled = p[SettingsStore.SEARCH_BUILTIN_HACKERNEWS_ENABLED] != false,
        searchGoogleWebViewFallbackEnabled = p[SettingsStore.SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED] != false,
    )

    private fun writeTo(p: MutablePreferences, data: SearchPrefsData) {
        p[SettingsStore.SEARCH_SERVICES] = JsonInstant.encodeToString(data.searchServices)
        p[SettingsStore.SEARCH_COMMON] = JsonInstant.encodeToString(data.searchCommonOptions)
        p[SettingsStore.SEARCH_SELECTED] = data.searchServiceSelected
        p[SettingsStore.SEARCH_ENABLED_SERVICE_IDS] =
            JsonInstant.encodeToString(data.searchEnabledServiceIds)
        p[SettingsStore.SEARCH_BUILTIN_DUCKDUCKGO_ENABLED] = data.searchBuiltinDuckDuckGoEnabled
        p[SettingsStore.SEARCH_BUILTIN_BING_ENABLED] = data.searchBuiltinBingEnabled
        p[SettingsStore.SEARCH_BUILTIN_JINA_ENABLED] = data.searchBuiltinJinaEnabled
        p[SettingsStore.SEARCH_BUILTIN_WIKIPEDIA_ENABLED] = data.searchBuiltinWikipediaEnabled
        p[SettingsStore.SEARCH_BUILTIN_HACKERNEWS_ENABLED] = data.searchBuiltinHackerNewsEnabled
        p[SettingsStore.SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED] =
            data.searchGoogleWebViewFallbackEnabled
    }
}
