package me.rerere.rikkahub.ui.pages.miniapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.MiniAppSetting
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniAppSettingsPage(
    settingsStore: SettingsAggregator = koinInject(),
) {
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val miniApp = settings.agentRuntime.miniApp
    val scope = rememberCoroutineScope()

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
                title = { Text("小应用设置") },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                MiniAppSwitchRow(
                    title = "启用小应用",
                    description = "允许 Amber 生成、保存并运行轻量 HTML 工具",
                    checked = miniApp.enabled,
                    onCheckedChange = { enabled -> updateMiniApp { it.copy(enabled = enabled) } },
                )
            }
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
            item {
                MiniAppSwitchRow(
                    title = "更新看板摘要",
                    description = "允许声明 host.updateBoardSummary 权限的小应用更新自己的摘要字段",
                    checked = miniApp.boardSummaryUpdateEnabled,
                    enabled = miniApp.enabled,
                    onCheckedChange = { enabled -> updateMiniApp { it.copy(boardSummaryUpdateEnabled = enabled) } },
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

@Composable
private fun MiniAppSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}
