package app.amber.feature.ui.pages.board

import com.composables.icons.lucide.BotMessageSquare

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.Screen
import app.amber.agent.R
import app.amber.agent.StandaloneSurfaces
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MAX
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MIN
import app.amber.feature.board.DEEP_READ_FONT_SCALE_STEP
import app.amber.feature.board.BoardRepository
import app.amber.feature.board.BoardSignalSourceType
import app.amber.feature.board.DEFAULT_HOT_LIST_FOCUS_KEYWORDS
import app.amber.feature.board.TodayBoardBackgroundStrategy
import app.amber.feature.board.TodayBoardHotListFilterMode
import app.amber.feature.board.TodayBoardReadingFontMode
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.HotListScheduler
import app.amber.feature.board.hotlist.normalizeHotListFocusKeywords
import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import app.amber.core.settings.findModelById
import app.amber.core.ai.tools.SearchOrchestrator
import app.amber.agent.data.db.entity.BoardFocusRuleEntity
import app.amber.agent.data.db.entity.BoardWeightEntity
import app.amber.agent.data.db.entity.HotListSourceEntity
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRepository
import app.amber.core.font.FontPackCategory
import app.amber.core.font.FontPackState
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.ui.components.ai.ProviderAccordionModelPicker
import app.amber.feature.ui.components.ui.NotionSlider
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.pages.setting.ExperimentalSettingsScaffold
import app.amber.feature.ui.pages.setting.SettingVM
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.LayoutDashboard
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.Megaphone
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.ScanSearch
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.WandSparkles

