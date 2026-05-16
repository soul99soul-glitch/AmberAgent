package me.rerere.rikkahub.ui.pages.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.agent.board.BoardRepository
import me.rerere.rikkahub.data.agent.board.BoardSignalSourceType
import me.rerere.rikkahub.data.agent.board.TodayBoardBackgroundStrategy
import me.rerere.rikkahub.data.agent.board.TodayBoardDensity
import me.rerere.rikkahub.data.agent.board.TodayBoardSetting
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.entity.BoardFocusRuleEntity
import me.rerere.rikkahub.data.db.entity.BoardWeightEntity
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.pages.setting.ExperimentDivider
import me.rerere.rikkahub.ui.pages.setting.ExperimentSectionCard
import me.rerere.rikkahub.ui.pages.setting.ExperimentalSettingsScaffold
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.components.ui.NotionSlider
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@Composable
fun SettingTodayBoardPage(vm: SettingVM = koinViewModel()) {
    val boardRepository: BoardRepository = koinInject()
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val focusRules by boardRepository.observeFocusRules().collectAsStateWithLifecycle(initialValue = emptyList())
    val board = settings.agentRuntime.todayBoard
    var sourceWeights by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var weightReloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(weightReloadKey) {
        sourceWeights = boardRepository.getAllWeights()
            .filter { it.keyword == BoardWeightEntity.WHOLE_SOURCE }
            .associate { it.sourceType to it.weight }
    }

    fun update(block: (TodayBoardSetting) -> TodayBoardSetting) {
        vm.updateSettings(settings.copy(
            agentRuntime = settings.agentRuntime.copy(todayBoard = block(board))
        ))
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

    val context = androidx.compose.ui.platform.LocalContext.current

    ExperimentalSettingsScaffold(title = "今日看板设置") { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentSectionCard(title = "基本设置") {
                    SourceSwitch("启用今日看板", "开启后 Agent 会定时分析信号并生成每日看板",
                        board.enabled) { update { it.copy(enabled = !it.enabled) } }
                    ExperimentDivider()
                    // Model picker for board generation
                    val boardModelUuid = board.boardModelId?.let {
                        runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
                    }
                    val boardModel: Model? = boardModelUuid?.let { uuid -> settings.findModelById(uuid) }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "看板模型",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = if (boardModel != null) "使用 ${boardModel.displayName}"
                                   else "跟随主聊天模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = workspaceColors().muted,
                        )
                        ModelSelector(
                            modelId = boardModelUuid,
                            type = ModelType.CHAT,
                            onSelect = { model ->
                                update { it.copy(boardModelId = model.id.toString()) }
                            },
                            providers = settings.providers,
                            allowClear = true,
                            emptyLabel = "跟随主聊天模型",
                            onClear = {
                                update { it.copy(boardModelId = null) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ExperimentDivider()
                    val notifPermissionOk = remember {
                        runCatching {
                            android.provider.Settings.Secure.getString(
                                context.contentResolver,
                                "enabled_notification_listeners"
                            )?.contains(context.packageName) == true
                        }.getOrDefault(false)
                    }
                    SourceSwitch(
                        "系统通知",
                        if (notifPermissionOk) "实时捕获设备通知"
                        else "⚠ 需要通知访问权限（设置 → 应用 → 特殊权限 → 通知访问）",
                        BoardSignalSourceType.NOTIFICATION in board.enabledSources
                    ) { toggleSource(BoardSignalSourceType.NOTIFICATION, ::update) }
                    ExperimentDivider()
                    val calendarPermissionOk = remember {
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.READ_CALENDAR
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    SourceSwitch(
                        "日历",
                        if (calendarPermissionOk) "读取今日日程"
                        else "⚠ 需要日历读取权限",
                        BoardSignalSourceType.CALENDAR in board.enabledSources
                    ) { toggleSource(BoardSignalSourceType.CALENDAR, ::update) }
                    ExperimentDivider()
                    SourceSwitch("飞书消息", "通过 MCP 拉取未读消息",
                        BoardSignalSourceType.FEISHU_MSG in board.enabledSources
                    ) { toggleSource(BoardSignalSourceType.FEISHU_MSG, ::update) }
                    ExperimentDivider()
                    SourceSwitch("飞书文档", "桥接文档雷达变更通知",
                        BoardSignalSourceType.FEISHU_DOC in board.enabledSources
                    ) { toggleSource(BoardSignalSourceType.FEISHU_DOC, ::update) }
                    ExperimentDivider()
                    SourceSwitch("聊天记录", "分析最近对话",
                        BoardSignalSourceType.CHAT_HISTORY in board.enabledSources
                    ) { toggleSource(BoardSignalSourceType.CHAT_HISTORY, ::update) }
                    ExperimentDivider()
                    SourceSwitch("时间锚点", "定时节点信号",
                        BoardSignalSourceType.TIME in board.enabledSources
                    ) { toggleSource(BoardSignalSourceType.TIME, ::update) }
                }
            }

            item {
                ExperimentSectionCard(title = "触发设置") {
                    DensityRow(board.density) { value -> update { it.copy(density = value) } }
                    ExperimentDivider()
                    IncrementalSlider(board.incrementalSignalThreshold) { value ->
                        update { it.copy(incrementalSignalThreshold = value) }
                    }
                }
            }

            item {
                ExperimentSectionCard(title = "后台策略") {
                    BackgroundStrategyRow(board.backgroundStrategy) { value ->
                        update { it.copy(backgroundStrategy = value) }
                    }
                }
            }

            item {
                ExperimentSectionCard(title = "关注点") {
                    FocusRulesEditor(
                        rules = focusRules,
                        onAdd = ::addFocusRule,
                        onToggle = ::updateFocusRule,
                        onDelete = ::deleteFocusRule,
                    )
                    ExperimentDivider()
                    SourceWeightsEditor(
                        weights = sourceWeights,
                        onChange = ::updateSourceWeight,
                    )
                }
            }
        }
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
    val ws = workspaceColors()

    Column(
        Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("自定义关注点", style = MaterialTheme.typography.titleSmall)
        Text(
            "用自然语言告诉看板更该关注什么；这是软提示，不会硬过滤信号。",
            style = MaterialTheme.typography.bodySmall,
            color = ws.muted,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("例如：关注飞书里和发布会有关的消息") },
                maxLines = 2,
                singleLine = false,
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
            Text("暂无关注点", style = MaterialTheme.typography.bodySmall, color = ws.muted)
        } else {
            rules.forEach { rule ->
                FocusRuleRow(
                    rule = rule,
                    onToggle = { onToggle(rule, it) },
                    onDelete = { onDelete(rule) },
                )
            }
        }
    }
}

@Composable
private fun FocusRuleRow(
    rule: BoardFocusRuleEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(checked = rule.active, onCheckedChange = onToggle)
        Text(
            text = rule.content,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (rule.active) MaterialTheme.colorScheme.onSurface else workspaceColors().muted,
        )
        TextButton(onClick = onDelete) {
            Text("删除")
        }
    }
}

@Composable
private fun SourceWeightsEditor(
    weights: Map<String, Int>,
    onChange: (sourceType: String, weight: Int) -> Unit,
) {
    val ws = workspaceColors()
    Column(
        Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("来源权重", style = MaterialTheme.typography.titleSmall)
        Text(
            "只调来源级偏好：负数少出现，正数多出现，-10 近似屏蔽。行为反馈也会微调这里；时间锚点不作为内容来源。",
            style = MaterialTheme.typography.bodySmall,
            color = ws.muted,
        )
        BOARD_WEIGHT_SOURCES.forEach { source ->
            SourceWeightRow(
                source = source,
                value = weights[source.sourceType] ?: 0,
                onChange = { onChange(source.sourceType, it) },
            )
        }
    }
}

@Composable
private fun SourceWeightRow(
    source: BoardWeightSource,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(source.title, style = MaterialTheme.typography.bodyMedium)
                Text(source.description, style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
            }
            Text(
                text = if (value > 0) "+$value" else value.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = workspaceColors().muted,
            )
        }
        NotionSlider(
            value = value.toFloat(),
            onValueChangeFinished = { onChange(it.roundToInt().coerceIn(-10, 10)) },
            valueRange = -10f..10f,
            snapStep = 1f,
        )
    }
}

