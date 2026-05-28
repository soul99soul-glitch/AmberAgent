package me.rerere.rikkahub.ui.pages.miniapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import me.rerere.rikkahub.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import app.amber.core.settings.MiniAppSetting
import app.amber.core.settings.prefs.SettingsAggregator
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniAppSettingsPage(
    groupRoute: String? = null,
    settingsStore: SettingsAggregator = koinInject(),
) {
    val navController = LocalNavController.current
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val miniApp = settings.agentRuntime.miniApp
    val scope = rememberCoroutineScope()
    val group = MiniAppSettingGroup.fromRoute(groupRoute)

    fun updateMiniApp(update: (MiniAppSetting) -> MiniAppSetting) {
        scope.launch {
            settingsStore.update { current ->
                current.copy(
                    agentRuntime = current.agentRuntime.copy(
                        miniApp = update(current.agentRuntime.miniApp),
                    )
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.title ?: "小应用设置") },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (group == null) {
                item {
                    MiniAppSwitchRow(
                        title = "启用小应用",
                        description = "允许 Amber 生成、保存并运行轻量 HTML 工具",
                        checked = miniApp.enabled,
                        onCheckedChange = { enabled -> updateMiniApp { it.copy(enabled = enabled) } },
                        prominent = true,
                    )
                }
                item {
                    MiniAppGroupCard(
                        title = MiniAppSettingGroup.Common.title,
                        description = "网络、图片、搜索与剪贴板写入",
                        onClick = { navController.navigate(Screen.MiniAppSettingsDetail(MiniAppSettingGroup.Common.route)) },
                    )
                }
                item {
                    MiniAppGroupCard(
                        title = MiniAppSettingGroup.HostAi.title,
                        description = "AI 调用、上下文读取、写回宿主与看板摘要",
                        onClick = { navController.navigate(Screen.MiniAppSettingsDetail(MiniAppSettingGroup.HostAi.route)) },
                    )
                }
                item {
                    MiniAppGroupCard(
                        title = MiniAppSettingGroup.Advanced.title,
                        description = "存储、事件、跳转、设备能力与调试入口",
                        onClick = { navController.navigate(Screen.MiniAppSettingsDetail(MiniAppSettingGroup.Advanced.route)) },
                    )
                }
            } else {
                item {
                    MiniAppGroupIntro(group)
                }
                when (group) {
                    MiniAppSettingGroup.Common -> {
                        item {
                            MiniAppSwitchRow(
                                title = "网络请求",
                                description = "允许声明 network 权限的小应用通过 Amber.fetch 访问 HTTPS",
                                checked = miniApp.networkEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(networkEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "外链图片",
                                description = "允许声明 externalImages 权限的小应用通过 Native 代理加载 HTTPS 图片",
                                checked = miniApp.externalImagesEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(externalImagesEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "搜索",
                                description = "允许声明 search 权限的小应用调用 Amber.search 获取结构化结果",
                                checked = miniApp.searchEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(searchEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "复制到剪贴板",
                                description = "允许声明 clipboard.copy 权限的小应用写入剪贴板",
                                checked = miniApp.clipboardCopyEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(clipboardCopyEnabled = enabled) } },
                            )
                        }
                    }
                    MiniAppSettingGroup.HostAi -> {
                        item {
                            MiniAppSwitchRow(
                                title = "Amber.ai",
                                description = "允许声明 ai.generate 权限的小应用在确认后调用当前聊天模型",
                                checked = miniApp.aiEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(aiEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "读取最小上下文",
                                description = "允许声明 host.context 权限的小应用在确认后读取最小化会话上下文",
                                checked = miniApp.hostContextEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(hostContextEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "写回宿主",
                                description = "允许声明 host.sendToConversation / host.createArtifact 的小应用在确认后写回",
                                checked = miniApp.hostWriteEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(hostWriteEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "更新看板摘要",
                                description = "允许声明 host.updateBoardSummary 权限的小应用更新自己的摘要字段",
                                checked = miniApp.boardSummaryUpdateEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(boardSummaryUpdateEnabled = enabled) } },
                            )
                        }
                    }
                    MiniAppSettingGroup.Advanced -> {
                        item {
                            MiniAppSwitchRow(
                                title = "共享存储",
                                description = "允许声明 sharedStore 权限的小应用访问隔离 namespace KV",
                                checked = miniApp.sharedStoreEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(sharedStoreEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "事件总线",
                                description = "允许声明 eventBus 权限的小应用在同 namespace 内收发临时事件",
                                checked = miniApp.eventBusEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(eventBusEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "小应用跳转",
                                description = "允许声明 launch 权限的小应用在确认后打开已保存的小应用",
                                checked = miniApp.launchEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(launchEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "传感器",
                                description = "允许声明 sensor 权限的小应用在确认后订阅加速度/陀螺仪/光照",
                                checked = miniApp.sensorEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(sensorEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "定位",
                                description = "允许声明 location 权限的小应用在确认后读取位置",
                                checked = miniApp.locationEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(locationEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "读取剪贴板",
                                description = "允许声明 clipboard.read 权限的小应用在确认后读取剪贴板文本",
                                checked = miniApp.clipboardReadEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(clipboardReadEnabled = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "显示源码入口",
                                description = "在 Runner 菜单里显示只读源码查看",
                                checked = miniApp.showSourceButton,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(showSourceButton = enabled) } },
                            )
                        }
                        item {
                            MiniAppSwitchRow(
                                title = "WebView 调试",
                                description = "仅调试时开启，允许通过系统 WebView 调试工具检查小应用",
                                checked = miniApp.webViewDebugEnabled,
                                enabled = miniApp.enabled,
                                onCheckedChange = { enabled -> updateMiniApp { it.copy(webViewDebugEnabled = enabled) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniAppGroupCard(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val colors = workspaceColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = colors.paper,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiniAppGroupIntro(group: MiniAppSettingGroup) {
    val colors = workspaceColors()
    Column(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(group.title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        Text(group.description, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
private fun MiniAppSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    val colors = workspaceColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        shape = RoundedCornerShape(if (prominent) 18.dp else 14.dp),
        color = colors.paper,
    ) {
        ListItem(
            headlineContent = {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Switch(
                    modifier = Modifier.scale(if (prominent) 0.92f else 0.78f),
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange,
                )
            },
        )
    }
}

private enum class MiniAppSettingGroup(
    val route: String,
    val title: String,
    val description: String,
) {
    Common(
        route = "common",
        title = "常用能力",
        description = "小应用最常用的网络、图片、搜索和剪贴板写入能力。",
    ),
    HostAi(
        route = "host_ai",
        title = "宿主与 AI",
        description = "涉及模型调用、会话上下文和写回宿主的高权限能力。",
    ),
    Advanced(
        route = "advanced",
        title = "高级与调试",
        description = "更偏开发者和实验能力，默认建议谨慎开启。",
    ),
    ;

    companion object {
        fun fromRoute(route: String?): MiniAppSettingGroup? =
            entries.firstOrNull { it.route == route }
    }
}
