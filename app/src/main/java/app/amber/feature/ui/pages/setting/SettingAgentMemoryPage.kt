package app.amber.feature.ui.pages.setting

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import app.amber.feature.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import app.amber.agent.R
import app.amber.agent.Screen
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
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.ConfirmDialog
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.hooks.EditStateContent
import app.amber.feature.ui.hooks.useEditState
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.androidx.compose.koinViewModel
import java.io.File

@Composable
fun SettingAgentMemoryPage(
    subpage: MemorySettingsSubpage = MemorySettingsSubpage.Overview,
) {
    val vm = koinViewModel<SettingAgentMemoryVM>()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val shortTermMemories by vm.shortTermMemories.collectAsStateWithLifecycle()
    val longTermMemories by vm.longTermMemories.collectAsStateWithLifecycle()
    val pendingCandidates by vm.pendingCandidates.collectAsStateWithLifecycle()
    val recentMemoryEvents by vm.recentMemoryEvents.collectAsStateWithLifecycle()
    val dreamPlan by vm.dreamPlan.collectAsStateWithLifecycle()
    val memoryTaskRunning by vm.memoryTaskRunning.collectAsStateWithLifecycle()
    val operationMessage by vm.operationMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
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
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryDialogState.dismiss() }) {
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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspaceColors().canvas,
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
            when (subpage) {
                MemorySettingsSubpage.Overview -> {
                    AgentSoulCard(
                        value = settings.agentRuntime.agentSoulMarkdown,
                        onSave = { value ->
                            vm.updateAgentRuntime { it.copy(agentSoulMarkdown = value) }
                        },
                    )
                    MemoryOverviewEntries(
                        pendingCandidateCount = pendingCandidates.size,
                        coreCount = memories.size,
                        shortCount = shortTermMemories.size,
                        longCount = longTermMemories.size,
                        hasPendingDreamPlan = dreamPlan != null,
                        onOpen = { target -> navController.navigate(target.toScreen()) },
                    )
                }

                MemorySettingsSubpage.Recall -> MemoryRecallSubpage(
                    settings = settings,
                    onUpdate = vm::updateAgentRuntime,
                )

                MemorySettingsSubpage.Worker -> MemoryWorkerSubpage(
                    settings = settings,
                    pendingCandidateCount = pendingCandidates.size,
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

                MemorySettingsSubpage.Library -> MemoryLibrarySubpage(
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
                    onAddMemory = { memoryDialogState.open(AssistantMemory(0, "")) },
                    onEditMemory = { memoryDialogState.open(it) },
                    onDeleteMemory = { pendingDeleteMemory = it },
                    onInfoClick = { title, text -> memoryInfoDialog = title to text },
                )
            }
        }
    }

    ConfirmDialog(
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
    onOpen: (MemorySettingsSubpage) -> Unit,
) {
    CardGroup {
        item(
            onClick = { onOpen(MemorySettingsSubpage.Recall) },
            headlineContent = { Text(stringResource(R.string.memory_recall_title)) },
            supportingContent = { Text(stringResource(R.string.memory_recall_desc)) },
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
                Text(stringResource(R.string.memory_worker_desc, suffix))
            },
        )
        item(
            onClick = { onOpen(MemorySettingsSubpage.Compaction) },
            headlineContent = { Text(stringResource(R.string.memory_compaction_title)) },
            supportingContent = { Text(stringResource(R.string.memory_compaction_desc)) },
        )
        item(
            onClick = { onOpen(MemorySettingsSubpage.Library) },
            headlineContent = { Text(stringResource(R.string.memory_library_title)) },
            supportingContent = {
                Text(stringResource(R.string.memory_library_desc, coreCount, shortCount, longCount, pendingCandidateCount))
            },
        )
    }
}

