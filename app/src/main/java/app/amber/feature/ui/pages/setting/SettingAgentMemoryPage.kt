package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.SwitchSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import androidx.activity.compose.BackHandler
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import app.amber.core.memory.dream.PersistedMemoryDreamPlan
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryEvent
import app.amber.core.memory.model.MemoryWorkerDreamGate
import app.amber.core.memory.safety.isSensitiveMemoryContent
import app.amber.core.model.AssistantMemory
import app.amber.core.model.MemoryKind
import app.amber.core.model.MemoryScope
import app.amber.core.repository.MemoryRepository
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.androidx.compose.koinViewModel
import java.text.DateFormat
import java.util.Date
import java.io.File
import java.util.Locale

@Composable
fun SettingAgentMemoryPage(
    subpage: MemorySettingsSubpage = MemorySettingsSubpage.Overview,
) {
    val vm = koinViewModel<SettingAgentMemoryVM>()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val memoryCounts by if (subpage == MemorySettingsSubpage.Overview) {
        vm.memoryCounts.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    }
    val memories by if (
        subpage == MemorySettingsSubpage.Library ||
        subpage == MemorySettingsSubpage.Overview
    ) {
        vm.memories.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList<AssistantMemory>()) }
    }
    val shortTermMemories by if (
        subpage == MemorySettingsSubpage.Library ||
        subpage == MemorySettingsSubpage.Overview
    ) {
        vm.shortTermMemories.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList<AssistantMemory>()) }
    }
    val longTermMemories by if (
        subpage == MemorySettingsSubpage.Library ||
        subpage == MemorySettingsSubpage.Overview
    ) {
        vm.longTermMemories.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList<AssistantMemory>()) }
    }
    val pendingCandidateCount by if (
        subpage == MemorySettingsSubpage.Overview ||
        subpage == MemorySettingsSubpage.Worker
    ) {
        vm.pendingCandidateCount.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(0) }
    }
    val pendingCandidates by if (subpage == MemorySettingsSubpage.Library) {
        vm.pendingCandidates.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<List<MemoryCandidate>>(emptyList()) }
    }
    val recentMemoryEvents by if (
        subpage == MemorySettingsSubpage.Worker ||
        subpage == MemorySettingsSubpage.Library
    ) {
        vm.recentMemoryEvents.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<List<MemoryEvent>>(emptyList()) }
    }
    val dreamPlan by if (
        subpage == MemorySettingsSubpage.Overview ||
        subpage == MemorySettingsSubpage.Worker
    ) {
        vm.dreamPlan.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<PersistedMemoryDreamPlan?>(null) }
    }
    val memoryTaskRunning by vm.memoryTaskRunning.collectAsStateWithLifecycle()
    val operationMessage by vm.operationMessage.collectAsStateWithLifecycle()
    val memoryMutation by vm.memoryMutation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var editingMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var memoryInfoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    val pageTitle = when (subpage) {
        MemorySettingsSubpage.Overview -> stringResource(R.string.setting_agent_memory_title)
        MemorySettingsSubpage.Recall -> stringResource(R.string.memory_recall_title)
        MemorySettingsSubpage.Worker -> stringResource(R.string.memory_worker_title)
        MemorySettingsSubpage.Compaction -> stringResource(R.string.memory_compaction_title)
        MemorySettingsSubpage.Library -> stringResource(R.string.memory_library_title)
    }

    LaunchedEffect(operationMessage) {
        operationMessage?.let { message ->
            toaster.show(message, type = ToastType.Info)
            vm.consumeOperationMessage()
        }
    }

    LaunchedEffect(memoryMutation) {
        when (val mutation = memoryMutation) {
            is MemoryMutationState.Saved -> {
                if (editingMemory?.id == mutation.memoryId ||
                    mutation.operation == MemoryMutationOperation.CREATE
                ) {
                    editingMemory = null
                }
                vm.consumeMemoryMutation()
            }

            is MemoryMutationState.Deleted -> {
                if (pendingDeleteMemory?.id == mutation.memoryId) {
                    pendingDeleteMemory = null
                }
                vm.consumeMemoryMutation()
            }

            else -> Unit
        }
    }

    editingMemory?.let { memory ->
        val isSaving = memoryMutation is MemoryMutationState.Saving
        val saveError = (memoryMutation as? MemoryMutationState.Failed)
            ?.takeIf {
                it.operation != MemoryMutationOperation.DELETE &&
                    it.draft.id == memory.id
            }
        AlertDialog(
            modifier = Modifier.imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            onDismissRequest = {
                if (!isSaving) {
                    editingMemory = null
                    vm.consumeMemoryMutation()
                }
            },
            title = {
                Text(
                    if (memory.id == 0) {
                        stringResource(R.string.setting_agent_memory_add_title)
                    } else {
                        stringResource(R.string.setting_agent_memory_edit_title)
                    },
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (memory.id != 0) {
                        MemoryEditMetadata(memory)
                    }
                    TextField(
                        value = memory.content,
                        onValueChange = { editingMemory = memory.copy(content = it) },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 360.dp),
                        label = { Text(stringResource(R.string.setting_agent_memory_content_label)) },
                        minLines = 5,
                        maxLines = 12,
                    )
                    saveError?.let { error ->
                        Text(
                            text = stringResource(
                                R.string.setting_agent_memory_write_error,
                                error.message,
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = LocalAmberType.current.secondary,
                        )
                    }
                    if (isSaving) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(R.string.setting_agent_memory_saving),
                                style = LocalAmberType.current.secondary,
                                color = workspaceColors().muted,
                            )
                        }
                    }
                    MemoryClassificationEditor(
                        memory = memory,
                        enabled = !isSaving,
                        onChange = { editingMemory = it },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isSaving,
                    onClick = {
                        if (memory.id == 0) {
                            vm.addMemory(memory)
                        } else {
                            vm.updateMemory(memory)
                        }
                    },
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSaving,
                    onClick = {
                        editingMemory = null
                        vm.consumeMemoryMutation()
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = pageTitle,
                navigationIcon = { BackButton() },
                actions = {
                    if (subpage == MemorySettingsSubpage.Worker) {
                        val worker = settings.agentRuntime.memoryWorker
                        val canRunDream = worker.enabled && MemoryWorkerDreamGate.isAnyDreamEnabled(worker)
                        IconButton(
                            enabled = canRunDream,
                            onClick = { vm.triggerDreamNow() },
                        ) {
                            Icon(
                                imageVector = Lucide.Play,
                                contentDescription = stringResource(R.string.memory_run_daydream_now),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        if (subpage == MemorySettingsSubpage.Library) {
            MemoryLibrarySubpage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = SettingPageHorizontalInset, vertical = 8.dp)
                    .imePadding(),
                memories = memories,
                shortTermMemories = shortTermMemories,
                longTermMemories = longTermMemories,
                pendingCandidates = pendingCandidates,
                recentMemoryEvents = recentMemoryEvents,
                running = memoryTaskRunning,
                onAcceptCandidate = vm::acceptCandidate,
                onIgnoreCandidate = vm::ignoreCandidate,
                onIgnoreLowConfidenceCandidates = vm::ignoreLowConfidenceCandidates,
                onExport = {
                    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                    vm.exportMemories(baseDir)
                },
                onImport = {
                    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                    vm.importMemories(File(baseDir, "AmberAgentMemory"))
                },
                onAddMemory = { editingMemory = AssistantMemory(0, "") },
                onEditMemory = { editingMemory = it },
                onDeleteMemory = { pendingDeleteMemory = it },
                onInfoClick = { title, text -> memoryInfoDialog = title to text },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = SettingPageHorizontalInset, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (subpage) {
                MemorySettingsSubpage.Overview -> {
                    AgentSoulCard(
                        value = settings.agentRuntime.agentSoulMarkdown,
                        onSave = { value ->
                            vm.updateAgentRuntime { it.copy(agentSoulMarkdown = value) }
                        },
                    )
                    MemoryOverviewEntries(
                        pendingCandidateCount = pendingCandidateCount,
                        coreCount = memoryCounts[MemoryRepository.GLOBAL_MEMORY_ID] ?: 0,
                        shortCount = memoryCounts[MemoryRepository.SHORT_TERM_MEMORY_ID] ?: 0,
                        longCount = memoryCounts[MemoryRepository.LONG_TERM_MEMORY_ID] ?: 0,
                        hasPendingDreamPlan = dreamPlan != null,
                        coreMemories = memories,
                        shortTermMemories = shortTermMemories,
                        longTermMemories = longTermMemories,
                        onOpen = { target -> navController.navigate(target.toScreen()) },
                    )
                }

                MemorySettingsSubpage.Recall -> MemoryRecallSubpage(
                    settings = settings,
                    onUpdate = vm::updateAgentRuntime,
                )

                MemorySettingsSubpage.Worker -> MemoryWorkerSubpage(
                    settings = settings,
                    pendingCandidateCount = pendingCandidateCount,
                    eventCount = recentMemoryEvents.size,
                    dreamPlan = dreamPlan,
                    running = memoryTaskRunning,
                    onUpdate = vm::updateAgentRuntime,
                    onPlan = vm::planDream,
                    onApply = vm::applyDreamPlan,
                    onDismiss = vm::dismissDreamPlan,
                )

                MemorySettingsSubpage.Compaction -> MemoryCompactionSubpage(
                    settings = settings,
                    onUpdate = vm::updateAgentRuntime,
                )

                MemorySettingsSubpage.Library -> Unit
                }
            }
        }
    }

    pendingDeleteMemory?.let { memory ->
        val isDeleting = memoryMutation is MemoryMutationState.Deleting
        val deleteError = (memoryMutation as? MemoryMutationState.Failed)
            ?.takeIf {
                it.operation == MemoryMutationOperation.DELETE && it.draft.id == memory.id
            }
        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) {
                    pendingDeleteMemory = null
                    vm.consumeMemoryMutation()
                }
            },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = memory.content)
                    deleteError?.let { error ->
                        Text(
                            text = stringResource(
                                R.string.setting_agent_memory_delete_error,
                                error.message,
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = LocalAmberType.current.secondary,
                        )
                    }
                    if (isDeleting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(R.string.setting_agent_memory_deleting),
                                style = LocalAmberType.current.secondary,
                                color = workspaceColors().muted,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = { vm.deleteMemory(memory) },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        pendingDeleteMemory = null
                        vm.consumeMemoryMutation()
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    (memoryMutation as? MemoryMutationState.Conflict)?.let { conflict ->
        MemoryConflictDialog(
            conflict = conflict,
            onDismiss = {
                if (conflict.operation == MemoryMutationOperation.DELETE) {
                    pendingDeleteMemory = null
                }
                vm.consumeMemoryMutation()
            },
            onUseLatest = { latest ->
                if (conflict.operation == MemoryMutationOperation.UPDATE) {
                    editingMemory = latest
                } else {
                    pendingDeleteMemory = latest
                }
                vm.consumeMemoryMutation()
            },
            onKeepDraft = { latest ->
                editingMemory = conflict.draft.copy(revision = latest.revision)
                vm.consumeMemoryMutation()
            },
        )
    }

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
private fun MemoryConflictDialog(
    conflict: MemoryMutationState.Conflict,
    onDismiss: () -> Unit,
    onUseLatest: (AssistantMemory) -> Unit,
    onKeepDraft: (AssistantMemory) -> Unit,
) {
    val latest = conflict.latest
    val isUpdate = conflict.operation == MemoryMutationOperation.UPDATE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isUpdate) {
                    stringResource(R.string.setting_agent_memory_edit_conflict_title)
                } else {
                    stringResource(R.string.setting_agent_memory_delete_conflict_title)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (isUpdate) {
                        stringResource(R.string.setting_agent_memory_edit_conflict_message)
                    } else {
                        stringResource(R.string.setting_agent_memory_delete_conflict_message)
                    },
                )
                if (latest == null) {
                    Text(
                        text = stringResource(R.string.setting_agent_memory_conflict_deleted),
                        color = MaterialTheme.colorScheme.error,
                        style = LocalAmberType.current.secondary,
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.setting_agent_memory_conflict_latest_label,
                            latest.revision,
                        ),
                        style = LocalAmberType.current.secondary.copy(fontWeight = FontWeight.SemiBold),
                    )
                    SelectionContainer {
                        Text(
                            text = latest.content,
                            modifier = Modifier.fillMaxWidth(),
                            style = LocalAmberType.current.body,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (latest != null) {
                    TextButton(onClick = { onUseLatest(latest) }) {
                        Text(
                            if (isUpdate) {
                                stringResource(R.string.setting_agent_memory_use_latest)
                            } else {
                                stringResource(R.string.setting_agent_memory_retry_delete)
                            },
                        )
                    }
                    if (isUpdate) {
                        TextButton(onClick = { onKeepDraft(latest) }) {
                            Text(stringResource(R.string.setting_agent_memory_keep_draft))
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun MemoryEditMetadata(memory: AssistantMemory) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MemoryMetadataLine(
            label = stringResource(R.string.setting_agent_memory_metadata_source),
            value = memorySourceLabel(memory),
        )
        MemoryMetadataLine(
            label = stringResource(R.string.setting_agent_memory_metadata_kind),
            value = memoryKindLabel(memory.kind),
        )
        MemoryMetadataLine(
            label = stringResource(R.string.setting_agent_memory_metadata_confidence),
            value = String.format(Locale.getDefault(), "%.2f", memory.confidence),
        )
        if (memory.updatedAt > 0L) {
            MemoryMetadataLine(
                label = stringResource(R.string.setting_agent_memory_metadata_updated),
                value = formatMemoryDate(memory.updatedAt),
            )
        }
        if (memory.kind == MemoryKind.TOPIC && memory.memberIds.isNotEmpty()) {
            MemoryMetadataLine(
                label = stringResource(R.string.setting_agent_memory_metadata_members),
                value = memory.memberIds.joinToString(", ") { "#$it" },
            )
        }
        memory.lastUsedAt?.takeIf { it > 0L }?.let { lastUsedAt ->
            MemoryMetadataLine(
                label = stringResource(R.string.setting_agent_memory_metadata_last_used),
                value = formatMemoryDate(lastUsedAt),
            )
        }
    }
}

@Composable
private fun MemoryMetadataLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.8f),
            style = LocalAmberType.current.secondary,
            color = workspaceColors().muted,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.2f),
            style = LocalAmberType.current.secondary,
            color = workspaceColors().ink,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MemoryClassificationEditor(
    memory: AssistantMemory,
    enabled: Boolean,
    onChange: (AssistantMemory) -> Unit,
) {
    // Topics stay in their dream-managed classification: pinning or re-scoping
    // one would defeat the recall rule that keeps topics out of always-eligible.
    val classificationEditable = enabled && memory.kind != MemoryKind.TOPIC
    var scopeMenuExpanded by remember(memory.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_agent_memory_classification),
            style = LocalAmberType.current.secondary.copy(fontWeight = FontWeight.SemiBold),
            color = LocalAmberTokens.current.accent,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                enabled = classificationEditable,
                onClick = { scopeMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_agent_memory_scope),
                            style = LocalAmberType.current.body,
                            color = workspaceColors().ink,
                        )
                        Text(
                            text = stringResource(R.string.setting_agent_memory_scope_desc),
                            style = LocalAmberType.current.secondary,
                            color = workspaceColors().muted,
                        )
                    }
                    Text(
                        text = memoryScopeLabel(memory.scope),
                        style = LocalAmberType.current.secondary,
                        color = workspaceColors().muted,
                        textAlign = TextAlign.End,
                    )
                }
            }
            DropdownMenu(
                expanded = scopeMenuExpanded,
                onDismissRequest = { scopeMenuExpanded = false },
            ) {
                MemoryScope.entries.forEach { scope ->
                    DropdownMenuItem(
                        text = { Text(memoryScopeLabel(scope)) },
                        onClick = {
                            scopeMenuExpanded = false
                            if (scope != memory.scope) {
                                onChange(memory.copy(scope = scope))
                            }
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = classificationEditable) {
                    onChange(memory.copy(pinned = !memory.pinned))
                }
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_agent_memory_pinned),
                    style = LocalAmberType.current.body,
                    color = workspaceColors().ink,
                )
                Text(
                    text = stringResource(R.string.setting_agent_memory_pinned_desc),
                    style = LocalAmberType.current.secondary,
                    color = workspaceColors().muted,
                )
            }
            Switch(
                checked = memory.pinned,
                onCheckedChange = { onChange(memory.copy(pinned = it)) },
                enabled = classificationEditable,
            )
        }
    }
}

