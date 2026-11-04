package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.prompts.AgentSoulSnapshot
import app.amber.feature.prompts.AgentSoulState
import app.amber.feature.prompts.AgentSoulStore
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.compose.koinInject

/**
 * Compact overview card for Settings (`Agent 与记忆`) that displays the current「形象人设」
 * and opens [AgentSoulSheet].
 */
@Composable
fun AgentSoulOverviewCard(
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
    agentSoulStore: AgentSoulStore = koinInject(),
) {
    val soulState by agentSoulStore.state.collectAsStateWithLifecycle()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val summaryText = when {
        soulState.tagline.isNotBlank() -> soulState.tagline
        soulState.soulMarkdown.isNotBlank() ->
            soulState.soulMarkdown.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        else -> "可在对话中直接告诉 AI 更换称呼、性格或说话方式，并自动跨端同步。"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("agent-soul-overview-card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = tokens.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, tokens.line),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSheet)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonaMonogramBadge(name = soulState.displayName, sizeDp = 38)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "形象人设 · ${soulState.displayName}",
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PersonaSourcePill(state = soulState)
                }
                Text(
                    text = summaryText,
                    style = type.secondary,
                    color = tokens.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpenSheet) {
                Text("管理", style = type.secondary.copy(color = tokens.accent, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/**
 * Modal bottom sheet for viewing, editing, rolling back snapshots, or resetting「形象人设」.
 * Follows Arquiel Design warm paper/ink tokens and strictly uses the「形象人设」naming convention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSoulSheet(
    onDismiss: () -> Unit,
    agentSoulStore: AgentSoulStore = koinInject(),
    macGatewayRepository: MacGatewayRepository = koinInject(),
) {
    val soulState by agentSoulStore.state.collectAsStateWithLifecycle()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var editing by remember { mutableStateOf(false) }
    var draftName by remember(soulState.displayName, editing) { mutableStateOf(soulState.displayName) }
    var draftTagline by remember(soulState.tagline, editing) { mutableStateOf(soulState.tagline) }
    var draftSoul by remember(soulState.soulMarkdown, editing) { mutableStateOf(soulState.soulMarkdown) }

    LaunchedEffect(Unit) {
        macGatewayRepository.syncSoulWithCloud()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.bg,
        contentColor = tokens.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Sheet Title Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "形象人设",
                        style = type.screenTitle,
                        color = tokens.ink,
                    )
                    Text(
                        text = "当前 AI 身份称呼、签名与性格设定",
                        style = type.secondary,
                        color = tokens.ink3,
                    )
                }
                if (!soulState.isDefault) {
                    TextButton(
                        onClick = {
                            editing = false
                            agentSoulStore.resetToDefault(source = AgentSoulStore.SOURCE_ANDROID_UI)
                        },
                        modifier = Modifier.testTag("agent-soul-reset-default"),
                    ) {
                        Text(
                            text = "恢复默认 Amber",
                            style = type.secondary.copy(color = tokens.ink2, fontWeight = FontWeight.Medium),
                        )
                    }
                }
            }

            // Hero Persona Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = tokens.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, tokens.line),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PersonaMonogramBadge(name = soulState.displayName, sizeDp = 46)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = soulState.displayName,
                                    style = type.sessionTitle,
                                    color = tokens.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                PersonaSourcePill(state = soulState)
                            }
                            Text(
                                text = soulState.tagline.ifBlank {
                                    if (soulState.isDefault) "默认形象人设 · 自然、克制、可靠的随身搭档" else "未设置一句话签名"
                                },
                                style = type.secondary,
                                color = tokens.ink2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            onClick = {
                                if (!editing) {
                                    draftName = soulState.displayName
                                    draftTagline = soulState.tagline
                                    draftSoul = soulState.soulMarkdown
                                }
                                editing = !editing
                            },
                            modifier = Modifier.testTag("agent-soul-toggle-edit"),
                        ) {
                            Text(
                                text = if (editing) "收起" else "编辑",
                                style = type.secondary.copy(
                                    color = tokens.accent,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }

                    if (!editing && soulState.soulMarkdown.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.surface2)
                                .padding(12.dp),
                        ) {
                            Text(
                                text = soulState.soulMarkdown,
                                style = type.body,
                                color = tokens.ink,
                            )
                        }
                    }

                    if (editing) {
                        val fieldColors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tokens.accent,
                            unfocusedBorderColor = tokens.line2,
                            focusedLabelColor = tokens.accent,
                            unfocusedLabelColor = tokens.ink3,
                            focusedTextColor = tokens.ink,
                            unfocusedTextColor = tokens.ink,
                        )
                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { draftName = it.take(AgentSoulStore.MAX_NAME_LENGTH) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("agent-soul-name-input"),
                            singleLine = true,
                            label = { Text("称呼（默认 Amber）", style = type.secondary) },
                            colors = fieldColors,
                        )
                        OutlinedTextField(
                            value = draftTagline,
                            onValueChange = { draftTagline = it.take(AgentSoulStore.MAX_TAGLINE_LENGTH) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("agent-soul-tagline-input"),
                            singleLine = true,
                            label = { Text("一句话签名", style = type.secondary) },
                            colors = fieldColors,
                        )
                        OutlinedTextField(
                            value = draftSoul,
                            onValueChange = { draftSoul = it.take(AgentSoulStore.MAX_SOUL_LENGTH) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 140.dp, max = 280.dp)
                                .testTag("agent-soul-markdown-input"),
                            minLines = 5,
                            maxLines = 12,
                            label = { Text("性格、语气与相处方式（Soul Markdown）", style = type.secondary) },
                            colors = fieldColors,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { editing = false }) {
                                Text("取消", style = type.secondary.copy(color = tokens.ink3))
                            }
                            TextButton(
                                onClick = {
                                    agentSoulStore.update(
                                        displayName = draftName,
                                        tagline = draftTagline,
                                        soulMarkdown = draftSoul,
                                        resetToDefault = false,
                                        source = AgentSoulStore.SOURCE_ANDROID_UI,
                                    )
                                    editing = false
                                },
                                modifier = Modifier.testTag("agent-soul-save-button"),
                            ) {
                                Text(
                                    text = "保存形象人设",
                                    style = type.secondary.copy(
                                        color = tokens.accent,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            // Conversational Self-Shaping Guidance Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.surface2)
                    .border(1.dp, tokens.line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "提示：你也可以直接在聊天中对 AI 说「以后叫你小琥珀，说话简洁毒舌一点」，AI 会直接调用 agent_soul_update 工具更新形象人设并同步到云端。",
                    style = type.secondary,
                    color = tokens.ink2,
                )
            }

            // History Snapshots Section
            if (soulState.history.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "历史人设快照（最近 ${soulState.history.size} 条）",
                        style = type.secondary.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.ink2,
                    )
                    soulState.history.forEach { snapshot ->
                        SnapshotHistoryRow(
                            snapshot = snapshot,
                            onRestore = {
                                editing = false
                                agentSoulStore.restoreSnapshot(
                                    snapshotId = snapshot.id,
                                    source = AgentSoulStore.SOURCE_ANDROID_UI,
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SnapshotHistoryRow(
    snapshot: AgentSoulSnapshot,
    onRestore: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val preview = snapshot.tagline.ifBlank {
        snapshot.soulMarkdown.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }.ifBlank { "无签名设定" }
    val timeLabel = remember(snapshot.updatedAtMs) {
        if (snapshot.updatedAtMs <= 0L) ""
        else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(snapshot.updatedAtMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = tokens.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, tokens.line),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonaMonogramBadge(name = snapshot.displayName, sizeDp = 32)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = snapshot.displayName,
                        style = type.body.copy(fontWeight = FontWeight.Medium),
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (timeLabel.isNotEmpty()) {
                        Text(
                            text = timeLabel,
                            style = type.meta.copy(fontSize = 10.5.sp),
                            color = tokens.ink3,
                        )
                    }
                }
                Text(
                    text = preview,
                    style = type.secondary,
                    color = tokens.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onRestore,
                modifier = Modifier.testTag("agent-soul-restore-${snapshot.id}"),
            ) {
                Text(
                    text = "切回",
                    style = type.secondary.copy(color = tokens.accent, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun PersonaMonogramBadge(
    name: String,
    sizeDp: Int,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(tokens.accent.copy(alpha = 0.14f))
            .border(1.dp, tokens.accent.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = type.body.copy(
                fontWeight = FontWeight.Bold,
                fontSize = (sizeDp * 0.42f).sp,
            ),
            color = tokens.accent,
        )
    }
}

@Composable
private fun PersonaSourcePill(state: AgentSoulState) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val label = when {
        state.isDefault -> "默认"
        state.source.contains("chat", ignoreCase = true) ||
            state.source.contains("conversation", ignoreCase = true) -> "对话塑造"
        state.source.startsWith("ios") || state.source.startsWith("mac") || state.source.startsWith("web") -> "云端同步"
        else -> "自定义"
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tokens.surface2)
            .border(1.dp, tokens.line, CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = type.tinyTag,
            color = tokens.ink2,
            maxLines = 1,
        )
    }
}