@Composable
fun SettingTodayBoardPage(
    paneRoute: String? = null,
    vm: SettingVM = koinViewModel(),
) {
    val boardRepository: BoardRepository = koinInject()
    val hotListRepository: HotListRepository = koinInject()
    val hotListScheduler: HotListScheduler = koinInject()
    val deepReadTemplateRepository: DeepReadTemplateRepository = koinInject()
    val fontRepository: SlidesFontRepository = koinInject()
    val json: Json = koinInject()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val focusRules by boardRepository.observeFocusRules().collectAsStateWithLifecycle(initialValue = emptyList())
    val customHotListSources by hotListRepository.observeSources().collectAsStateWithLifecycle(initialValue = emptyList())
    val customDeepReadTemplates by deepReadTemplateRepository.observeTemplates().collectAsStateWithLifecycle()
    val invalidDeepReadTemplateCount by deepReadTemplateRepository.observeInvalidTemplateCount().collectAsStateWithLifecycle()
    val fontStates by fontRepository.fontsFlow.collectAsStateWithLifecycle()
    val board = settings.agentRuntime.todayBoard
    var sourceWeights by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var weightReloadKey by remember { mutableIntStateOf(0) }
    val pane = TodayBoardSettingsPane.fromRoute(paneRoute)
    val templateFontCss = rememberDeepReadTemplateFontCss(
        mode = board.boardReadingFontMode,
        fontPackId = board.boardReadingFontPackId,
        fontStates = fontStates,
        fontScale = board.deepReadFontScale,
    )

    LaunchedEffect(weightReloadKey) {
        sourceWeights = boardRepository.getAllWeights()
            .filter { it.keyword == BoardWeightEntity.WHOLE_SOURCE }
            .associate { it.sourceType to it.weight }
    }

    LaunchedEffect(Unit) {
        deepReadTemplateRepository.reload()
    }

    fun update(block: (TodayBoardSetting) -> TodayBoardSetting) {
        vm.updateSettings { current ->
            current.copy(
                agentRuntime = current.agentRuntime.copy(todayBoard = block(board))
            )
        }
    }

    fun addFocusRule(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()
        scope.launch {
            boardRepository.upsertFocusRule(
                BoardFocusRuleEntity(
                    id = Uuid.random().toString(),
                    content = trimmed,
                    active = true,
                    sortOrder = (focusRules.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    fun updateFocusRule(rule: BoardFocusRuleEntity, active: Boolean) {
        scope.launch {
            boardRepository.upsertFocusRule(rule.copy(active = active, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteFocusRule(rule: BoardFocusRuleEntity) {
        scope.launch { boardRepository.deleteFocusRule(rule.id) }
    }

    fun updateSourceWeight(sourceType: String, weight: Int) {
        sourceWeights = sourceWeights + (sourceType to weight)
        scope.launch {
            boardRepository.upsertWeight(
                BoardWeightEntity(
                    sourceType = sourceType,
                    keyword = BoardWeightEntity.WHOLE_SOURCE,
                    weight = weight,
                    lastActionAt = System.currentTimeMillis(),
                )
            )
            weightReloadKey++
        }
    }

    fun saveCustomHotListSource(draft: CustomHotListSourceDraft) {
        val now = System.currentTimeMillis()
        scope.launch {
            hotListRepository.upsertSource(
                HotListSourceEntity(
                    id = "custom:${Uuid.random()}",
                    displayName = draft.name.trim(),
                    sourceType = draft.sourceType,
                    url = draft.url.trim(),
                    enabled = true,
                    fieldMappingJson = json.encodeToString(draft.mapping),
                    sortOrder = (customHotListSources.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            hotListScheduler.runOnce()
        }
    }

    fun addNewsNowPresets(presets: List<app.amber.feature.board.hotlist.providers.NewsNowPreset>) {
        if (presets.isEmpty()) return
        val now = System.currentTimeMillis()
        val baseOrder = (customHotListSources.maxOfOrNull { it.sortOrder } ?: 0) + 1
        scope.launch {
            presets.forEachIndexed { index, preset ->
                hotListRepository.upsertSource(
                    app.amber.feature.board.hotlist.providers.NewsNowPresets.entityFor(
                        preset = preset,
                        now = now,
                        sortOrder = baseOrder + index,
                    )
                )
            }
            hotListScheduler.runOnce()
        }
    }

    fun toggleCustomHotListSource(source: HotListSourceEntity) {
        scope.launch {
            hotListRepository.upsertSource(source.copy(enabled = !source.enabled, updatedAt = System.currentTimeMillis()))
            hotListScheduler.runOnce()
        }
    }

    fun deleteCustomHotListSource(source: HotListSourceEntity) {
        scope.launch {
            hotListRepository.deleteSource(source.id)
            hotListScheduler.runOnce()
        }
    }

    ExperimentalSettingsScaffold(
        title = pane.localizedTitle(),
        titleStyle = deepReadEditorialSerif?.let { serif ->
            LocalAmberType.current.screenTitle.copy(fontFamily = serif, fontWeight = FontWeight.Bold)
        },
    ) { innerPadding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .amberCanvas()
                .let {
                    if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
                        it.deepReadPaper(night = DeepReadMoments.isNight(), tokens = LocalAmberTokens.current)
                    } else it
                },
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (pane) {
                TodayBoardSettingsPane.ROOT -> {
                    // Standalone deepread: iOS DeepReadSettingsView parity —
                    // 外观 / 文章生成 / 多来源搜索 cards first, then the board
                    // feature cards. The full app skips straight to features.
                    if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
                        item {
                            val selectedTemplateName = DeepReadTemplateCatalog.name(
                                board.deepReadTemplateId,
                                customDeepReadTemplates
                                    .firstOrNull { it.id == board.deepReadTemplateId }?.name,
                            )
                            BoardSectionCard(
                                title = stringResource(R.string.deepread_settings_appearance),
                                footer = stringResource(R.string.deepread_settings_appearance_footer),
                            ) {
                                DeepReadAccentPicker(
                                    selectedId = board.deepReadAccent,
                                    onSelect = { choice -> update { it.copy(deepReadAccent = choice.id) } },
                                )
                                BoardDivider()
                                DetailNavigationRow(
                                    title = stringResource(R.string.deepread_settings_layout_style),
                                    value = selectedTemplateName,
                                    icon = Lucide.BookOpenText,
                                    onClick = {
                                        navController.navigate(
                                            Screen.SettingTodayBoardDetail(TodayBoardSettingsPane.STYLE.route)
                                        )
                                    },
                                )
                            }
                        }

                        item {
                            // Mirrors BoardModelRow semantics: null boardModelId
                            // means "follow main chat model", not a pinned model.
                            val boardModel = board.boardModelId
                                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                                ?.let { settings.findModelById(it) }
                            BoardSectionCard(
                                title = stringResource(R.string.deepread_settings_generation),
                                footer = stringResource(R.string.deepread_settings_gen_footer),
                            ) {
                                DetailNavigationRow(
                                    title = stringResource(R.string.deepread_settings_gen_model),
                                    value = boardModel?.displayName ?: stringResource(R.string.deepread_board_use_default_model),
                                    icon = Lucide.WandSparkles,
                                    onClick = {
                                        navController.navigate(
                                            Screen.SettingTodayBoardDetail(TodayBoardSettingsPane.MODEL.route)
                                        )
                                    },
                                )
                                BoardDivider()
                                // 前台/后台 execution strategy belongs with
                                // generation ops (folded from the old 通用 card).
                                DetailNavigationRow(
                                    title = stringResource(R.string.board_model_background),
                                    icon = Lucide.RotateCw,
                                    onClick = {
                                        navController.navigate(
                                            Screen.SettingTodayBoardDetail(TodayBoardSettingsPane.GENERAL.route)
                                        )
                                    },
                                )
                                BoardDivider()
                                DetailNavigationRow(
                                    title = stringResource(R.string.setting_page_providers),
                                    icon = Lucide.Cpu,
                                    onClick = { navController.navigate(Screen.SettingProvider) },
                                )
                                BoardDivider()
                                // iOS "＋ 添加模型服务" accent action row.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { navController.navigate(Screen.SettingProvider) }
                                        .heightIn(min = 52.dp)
                                        .padding(horizontal = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Lucide.Plus,
                                        contentDescription = null,
                                        tint = LocalAmberTokens.current.accent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        stringResource(R.string.deepread_settings_add_model),
                                        style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                                        color = LocalAmberTokens.current.accent,
                                    )
                                }
                            }
                        }

                        // iOS "多来源搜索" — everything that feeds the digest
                        // lives in one card: hotlist sources, the search service,
                        // and personal signals with their weights.
                        item {
                            BoardSectionCard(
                                title = stringResource(R.string.deepread_settings_search),
                                footer = stringResource(R.string.deepread_settings_sources_footer),
                            ) {
                                DetailNavigationRow(
                                    title = stringResource(R.string.deepread_board_sources_focus_title),
                                    icon = Lucide.BookOpenText,
                                    onClick = {
                                        navController.navigate(
                                            Screen.SettingTodayBoardDetail(TodayBoardSettingsPane.HOT_LIST.route)
                                        )
                                    },
                                )
                                BoardDivider()
                                DetailNavigationRow(
                                    title = stringResource(R.string.setting_page_search_service),
                                    value = "${settings.searchEnabledServiceIds.size}/${settings.searchServices.size}",
                                    icon = Lucide.ScanSearch,
                                    onClick = { navController.navigate(Screen.SettingSearch) },
                                )
                                BoardDivider()
                                DetailNavigationRow(
                                    title = stringResource(R.string.deepread_board_signals_weights),
                                    icon = Lucide.SlidersHorizontal,
                                    onClick = {
                                        navController.navigate(
                                            Screen.SettingTodayBoardDetail(TodayBoardSettingsPane.REVIEW.route)
                                        )
                                    },
                                )
                            }
                        }

                        // iOS "免费聚合与正文读取" — the keyless built-in
                        // sources. Android consumes them as three switches:
                        // DuckDuckGo+Bing ride one aggregate, Jina reads page
                        // text, Google WebView is the last-resort fallback.
                        // (Wikipedia/HackerNews flags exist in prefs but have
                        // no Android consumer — don't expose dead toggles.)
                        item {
                            BoardSectionCard(title = stringResource(R.string.deepread_settings_free_aggregate)) {
                                SourceSwitch(
                                    title = stringResource(R.string.setting_page_search_builtin_free_aggregate),
                                    description = stringResource(R.string.setting_page_search_builtin_free_aggregate_desc),
                                    checked = SearchOrchestrator.freeAggregateEnabled(settings),
                                    icon = Lucide.Globe,
                                ) {
                                    vm.updateSettings { current ->
                                        // The aggregate is one source on
                                        // Android — both flags move together.
                                        val target = !(current.searchBuiltinDuckDuckGoEnabled ||
                                            current.searchBuiltinBingEnabled)
                                        current.copy(
                                            searchBuiltinDuckDuckGoEnabled = target,
                                            searchBuiltinBingEnabled = target,
                                        )
                                    }
                                }
                                BoardDivider()
                                SourceSwitch(
                                    title = stringResource(R.string.setting_page_search_builtin_jina),
                                    description = stringResource(R.string.setting_page_search_builtin_jina_desc),
                                    checked = settings.searchBuiltinJinaEnabled,
                                    icon = Lucide.BookOpenText,
                                ) {
                                    vm.updateSettings { it.copy(searchBuiltinJinaEnabled = !it.searchBuiltinJinaEnabled) }
                                }
                                BoardDivider()
                                SourceSwitch(
                                    title = stringResource(R.string.setting_page_search_google_webview_fallback),
                                    description = stringResource(R.string.setting_page_search_google_webview_fallback_desc),
                                    checked = settings.searchGoogleWebViewFallbackEnabled,
                                    icon = Lucide.Search,
                                ) {
                                    vm.updateSettings { it.copy(searchGoogleWebViewFallbackEnabled = !it.searchGoogleWebViewFallbackEnabled) }
                                }
                            }
                        }

                        // iOS-style 通用: the product switch plus refresh
                        // scheduling — what was previously split across
                        // 通用 / 热榜 / 今日复盘 shell cards.
                        item {
                            BoardSectionCard(title = stringResource(R.string.board_settings_general)) {
                                SourceSwitch(
                                    title = stringResource(R.string.deepread_board_enable_title),
                                    description = stringResource(R.string.deepread_board_enable_description),
                                    checked = board.enabled,
                                    icon = Lucide.LayoutDashboard,
                                ) {
                                    update { it.copy(enabled = !it.enabled) }
                                }
                                BoardDivider()
                                IntervalRow(board.hotListRefreshIntervalMinutes) { value ->
                                    update { it.copy(hotListRefreshIntervalMinutes = value) }
                                }
                                BoardDivider()
                                SourceSwitch(
                                    title = stringResource(R.string.board_wifi_only),
                                    description = stringResource(R.string.deepread_board_wifi_only_description),
                                    checked = board.hotListWifiOnly,
                                    icon = Lucide.Cloud,
                                ) {
                                    update { it.copy(hotListWifiOnly = !it.hotListWifiOnly) }
                                }
                            }
                        }
                    } else {
                        // Full app keeps its original feature-shell cards.
                        item {
                            BoardSectionCard(title = stringResource(R.string.board_settings_general)) {
                                SourceSwitch(
                                    title = stringResource(R.string.board_enable_title),
                                    description = stringResource(R.string.board_enable_description),
                                    checked = board.enabled,
                                    icon = Lucide.LayoutDashboard,
                                ) {
                                    update { it.copy(enabled = !it.enabled) }
                                }
                                BoardDivider()
                                DetailNavigationRow(
                                    title = stringResource(R.string.board_model_background),
                                    description = null,
                                    icon = Lucide.RotateCw,
                                    onClick = {
                                        navController.navigate(Screen.SettingTodayBoardDetail(TodayBoardSettingsPane.GENERAL.route))
                                    },
                                )
                            }
                        }

                        item {
                            BoardSectionCard(title = stringResource(R.string.board_hotlist)) {
                                IntervalRow(board.hotListRefreshIntervalMinutes) { value ->
                                    update { it.copy(hotListRefreshIntervalMinutes = value) }
                                }
                                BoardDivider()
                                SourceSwitch(
                                    title = stringResource(R.string.board_wifi_only),
                                    description = stringResource(R.string.board_wifi_only_description),
                                    checked = board.hotListWifiOnly,
                                    icon = Lucide.Cloud,
                                ) {
                                    update { it.copy(hotListWifiOnly = !it.hotListWifiOnly) }
                                }
                                BoardDivider()
                                DetailNavigationRow(
                                    title = stringResource(R.string.board_sources_focus_deep_read),
                                    description = null,
                                    icon = Lucide.BookOpenText,
                                    onClick = {
                                        navController.navigate(Screen.SettingTodayBoardDetail(TodayBoardSettingsPane.HOT_LIST.route))
                                    },
                                )
                            }
                        }

                        item {
                            BoardSectionCard(title = stringResource(R.string.board_daily_review)) {
                                DetailNavigationRow(
                                    title = stringResource(R.string.board_signal_sources_focus),
                                    description = null,
                                    icon = Lucide.SlidersHorizontal,
                                    onClick = {
                                        navController.navigate(Screen.SettingTodayBoardDetail(TodayBoardSettingsPane.REVIEW.route))
                                    },
                                )
                            }
                        }
                    }

                }

                TodayBoardSettingsPane.GENERAL -> {
                    item {
                        BoardSectionCard(title = stringResource(R.string.board_settings_general)) {
                            // Standalone deepread keeps the model picker under
                            // 文章生成 → 生成模型 (iOS parity); duplicating it here
                            // would give two entries to the same setting.
                            if (StandaloneSurfaces.current != StandaloneSurfaces.DEEP_READ) {
                                BoardModelRow(board = board, settings = settings, update = ::update)
                                BoardDivider()
                            }
                            BackgroundStrategyRow(board.backgroundStrategy) { value ->
                                update { it.copy(backgroundStrategy = value) }
                            }
                        }
                    }
                }

                TodayBoardSettingsPane.MODEL -> {
                    item {
                        BoardSectionCard(title = stringResource(R.string.deepread_settings_gen_model)) {
                            BoardModelRow(
                                board = board,
                                settings = settings,
                                update = ::update,
                                initiallyExpanded = true,
                                // The pane title and card header already say
                                // 生成模型 — a third identical label inside the
                                // card reads as a bug, not structure.
                                showHeading = false,
                            )
                        }
                    }
                }

                TodayBoardSettingsPane.HOT_LIST -> {
                    item {
                        HotListSourceSettings(
                            enabledBuiltIns = board.hotListEnabledSources,
                            customSources = customHotListSources,
                            onToggleBuiltIn = { source ->
                                update {
                                    it.copy(
                                        hotListEnabledSources = if (source in it.hotListEnabledSources) {
                                            it.hotListEnabledSources - source
                                        } else {
                                            it.hotListEnabledSources + source
                                        }
                                    )
                                }
                                hotListScheduler.runOnce()
                            },
                            onToggleCustom = ::toggleCustomHotListSource,
                            onDeleteCustom = ::deleteCustomHotListSource,
                            onAddNewsNowPresets = ::addNewsNowPresets,
                            onSaveCustom = ::saveCustomHotListSource,
                        )
                    }
                    item {
                        BoardSectionCard(title = stringResource(R.string.board_search_title)) {
                            SearchServiceSummary(
                                enabledCount = settings.searchEnabledServiceIds.size,
                                totalCount = settings.searchServices.size,
                            )
                        }
                    }
                    item {
                        BoardSectionCard(title = stringResource(R.string.board_focus_filter)) {
                            HotListFocusKeywordEditor(
                                keywords = board.hotListFocusKeywords,
                                mode = board.hotListFilterMode,
                                onKeywordsChange = { keywords -> update { it.copy(hotListFocusKeywords = keywords) } },
                                onModeChange = { mode -> update { it.copy(hotListFilterMode = mode) } },
                            )
                        }
                    }
                    // Standalone deepread: reading typography + templates live in
                    // the dedicated STYLE pane (iOS 版式与样式). Rendering them
                    // here too would give two entries to the same settings.
                    if (StandaloneSurfaces.current != StandaloneSurfaces.DEEP_READ) {
                        item {
                            BoardSectionCard(title = stringResource(R.string.deep_read_title)) {
                                ReadingFontRow(board = board, fontStates = fontStates, update = ::update)
                                BoardDivider()
                                DeepReadCacheTtlRow(board = board, update = ::update)
                            }
                        }
                        item {
                            DeepReadTemplateSettingsRow(
                                board = board,
                                customTemplates = customDeepReadTemplates,
                                invalidTemplateCount = invalidDeepReadTemplateCount,
                                fontCss = templateFontCss,
                                fontRepository = fontRepository,
                                onSelect = { templateId -> update { it.copy(deepReadTemplateId = templateId) } },
                                onDelete = { template ->
                                    scope.launch {
                                        deepReadTemplateRepository.deleteTemplate(template.id)
                                        if (board.deepReadTemplateId == template.id) {
                                            update {
                                                it.copy(
                                                    deepReadTemplateId = app.amber.feature.board.DeepReadTemplateIds.COMPOSE_MAGAZINE
                                                )
                                            }
                                        }
                                    }
                                },
                                onCreateTemplate = { navController.navigate(Screen.DeepReadTemplateWorkbench) },
                            )
                        }
                    }
                }

                // Standalone "版式与样式" page (iOS DeepReadTemplatesView parity):
                // reading typography + template gallery in one place.
                TodayBoardSettingsPane.STYLE -> {
                    item {
                        BoardSectionCard(title = stringResource(R.string.deep_read_title)) {
                            ReadingFontRow(board = board, fontStates = fontStates, update = ::update)
                            BoardDivider()
                            DeepReadCacheTtlRow(board = board, update = ::update)
                        }
                    }
                    item {
                        DeepReadTemplateSettingsRow(
                            board = board,
                            customTemplates = customDeepReadTemplates,
                            invalidTemplateCount = invalidDeepReadTemplateCount,
                            fontCss = templateFontCss,
                            fontRepository = fontRepository,
                            onSelect = { templateId -> update { it.copy(deepReadTemplateId = templateId) } },
                            onDelete = { template ->
                                scope.launch {
                                    deepReadTemplateRepository.deleteTemplate(template.id)
                                    if (board.deepReadTemplateId == template.id) {
                                        update {
                                            it.copy(
                                                deepReadTemplateId = app.amber.feature.board.DeepReadTemplateIds.COMPOSE_MAGAZINE
                                            )
                                        }
                                    }
                                }
                            },
                            onCreateTemplate = { navController.navigate(Screen.DeepReadTemplateWorkbench) },
                        )
                    }
                }

                TodayBoardSettingsPane.REVIEW -> {
                    item {
                        BoardSectionCard(title = stringResource(R.string.board_signal_sources)) {
                            val notifPermissionOk = remember {
                                runCatching {
                                    android.provider.Settings.Secure.getString(
                                        context.contentResolver,
                                        "enabled_notification_listeners"
                                    )?.contains(context.packageName) == true
                                }.getOrDefault(false)
                            }
                            val calendarPermissionOk = remember {
                                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                                    PackageManager.PERMISSION_GRANTED
                            }
                            SourceSwitch(
                                stringResource(R.string.board_signal_notification),
                                if (notifPermissionOk) stringResource(R.string.board_signal_notification_description)
                                else stringResource(R.string.board_signal_notification_permission),
                                BoardSignalSourceType.NOTIFICATION in board.enabledSources,
                                icon = Lucide.Megaphone,
                            ) { toggleSignalSource(BoardSignalSourceType.NOTIFICATION, ::update) }
                            BoardDivider()
                            SourceSwitch(
                                stringResource(R.string.board_signal_calendar),
                                if (calendarPermissionOk) stringResource(R.string.board_signal_calendar_description)
                                else stringResource(R.string.board_signal_calendar_permission),
                                BoardSignalSourceType.CALENDAR in board.enabledSources,
                                icon = Lucide.AlarmClock,
                            ) { toggleSignalSource(BoardSignalSourceType.CALENDAR, ::update) }
                            // Feishu credentials and chat history only exist in the
                            // full agent app; in standalone surfaces these toggles
                            // could never produce data — don't offer them.
                            if (StandaloneSurfaces.allowsAgentFeatures) {
                                BoardDivider()
                                SourceSwitch(stringResource(R.string.board_signal_feishu_messages), stringResource(R.string.board_signal_feishu_messages_description), BoardSignalSourceType.FEISHU_MSG in board.enabledSources, icon = Lucide.MessagesSquare) {
                                    toggleSignalSource(BoardSignalSourceType.FEISHU_MSG, ::update)
                                }
                                BoardDivider()
                                SourceSwitch(stringResource(R.string.board_signal_feishu_docs), stringResource(R.string.board_signal_feishu_docs_description), BoardSignalSourceType.FEISHU_DOC in board.enabledSources, icon = Lucide.FileText) {
                                    toggleSignalSource(BoardSignalSourceType.FEISHU_DOC, ::update)
                                }
                                BoardDivider()
                                SourceSwitch(stringResource(R.string.board_signal_chat_history), stringResource(R.string.board_signal_chat_history_description), BoardSignalSourceType.CHAT_HISTORY in board.enabledSources, icon = Lucide.MessageSquare) {
                                    toggleSignalSource(BoardSignalSourceType.CHAT_HISTORY, ::update)
                                }
                            }
                        }
                    }
                    item {
                        BoardSectionCard(title = stringResource(R.string.board_generation_rules)) {
                            IncrementalSlider(board.incrementalSignalThreshold) { value ->
                                update { it.copy(incrementalSignalThreshold = value) }
                            }
                            BoardDivider()
                            FocusRulesEditor(
                                rules = focusRules,
                                onAdd = ::addFocusRule,
                                onToggle = ::updateFocusRule,
                                onDelete = ::deleteFocusRule,
                            )
                        }
                    }
                    item {
                        BoardSectionCard(title = stringResource(R.string.board_source_weights)) {
                            SourceWeightsEditor(weights = sourceWeights, onChange = ::updateSourceWeight)
                        }
                    }
                }
            }
        }
    }
}

private enum class TodayBoardSettingsPane(val route: String) {
    ROOT("root"),
    GENERAL("general"),
    HOT_LIST("hot_list"),
    REVIEW("review"),
    // Standalone-only "版式与样式" page — iOS DeepReadTemplatesView parity.
    STYLE("style"),
    // Standalone-only "生成模型" page — iOS DeepReadSettingsView's single
    // model picker parity (the full app's multi-role SettingModelPage is
    // agent-only and must not surface in standalone products).
    MODEL("model"),
    ;

    companion object {
        fun fromRoute(route: String?): TodayBoardSettingsPane =
            entries.firstOrNull { it.route == route } ?: ROOT
    }
}

@Composable
private fun TodayBoardSettingsPane.localizedTitle(): String = when (this) {
    // In the standalone deepread app this page IS the product settings —
    // "今日看板" is internal full-app vocabulary the user never sees (iOS tab
    // title is just "设置").
    TodayBoardSettingsPane.ROOT -> stringResource(
        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.settings else R.string.board_settings_title,
    )
    TodayBoardSettingsPane.GENERAL -> stringResource(R.string.board_settings_general_title)
    TodayBoardSettingsPane.HOT_LIST -> stringResource(R.string.board_settings_hotlist_title)
    TodayBoardSettingsPane.REVIEW -> stringResource(
        // Standalone pane only holds signal sources + weights — "复盘" is
        // full-app feature vocabulary with no surface here.
        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.deepread_board_signals_weights
        else R.string.board_settings_review_title,
    )
    TodayBoardSettingsPane.STYLE -> stringResource(R.string.deepread_settings_layout_style)
    TodayBoardSettingsPane.MODEL -> stringResource(R.string.deepread_settings_gen_model)
}

@Composable
private fun BoardSectionCard(
    title: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("//", style = LocalAmberType.current.eyebrow, color = tokens.accent)
            Text(title, style = LocalAmberType.current.eyebrow, color = tokens.ink2)
            androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = tokens.line)
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = tokens.surface,
            contentColor = tokens.ink,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        footer?.takeIf { it.isNotBlank() }?.let {
            // iOS Form section footer: muted caption under the card.
            Text(
                it,
                style = LocalAmberType.current.secondary,
                color = tokens.ink3,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun BoardDivider() {
    val tokens = LocalAmberTokens.current
    androidx.compose.material3.HorizontalDivider(
        // Align the hairline with the row label: icon(14+32) + gap(12) = 58dp.
        Modifier.padding(start = 58.dp),
        color = tokens.line,
    )
}

@Composable
private fun DetailNavigationRow(
    title: String,
    description: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            WorkspaceLeadingIcon(icon = it, size = 32.dp, iconSize = 17.dp)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.ink,
            )
            description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = LocalAmberType.current.secondary,
                    color = tokens.ink3,
                )
            }
        }
        value?.takeIf { it.isNotBlank() }?.let {
            // iOS LabeledContent value — current selection shown beside the chevron.
            Text(
                text = it,
                style = LocalAmberType.current.secondary,
                color = tokens.ink3,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tokens.ink3,
        )
    }
}

@Composable
private fun TodayBoardHotListFilterMode.label(): String =
    when (this) {
        TodayBoardHotListFilterMode.ALL -> stringResource(R.string.board_filter_all)
        TodayBoardHotListFilterMode.FOCUS_FIRST -> stringResource(R.string.board_filter_focus_first)
        TodayBoardHotListFilterMode.FOCUS_ONLY -> stringResource(R.string.board_filter_focus_only)
    }

@Composable
private fun TodayBoardBackgroundStrategy.label(): String =
    when (this) {
        TodayBoardBackgroundStrategy.SMART -> stringResource(R.string.board_background_smart)
        TodayBoardBackgroundStrategy.WIFI_ONLY -> stringResource(R.string.board_background_wifi)
        TodayBoardBackgroundStrategy.FOREGROUND_ONLY -> stringResource(R.string.board_background_foreground)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HotListFocusKeywordEditor(
    keywords: List<String>,
    mode: TodayBoardHotListFilterMode,
    onKeywordsChange: (List<String>) -> Unit,
    onModeChange: (TodayBoardHotListFilterMode) -> Unit,
) {
    var draft by rememberSaveable(keywords.joinToString("|")) { mutableStateOf(keywords.joinToString("、")) }
    val parsed = normalizeHotListFocusKeywords(listOf(draft))
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.board_focus_filter), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                TodayBoardHotListFilterMode.ALL to stringResource(R.string.board_filter_all),
                TodayBoardHotListFilterMode.FOCUS_FIRST to stringResource(R.string.board_filter_focus_first),
                TodayBoardHotListFilterMode.FOCUS_ONLY to stringResource(R.string.board_filter_focus_only),
            ).forEach { (value, label) ->
                ChoiceChip(selected = mode == value, label = label, onClick = { onModeChange(value) })
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.board_focus_placeholder)) },
            minLines = 2,
            maxLines = 4,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LocalAmberTokens.current.accent,
                unfocusedBorderColor = LocalAmberTokens.current.line,
                focusedContainerColor = LocalAmberTokens.current.raised,
                unfocusedContainerColor = LocalAmberTokens.current.surface2,
                cursorColor = LocalAmberTokens.current.accent,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = parsed.isNotEmpty(),
                onClick = { onKeywordsChange(parsed) },
            ) {
                Text(stringResource(R.string.board_apply))
            }
            TextButton(
                onClick = {
                    draft = DEFAULT_HOT_LIST_FOCUS_KEYWORDS.joinToString("、")
                    onKeywordsChange(DEFAULT_HOT_LIST_FOCUS_KEYWORDS)
                },
            ) {
                Text(stringResource(R.string.board_focus_restore))
            }
        }
        if (keywords.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                keywords.take(16).forEach { keyword ->
                    ChoiceChip(selected = false, label = keyword, onClick = {})
                }
                if (keywords.size > 16) {
                    Text("+${keywords.size - 16}", style = MaterialTheme.typography.labelSmall, color = workspaceColors().muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeepReadCacheTtlRow(
    board: TodayBoardSetting,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    // TTL options: 1 / 3 / 7 (default) / 0 (never expire). 0 keeps expiresAt at Long.MAX_VALUE.
    val options = listOf(
        1 to stringResource(R.string.board_cache_24_hours),
        3 to stringResource(R.string.board_cache_3_days),
        7 to stringResource(R.string.board_cache_7_days),
        0 to stringResource(R.string.board_cache_forever),
    )
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.board_cache_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.board_cache_description),
            style = MaterialTheme.typography.bodySmall,
            color = workspaceColors().muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (days, label) ->
                ChoiceChip(
                    selected = board.deepReadCacheTtlDays == days,
                    label = label,
                    onClick = { update { it.copy(deepReadCacheTtlDays = days) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingFontRow(
    board: TodayBoardSetting,
    fontStates: List<FontPackState>,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    val installedFonts = fontStates.filter { it.installed && !it.installedPath.isNullOrBlank() }
    val selectedPackAvailable = installedFonts.any { it.pack.id == board.boardReadingFontPackId }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.board_reading_font_title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(
                selected = board.boardReadingFontMode == TodayBoardReadingFontMode.SYSTEM,
                label = stringResource(R.string.board_font_system),
                onClick = { update { it.copy(boardReadingFontMode = TodayBoardReadingFontMode.SYSTEM) } },
            )
            ChoiceChip(
                selected = board.boardReadingFontMode == TodayBoardReadingFontMode.SERIF,
                label = stringResource(R.string.board_font_serif),
                onClick = {
                    update {
                        it.copy(
                            boardReadingFontMode = TodayBoardReadingFontMode.SERIF,
                            boardReadingFontPackId = null,
                        )
                    }
                },
            )
        }
        if (installedFonts.isEmpty()) {
            Text(stringResource(R.string.board_fonts_empty), style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        } else {
            Text(stringResource(R.string.board_fonts_downloaded), style = MaterialTheme.typography.labelMedium, color = workspaceColors().muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                installedFonts
                    .sortedWith(compareByDescending<FontPackState> { it.pack.category == FontPackCategory.SERIF }.thenBy { it.pack.displayName })
                    .take(12)
                    .forEach { state ->
                        ChoiceChip(
                            selected = board.boardReadingFontMode == TodayBoardReadingFontMode.SLIDES_PACK &&
                                board.boardReadingFontPackId == state.pack.id,
                            label = "${state.pack.displayName} · ${state.pack.category.label()}",
                            onClick = {
                                update {
                                    it.copy(
                                        boardReadingFontMode = TodayBoardReadingFontMode.SLIDES_PACK,
                                        boardReadingFontPackId = state.pack.id,
                                    )
                                }
                            },
                        )
                    }
            }
            if (board.boardReadingFontMode == TodayBoardReadingFontMode.SLIDES_PACK && !selectedPackAvailable) {
                Text(stringResource(R.string.board_font_unavailable), style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.board_font_size), style = MaterialTheme.typography.bodyMedium)
                Text("${(board.deepReadFontScale * 100f).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
            }
            NotionSlider(
                value = board.deepReadFontScale.coerceIn(DEEP_READ_FONT_SCALE_MIN, DEEP_READ_FONT_SCALE_MAX),
                onValueChangeFinished = { value ->
                    update { it.copy(deepReadFontScale = value.coerceIn(DEEP_READ_FONT_SCALE_MIN, DEEP_READ_FONT_SCALE_MAX)) }
                },
                valueRange = DEEP_READ_FONT_SCALE_MIN..DEEP_READ_FONT_SCALE_MAX,
                snapStep = DEEP_READ_FONT_SCALE_STEP,
                valueLabel = { value ->
                    Text("${(value * 100f).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
                },
            )
        }
    }
}

@Composable
private fun BoardModelRow(
    board: TodayBoardSetting,
    settings: app.amber.core.settings.Settings,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
    initiallyExpanded: Boolean = false,
    showHeading: Boolean = true,
) {
    val boardModelUuid = board.boardModelId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    val boardModel: Model? = boardModelUuid?.let { uuid -> settings.findModelById(uuid) }
    val selectedProvider = boardModel?.findProviderForBoard(settings.providers)
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showHeading) {
            Text(
                stringResource(
                    // "看板" is full-app vocabulary; standalone products name the role.
                    if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.deepread_settings_gen_model
                    else R.string.board_model_title,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceLeadingIcon(
                icon = Lucide.BotMessageSquare,
                size = 32.dp,
                iconSize = 17.dp,
                tone = if (boardModel == null) app.amber.feature.ui.components.ui.WorkspaceTone.Neutral
                else app.amber.feature.ui.components.ui.WorkspaceTone.Accent,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = boardModel?.displayName ?: stringResource(
                        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.deepread_board_use_default_model
                        else R.string.board_follow_main_model,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspaceColors().ink,
                )
                Text(
                    text = selectedProvider?.name ?: stringResource(
                        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.deepread_board_follow_default_model
                        else R.string.board_using_current_model,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspaceColors().muted,
                )
            }
            Text(
                text = if (expanded) "−" else "+",
                style = LocalAmberType.current.meta.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = workspaceColors().muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.width(42.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ProviderAccordionModelPicker(
                    currentModel = boardModel?.id,
                    providers = settings.providers,
                    modelType = ModelType.CHAT,
                    clearLabel = stringResource(
                        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) R.string.deepread_board_use_default_model
                        else R.string.board_follow_main_model,
                    ),
                    onClear = { update { it.copy(boardModelId = null) } },
                    onSelect = { model -> update { it.copy(boardModelId = model.id.toString()) } },
                    dense = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalRow(current: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WorkspaceLeadingIcon(icon = Lucide.Clock, size = 32.dp, iconSize = 17.dp)
            Text(
                stringResource(R.string.board_refresh_interval),
                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.Medium),
                color = LocalAmberTokens.current.ink,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                30 to stringResource(R.string.board_interval_30_minutes),
                60 to stringResource(R.string.board_interval_1_hour),
                120 to stringResource(R.string.board_interval_2_hours),
                240 to stringResource(R.string.board_interval_4_hours),
            ).forEach { (value, label) ->
                ChoiceChip(
                    selected = current == value,
                    label = label,
                    onClick = { onChange(value) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SearchServiceSummary(enabledCount: Int, totalCount: Int) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.board_search_title), style = MaterialTheme.typography.titleSmall)
        Text(
            if (enabledCount > 0) {
                stringResource(R.string.board_search_enabled_summary, enabledCount, totalCount)
            } else {
                stringResource(R.string.board_search_required)
            },
            style = MaterialTheme.typography.bodySmall,
            color = workspaceColors().muted,
        )
    }
}

@Composable
private fun FocusRulesEditor(
    rules: List<BoardFocusRuleEntity>,
    onAdd: (String) -> Unit,
    onToggle: (BoardFocusRuleEntity, Boolean) -> Unit,
    onDelete: (BoardFocusRuleEntity) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.board_focus_points), style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.board_focus_example)) },
                maxLines = 2,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LocalAmberTokens.current.accent,
                    unfocusedBorderColor = LocalAmberTokens.current.line,
                    focusedContainerColor = LocalAmberTokens.current.raised,
                    unfocusedContainerColor = LocalAmberTokens.current.surface2,
                    cursorColor = LocalAmberTokens.current.accent,
                ),
            )
            TextButton(
                enabled = input.trim().isNotEmpty(),
                onClick = {
                    onAdd(input)
                    input = ""
                },
            ) {
                Text(stringResource(R.string.add))
            }
        }
        if (rules.isEmpty()) {
            Text(stringResource(R.string.board_focus_empty_list), style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        } else {
            rules.forEach { rule ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = rule.active, onCheckedChange = { onToggle(rule, it) })
                    Text(rule.content, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onDelete(rule) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}

@Composable
private fun SourceWeightsEditor(weights: Map<String, Int>, onChange: (sourceType: String, weight: Int) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BOARD_WEIGHT_SOURCES.forEach { source ->
            // Feishu/chat-history sources can never produce data outside the
            // full agent app — hide their weights there too.
            if (!StandaloneSurfaces.allowsAgentFeatures &&
                source.sourceType in AGENT_ONLY_SIGNAL_SOURCES) return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(source.localizedTitle(), style = MaterialTheme.typography.bodyMedium)
                        Text(source.localizedDescription(), style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
                    }
                    val value = weights[source.sourceType] ?: 0
                    Text(if (value > 0) "+$value" else value.toString(), color = workspaceColors().muted)
                }
                NotionSlider(
                    value = (weights[source.sourceType] ?: 0).toFloat(),
                    onValueChangeFinished = { onChange(source.sourceType, it.roundToInt().coerceIn(-10, 10)) },
                    valueRange = -10f..10f,
                    snapStep = 1f,
                )
            }
        }
    }
}

@Composable
private fun SourceSwitch(
    title: String,
    description: String,
    checked: Boolean,
    icon: ImageVector? = null,
    onToggle: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (description.isBlank()) 52.dp else 64.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let {
            WorkspaceLeadingIcon(icon = it, size = 32.dp, iconSize = 17.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = LocalAmberType.current.body.copy(fontWeight = FontWeight.Medium), color = tokens.ink)
            if (description.isNotBlank()) {
                Text(description, style = LocalAmberType.current.secondary, color = tokens.ink2)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            trackColor = tokens.accent,
            trackColorUnchecked = tokens.surface2,
            thumbColor = tokens.accentInk,
            thumbColorUnchecked = tokens.ink2,
        )
    }
}

@Composable
private fun IncrementalSlider(value: Int, onValueChange: (Int) -> Unit) {
    Column(Modifier.padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.board_signal_threshold), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.board_signal_threshold_value, value), style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        }
        NotionSlider(value = value.toFloat(), onValueChangeFinished = { onValueChange(it.toInt()) }, valueRange = 1f..30f)
    }
}

@Composable
private fun BackgroundStrategyRow(current: TodayBoardBackgroundStrategy, onChange: (TodayBoardBackgroundStrategy) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.board_background_strategy), style = MaterialTheme.typography.titleSmall)
        listOf(
            Triple(
                TodayBoardBackgroundStrategy.SMART,
                stringResource(R.string.board_background_smart),
                stringResource(R.string.board_background_smart_description),
            ),
            Triple(
                TodayBoardBackgroundStrategy.WIFI_ONLY,
                stringResource(R.string.board_background_wifi),
                stringResource(R.string.board_background_wifi_description),
            ),
            Triple(
                TodayBoardBackgroundStrategy.FOREGROUND_ONLY,
                stringResource(R.string.board_background_foreground),
                stringResource(R.string.board_background_foreground_description),
            ),
        ).forEach { (strategy, label, description) ->
            RadioRow(
                selected = current == strategy,
                label = label,
                description = description,
                onClick = { onChange(strategy) },
            )
        }
    }
}

