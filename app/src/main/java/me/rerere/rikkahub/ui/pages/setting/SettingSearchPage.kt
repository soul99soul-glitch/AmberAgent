package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Add01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun SettingSearchPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var editingService by remember { mutableStateOf<SearchServiceEditorTarget?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_search_title))
                },
                navigationIcon = {
                    BackButton()
                },
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
                            contentDescription = stringResource(R.string.setting_page_search_add_provider)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) {
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            // Three fixed items live before the configurable service rows in this LazyColumn:
            //   0  agent_search_enable    (the global toggle card)
            //   1  builtin_free_sources   (built-in free providers)
            //   2  recommended_search_sets (the recommended-combo card)
            // → service[i] sits at LazyColumn index (i + 3). If you add or remove a fixed
            //   header card above the `items(searchServices)` block, update this offset
            //   too — drag/drop silently mis-targets when it drifts.
            val offset = 3
            val fromIndex = from.index - offset
            val toIndex = to.index - offset

            if (fromIndex >= 0 && toIndex >= 0 && fromIndex < settings.searchServices.size && toIndex < settings.searchServices.size) {
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
        }
        val haptic = LocalHapticFeedback.current

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            state = lazyListState
        ) {
            item("agent_search_enable") {
                AgentSearchEnableCard(
                    enabled = settings.enableWebSearch,
                    enabledCount = settings.searchServices.count { it.id in settings.searchEnabledServiceIds } +
                        listOf(
                            settings.searchBuiltinJinaEnabled,
                            settings.searchBuiltinDuckDuckGoEnabled,
                            settings.searchBuiltinBingEnabled,
                            settings.searchBuiltinWikipediaEnabled,
                            settings.searchBuiltinHackerNewsEnabled,
                        ).count { it },
                    serviceCount = settings.searchServices.size + 5,
                    onCheckedChange = { enabled ->
                        vm.updateSettings(settings.copy(enableWebSearch = enabled))
                    },
                )
            }

            item("builtin_free_sources") {
                BuiltinFreeSearchCard(
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
                )
            }

            item("recommended_search_sets") {
                SearchRecommendationCard()
            }

            // 搜索提供商列表
            items(settings.searchServices, key = { it.id }) { service ->
                val index = settings.searchServices.indexOf(service)
                ReorderableItem(
                    state = reorderableState,
                    key = service.id
                ) { isDragging ->
                    SearchProviderCard(
                        service = service,
                        enabled = service.id in settings.searchEnabledServiceIds,
                        onEnabledChange = { enabled ->
                            val enabledIds = if (enabled) {
                                (settings.searchEnabledServiceIds + service.id).distinct()
                            } else {
                                settings.searchEnabledServiceIds.filterNot { it == service.id }
                            }
                            vm.updateSettings(settings.copy(searchEnabledServiceIds = enabledIds))
                        },
                        onEditService = {
                            editingService = SearchServiceEditorTarget(
                                index = index,
                                service = service,
                            )
                        },
                        onDeleteService = {
                            if (settings.searchServices.size > 1) {
                                val selectedServiceId = settings.searchServices.getOrNull(settings.searchServiceSelected)?.id
                                val newServices = settings.searchServices.toMutableList()
                                newServices.removeAt(index)
                                vm.updateSettings(
                                    settings.copy(
                                        searchServices = newServices,
                                        searchServiceSelected = resolveSelectedSearchIndex(
                                            services = newServices,
                                            selectedServiceId = selectedServiceId.takeUnless { it == service.id },
                                        ),
                                        searchEnabledServiceIds = settings.searchEnabledServiceIds.filterNot {
                                            it == service.id
                                        },
                                    )
                                )
                            }
                        },
                        canDelete = settings.searchServices.size > 1,
                        modifier = Modifier
                            .scale(if (isDragging) 0.95f else 1f)
                            .animateItem(),
                        dragHandle = {
                            // 36dp touch box — sized to match the new compact IconButton
                            // siblings in the action row. Glyph stays 20dp centered.
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .longPressDraggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        }
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = HugeIcons.DragDropHorizontal,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    )
                }
            }

            // 通用选项
            item("common_options") {
                CommonOptions(
                    settings = settings,
                    onUpdate = { options ->
                        vm.updateSettings(
                            settings.copy(
                                searchCommonOptions = options
                            )
                        )
                    }
                )
            }
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
                        lazyListState.animateScrollToItem(2)
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
