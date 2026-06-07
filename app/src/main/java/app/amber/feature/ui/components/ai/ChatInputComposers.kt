package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FullScreen
import app.amber.agent.R
import app.amber.feature.subagent.SubAgentMode
import app.amber.core.model.reasoningLevelForModel
import app.amber.core.model.withReasoningLevelForModel
import app.amber.core.settings.defaultReasoningLevelForModel
import app.amber.core.settings.getCurrentAssistant
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.getQuickMessagesOfAssistant
import app.amber.core.files.FilesManager
import app.amber.core.files.SkillManager
import app.amber.core.files.SkillMetadata
import app.amber.core.model.QuickMessage
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.hooks.ChatInputState
import org.koin.compose.koinInject
import java.util.Locale

/**
 * Composer text-input row + slash-command panel + @role mention panel,
 * plus all the pure helpers (slash-command detection / build / filter,
 * mention context detection / replacement). Extracted from the ChatInput
 * god class so the main composable stays focused on layout + state wiring.
 *
 * Only `TextInputRow` is `internal` (call site: main `ChatInput()`). Every
 * other helper / composable / data class / sealed interface stays `private`
 * file-private to this file.
 *
 * Cross-file dependencies on the same package:
 *  - `FullScreenEditor` (defined in ChatInputAttachments.kt) — invoked when
 *    the user taps the FullScreen icon inside the TextField.
 *  - `MentionRoleItem` / `buildMentionRoleItems` / `filterMentionRoleItems`
 *    (defined in MentionRoles.kt) — the data model + filter for `@xxx`.
 *
 * AnimatedVisibility is intentionally invoked via fully-qualified call form
 * (`androidx.compose.animation.AnimatedVisibility(...)` + `fadeIn` / slide /
 * tween) matching the pre-refactor source byte-for-byte. The package-wide
 * import isn't pulled in here to keep the diff and behavior identical.
 */

private const val MAX_SLASH_COMMANDS = 9
private const val MAX_SLASH_COMMAND_TITLE_CHARS = 32
private const val DYNAMIC_SLASH_COMMAND_MIN_QUERY_CHARS = 2
private const val MAX_MENTIONS = 9
private const val SLASH_COMMAND_PANEL_EXIT_MS = 130
private const val MENTION_PANEL_EXIT_MS = 100

