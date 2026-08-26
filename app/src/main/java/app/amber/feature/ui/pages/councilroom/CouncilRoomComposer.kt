package app.amber.feature.ui.pages.councilroom

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.vision.ImageAttachmentStatusKind
import app.amber.core.ai.vision.ImageAttachmentValidator
import app.amber.core.files.FilesManager
import app.amber.feature.modelcouncil.CouncilParticipant
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.running
import app.amber.feature.ui.components.ui.SubAgentAvatar
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.LocalAmberTokens
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.X
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Image
import org.koin.compose.koinInject

/**
 * Bottom composer for the timeline tab. Lightweight @mention: typing a trailing
 * `@` (optionally followed by a query with no space) opens a popup listing the
 * room's participants (host + active guests); selecting one inserts `@name` and
 * records the participant id for routing.
 *
 * On send, the raw text + collected mention target ids are forwarded to
 * [CouncilRoomVM.sendUserMessage]; the manager stores them on the user message
 * and PR4 routes them into host actions.
 */
@Composable
fun CouncilRoomComposer(
    room: CouncilRoom,
    vm: CouncilRoomVM,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val mentionTargets = remember { mutableStateListOf<String>() }
    var showMentionPopup by remember { mutableStateOf(false) }
    var mentionQuery by remember { mutableStateOf("") }
    // Pending attachments (Image + Document parts) shown as chips above the input,
    // cleared on send. Picked files are first copied into app storage so their
    // local uris stay valid after the picker grant is gone.
    val attachments = remember { mutableStateListOf<UIMessagePart>() }
    var attachExpanded by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val localUris = filesManager.createChatFilesByContents(uris)
                val existingUrls = attachments.filterIsInstance<UIMessagePart.Image>().map { it.url }.toSet()
                val newImages = localUris
                    .map { UIMessagePart.Image(url = it.toString()) }
                    .filter { it.url !in existingUrls }
                attachments.addAll(newImages)
            }
        }
    }
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val existingDocUrls = attachments.filterIsInstance<UIMessagePart.Document>().map { it.url }.toSet()
                val docs = uris.mapNotNull { uri: Uri ->
                    val fileName = withContext(Dispatchers.IO) {
                        filesManager.getFileNameFromUri(uri) ?: "file"
                    }
                    val mime = withContext(Dispatchers.IO) {
                        filesManager.getFileMimeType(uri) ?: "application/octet-stream"
                    }
                    val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                    localUri?.let {
                        UIMessagePart.Document(url = it.toString(), fileName = fileName, mime = mime)
                    }
                }.filter { it.url !in existingDocUrls }
                attachments.addAll(docs)
            }
        }
    }

    // Detect a trailing "@<query>" within the *current word* (bounded by
    // whitespace) to open the popup. Scoping to the word avoids false triggers
    // on email-like text ("foo@bar") and reopening after a committed mention.
    LaunchedEffect(Unit) {
        snapshotFlow { textFieldValue }.collect { value ->
            val cursor = value.selection.end.coerceAtLeast(0)
            val before = value.text.take(cursor)
            // Start of the current word = char after the last whitespace before cursor.
            val wordStart = (before.lastIndexOfAny(charArrayOf(' ', '\n', '\t')) + 1)
                .coerceAtLeast(0)
            val word = before.substring(wordStart)
            // The token counts as a mention trigger only if @ is the FIRST char
            // of the word (so "foo@bar" never triggers; "@query" does).
            if (word.startsWith("@")) {
                showMentionPopup = true
                mentionQuery = word.removePrefix("@")
            } else {
                showMentionPopup = false
            }
        }
    }

    // Full-bleed tray on `surface`; the surface/bg colour difference separates it
    // from the timeline (no divider) — same as the main chat composer. imePadding +
    // navigationBarsPadding lift it above the keyboard and gesture bar.
    Surface(color = tokens.surface) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 6.dp)
                .padding(horizontal = 12.dp)
                .padding(top = 10.dp),
        ) {
            if (showMentionPopup) {
                MentionPopup(
                    room = room,
                    query = mentionQuery,
                    onSelect = { id, name ->
                        val text = textFieldValue.text
                        val cursor = textFieldValue.selection.end
                        val before = text.take(cursor)
                        val after = text.drop(cursor)
                        // Replace the entire current @word with "@name ".
                        val wordStart = (before.lastIndexOfAny(charArrayOf(' ', '\n', '\t')) + 1)
                            .coerceAtLeast(0)
                        if (wordStart < before.length && before.substring(wordStart).startsWith("@")) {
                            val replaced = before.take(wordStart) + "@$name " + after
                            val newCursor = wordStart + name.length + 2 // @ + name + space
                            textFieldValue = TextFieldValue(replaced, TextRange(newCursor))
                        }
                        if (id !in mentionTargets) mentionTargets.add(id)
                        showMentionPopup = false
                    },
                )
            }

            // Scope hint only when specific members are @mentioned.
            val mentionedNames = mentionTargets.mapNotNull { id ->
                room.participantById(id)?.name?.takeIf { it.isNotBlank() }
            }
            if (mentionedNames.isNotEmpty()) {
                Text(
                    text = "发送给 · ${mentionedNames.joinToString("、")}",
                    modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.accent,
                )
            }

            if (attachments.isNotEmpty()) {
                CouncilAttachmentStrip(
                    attachments = attachments,
                    onRemove = { attachments.remove(it) },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                // Attach — surface2 capsule that expands to the right into inline
                // image / file actions (+ rotates to ×), mirroring the main chat
                // composer instead of popping a dropdown menu.
                val attachInteraction = remember { MutableInteractionSource() }
                val addRotation by animateFloatAsState(
                    targetValue = if (attachExpanded) 45f else 0f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    label = "councilAttachToggleRotation",
                )
                Row(
                    modifier = Modifier
                        .height(46.dp)
                        .clip(CircleShape)
                        .background(tokens.surface2)
                        .animateContentSize(animationSpec = tween(220, easing = FastOutSlowInEasing)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .councilPressBounce(attachInteraction)
                            .clip(CircleShape)
                            .clickable(interactionSource = attachInteraction, indication = null) {
                                attachExpanded = !attachExpanded
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.Plus,
                            contentDescription = "添加附件",
                            tint = if (attachExpanded) tokens.accent else tokens.ink3,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { rotationZ = addRotation },
                        )
                    }
                    AnimatedVisibility(
                        visible = attachExpanded,
                        enter = fadeIn(animationSpec = tween(160)) + scaleIn(
                            initialScale = 0.92f,
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                        ),
                        exit = fadeOut(animationSpec = tween(120)) + scaleOut(
                            targetScale = 0.94f,
                            animationSpec = tween(160, easing = FastOutSlowInEasing),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        attachExpanded = false
                                        imagePickerLauncher.launch("image/*")
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Lucide.Image,
                                    contentDescription = "图片",
                                    tint = tokens.ink3,
                                    modifier = Modifier.size(23.dp),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        attachExpanded = false
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Lucide.FileText,
                                    contentDescription = "文件",
                                    tint = tokens.ink3,
                                    modifier = Modifier.size(23.dp),
                                )
                            }
                        }
                    }
                }

                // Input pill — surface2, 26dp radius, 1dp hairline; BasicTextField with
                // 9dp vertical padding keeps the single-line height ≈46dp, level with
                // the flanking circles (mirrors the main chat composer exactly).
                val pillShape = RoundedCornerShape(26.dp)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                        .clip(pillShape)
                        .background(tokens.surface2)
                        .border(BorderStroke(1.dp, tokens.line), pillShape)
                        .padding(start = 18.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = { textFieldValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.ink),
                            cursorBrush = SolidColor(tokens.accent),
                            maxLines = 5,
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.padding(vertical = 9.dp)) {
                                    if (textFieldValue.text.isEmpty()) {
                                        Text(
                                            text = "输入消息",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = tokens.ink3,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }
                }

                // Send — flat circle (46dp); accent fill when there's a draft.
                // While the council is actively producing output AND the draft is
                // empty, this flips to a red STOP button that cancels the run
                // (vm.close keeps the partial discussion). A non-empty draft always
                // sends, so the user can still drop a mid-run interjection.
                val sendInteraction = remember { MutableInteractionSource() }
                val armed = textFieldValue.text.isNotBlank() || attachments.isNotEmpty()
                val running = room.status.running && room.messages.any { it.role != "user" }
                val showStop = running && !armed
                // Mirror the main chat composer's send/stop button exactly: an
                // accent-filled circle with a white glyph — ArrowUp to send, Cancel01
                // (×) to stop; neutral surface only when idle with an empty draft.
                val sendFill by animateColorAsState(
                    if (!armed && !showStop) tokens.surface2 else tokens.accent,
                    label = "council-send-fill",
                )
                val sendIconTint by animateColorAsState(
                    if (!armed && !showStop) tokens.ink3 else Color.White,
                    label = "council-send-tint",
                )
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .councilPressBounce(sendInteraction)
                        .clip(CircleShape)
                        .background(sendFill)
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = null,
                            enabled = showStop || armed,
                        ) {
                            if (showStop) {
                                vm.close()
                                return@clickable
                            }
                            val text = textFieldValue.text.trim()
                            if (text.isNotEmpty() || attachments.isNotEmpty()) {
                                val resolved = mentionTargets.filter { pid ->
                                    room.participantById(pid)?.name?.let { "@$it" in text } ?: false
                                }
                                vm.sendUserMessage(text, resolved, attachments.toList())
                                textFieldValue = TextFieldValue("")
                                mentionTargets.clear()
                                attachments.clear()
                            }
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = if (showStop) "停止" else "发送"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (showStop) Lucide.X else Lucide.ArrowUp,
                        contentDescription = if (showStop) "停止" else "发送",
                        tint = sendIconTint,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MentionPopup(
    room: CouncilRoom,
    query: String,
    onSelect: (id: String, name: String) -> Unit,
) {
    val workspace = workspaceColors()
    val candidates: List<CouncilParticipant> = remember(room.participants, query) {
        room.participants
            .filter { it.status != CouncilParticipantStatus.DISMISSED }
            .filter {
                query.isBlank() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.id.contains(query, ignoreCase = true)
            }
            .take(8)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = workspace.paper,
        border = workspaceBorder(),
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            candidates.forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(p.id, p.name) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SubAgentAvatar(id = p.id, name = p.name, avatarSize = 24.dp)
                    Column {
                        Text(
                            text = "@${p.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = workspace.ink,
                        )
                        if (p.role.isNotBlank()) {
                            Text(
                                text = p.role,
                                style = MaterialTheme.typography.labelSmall,
                                color = workspace.faint,
                            )
                        }
                    }
                }
            }
            if (candidates.isEmpty()) {
                Text(
                    text = "无匹配成员",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.faint,
                )
            }
        }
    }
}

