package me.rerere.rikkahub.ui.pages.board

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.agent.board.BoardRepository
import me.rerere.rikkahub.data.agent.board.BoardSignalSourceType
import me.rerere.rikkahub.data.agent.board.DEFAULT_HOT_LIST_FOCUS_KEYWORDS
import me.rerere.rikkahub.data.agent.board.TodayBoardBackgroundStrategy
import me.rerere.rikkahub.data.agent.board.TodayBoardHotListFilterMode
import me.rerere.rikkahub.data.agent.board.TodayBoardReadingFontMode
import me.rerere.rikkahub.data.agent.board.TodayBoardSetting
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import me.rerere.rikkahub.data.agent.board.hotlist.HotListScheduler
import me.rerere.rikkahub.data.agent.board.hotlist.normalizeHotListFocusKeywords
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.entity.BoardFocusRuleEntity
import me.rerere.rikkahub.data.db.entity.BoardWeightEntity
import me.rerere.rikkahub.data.db.entity.HotListSourceEntity
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateAgent
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateRepository
import me.rerere.rikkahub.data.font.FontPackCategory
import me.rerere.rikkahub.data.font.FontPackState
import me.rerere.rikkahub.data.font.SlidesFontRepository
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.NotionSlider
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.pages.setting.ExperimentDivider
import me.rerere.rikkahub.ui.pages.setting.ExperimentSectionCard
import me.rerere.rikkahub.ui.pages.setting.ExperimentalSettingsScaffold
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@Composable
fun SettingTodayBoardPage(vm: SettingVM = koinViewModel()) {
    val boardRepository: BoardRepository = koinInject()
    val hotListRepository: HotListRepository = koinInject()
    val hotListScheduler: HotListScheduler = koinInject()
    val deepReadTemplateRepository: DeepReadTemplateRepository = koinInject()
    val deepReadTemplateAgent: DeepReadTemplateAgent = koinInject()
    val fontRepository: SlidesFontRepository = koinInject()
    val json: Json = koinInject()
    val context = LocalContext.current
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
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var templateGenerating by rememberSaveable { mutableStateOf(false) }
    var templateGenerationError by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(weightReloadKey) {
        sourceWeights = boardRepository.getAllWeights()
            .filter { it.keyword == BoardWeightEntity.WHOLE_SOURCE }
            .associate { it.sourceType to it.weight }
    }

    LaunchedEffect(Unit) {
        deepReadTemplateRepository.reload()
    }

    fun update(block: (TodayBoardSetting) -> TodayBoardSetting) {
        vm.updateSettings(
            settings.copy(
                agentRuntime = settings.agentRuntime.copy(todayBoard = block(board))
            )
        )
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

    fun generateDeepReadTemplate(name: String, brief: String) {
        templateGenerating = true
        templateGenerationError = null
        scope.launch {
            val result = deepReadTemplateAgent.generate(name, brief)
            templateGenerating = false
            result.onSuccess { template ->
                update { it.copy(deepReadTemplateId = template.id) }
            }.onFailure { error ->
                templateGenerationError = error.message ?: "模板生成失败"
            }
        }
    }

    ExperimentalSettingsScaffold(title = "今日看板设置") { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentSectionCard(title = "热榜") {
                    IntervalRow(board.hotListRefreshIntervalMinutes) { value ->
                        update { it.copy(hotListRefreshIntervalMinutes = value) }
                    }
                    ExperimentDivider()
                    SourceSwitch("仅 WiFi", "刷新热榜时只使用未计费网络", board.hotListWifiOnly) {
                        update { it.copy(hotListWifiOnly = !it.hotListWifiOnly) }
                    }
                    ExperimentDivider()
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
                        onSaveCustom = ::saveCustomHotListSource,
                    )
                    ExperimentDivider()
                    HotListFocusKeywordEditor(
                        keywords = board.hotListFocusKeywords,
                        mode = board.hotListFilterMode,
                        onKeywordsChange = { keywords -> update { it.copy(hotListFocusKeywords = keywords) } },
                        onModeChange = { mode -> update { it.copy(hotListFilterMode = mode) } },
                    )
                    ExperimentDivider()
                    SearchServiceSummary(
                        enabledCount = settings.searchEnabledServiceIds.size,
                        totalCount = settings.searchServices.size,
                    )
                }
            }

            item {
                ExperimentSectionCard(title = "待办") {
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
                        "系统通知",
                        if (notifPermissionOk) "设备通知和应用提醒" else "⚠ 需要通知访问权限",
                        BoardSignalSourceType.NOTIFICATION in board.enabledSources,
                    ) { toggleSignalSource(BoardSignalSourceType.NOTIFICATION, ::update) }
                    ExperimentDivider()
                    SourceSwitch(
                        "日历",
                        if (calendarPermissionOk) "今日日程和会议" else "⚠ 需要日历读取权限",
                        BoardSignalSourceType.CALENDAR in board.enabledSources,
                    ) { toggleSignalSource(BoardSignalSourceType.CALENDAR, ::update) }
                    ExperimentDivider()
                    SourceSwitch("飞书消息", "未读消息和工作沟通", BoardSignalSourceType.FEISHU_MSG in board.enabledSources) {
                        toggleSignalSource(BoardSignalSourceType.FEISHU_MSG, ::update)
                    }
                    ExperimentDivider()
                    SourceSwitch("飞书文档", "文档雷达变更信号", BoardSignalSourceType.FEISHU_DOC in board.enabledSources) {
                        toggleSignalSource(BoardSignalSourceType.FEISHU_DOC, ::update)
                    }
                    ExperimentDivider()
                    SourceSwitch("聊天记录", "最近对话中的真实待办", BoardSignalSourceType.CHAT_HISTORY in board.enabledSources) {
                        toggleSignalSource(BoardSignalSourceType.CHAT_HISTORY, ::update)
                    }
                    ExperimentDivider()
                    IncrementalSlider(board.incrementalSignalThreshold) { value ->
                        update { it.copy(incrementalSignalThreshold = value) }
                    }
                    ExperimentDivider()
                    FocusRulesEditor(
                        rules = focusRules,
                        onAdd = ::addFocusRule,
                        onToggle = ::updateFocusRule,
                        onDelete = ::deleteFocusRule,
                    )
                    ExperimentDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (showAdvanced) "▾ 高级设置" else "▸ 高级设置", style = MaterialTheme.typography.titleSmall)
                        Text("来源权重", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
                    }
                    if (showAdvanced) {
                        SourceWeightsEditor(weights = sourceWeights, onChange = ::updateSourceWeight)
                    }
                }
            }

            item {
                ExperimentSectionCard(title = "通用") {
                    SourceSwitch("启用今日看板", "开启后定时分析信号、刷新热榜并生成回顾", board.enabled) {
                        update { it.copy(enabled = !it.enabled) }
                    }
                    ExperimentDivider()
                    BoardModelRow(board = board, settings = settings, update = ::update)
                    ExperimentDivider()
                    ReadingFontRow(board = board, fontStates = fontStates, update = ::update)
                    ExperimentDivider()
                    DeepReadTemplateSettingsRow(
                        board = board,
                        customTemplates = customDeepReadTemplates,
                        invalidTemplateCount = invalidDeepReadTemplateCount,
                        generating = templateGenerating,
                        generationError = templateGenerationError,
                        onSelect = { templateId -> update { it.copy(deepReadTemplateId = templateId) } },
                        onDelete = { template ->
                            scope.launch {
                                deepReadTemplateRepository.deleteTemplate(template.id)
                                if (board.deepReadTemplateId == template.id) {
                                    update { it.copy(deepReadTemplateId = me.rerere.rikkahub.data.agent.board.DeepReadTemplateIds.COMPOSE_MAGAZINE) }
                                }
                            }
                        },
                        onGenerate = ::generateDeepReadTemplate,
                    )
                    ExperimentDivider()
                    BackgroundStrategyRow(board.backgroundStrategy) { value ->
                        update { it.copy(backgroundStrategy = value) }
                    }
                }
            }
        }
    }
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
        Text("关注筛选", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                TodayBoardHotListFilterMode.ALL to "全部",
                TodayBoardHotListFilterMode.FOCUS_FIRST to "关注优先",
                TodayBoardHotListFilterMode.FOCUS_ONLY to "只看关注",
            ).forEach { (value, label) ->
                ChoiceChip(selected = mode == value, label = label, onClick = { onModeChange(value) })
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("AI、大模型、机器人、数码 3C...") },
            minLines = 2,
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = parsed.isNotEmpty(),
                onClick = { onKeywordsChange(parsed) },
            ) {
                Text("应用")
            }
            TextButton(
                onClick = {
                    draft = DEFAULT_HOT_LIST_FOCUS_KEYWORDS.joinToString("、")
                    onKeywordsChange(DEFAULT_HOT_LIST_FOCUS_KEYWORDS)
                },
            ) {
                Text("恢复推荐")
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
private fun ReadingFontRow(
    board: TodayBoardSetting,
    fontStates: List<FontPackState>,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    val installedFonts = fontStates.filter { it.installed && !it.installedPath.isNullOrBlank() }
    val selectedPackAvailable = installedFonts.any { it.pack.id == board.boardReadingFontPackId }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("深度阅读字体", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(
                selected = board.boardReadingFontMode == TodayBoardReadingFontMode.SYSTEM,
                label = "系统默认",
                onClick = { update { it.copy(boardReadingFontMode = TodayBoardReadingFontMode.SYSTEM) } },
            )
            ChoiceChip(
                selected = board.boardReadingFontMode == TodayBoardReadingFontMode.SERIF,
                label = "内置宋体",
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
            Text("已下载 Slides 字体包会显示在这里；当前深度阅读会使用内置宋体。", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        } else {
            Text("已下载 Slides 字体", style = MaterialTheme.typography.labelMedium, color = workspaceColors().muted)
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
                Text("当前选择的字体包不可用，阅读页会自动回退内置宋体。", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
            }
        }
    }
}

@Composable
private fun BoardModelRow(
    board: TodayBoardSetting,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    val boardModelUuid = board.boardModelId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    val boardModel: Model? = boardModelUuid?.let { uuid -> settings.findModelById(uuid) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("看板模型", style = MaterialTheme.typography.titleSmall)
        Text(
            if (boardModel != null) "使用 ${boardModel.displayName}" else "跟随主聊天模型",
            style = MaterialTheme.typography.bodySmall,
            color = workspaceColors().muted,
        )
        ModelSelector(
            modelId = boardModelUuid,
            type = ModelType.CHAT,
            onSelect = { model -> update { it.copy(boardModelId = model.id.toString()) } },
            providers = settings.providers,
            allowClear = true,
            emptyLabel = "跟随主聊天模型",
            onClear = { update { it.copy(boardModelId = null) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalRow(current: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("更新间隔", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30 to "30 分钟", 60 to "1 小时", 120 to "2 小时", 240 to "4 小时").forEach { (value, label) ->
                ChoiceChip(selected = current == value, label = label, onClick = { onChange(value) })
            }
        }
    }
}

@Composable
private fun SearchServiceSummary(enabledCount: Int, totalCount: Int) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("搜索引擎（深度阅读）", style = MaterialTheme.typography.titleSmall)
        Text(
            if (enabledCount > 0) "复用当前已启用的 $enabledCount / $totalCount 个搜索服务"
            else "未启用搜索服务时无法生成深度阅读",
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
        Text("关注点", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("例如：关注老板的消息") },
                maxLines = 2,
            )
            TextButton(
                enabled = input.trim().isNotEmpty(),
                onClick = {
                    onAdd(input)
                    input = ""
                },
            ) {
                Text("添加")
            }
        }
        if (rules.isEmpty()) {
            Text("暂无关注点", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        } else {
            rules.forEach { rule ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = rule.active, onCheckedChange = { onToggle(rule, it) })
                    Text(rule.content, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onDelete(rule) }) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun SourceWeightsEditor(weights: Map<String, Int>, onChange: (sourceType: String, weight: Int) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BOARD_WEIGHT_SOURCES.forEach { source ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.bodyMedium)
                        Text(source.description, style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
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
private fun SourceSwitch(title: String, description: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun IncrementalSlider(value: Int, onValueChange: (Int) -> Unit) {
    Column(Modifier.padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("信号阈值", style = MaterialTheme.typography.titleSmall)
            Text("$value 条", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        }
        NotionSlider(value = value.toFloat(), onValueChangeFinished = { onValueChange(it.toInt()) }, valueRange = 1f..30f)
    }
}

@Composable
private fun BackgroundStrategyRow(current: TodayBoardBackgroundStrategy, onChange: (TodayBoardBackgroundStrategy) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("后台策略", style = MaterialTheme.typography.titleSmall)
        listOf(
            TodayBoardBackgroundStrategy.SMART to "智能",
            TodayBoardBackgroundStrategy.WIFI_ONLY to "仅 WiFi",
            TodayBoardBackgroundStrategy.FOREGROUND_ONLY to "仅前台",
        ).forEach { (strategy, label) ->
            RadioRow(selected = current == strategy, label = label, onClick = { onChange(strategy) })
        }
    }
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).padding(2.dp)) {
            Surface(
                Modifier.fillMaxSize(),
                RoundedCornerShape(50),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                content = {},
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChoiceChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
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

private fun FontPackCategory.label(): String =
    when (this) {
        FontPackCategory.SERIF -> "宋/明体"
        FontPackCategory.SANS -> "黑体"
        FontPackCategory.HANDWRITING -> "手写"
        FontPackCategory.MONO -> "等宽"
    }

private data class BoardWeightSource(
    val sourceType: String,
    val title: String,
    val description: String,
)

private val BOARD_WEIGHT_SOURCES = listOf(
    BoardWeightSource(BoardSignalSourceType.NOTIFICATION, "系统通知", "设备通知、应用提醒、重要推送"),
    BoardWeightSource(BoardSignalSourceType.CALENDAR, "日历", "今日日程、会议和时间安排"),
    BoardWeightSource(BoardSignalSourceType.FEISHU_MSG, "飞书消息", "未读消息、群聊和工作沟通"),
    BoardWeightSource(BoardSignalSourceType.FEISHU_DOC, "飞书文档", "文档更新、索引和变更信号"),
    BoardWeightSource(BoardSignalSourceType.CHAT_HISTORY, "聊天记录", "最近对话中的真实待办和项目上下文"),
)