@Composable
internal fun TextInputRow(
    state: ChatInputState,
    onSendMessage: () -> Unit,
    onUsageClick: () -> Unit,
    onCompactContext: () -> Unit,
    modifier: Modifier = Modifier,
    minimalChrome: Boolean = false,
    hidePlaceholder: Boolean = false,
    // V3: SlashCommandPanel footer 需要 commit reasoningLevel 到当前 assistant.
    // 为 null 时 (sandbox / 历史预览等场景) footer 不渲染. ChatInput 调用处必传.
    onUpdateAssistant: ((app.amber.core.model.Assistant) -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    val skillManager: SkillManager = koinInject()
    val assistant = settings.getCurrentAssistant()
    val workspace = workspaceColors()
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }
    val enabledSkills by produceState(
        initialValue = emptyList<SkillMetadata>(),
        key1 = assistant.enabledSkills,
        key2 = skillManager,
    ) {
        value = if (assistant.enabledSkills.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                skillManager.listSkills()
                    .filter { skill -> skill.name in assistant.enabledSkills }
                    .sortedBy { skill -> skill.name.lowercase(Locale.getDefault()) }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val slashQuery = state.textContent.text.toString().slashCommandQuery()
        val allSlashCommands = remember(
            quickMessages,
            enabledSkills,
            settings.agentRuntime.subAgent.enabled,
            settings.agentRuntime.subAgent.mode,
            settings.agentRuntime.modelCouncil.enabled,
        ) {
            buildSlashCommandItems(
                quickMessages = quickMessages,
                enabledSkills = enabledSkills,
                subAgentEnabled = settings.agentRuntime.subAgent.enabled,
                subAgentMode = settings.agentRuntime.subAgent.mode,
            )
        }
        val slashCommands = remember(allSlashCommands, slashQuery) {
            slashQuery?.let { query ->
                filterSlashCommandItems(allSlashCommands, query)
            }.orEmpty()
        }
        val receiveContentListener = remember(
            settings.displaySetting.pasteLongTextAsFile,
            settings.displaySetting.pasteLongTextThreshold,
            filesManager,
            scope,
            state,
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                scope.launch {
                                    state.addImages(
                                        filesManager.createChatFilesByContents(
                                            listOf(uri)
                                        )
                                    )
                                }
                            }
                            uri != null
                        }
                    }

                    settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                scope.launch {
                                    val document = filesManager.createChatTextFile(text)
                                    state.addFiles(listOf(document))
                                }
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }
        // V3: 修两处:
        //   1) 用 Popup 让 panel 浮在 TextField 上方, 不占布局空间 → 不再顶起 composer.
        //   2) 去掉 isFocused 条件 — 点 "/" 按钮 append 了斜杠但 TextField 没获焦, 之前 panel 不弹.
        //      只要 text 开头是 "/" 就显示 panel.
        val slashVisible = slashQuery != null
        var slashPanelMounted by remember { mutableStateOf(false) }
        var retainedSlashCommands by remember { mutableStateOf<List<SlashCommandItem>>(emptyList()) }
        var retainedHasAnySlashCommand by remember { mutableStateOf(false) }
        LaunchedEffect(slashVisible) {
            if (slashVisible) {
                slashPanelMounted = true
            } else if (slashPanelMounted) {
                delay(SLASH_COMMAND_PANEL_EXIT_MS.toLong())
                slashPanelMounted = false
            }
        }
        LaunchedEffect(slashVisible, slashCommands, allSlashCommands) {
            if (slashVisible) {
                retainedSlashCommands = slashCommands
                retainedHasAnySlashCommand = allSlashCommands.isNotEmpty()
            }
        }
        if (slashPanelMounted) {
            androidx.compose.ui.window.Popup(
                properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                popupPositionProvider = inputPanelPopupPositionProvider(),
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = slashVisible,
                    enter = androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(120)
                    ) + androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 180,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                        initialOffsetY = { it / 12 },
                    ) + androidx.compose.animation.scaleIn(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 180,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                        initialScale = 0.98f,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.85f, 1f),
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(SLASH_COMMAND_PANEL_EXIT_MS)
                    ) + androidx.compose.animation.slideOutVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = SLASH_COMMAND_PANEL_EXIT_MS,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                        targetOffsetY = { it / 16 },
                    ) + androidx.compose.animation.scaleOut(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = SLASH_COMMAND_PANEL_EXIT_MS,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                        targetScale = 0.985f,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.85f, 1f),
                    ),
                ) {
                    SlashCommandPanel(
                        commands = retainedSlashCommands,
                        hasAnyCommand = retainedHasAnySlashCommand,
                        onSelect = { command ->
                            when (val action = command.action) {
                                SlashCommandAction.ClearInput -> state.clearInput()
                                SlashCommandAction.CompactContext -> {
                                    state.clearInput()
                                    onCompactContext()
                                }
                                is SlashCommandAction.InsertText -> state.setMessageText(action.text)
                                SlashCommandAction.OpenUsage -> {
                                    state.clearInput()
                                    onUsageClick()
                                }
                            }
                        },
                        // V3: thinking footer 数据来自当前 settings, 不为空且 model 支持 reasoning 时渲染
                        thinkingFooter = onUpdateAssistant?.let { update ->
                            {
                                val currentModel = settings.getCurrentChatModel()
                                val hasReasoning = currentModel?.abilities?.contains(
                                    app.amber.ai.provider.ModelAbility.REASONING
                                ) == true
                                if (hasReasoning) {
                                    val currentLevel = assistant.reasoningLevelForModel(
                                        modelId = currentModel.id,
                                        defaultReasoningLevel = settings.defaultReasoningLevelForModel(currentModel),
                                    )
                                    SlashCommandThinkingFooter(
                                        currentLevel = currentLevel,
                                        levels = app.amber.feature.ui.components.ai.reasoningLevelsForModel(currentModel),
                                        onChange = { level ->
                                            update(assistant.withReasoningLevelForModel(currentModel.id, level))
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
        // @role mention: subagents and Model Council share the same lightweight picker.
        // Detection result is
        // memoized on (text, selection) so we don't walk the string on every recomposition.
        // Slash command takes precedence — a leading `/` shouldn't double-pop a mention panel.
        val mentionEnabled = settings.agentRuntime.subAgent.enabled || settings.agentRuntime.modelCouncil.enabled
        val mentionTextSnapshot = state.textContent.text.toString()
        val mentionSelection = state.textContent.selection
        val mentionState = remember(mentionEnabled, slashQuery, mentionTextSnapshot, mentionSelection) {
            if (!mentionEnabled || slashQuery != null) null
            else detectMentionContextFor(mentionTextSnapshot, mentionSelection.start)
        }
        val mentionVisible = isFocused && mentionState != null
        val mentionMatches = mentionState?.takeIf { mentionVisible }?.let { activeMention ->
            remember(
                settings.agentRuntime.subAgent.enabled,
                settings.agentRuntime.subAgent.mode,
                settings.agentRuntime.subAgent.customDefinitions,
                settings.agentRuntime.modelCouncil.enabled,
                activeMention.query,
            ) {
                filterMentionRoleItems(
                    items = buildMentionRoleItems(
                        subAgentEnabled = settings.agentRuntime.subAgent.enabled,
                        modelCouncilEnabled = settings.agentRuntime.modelCouncil.enabled,
                        subAgentMode = settings.agentRuntime.subAgent.mode,
                        customSubAgents = settings.agentRuntime.subAgent.customDefinitions,
                    ),
                    query = activeMention.query,
                )
            }
        } ?: emptyList()
        var mentionPanelMounted by remember { mutableStateOf(false) }
        var retainedMentionMatches by remember { mutableStateOf<List<MentionRoleItem>>(emptyList()) }
        var retainedMentionState by remember { mutableStateOf<MentionContext?>(null) }
        LaunchedEffect(mentionVisible) {
            if (mentionVisible) {
                mentionPanelMounted = true
            } else if (mentionPanelMounted) {
                delay(MENTION_PANEL_EXIT_MS.toLong())
                mentionPanelMounted = false
            }
        }
        LaunchedEffect(mentionVisible, mentionMatches, mentionState) {
            if (mentionVisible) {
                retainedMentionMatches = mentionMatches
                retainedMentionState = mentionState
            }
        }
        if (mentionPanelMounted) {
            androidx.compose.ui.window.Popup(
                properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                popupPositionProvider = inputPanelPopupPositionProvider(),
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = mentionVisible,
                    enter = androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(120)
                    ) + androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 180,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                        initialOffsetY = { it / 12 },
                    ) + androidx.compose.animation.scaleIn(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 180,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                        initialScale = 0.98f,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.85f, 1f),
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(MENTION_PANEL_EXIT_MS)
                    ) + androidx.compose.animation.slideOutVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = MENTION_PANEL_EXIT_MS,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                        targetOffsetY = { it / 16 },
                    ) + androidx.compose.animation.scaleOut(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = MENTION_PANEL_EXIT_MS,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                        targetScale = 0.985f,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.85f, 1f),
                    ),
                ) {
                    MentionPanel(
                        roles = retainedMentionMatches,
                        onSelect = { role ->
                            retainedMentionState?.let { activeMention ->
                                state.replaceMention(activeMention, role.id)
                            }
                        },
                    )
                }
            }
        }
        // In the graphite composer (minimalChrome) the field lives inside a 26dp `surface-2`
        // pill that already supplies the visual chrome + horizontal inset, so we drop M3's
        // 48dp interactive floor and trim the content padding to keep the single-line pill
        // height aligned with the flanking 46dp [+] / send circles.
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides
                if (minimalChrome) 0.dp else LocalMinimumInteractiveComponentSize.current
        ) {
        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            contentPadding = if (minimalChrome) {
                // 单行高度压到 ≤46dp，让外层 pill 的 heightIn(min=46) 钳到 46dp，与两侧圆按钮齐平
                PaddingValues(horizontal = 0.dp, vertical = 9.dp)
            } else {
                TextFieldDefaults.contentPaddingWithoutLabel()
            },
            shape = if (minimalChrome) RoundedCornerShape(0.dp) else RoundedCornerShape(8.dp),
            placeholder = {
                if (!hidePlaceholder) {
                    Text(
                        text = if (minimalChrome) "输入消息" else stringResource(R.string.chat_input_placeholder),
                        color = workspace.faint,
                    )
                }
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = if (minimalChrome) Color.Transparent else workspace.paper,
                unfocusedContainerColor = if (minimalChrome) Color.Transparent else workspace.paper,
                focusedTextColor = workspace.ink,
                unfocusedTextColor = workspace.ink,
                focusedPlaceholderColor = workspace.faint,
                unfocusedPlaceholderColor = workspace.faint,
            ),
            trailingIcon = if (isFocused && !minimalChrome) {
                {
                    IconButton(
                        onClick = {
                            isFullScreen = !isFullScreen
                        },
                    ) {
                        Icon(HugeIcons.FullScreen, null)
                    }
                }
            } else {
                null
            },
            leadingIcon = if (minimalChrome) {
                null
            } else if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else {
                {
                    SlashCommandLeadingMark()
                }
            },
        )
        }
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

