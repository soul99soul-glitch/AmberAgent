package app.amber.feature.ui.pages.councilroom

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.COUNCIL_ROOM_HOST_ID
import app.amber.feature.modelcouncil.COUNCIL_ROOM_USER_ID
import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.feature.modelcouncil.CouncilMessageStatus
import app.amber.feature.modelcouncil.CouncilPhaseMarker
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.running
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.SubAgentAvatar
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.LocalChatTheme

/**
 * One entry in the merged timeline (a message or a phase marker), with a stable
 * key for [LazyColumn].
 */
private sealed interface TimelineEntry {
    val key: String
    val sortMs: Long

    data class Message(val msg: CouncilMessage) : TimelineEntry {
        override val key get() = msg.id
        override val sortMs get() = msg.createdAtMs
    }

    data class Phase(val marker: CouncilPhaseMarker) : TimelineEntry {
        override val key get() = marker.id
        override val sortMs get() = marker.createdAtMs
    }
}

/**
 * 群聊时间线 — merges messages + phase markers by timestamp.
 *
 * Alignment mirrors the main chat: user right-aligned, host/guest left-aligned.
 * Host bubbles get an amber avatar; guests get a coloured pixel avatar. Guest-
 * to-guest reference graph (reply / continues / invited-by) renders as small
 * footnotes under the bubble.
 *
 * Streaming auto-follow: while any message is STREAMING and the viewport sits
 * at the bottom, we keep it pinned there. A user scrolling up naturally breaks
 * the follow (the "at bottom" check fails); scrolling back down re-engages it.
 * This is the lightweight variant used by the legacy Council sheet.
 */
@Composable
fun CouncilTimelineTab(
    room: CouncilRoom,
    vm: CouncilRoomVM?,
    modifier: Modifier = Modifier,
) {
    val entries = remember(room.messages, room.phaseMarkers) {
        buildList {
            room.messages.forEach { add(TimelineEntry.Message(it)) }
            room.phaseMarkers.forEach { add(TimelineEntry.Phase(it)) }
        }.sortedBy { it.sortMs }
    }
    val isStreaming = remember(room.messages) {
        room.messages.any { it.status.running }
    }
    // Length of the currently-streaming message's text. This changes on every
    // chunk (the manager mutates an existing message in place rather than
    // appending), so it is the key that makes auto-scroll re-fire mid-stream.
    val streamingTail = room.messages.lastOrNull { it.status.running }?.text?.length ?: 0

    val listState = rememberLazyListState()
    var followBottom by remember { mutableStateOf(true) }

    // Re-engage follow when the viewport is parked near the bottom (reactive to
    // both new items landing and the user scrolling back down). Breaking out of
    // follow happens implicitly: an upward scroll drops lastVisible below the
    // threshold, so this collector stops flipping followBottom back on.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && lastVisible >= total - 2
        }.collect { atBottom ->
            if (atBottom) followBottom = true
        }
    }
    // A user-initiated scroll that moves away from the bottom cancels follow.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val lastIndex = (info.totalItemsCount - 1).coerceAtLeast(0)
            Triple(listState.isScrollInProgress, lastVisible, lastIndex)
        }.collect { (scrolling, lastVisible, lastIndex) ->
            if (scrolling) {
                followBottom = lastVisible >= lastIndex - 1
            }
        }
    }
    // Pin to the newest entry while following. Keyed on size, streaming state,
    // AND streamingTail so it re-fires on every in-place chunk growth.
    LaunchedEffect(entries.size, isStreaming, streamingTail, followBottom) {
        if (followBottom && entries.isNotEmpty()) {
            listState.animateScrollToItem((entries.size - 1).coerceAtLeast(0))
        }
    }

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    CouncilTimelineEmpty(room)
                }
            }
            items(items = entries, key = { it.key }) { entry ->
                when (entry) {
                    is TimelineEntry.Phase -> PhaseDivider(entry.marker)
                    is TimelineEntry.Message -> TimelineMessageRow(msg = entry.msg, room = room)
                }
            }
        }
        // Composer only in active (non-terminal) mode.
        if (vm != null) {
            CouncilRoomComposer(room = room, vm = vm)
        }
    }
}

