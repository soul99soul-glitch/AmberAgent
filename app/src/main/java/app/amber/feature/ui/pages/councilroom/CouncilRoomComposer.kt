package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.CouncilParticipant
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.ui.components.ui.SubAgentAvatar
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.LocalChatTheme
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02

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
    val workspace = workspaceColors()
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val mentionTargets = remember { mutableStateListOf<String>() }
    var showMentionPopup by remember { mutableStateOf(false) }
    var mentionQuery by remember { mutableStateOf("") }

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

    Column(modifier = modifier.fillMaxWidth()) {
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val chatTheme = LocalChatTheme.current
            Surface(
                modifier = Modifier.width(52.dp),
                shape = CircleShape,
                color = chatTheme.surface,
                border = BorderStroke(1.dp, chatTheme.surfaceEdge),
                onClick = {
                    if (!textFieldValue.text.endsWith("@")) {
                        textFieldValue = TextFieldValue(
                            text = textFieldValue.text + "@",
                            selection = TextRange(textFieldValue.text.length + 1),
                        )
                    }
                },
            ) {
                Box(modifier = Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Text("@", color = chatTheme.inkSoft, style = MaterialTheme.typography.titleMedium)
                }
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(999.dp),
                color = chatTheme.surface,
                border = BorderStroke(1.dp, chatTheme.surfaceEdge),
            ) {
                TextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("发消息给议会… 用 @ 指定成员", color = workspace.faint) },
                    shape = RoundedCornerShape(999.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = workspace.ink,
                        unfocusedTextColor = workspace.ink,
                    ),
                    maxLines = 5,
                )
            }
            Surface(
                modifier = Modifier
                    .width(52.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "发送"
                    },
                shape = CircleShape,
                color = if (textFieldValue.text.isNotBlank()) chatTheme.sendBg else chatTheme.surface,
                contentColor = if (textFieldValue.text.isNotBlank()) chatTheme.sendArrow else workspace.faint,
                border = BorderStroke(1.dp, if (textFieldValue.text.isNotBlank()) chatTheme.sendBg else chatTheme.surfaceEdge),
                enabled = textFieldValue.text.isNotBlank(),
                onClick = {
                    val text = textFieldValue.text.trim()
                    if (text.isNotEmpty()) {
                        val resolved = mentionTargets.filter { pid ->
                            room.participantById(pid)?.name
                                ?.let { "@$it" in text }
                                ?: false
                        }
                        vm.sendUserMessage(text, resolved)
                        textFieldValue = TextFieldValue("")
                        mentionTargets.clear()
                    }
                },
            ) {
                Box(modifier = Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = HugeIcons.ArrowUp02,
                        contentDescription = "发送",
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
