package app.amber.feature.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.files.FilesManager
import app.amber.core.settings.isNotConfigured
import app.amber.core.settings.themeContrast
import app.amber.feature.macgateway.GatewayEndpointRole
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.hooks.rememberColorMode
import app.amber.feature.ui.theme.ColorMode
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.LocalThemeDesign
import app.amber.feature.ui.theme.LocalThemePack
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.ChartNoAxesColumnIncreasing
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.Contrast
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.DatabaseZap
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Grid2x2
import com.composables.icons.lucide.ImageUp
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MonitorSmartphone
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.SquareCode
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Wrench
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.Locale

private data class SettingHomeEntry(
    @StringRes val title: Int,
    val icon: ImageVector,
    val screen: Screen,
    @StringRes val subtitle: Int? = null,
    val summary: String? = null,
    val summaryStyle: TextStyle? = null,
)

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val colorMode by rememberColorMode()
    val filesManager: FilesManager = koinInject()
    val gatewayRepository: MacGatewayRepository = koinInject()
    val cloudGateway by gatewayRepository.slot(GatewayEndpointRole.CLOUD).state.collectAsStateWithLifecycle()
    val macGateway by gatewayRepository.slot(GatewayEndpointRole.MAC).state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val hasCustomCanvas = LocalThemePack.current != null || LocalThemeDesign.current != null
    Scaffold(
        modifier = if (hasCustomCanvas) Modifier.amberCanvas() else Modifier.background(colors.background),
        containerColor = Color.Transparent,
        topBar = {
            SettingHomeTopBar(developerMode = settings.developerMode) {
                UIAvatar(
                    name = settings.displaySetting.userNickname.ifBlank {
                        stringResource(R.string.user_default_name)
                    },
                    value = settings.displaySetting.userAvatar,
                    size = 38.dp,
                    showEditBadge = false,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = SettingPageHorizontalInset,
                end = SettingPageHorizontalInset,
                top = 24.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (settings.isNotConfigured()) {
                item { ProviderConfigWarningCard(navController) }
            }

            item("generalSettings") {
                val cloudPaired = cloudGateway.connection != null
                val macPaired = macGateway.connection != null
                SettingHomeGroup(
                    R.string.setting_page_general_settings,
                    navController,
                    SettingHomeEntry(
                        R.string.setting_page_appearance,
                        Lucide.Contrast,
                        Screen.SettingAppearance,
                        summary = stringResource(
                            when (colorMode) {
                                ColorMode.SYSTEM -> R.string.setting_page_color_mode_system
                                ColorMode.LIGHT -> R.string.setting_page_color_mode_light
                                ColorMode.DARK -> R.string.setting_page_color_mode_dark
                            },
                        ),
                    ),
                    SettingHomeEntry(R.string.setting_page_display_setting, Lucide.SlidersHorizontal, Screen.SettingDisplay),
                    SettingHomeEntry(
                        R.string.setting_home_multi_device_title,
                        Lucide.MonitorSmartphone,
                        Screen.SettingExperimentalMacGateway,
                        subtitle = R.string.setting_home_multi_device_subtitle,
                        summary = when {
                            cloudPaired && macPaired -> stringResource(R.string.setting_home_multi_device_subtitle)
                            cloudPaired -> stringResource(R.string.personal_agent_host_cloud)
                            macPaired -> "Mac"
                            else -> null
                        },
                    ),
                )
            }

            item("agentRuntimeSettings") {
                SettingHomeGroup(
                    R.string.setting_page_agent_runtime,
                    navController,
                    SettingHomeEntry(R.string.setting_page_agent_memory, Lucide.Database, Screen.SettingAgentMemory),
                    SettingHomeEntry(R.string.setting_page_agent_execution, Lucide.Terminal, Screen.SettingAgentExecution),
                    SettingHomeEntry(R.string.setting_page_agent_extensions, Lucide.Wrench, Screen.SettingAgentExtensions),
                    SettingHomeEntry(R.string.setting_page_agent_permissions, Lucide.Shield, Screen.SettingAgentPermissions),
                )
            }

            item("modelServices") {
                SettingHomeGroup(
                    R.string.setting_page_model_and_services,
                    navController,
                    SettingHomeEntry(R.string.setting_page_providers, Lucide.Server, Screen.SettingProvider),
                    SettingHomeEntry(R.string.setting_page_default_model, Lucide.Cpu, Screen.SettingModels),
                    SettingHomeEntry(R.string.setting_page_search_service, Lucide.Search, Screen.SettingSearch),
                    SettingHomeEntry(R.string.setting_page_tts, Lucide.AudioLines, Screen.SettingTts),
                    SettingHomeEntry(R.string.setting_page_jev, Lucide.Sparkles, Screen.SettingJev),
                )
            }

            item("advancedFeatures") {
                SettingHomeGroup(
                    R.string.setting_page_advanced_features,
                    navController,
                    SettingHomeEntry(R.string.setting_page_webmount, Lucide.Globe, Screen.SettingExperimentalWebMount),
                    SettingHomeEntry(R.string.setting_subagent_title, Lucide.Users, Screen.SettingExperimentalSubAgent),
                    SettingHomeEntry(R.string.setting_page_model_council, Lucide.MessageCircle, Screen.SettingExperimentalModelCouncil),
                    SettingHomeEntry(R.string.setting_page_miniapp, Lucide.Grid2x2, Screen.MiniAppList),
                )
            }

            item("dataSettings") {
                val (fileCount, storageBytes) = produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }.value
                val storageSummary = if (fileCount == -1) {
                    stringResource(R.string.calculating)
                } else {
                    "%d · %.2f MB".format(Locale.US, fileCount, storageBytes / (1024 * 1024.0))
                }
                SettingHomeGroup(
                    R.string.setting_page_data_settings,
                    navController,
                    SettingHomeEntry(R.string.setting_page_backup, Lucide.DatabaseZap, Screen.Backup),
                    SettingHomeEntry(R.string.setting_icloud_title, Lucide.Cloud, Screen.SettingExperimentalICloud),
                    SettingHomeEntry(
                        R.string.setting_page_chat_storage,
                        Lucide.ImageUp,
                        Screen.SettingChatStorage,
                        summary = storageSummary,
                        summaryStyle = LocalAmberType.current.meta,
                    ),
                    SettingHomeEntry(
                        R.string.setting_page_storage_cleanup,
                        Lucide.ChartNoAxesColumnIncreasing,
                        Screen.SettingStorage,
                    ),
                )
            }

            item("about") {
                SettingHomeGroup(
                    R.string.app_name,
                    navController,
                    SettingHomeEntry(R.string.about_page_title, Lucide.Info, Screen.SettingAbout),
                )
            }
        }
    }
}

