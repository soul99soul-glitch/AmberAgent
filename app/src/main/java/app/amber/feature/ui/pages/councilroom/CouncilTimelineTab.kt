package app.amber.feature.ui.pages.councilroom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.COUNCIL_ROOM_HOST_ID
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.modelcouncil.COUNCIL_ROOM_USER_ID
import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.feature.modelcouncil.CouncilMessageStatus
import app.amber.feature.modelcouncil.CouncilParticipant
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilPhaseMarker
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.modelcouncil.running
import app.amber.feature.ui.components.richtext.MarkdownBlock
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
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
    val scope = rememberCoroutineScope()
    var followBottom by remember { mutableStateOf(true) }

    // Follow engages only at the TRUE bottom (canScrollForward == false). A user
    // scroll that leaves the bottom suspends it immediately — even a small upward
    // swipe — so the timeline never drags the reader back down while they look
    // around; it re-engages only when they return to the very bottom. Streaming
    // growth re-pins programmatically (isScrollInProgress stays false), so that
    // path never trips the "user scrolled away" branch.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                when {
                    scrolling && canScrollForward -> followBottom = false
                    !canScrollForward -> followBottom = true
                }
            }
    }
    // Anchor the newest content to the bottom while following. A one-shot pin
    // handles a freshly-landed message; during an in-flight stream we re-pin EVERY
    // FRAME so the column simply grows upward — a per-chunk scroll lagged behind
    // fast token bursts and let new lines spill below the fold. The frame loop runs
    // only while streaming AND following, so it stops the instant the user scrolls
    // away (followBottom flips) or the turn finishes (isStreaming flips).
    LaunchedEffect(entries.size, isStreaming, followBottom) {
        if (!followBottom || entries.isEmpty()) return@LaunchedEffect
        val lastIndex = (entries.size - 1).coerceAtLeast(0)
        listState.scrollToItem(lastIndex, scrollOffset = 100_000)
        if (isStreaming) {
            while (true) {
                withFrameNanos { }
                if (listState.canScrollForward) {
                    listState.scrollToItem(lastIndex, scrollOffset = 100_000)
                }
            }
        }
    }

    // The first user message is the room's "topic"; it gets the 议题 · 发起人 label.
    val topicMessageId = remember(room.messages) {
        room.messages.firstOrNull { it.authorId == COUNCIL_ROOM_USER_ID }?.id
    }
    // Who is mid-turn — drives the live "正在发言" strip above the composer.
    val speaking = remember(room.participants, streamingTail) {
        room.participants.firstOrNull { it.status == CouncilParticipantStatus.SPEAKING }
    }

    // Timeline keys whose entrance "pop" has already played. Pre-seeded with the
    // entries present at first composition, so opening a room with history does
    // NOT replay the pop for every past message — only turns that arrive after
    // open pop, and each only once (scrolling a popped item back into view, which
    // re-creates its composition, finds the key here and skips re-animating).
    val poppedKeys = remember { entries.mapTo(mutableSetOf<String>()) { it.key } }

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            // Extra bottom headroom so the streaming tail (and a freshly appended
            // line) stays comfortably above the composer instead of hugging the edge.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    CouncilTimelineEmpty(room)
                }
            }
            items(items = entries, key = { it.key }) { entry ->
                // animateItem gives every new turn the design's "rise" entrance
                // (fade + settle) and smoothly reflows neighbours on insertion.
                // On top of that, a user message that arrives after the room is
                // open gets a one-shot scale "pop" from its trailing edge — the
                // same send feedback as the main chat. firstAppearance is false
                // for history (pre-seeded) and for items scrolled back into view,
                // so neither replays the pop.
                val isUserMsg = entry is TimelineEntry.Message &&
                    entry.msg.authorId == COUNCIL_ROOM_USER_ID
                val firstAppearance = remember(entry.key) { entry.key !in poppedKeys }
                val popIn = isUserMsg && firstAppearance
                val popScale = remember(entry.key) { Animatable(if (popIn) 0.8f else 1f) }
                LaunchedEffect(entry.key) {
                    if (popIn) {
                        poppedKeys += entry.key
                        popScale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
                // The actively-streaming message must NOT use animateItem: its
                // placement animation chases the per-frame auto-scroll and the two
                // fight, which reads as a flicker at the anchor point. Landed turns
                // and phase markers keep the "rise" entrance / reflow.
                val isStreamingMsg = entry is TimelineEntry.Message && entry.msg.status.running
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = popScale.value
                            scaleY = popScale.value
                            // User bubble is right-aligned; pop from its trailing edge.
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        }
                        .then(if (isStreamingMsg) Modifier else Modifier.animateItem()),
                ) {
                    when (entry) {
                        is TimelineEntry.Phase -> PhaseDivider(entry.marker)
                        is TimelineEntry.Message -> TimelineMessageRow(
                            msg = entry.msg,
                            room = room,
                            isTopic = entry.msg.id == topicMessageId,
                        )
                    }
                }
            }
        }
        // Live speaking strip — slides/fades in while a guest turn streams.
        AnimatedVisibility(
            visible = isStreaming && speaking != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            speaking?.let { p ->
                CouncilSpeakingStrip(
                    participant = p,
                    onClick = {
                        val idx = entries.indexOfLast {
                            it is TimelineEntry.Message && it.msg.authorId == p.id
                        }
                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                    },
                )
            }
        }
        // Composer only in active (non-terminal) mode.
        if (vm != null) {
            CouncilRoomComposer(room = room, vm = vm)
        }
    }
}