@Composable
private fun CouncilTimelineEmpty(room: CouncilRoom) {
    val chatTheme = LocalChatTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "amber council",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = chatTheme.ink,
        )
        Text(
            text = room.objective.ifBlank { "把问题交给多模型一起讨论。" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = chatTheme.ink,
        )
        Text(
            text = "发送第一条消息，或邀请成员开始发言。",
            style = MaterialTheme.typography.bodyMedium,
            color = chatTheme.inkFaint,
        )
    }
}

@Composable
private fun PhaseDivider(marker: CouncilPhaseMarker) {
    val chatTheme = LocalChatTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(chatTheme.hair),
        )
        Text(
            text = marker.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = chatTheme.inkFaint,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(chatTheme.hair),
        )
    }
}

@Composable
private fun TimelineMessageRow(msg: CouncilMessage, room: CouncilRoom) {
    val isUser = msg.authorId == COUNCIL_ROOM_USER_ID
    val isHost = msg.authorId == COUNCIL_ROOM_HOST_ID
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()

    if (isUser) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            MessageBubble(
                msg = msg,
                container = chatTheme.userBubble,
                content = chatTheme.userBubbleInk,
                borderColor = chatTheme.userBubbleEdge,
            )
            ReferenceFootnotes(msg, room, alignEnd = true)
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            SubAgentAvatar(id = msg.authorId, name = msg.authorName, avatarSize = 34.dp)
            Column(modifier = Modifier.widthIn(max = 360.dp)) {
                AuthorLabel(msg, isHost)
                MessageBubble(
                    msg = msg,
                    container = if (isHost) chatTheme.accentSoft else chatTheme.surface,
                    content = if (isHost) chatTheme.accentDeep else chatTheme.ink,
                    borderColor = if (isHost) chatTheme.accentTint else chatTheme.surfaceEdge,
                )
                ReferenceFootnotes(msg, room, alignEnd = false)
            }
        }
    }
}

@Composable
private fun AuthorLabel(msg: CouncilMessage, isHost: Boolean) {
    val workspace = workspaceColors()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = msg.authorName.ifBlank { msg.authorId },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (isHost) workspace.amber else workspace.ink,
        )
        if (msg.role.isNotBlank() && msg.role != msg.authorName) {
            Text(
                text = "· ${msg.role}",
                style = MaterialTheme.typography.labelSmall,
                color = workspace.faint,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    msg: CouncilMessage,
    container: Color,
    content: Color,
    borderColor: Color,
) {
    val workspace = workspaceColors()
    val streaming = msg.status == CouncilMessageStatus.STREAMING
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (msg.text.isNotBlank()) {
                MarkdownBlock(
                    content = msg.text,
                    streaming = streaming,
                    deferStreamingParse = streaming,
                    style = TextStyle(color = content),
                )
            } else if (streaming) {
                StreamingPlaceholder()
            }
            if (msg.error.isNotBlank()) {
                Text(
                    text = msg.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.red,
                )
            }
            if (msg.warnings.isNotEmpty()) {
                Text(
                    text = msg.warnings.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.amber,
                )
            }
        }
    }
}

@Composable
private fun StreamingPlaceholder() {
    val transition = rememberInfiniteTransition(label = "council-streaming")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "council-streaming-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(workspaceColors().muted, RoundedCornerShape(50)),
    )
}

/**
 * Reference-graph footnotes (reply / continues / invited-by). Only rendered for
 * guest messages with a non-null reference; resolved against the room's message
 * list to show the target author's name.
 */
@Composable
private fun ReferenceFootnotes(msg: CouncilMessage, room: CouncilRoom, alignEnd: Boolean) {
    val workspace = workspaceColors()
    val notes = buildList {
        msg.replyToMessageId?.let { id ->
            val name = room.messages.firstOrNull { it.id == id }?.authorName ?: "已删除"
            add("↳ 回复 $name")
        }
        msg.continuesFromMessageId?.let { id ->
            val name = room.messages.firstOrNull { it.id == id }?.authorName ?: "已删除"
            add("↳ 延续 $name 的论点")
        }
        if (msg.invitedBy == COUNCIL_ROOM_HOST_ID && msg.authorId != COUNCIL_ROOM_HOST_ID) {
            add("由 Host 邀请")
        }
    }
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .padding(start = 4.dp, top = 2.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        notes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = workspace.faint,
            )
        }
    }
}
