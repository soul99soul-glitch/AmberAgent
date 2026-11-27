package app.amber.feature.ui.components.ai

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.prompts.AgentPersonaAvatarStore
import app.amber.feature.prompts.AgentSoulSnapshot
import app.amber.feature.prompts.AgentSoulState
import app.amber.feature.prompts.AgentSoulStore
import app.amber.feature.ui.components.ds.AmberFeedback
import app.amber.feature.ui.pages.personalagent.AmberPersonaMedallion
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
            AmberPersonaMedallion(diameter = 38.dp)
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
    val avatarStore: AgentPersonaAvatarStore = koinInject()
    val avatar by avatarStore.avatar.collectAsStateWithLifecycle()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var editing by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable { mutableStateOf("") }
    var draftTagline by rememberSaveable { mutableStateOf("") }
    var draftSoul by rememberSaveable { mutableStateOf("") }
    var textEdited by rememberSaveable { mutableStateOf(false) }
    var draftAvatar by remember { mutableStateOf<ByteArray?>(null) }
    var avatarEdited by remember { mutableStateOf(false) }
    var pickingPhoto by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }
    var photoMessage by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shownAvatar = if (editing && avatarEdited) draftAvatar else avatar

    fun beginEditing() {
        if (editing) return
        val current = agentSoulStore.state.value
        draftName = current.displayName
        draftTagline = current.tagline
        draftSoul = current.soulMarkdown
        textEdited = false
        editing = true
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickingPhoto = false
        pendingPhoto = uri
    }

    LaunchedEffect(pendingPhoto) {
        val uri = pendingPhoto ?: return@LaunchedEffect
        photoMessage = 0
        try {
            val image = avatarStore.importPhoto(uri)
            if (pendingPhoto == uri) {
                beginEditing()
                draftAvatar = image
                avatarEdited = true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (pendingPhoto == uri) photoMessage = R.string.agent_persona_avatar_import_error
        }
        if (pendingPhoto == uri) pendingPhoto = null
    }

    LaunchedEffect(Unit) {
        macGatewayRepository.syncSoulWithCloud()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = !saving,
        containerColor = tokens.bg,
        contentColor = tokens.ink,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = !editing,
            shouldDismissOnClickOutside = !saving,
        ),
    ) {
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val changeAvatarLabel = stringResource(R.string.avatar_change_avatar)
        fun closeEditor() {
            if (!editing) return
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            pendingPhoto = null
            photoMessage = 0
            draftAvatar = null
            avatarEdited = false
            editing = false
        }

        BackHandler(enabled = editing) { if (!saving) closeEditor() }
        val editorTransition = updateTransition(editing, label = "Agent soul editor")
        val resizeSpec = tween<IntSize>(240, easing = AmberFeedback.EaseOut)
        val fadeSpec = tween<Float>(AmberFeedback.OptionMs, easing = AmberFeedback.EaseOut)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            // Sheet Title Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
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
                            closeEditor()
                            agentSoulStore.resetToDefault(source = AgentSoulStore.SOURCE_ANDROID_UI)
                        },
                        enabled = !saving,
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
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(
                                    enabled = !saving && !pickingPhoto && pendingPhoto == null,
                                    role = Role.Button,
                                ) {
                                    if (saving || pickingPhoto || pendingPhoto != null) return@clickable
                                    photoMessage = 0
                                    pickingPhoto = true
                                    try {
                                        photoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                        pickingPhoto = false
                                        photoMessage = R.string.agent_persona_avatar_import_error
                                    }
                                }
                                .semantics { contentDescription = changeAvatarLabel }
                                .testTag("agent-soul-change-avatar"),
                            contentAlignment = Alignment.Center,
                        ) {
                            AmberPersonaMedallion(diameter = 46.dp, avatar = shownAvatar)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(20.dp)
                                    .border(1.5.dp, tokens.surface, CircleShape)
                                    .background(tokens.accent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Lucide.Image,
                                    contentDescription = null,
                                    tint = tokens.accentInk,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
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
                                if (editing) closeEditor() else beginEditing()
                            },
                            enabled = !saving,
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

                    val statusMessage = if (pendingPhoto != null) {
                        R.string.agent_persona_avatar_importing
                    } else {
                        photoMessage
                    }
                    if (statusMessage != 0) {
                        Text(
                            text = stringResource(statusMessage),
                            style = type.secondary,
                            color = if (photoMessage != 0) MaterialTheme.colorScheme.error else tokens.ink3,
                            modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }

                    editorTransition.AnimatedContent(
                        modifier = Modifier.fillMaxWidth(),
                        transitionSpec = {
                            (fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec))
                                .using(SizeTransform { _, _ -> resizeSpec })
                        },
                    ) { showEditor ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (showEditor == editing) Modifier
                                    else Modifier.semantics { hideFromAccessibility() },
                                ),
                        ) {
                            if (showEditor) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (shownAvatar != null) {
                                        TextButton(
                                            onClick = {
                                                draftAvatar = null
                                                avatarEdited = true
                                                photoMessage = 0
                                            },
                                            enabled = editing && !saving && !pickingPhoto && pendingPhoto == null,
                                            modifier = Modifier.testTag("agent-soul-reset-avatar"),
                                        ) {
                                            Text(stringResource(R.string.agent_persona_avatar_restore))
                                        }
                                    }
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
                                        enabled = editing && !saving,
                                        onValueChange = {
                                            draftName = it.take(AgentSoulStore.MAX_NAME_LENGTH)
                                            textEdited = true
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("agent-soul-name-input"),
                                        singleLine = true,
                                        label = { Text("称呼（默认 Amber）", style = type.secondary) },
                                        colors = fieldColors,
                                    )
                                    OutlinedTextField(
                                        value = draftTagline,
                                        enabled = editing && !saving,
                                        onValueChange = {
                                            draftTagline = it.take(AgentSoulStore.MAX_TAGLINE_LENGTH)
                                            textEdited = true
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("agent-soul-tagline-input"),
                                        singleLine = true,
                                        label = { Text("一句话签名", style = type.secondary) },
                                        colors = fieldColors,
                                    )
                                    OutlinedTextField(
                                        value = draftSoul,
                                        enabled = editing && !saving,
                                        onValueChange = {
                                            draftSoul = it.take(AgentSoulStore.MAX_SOUL_LENGTH)
                                            textEdited = true
                                        },
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
                                        TextButton(onClick = ::closeEditor, enabled = editing && !saving) {
                                            Text("取消", style = type.secondary.copy(color = tokens.ink3))
                                        }
                                        TextButton(
                                            onClick = {
                                                if (!editing || saving || pendingPhoto != null) return@TextButton
                                                saving = true
                                                scope.launch {
                                                    try {
                                                        if (avatarEdited) avatarStore.save(draftAvatar)
                                                    } catch (error: CancellationException) {
                                                        throw error
                                                    } catch (_: Exception) {
                                                        photoMessage = R.string.agent_persona_avatar_save_error
                                                        return@launch
                                                    } finally {
                                                        saving = false
                                                    }
                                                    if (textEdited) {
                                                        agentSoulStore.update(
                                                            displayName = draftName,
                                                            tagline = draftTagline,
                                                            soulMarkdown = draftSoul,
                                                            source = AgentSoulStore.SOURCE_ANDROID_UI,
                                                        )
                                                    }
                                                    closeEditor()
                                                }
                                            },
                                            enabled = editing && !saving && pendingPhoto == null,
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
                            } else if (soulState.soulMarkdown.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp)
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
                        }
                    }
                }
            }

            editorTransition.AnimatedVisibility(
                visible = { !it },
                enter = expandVertically(resizeSpec, expandFrom = Alignment.Top) + fadeIn(fadeSpec),
                exit = shrinkVertically(resizeSpec, shrinkTowards = Alignment.Top) + fadeOut(fadeSpec),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .then(
                            if (editing) Modifier.semantics { hideFromAccessibility() }
                            else Modifier,
                        ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
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
                                    enabled = !editing,
                                    onRestore = {
                                        if (!editing) {
                                            agentSoulStore.restoreSnapshot(
                                                snapshotId = snapshot.id,
                                                source = AgentSoulStore.SOURCE_ANDROID_UI,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
        }
    }
}

@Composable
private fun SnapshotHistoryRow(
    snapshot: AgentSoulSnapshot,
    enabled: Boolean,
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
                enabled = enabled,
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