@Composable
private fun RadioRow(selected: Boolean, label: String, description: String, onClick: () -> Unit) {
    val tokens = LocalAmberTokens.current
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp).padding(2.dp)) {
            Surface(
                Modifier.fillMaxSize(),
                RoundedCornerShape(50),
                color = if (selected) tokens.accent else tokens.ink3,
                content = {},
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = workspaceColors().ink)
            Text(description, style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        }
    }
}

@Composable
private fun ChoiceChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) tokens.accent else tokens.surface2,
        contentColor = if (selected) tokens.accentInk else tokens.ink2,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) tokens.accent else tokens.line2),
        modifier = modifier
            .heightIn(min = 32.dp)
            .clickable { onClick() },
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = LocalAmberType.current.secondary.copy(fontSize = 13.sp))
    }
}

private fun toggleSignalSource(
    source: String,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    update {
        it.copy(enabledSources = if (source in it.enabledSources) it.enabledSources - source else it.enabledSources + source)
    }
}

private fun Model.findProviderForBoard(providers: List<ProviderSetting>): ProviderSetting? {
    providerOverwrite?.let { return it }
    return providers.firstOrNull { provider -> provider.models.any { it.id == id } }
}

@Composable
private fun FontPackCategory.label(): String =
    when (this) {
        FontPackCategory.SERIF -> stringResource(R.string.board_font_category_serif)
        FontPackCategory.SANS -> stringResource(R.string.board_font_category_sans)
        FontPackCategory.HANDWRITING -> stringResource(R.string.board_font_category_handwriting)
        FontPackCategory.MONO -> stringResource(R.string.board_font_category_mono)
    }

