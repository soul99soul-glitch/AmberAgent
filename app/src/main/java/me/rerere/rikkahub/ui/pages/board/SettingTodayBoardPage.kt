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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.agent.board.BoardSignalSourceType
import me.rerere.rikkahub.data.agent.board.TodayBoardBackgroundStrategy
import me.rerere.rikkahub.data.agent.board.TodayBoardDensity
import me.rerere.rikkahub.data.agent.board.TodayBoardSetting
import androidx.compose.runtime.remember
import me.rerere.rikkahub.ui.pages.setting.ExperimentDivider
import me.rerere.rikkahub.ui.pages.setting.ExperimentSectionCard
import me.rerere.rikkahub.ui.pages.setting.ExperimentalSettingsScaffold
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.components.ui.NotionSlider
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.pages.setting.ExperimentDivider
import me.rerere.rikkahub.ui.pages.setting.ExperimentSectionCard
import me.rerere.rikkahub.ui.pages.setting.ExperimentalSettingsScaffold
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingTodayBoardPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val board = settings.agentRuntime.todayBoard

    fun update(block: (TodayBoardSetting) -> TodayBoardSetting) {
        vm.updateSettings(settings.copy(
            agentRuntime = settings.agentRuntime.copy(todayBoard = block(board))
        ))
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
                    Column(Modifier.padding(12.dp)) {
                        workspaceColors().let { ws ->
                            Text("关注点规则将在后续版本中支持编辑", style = MaterialTheme.typography.bodySmall, color = ws.muted)
                        }
                    }
                }
            }
        }
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