@Composable
private fun SourceSwitch(title: String, description: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun DensityRow(current: TodayBoardDensity, onChange: (TodayBoardDensity) -> Unit) {
    Column(Modifier.padding(12.dp)) {
        Text("看板密度", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(6.dp))
        listOf(
            TodayBoardDensity.COMPACT to "精简 (5/3/2)",
            TodayBoardDensity.STANDARD to "标准 (8/5/3)",
            TodayBoardDensity.RICH to "详尽 (12/8/5)",
        ).forEach { (density, label) ->
            RadioRow(selected = current == density, label = label, onClick = { onChange(density) })
        }
    }
}

@Composable
private fun IncrementalSlider(value: Int, onValueChange: (Int) -> Unit) {
    Column(Modifier.padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("增量信号阈值", style = MaterialTheme.typography.titleSmall)
            Text("$value 条", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
        }
        NotionSlider(value = value.toFloat(), onValueChangeFinished = { onValueChange(it.toInt()) }, valueRange = 1f..30f)
        Text("累积达到阈值时自动触发看板更新", style = MaterialTheme.typography.bodySmall, color = workspaceColors().muted)
    }
}

@Composable
private fun BackgroundStrategyRow(current: TodayBoardBackgroundStrategy, onChange: (TodayBoardBackgroundStrategy) -> Unit) {
    Column(Modifier.padding(12.dp)) {
        listOf(
            TodayBoardBackgroundStrategy.SMART to "智能（WiFi + 电量充足）",
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

private fun toggleSource(
    source: String,
    update: (block: (TodayBoardSetting) -> TodayBoardSetting) -> Unit,
) {
    update { it.copy(enabledSources = if (source in it.enabledSources) it.enabledSources - source else it.enabledSources + source) }
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