/** Live "X 正在发言…" strip with a breathing signal dot + model label. */
@Composable
private fun CouncilSpeakingStrip(participant: CouncilParticipant, onClick: () -> Unit) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = chatTheme.surface,
        border = BorderStroke(1.dp, chatTheme.surfaceEdge),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CouncilBreathingDot(color = workspace.green)
            Text(
                text = "${participant.name.ifBlank { participant.id }} 正在发言…",
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
            )
            val modelLabel = participant.modelLabel()
            if (modelLabel.isNotBlank()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.faint,
                    )
                }
            }
        }
    }
}

/** Pulsing signal dot — a fading, expanding ring around a solid core (design `.dot::after`). */
@Composable
private fun CouncilBreathingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "council-dot")
    val ringScale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 2.1f,
        animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "council-dot-scale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "council-dot-alpha",
    )
    Box(modifier = Modifier.size(15.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer {
                    scaleX = ringScale
                    scaleY = ringScale
                    alpha = ringAlpha
                }
                .background(color, CircleShape),
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
    }
}

/** A guest's display model id, e.g. "gpt-5.1" / "deepseek-v3.2". */
private fun CouncilParticipant.modelLabel(): String =
    modelName.ifBlank { externalModel }.ifBlank { providerName }

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
                .background(chatTheme.surfaceEdge),
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = chatTheme.surface,
            border = BorderStroke(1.dp, chatTheme.surfaceEdge),
        ) {
            Text(
                text = marker.label,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = chatTheme.inkFaint,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(chatTheme.surfaceEdge),
        )
    }
}

