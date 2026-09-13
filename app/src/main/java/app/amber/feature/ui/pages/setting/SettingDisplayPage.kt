package app.amber.feature.ui.pages.setting

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import app.amber.core.settings.ChatFontFamily
import app.amber.core.settings.DisplaySetting
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.permission.PermissionManager
import app.amber.feature.ui.components.ui.permission.PermissionNotification
import app.amber.feature.ui.components.ui.permission.rememberPermissionState
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ui.IntLabel
import app.amber.feature.ui.components.ui.NotionSlider
import app.amber.feature.ui.components.ui.PercentLabel
import app.amber.feature.ui.theme.JetbrainsMono
import app.amber.feature.ui.theme.NotoSerifSC
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
private fun <T> WorkspaceSegmentedChoice(
    options: List<T>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
    label: @Composable (T) -> Unit,
) {
    val workspace = workspaceColors()
    val capsuleShape = androidx.compose.foundation.shape.CircleShape
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .pressable(onClick = { onSelected(option) }),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides if (isSelected) workspace.ink else workspace.muted) {
                    ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .clip(capsuleShape)
                                .background(if (isSelected) accent.copy(alpha = 0.10f) else workspace.row)
                                .border(1.dp, accent.copy(alpha = if (isSelected) 0.12f else 0.04f), capsuleShape)
                                .padding(horizontal = 6.dp),
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

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = SettingPageHorizontalInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SettingCardGroup(
                    title = stringResource(R.string.setting_page_message_display_settings),
                ) {
                        // V3: 聊天主题切换器已移到顶部 (替代旧 "Notion style" 项), 这里去除重复
                        item(
                            modifier = Modifier.settingTwoLine(),
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
                            modifier = Modifier.settingTwoLine(),
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
                            modifier = Modifier.settingTwoLine(),
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
                            modifier = Modifier.settingTwoLine(),
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
                                            updateDisplaySetting(displaySetting.copy(fontSizeRatio = DisplaySetting().fontSizeRatio))
                                        },
                                    )
                                }
                            },
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
                                        content = stringResource(R.string.setting_display_page_font_size_preview) + "\n\nAa Bb Cc · 0123456789",
                                        style = LocalTextStyle.current.copy(
                                            fontSize = 14.sp * displaySetting.fontSizeRatio,
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
                                        permissionState.requestPermissions()
                                    }
                                    updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = enabled))
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
                                        updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
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
                                        updateDisplaySetting(displaySetting.copy(enableAutoScroll = it))
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
                                        updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
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
                                        updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it))
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
                                            updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
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
