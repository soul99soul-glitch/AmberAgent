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
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.AgentRuntimeSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.dream.PersistedMemoryDreamPlan
import me.rerere.rikkahub.data.memory.model.MemoryCandidate
import me.rerere.rikkahub.data.memory.model.MemoryEvent
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
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
        MemorySettingsSubpage.Recall -> "记忆开关"
        MemorySettingsSubpage.Worker -> "自动整理"
        MemorySettingsSubpage.Compaction -> "上下文管理"
        MemorySettingsSubpage.Library -> "记忆库"
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
            TopAppBar(
                title = { Text(pageTitle) },
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
            headlineContent = { Text("记忆开关") },
            supportingContent = { Text("核心、短期、长期、最近会话、时间提醒、选择性召回。") },
        )
        item(
            onClick = { onOpen(MemorySettingsSubpage.Worker) },
            headlineContent = { Text("自动整理") },
            supportingContent = {
                Text("后台提取、Daydream 自动管理、空闲和充电条件" + if (hasPendingDreamPlan) " · 有手动建议" else " · 待审核 $pendingCandidateCount 条")
            },
        )
        item(
            onClick = { onOpen(MemorySettingsSubpage.Compaction) },
            headlineContent = { Text("上下文管理") },
            supportingContent = { Text("压缩策略、提醒模式、默认阈值。") },
        )
        item(
            onClick = { onOpen(MemorySettingsSubpage.Library) },
            headlineContent = { Text("记忆库") },
            supportingContent = { Text("核心 $coreCount · 短期 $shortCount · 长期 $longCount · 候选 $pendingCandidateCount") },
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
            headlineContent = { Text("选择性召回") },
            supportingContent = {
                Text("每轮最多 ${settings.agentRuntime.memoryRecall.maxItems} 条、${settings.agentRuntime.memoryRecall.maxPromptChars} 字符，不再全量注入。")
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
    CardGroup {
        item(
            headlineContent = { Text("记忆后台任务") },
            supportingContent = { Text("生成结束后提取候选记忆，待审核 $pendingCandidateCount 条，最近事件 $eventCount 条。") },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.memoryWorker.enabled,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(memoryWorker = it.memoryWorker.copy(enabled = enabled)) }
                    },
                )
            },
        )
        item(
            headlineContent = { Text("对话结束后提取") },
            supportingContent = { Text("高置信短期项目可自动写入短期记忆，其它进入候选审核。") },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.memoryWorker.extractionEnabled,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(memoryWorker = it.memoryWorker.copy(extractionEnabled = enabled)) }
                    },
                )
            },
        )
        item(
            headlineContent = { Text("跟随压缩模型") },
            supportingContent = { Text("只控制对话结束后的记忆提取；Daydream 可在模型页单独指定更强模型。") },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.memoryWorker.followCompressModel,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(memoryWorker = it.memoryWorker.copy(followCompressModel = enabled)) }
                    },
                )
            },
        )
        item(
            headlineContent = { Text("Daydream 自动整理") },
            supportingContent = { Text("后台自动合并、提升、归档短期/长期记忆；核心记忆不会自动修改。") },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.memoryWorker.dreamEnabled,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(memoryWorker = it.memoryWorker.copy(dreamEnabled = enabled)) }
                    },
                )
            },
        )
        item(
            headlineContent = { Text("Daydream 推理强度") },
            supportingContent = {
                Text(
                    "当前 ${settings.agentRuntime.memoryWorker.daydreamReasoningLevel.memoryReasoningLabel()}；建议用较高强度处理合并、提升和归档判断。"
                )
            },
            trailingContent = {
                ReasoningButton(
                    onlyIcon = true,
                    reasoningLevel = settings.agentRuntime.memoryWorker.daydreamReasoningLevel,
                    onUpdateReasoningLevel = { level ->
                        onUpdate { it.copy(memoryWorker = it.memoryWorker.copy(daydreamReasoningLevel = level)) }
                    },
                )
            },
        )
        item(
            headlineContent = { Text("只在充电时运行") },
            supportingContent = { Text("自动 Daydream 默认需要联网、电量不低、正在充电；每天最多 ${settings.agentRuntime.memoryWorker.dreamMaxDailyRuns} 次。") },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.memoryWorker.runOnlyOnCharging,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(memoryWorker = it.memoryWorker.copy(runOnlyOnCharging = enabled)) }
                    },
                )
            },
        )
        item(
            headlineContent = { Text("只在设备空闲时运行") },
            supportingContent = { Text("Android 6.0+ 交给系统 idle 约束决定运行时机，不是松手几秒后的即时触发。") },
            trailingContent = {
                Switch(
                    checked = settings.agentRuntime.memoryWorker.runOnlyOnIdle,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(memoryWorker = it.memoryWorker.copy(runOnlyOnIdle = enabled)) }
                    },
                )
            },
        )
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onExport: () -> Unit,
    onImport: () -> Unit,
    onAddMemory: () -> Unit,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onInfoClick: (String, String) -> Unit,
) {
    var showPortabilityDialog by remember { mutableStateOf(false) }
    var showEventsDialog by remember { mutableStateOf(false) }

    MemoryCandidatesSection(
        candidates = pendingCandidates,
        onAccept = onAcceptCandidate,
        onIgnore = onIgnoreCandidate,
    )

    MemoryRecordsSection(
        title = "核心记忆",
        emptyText = stringResource(R.string.setting_agent_memory_empty),
        memories = memories,
        infoTitle = "核心记忆是什么？",
        infoText = "核心记忆、短期记忆和长期记忆是并列的三类记忆。核心记忆优先级最高，适合手动维护稳定偏好、身份设定和长期规则。",
        onInfoClick = onInfoClick,
        onAddMemory = onAddMemory,
        onEditMemory = onEditMemory,
        onDeleteMemory = onDeleteMemory,
    )

    MemoryRecordsSection(
        title = "短期记忆",
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
        title = "长期记忆",
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
            title = { Text("导入导出") },
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
            title = { Text("事件日志") },
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
private fun MemoryCandidatesSection(
    candidates: List<MemoryCandidate>,
    onAccept: (String) -> Unit,
    onIgnore: (String) -> Unit,
) {
    Text(
        text = "候选记忆审核",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
    if (candidates.isEmpty()) {
        Text(
            text = "暂无待审核候选。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        return
    }
    candidates.take(5).forEach { candidate ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CustomColors.cardColorsOnSurfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "[${candidate.scope.wireName}/${candidate.kind.wireName}] confidence ${"%.2f".format(candidate.confidence)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = candidate.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (candidate.reason.isNotBlank()) {
                    Text(
                        text = candidate.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onAccept(candidate.id) }) {
                        Text("接受")
                    }
                    TextButton(onClick = { onIgnore(candidate.id) }) {
                        Text("忽略")
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
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
                    Text("手动整理审核", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        "手动生成的 diff 仍需确认；后台 Daydream 会自动应用短期/长期整理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (running) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            plan?.let { persisted ->
                val current = persisted.plan
                val summary = "合并 ${current.mergeSuggestions.size} 组 · 提升 ${current.promoteMemoryIds.size} 条 · " +
                    "归档 ${current.archiveMemoryIds.size} 条 · 忽略候选 ${current.ignoreCandidateIds.size} 条"
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "来源：${if (persisted.source.name == "AUTO") "自动 Daydream" else "手动生成"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                current.notes.take(4).forEach { note ->
                    Text(
                        text = "• $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } ?: Text(
                text = "还没有手动整理建议。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !running,
                    onClick = onPlan,
                ) {
                    Text("生成建议")
                }
                TextButton(
                    enabled = !running && plan?.plan?.hasChanges == true,
                    onClick = onApply,
                ) {
                    Text("应用建议")
                }
                TextButton(
                    enabled = !running && plan != null,
                    onClick = onDismiss,
                ) {
                    Text("清除")
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
        Text(
            text = "记忆事件日志",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (events.isEmpty()) {
        Text(
            text = "暂无记忆事件。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    CardGroup(title = { Text("维护工具") }) {
        item(
            onClick = onOpenPortability,
            headlineContent = { Text("导入导出") },
            supportingContent = { Text("Frontmatter 备份与恢复。") },
        )
        item(
            onClick = onOpenEvents,
            headlineContent = { Text("事件日志") },
            supportingContent = { Text("查看最近 $eventCount 条记忆后台事件。") },
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
            headlineContent = { Text("Frontmatter 导出") },
            supportingContent = { Text("导出到外部文件目录 AmberAgentMemory，包含 memories、archive、events 和 manifest。") },
            trailingContent = {
                TextButton(enabled = !running, onClick = onExport) {
                    Text("导出")
                }
            },
        )
        item(
            headlineContent = { Text("Frontmatter 导入") },
            supportingContent = { Text("从 AmberAgentMemory 目录读取 .mem.md 文件并写回 Room 主存储。") },
            trailingContent = {
                TextButton(enabled = !running, onClick = onImport) {
                    Text("导入")
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
private fun ReasoningLevel.memoryReasoningLabel(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
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