private data class BoardWeightSource(
    val sourceType: String,
    val title: String,
    val description: String,
)

@Composable
private fun BoardWeightSource.localizedTitle(): String = when (sourceType) {
    BoardSignalSourceType.NOTIFICATION -> stringResource(R.string.board_signal_notification)
    BoardSignalSourceType.CALENDAR -> stringResource(R.string.board_signal_calendar)
    BoardSignalSourceType.FEISHU_MSG -> stringResource(R.string.board_signal_feishu_messages)
    BoardSignalSourceType.FEISHU_DOC -> stringResource(R.string.board_signal_feishu_docs)
    BoardSignalSourceType.CHAT_HISTORY -> stringResource(R.string.board_signal_chat_history)
    else -> title
}

@Composable
private fun BoardWeightSource.localizedDescription(): String = when (sourceType) {
    BoardSignalSourceType.NOTIFICATION -> stringResource(R.string.board_signal_notification_weight_description)
    BoardSignalSourceType.CALENDAR -> stringResource(R.string.board_signal_calendar_weight_description)
    BoardSignalSourceType.FEISHU_MSG -> stringResource(R.string.board_signal_feishu_messages_weight_description)
    BoardSignalSourceType.FEISHU_DOC -> stringResource(R.string.board_signal_feishu_docs_weight_description)
    BoardSignalSourceType.CHAT_HISTORY -> stringResource(R.string.board_signal_chat_history_weight_description)
    else -> description
}

private val BOARD_WEIGHT_SOURCES = listOf(
    BoardWeightSource(BoardSignalSourceType.NOTIFICATION, "系统通知", "设备通知、应用提醒、重要推送"),
    BoardWeightSource(BoardSignalSourceType.CALENDAR, "日历", "今日日程、会议和时间安排"),
    BoardWeightSource(BoardSignalSourceType.FEISHU_MSG, "飞书消息", "未读消息、群聊和工作沟通"),
    BoardWeightSource(BoardSignalSourceType.FEISHU_DOC, "飞书文档", "文档更新、索引和变更信号"),
    BoardWeightSource(BoardSignalSourceType.CHAT_HISTORY, "聊天记录", "最近对话中的真实待办和项目上下文"),
)

private val AGENT_ONLY_SIGNAL_SOURCES = setOf(
    BoardSignalSourceType.FEISHU_MSG,
    BoardSignalSourceType.FEISHU_DOC,
    BoardSignalSourceType.CHAT_HISTORY,
)