@Composable
private fun memorySourceLabel(memory: AssistantMemory): String {
    val parts = buildList {
        if (!memory.sourceConversationId.isNullOrBlank()) {
            add(stringResource(R.string.setting_agent_memory_source_chat))
        }
        if (memory.sourceMessageIds.isNotEmpty()) {
            add(
                stringResource(
                    R.string.setting_agent_memory_source_messages,
                    memory.sourceMessageIds.size,
                )
            )
        }
        if (memory.supersedesIds.isNotEmpty()) {
            add(
                stringResource(
                    R.string.setting_agent_memory_source_supersedes,
                    memory.supersedesIds.size,
                )
            )
        }
    }
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return when (memory.sourceTrigger) {
        MemoryRepository.TRIGGER_AUTO_EXTRACTION ->
            stringResource(R.string.setting_agent_memory_source_auto_extraction)
        MemoryRepository.TRIGGER_TOOL -> stringResource(R.string.setting_agent_memory_source_tool)
        MemoryRepository.TRIGGER_DREAM -> stringResource(R.string.setting_agent_memory_source_dream)
        null -> if (memory.sourceRunId == null) {
            stringResource(R.string.setting_agent_memory_source_manual)
        } else {
            stringResource(R.string.setting_agent_memory_source_run)
        }
        else -> stringResource(R.string.setting_agent_memory_source_run)
    }
}

