package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.PulseDialogButton
import me.rerere.rikkahub.ui.components.ui.PulseDialogVariant
import me.rerere.rikkahub.ui.components.ui.PulseTextField
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.WorkspaceBottomSheet
import me.rerere.rikkahub.ui.components.ui.WorkspaceSegmentedChoice
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.reflect.full.primaryConstructor
import kotlin.uuid.Uuid

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
            // The first two items are global/free-source cards; configured service cards start after them.
            val offset = 2
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

        // Pulse pI1: stat hero. ENABLED = chartreuse-filled (brand
        // active accent); BUILT-IN + EXTERNAL are plain tan tiles with
        // hairline border.
        val builtInEnabled = listOf(
            settings.searchBuiltinJinaEnabled,
            settings.searchBuiltinDuckDuckGoEnabled,
            settings.searchBuiltinBingEnabled,
            settings.searchBuiltinWikipediaEnabled,
            settings.searchBuiltinHackerNewsEnabled,
        ).count { it }
        val externalEnabled = settings.searchServices.count { svc ->
            svc.id in settings.searchEnabledServiceIds
        }
        val totalEnabled = builtInEnabled + externalEnabled
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            state = lazyListState
        ) {
            item("search_stat_hero") {
                SearchStatHero(
                    enabled = totalEnabled,
                    builtIn = builtInEnabled,
                    external = externalEnabled,
                )
            }

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
                            Icon(
                                imageVector = HugeIcons.DragDropHorizontal,
                                contentDescription = null,
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                            )
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

@Composable
private fun SearchRecommendationCard() {
    val workspace = workspaceColors()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_recommend_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.setting_page_search_recommend_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BuiltinFreeSearchCard(
    jinaEnabled: Boolean,
    duckDuckGoEnabled: Boolean,
    bingEnabled: Boolean,
    wikipediaEnabled: Boolean,
    hackerNewsEnabled: Boolean,
    googleWebViewFallbackEnabled: Boolean,
    onJinaEnabledChange: (Boolean) -> Unit,
    onDuckDuckGoEnabledChange: (Boolean) -> Unit,
    onBingEnabledChange: (Boolean) -> Unit,
    onWikipediaEnabledChange: (Boolean) -> Unit,
    onHackerNewsEnabledChange: (Boolean) -> Unit,
    onGoogleWebViewFallbackEnabledChange: (Boolean) -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.setting_page_search_builtin_sources),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.setting_page_search_builtin_sources_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_builtin_jina),
                description = stringResource(R.string.setting_page_search_builtin_jina_desc),
                checked = jinaEnabled,
                onCheckedChange = onJinaEnabledChange,
            )
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_builtin_duckduckgo),
                description = stringResource(R.string.setting_page_search_builtin_duckduckgo_desc),
                checked = duckDuckGoEnabled,
                onCheckedChange = onDuckDuckGoEnabledChange,
            )
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_builtin_bing),
                description = stringResource(R.string.setting_page_search_builtin_bing_desc),
                checked = bingEnabled,
                onCheckedChange = onBingEnabledChange,
            )
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_builtin_wikipedia),
                description = stringResource(R.string.setting_page_search_builtin_wikipedia_desc),
                checked = wikipediaEnabled,
                onCheckedChange = onWikipediaEnabledChange,
            )
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_builtin_hackernews),
                description = stringResource(R.string.setting_page_search_builtin_hackernews_desc),
                checked = hackerNewsEnabled,
                onCheckedChange = onHackerNewsEnabledChange,
            )
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_google_webview_fallback),
                description = stringResource(R.string.setting_page_search_google_webview_fallback_desc),
                checked = googleWebViewFallbackEnabled,
                onCheckedChange = onGoogleWebViewFallbackEnabledChange,
            )
        }
    }
}

@Composable
private fun BuiltinSourceRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private data class SearchServiceEditorTarget(
    val index: Int?,
    val service: SearchServiceOptions,
)

