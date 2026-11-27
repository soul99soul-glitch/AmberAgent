package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.subagent.SubAgentMode
import app.amber.core.files.SkillMetadata
import app.amber.core.model.QuickMessage
import app.amber.feature.ui.components.ui.workspaceColors

private const val MAX_SLASH_COMMANDS = 9
private const val MAX_SLASH_COMMAND_TITLE_CHARS = 32
private const val DYNAMIC_SLASH_COMMAND_MIN_QUERY_CHARS = 2

@Composable
internal fun SlashCommandPanel(
    commands: List<SlashCommandItem>,
    hasAnyCommand: Boolean,
    onSelect: (SlashCommandItem) -> Unit,
) {
    val workspace = workspaceColors()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // V3: panel 宽度跟 composer 一致 — screen 宽减去 24dp (composer 父级 horizontal padding 12dp 左右)
    val panelWidth = inputPanelWidth()
    val panelShape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier
            .width(panelWidth)
            .shadow(
                elevation = 8.dp,
                shape = panelShape,
                clip = false,
                ambientColor = Color.Transparent,
                spotColor = Color.Transparent,
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
internal fun SlashCommandEmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
internal fun SlashCommandLeadingMark() {
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

internal fun String.slashCommandQuery(): String? {
    // V3 修: 中文 IME 默认输入全角"／" (U+FF0F), ASCII "/" 检查会漏 → user 用中文输入法
    // 直接打"/"唤不出 slash panel. 同时接受半角和全角.
    if (!startsWith("/") && !startsWith("／")) return null
    val query = drop(1)
    return query.takeIf { it.none { char -> char.isWhitespace() } }
}

internal sealed interface SlashCommandAction {
    data object ClearInput : SlashCommandAction
    data object CompactContext : SlashCommandAction
    data object OpenUsage : SlashCommandAction
    data class InsertText(val text: String) : SlashCommandAction
}

internal data class SlashCommandItem(
    val id: String,
    val title: String,
    val description: String,
    val action: SlashCommandAction,
    val marker: String = "/",
    val minQueryChars: Int = 0,
    val accent: Boolean = false,
)

internal data class SlashCommandLabels(
    val clearDescription: String,
    val compactDescription: String,
    val subAgentDescription: String,
    val usageDescription: String,
    val skillFallbackDescription: String,
)

internal fun buildSlashCommandItems(
    quickMessages: List<QuickMessage>,
    enabledSkills: List<SkillMetadata>,
    subAgentEnabled: Boolean,
    subAgentMode: SubAgentMode,
    labels: SlashCommandLabels,
): List<SlashCommandItem> = buildList {
    add(
        SlashCommandItem(
            id = "core.clear",
            title = "clear",
            description = labels.clearDescription,
            action = SlashCommandAction.ClearInput,
        )
    )
    add(
        SlashCommandItem(
            id = "core.compact",
            title = "compact",
            description = labels.compactDescription,
            action = SlashCommandAction.CompactContext,
        )
    )
    if (subAgentEnabled) {
        add(
            SlashCommandItem(
                id = "core.subagent",
                title = "subagent",
                description = labels.subAgentDescription,
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
            description = labels.usageDescription,
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
                description = skill.description.ifBlank { labels.skillFallbackDescription },
                action = SlashCommandAction.InsertText(skill.toSlashCommandPrompt()),
                marker = "S",
                minQueryChars = DYNAMIC_SLASH_COMMAND_MIN_QUERY_CHARS,
            )
        )
    }
}

internal fun filterSlashCommandItems(
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

private fun QuickMessage.slashTitle(fallback: String): String =
    title.ifBlank { content.lineSequence().firstOrNull().orEmpty() }
        .trim()
        .replace(Regex("\\s+"), "-")
        .take(MAX_SLASH_COMMAND_TITLE_CHARS)
        .ifBlank { fallback }
