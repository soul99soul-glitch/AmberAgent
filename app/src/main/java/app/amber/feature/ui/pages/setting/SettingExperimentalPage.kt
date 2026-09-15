package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import app.amber.feature.ui.components.ds.pressable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.ai.provider.Model
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.LayoutDashboard
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Newspaper
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Users
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberType
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
            contentPadding = innerPadding + PaddingValues(horizontal = SettingPageHorizontalInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_experimental_page_title),
                ) {
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalWebMount) },
                        icon = { Icon(Lucide.Globe, contentDescription = null) },
                        title = stringResource(R.string.setting_webmount_title),
                        description = stringResource(R.string.setting_webmount_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalICloud) },
                        icon = { Icon(Lucide.Server, contentDescription = null) },
                        title = stringResource(R.string.setting_icloud_title),
                        description = stringResource(R.string.setting_icloud_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingExperimentalSubAgent) },
                        icon = { Icon(Lucide.Users, contentDescription = null) },
                        title = stringResource(R.string.setting_subagent_title),
                        description = stringResource(R.string.setting_subagent_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SettingTodayBoard) },
                        icon = { Icon(Lucide.Newspaper, contentDescription = null) },
                        title = stringResource(R.string.notification_channel_today_board),
                        description = stringResource(R.string.setting_experimental_today_board_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.MiniAppSettings) },
                        icon = { Icon(Lucide.LayoutDashboard, contentDescription = null) },
                        title = stringResource(R.string.session_home_feature_mini_apps),
                        description = stringResource(R.string.setting_experimental_mini_apps_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.SynaraCompanion) },
                        icon = { Icon(Lucide.Server, contentDescription = null) },
                        title = "Synara",
                        description = stringResource(R.string.setting_experimental_synara_desc),
                    )
                    ExperimentDivider()
                    ExperimentFeatureRow(
                        onClick = { navController.navigate(Screen.ZCode) },
                        icon = { Icon(Lucide.CodeXml, contentDescription = null) },
                        title = "ZCode",
                        description = stringResource(R.string.setting_experimental_zcode_desc),
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
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingSectionTitle(title, modifier = Modifier.padding(top = 10.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth(),
            shape = shape,
            color = workspace.paper,
            contentColor = workspace.ink,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        shape = shape,
        color = workspace.paper,
        contentColor = workspace.ink,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = RoundedCornerShape(8.dp),
                color = workspace.row,
                contentColor = workspace.muted,
            ) {
                Box(modifier = Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
                    icon()
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = type.secondary,
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
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = RoundedCornerShape(8.dp),
            color = workspace.row,
            contentColor = workspace.muted,
        ) {
            Box(modifier = Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = type.body,
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = type.secondary,
                color = workspace.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ExperimentDivider(startPadding: androidx.compose.ui.unit.Dp = 38.dp) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Content rows have a 28dp leading icon and a 10dp gap; align with
            // the title instead of spanning the full card width.
            .padding(start = startPadding)
            .height(0.5.dp),
        color = workspace.hairline.copy(alpha = 0.48f),
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
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val scheme = MaterialTheme.colorScheme
    // primary 按钮用 colorScheme.primary (随应用主题色解析), 不硬编码 workspace.blue.
    val container = when {
        !enabled -> workspace.row
        primary -> scheme.primary
        else -> workspace.row
    }
    val contentColor = when {
        !enabled -> workspace.faint
        primary -> scheme.onPrimary
        else -> workspace.ink
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(if (compact) 26.dp else 28.dp)
                .clip(CircleShape)
                .background(container)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = type.tinyTag,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ExperimentStatusRow(
    label: String,
    value: String,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = type.secondary,
            color = workspace.faint,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = type.meta,
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
    val type = LocalAmberType.current
    // ready 态用 colorScheme.primary 系配色 (随应用主题色), 不硬编码 workspace.blue
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (ready) scheme.primaryContainer else workspace.row,
        contentColor = if (ready) scheme.primary else workspace.muted,
        border = BorderStroke(1.dp, if (ready) scheme.primary.copy(alpha = 0.22f) else workspace.hairline),
    ) {
        Text(
            text = "$label ${if (ready) stringResource(R.string.setting_experimental_ready) else stringResource(R.string.setting_experimental_missing)}",
            style = type.tinyTag,
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
    val type = LocalAmberType.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else workspace.row,
        contentColor = if (error) MaterialTheme.colorScheme.error else workspace.muted,
    ) {
        Text(
            text = text,
            style = type.secondary,
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
    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = title,
                navigationIcon = navigationIcon,
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        content(innerPadding)
    }
}