@Composable
private fun memoryScopeLabel(scope: MemoryScope): String = when (scope) {
    MemoryScope.CORE -> stringResource(R.string.setting_agent_memory_scope_core)
    MemoryScope.SHORT_TERM -> stringResource(R.string.setting_agent_memory_scope_short_term)
    MemoryScope.LONG_TERM -> stringResource(R.string.setting_agent_memory_scope_long_term)
}

@Composable
private fun memoryKindLabel(kind: MemoryKind): String = when (kind) {
    MemoryKind.USER -> stringResource(R.string.setting_agent_memory_kind_user)
    MemoryKind.FEEDBACK -> stringResource(R.string.setting_agent_memory_kind_feedback)
    MemoryKind.PROJECT -> stringResource(R.string.setting_agent_memory_kind_project)
    MemoryKind.REFERENCE -> stringResource(R.string.setting_agent_memory_kind_reference)
    MemoryKind.ROUTINE -> stringResource(R.string.setting_agent_memory_kind_routine)
    MemoryKind.NOTE -> stringResource(R.string.setting_agent_memory_kind_note)
    MemoryKind.TOPIC -> stringResource(R.string.setting_agent_memory_kind_topic)
}

private fun formatMemoryDate(value: Long): String =
    DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault(),
    ).format(Date(value))

enum class MemorySettingsSubpage {
    Overview,
    Recall,
    Worker,
    Compaction,
    Library,
}

@Composable
fun SettingAgentMemoryRecallPage() {
    SettingAgentMemoryPage(subpage = MemorySettingsSubpage.Recall)
}

@Composable
fun SettingAgentMemoryWorkerPage() {
    SettingAgentMemoryPage(subpage = MemorySettingsSubpage.Worker)
}

@Composable
fun SettingAgentMemoryCompactionPage() {
    SettingAgentMemoryPage(subpage = MemorySettingsSubpage.Compaction)
}

@Composable
fun SettingAgentMemoryLibraryPage() {
    SettingAgentMemoryPage(subpage = MemorySettingsSubpage.Library)
}

private fun MemorySettingsSubpage.toScreen(): Screen = when (this) {
    MemorySettingsSubpage.Overview -> Screen.SettingAgentMemory
    MemorySettingsSubpage.Recall -> Screen.SettingAgentMemoryRecall
    MemorySettingsSubpage.Worker -> Screen.SettingAgentMemoryWorker
    MemorySettingsSubpage.Compaction -> Screen.SettingAgentMemoryCompaction
    MemorySettingsSubpage.Library -> Screen.SettingAgentMemoryLibrary
}

