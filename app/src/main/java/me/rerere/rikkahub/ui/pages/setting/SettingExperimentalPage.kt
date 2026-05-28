package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DashboardSquare01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.News01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import app.amber.feature.office.FeishuOfficeAnalysisTemplate
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import app.amber.core.utils.base64Encode
import app.amber.core.utils.navigateToChatPage
import app.amber.core.utils.plus

@Composable
fun SettingExperimentalPage() {
    val navController = LocalNavController.current

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_experimental_page_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_experimental_page_title),
                ) {
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalWebMount) },
                        icon = { Icon(HugeIcons.Globe02, contentDescription = null) },
                        title = stringResource(R.string.setting_webmount_title),
                        description = stringResource(R.string.setting_webmount_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalICloud) },
                        icon = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                        title = stringResource(R.string.setting_icloud_title),
                        description = stringResource(R.string.setting_icloud_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalOfficePro) },
                        icon = { Icon(HugeIcons.File02, contentDescription = null) },
                        title = stringResource(R.string.setting_officepro_title),
                        description = stringResource(R.string.setting_officepro_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalSubAgent) },
                        icon = { Icon(HugeIcons.File02, contentDescription = null) },
                        title = stringResource(R.string.setting_subagent_title),
                        description = stringResource(R.string.setting_subagent_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingTodayBoard) },
                        icon = { Icon(HugeIcons.News01, contentDescription = null) },
                        title = "今日看板",
                        description = "Agent 主动整理每日信号，生成待办与关注项",
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.MiniAppSettings) },
                        icon = { Icon(HugeIcons.DashboardSquare01, contentDescription = null) },
                        title = "小应用",
                        description = "让 Amber 生成、保存并运行轻量 HTML 工具",
                    )
                    // Model Council top-level entry removed — it's now reachable from inside the
                    // SubAgent settings page as an "advanced" section (it's effectively a
                    // multi-model variant of @oracle). Route Screen.SettingExperimentalModelCouncil
                    // is preserved for the in-page jump button.
                }
            }
        }
    }
}

@Composable
internal fun ExperimentSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val workspace = workspaceColors()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = workspace.faint,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = workspace.paper,
            contentColor = workspace.ink,
            border = BorderStroke(1.dp, workspace.hairline),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun ExperimentHeroCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    trailing: @Composable () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = workspace.paper,
        contentColor = workspace.ink,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(8.dp),
                color = workspace.row,
                contentColor = workspace.muted,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    icon()
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun ExperimentFeatureRow(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(8.dp),
            color = workspace.row,
            contentColor = workspace.muted,
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ExperimentDivider() {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // V3 settings-experimental.jsx HairDivider indent={60} —— 让 divider 对齐到 leading icon 右边线
            .padding(start = 60.dp)
            .height(1.dp),
        color = workspace.hairline,
    ) {}
}

@Composable
internal fun ExperimentActionRow(
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
internal fun ExperimentActionButton(
    text: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    // V3: primary 按钮跟主题 (Paper 砖红 / Whisper 天蓝 / Plain 黑 / Midnight 靛蓝),
    // 不再硬编码 workspace.blue.
    val container = when {
        !enabled -> workspace.row
        primary -> scheme.primary
        else -> workspace.paper
    }
    val contentColor = when {
        !enabled -> workspace.faint
        primary -> scheme.onPrimary
        else -> workspace.ink
    }
    Surface(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = contentColor,
        border = if (primary || !enabled) null else BorderStroke(1.dp, workspace.hairline),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
internal fun ExperimentStatusRow(
    label: String,
    value: String,
) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = workspace.faint,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = workspace.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ExperimentBooleanPill(
    label: String,
    ready: Boolean,
) {
    val workspace = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    // V3: ready 跟主题 (Paper 砖红 / Whisper 天蓝 等), 不硬编码 workspace.blue
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (ready) scheme.primaryContainer else workspace.row,
        contentColor = if (ready) scheme.primary else workspace.muted,
        border = BorderStroke(1.dp, if (ready) scheme.primary.copy(alpha = 0.22f) else workspace.hairline),
    ) {
        Text(
            text = "$label ${if (ready) stringResource(R.string.setting_experimental_ready) else stringResource(R.string.setting_experimental_missing)}",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun ExperimentNote(
    text: String,
    error: Boolean = false,
) {
    val workspace = workspaceColors()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else workspace.row,
        contentColor = if (error) MaterialTheme.colorScheme.error else workspace.muted,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun ExperimentalSettingsScaffold(
    title: String,
    navigationIcon: @Composable () -> Unit = { BackButton() },
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val workspace = workspaceColors()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = navigationIcon,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = workspace.paper,
                    scrolledContainerColor = workspace.paper,
                    titleContentColor = workspace.ink,
                    navigationIconContentColor = workspace.muted,
                    actionIconContentColor = workspace.blue,
                ),
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspace.canvas,
    ) { innerPadding ->
        content(innerPadding)
    }
}
