package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.subagent.SubAgentMode
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
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

@Composable
internal fun TextInputRow(
    state: ChatInputState,
    onSendMessage: () -> Unit,
    onUsageClick: () -> Unit,
    onCompactContext: () -> Unit,
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
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = workspace.blueContainer,
                contentColor = workspace.blue,
                border = BorderStroke(1.dp, workspace.blue.copy(alpha = 0.16f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
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
            settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                state.addImages(
                                    filesManager.createChatFilesByContents(
                                        listOf(uri)
                                    )
                                )
                            }
                            uri != null
                        }
                    }

                    settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                val document = filesManager.createChatTextFile(text)
                                state.addFiles(listOf(document))
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
        val slashVisible = isFocused && slashQuery != null
        androidx.compose.animation.AnimatedVisibility(
            visible = slashVisible,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(150)
            ) + androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.tween(150),
                initialOffsetY = { it / 4 }
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(100)
            ) + androidx.compose.animation.slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(100),
                targetOffsetY = { it / 4 }
            ),
        ) {
            SlashCommandPanel(
                commands = slashCommands,
                hasAnyCommand = allSlashCommands.isNotEmpty(),
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
            )
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
        androidx.compose.animation.AnimatedVisibility(
            visible = mentionVisible,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(150)
            ) + androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.tween(150),
                initialOffsetY = { it / 4 }
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(100)
            ) + androidx.compose.animation.slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(100),
                targetOffsetY = { it / 4 }
            ),
        ) {
            MentionPanel(
                roles = mentionMatches,
                onSelect = { role ->
                    if (mentionState != null) {
                        state.replaceMention(mentionState, role.id)
                    }
                },
            )
        }
        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            shape = RoundedCornerShape(8.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.chat_input_placeholder),
                    color = workspace.faint,
                )
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
                focusedContainerColor = workspace.paper,
                unfocusedContainerColor = workspace.paper,
                focusedTextColor = workspace.ink,
                unfocusedTextColor = workspace.ink,
                focusedPlaceholderColor = workspace.faint,
                unfocusedPlaceholderColor = workspace.faint,
            ),
            trailingIcon = if (isFocused) {
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
            leadingIcon = if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else {
                {
                    SlashCommandLeadingMark()
                }
            },
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

@Composable
private fun SlashCommandPanel(
    commands: List<SlashCommandItem>,
    hasAnyCommand: Boolean,
    onSelect: (SlashCommandItem) -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
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
            Surface(
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(6.dp),
                color = if (command.accent) workspace.blueContainer else workspace.row,
                contentColor = if (command.accent) workspace.blue else workspace.muted,
                border = BorderStroke(
                    1.dp,
                    if (command.accent) workspace.blue.copy(alpha = 0.14f) else workspace.hairline
                ),
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
    Surface(
        modifier = Modifier.size(24.dp),
        shape = RoundedCornerShape(5.dp),
        color = workspace.blueContainer,
        contentColor = workspace.blue,
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
    if (!startsWith("/")) return null
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
    if (subAgentEnabled) {
        add(
            SlashCommandItem(
                id = "core.subagent",
                title = "subagent",
                description = "引导 Agent 按任务需要灵活使用 SubAgent",
                action = SlashCommandAction.InsertText(
                    if (subAgentMode == SubAgentMode.SMART_DYNAMIC) {
                        "请根据这个任务的复杂度，判断是否需要临时创建合适的英文名 SubAgent；" +
                            "为它设计清晰 prompt、边界和只读工具范围，等待返回后再综合结论："
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
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