@Composable
private fun MemoryOverviewEntries(
    pendingCandidateCount: Int,
    coreCount: Int,
    shortCount: Int,
    longCount: Int,
    hasPendingDreamPlan: Boolean,
    coreMemories: List<AssistantMemory>,
    shortTermMemories: List<AssistantMemory>,
    longTermMemories: List<AssistantMemory>,
    onOpen: (MemorySettingsSubpage) -> Unit,
) {
    SettingCardGroup(title = stringResource(R.string.chat_message_tool_kind_memory)) {
        item(
            onClick = { onOpen(MemorySettingsSubpage.Recall) },
            headlineContent = { Text(stringResource(R.string.memory_recall_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.memory_recall_desc)) },
            trailingContent = { MemoryChevron() },
        )
        item(
            onClick = { onOpen(MemorySettingsSubpage.Worker) },
            headlineContent = { Text(stringResource(R.string.memory_worker_title)) },
            supportingContent = {
                val suffix = if (hasPendingDreamPlan) {
                    stringResource(R.string.memory_worker_manual_suffix)
                } else {
                    stringResource(R.string.memory_worker_pending_suffix, pendingCandidateCount)
                }
                MemoryRowSubtitle(stringResource(R.string.memory_worker_desc, suffix))
            },
            trailingContent = { MemoryChevron() },
        )
        item(
            onClick = { onOpen(MemorySettingsSubpage.Compaction) },
            headlineContent = { Text(stringResource(R.string.memory_compaction_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.memory_compaction_desc)) },
            trailingContent = { MemoryChevron() },
        )
        item(
            onClick = { onOpen(MemorySettingsSubpage.Library) },
            headlineContent = { Text(stringResource(R.string.memory_library_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(
                        R.string.memory_library_desc,
                        coreCount,
                        shortCount,
                        longCount,
                        pendingCandidateCount,
                    )
                )
            },
            trailingContent = { MemoryChevron() },
        )
    }
    Spacer(Modifier.height(12.dp))
    MemoryOverviewPreview(
        coreMemories = coreMemories,
        shortTermMemories = shortTermMemories,
        longTermMemories = longTermMemories,
    )
}

@Composable
private fun MemoryChevron() {
    Icon(
        imageVector = Lucide.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = LocalAmberTokens.current.ink3,
    )
}

@Composable
private fun MemoryRowSubtitle(
    text: String,
    mono: Boolean = true,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = text,
        style = if (mono) {
            type.meta.copy(fontSize = 12.sp, lineHeight = 16.sp)
        } else {
            type.secondary.copy(fontSize = 13.sp, lineHeight = 18.sp)
        },
        color = tokens.ink3,
    )
}

@Composable
private fun MemoryOverviewPreview(
    coreMemories: List<AssistantMemory>,
    shortTermMemories: List<AssistantMemory>,
    longTermMemories: List<AssistantMemory>,
) {
    val previewMemories = (coreMemories + shortTermMemories + longTermMemories)
        .filter { !it.archived && !it.isSummarySensitive() }
        .distinctBy { it.id }
        .take(3)
    SettingCardGroup(title = stringResource(R.string.redesign_memory_library_preview)) {
        if (previewMemories.isEmpty()) {
            item(
                headlineContent = { Text(stringResource(R.string.memory_summary_empty)) },
            )
        } else {
            previewMemories.forEach { memory ->
                item(
                    headlineContent = {
                        Text(
                            text = if (memory.kind == MemoryKind.TOPIC &&
                                !memory.topicTitle.isNullOrBlank()
                            ) {
                                "${memory.topicTitle} · ${memory.content}"
                            } else {
                                memory.content
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        SettingTileIcon(
                            when (memory.kind) {
                                MemoryKind.USER, MemoryKind.FEEDBACK -> Lucide.Sparkles
                                MemoryKind.PROJECT, MemoryKind.REFERENCE -> Lucide.Smartphone
                                MemoryKind.TOPIC -> Lucide.Folder
                                else -> Lucide.Maximize
                            },
                        )
                    },
                    trailingContent = {
                        MemoryScopePill(memoryScopeLabel(memory.scope))
                    },
                )
            }
        }
    }
}

@Composable
private fun MemoryScopePill(text: String) {
    val tokens = LocalAmberTokens.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tokens.surface2)
            .border(1.dp, tokens.line, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = LocalAmberType.current.meta.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
            color = tokens.ink2,
            maxLines = 1,
        )
    }
}

@Composable
private fun MemoryRecallSubpage(
    settings: Settings,
    onUpdate: ((AgentRuntimeSetting) -> AgentRuntimeSetting) -> Unit,
) {
    SettingCardGroup(title = stringResource(R.string.chat_message_tool_kind_memory)) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_core_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.setting_agent_memory_core_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableCoreMemory,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableCoreMemory = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_short_term_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.setting_agent_memory_short_term_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableShortTermMemory,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableShortTermMemory = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_long_term_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.setting_agent_memory_long_term_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableLongTermMemory,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableLongTermMemory = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_recent_chats_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.setting_agent_memory_recent_chats_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableRecentChatsReference,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableRecentChatsReference = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_time_reminder_title)) },
            supportingContent = { MemoryRowSubtitle(stringResource(R.string.setting_agent_memory_time_reminder_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableTimeReminder,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableTimeReminder = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.memory_selective_recall_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(
                        R.string.memory_selective_recall_desc,
                        settings.agentRuntime.memoryRecall.maxItems,
                        settings.agentRuntime.memoryRecall.maxPromptChars,
                    )
                )
            },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.memoryRecall.debug,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(memoryRecall = it.memoryRecall.copy(debug = enabled)) }
                    },
                )
            },
        )
    }
}

@Composable
private fun MemoryWorkerSubpage(
    settings: Settings,
    pendingCandidateCount: Int,
    eventCount: Int,
    dreamPlan: PersistedMemoryDreamPlan?,
    running: Boolean,
    onUpdate: ((AgentRuntimeSetting) -> AgentRuntimeSetting) -> Unit,
    onPlan: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val worker = settings.agentRuntime.memoryWorker
    val canRunDream = worker.enabled && MemoryWorkerDreamGate.isAnyDreamEnabled(worker)
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.memory_local_maintenance_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(
                        R.string.memory_local_maintenance_desc,
                        pendingCandidateCount,
                        eventCount,
                    ),
                    mono = false,
                )
            },
            trailingContent = {
                Switch(
                    checked = worker.dreamMaintenanceEnabled,
                    size = SwitchSize.Small,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                memoryWorker = it.memoryWorker.copy(
                                    dreamMaintenanceEnabled = enabled,
                                    dreamEnabled = false,
                                )
                            )
                        }
                    },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.memory_auto_apply_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(R.string.memory_auto_apply_desc),
                    mono = false,
                )
            },
            trailingContent = {
                Switch(
                    checked = worker.autoApplyMaintenance && worker.dreamMaintenanceEnabled,
                    size = SwitchSize.Small,
                    enabled = worker.dreamMaintenanceEnabled,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                memoryWorker = it.memoryWorker.copy(autoApplyMaintenance = enabled)
                            )
                        }
                    },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.memory_llm_maintenance_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(R.string.memory_llm_maintenance_desc),
                    mono = false,
                )
            },
            trailingContent = {
                Switch(
                    checked = worker.dreamModelEnabled,
                    size = SwitchSize.Small,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                memoryWorker = it.memoryWorker.copy(
                                    dreamModelEnabled = enabled,
                                    dreamEnabled = false,
                                )
                            )
                        }
                    },
                )
            },
        )
        if (!canRunDream) {
            item(
                headlineContent = { Text(stringResource(R.string.memory_run_unavailable_title)) },
                supportingContent = {
                    MemoryRowSubtitle(
                        stringResource(R.string.memory_run_unavailable_desc),
                        mono = false,
                    )
                },
            )
        }
        // The following toggles were removed in favor of defaults:
        //   - 记忆后台任务 (worker.enabled)            → field kept ON
        //   - 对话结束后提取 (worker.extractionEnabled) → field kept ON
        //   - 跟随压缩模型 (worker.followCompressModel)  → moved to model settings page
        //   - 只在充电时运行 (worker.runOnlyOnCharging)  → kept ON; wired to the nightly
        //     WorkManager constraint, where it replaces the idle gate when enabled
        //   - 仅设备空闲时 (worker.runOnlyOnIdle)         → inert while charging-only is on
        //     (no UI for that pref), so the row was removed; the pref is still honored
        // Manual "立即运行一次" → moved to toolbar play icon.
    }

    Spacer(Modifier.height(12.dp))
    DreamReviewSection(
        plan = dreamPlan,
        running = running,
        canRun = canRunDream,
        onPlan = onPlan,
        onApply = onApply,
        onDismiss = onDismiss,
    )
}

