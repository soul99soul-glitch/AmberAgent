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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Sparkles
import app.amber.agent.R
import app.amber.core.model.AssistantMemory
import app.amber.core.model.MemoryKind
import app.amber.feature.ui.components.ai.AgentSoulOverviewCard
import app.amber.feature.ui.components.ai.AgentSoulSheet
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun MemoryOverviewSubpage(
    soulMarkdown: String,
    onSaveSoul: (String) -> Unit,
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
    var showPersonaSheet by remember { mutableStateOf(false) }
    AgentSoulOverviewCard(onOpenSheet = { showPersonaSheet = true })
    Spacer(Modifier.height(10.dp))
    AgentSoulCard(value = soulMarkdown, onSave = onSaveSoul)
    if (showPersonaSheet) {
        AgentSoulSheet(onDismiss = { showPersonaSheet = false })
    }
    MemoryOverviewEntries(
        pendingCandidateCount = pendingCandidateCount,
        coreCount = coreCount,
        shortCount = shortCount,
        longCount = longCount,
        hasPendingDreamPlan = hasPendingDreamPlan,
        coreMemories = coreMemories,
        shortTermMemories = shortTermMemories,
        longTermMemories = longTermMemories,
        onOpen = onOpen,
    )
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