/** Horizontal strip of pending attachment chips above the input, each removable.
 *  Images show validation status (checking/ready/blocked) like the main chat composer. */
@Composable
private fun CouncilAttachmentStrip(
    attachments: List<UIMessagePart>,
    onRemove: (UIMessagePart) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { part ->
            when (part) {
                is UIMessagePart.Image -> {
                    val status by produceState(
                        ImageAttachmentValidator.checking(),
                        part.url,
                        settings.chatModelId,
                        settings.ocrModelId,
                        settings.providers,
                    ) {
                        value = withContext(Dispatchers.IO) {
                            ImageAttachmentValidator.inspectImage(part, settings)
                        }
                    }
                    Box {
                        AsyncImage(
                            model = part.url,
                            contentDescription = "图片附件",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.surface2),
                        )
                        // 验证状态指示器：CHECKING 显示加载圈，其他显示颜色点
                        when (status.kind) {
                            ImageAttachmentStatusKind.CHECKING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp),
                                    color = tokens.accent,
                                    strokeWidth = 2.dp,
                                )
                            }
                            else -> {
                                val dotColor = when (status.kind) {
                                    ImageAttachmentStatusKind.READY -> Color(0xFF2EAD5B)
                                    ImageAttachmentStatusKind.FALLBACK -> Color(0xFFFFB020)
                                    ImageAttachmentStatusKind.BLOCKED -> MaterialTheme.colorScheme.error
                                    else -> workspaceColors().muted
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                        .clickable(
                                            enabled = status.blocksSend,
                                            onClick = {
                                                toaster.show(status.message, type = ToastType.Error)
                                            },
                                        )
                                )
                            }
                        }
                        AttachmentRemoveBadge(
                            onClick = { onRemove(part) },
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                }

                is UIMessagePart.Document -> Box {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .widthIn(max = 180.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.surface2)
                            .padding(start = 10.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Lucide.FileText,
                            contentDescription = null,
                            tint = tokens.ink3,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = part.fileName,
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.ink,
                            maxLines = 1,
                        )
                    }
                    AttachmentRemoveBadge(
                        onClick = { onRemove(part) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun AttachmentRemoveBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalAmberTokens.current
    Box(
        modifier = modifier
            .padding(2.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(tokens.ink.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Lucide.X,
            contentDescription = "移除附件",
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}
