package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.PulseDialogButton
import me.rerere.rikkahub.ui.components.ui.PulseDialogVariant
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAgentMemoryPage() {
    val vm = koinViewModel<SettingAgentMemoryVM>()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val shortTermMemories by vm.shortTermMemories.collectAsStateWithLifecycle()
    val longTermMemories by vm.longTermMemories.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val memoryDialogState = useEditState<AssistantMemory> { memory ->
        if (memory.id == 0) {
            vm.addMemory(memory)
        } else {
            vm.updateMemory(memory)
        }
    }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var memoryInfoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = { memoryDialogState.dismiss() },
            title = { Text(stringResource(R.string.setting_agent_memory_edit_title)) },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = { update(memory.copy(content = it)) },
                    label = { Text(stringResource(R.string.setting_agent_memory_content_label)) },
                    minLines = 2,
                    maxLines = 8,
                )
            },
            confirmButton = {
                PulseDialogButton(
                    onClick = { memoryDialogState.confirm() },
                    text = stringResource(R.string.assistant_page_save),
                    variant = PulseDialogVariant.Primary,
                )
            },
            dismissButton = {
                PulseDialogButton(
                    onClick = { memoryDialogState.dismiss() },
                    text = stringResource(R.string.assistant_page_cancel),
                    variant = PulseDialogVariant.Ghost,
                )
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_agent_memory_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Ink-grounded stat hero. Three KPI tiles share the dark
            // canvas with chartreuse content — high-contrast, glanceable
            // memory inventory at the top of the page. CORE = persistent
            // user-curated memories; SHORT-TERM = working memory entries;
            // LONG-TERM = consolidated condensed memories.
            MemoryStatHero(
                core = memories.size,
                shortTerm = shortTermMemories.size,
                longTerm = longTermMemories.size,
            )

            AgentSoulCard(
                value = settings.agentRuntime.agentSoulMarkdown,
                onSave = { value ->
                    vm.updateAgentRuntime { it.copy(agentSoulMarkdown = value) }
                },
            )

            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_agent_memory_core_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_memory_core_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.agentRuntime.enableCoreMemory,
                            onCheckedChange = { enabled ->
                                vm.updateAgentRuntime { it.copy(enableCoreMemory = enabled) }
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_agent_memory_short_term_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_memory_short_term_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.agentRuntime.enableShortTermMemory,
                            onCheckedChange = { enabled ->
                                vm.updateAgentRuntime { it.copy(enableShortTermMemory = enabled) }
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_agent_memory_long_term_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_memory_long_term_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.agentRuntime.enableLongTermMemory,
                            onCheckedChange = { enabled ->
                                vm.updateAgentRuntime { it.copy(enableLongTermMemory = enabled) }
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_agent_memory_recent_chats_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_memory_recent_chats_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.agentRuntime.enableRecentChatsReference,
                            onCheckedChange = { enabled ->
                                vm.updateAgentRuntime { it.copy(enableRecentChatsReference = enabled) }
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_agent_memory_time_reminder_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_memory_time_reminder_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.agentRuntime.enableTimeReminder,
                            onCheckedChange = { enabled ->
                                vm.updateAgentRuntime { it.copy(enableTimeReminder = enabled) }
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_compaction_title)) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.setting_agent_memory_context_compaction_desc))
                            Text(
                                text = stringResource(R.string.setting_agent_memory_context_compaction_defaults),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.agentRuntime.contextCompaction.enabled,
                            onCheckedChange = { enabled ->
                                vm.updateAgentRuntime {
                                    it.copy(
                                        contextCompaction = it.contextCompaction.copy(enabled = enabled)
                                    )
                                }
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_compaction_notify_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_memory_context_compaction_notify_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.agentRuntime.contextCompaction.notifyOnly,
                            onCheckedChange = { enabled ->
                                vm.updateAgentRuntime {
                                    it.copy(
                                        contextCompaction = it.contextCompaction.copy(notifyOnly = enabled)
                                    )
                                }
                            },
                        )
                    },
                )
            }

            MemoryRecordsSection(
                title = stringResource(R.string.setting_agent_memory_records_title),
                emptyText = stringResource(R.string.setting_agent_memory_empty),
                memories = memories,
                onAddMemory = { memoryDialogState.open(AssistantMemory(0, "")) },
                onEditMemory = { memoryDialogState.open(it) },
                onDeleteMemory = { pendingDeleteMemory = it },
            )

            MemoryRecordsSection(
                title = stringResource(R.string.setting_agent_memory_short_records_title),
                emptyText = stringResource(R.string.setting_agent_memory_short_empty),
                memories = shortTermMemories,
                infoTitle = stringResource(R.string.setting_agent_memory_short_info_title),
                infoText = stringResource(R.string.setting_agent_memory_short_info_body),
                onInfoClick = { title, text -> memoryInfoDialog = title to text },
                onAddMemory = null,
                onEditMemory = { memoryDialogState.open(it) },
                onDeleteMemory = { pendingDeleteMemory = it },
            )

            MemoryRecordsSection(
                title = stringResource(R.string.setting_agent_memory_long_records_title),
                emptyText = stringResource(R.string.setting_agent_memory_long_empty),
                memories = longTermMemories,
                infoTitle = stringResource(R.string.setting_agent_memory_long_info_title),
                infoText = stringResource(R.string.setting_agent_memory_long_info_body),
                onInfoClick = { title, text -> memoryInfoDialog = title to text },
                onAddMemory = null,
                onEditMemory = { memoryDialogState.open(it) },
                onDeleteMemory = { pendingDeleteMemory = it },
            )
        }
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(vm::deleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        destructive = true,
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )

    memoryInfoDialog?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { memoryInfoDialog = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                PulseDialogButton(
                    onClick = { memoryInfoDialog = null },
                    text = stringResource(R.string.confirm),
                    variant = PulseDialogVariant.Primary,
                )
            },
        )
    }
}

