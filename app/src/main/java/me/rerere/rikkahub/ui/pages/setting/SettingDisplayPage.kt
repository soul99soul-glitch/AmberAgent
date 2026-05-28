package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.LAUNCH_START_MODE_PREF
import me.rerere.rikkahub.LEGACY_CREATE_NEW_CONVERSATION_ON_START_PREF
import me.rerere.rikkahub.LaunchStartMode
import app.amber.core.settings.ChatFontFamily
import app.amber.core.settings.DisplaySetting
import me.rerere.rikkahub.migrateLaunchStartMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.WorkspaceTopBar
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceString
import me.rerere.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import me.rerere.rikkahub.ui.pages.chat.ChatThemeChoice
import me.rerere.rikkahub.ui.components.ui.IntLabel
import me.rerere.rikkahub.ui.components.ui.NotionSlider
import me.rerere.rikkahub.ui.components.ui.PercentLabel
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.NotoSerifSC
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
private fun LaunchStartMode.launchStartModeLabel(): String = when (this) {
    LaunchStartMode.AUTO -> stringResource(R.string.setting_display_page_launch_start_mode_auto)
    LaunchStartMode.LAST_SESSION -> stringResource(R.string.setting_display_page_launch_start_mode_last_session)
    LaunchStartMode.NEW_CHAT -> stringResource(R.string.setting_display_page_launch_start_mode_new_chat)
    LaunchStartMode.HOME -> stringResource(R.string.setting_display_page_launch_start_mode_home)
}

