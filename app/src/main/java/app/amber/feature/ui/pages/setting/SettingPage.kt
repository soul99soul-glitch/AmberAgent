package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Developer
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.WavingHand01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import app.amber.core.settings.isNotConfigured
import app.amber.core.files.FilesManager
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.Select
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.hooks.rememberColorMode
import app.amber.feature.ui.theme.ColorMode
import app.amber.feature.ui.theme.CustomColors
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    val workspace = workspaceColors()
    val settingListColors = ListItemDefaults.colors(
        containerColor = workspace.paper,
        headlineColor = workspace.ink,
        supportingColor = workspace.muted,
        leadingIconColor = workspace.muted,
        trailingIconColor = workspace.muted,
    )

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.settings),
                navigationIcon = { BackButton() },
                actions = {
                    if (settings.developerMode) {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.Developer)
                            }
                        ) {
                            Icon(HugeIcons.Developer, "Developer")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspace.canvas
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            item("generalSettings") {
                var colorMode by rememberColorMode()
                val selectedColorModeText = when (colorMode) {
                    ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                    ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                    ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    title = { Text(stringResource(R.string.setting_page_general_settings)) },
                    colors = settingListColors,
                ) {
                    item(
                        leadingContent = { SettingLeadingIcon(HugeIcons.Sun01) },
                        trailingContent = {
                            Select(
                                options = ColorMode.entries,
                                selectedOption = colorMode,
                                onOptionSelected = {
                                    colorMode = it
                                    navController.navigate(Screen.Setting) {
                                        popUpTo(Screen.Setting) {
                                            inclusive = true
                                        }
                                    }
                                },
                                optionToString = {
                                    when (it) {
                                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                    }
                                },
                                // V3 ValueChip 内容自适应（不硬宽 150dp）
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_color_mode)) },
                        supportingContent = { Text(selectedColorModeText) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingDisplay) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.Settings03) },
                        supportingContent = { Text(stringResource(R.string.setting_page_display_setting_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_display_setting)) },
                    )
                }
            }

            item("agentRuntimeSettings") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    title = { Text(stringResource(R.string.setting_page_agent_runtime)) },
                    colors = settingListColors,
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingAgentMemory) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.Brain02) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_memory_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_memory)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAgentExtensions) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.Package) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_extensions_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_extensions)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAgentExecution) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.LookTop) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_execution_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_execution)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAgentPermissions) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.Alert01) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_permissions_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_permissions)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSandbox) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.Code) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_sandbox_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_sandbox)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingExperimental) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.Rocket01) },
                        supportingContent = { Text(stringResource(R.string.setting_page_experimental_features_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_experimental_features)) },
                    )
                }
            }

            item("modelServices") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    title = { Text(stringResource(R.string.setting_page_model_and_services)) },
                    colors = settingListColors,
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.Brain02) },
                        supportingContent = { Text(stringResource(R.string.setting_page_providers_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.AiMagic) },
                        supportingContent = { Text(stringResource(R.string.setting_page_default_model_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.GlobalSearch) },
                        supportingContent = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTTS) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.Megaphone01) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tts_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tts_service)) },
                    )
                    // V3: 隐藏 "Web 服务器 / 外部服务器" 入口 (用户暂不需要).
                    // 改 SHOW_WEB_SERVER = true 即可恢复.
                    @Suppress("ConstantConditionIf", "KotlinConstantConditions")
                    val SHOW_WEB_SERVER = false
                    if (SHOW_WEB_SERVER) {
                        item(
                            onClick = { navController.navigate(Screen.SettingWeb) },
                            leadingContent = { SettingLeadingIcon(HugeIcons.ServerStack01) },
                            supportingContent = { Text(stringResource(R.string.setting_page_web_server_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                        )
                    }
                }
            }

            item("dataSettings") {
                val storageState by produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    title = { Text(stringResource(R.string.setting_page_data_settings)) },
                    colors = settingListColors,
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.Database02) },
                        supportingContent = { Text("Google 云同步、本地加密备份与完整数据恢复") },
                        headlineContent = { Text("同步与备份") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFiles) },
                        leadingContent = { SettingLeadingIcon(HugeIcons.ImageUpload) },
                        supportingContent = {
                            if (storageState.first == -1) {
                                Text(stringResource(R.string.calculating))
                            } else {
                                Text(
                                    stringResource(
                                        R.string.setting_page_chat_storage_desc,
                                        storageState.first,
                                        storageState.second / 1024 / 1024.0
                                    )
                                )
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_chat_storage)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingLeadingIcon(
    icon: ImageVector,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    // V3 settings-screen.jsx: stroke icon 21dp 无底色容器
    WorkspaceLeadingIcon(
        icon = icon,
        size = 32.dp,
        iconSize = 21.dp,
        tone = tone,
    )
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    val workspace = workspaceColors()
    Card(
        modifier = Modifier.padding(2.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = workspace.amberContainer
        ),
        border = BorderStroke(1.dp, workspace.amber.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.setting_page_config_api_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    SettingLeadingIcon(HugeIcons.Alert01, tone = WorkspaceTone.Warning)
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = workspace.ink,
                    supportingColor = workspace.muted,
                )
            )

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}
