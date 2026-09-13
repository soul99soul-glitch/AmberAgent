package app.amber.feature.ui.pages.miniapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.settings.MiniAppSetting
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.LiveDot
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.SwitchSize
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

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
                    ),
                )
            }
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = if (group == null) {
                    androidx.compose.ui.res.stringResource(R.string.miniapp_settings)
                } else {
                    androidx.compose.ui.res.stringResource(group.titleRes)
                },
                navigationIcon = { BackButton() },
            )
        },
        modifier = Modifier.amberCanvas(),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (group == null) {
                item {
                    AmberCard(modifier = Modifier.fillMaxWidth()) {
                        MiniAppSwitchRow(
                            title = androidx.compose.ui.res.stringResource(R.string.miniapp_enable_title),
                            description = androidx.compose.ui.res.stringResource(R.string.miniapp_enable_description),
                            checked = miniApp.enabled,
                            onCheckedChange = { enabled -> updateMiniApp { it.copy(enabled = enabled) } },
                            prominent = true,
                        )
                    }
                }
                item {
                    SectionLabel(
                        text = androidx.compose.ui.res.stringResource(R.string.miniapp_settings),
                        modifier = Modifier.padding(top = 20.dp, start = 2.dp, bottom = 2.dp),
                    )
                }
                item {
                    AmberCard(modifier = Modifier.fillMaxWidth()) {
                        MiniAppGroupCard(
                            icon = Lucide.Globe,
                            title = androidx.compose.ui.res.stringResource(MiniAppSettingGroup.Common.titleRes),
                            description = androidx.compose.ui.res.stringResource(MiniAppSettingGroup.Common.descriptionRes),
                            onClick = {
                                navController.navigate(Screen.MiniAppSettingsDetail(MiniAppSettingGroup.Common.route))
                            },
                        )
                        Hairline()
                        MiniAppGroupCard(
                            icon = Lucide.Bot,
                            title = androidx.compose.ui.res.stringResource(MiniAppSettingGroup.HostAi.titleRes),
                            description = androidx.compose.ui.res.stringResource(MiniAppSettingGroup.HostAi.descriptionRes),
                            onClick = {
                                navController.navigate(Screen.MiniAppSettingsDetail(MiniAppSettingGroup.HostAi.route))
                            },
                        )
                        Hairline()
                        MiniAppGroupCard(
                            icon = Lucide.Wrench,
                            title = androidx.compose.ui.res.stringResource(MiniAppSettingGroup.Advanced.titleRes),
                            description = androidx.compose.ui.res.stringResource(MiniAppSettingGroup.Advanced.descriptionRes),
                            onClick = {
                                navController.navigate(Screen.MiniAppSettingsDetail(MiniAppSettingGroup.Advanced.route))
                            },
                        )
                    }
                }
            } else {
                item { MiniAppGroupIntro(group) }
                item {
                    AmberCard(modifier = Modifier.fillMaxWidth()) {
                        MiniAppCapabilityRows(
                            group = group,
                            enabled = miniApp.enabled,
                            miniApp = miniApp,
                            updateMiniApp = ::updateMiniApp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniAppCapabilityRows(
    group: MiniAppSettingGroup,
    enabled: Boolean,
    miniApp: MiniAppSetting,
    updateMiniApp: ((MiniAppSetting) -> MiniAppSetting) -> Unit,
) {
    when (group) {
        MiniAppSettingGroup.Common -> {
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_network_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_network_description),
                checked = miniApp.networkEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(networkEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_external_images_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_external_images_description),
                checked = miniApp.externalImagesEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(externalImagesEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_search_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_search_description),
                checked = miniApp.searchEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(searchEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_clipboard_copy_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_clipboard_copy_description),
                checked = miniApp.clipboardCopyEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(clipboardCopyEnabled = value) } },
            )
        }
        MiniAppSettingGroup.HostAi -> {
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_ai_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_ai_description),
                checked = miniApp.aiEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(aiEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_host_context_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_host_context_description),
                checked = miniApp.hostContextEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(hostContextEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_host_write_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_host_write_description),
                checked = miniApp.hostWriteEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(hostWriteEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_board_summary_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_board_summary_description),
                checked = miniApp.boardSummaryUpdateEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(boardSummaryUpdateEnabled = value) } },
            )
        }
        MiniAppSettingGroup.Advanced -> {
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_shared_storage_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_shared_storage_description),
                checked = miniApp.sharedStoreEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(sharedStoreEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_event_bus_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_event_bus_description),
                checked = miniApp.eventBusEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(eventBusEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_launch_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_launch_description),
                checked = miniApp.launchEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(launchEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_sensor_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_sensor_description),
                checked = miniApp.sensorEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(sensorEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_location_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_location_description),
                checked = miniApp.locationEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(locationEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_clipboard_read_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_clipboard_read_description),
                checked = miniApp.clipboardReadEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(clipboardReadEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = "系统交互",
                description = "允许已声明权限的小应用调用振动、设备、屏幕、语音、分享与外链；二维码也受此开关控制",
                checked = miniApp.systemCapabilitiesEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(systemCapabilitiesEnabled = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_source_button_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_source_button_description),
                checked = miniApp.showSourceButton,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(showSourceButton = value) } },
            )
            Hairline()
            MiniAppSettingRow(
                title = androidx.compose.ui.res.stringResource(R.string.miniapp_webview_debug_title),
                description = androidx.compose.ui.res.stringResource(R.string.miniapp_webview_debug_description),
                checked = miniApp.webViewDebugEnabled,
                enabled = enabled,
                onCheckedChange = { value -> updateMiniApp { it.copy(webViewDebugEnabled = value) } },
            )
        }
    }
}