private fun inputPanelPopupPositionProvider(
    gapPx: Int = 8,
): androidx.compose.ui.window.PopupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

@Composable
private fun SlashCommandPanel(
    commands: List<SlashCommandItem>,
    hasAnyCommand: Boolean,
    onSelect: (SlashCommandItem) -> Unit,
    thinkingFooter: (@Composable () -> Unit)? = null,
) {
    val workspace = workspaceColors()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // V3: panel 宽度跟 composer 一致 — screen 宽减去 24dp (composer 父级 horizontal padding 12dp 左右)
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val panelShape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier
            .width(screenWidth - 24.dp)
            .shadow(
                elevation = 8.dp,
                shape = panelShape,
                clip = false,
                ambientColor = chatTheme.composerShadow,
                spotColor = chatTheme.composerShadow,
            ),
        shape = panelShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = chatTheme.surface,
        border = BorderStroke(1.dp, chatTheme.surfaceEdge),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            when {
                !hasAnyCommand -> SlashCommandEmptyRow(
                    text = stringResource(R.string.chat_input_slash_command_empty)
                )

                commands.isEmpty() -> SlashCommandEmptyRow(
                    text = stringResource(R.string.chat_input_slash_command_no_match)
                )

                else -> {
                    val shownCommands = commands.take(MAX_SLASH_COMMANDS)
                    shownCommands.forEachIndexed { index, command ->
                        SlashCommandRow(
                            command = command,
                            onClick = { onSelect(command) },
                        )
                        if (index < shownCommands.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 58.dp),
                                color = workspace.hairline,
                            )
                        }
                    }
                }
            }
            // V3: thinking-level footer — 跟着 panel 一起出现, 仅当 model 支持 reasoning 时显示
            thinkingFooter?.invoke()
        }
    }
}