/**
 * Three-tile ink-grounded stat hero for the Memory page. Each tile is
 * the same dark surface but the value digit gets a different accent —
 * chartreuse for the count that's most actively used (Core), orange
 * for the working set (Short-term), cream for the consolidated archive
 * (Long-term). Bold black 30sp digits on ink for "fitness-tech KPI"
 * read at a glance.
 */
@Composable
private fun MemoryStatHero(
    core: Int,
    shortTerm: Int,
    longTerm: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoryStatSlot(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.setting_agent_memory_stat_core),
                value = core.toString().padStart(2, '0'),
                valueColor = MaterialTheme.colorScheme.primary,
            )
            MemoryStatSlot(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.setting_agent_memory_stat_short),
                value = shortTerm.toString().padStart(2, '0'),
                valueColor = MaterialTheme.colorScheme.secondary,
            )
            MemoryStatSlot(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.setting_agent_memory_stat_long),
                value = longTerm.toString().padStart(2, '0'),
                valueColor = MaterialTheme.colorScheme.onTertiary,
            )
        }
    }
}

@Composable
private fun MemoryStatSlot(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.em,
            ),
            color = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            color = valueColor,
        )
    }
}

@Composable
private fun AgentSoulCard(
    value: String,
    onSave: (String) -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }
    val previewText = if (value.isBlank()) {
        stringResource(R.string.setting_agent_memory_soul_empty_preview)
    } else {
        value
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                draft = value
                showEditor = true
            },
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_agent_memory_soul_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = stringResource(R.string.setting_agent_memory_soul_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = previewText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(14.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (value.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = stringResource(R.string.setting_agent_memory_soul_edit_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(stringResource(R.string.setting_agent_memory_soul_edit_title)) },
            text = {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp),
                    minLines = 8,
                    maxLines = 18,
                    label = { Text(stringResource(R.string.setting_agent_memory_soul_label)) },
                )
            },
            confirmButton = {
                PulseDialogButton(
                    onClick = {
                        onSave(draft)
                        showEditor = false
                    },
                    text = stringResource(R.string.assistant_page_save),
                    variant = PulseDialogVariant.Primary,
                )
            },
            dismissButton = {
                PulseDialogButton(
                    onClick = { showEditor = false },
                    text = stringResource(R.string.assistant_page_cancel),
                    variant = PulseDialogVariant.Ghost,
                )
            },
        )
    }
}

@Composable
private fun MemoryRecordsSection(
    title: String,
    emptyText: String,
    memories: List<AssistantMemory>,
    infoTitle: String? = null,
    infoText: String? = null,
    onInfoClick: ((String, String) -> Unit)? = null,
    onAddMemory: (() -> Unit)?,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .align(Alignment.CenterStart),
        )
        if (onInfoClick != null && infoTitle != null && infoText != null) {
            IconButton(
                onClick = { onInfoClick(infoTitle, infoText) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(40.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "?",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (onAddMemory != null) {
            IconButton(
                onClick = onAddMemory,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        }
    }

    if (memories.isEmpty()) {
        Text(
            text = emptyText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }

    memories.fastForEach { memory ->
        key(memory.id) {
            MemoryItem(
                memory = memory,
                onEditMemory = onEditMemory,
                onDeleteMemory = onDeleteMemory,
            )
        }
    }
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "#${memory.id}",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = memory.content,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = { onEditMemory(memory) }) {
                Icon(HugeIcons.PencilEdit01, contentDescription = null)
            }
            IconButton(onClick = { onDeleteMemory(memory) }) {
                Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.assistant_page_delete))
            }
        }
    }
}