@Composable
private fun MemoryRecallSubpage(
    settings: Settings,
    onUpdate: ((AgentRuntimeSetting) -> AgentRuntimeSetting) -> Unit,
) {
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_core_title)) },
            supportingContent = { Text(stringResource(R.string.setting_agent_memory_core_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableCoreMemory,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableCoreMemory = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_short_term_title)) },
            supportingContent = { Text(stringResource(R.string.setting_agent_memory_short_term_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableShortTermMemory,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableShortTermMemory = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_long_term_title)) },
            supportingContent = { Text(stringResource(R.string.setting_agent_memory_long_term_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableLongTermMemory,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableLongTermMemory = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_recent_chats_title)) },
            supportingContent = { Text(stringResource(R.string.setting_agent_memory_recent_chats_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.enableRecentChatsReference,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enableRecentChatsReference = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_time_reminder_title)) },
            supportingContent = { Text(stringResource(R.string.setting_agent_memory_time_reminder_desc)) },
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
                Text(
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
                Text(
                    stringResource(
                        R.string.memory_local_maintenance_desc,
                        pendingCandidateCount,
                        eventCount,
                    )
                )
            },
            trailingContent = {
                Switch(
                    checked = worker.dreamMaintenanceEnabled,
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
            headlineContent = { Text(stringResource(R.string.memory_llm_maintenance_title)) },
            supportingContent = {
                Text(stringResource(R.string.memory_llm_maintenance_desc))
            },
            trailingContent = {
                Switch(
                    checked = worker.dreamModelEnabled,
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
        item(
            headlineContent = { Text(stringResource(R.string.memory_idle_only_title)) },
            supportingContent = { Text(stringResource(R.string.memory_idle_only_desc)) },
            trailingContent = {
                Switch(
                    checked = worker.runOnlyOnIdle,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(memoryWorker = it.memoryWorker.copy(runOnlyOnIdle = enabled)) }
                    },
                )
            },
        )
        if (!canRunDream) {
            item(
                headlineContent = { Text(stringResource(R.string.memory_run_unavailable_title)) },
                supportingContent = { Text(stringResource(R.string.memory_run_unavailable_desc)) },
            )
        }
        // The following toggles were removed in favor of defaults:
        //   - 记忆后台任务 (worker.enabled)            → field kept ON
        //   - 对话结束后提取 (worker.extractionEnabled) → field kept ON
        //   - 跟随压缩模型 (worker.followCompressModel)  → moved to model settings page
        //   - 只在充电时运行 (worker.runOnlyOnCharging)  → scheduler ignores; runs whenever
        // Manual "立即运行一次" → moved to toolbar play icon.
    }

    DreamReviewSection(
        plan = dreamPlan,
        running = running,
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
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.setting_agent_memory_context_compaction_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.setting_agent_memory_context_compaction_desc))
                    Text(
                        text = stringResource(R.string.setting_agent_memory_context_compaction_defaults),
                        style = LocalAmberType.current.secondary,
                        color = workspaceColors().muted,
                    )
                }
            },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.contextCompaction.enabled,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(contextCompaction = it.contextCompaction.copy(enabled = enabled)) }
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
                        onUpdate { it.copy(contextCompaction = it.contextCompaction.copy(notifyOnly = enabled)) }
                    },
                )
            },
        )
    }
}