@Composable
private fun MemoryCompactionSubpage(
    settings: Settings,
    onUpdate: ((AgentRuntimeSetting) -> AgentRuntimeSetting) -> Unit,
) {
    val compaction = settings.agentRuntime.contextCompaction
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_compaction_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MemoryRowSubtitle(
                        stringResource(R.string.setting_agent_memory_context_compaction_desc)
                    )
                    MemoryRowSubtitle(
                        stringResource(R.string.setting_agent_memory_context_compaction_defaults)
                    )
                }
            },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.contextCompaction.enabled,
                    size = SwitchSize.Small,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                contextCompaction = it.contextCompaction.copy(
                                    enabled = enabled,
                                    notifyOnly = if (enabled) false else it.contextCompaction.notifyOnly,
                                )
                            )
                        }
                    },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_compaction_notify_title)) },
            supportingContent = {
                MemoryRowSubtitle(
                    stringResource(R.string.setting_agent_memory_context_compaction_notify_desc)
                )
            },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.contextCompaction.notifyOnly,
                    size = SwitchSize.Small,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                contextCompaction = it.contextCompaction.copy(
                                    notifyOnly = enabled,
                                    enabled = if (enabled) false else it.contextCompaction.enabled,
                                )
                            )
                        }
                    },
                )
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    SettingCardGroup(title = stringResource(R.string.setting_agent_memory_context_threshold_section)) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_force_threshold_title)) },
            trailingContent = {
                Text(
                    text = "${(compaction.forceRatio * 100f).toInt()}%",
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_precompact_threshold_title)) },
            trailingContent = {
                Text(
                    text = "${(compaction.precompactRatio * 100f).toInt()}%",
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    SettingCardGroup(title = stringResource(R.string.setting_agent_memory_context_protect_section)) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_protect_turns_title)) },
            trailingContent = {
                Text(
                    text = stringResource(
                        R.string.setting_agent_memory_context_protect_turns_value,
                        compaction.keepRecentTurns,
                    ),
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_protect_messages_title)) },
            trailingContent = {
                Text(
                    text = stringResource(
                        R.string.setting_agent_memory_context_protect_messages_value,
                        compaction.keepRecentTurns * 2,
                    ),
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            },
        )
    }
}