private fun resolveSelectedSearchIndex(
    services: List<SearchServiceOptions>,
    selectedServiceId: Uuid?,
): Int {
    if (services.isEmpty()) return 0
    return selectedServiceId
        ?.let { id -> services.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
        ?: 0
}

@Composable
private fun AgentSearchEnableCard(
    enabled: Boolean,
    enabledCount: Int,
    serviceCount: Int,
    onCheckedChange: (Boolean) -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_page_search_agent_search),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.setting_page_search_agent_search_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.setting_page_search_enabled_services_count,
                        enabledCount,
                        serviceCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun SearchProviderCard(
    service: SearchServiceOptions,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEditService: () -> Unit,
    onDeleteService: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit = {}
) {
    val workspace = workspaceColors()
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AutoAIIcon(
                    name = SearchServiceOptions.TYPES[service::class] ?: "Search",
                    modifier = Modifier.size(32.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = SearchServiceOptions.TYPES[service::class] ?: "Search",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (enabled) {
                            stringResource(R.string.setting_page_search_use_for_agent)
                        } else {
                            stringResource(R.string.setting_provider_page_disabled)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            SearchAbilityTagLine(options = service)

            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_search_use_for_agent_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canDelete) {
                    IconButton(
                        onClick = onDeleteService
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_page_search_delete_provider)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                PulseDialogButton(
                    text = stringResource(R.string.edit),
                    onClick = onEditService,
                    variant = PulseDialogVariant.Ghost,
                )

                IconButton(
                    onClick = {}
                ) {
                    dragHandle()
                }
            }
        }
    }
}

@Composable
private fun SearchServiceEditorSheet(
    title: String,
    confirmText: String,
    initialService: SearchServiceOptions,
    onDismiss: () -> Unit,
    onConfirm: (SearchServiceOptions) -> Unit,
) {
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var options by remember(initialService) { mutableStateOf(initialService) }

    WorkspaceBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = bottomSheetState,
        dragHandle = {
            BottomSheetDefaults.DragHandle()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Select(
                    options = SearchServiceOptions.TYPES.keys.toList(),
                    selectedOption = options::class,
                    optionToString = { SearchServiceOptions.TYPES[it] ?: "[Unknown]" },
                    onOptionSelected = {
                        options = it.primaryConstructor!!.callBy(mapOf())
                    },
                    optionLeading = {
                        AutoAIIcon(
                            name = SearchServiceOptions.TYPES[it] ?: it.simpleName ?: "unknown",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    leading = {
                        AutoAIIcon(
                            name = SearchServiceOptions.TYPES[options::class] ?: "unknown",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                SearchAbilityTagLine(options = options)

                SearchServiceOptionsEditor(
                    options = options,
                    onUpdateOptions = {
                        options = it
                    },
                )

                ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                    SearchService.getService(options).Description()
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseDialogButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    variant = PulseDialogVariant.Ghost,
                    modifier = Modifier.weight(1f),
                )
                PulseDialogButton(
                    text = confirmText,
                    onClick = { onConfirm(options) },
                    variant = PulseDialogVariant.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SearchServiceOptionsEditor(
    options: SearchServiceOptions,
    onUpdateOptions: (SearchServiceOptions) -> Unit,
) {
    when (options) {
        is SearchServiceOptions.TavilyOptions -> {
            TavilyOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.ExaOptions -> {
            ExaOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.ZhipuOptions -> {
            ZhipuOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.SearXNGOptions -> {
            SearXNGOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.LinkUpOptions -> {
            SearchLinkUpOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.BraveOptions -> {
            BraveOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.SerperOptions -> {
            SerperOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.SerpApiOptions -> {
            SerpApiOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.MetasoOptions -> {
            MetasoOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.OllamaOptions -> {
            OllamaOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.PerplexityOptions -> {
            PerplexityOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.BingLocalOptions -> Unit

        is SearchServiceOptions.FirecrawlOptions -> {
            FirecrawlOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.JinaOptions -> {
            JinaOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.BochaOptions -> {
            BochaOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.RikkaHubOptions -> {
            RikkaHubOptions(options) { onUpdateOptions(it) }
        }

        is SearchServiceOptions.GrokOptions -> {
            GrokOptions(options) { onUpdateOptions(it) }
        }
    }
}

@Composable
fun SearchAbilityTagLine(
    modifier: Modifier = Modifier,
    options: SearchServiceOptions
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Tag(
            type = TagType.DEFAULT,
        ) {
            Text(stringResource(R.string.search_ability_search))
        }
        if (SearchService.getService(options).scrapingParameters != null) {
            Tag(
                type = TagType.DEFAULT,
            ) {
                Text(stringResource(R.string.search_ability_scrape))
            }
        }
    }
}

@Composable
private fun TavilyOptions(
    options: SearchServiceOptions.TavilyOptions,
    onUpdateOptions: (SearchServiceOptions.TavilyOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Depth")
        }
    ) {
        val depthOptions = listOf("basic", "advanced")
        WorkspaceSegmentedChoice(
            options = depthOptions,
            selected = options.depth,
            modifier = Modifier.fillMaxWidth(),
            onSelected = { depth ->
                onUpdateOptions(options.copy(depth = depth))
            },
            label = { Text(it.replaceFirstChar { c -> c.uppercase() }) },
        )
    }
}

@Composable
private fun ExaOptions(
    options: SearchServiceOptions.ExaOptions,
    onUpdateOptions: (SearchServiceOptions.ExaOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}


@Composable
fun ZhipuOptions(
    options: SearchServiceOptions.ZhipuOptions,
    onUpdateOptions: (SearchServiceOptions.ZhipuOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CommonOptions(
    settings: Settings,
    onUpdate: (SearchCommonOptions) -> Unit
) {
    var commonOptions by remember(settings.searchCommonOptions) {
        mutableStateOf(settings.searchCommonOptions)
    }
    val workspace = workspaceColors()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_common_options),
                style = MaterialTheme.typography.titleMedium
            )

            FormItem(
                label = {
                    Text(stringResource(R.string.setting_page_search_result_size))
                }
            ) {
                OutlinedNumberInput(
                    value = commonOptions.resultSize,
                    onValueChange = {
                        commonOptions = commonOptions.copy(
                            resultSize = it
                        )
                        onUpdate(commonOptions)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SearXNGOptions(
    options: SearchServiceOptions.SearXNGOptions,
    onUpdateOptions: (SearchServiceOptions.SearXNGOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API URL")
        }
    ) {
        PulseTextField(
            value = options.url,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        url = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Engines")
        }
    ) {
        PulseTextField(
            value = options.engines,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        engines = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Language")
        }
    ) {
        PulseTextField(
            value = options.language,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        language = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Username")
        }
    ) {
        PulseTextField(
            value = options.username,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        username = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Password")
        }
    ) {
        PulseTextField(
            value = options.password,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        password = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SearchLinkUpOptions(
    options: SearchServiceOptions.LinkUpOptions,
    onUpdateOptions: (SearchServiceOptions.LinkUpOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Depth")
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        WorkspaceSegmentedChoice(
            options = depthOptions,
            selected = options.depth,
            modifier = Modifier.fillMaxWidth(),
            onSelected = { depth ->
                onUpdateOptions(options.copy(depth = depth))
            },
            label = { Text(it.replaceFirstChar { c -> c.uppercase() }) },
        )
    }
}

@Composable
private fun BraveOptions(
    options: SearchServiceOptions.BraveOptions,
    onUpdateOptions: (SearchServiceOptions.BraveOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SerperOptions(
    options: SearchServiceOptions.SerperOptions,
    onUpdateOptions: (SearchServiceOptions.SerperOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SerpApiOptions(
    options: SearchServiceOptions.SerpApiOptions,
    onUpdateOptions: (SearchServiceOptions.SerpApiOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}


@Composable
private fun MetasoOptions(
    options: SearchServiceOptions.MetasoOptions,
    onUpdateOptions: (SearchServiceOptions.MetasoOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OllamaOptions(
    options: SearchServiceOptions.OllamaOptions,
    onUpdateOptions: (SearchServiceOptions.OllamaOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PerplexityOptions(
    options: SearchServiceOptions.PerplexityOptions,
    onUpdateOptions: (SearchServiceOptions.PerplexityOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Max Tokens")
        }
    ) {
        PulseTextField(
            value = options.maxTokens?.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdateOptions(
                    options.copy(
                        maxTokens = value.toIntOrNull()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    FormItem(
        label = {
            Text("Max Tokens / Page")
        }
    ) {
        PulseTextField(
            value = options.maxTokensPerPage?.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdateOptions(
                    options.copy(
                        maxTokensPerPage = value.toIntOrNull()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun FirecrawlOptions(
    options: SearchServiceOptions.FirecrawlOptions,
    onUpdateOptions: (SearchServiceOptions.FirecrawlOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun JinaOptions(
    options: SearchServiceOptions.JinaOptions,
    onUpdateOptions: (SearchServiceOptions.JinaOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Search URL")
        }
    ) {
        PulseTextField(
            value = options.searchUrl,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        searchUrl = it.trim()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://s.jina.ai/")
            }
        )
    }

    FormItem(
        label = {
            Text("Scrape URL")
        }
    ) {
        PulseTextField(
            value = options.scrapeUrl,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        scrapeUrl = it.trim()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://r.jina.ai/")
            }
        )
    }
}

@Composable
private fun BochaOptions(
    options: SearchServiceOptions.BochaOptions,
    onUpdateOptions: (SearchServiceOptions.BochaOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Summary")
        },
        description = {
            Text("Enable summary generation")
        },
        tail = {
            Switch(
                checked = options.summary,
                onCheckedChange = { checked ->
                    onUpdateOptions(
                        options.copy(
                            summary = checked
                        )
                    )
                }
            )
        }
    )
}

@Composable
private fun RikkaHubOptions(
    options: SearchServiceOptions.RikkaHubOptions,
    onUpdateOptions: (SearchServiceOptions.RikkaHubOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Depth")
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        WorkspaceSegmentedChoice(
            options = depthOptions,
            selected = options.depth,
            modifier = Modifier.fillMaxWidth(),
            onSelected = { depth ->
                onUpdateOptions(options.copy(depth = depth))
            },
            label = { Text(it.replaceFirstChar { c -> c.uppercase() }) },
        )
    }
}

@Composable
private fun GrokOptions(
    options: SearchServiceOptions.GrokOptions,
    onUpdateOptions: (SearchServiceOptions.GrokOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        PulseTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Model")
        }
    ) {
        PulseTextField(
            value = options.model,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        model = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Custom URL")
        }
    ) {
        PulseTextField(
            value = options.customUrl,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        customUrl = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("System Prompt")
        }
    ) {
        PulseTextField(
            value = options.systemPrompt,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        systemPrompt = it
                    )
                )
            },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Pulse pI1 — 3-tile stat hero for the Web Search page. Mirrors the
 * SettingProviderPage hero (Configured / Models) treatment: ENABLED
 * goes chartreuse-filled (brand active accent), BUILT-IN + EXTERNAL
 * are plain tan tiles with a hairline border.
 *
 * Counts pad to two digits and use 36sp Black weight; ALL-CAPS labels
 * are 0.1em-tracked. Same recipe as the Provider / Skills heros so
 * they read as a cohesive family.
 */
@Composable
private fun SearchStatHero(
    enabled: Int,
    builtIn: Int,
    external: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchStatTile(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.setting_search_page_stat_enabled),
            value = enabled.toString().padStart(2, '0'),
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            border = null,
        )
        SearchStatTile(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.setting_search_page_stat_builtin),
            value = builtIn.toString().padStart(2, '0'),
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        )
        SearchStatTile(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.setting_search_page_stat_external),
            value = external.toString().padStart(2, '0'),
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun SearchStatTile(
    label: String,
    value: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    border: BorderStroke?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = content,
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.em,
                ),
                color = content.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = content,
            )
        }
    }
}