@Composable
private fun SettingHomeTopBar(
    developerMode: Boolean,
    avatar: @Composable () -> Unit,
) {
    val navController = LocalNavController.current
    val colors = MaterialTheme.colorScheme
    val profileLabel = stringResource(R.string.profile_title)
    val titleInset = if (developerMode) 96.dp else 48.dp
    val chevronScale = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = SettingPageHorizontalInset, vertical = 8.dp)
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.settings),
            modifier = Modifier
                .padding(horizontal = titleInset)
                .semantics { heading() },
            style = LocalAmberType.current.screenTitle.copy(fontSize = 20.sp, lineHeight = 26.sp),
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        if (navController.canPop) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Box(
                    modifier = Modifier.size(44.dp).border(1.dp, colors.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.ChevronLeft,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(22.dp).graphicsLayer { scaleX = chevronScale },
                        tint = colors.onSurface,
                    )
                }
            }
        }
        Row(modifier = Modifier.align(Alignment.CenterEnd)) {
            if (developerMode) {
                IconButton(onClick = { navController.navigate(Screen.Developer) }) {
                    Icon(
                        Lucide.SquareCode,
                        contentDescription = stringResource(R.string.redesign_developer),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = { navController.navigate(Screen.Profile) },
                modifier = Modifier.semantics { contentDescription = profileLabel },
                content = avatar,
            )
        }
    }
}

@Composable
private fun SettingHomeGroup(
    @StringRes title: Int,
    navController: Navigator,
    vararg entries: SettingHomeEntry,
) {
    val colors = MaterialTheme.colorScheme
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(LocalThemeDesign.current?.components?.cardRadius?.toFloat()?.dp ?: 18.dp)
    val secondaryColor = tokens.ink3.takeIf {
        themeContrast(it.toArgb(), colors.background.toArgb()) >= 4.5 &&
            themeContrast(it.toArgb(), colors.surface.toArgb()) >= 4.5
    } ?: colors.onSurfaceVariant
    val accentTint = colors.primaryContainer
    val accentInk = when {
        themeContrast(colors.primary.toArgb(), accentTint.toArgb()) >= 3.0 -> colors.primary
        themeContrast(tokens.accentLabel.toArgb(), accentTint.toArgb()) >= 3.0 -> tokens.accentLabel
        else -> colors.onSurface
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(title),
            modifier = Modifier.semantics { heading() },
            style = type.secondary.copy(fontWeight = FontWeight.SemiBold),
            color = secondaryColor,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column {
                entries.forEachIndexed { index, entry ->
                    SettingHomeRow(entry, secondaryColor, accentInk, accentTint) {
                        navController.navigate(entry.screen)
                    }
                    if (index < entries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = colors.onSurface.copy(alpha = 0.08f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingHomeRow(
    entry: SettingHomeEntry,
    secondaryColor: Color,
    accentInk: Color,
    accentTint: Color,
    onClick: () -> Unit,
) {
    val type = LocalAmberType.current
    val secondaryStyle = type.secondary.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val chevronScale = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(accentTint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(entry.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = accentInk)
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(entry.title),
                    style = type.body.copy(fontSize = 16.sp, lineHeight = 22.sp),
                )
                entry.subtitle?.let {
                    Text(text = stringResource(it), style = secondaryStyle, color = secondaryColor)
                }
            }
            entry.summary?.let {
                Text(
                    text = it,
                    modifier = Modifier.fillMaxWidth(0.40f),
                    style = entry.summaryStyle ?: secondaryStyle,
                    color = secondaryColor,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp).graphicsLayer { scaleX = chevronScale },
            tint = secondaryColor,
        )
    }
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    val workspace = workspaceColors()
    Card(
        modifier = Modifier.padding(2.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = workspace.amberContainer,
        ),
        border = BorderStroke(1.dp, workspace.amber.copy(alpha = 0.18f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            WorkspaceLeadingIcon(Lucide.TriangleAlert, tone = WorkspaceTone.Warning)
            Text(
                text = stringResource(R.string.setting_page_config_api_title),
                modifier = Modifier.weight(1f),
                color = workspace.ink,
            )
            TextButton(
                onClick = { navController.navigate(Screen.SettingProvider) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_page_config),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