@Composable
private fun MemoryLibrarySubpage(
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
    var showCandidates by remember { mutableStateOf(false) }

    MemorySummarySection(
        coreMemories = memories,
        longTermMemories = longTermMemories,
        shortTermMemories = shortTermMemories,
        onEditMemory = onEditMemory,
    )

    MemoryCandidateInboxEntry(
        candidateCount = pendingCandidates.size,
        lowConfidenceCount = pendingCandidates.count { it.confidence < LOW_CONFIDENCE_CANDIDATE_THRESHOLD },
        expanded = showCandidates,
        onToggle = { showCandidates = !showCandidates },
    )
    if (showCandidates) {
        MemoryCandidatesSection(
            candidates = pendingCandidates,
            onAccept = onAcceptCandidate,
            onIgnore = onIgnoreCandidate,
            onIgnoreLowConfidence = onIgnoreLowConfidenceCandidates,
        )
    }

    MemoryRecordsSection(
        title = stringResource(R.string.memory_core_title),
        emptyText = stringResource(R.string.setting_agent_memory_empty),
        memories = memories,
        infoTitle = stringResource(R.string.memory_core_info_title),
        infoText = stringResource(R.string.memory_core_info_body),
        onInfoClick = onInfoClick,
        onAddMemory = onAddMemory,
        onEditMemory = onEditMemory,
        onDeleteMemory = onDeleteMemory,
    )

    MemoryRecordsSection(
        title = stringResource(R.string.memory_short_title),
        emptyText = stringResource(R.string.setting_agent_memory_short_empty),
        memories = shortTermMemories,
        infoTitle = stringResource(R.string.setting_agent_memory_short_info_title),
        infoText = stringResource(R.string.setting_agent_memory_short_info_body),
        onInfoClick = onInfoClick,
        onAddMemory = null,
        onEditMemory = onEditMemory,
        onDeleteMemory = onDeleteMemory,
    )

    MemoryRecordsSection(
        title = stringResource(R.string.memory_long_title),
        emptyText = stringResource(R.string.setting_agent_memory_long_empty),
        memories = longTermMemories,
        infoTitle = stringResource(R.string.setting_agent_memory_long_info_title),
        infoText = stringResource(R.string.setting_agent_memory_long_info_body),
        onInfoClick = onInfoClick,
        onAddMemory = null,
        onEditMemory = onEditMemory,
        onDeleteMemory = onDeleteMemory,
    )

    MemoryMaintenanceSection(
        eventCount = recentMemoryEvents.size,
        onOpenPortability = { showPortabilityDialog = true },
        onOpenEvents = { showEventsDialog = true },
    )

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
    val previewText = if (value.isBlank()) {
        stringResource(R.string.setting_agent_memory_soul_empty_preview)
    } else {
        value
    }

    // V3: 强制跟 chatTheme.surface (即使 dynamicColor 开了 Material You, 这里也跟主题色, 不出现浅蓝底)
    val agentMemorySoulTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                draft = value
                showEditor = true
            },
        colors = CardDefaults.cardColors(containerColor = agentMemorySoulTheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_agent_memory_soul_title),
                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                color = workspaceColors().ink,
            )
            Text(
                text = stringResource(R.string.setting_agent_memory_soul_desc),
                style = LocalAmberType.current.secondary,
                color = workspaceColors().muted,
            )
            Text(
                text = previewText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 1.dp,
                        color = workspaceColors().hairline,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(14.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                // Graphite §3: agents.md preview is machine-fact text → MONO token.
                style = LocalAmberType.current.meta,
                color = if (value.isBlank()) {
                    workspaceColors().muted
                } else {
                    workspaceColors().ink
                },
            )
            Text(
                text = stringResource(R.string.setting_agent_memory_soul_edit_hint),
                style = LocalAmberType.current.secondary,
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

    SectionLabel(
        text = stringResource(R.string.memory_summary_title),
        modifier = Modifier.padding(horizontal = 8.dp),
    )
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val hasContent = stableMemories.isNotEmpty() ||
                longTermProjects.isNotEmpty() ||
                currentProjects.isNotEmpty()
            if (!hasContent) {
                Text(
                    text = stringResource(R.string.memory_summary_empty),
                    style = LocalAmberType.current.secondary,
                    color = workspaceColors().muted,
                )
            } else {
                MemorySummaryGroup(
                    title = stringResource(R.string.memory_summary_stable_preferences),
                    memories = stableMemories,
                    onEditMemory = onEditMemory,
                )
                MemorySummaryGroup(
                    title = stringResource(R.string.memory_summary_long_term_projects),
                    memories = longTermProjects,
                    onEditMemory = onEditMemory,
                )
                MemorySummaryGroup(
                    title = stringResource(R.string.memory_summary_current_short_term),
                    memories = currentProjects,
                    onEditMemory = onEditMemory,
                )
            }
        }
    }
}

