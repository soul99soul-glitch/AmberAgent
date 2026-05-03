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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
                TextButton(onClick = { memoryDialogState.confirm() }) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryDialogState.dismiss() }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
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
                TextButton(onClick = { memoryInfoDialog = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
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
                TextButton(
                    onClick = {
                        onSave(draft)
                        showEditor = false
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
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