@Composable
private fun MemoryLibrarySubpage(
    modifier: Modifier,
    memories: List<AssistantMemory>,
    shortTermMemories: List<AssistantMemory>,
    longTermMemories: List<AssistantMemory>,
    pendingCandidates: List<MemoryCandidate>,
    recentMemoryEvents: List<MemoryEvent>,
    running: Boolean,
    onAcceptCandidate: (String) -> Unit,
    onIgnoreCandidate: (String) -> Unit,
    onIgnoreLowConfidenceCandidates: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onAddMemory: () -> Unit,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onInfoClick: (String, String) -> Unit,
) {
    var showPortabilityDialog by remember { mutableStateOf(false) }
    var showEventsDialog by remember { mutableStateOf(false) }
    val docsTitle = stringResource(R.string.memory_docs_title)
    val docsEmptyText = stringResource(R.string.memory_docs_empty)
    val docsInfoText = stringResource(R.string.memory_docs_info_body)

    // The library renders memories as documents: the core bucket is one file,
    // each dream-synthesized topic is one file, and every record not yet
    // grouped into a topic merges into its per-kind document.
    val docs = remember(memories, shortTermMemories, longTermMemories) {
        buildMemoryDocs(memories, shortTermMemories, longTermMemories)
    }
    var openDocId by remember { mutableStateOf<String?>(null) }
    val openDoc = docs.firstOrNull { it.id == openDocId }
    BackHandler(enabled = openDoc != null) { openDocId = null }
    val listState = rememberLazyListState()

    if (openDoc != null) {
        MemoryDocDetail(
            doc = openDoc,
            modifier = modifier,
            onBack = { openDocId = null },
            onEditMemory = onEditMemory,
            onDeleteMemory = onDeleteMemory,
        )
        return
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item("memory_summary") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MemorySummarySection(
                    coreMemories = memories,
                    longTermMemories = longTermMemories,
                    shortTermMemories = shortTermMemories,
                    onEditMemory = onEditMemory,
                )
            }
        }

        item("memory_gap_candidates") { Spacer(Modifier.height(28.dp)) }
        memoryCandidatesSection(
            candidates = pendingCandidates,
            onAccept = onAcceptCandidate,
            onIgnore = onIgnoreCandidate,
            onIgnoreLowConfidence = onIgnoreLowConfidenceCandidates,
        )

        item("memory_gap_docs") { Spacer(Modifier.height(28.dp)) }
        memoryDocumentsSection(
            title = docsTitle,
            emptyText = docsEmptyText,
            docs = docs,
            infoText = docsInfoText,
            onInfoClick = onInfoClick,
            onAddMemory = onAddMemory,
            onOpenDoc = { openDocId = it },
        )

        item("memory_gap_maintenance") { Spacer(Modifier.height(28.dp)) }
        item("memory_maintenance") {
            MemoryMaintenanceSection(
                eventCount = recentMemoryEvents.size,
                onOpenPortability = { showPortabilityDialog = true },
                onOpenEvents = { showEventsDialog = true },
            )
        }
    }

    if (showPortabilityDialog) {
        AlertDialog(
            onDismissRequest = { showPortabilityDialog = false },
            title = { Text(stringResource(R.string.memory_import_export_title)) },
            text = {
                MemoryPortabilitySection(
                    running = running,
                    onExport = onExport,
                    onImport = onImport,
                )
            },
            confirmButton = {
                TextButton(onClick = { showPortabilityDialog = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    if (showEventsDialog) {
        AlertDialog(
            onDismissRequest = { showEventsDialog = false },
            title = { Text(stringResource(R.string.memory_event_log_title)) },
            text = { MemoryEventsSection(events = recentMemoryEvents, showTitle = false) },
            confirmButton = {
                TextButton(onClick = { showEventsDialog = false }) {
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
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val openEditor = {
        draft = value
        showEditor = true
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = tokens.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, tokens.line),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = openEditor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingTileIcon(Lucide.FileText)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "agents.md",
                    style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.ink,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.setting_agent_memory_soul_desc),
                    style = type.secondary,
                    color = tokens.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = openEditor) {
                Text(stringResource(R.string.edit), style = type.secondary)
            }
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
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun MemorySummarySection(
    coreMemories: List<AssistantMemory>,
    longTermMemories: List<AssistantMemory>,
    shortTermMemories: List<AssistantMemory>,
    onEditMemory: (AssistantMemory) -> Unit,
) {
    val stableMemories = (coreMemories + longTermMemories)
        .filter { memory ->
            !memory.archived &&
                !memory.isSummarySensitive() &&
                (
                    memory.scope == MemoryScope.CORE ||
                        memory.kind == MemoryKind.USER ||
                        memory.kind == MemoryKind.FEEDBACK ||
                        memory.kind == MemoryKind.ROUTINE ||
                        memory.pinned
                    )
        }
        .distinctBy { it.id }
    val longTermProjects = longTermMemories
        .filter { memory ->
                !memory.archived &&
                !memory.isSummarySensitive() &&
                memory.kind in setOf(
                    MemoryKind.PROJECT,
                    MemoryKind.REFERENCE,
                )
        }
    val currentProjects = shortTermMemories
        .filter { memory ->
            !memory.archived &&
                !memory.isSummarySensitive() &&
                memory.kind == MemoryKind.PROJECT
        }

    val emptyValue = stringResource(R.string.memory_summary_empty)
    val facts = listOf(
        SummaryFact(
            label = stringResource(R.string.memory_summary_stable_preferences),
            value = stableMemories.firstOrNull()?.content ?: emptyValue,
            memory = stableMemories.firstOrNull(),
        ),
        SummaryFact(
            label = stringResource(R.string.memory_summary_long_term_projects),
            value = longTermProjects.firstOrNull()?.content ?: emptyValue,
            memory = longTermProjects.firstOrNull(),
        ),
        SummaryFact(
            label = stringResource(R.string.memory_summary_current_short_term),
            value = currentProjects.firstOrNull()?.content ?: emptyValue,
            memory = currentProjects.firstOrNull(),
        ),
    )

    SettingSectionTitle(
        text = stringResource(R.string.memory_summary_title),
        modifier = Modifier.padding(start = 2.dp, top = 8.dp, end = 2.dp),
    )
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(LocalAmberTokens.current.line),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(fact.memory?.let { memory -> Modifier.clickable { onEditMemory(memory) } } ?: Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .heightIn(min = 64.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = fact.label,
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink3,
                )
                Text(
                    text = fact.value,
                    style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                    color = LocalAmberTokens.current.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class SummaryFact(
    val label: String,
    val value: String,
    val memory: AssistantMemory?,
)

private fun AssistantMemory.isSummarySensitive(): Boolean {
    return isSensitiveMemoryContent(content)
}

private fun memoryGroupShape(index: Int, size: Int): RoundedCornerShape = when {
    size <= 1 -> RoundedCornerShape(14.dp)
    index == 0 -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    index == size - 1 -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    else -> RoundedCornerShape(0.dp)
}

internal fun LazyListScope.memoryCandidatesSection(
    candidates: List<MemoryCandidate>,
    onAccept: (String) -> Unit,
    onIgnore: (String) -> Unit,
    onIgnoreLowConfidence: () -> Unit,
) {
    val lowConfidenceCount = candidates.count { it.confidence < LOW_CONFIDENCE_CANDIDATE_THRESHOLD }
    item("memory_candidate_section_title") {
        SettingSectionTitle(stringResource(R.string.memory_candidate_review_title))
    }
    item("memory_candidate_card_gap") { Spacer(Modifier.height(8.dp)) }
    if (candidates.isEmpty()) {
        item("memory_candidate_empty") {
            AmberCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.memory_candidate_empty),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        return
    }

    val groupSize = candidates.size + if (lowConfidenceCount > 0) 1 else 0
    itemsIndexed(
        items = candidates,
        key = { _, candidate -> "memory_candidate_${candidate.id}" },
    ) { index, candidate ->
        MemoryCandidateCard(
            candidate = candidate,
            shape = memoryGroupShape(index, groupSize),
            onAccept = { onAccept(candidate.id) },
            onIgnore = { onIgnore(candidate.id) },
        )
    }
    if (lowConfidenceCount > 0) {
        item("memory_candidate_low_confidence") {
            val tokens = LocalAmberTokens.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(memoryGroupShape(groupSize - 1, groupSize))
                    .background(tokens.surface)
                    .border(1.dp, tokens.line, memoryGroupShape(groupSize - 1, groupSize))
                    .padding(horizontal = 14.dp),
                horizontalAlignment = Alignment.End,
            ) {
                TextButton(onClick = onIgnoreLowConfidence) {
                    Text(stringResource(R.string.memory_ignore_low_confidence, lowConfidenceCount))
                }
            }
        }
    }
}

@Composable
private fun MemoryCandidateCard(
    candidate: MemoryCandidate,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    onAccept: () -> Unit,
    onIgnore: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.surface)
            .border(1.dp, tokens.line, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val reviewSuffix = if (candidate.confidence >= LOW_CONFIDENCE_CANDIDATE_THRESHOLD) {
            stringResource(R.string.memory_recommend_manual_review)
        } else {
            ""
        }
        Text(
            text = stringResource(
                R.string.memory_candidate_meta,
                candidate.scope.wireName,
                candidate.kind.wireName,
                "%.2f".format(candidate.confidence),
                reviewSuffix,
            ),
            // Graphite §3: scope/kind tags + confidence value are machine-facts → MONO.
            style = LocalAmberType.current.meta,
            color = LocalAmberTokens.current.accent,
        )
        Text(
            text = candidate.content,
            style = LocalAmberType.current.body,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (candidate.reason.isNotBlank()) {
            Text(
                text = candidate.reason,
                style = LocalAmberType.current.secondary,
                color = workspaceColors().muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.memory_accept))
            }
            TextButton(onClick = onIgnore) {
                Text(stringResource(R.string.memory_ignore))
            }
        }
    }
}

@Composable
private fun DreamReviewSection(
    plan: PersistedMemoryDreamPlan?,
    running: Boolean,
    canRun: Boolean,
    onPlan: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingSectionTitle(stringResource(R.string.memory_manual_review_title))
        AmberCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (plan == null) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(tokens.surface2)
                            .border(1.dp, tokens.line, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.Sparkles,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = tokens.ink2,
                        )
                    }
                    Text(
                        text = stringResource(R.string.memory_no_manual_plan),
                        style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.ink,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.memory_manual_review_desc),
                        style = LocalAmberType.current.secondary,
                        color = tokens.ink2,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        enabled = !running && canRun,
                        onClick = onPlan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(tokens.surface2)
                            .border(1.dp, tokens.line, RoundedCornerShape(15.dp)),
                    ) {
                        if (running) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                        } else {
                            Text(stringResource(R.string.memory_generate_suggestion))
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.memory_manual_review_title),
                                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                                color = tokens.ink,
                            )
                            Text(
                                stringResource(R.string.memory_manual_review_desc),
                                style = LocalAmberType.current.secondary,
                                color = tokens.ink2,
                            )
                        }
                        if (running) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }

                    val persisted = plan
                    val current = persisted.plan
                    val summary = stringResource(
                        R.string.memory_dream_summary,
                        current.mergeSuggestions.size,
                        current.promoteMemoryIds.size,
                        current.archiveMemoryIds.size,
                        current.supersedeSuggestions.size,
                        current.ignoreCandidateIds.size,
                        current.topicSuggestions.size,
                    )
                    // Graphite §3: dream-plan summary is a count-dense machine-fact → MONO.
                    Text(
                        summary,
                        style = LocalAmberType.current.meta,
                        color = tokens.ink,
                    )
                    Text(
                        text = stringResource(
                            R.string.memory_dream_source,
                            if (persisted.source.name == "AUTO") {
                                stringResource(R.string.memory_dream_source_auto)
                            } else {
                                stringResource(R.string.memory_dream_source_manual)
                            },
                        ),
                        style = LocalAmberType.current.secondary,
                        color = tokens.ink2,
                    )
                    current.notes.take(6).forEach { note ->
                        Text(
                            text = "• $note",
                            style = LocalAmberType.current.secondary,
                            color = tokens.ink2,
                        )
                    }
                    current.mergeSuggestions.take(5).forEach { suggestion ->
                        // Graphite §3: merge suggestion = #id references → MONO.
                        Text(
                            text = stringResource(
                                R.string.memory_dream_merge,
                                suggestion.targetMemoryId,
                                suggestion.duplicateMemoryIds.joinToString(","),
                            ),
                            style = LocalAmberType.current.meta,
                            color = tokens.ink,
                        )
                    }
                    current.supersedeSuggestions.take(5).forEach { suggestion ->
                        val reasonSuffix = if (suggestion.reason.isNotBlank()) {
                            stringResource(R.string.memory_dream_reason_suffix, suggestion.reason)
                        } else {
                            ""
                        }
                        Text(
                            text = stringResource(
                                R.string.memory_dream_replace,
                                suggestion.oldMemoryIds.joinToString(","),
                                suggestion.newContent,
                                reasonSuffix,
                            ),
                            style = LocalAmberType.current.secondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    current.topicSuggestions.take(4).forEach { suggestion ->
                        val reasonSuffix = if (suggestion.reason.isNotBlank()) {
                            stringResource(R.string.memory_dream_reason_suffix, suggestion.reason)
                        } else {
                            ""
                        }
                        Text(
                            text = stringResource(
                                R.string.memory_dream_topic,
                                suggestion.title,
                                suggestion.memberMemoryIds.joinToString(", ") { "#$it" },
                                reasonSuffix,
                            ),
                            style = LocalAmberType.current.secondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !running && canRun,
                            onClick = onPlan,
                        ) {
                            Text(stringResource(R.string.memory_generate_suggestion))
                        }
                        TextButton(
                            enabled = !running && persisted.plan.hasChanges,
                            onClick = onApply,
                        ) {
                            Text(stringResource(R.string.memory_apply_suggestion))
                        }
                        TextButton(
                            enabled = !running,
                            onClick = onDismiss,
                        ) {
                            Text(stringResource(R.string.memory_clear_suggestion))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryEventsSection(events: List<MemoryEvent>) {
    MemoryEventsSection(events = events, showTitle = true)
}

@Composable
private fun MemoryEventsSection(
    events: List<MemoryEvent>,
    showTitle: Boolean,
) {
    if (showTitle) {
        SectionLabel(
            text = stringResource(R.string.memory_event_log_title),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (events.isEmpty()) {
        Text(
            text = stringResource(R.string.memory_event_log_empty),
            style = LocalAmberType.current.secondary,
            color = workspaceColors().muted,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        return
    }
    CardGroup {
        events.take(6).forEach { event ->
            item(
                headlineContent = { Text(event.type.wireName) },
                supportingContent = {
                    val message = event.message.ifBlank { "memory=${event.memoryId ?: "-"} candidate=${event.candidateId ?: "-"}" }
                    // Graphite §3: fallback shows raw memory/candidate ids → MONO.
                    val isIdFallback = event.message.isBlank()
                    Text(
                        message,
                        style = if (isIdFallback) LocalAmberType.current.meta else LocalAmberType.current.secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun MemoryMaintenanceSection(
    eventCount: Int,
    onOpenPortability: () -> Unit,
    onOpenEvents: () -> Unit,
) {
    SettingCardGroup(title = stringResource(R.string.memory_maintenance_tools_title)) {
        item(
            onClick = onOpenPortability,
            headlineContent = { Text(stringResource(R.string.memory_import_export_title)) },
            supportingContent = { Text(stringResource(R.string.memory_import_export_desc)) },
        )
        item(
            onClick = onOpenEvents,
            headlineContent = { Text(stringResource(R.string.memory_event_log_title)) },
            supportingContent = { Text(stringResource(R.string.memory_event_log_desc, eventCount)) },
        )
    }
}

@Composable
private fun MemoryPortabilitySection(
    running: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.memory_frontmatter_export_title)) },
            supportingContent = { Text(stringResource(R.string.memory_frontmatter_export_desc)) },
            trailingContent = {
                TextButton(enabled = !running, onClick = onExport) {
                    Text(stringResource(R.string.memory_export))
                }
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.memory_frontmatter_import_title)) },
            supportingContent = { Text(stringResource(R.string.memory_frontmatter_import_desc)) },
            trailingContent = {
                TextButton(enabled = !running, onClick = onImport) {
                    Text(stringResource(R.string.memory_import))
                }
            },
        )
    }
}

private data class MemoryDoc(
    val id: String,
    val fileName: String,
    /** Non-null when the document is a dream-synthesized topic file. */
    val topic: AssistantMemory?,
    /** Aggregate: the doc's records. Topic: resolved live member records. */
    val records: List<AssistantMemory>,
    val updatedAt: Long,
    val sizeBytes: Int,
    val preview: String,
)

private fun buildMemoryDocs(
    coreMemories: List<AssistantMemory>,
    shortTermMemories: List<AssistantMemory>,
    longTermMemories: List<AssistantMemory>,
): List<MemoryDoc> {
    val byId = (coreMemories + shortTermMemories + longTermMemories).associateBy { it.id }
    val topics = (shortTermMemories + longTermMemories)
        .filter { it.kind == MemoryKind.TOPIC && !it.archived }
    // Members already filed under a topic must not reappear in the loose
    // per-kind documents — the doc list stays close to a partition.
    val topicMemberIds = topics.flatMap { it.memberIds }.toSet()
    val loose = (shortTermMemories + longTermMemories)
        .filter { it.kind != MemoryKind.TOPIC && !it.archived && it.id !in topicMemberIds }
    return buildList {
        val liveCore = coreMemories.filter { !it.archived }
        if (liveCore.isNotEmpty()) {
            add(memoryDoc("core", "core.md", null, liveCore))
        }
        // Two titles can normalize to the same slug — disambiguate with the id
        // so the rows stay distinguishable.
        val slugCounts = topics
            .groupingBy { memoryDocSlug(it.topicTitle ?: it.content).ifBlank { "topic" } }
            .eachCount()
        topics.sortedByDescending { it.updatedAt }.forEach { topic ->
            val members = topic.memberIds
                .distinct()
                .mapNotNull { byId[it] }
                .filter {
                    !it.archived &&
                        it.scope != MemoryScope.CORE &&
                        it.kind != MemoryKind.TOPIC
                }
            val slug = memoryDocSlug(topic.topicTitle ?: topic.content).ifBlank { "topic" }
            val fileName = if (slugCounts.getValue(slug) > 1) {
                "$slug-${topic.id}.md"
            } else {
                "$slug.md"
            }
            add(memoryDoc("topic-${topic.id}", fileName, topic, members))
        }
        MemoryKind.entries
            .filter { it != MemoryKind.TOPIC }
            .forEach { kind ->
                loose.filter { it.kind == kind }
                    .takeIf { it.isNotEmpty() }
                    ?.let { add(memoryDoc("kind-${kind.wireName}", "${kind.wireName}.md", null, it)) }
            }
    }
}

private fun memoryDoc(
    id: String,
    fileName: String,
    topic: AssistantMemory?,
    records: List<AssistantMemory>,
): MemoryDoc {
    val sorted = records.sortedByDescending { it.updatedAt }
    val previewSource = topic?.content ?: sorted.firstOrNull()?.content.orEmpty()
    return MemoryDoc(
        id = id,
        fileName = fileName,
        topic = topic,
        records = sorted,
        updatedAt = listOfNotNull(topic?.updatedAt, sorted.firstOrNull()?.updatedAt).max(),
        sizeBytes = sorted.sumOf { it.content.toByteArray().size } +
            (topic?.content?.toByteArray()?.size ?: 0),
        preview = firstLineOf(previewSource),
    )
}

private fun memoryDocSlug(text: String): String =
    text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
        .take(48)

private fun firstLineOf(text: String): String =
    text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

private fun formatMemorySize(bytes: Int): String =
    if (bytes >= 1024) {
        String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
    } else {
        "$bytes B"
    }

private fun LazyListScope.memoryDocumentsSection(
    title: String,
    emptyText: String,
    docs: List<MemoryDoc>,
    infoText: String,
    onInfoClick: (String, String) -> Unit,
    onAddMemory: () -> Unit,
    onOpenDoc: (String) -> Unit,
) {
    item("memory_docs_header") {
        MemoryRecordsHeader(
            title = title,
            infoTitle = title,
            infoText = infoText,
            onInfoClick = onInfoClick,
            onAddMemory = onAddMemory,
        )
    }
    item("memory_docs_gap") { Spacer(Modifier.height(8.dp)) }
    if (docs.isEmpty()) {
        item("memory_docs_empty") {
            AmberCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = emptyText,
                    style = LocalAmberType.current.secondary,
                    color = workspaceColors().muted,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        return
    }
    itemsIndexed(
        items = docs,
        key = { _, doc -> "memory_doc_${doc.id}" },
    ) { index, doc ->
        MemoryDocRow(
            doc = doc,
            shape = memoryGroupShape(index, docs.size),
            onClick = { onOpenDoc(doc.id) },
        )
    }
}

@Composable
private fun MemoryDocRow(
    doc: MemoryDoc,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.surface)
            .border(1.dp, tokens.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = doc.fileName,
                    style = LocalAmberType.current.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = formatMemorySize(doc.sizeBytes),
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatMemoryDate(doc.updatedAt),
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                    maxLines = 1,
                )
            }
            Text(
                text = doc.preview.ifBlank { "—" },
                style = LocalAmberType.current.secondary,
                color = workspaceColors().muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = workspaceColors().muted,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun MemoryDocDetail(
    doc: MemoryDoc,
    modifier: Modifier,
    onBack: () -> Unit,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val countLabel = if (doc.topic != null) {
        pluralStringResource(R.plurals.memory_doc_members, doc.records.size, doc.records.size)
    } else {
        pluralStringResource(R.plurals.memory_doc_entries, doc.records.size, doc.records.size)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = tokens.ink,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.fileName,
                    style = LocalAmberType.current.sessionTitle,
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$countLabel · ${formatMemorySize(doc.sizeBytes)} · ${formatMemoryDate(doc.updatedAt)}",
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The topic record itself is the document — its edit/delete lives
            // on the header, not in the member entries below.
            doc.topic?.let { topic ->
                IconButton(
                    onClick = { onEditMemory(topic) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Lucide.Pencil,
                        contentDescription = stringResource(R.string.edit),
                        tint = workspaceColors().muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { onDeleteMemory(topic) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .height(1.dp)
                .background(tokens.line),
        )
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                doc.topic?.let { topic ->
                    item("doc_topic_body") {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            topic.topicTitle?.takeIf { it.isNotBlank() }?.let { title ->
                                Text(
                                    text = title,
                                    style = LocalAmberType.current.sessionTitle,
                                    color = tokens.ink,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                text = topic.content.trim(),
                                style = LocalAmberType.current.body,
                                color = tokens.ink,
                            )
                        }
                    }
                    if (doc.records.isNotEmpty()) {
                        item("doc_members_label") {
                            SectionLabel(
                                text = pluralStringResource(
                                    R.plurals.memory_doc_members,
                                    doc.records.size,
                                    doc.records.size,
                                ),
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
                itemsIndexed(
                    items = doc.records,
                    key = { _, memory -> "doc_entry_${memory.id}" },
                ) { index, memory ->
                    MemoryDocEntry(
                        memory = memory,
                        onEditMemory = onEditMemory,
                        onDeleteMemory = onDeleteMemory,
                    )
                    if (index < doc.records.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(tokens.line),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryDocEntry(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = memory.content.trim(),
            style = LocalAmberType.current.body,
            color = tokens.ink,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = listOf(
                    memoryKindLabel(memory.kind),
                    memoryScopeLabel(memory.scope),
                    formatMemoryDate(memory.updatedAt),
                ).joinToString(" · "),
                style = LocalAmberType.current.meta,
                color = workspaceColors().muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onEditMemory(memory) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Lucide.Pencil,
                    contentDescription = stringResource(R.string.edit),
                    tint = workspaceColors().muted,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { onDeleteMemory(memory) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MemoryRecordsHeader(
    title: String,
    infoTitle: String?,
    infoText: String?,
    onInfoClick: ((String, String) -> Unit)?,
    onAddMemory: (() -> Unit)?,
) {
    val tokens = LocalAmberTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(
                text = title,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(tokens.line),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onInfoClick != null && infoTitle != null && infoText != null) {
                    val infoDescription = stringResource(R.string.setting_agent_memory_info_content_description)
                    IconButton(
                        onClick = { onInfoClick(infoTitle, infoText) },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = infoDescription
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .border(
                                    width = 1.dp,
                                    color = workspaceColors().hairline,
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "?",
                                style = LocalAmberType.current.tinyTag,
                                color = workspaceColors().muted,
                            )
                        }
                    }
                }
                if (onAddMemory != null) {
                    IconButton(
                        onClick = onAddMemory,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Lucide.Plus,
                            contentDescription = stringResource(
                                R.string.setting_agent_memory_add_content_description,
                            ),
                        )
                    }
                }
            }
        }
    }
}