@Composable
private fun MiniAppGroupCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics { contentDescription = "$title. $description" }
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceLeadingIcon(
            icon = icon,
            size = 32.dp,
            iconSize = 18.dp,
            tone = WorkspaceTone.Neutral,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = type.secondary,
                color = tokens.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Lucide.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tokens.ink3,
        )
    }
}

@Composable
private fun MiniAppGroupIntro(group: MiniAppSettingGroup) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel(text = androidx.compose.ui.res.stringResource(group.titleRes))
        Text(
            text = androidx.compose.ui.res.stringResource(group.descriptionRes),
            style = type.secondary,
            color = tokens.ink2,
        )
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
    MiniAppSettingRow(
        title = title,
        description = description,
        checked = checked,
        enabled = enabled,
        prominent = prominent,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun MiniAppSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics { contentDescription = "$title. $description" }
            .pressable(onClick = { if (enabled) onCheckedChange(!checked) }, enabled = enabled)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (prominent) {
                    LiveDot(idle = !checked, dotSize = 7.dp)
                }
                Text(
                    text = title,
                    style = type.body.copy(fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal),
                    color = tokens.ink.copy(alpha = if (enabled) 1f else 0.42f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = description,
                style = type.secondary.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = tokens.ink2.copy(alpha = if (enabled) 1f else 0.5f),
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            size = SwitchSize.Small,
            onCheckedChange = onCheckedChange,
            trackColor = tokens.accent,
            trackColorUnchecked = tokens.surface2,
            thumbColor = tokens.accentInk,
            thumbColorUnchecked = tokens.ink3,
        )
    }
}

private enum class MiniAppSettingGroup(
    val route: String,
    val titleRes: Int,
    val descriptionRes: Int,
) {
    Common(
        route = "common",
        titleRes = R.string.miniapp_group_common_title,
        descriptionRes = R.string.miniapp_group_common_description,
    ),
    HostAi(
        route = "host_ai",
        titleRes = R.string.miniapp_group_host_ai_title,
        descriptionRes = R.string.miniapp_group_host_ai_description,
    ),
    Advanced(
        route = "advanced",
        titleRes = R.string.miniapp_group_advanced_title,
        descriptionRes = R.string.miniapp_group_advanced_description,
    ),
    ;

    companion object {
        fun fromRoute(route: String?): MiniAppSettingGroup? = entries.firstOrNull { it.route == route }
    }
}