@Composable
private fun <T> WorkspaceSegmentedChoice(
    options: List<T>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
    label: @Composable (T) -> Unit,
) {
    // V3: 改胶囊形状 (CircleShape) — 之前是 6dp 圆角矩形, 视觉太"框框"
    val workspace = workspaceColors()
    val capsuleShape = androidx.compose.foundation.shape.CircleShape
    Row(
        modifier = modifier
            .clip(capsuleShape)
            .border(1.dp, workspace.hairline, capsuleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                onClick = { onSelected(option) },
                modifier = Modifier.weight(1f),
                shape = capsuleShape,
                color = if (isSelected) workspace.row else Color.Transparent,
                contentColor = if (isSelected) workspace.ink else workspace.muted,
            ) {
                CompositionLocalProvider(LocalContentColor provides LocalContentColor.current) {
                    ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                        Box(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            label(option)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatThemeChoiceCard(
    choice: ChatThemeChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = choice.instance
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(128.dp)
            .height(86.dp),
        shape = RoundedCornerShape(18.dp),
        color = theme.bg,
        contentColor = theme.ink,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) theme.accent else theme.hair,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(theme.accent),
                )
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(theme.userBubble)
                        .border(1.dp, theme.hair, androidx.compose.foundation.shape.CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(theme.modelLogoBg)
                        .border(1.dp, theme.hair, androidx.compose.foundation.shape.CircleShape),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = choice.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = theme.ink,
                    maxLines = 1,
                )
                Text(
                    text = if (theme.isDark) "深色" else "浅色",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.inkSoft,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SettingDisplayPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var amoledDarkMode by rememberAmoledDarkMode()

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val workspace = workspaceColors()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_display_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspace.canvas
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_theme_setting),
                        style = MaterialTheme.typography.titleSmall,
                        color = workspace.faint,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                    )
                    if (BuildConfig.NOTION_LIKE) {
                        val darkMode = LocalDarkMode.current
                        val themeOptions = remember(darkMode) {
                            ChatThemeChoice.choicesFor(darkMode)
                        }
                        val currentTheme = remember(displaySetting.chatThemeChoice, darkMode) {
                            ChatThemeChoice.resolve(displaySetting.chatThemeChoice, darkMode)
                        }
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = 2.dp,
                                        bottomEnd = 2.dp
                                    )
                                ),
                            headlineContent = { Text("聊天主题") },
                            supportingContent = {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (darkMode) {
                                            "深色主题; 横向滑动查看更多"
                                        } else {
                                            "浅色主题; 横向滑动查看更多"
                                        }
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(90.dp)
                                            .horizontalScroll(rememberScrollState())
                                            .padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        themeOptions.forEach { choice ->
                                            ChatThemeChoiceCard(
                                                choice = choice,
                                                selected = choice == currentTheme,
                                                onClick = {
                                                    updateDisplaySetting(
                                                        displaySetting.copy(chatThemeChoice = choice.name)
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                            colors = CustomColors.listItemColors,
                        )
                    } else {
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = 2.dp,
                                        bottomEnd = 2.dp
                                    )
                                ),
                            headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                            supportingContent = { Text(stringResource(R.string.setting_page_dynamic_color_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = settings.dynamicColor,
                                    onCheckedChange = { vm.updateSettings(settings.copy(dynamicColor = it)) },
                                )
                            },
                            colors = CustomColors.listItemColors,
                        )
                    }
                    if (!settings.dynamicColor && !BuildConfig.NOTION_LIKE) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.surfaceBright)
                        ) {
                            PresetThemeButtonGroup(
                                themeId = settings.themeId,
                                modifier = Modifier.fillMaxWidth(),
                                onChangeTheme = { vm.updateSettings(settings.copy(themeId = it)) }
                            )
                        }
                    }
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(
                                RoundedCornerShape(
                                    topStart = 2.dp,
                                    topEnd = 2.dp,
                                    bottomStart = 16.dp,
                                    bottomEnd = 16.dp
                                )
                            ),
                        headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                        trailingContent = {
                            Switch(
                                checked = amoledDarkMode,
                                onCheckedChange = { amoledDarkMode = it }
                            )
                        },
                        colors = CustomColors.listItemColors,
                    )
                }
            }

            item {
                var launchStartModeRaw by rememberSharedPreferenceString(
                    LAUNCH_START_MODE_PREF,
                    null
                )
                var legacyCreateNewConversationOnStart by rememberSharedPreferenceBoolean(
                    LEGACY_CREATE_NEW_CONVERSATION_ON_START_PREF,
                    true
                )
                val launchStartMode = migrateLaunchStartMode(
                    storedMode = launchStartModeRaw,
                    legacyCreateNewConversationOnStart = legacyCreateNewConversationOnStart,
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    title = { Text(stringResource(R.string.setting_page_general_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_launch_start_mode_title)) },
                        supportingContent = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.setting_display_page_launch_start_mode_desc))
                                WorkspaceSegmentedChoice(
                                    options = LaunchStartMode.entries,
                                    selected = launchStartMode,
                                    modifier = Modifier.fillMaxWidth(),
                                    onSelected = { mode ->
                                        launchStartModeRaw = mode.name
                                        legacyCreateNewConversationOnStart = mode == LaunchStartMode.NEW_CHAT
                                    },
                                    label = { mode ->
                                        Text(
                                            text = mode.launchStartModeLabel(),
                                            maxLines = 1,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    },
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableNotificationOnMessageGeneration,
                                onCheckedChange = {
                                    if (it && !permissionState.allPermissionsGranted) {
                                        permissionState.requestPermissions()
                                    }
                                    updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                                }
                            )
                        },
                    )
                }
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_page_message_display_settings)) },
                    ) {
                        // V3: 聊天主题切换器已移到顶部 (替代旧 "Notion style" 项), 这里去除重复
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showAssistantBubble,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showAssistantBubble = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showModelIcon,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showModelIcon = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_model_name_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_model_name_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showModelName,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showModelName = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_date_below_name_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_date_below_name_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showDateBelowName,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showDateBelowName = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showThinkingContent,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showThinkingContent = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.autoCloseThinking,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(autoCloseThinking = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableLatexRendering,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableLatexRendering = it))
                                    }
                                )
                            },
                        )
                        val chatFontFamilyOptions = listOf(
                            ChatFontFamily.DEFAULT to stringResource(R.string.setting_display_page_chat_font_family_default),
                            ChatFontFamily.SERIF to stringResource(R.string.setting_display_page_chat_font_family_serif),
                            ChatFontFamily.MONOSPACE to stringResource(R.string.setting_display_page_chat_font_family_monospace),
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
                            supportingContent = {
                                WorkspaceSegmentedChoice(
                                    options = chatFontFamilyOptions,
                                    selected = chatFontFamilyOptions.first { it.first == displaySetting.chatFontFamily },
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .fillMaxWidth(),
                                    onSelected = { (family, _) ->
                                        updateDisplaySetting(displaySetting.copy(chatFontFamily = family))
                                    },
                                    label = { (family, label) ->
                                        Text(
                                            text = label,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                            fontFamily = when (family) {
                                                ChatFontFamily.DEFAULT -> FontFamily.Default
                                                ChatFontFamily.SERIF -> NotoSerifSC
                                                ChatFontFamily.MONOSPACE -> JetbrainsMono
                                            }
                                        )
                                    },
                                )
                            }
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_font_size_title)) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // Range 0.5..2.0 with 5% snap → {50, 55, ..., 200}%.
                                    NotionSlider(
                                        value = displaySetting.fontSizeRatio,
                                        onValueChangeFinished = {
                                            updateDisplaySetting(displaySetting.copy(fontSizeRatio = it))
                                        },
                                        valueRange = 0.5f..2.0f,
                                        snapStep = 0.05f,
                                        valueLabel = { PercentLabel(it) },
                                    )
                                    MarkdownBlock(
                                        content = stringResource(R.string.setting_display_page_font_size_preview),
                                        style = LocalTextStyle.current.copy(
                                            fontSize = LocalTextStyle.current.fontSize * displaySetting.fontSizeRatio,
                                            lineHeight = LocalTextStyle.current.lineHeight * displaySetting.fontSizeRatio,
                                            fontFamily = when (displaySetting.chatFontFamily) {
                                                ChatFontFamily.DEFAULT -> FontFamily.Default
                                                ChatFontFamily.SERIF -> NotoSerifSC
                                                ChatFontFamily.MONOSPACE -> JetbrainsMono
                                            }
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_code_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoWrap,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(codeBlockAutoWrap = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoCollapse,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(codeBlockAutoCollapse = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showLineNumbers,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showLineNumbers = it))
                                }
                            )
                        },
                    )
                }
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_page_interaction_notification_settings)) },
                    ) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.sendOnEnter,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(sendOnEnter = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showMessageJumper,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
                                    }
                                )
                            },
                        )
                        if (displaySetting.showMessageJumper) {
                            item(
                                headlineContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_title)) },
                                supportingContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = displaySetting.messageJumperOnLeft,
                                        onCheckedChange = {
                                            updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it))
                                        }
                                    )
                                },
                            )
                        }
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableAutoScroll,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableAutoScroll = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_bottom_follow_animation_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_bottom_follow_animation_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showBottomFollowAnimation,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showBottomFollowAnimation = it))
                                    }
                                )
                            },
                        )
                        if (!BuildConfig.NOTION_LIKE) {
                            item(
                                headlineContent = { Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_title)) },
                                supportingContent = {
                                    Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_desc))
                                },
                                trailingContent = {
                                    Switch(
                                        checked = displaySetting.useAppIconStyleLoadingIndicator,
                                        onCheckedChange = {
                                            updateDisplaySetting(displaySetting.copy(useAppIconStyleLoadingIndicator = it))
                                        }
                                    )
                                },
                            )
                            item(
                                headlineContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_title)) },
                                supportingContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = displaySetting.enableBlurEffect,
                                        onCheckedChange = {
                                            updateDisplaySetting(displaySetting.copy(enableBlurEffect = it))
                                        }
                                    )
                                },
                            )
                        }
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableMessageGenerationHapticEffect,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.skipCropImage,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(skipCropImage = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.pasteLongTextAsFile,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it))
                                    }
                                )
                            },
                        )
                        if (displaySetting.pasteLongTextAsFile) {
                            item(
                                headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                                supportingContent = {
                                    // Range 100..10000 chars, 100-char step. Single int value
                                    // shown on the right; the Material slider previously had
                                    // 99 stop dots which read as visual noise.
                                    NotionSlider(
                                        value = displaySetting.pasteLongTextThreshold.toFloat(),
                                        onValueChangeFinished = {
                                            updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
                                        },
                                        valueRange = 100f..10000f,
                                        snapStep = 100f,
                                        valueLabel = { IntLabel(it) },
                                    )
                                },
                            )
                        }
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableVolumeKeyScroll,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableVolumeKeyScroll = it))
                                    }
                                )
                            },
                        )
                        if (displaySetting.enableVolumeKeyScroll) {
                            item(
                                headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_ratio)) },
                                supportingContent = {
                                    // Range 25%..100%, 25% snap → {25, 50, 75, 100}%.
                                    NotionSlider(
                                        value = displaySetting.volumeKeyScrollRatio,
                                        onValueChangeFinished = {
                                            updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                        },
                                        valueRange = 0.25f..1.0f,
                                        snapStep = 0.25f,
                                        valueLabel = { PercentLabel(it) },
                                    )
                                }
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_tts_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.ttsOnlyReadQuoted,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadQuoted = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.autoPlayTTSAfterGeneration,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(autoPlayTTSAfterGeneration = it))
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}