@Composable
private fun TimelineMessageRow(msg: CouncilMessage, room: CouncilRoom, isTopic: Boolean) {
    val isUser = msg.authorId == COUNCIL_ROOM_USER_ID
    val isHost = msg.authorId == COUNCIL_ROOM_HOST_ID
    val chatTheme = LocalChatTheme.current

    if (isUser) {
        // Mirror the main chat user bubble: solid userBubble fill, light userBubbleInk
        // text, asymmetric 16/16/5/16 corners, RIGHT-aligned, wraps content up to 82%
        // of the row (fillWidth=false on the markdown is what makes it adaptive).
        val userBubbleShape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomEnd = 5.dp,
            bottomStart = 16.dp,
        )
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopEnd,
        ) {
            val userBubbleMaxWidth = maxWidth * 0.82f
            Surface(
                modifier = Modifier
                    .wrapContentWidth(Alignment.End)
                    .widthIn(max = userBubbleMaxWidth)
                    .clip(userBubbleShape),
                shape = userBubbleShape,
                color = chatTheme.userBubble,
                contentColor = chatTheme.userBubbleInk,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    if (isTopic) {
                        Text(
                            text = "议题 · 发起人",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = chatTheme.userBubbleInk.copy(alpha = 0.55f),
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    if (msg.attachments.isNotEmpty()) {
                        CouncilBubbleAttachments(
                            attachments = msg.attachments,
                            modifier = Modifier.padding(bottom = if (msg.text.isNotBlank()) 8.dp else 0.dp),
                        )
                    }
                    if (msg.text.isNotBlank()) {
                        MarkdownBlock(
                            content = msg.text,
                            fillWidth = false,
                        )
                    }
                }
            }
        }
    } else {
        // The host's final synthesis is surfaced inline as a host message
        // (mode == SYNTHESIZE). Give it its own look — a neutral surface card with
        // an accent frame + "综合结论" kicker — so it reads as the conclusion and
        // doesn't get confused with the green topic ("命题") or ordinary host turns.
        val isSynthesis = isHost && msg.mode == CouncilRoomMode.SYNTHESIZE
        val modelLabel = remember(room.participants, msg.authorId) {
            room.participantById(msg.authorId)?.modelLabel().orEmpty()
        }
        // Member / host turn: header (avatar + identity + model) on top, then a
        // FULL-WIDTH bubble flush to the content margins (left margin = right
        // margin). The bubble's top-left corner is squared, mirroring the user
        // bubble's squared bottom-right — each notch points back at its sender.
        val bubbleShape = RoundedCornerShape(
            topStart = 5.dp,
            topEnd = 16.dp,
            bottomEnd = 16.dp,
            bottomStart = 16.dp,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                SubAgentAvatar(id = msg.authorId, name = msg.authorName, avatarSize = 34.dp)
                AuthorLabel(msg, isHost, modelLabel)
            }
            if (isSynthesis) {
                Text(
                    text = "综合结论",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = chatTheme.accent,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            MessageBubble(
                msg = msg,
                container = when {
                    isSynthesis -> chatTheme.surface
                    isHost -> chatTheme.accentSoft
                    else -> chatTheme.surface
                },
                content = when {
                    isSynthesis -> chatTheme.ink
                    isHost -> chatTheme.accentDeep
                    else -> chatTheme.ink
                },
                borderColor = when {
                    isSynthesis -> chatTheme.accent
                    isHost -> chatTheme.accentTint
                    else -> chatTheme.surfaceEdge
                },
                shape = bubbleShape,
            )
            ReferenceFootnotes(msg, room, alignEnd = false)
        }
    }
}

/** Renders a user message's image thumbnails + document chips inside its bubble. */
@Composable
private fun CouncilBubbleAttachments(attachments: List<UIMessagePart>, modifier: Modifier = Modifier) {
    val chatTheme = LocalChatTheme.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.filterIsInstance<UIMessagePart.Image>().forEach { img ->
            AsyncImage(
                model = img.url,
                contentDescription = "图片附件",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        attachments.filterIsInstance<UIMessagePart.Document>().forEach { doc ->
            Text(
                text = "文件 · ${doc.fileName}",
                style = MaterialTheme.typography.labelMedium,
                color = chatTheme.userBubbleInk,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(chatTheme.userBubbleInk.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun AuthorLabel(msg: CouncilMessage, isHost: Boolean, modelLabel: String) {
    val workspace = workspaceColors()
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = msg.authorName.ifBlank { msg.authorId },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isHost) workspace.amber else workspace.ink,
            )
            val displayRole = if (isHost) "主持人" else msg.role
            if (displayRole.isNotBlank() && displayRole != msg.authorName) {
                CouncilRolePill(text = displayRole, isHost = isHost)
            }
        }
        if (modelLabel.isNotBlank()) {
            Text(
                text = modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = workspace.faint,
            )
        }
    }
}

@Composable
internal fun CouncilRolePill(text: String, isHost: Boolean) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (isHost) chatTheme.accentTint else chatTheme.surfaceEdge),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (isHost) chatTheme.accent else workspace.muted,
        )
    }
}

@Composable
private fun MessageBubble(
    msg: CouncilMessage,
    container: Color,
    content: Color,
    borderColor: Color,
    shape: Shape = RoundedCornerShape(18.dp),
) {
    val workspace = workspaceColors()
    val streaming = msg.status == CouncilMessageStatus.STREAMING
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
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
        // (Removed the "由 Host 邀请" note — in auto-orchestration every member is
        // host-invited, so it was on every bubble and carried no signal.)
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
