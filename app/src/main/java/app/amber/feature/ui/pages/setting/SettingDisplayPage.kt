package app.amber.feature.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.LAUNCH_START_MODE_PREF
import app.amber.agent.LEGACY_CREATE_NEW_CONVERSATION_ON_START_PREF
import app.amber.agent.LaunchStartMode
import app.amber.agent.R
import app.amber.agent.migrateLaunchStartMode
import app.amber.feature.ui.hooks.containsPreference
import app.amber.feature.ui.hooks.readBooleanPreference
import app.amber.feature.ui.hooks.rememberSharedPreferenceString
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import app.amber.core.settings.ChatFontFamily
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.ReplyDeliveryMode
import app.amber.core.settings.TextWeight
import app.amber.feature.ui.components.message.chatMessageTextStyle
import app.amber.feature.ui.components.message.composeFamily
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.permission.PermissionManager
import app.amber.feature.ui.components.ui.permission.PermissionNotification
import app.amber.feature.ui.components.ui.permission.rememberPermissionState
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.IntLabel
import app.amber.feature.ui.components.ui.NotionSlider
import app.amber.feature.ui.components.ui.PercentLabel
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
private fun <T> TypographySettingChoice(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    fontFamily: (T) -> FontFamily? = { null },
    label: @Composable (T) -> String,
) {
    val labelStyle = MaterialTheme.typography.bodySmall.copy(
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    SettingSegmentedChoice(
        options = options,
        selected = selected,
        onSelected = onSelected,
        modifier = Modifier.padding(top = 4.dp),
    ) { option ->
        Text(
            text = label(option),
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            style = labelStyle,
            fontFamily = fontFamily(option),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SettingDisplayPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val context = LocalContext.current
    var storedLaunchStartMode by rememberSharedPreferenceString(LAUNCH_START_MODE_PREF)
    val legacyCreateNewOnStart = remember(context) {
        if (context.containsPreference(LEGACY_CREATE_NEW_CONVERSATION_ON_START_PREF)) {
            context.readBooleanPreference(LEGACY_CREATE_NEW_CONVERSATION_ON_START_PREF, true)
        } else {
            null
        }
    }
    val effectiveLaunchStartMode = migrateLaunchStartMode(
        storedMode = storedLaunchStartMode,
        legacyCreateNewConversationOnStart = legacyCreateNewOnStart,
    )
    val selectedLaunchStartOption = when (effectiveLaunchStartMode) {
        LaunchStartMode.AUTO -> LaunchStartMode.LAST_SESSION
        else -> effectiveLaunchStartMode
    }

    fun updateDisplaySetting(transform: DisplaySetting.() -> DisplaySetting) {
        displaySetting = displaySetting.transform()
        vm.updateSettings { current -> current.copy(displaySetting = current.displaySetting.transform()) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    // 通知开关：系统授权确认后才写 ON，避免「开关已开但通知权限被拒」的静默失效。
    // 拒绝时开关保持 OFF；rationale 弹窗与「去设置」引导由 PermissionManager 处理。
    var pendingNotificationEnable by remember { mutableStateOf(false) }
    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (permissionState.allPermissionsGranted && pendingNotificationEnable) {
            pendingNotificationEnable = false
            updateDisplaySetting { copy(enableNotificationOnMessageGeneration = true) }
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_display_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = Color.Transparent
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = SettingPageHorizontalInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SettingCardGroup(title = stringResource(R.string.setting_typography_group)) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_typography_weight)) },
                        supportingContent = {
                            TypographySettingChoice(
                                options = TextWeight.entries,
                                selected = displaySetting.textWeight,
                                onSelected = { weight -> updateDisplaySetting { copy(textWeight = weight) } },
                            ) { weight ->
                                stringResource(
                                    when (weight) {
                                        TextWeight.SYSTEM -> R.string.setting_typography_weight_system
                                        TextWeight.LIGHTER -> R.string.setting_typography_weight_lighter
                                        TextWeight.HEAVIER -> R.string.setting_typography_weight_heavier
                                    },
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
                        supportingContent = {
                            TypographySettingChoice(
                                options = ChatFontFamily.entries,
                                selected = displaySetting.chatFontFamily,
                                onSelected = { family -> updateDisplaySetting { copy(chatFontFamily = family) } },
                                fontFamily = { it.composeFamily },
                            ) { family ->
                                stringResource(
                                    when (family) {
                                        ChatFontFamily.DEFAULT -> R.string.setting_typography_family_system
                                        ChatFontFamily.SERIF -> R.string.setting_display_page_chat_font_family_serif
                                        ChatFontFamily.MONOSPACE -> R.string.setting_display_page_chat_font_family_monospace
                                    },
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.setting_display_page_font_size_title),
                                    modifier = Modifier.weight(1f),
                                )
                                ProviderGhostButton(
                                    text = stringResource(R.string.setting_model_page_reset_to_default),
                                    imageVector = Lucide.RotateCcw,
                                    onClick = {
                                        updateDisplaySetting { copy(fontSizeRatio = DisplaySetting().fontSizeRatio) }
                                    },
                                )
                            }
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                var previewSizeRatio by remember(displaySetting.fontSizeRatio) {
                                    mutableStateOf(displaySetting.fontSizeRatio)
                                }
                                NotionSlider(
                                    value = displaySetting.fontSizeRatio,
                                    onValueChange = { previewSizeRatio = it },
                                    onValueChangeFinished = {
                                        previewSizeRatio = it
                                        updateDisplaySetting { copy(fontSizeRatio = it) }
                                    },
                                    valueRange = 0.5f..2.0f,
                                    snapStep = 0.05f,
                                    valueLabel = { PercentLabel(it) },
                                )
                                MarkdownBlock(
                                    content = stringResource(R.string.setting_typography_preview),
                                    style = chatMessageTextStyle(
                                        MaterialTheme.typography.bodyLarge,
                                        displaySetting.copy(fontSizeRatio = previewSizeRatio),
                                    ),
                                )
                            }
                        },
                    )
                }
            }

            item {
                val launchStartOptions = listOf(
                    Triple(
                        LaunchStartMode.PERSONAL_AGENT,
                        stringResource(R.string.setting_display_launch_start_mode_personal_agent),
                        stringResource(R.string.setting_display_launch_start_mode_personal_agent_desc),
                    ),
                    Triple(
                        LaunchStartMode.HOME,
                        stringResource(R.string.setting_display_launch_start_mode_home),
                        stringResource(R.string.setting_display_launch_start_mode_home_desc),
                    ),
                    Triple(
                        LaunchStartMode.LAST_SESSION,
                        stringResource(R.string.setting_display_launch_start_mode_last_session),
                        stringResource(R.string.setting_display_launch_start_mode_last_session_desc),
                    ),
                    Triple(
                        LaunchStartMode.NEW_CHAT,
                        stringResource(R.string.setting_display_launch_start_mode_new_chat),
                        stringResource(R.string.setting_display_launch_start_mode_new_chat_desc),
                    ),
                )
                SettingCardGroup(
                    title = stringResource(R.string.setting_display_launch_start_mode_title),
                    modifier = Modifier.selectableGroup(),
                ) {
                    launchStartOptions.forEach { (mode, label, description) ->
                        val isSelected = selectedLaunchStartOption == mode
                        item(
                            modifier = Modifier
                                .settingTwoLine()
                                .testTag("launch-start-mode-${mode.name}")
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { storedLaunchStartMode = mode.name },
                                ),
                            headlineContent = { Text(label) },
                            supportingContent = { Text(description) },
                            trailingContent = {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null,
                                )
                            },
                        )
                    }
                }
            }

            item {
                val isSegmentedDelivery = displaySetting.replyDeliveryMode == ReplyDeliveryMode.SEGMENTED_BLOCKS
                SettingCardGroup(
                    title = stringResource(R.string.setting_page_message_display_settings),
                ) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_reply_delivery_mode_title)) },
                            supportingContent = {
                                TypographySettingChoice(
                                    options = ReplyDeliveryMode.entries,
                                    selected = displaySetting.replyDeliveryMode,
                                    onSelected = { mode ->
                                        updateDisplaySetting { copy(replyDeliveryMode = mode) }
                                    },
                                ) { mode ->
                                    stringResource(
                                        when (mode) {
                                            ReplyDeliveryMode.LIVE_STREAMING -> R.string.setting_display_reply_delivery_live
                                            ReplyDeliveryMode.SEGMENTED_BLOCKS -> R.string.setting_display_reply_delivery_segmented
                                        },
                                    )
                                }
                            },
                        )
                        item(
                            modifier = Modifier.settingTwoLine(),
                            headlineContent = { Text(stringResource(R.string.setting_display_agent_bubble_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_agent_bubble_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showAssistantBubble,
                                    onCheckedChange = {
                                        updateDisplaySetting { copy(showAssistantBubble = it) }
                                    }
                                )
                            },
                        )
                        item(
                            modifier = Modifier.settingTwoLine(),
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showUserAvatar,
                                    onCheckedChange = {
                                        updateDisplaySetting { copy(showUserAvatar = it) }
                                    }
                                )
                            },
                        )
                        item(
                            modifier = Modifier.settingTwoLine(),
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_model_name_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_model_name_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showModelName,
                                    onCheckedChange = {
                                        updateDisplaySetting { copy(showModelName = it) }
                                    }
                                )
                            },
                        )
                        item(
                            modifier = Modifier.settingTwoLine(),
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_title)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (isSegmentedDelivery) {
                                            R.string.setting_display_thinking_disabled_in_segmented_desc
                                        } else {
                                            R.string.setting_display_page_show_thinking_content_desc
                                        },
                                    ),
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.effectiveShowThinkingContent(),
                                    enabled = !isSegmentedDelivery,
                                    onCheckedChange = {
                                        if (!isSegmentedDelivery) {
                                            updateDisplaySetting { copy(showThinkingContent = it) }
                                        }
                                    },
                                )
                            },
                        )
                }
            }

            item {
                SettingCardGroup(
                    title = stringResource(R.string.setting_page_interaction_notification_settings),
                ) {
                    item(
                        modifier = Modifier.settingTwoLine(),
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableNotificationOnMessageGeneration,
                                onCheckedChange = { enabled ->
                                    if (enabled && !permissionState.allPermissionsGranted) {
                                        pendingNotificationEnable = true
                                        permissionState.requestPermissions()
                                    } else {
                                        if (!enabled) pendingNotificationEnable = false
                                        updateDisplaySetting { copy(enableNotificationOnMessageGeneration = enabled) }
                                    }
                                },
                            )
                        },
                    )
                        item(
                            modifier = Modifier.settingTwoLine(),
                            headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.showMessageJumper,
                                    onCheckedChange = {
                                        updateDisplaySetting { copy(showMessageJumper = it) }
                                    }
                                )
                            },
                        )
                        item(
                            modifier = Modifier.settingTwoLine(),
                            headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableAutoScroll,
                                    onCheckedChange = {
                                        updateDisplaySetting { copy(enableAutoScroll = it) }
                                    }
                                )
                            },
                        )
                        item(
                            modifier = Modifier.settingTwoLine(),
                            headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableMessageGenerationHapticEffect,
                                    onCheckedChange = {
                                        updateDisplaySetting { copy(enableMessageGenerationHapticEffect = it) }
                                    }
                                )
                            },
                        )
                        item(
                            modifier = Modifier.settingTwoLine(),
                            headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.pasteLongTextAsFile,
                                    onCheckedChange = {
                                        updateDisplaySetting { copy(pasteLongTextAsFile = it) }
                                    }
                                )
                            },
                        )
                        if (displaySetting.pasteLongTextAsFile) {
                            item(
                                modifier = Modifier.settingTwoLine(),
                                headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                                supportingContent = {
                                    // Range 100..10000 chars, 100-char step. Single int value
                                    // shown on the right; the Material slider previously had
                                    // 99 stop dots which read as visual noise.
                                    NotionSlider(
                                        value = displaySetting.pasteLongTextThreshold.toFloat(),
                                        onValueChangeFinished = {
                                            updateDisplaySetting { copy(pasteLongTextThreshold = it.toInt()) }
                                        },
                                        valueRange = 100f..10000f,
                                        snapStep = 100f,
                                        valueLabel = { IntLabel(it) },
                                    )
                                },
                            )
                        }
                }
            }

        }
    }

}