@Composable
private fun MemorySummaryGroup(
    title: String,
    memories: List<AssistantMemory>,
    onEditMemory: (AssistantMemory) -> Unit,
) {
    if (memories.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = LocalAmberType.current.secondary.copy(fontWeight = FontWeight.SemiBold),
            color = LocalAmberTokens.current.accent,
        )
        memories.take(6).fastForEach { memory ->
            Text(
                text = "#${memory.id} [${memory.scope.wireName}/${memory.kind.wireName}] ${memory.content}",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditMemory(memory) },
                style = LocalAmberType.current.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MemoryCandidateInboxEntry(
    candidateCount: Int,
    lowConfidenceCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    CardGroup {
        item(
            onClick = onToggle,
            headlineContent = { Text(stringResource(R.string.memory_candidate_review_title)) },
            supportingContent = {
                Text(
                    if (candidateCount == 0) {
                        stringResource(R.string.memory_candidate_empty)
                    } else {
                        stringResource(
                            R.string.memory_candidate_counts,
                            candidateCount,
                            lowConfidenceCount,
                            if (expanded) {
                                stringResource(R.string.memory_collapse)
                            } else {
                                stringResource(R.string.memory_expand)
                            },
                        )
                    }
                )
            },
        )
    }
}

private fun AssistantMemory.isSummarySensitive(): Boolean {
    return isSensitiveMemoryContent(content)
}

@Composable
private fun MemoryCandidatesSection(
    candidates: List<MemoryCandidate>,
    onAccept: (String) -> Unit,
    onIgnore: (String) -> Unit,
    onIgnoreLowConfidence: () -> Unit,
) {
    SectionLabel(
        text = stringResource(R.string.memory_candidate_review_title),
        modifier = Modifier.padding(horizontal = 8.dp),
    )
    if (candidates.isEmpty()) {
        Text(
            text = stringResource(R.string.memory_candidate_empty),
            style = LocalAmberType.current.secondary,
            color = workspaceColors().muted,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        return
    }
    val lowConfidenceCount = candidates.count { it.confidence < LOW_CONFIDENCE_CANDIDATE_THRESHOLD }
    if (lowConfidenceCount > 0) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onIgnoreLowConfidence) {
                Text(stringResource(R.string.memory_ignore_low_confidence, lowConfidenceCount))
            }
        }
    }
    candidates.forEach { candidate ->
        AmberCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
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
                    TextButton(onClick = { onAccept(candidate.id) }) {
                        Text(stringResource(R.string.memory_accept))
                    }
                    TextButton(onClick = { onIgnore(candidate.id) }) {
                        Text(stringResource(R.string.memory_ignore))
                    }
                }
            }
        }
    }
}

@Composable
private fun DreamReviewSection(
    plan: PersistedMemoryDreamPlan?,
    running: Boolean,
    onPlan: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                        color = workspaceColors().ink,
                    )
                    Text(
                        stringResource(R.string.memory_manual_review_desc),
                        style = LocalAmberType.current.secondary,
                        color = workspaceColors().muted,
                    )
                }
                if (running) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            plan?.let { persisted ->
                val current = persisted.plan
                val summary = stringResource(
                    R.string.memory_dream_summary,
                    current.mergeSuggestions.size,
                    current.promoteMemoryIds.size,
                    current.archiveMemoryIds.size,
                    current.supersedeSuggestions.size,
                    current.ignoreCandidateIds.size,
                )
                // Graphite §3: dream-plan summary is a count-dense machine-fact → MONO.
                Text(
                    summary,
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().ink,
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
                    color = workspaceColors().muted,
                )
                current.notes.take(4).forEach { note ->
                    Text(
                        text = "• $note",
                        style = LocalAmberType.current.secondary,
                        color = workspaceColors().muted,
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
                        color = workspaceColors().ink,
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
            } ?: Text(
                text = stringResource(R.string.memory_no_manual_plan),
                style = LocalAmberType.current.secondary,
                color = workspaceColors().muted,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !running,
                    onClick = onPlan,
                ) {
                    Text(stringResource(R.string.memory_generate_suggestion))
                }
                TextButton(
                    enabled = !running && plan?.plan?.hasChanges == true,
                    onClick = onApply,
                ) {
                    Text(stringResource(R.string.memory_apply_suggestion))
                }
                TextButton(
                    enabled = !running && plan != null,
                    onClick = onDismiss,
                ) {
                    Text(stringResource(R.string.memory_clear_suggestion))
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
    CardGroup(title = { SectionLabel(stringResource(R.string.memory_maintenance_tools_title)) }) {
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
        SectionLabel(
            text = title,
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
        } else if (onAddMemory != null) {
            IconButton(
                onClick = onAddMemory,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(Lucide.Plus, contentDescription = null)
            }
        }
    }

    if (memories.isEmpty()) {
        Text(
            text = emptyText,
            style = LocalAmberType.current.secondary,
            color = workspaceColors().muted,
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
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
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
                    // Graphite §3: memory entry id is a machine-fact → MONO.
                    text = "#${memory.id}",
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                Text(
                    text = memory.content,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = LocalAmberType.current.body,
                )
            }
            IconButton(onClick = { onEditMemory(memory) }) {
                Icon(Lucide.Pencil, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { onDeleteMemory(memory) }) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
