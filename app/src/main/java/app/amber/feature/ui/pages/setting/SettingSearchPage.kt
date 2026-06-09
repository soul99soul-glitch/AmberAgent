package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import app.amber.agent.R
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.core.utils.plus
import app.amber.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingSearchPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var editingService by remember { mutableStateOf<SearchServiceEditorTarget?>(null) }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_page_search_title),
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            editingService = SearchServiceEditorTarget(
                                index = null,
                                service = SearchServiceOptions.BingLocalOptions(),
                            )
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.setting_page_search_add_provider),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspaceColors().canvas,
    ) { contentPadding ->
        fun moveSearchService(fromIndex: Int, toIndex: Int) {
            if (fromIndex !in settings.searchServices.indices || toIndex !in settings.searchServices.indices) return
            val selectedServiceId = settings.searchServices.getOrNull(settings.searchServiceSelected)?.id
            val newServices = settings.searchServices.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            vm.updateSettings(
                settings.copy(
                    searchServices = newServices,
                    searchServiceSelected = resolveSelectedSearchIndex(newServices, selectedServiceId),
                )
            )
        }

        fun deleteSearchService(target: SearchServiceEditorTarget) {
            if (target.index == null || settings.searchServices.size <= 1) return
            val currentIndex = settings.searchServices.indexOfFirst { it.id == target.service.id }
                .takeIf { it >= 0 }
                ?: target.index
            if (currentIndex !in settings.searchServices.indices) return
            val selectedServiceId = settings.searchServices.getOrNull(settings.searchServiceSelected)?.id
            val newServices = settings.searchServices.toMutableList()
            val deleted = newServices.removeAt(currentIndex)
            vm.updateSettings(
                settings.copy(
                    searchServices = newServices,
                    searchServiceSelected = resolveSelectedSearchIndex(
                        services = newServices,
                        selectedServiceId = selectedServiceId.takeUnless { it == deleted.id },
                    ),
                    searchEnabledServiceIds = settings.searchEnabledServiceIds.filterNot { it == deleted.id },
                )
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            state = lazyListState,
        ) {
            item("agent_search_enable") {
                SearchHeroCard(
                    enabled = settings.enableWebSearch,
                    enabledCount = settings.searchServices.count { it.id in settings.searchEnabledServiceIds } +
                        listOf(
                            settings.searchBuiltinJinaEnabled,
                            settings.searchBuiltinDuckDuckGoEnabled,
                            settings.searchBuiltinBingEnabled,
                            settings.searchBuiltinWikipediaEnabled,
                            settings.searchBuiltinHackerNewsEnabled,
                            settings.searchGoogleWebViewFallbackEnabled,
                        ).count { it },
                    serviceCount = settings.searchServices.size + 6,
                    onCheckedChange = { enabled ->
                        vm.updateSettings(settings.copy(enableWebSearch = enabled))
                    },
                )
            }

            item("service_list") {
                SearchServiceListCard(
                    jinaEnabled = settings.searchBuiltinJinaEnabled,
                    duckDuckGoEnabled = settings.searchBuiltinDuckDuckGoEnabled,
                    bingEnabled = settings.searchBuiltinBingEnabled,
                    wikipediaEnabled = settings.searchBuiltinWikipediaEnabled,
                    hackerNewsEnabled = settings.searchBuiltinHackerNewsEnabled,
                    googleWebViewFallbackEnabled = settings.searchGoogleWebViewFallbackEnabled,
                    onJinaEnabledChange = { enabled ->
                        vm.updateSettings(settings.copy(searchBuiltinJinaEnabled = enabled))
                    },
                    onDuckDuckGoEnabledChange = { enabled ->
                        vm.updateSettings(settings.copy(searchBuiltinDuckDuckGoEnabled = enabled))
                    },
                    onBingEnabledChange = { enabled ->
                        vm.updateSettings(settings.copy(searchBuiltinBingEnabled = enabled))
                    },
                    onWikipediaEnabledChange = { enabled ->
                        vm.updateSettings(settings.copy(searchBuiltinWikipediaEnabled = enabled))
                    },
                    onHackerNewsEnabledChange = { enabled ->
                        vm.updateSettings(settings.copy(searchBuiltinHackerNewsEnabled = enabled))
                    },
                    onGoogleWebViewFallbackEnabledChange = { enabled ->
                        vm.updateSettings(settings.copy(searchGoogleWebViewFallbackEnabled = enabled))
                    },
                    services = settings.searchServices,
                    enabledServiceIds = settings.searchEnabledServiceIds,
                    onServiceMove = ::moveSearchService,
                    onServiceEnabledChange = { service, enabled ->
                        val enabledIds = if (enabled) {
                            (settings.searchEnabledServiceIds + service.id).distinct()
                        } else {
                            settings.searchEnabledServiceIds.filterNot { it == service.id }
                        }
                        vm.updateSettings(settings.copy(searchEnabledServiceIds = enabledIds))
                    },
                    onEditService = { service ->
                        editingService = SearchServiceEditorTarget(
                            index = settings.searchServices.indexOfFirst { it.id == service.id },
                            service = service,
                        )
                    },
                )
            }

            item("common_options") {
                SearchCommonOptionsCard(
                    settings = settings,
                    onUpdate = { options ->
                        vm.updateSettings(settings.copy(searchCommonOptions = options))
                    },
                )
            }

            item("recommended_search_sets") {
                SearchRecommendationFooter()
            }
        }

        editingService?.let { target ->
            SearchServiceEditorSheet(
                title = if (target.index == null) {
                    stringResource(R.string.setting_page_search_add_provider)
                } else {
                    stringResource(R.string.edit)
                },
                confirmText = if (target.index == null) {
                    stringResource(R.string.setting_page_search_add_provider)
                } else {
                    stringResource(R.string.chat_page_save)
                },
                initialService = target.service,
                onDismiss = {
                    editingService = null
                },
                onDelete = if (target.index != null && settings.searchServices.size > 1) {
                    {
                        deleteSearchService(target)
                        editingService = null
                    }
                } else {
                    null
                },
                onConfirm = { updatedService ->
                    if (target.index == null) {
                        vm.updateSettings(
                            settings.copy(
                                searchServices = listOf(updatedService) + settings.searchServices,
                                searchEnabledServiceIds = (listOf(updatedService.id) + settings.searchEnabledServiceIds).distinct(),
                                searchServiceSelected = 0,
                            )
                        )
                        scope.launch {
                            lazyListState.animateScrollToItem(1)
                        }
                    } else {
                        val currentIndex = settings.searchServices.indexOfFirst { it.id == target.service.id }
                            .takeIf { it >= 0 }
                            ?: target.index
                        if (currentIndex in settings.searchServices.indices) {
                            val oldService = settings.searchServices[currentIndex]
                            val wasEnabled = oldService.id in settings.searchEnabledServiceIds
                            val newServices = settings.searchServices.toMutableList()
                            newServices[currentIndex] = updatedService
                            val enabledIds = if (wasEnabled) {
                                settings.searchEnabledServiceIds.filterNot { it == oldService.id } + updatedService.id
                            } else {
                                settings.searchEnabledServiceIds
                            }
                            vm.updateSettings(
                                settings.copy(
                                    searchServices = newServices,
                                    searchEnabledServiceIds = enabledIds.distinct(),
                                )
                            )
                        }
                    }
                    editingService = null
                },
            )
        }
    }
}
