package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChartNoAxesColumnIncreasing
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Braces
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.DatabaseZap
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Grid2x2
import com.composables.icons.lucide.ImageUp
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Pen
import com.composables.icons.lucide.ScanSearch
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SquareCode
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.WandSparkles
import com.composables.icons.lucide.Wrench
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.settings.isNotConfigured
import app.amber.core.files.FilesManager
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.Select
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.hooks.rememberColorMode
import app.amber.feature.ui.theme.ColorMode
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.Locale

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
                            Icon(Lucide.SquareCode, "Developer")
                        }
                    }
                    IconButton(
                        onClick = { navController.navigate(Screen.Profile) }
                    ) {
                        UIAvatar(
                            name = settings.displaySetting.userNickname.ifBlank {
                                stringResource(R.string.user_default_name)
                            },
                            value = settings.displaySetting.userAvatar,
                            size = 32.dp,
                            showEditBadge = false,
                        )
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
                SectionLabel(
                    text = stringResource(R.string.setting_page_general_settings),
                    modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    colors = settingListColors,
                ) {
                    item(
                        leadingContent = { SettingLeadingIcon(Lucide.Sun) },
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
                            )
                        },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_appearance)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingDisplay) },
                        leadingContent = { SettingLeadingIcon(Lucide.Settings) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_display_setting)) },
                        trailingContent = { SettingChevron() },
                    )
                }
            }

            item("agentRuntimeSettings") {
                SectionLabel(
                    text = stringResource(R.string.setting_page_agent_runtime),
                    modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    colors = settingListColors,
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingAgentMemory) },
                        leadingContent = { SettingLeadingIcon(Lucide.Brain) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_agent_memory)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAgentExecution) },
                        leadingContent = { SettingLeadingIcon(Lucide.CodeXml) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_agent_execution)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTts) },
                        leadingContent = { SettingLeadingIcon(Lucide.AudioLines) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_tts)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAgentExtensions) },
                        leadingContent = { SettingLeadingIcon(Lucide.Wrench) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_agent_extensions)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAgentPermissions) },
                        leadingContent = { SettingLeadingIcon(Lucide.TriangleAlert) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_agent_permissions)) },
                        trailingContent = { SettingChevron() },
                    )
                }
            }

            item("modelServices") {
                SectionLabel(
                    text = stringResource(R.string.setting_page_model_and_services),
                    modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    colors = settingListColors,
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { SettingLeadingIcon(Lucide.Server) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_providers)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { SettingLeadingIcon(Lucide.WandSparkles) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_default_model)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { SettingLeadingIcon(Lucide.ScanSearch) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_search_service)) },
                        trailingContent = { SettingChevron() },
                    )
                }
            }

            item("advancedFeatures") {
                SectionLabel(
                    text = stringResource(R.string.setting_page_advanced_features),
                    modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    colors = settingListColors,
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingExperimentalWebMount) },
                        leadingContent = { SettingLeadingIcon(Lucide.Globe) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_webmount)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingExperimentalSubAgent) },
                        leadingContent = { SettingLeadingIcon(Lucide.Users) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_subagent_title)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingExperimentalModelCouncil) },
                        leadingContent = { SettingLeadingIcon(Lucide.MessageCircle) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_model_council)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.MiniAppList) },
                        leadingContent = { SettingLeadingIcon(Lucide.Grid2x2) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_miniapp)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.NovelProjects) },
                        leadingContent = { SettingLeadingIcon(Lucide.Pen) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_novel)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.TodayBoard) },
                        leadingContent = { SettingLeadingIcon(Lucide.BookOpenText) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_deep_read)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingExperimentalICloud) },
                        leadingContent = { SettingLeadingIcon(Lucide.Cloud) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_icloud_title)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SynaraCompanion) },
                        leadingContent = { SettingLeadingIcon(Lucide.Cpu) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_synara)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.ZCode) },
                        leadingContent = { SettingLeadingIcon(Lucide.Braces) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_zcode)) },
                        trailingContent = { SettingChevron() },
                    )
                }
            }

            item("dataSettings") {
                val storageState by produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }
                SectionLabel(
                    text = stringResource(R.string.setting_page_data_settings),
                    modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    colors = settingListColors,
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { SettingLeadingIcon(Lucide.DatabaseZap) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_backup)) },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFiles) },
                        leadingContent = { SettingLeadingIcon(Lucide.ImageUp) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (storageState.first == -1) {
                                    Text(
                                        stringResource(R.string.calculating),
                                        style = LocalAmberType.current.meta,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    // Machine-fact (count · size) → mono（design §3）；数字+单位 locale 无关，
                                    // 定长 US 格式避免本地化长句把 trailing 撑爆（EN/RU 曾溢出 6-31dp）。
                                    Text(
                                        text = "${storageState.first} · " +
                                            "%.2f MB".format(Locale.US, storageState.second / 1024 / 1024.0),
                                        style = LocalAmberType.current.meta,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                SettingChevron()
                            }
                        },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_chat_storage)) },
                    )
                    item(
                        // P7-03：存储占用与按时间清理会话。
                        onClick = { navController.navigate(Screen.SettingStorage) },
                        leadingContent = { SettingLeadingIcon(Lucide.ChartNoAxesColumnIncreasing) },
                        headlineContent = { SettingRowTitle(stringResource(R.string.setting_page_storage_cleanup)) },
                        trailingContent = { SettingChevron() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingChevron() {
    Icon(
        Lucide.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = workspaceColors().faint,
    )
}

@Composable
private fun SettingLeadingIcon(
    icon: ImageVector,
    tone: WorkspaceTone = WorkspaceTone.Accent,
) {
    // 一级设置行对齐 iOS 单行样式：accent tinted 圆角容器 + 21dp stroke icon
    WorkspaceLeadingIcon(
        icon = icon,
        size = 32.dp,
        iconSize = 21.dp,
        tone = tone,
    )
}

@Composable
private fun SettingRowTitle(text: String) {
    Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    SettingLeadingIcon(Lucide.TriangleAlert, tone = WorkspaceTone.Warning)
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