@Composable
private fun SlashCommandThinkingFooter(
    currentLevel: app.amber.ai.core.ReasoningLevel,
    levels: List<Pair<app.amber.ai.core.ReasoningLevel, String>>,
    onChange: (app.amber.ai.core.ReasoningLevel) -> Unit,
) {
    val workspace = workspaceColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        HorizontalDivider(color = workspace.hairline)
        Text(
            text = "思考等级",
            color = workspace.faint,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp),
        )
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            app.amber.feature.ui.components.ai.ThinkingLevelSegment(
                levels = levels,
                current = currentLevel,
                onChange = onChange,
            )
        }
    }
}

@Composable
private fun SlashCommandRow(
    command: SlashCommandItem,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // V3: 跟主题色 (scheme.primary/primaryContainer), 边框降到 hair 极淡, 不再粗硬蓝勾边
            val scheme = MaterialTheme.colorScheme
            Surface(
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(6.dp),
                color = if (command.accent) scheme.primaryContainer else workspace.row,
                contentColor = if (command.accent) scheme.primary else workspace.muted,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = command.marker,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "/${command.title}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = command.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SlashCommandEmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        SlashCommandLeadingMark()
        // Notion-like dropdown: each entry is a Row of [leading "/" chip + Column(title,
        // optional content preview)] instead of the old plain Column of two unspaced
        // Text lines. Hairline dividers between entries; min width bumped to 260dp so
        // the title doesn't get clipped to a noun fragment.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 260.dp, max = 360.dp)
        ) {
            quickMessages.forEachIndexed { index, quickMessage ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SlashCommandLeadingMark()
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = quickMessage.title.ifBlank {
                                    stringResource(R.string.extension_content_unnamed)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (quickMessage.content.isNotBlank()) {
                                Text(
                                    text = quickMessage.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashCommandLeadingMark() {
    val workspace = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    // V3: 跟主题色 (scheme.primaryContainer/primary), 替代硬编码 workspace.blue
    Surface(
        modifier = Modifier.size(24.dp),
        shape = RoundedCornerShape(5.dp),
        color = scheme.primaryContainer,
        contentColor = scheme.primary,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "/",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun String.slashCommandQuery(): String? {
    // V3 修: 中文 IME 默认输入全角"／" (U+FF0F), ASCII "/" 检查会漏 → user 用中文输入法
    // 直接打"/"唤不出 slash panel. 同时接受半角和全角.
    if (!startsWith("/") && !startsWith("／")) return null
    val query = drop(1)
    return query.takeIf { it.none { char -> char.isWhitespace() } }
}

private sealed interface SlashCommandAction {
    data object ClearInput : SlashCommandAction
    data object CompactContext : SlashCommandAction
    data object OpenUsage : SlashCommandAction
    data class InsertText(val text: String) : SlashCommandAction
}

private data class SlashCommandItem(
    val id: String,
    val title: String,
    val description: String,
    val action: SlashCommandAction,
    val marker: String = "/",
    val minQueryChars: Int = 0,
    val accent: Boolean = false,
)

private fun buildSlashCommandItems(
    quickMessages: List<QuickMessage>,
    enabledSkills: List<SkillMetadata>,
    subAgentEnabled: Boolean,
    subAgentMode: SubAgentMode,
): List<SlashCommandItem> = buildList {
    add(
        SlashCommandItem(
            id = "core.clear",
            title = "clear",
            description = "清空当前输入框",
            action = SlashCommandAction.ClearInput,
        )
    )
    add(
        SlashCommandItem(
            id = "core.compact",
            title = "compact",
            description = "立即压缩当前对话上下文",
            action = SlashCommandAction.CompactContext,
        )
    )
    add(
        SlashCommandItem(
            id = "core.deepread",
            title = "deepread",
            description = "全屏打开深度阅读面板",
            action = SlashCommandAction.InsertText("/deepread "),
            accent = true,
        )
    )
    if (subAgentEnabled) {
        add(
            SlashCommandItem(
                id = "core.subagent",
                title = "subagent",
                description = "引导 Agent 按任务需要灵活使用 SubAgent",
                action = SlashCommandAction.InsertText(
                    if (subAgentMode == SubAgentMode.SMART_DYNAMIC) {
                        "请根据这个任务的复杂度，判断是否需要用 custom_subagent 临时创建 SubAgent，不要使用 subagent_id；" +
                            "名称可省略由应用分配英文名；纯创作或无需外部读取时设 tool_profile=none，" +
                            "需要资料时再选择只读 profile；写清楚 prompt、边界和输出格式，等待返回后再综合结论："
                    } else {
                        "请根据这个任务的复杂度，主动拆分并灵活调用合适的 subagent 并行处理；" +
                            "等待它们返回后，再综合成一个可执行的结论："
                    }
                ),
            )
        )
    }
    add(
        SlashCommandItem(
            id = "core.usage",
            title = "usage",
            description = "查看 5h / weekly / cache 用量",
            action = SlashCommandAction.OpenUsage,
            minQueryChars = 1,
            accent = true,
        )
    )
    quickMessages.forEach { quickMessage ->
        val title = quickMessage.slashTitle("quick")
        add(
            SlashCommandItem(
                id = "quick.${quickMessage.id}",
                title = title,
                description = quickMessage.content,
                action = SlashCommandAction.InsertText(quickMessage.content),
                marker = "Q",
                minQueryChars = DYNAMIC_SLASH_COMMAND_MIN_QUERY_CHARS,
            )
        )
    }
    enabledSkills.forEach { skill ->
        add(
            SlashCommandItem(
                id = "skill.${skill.name}",
                title = skill.name,
                description = skill.description.ifBlank { "调用这个 Skill 处理当前任务" },
                action = SlashCommandAction.InsertText(skill.toSlashCommandPrompt()),
                marker = "S",
                minQueryChars = DYNAMIC_SLASH_COMMAND_MIN_QUERY_CHARS,
            )
        )
    }
}

private fun filterSlashCommandItems(
    items: List<SlashCommandItem>,
    query: String,
): List<SlashCommandItem> {
    val normalized = query.trim()
    return items.filter { command ->
        if (normalized.length < command.minQueryChars) {
            false
        } else if (normalized.isBlank()) {
            true
        } else {
            command.title.startsWith(normalized, ignoreCase = true) ||
                command.title.contains(normalized, ignoreCase = true) ||
                command.description.contains(normalized, ignoreCase = true)
        }
    }
}

private fun SkillMetadata.toSlashCommandPrompt(): String =
    "请先调用 use_skill(\"${name.escapeForPromptString()}\")，然后按这个 skill 的说明继续完成："

private fun String.escapeForPromptString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

/** Position of an active `@xxx` mention context: the `@` index and the partial query after it. */
private data class MentionContext(val atIndex: Int, val query: String)

/**
 * Pure variant: detect whether [cursor] is inside an `@xxx` token in [text]. Walks backwards
 * until it hits an `@` (preceded by start-of-text or whitespace, to avoid emails/handles inside
 * other text) or any whitespace (= not in mention context).
 */
private fun detectMentionContextFor(text: String, cursor: Int): MentionContext? {
    val safeCursor = cursor.coerceIn(0, text.length)
    if (safeCursor == 0) return null
    var i = safeCursor - 1
    while (i >= 0) {
        val ch = text[i]
        if (ch == '@') {
            if (i == 0 || text[i - 1].isWhitespace()) {
                return MentionContext(atIndex = i, query = text.substring(i + 1, safeCursor))
            }
            return null
        }
        if (ch.isWhitespace()) return null
        i--
    }
    return null
}

/** Replace `@<query>` (under the current cursor) with `@<roleId> ` and place the cursor after it. */
private fun ChatInputState.replaceMention(context: MentionContext, roleId: String) {
    textContent.edit {
        val replaceEnd = context.atIndex + 1 + context.query.length
        replace(context.atIndex, replaceEnd, "@$roleId ")
    }
}

@Composable
private fun MentionPanel(
    roles: List<MentionRoleItem>,
    onSelect: (MentionRoleItem) -> Unit,
) {
    val workspace = workspaceColors()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val panelShape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier
            .width(screenWidth - 24.dp)
            .shadow(
                elevation = 8.dp,
                shape = panelShape,
                clip = false,
                ambientColor = chatTheme.composerShadow,
                spotColor = chatTheme.composerShadow,
            ),
        shape = panelShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = chatTheme.surface,
        border = BorderStroke(1.dp, chatTheme.surfaceEdge),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            if (roles.isEmpty()) {
                SlashCommandEmptyRow(text = stringResource(R.string.chat_input_mention_no_match))
            } else {
                val shown = roles.take(MAX_MENTIONS)
                shown.forEachIndexed { index, role ->
                    MentionRow(role = role, onClick = { onSelect(role) })
                    if (index < shown.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 58.dp),
                            color = workspace.hairline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MentionRow(
    role: MentionRoleItem,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(6.dp),
                color = workspace.row,
                contentColor = workspace.muted,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "@", style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "@${role.id}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = role.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun QuickMessage.slashTitle(fallback: String): String =
    title.ifBlank { content.lineSequence().firstOrNull().orEmpty() }
        .trim()
        .replace(Regex("\\s+"), "-")
        .take(MAX_SLASH_COMMAND_TITLE_CHARS)
        .ifBlank { fallback }
