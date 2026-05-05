package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.migrateLaunchStartMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceString
import me.rerere.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
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
    val workspace = workspaceColors()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, workspace.hairline, RoundedCornerShape(6.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                onClick = { onSelected(option) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(4.dp),
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
            TopAppBar(
                title = {
                    Text(stringResource(R.string.setting_display_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = workspace.paper,
                    scrolledContainerColor = workspace.paper,
                    titleContentColor = workspace.ink,
                    navigationIconContentColor = workspace.muted,
                    actionIconContentColor = workspace.blue,
                )
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
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 8.dp,
                                        topEnd = 8.dp,
                                        bottomStart = 2.dp,
                                        bottomEnd = 2.dp
                                    )
                                ),
                            headlineContent = { Text("Workspace White") },
                            supportingContent = { Text("白色纸面 + 蓝色强调，不跟随系统动态色") },
                            colors = CustomColors.listItemColors,
                        )
                    } else {
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 8.dp,
                                        topEnd = 8.dp,
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
                                    topStart = 4.dp,
                                    topEnd = 4.dp,
                                    bottomStart = 8.dp,
                                    bottomEnd = 8.dp
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
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableLiveUpdateNotification,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                                    }
                                )
                            },
                        )
                    }
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
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showUserAvatar,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showUserAvatar = it))
                                    }
                                )
                            },
                        )
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
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showTokenUsage,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(showTokenUsage = it))
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
                                                ChatFontFamily.SERIF -> FontFamily.Serif
                                                ChatFontFamily.MONOSPACE -> FontFamily.Monospace
                                            }
                                        )
                                    },
                                )
                            }
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_font_size_title)) },
                            supportingContent = {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Slider(
                                            value = displaySetting.fontSizeRatio,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(fontSizeRatio = it))
                                            },
                                            valueRange = 0.5f..2f,
                                            steps = 11,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${(displaySetting.fontSizeRatio * 100).toInt()}%")
                                    }
                                    MarkdownBlock(
                                        content = stringResource(R.string.setting_display_page_font_size_preview),
                                        style = LocalTextStyle.current.copy(
                                            fontSize = LocalTextStyle.current.fontSize * displaySetting.fontSizeRatio,
                                            lineHeight = LocalTextStyle.current.lineHeight * displaySetting.fontSizeRatio,
                                            fontFamily = when (displaySetting.chatFontFamily) {
                                                ChatFontFamily.DEFAULT -> FontFamily.Default
                                                ChatFontFamily.SERIF -> FontFamily.Serif
                                                ChatFontFamily.MONOSPACE -> FontFamily.Monospace
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Slider(
                                            value = displaySetting.pasteLongTextThreshold.toFloat(),
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
                                            },
                                            valueRange = 100f..10000f,
                                            steps = 98,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${displaySetting.pasteLongTextThreshold}")
                                    }
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Slider(
                                            value = displaySetting.volumeKeyScrollRatio,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                            },
                                            valueRange = 0.25f..1.0f,
                                            steps = 2,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(text = "${(displaySetting.volumeKeyScrollRatio * 100).toInt()}%")
                                    }
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
