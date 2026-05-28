package app.amber.feature.ui.pages.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.rikkahub.R
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.CompactSummaryPayloads
import app.amber.core.context.ConversationCompact
import app.amber.core.service.PendingUserMessage
import app.amber.core.service.PendingUserMessageMode
import app.amber.core.service.previewText
import app.amber.feature.ui.components.ui.workspaceColors

@Composable
internal fun TimelineCompactMarkers(
    timelineEndIndex: Int,
    completedMarkersByTimelineEndIndex: Map<Int, List<ConversationCompact>>,
    lifecycleCompletedTimelineEndIndex: Int?,
    compactLifecycleState: CompactLifecycleState,
    activeCompactTimelineEndIndex: Int?,
    activeCompactStreamingSummary: String,
    modifier: Modifier = Modifier,
) {
    completedMarkersByTimelineEndIndex[timelineEndIndex].orEmpty().forEach { compact ->
        ContextCompactMarker(
            modifier = modifier,
            summaryPreview = summaryPreviewOf(compact),
        )
    }
    if (lifecycleCompletedTimelineEndIndex == timelineEndIndex) {
        ContextCompactMarker(
            modifier = modifier,
            summaryPreview = summaryPreviewOf(compactLifecycleState),
        )
    }
    if (activeCompactTimelineEndIndex == timelineEndIndex) {
        ContextCompactInProgressMarker(
            modifier = modifier,
            streamingText = activeCompactStreamingSummary,
        )
    }
}

@Composable
internal fun TimelineHistoryLoadingIndicator(
    prefetching: Boolean,
    loadedNodeCount: Int,
    totalNodeCount: Int,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = workspace.paper.copy(alpha = 0.72f),
        contentColor = workspace.muted,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AgentWaitingDot(modifier = Modifier.size(18.dp))
            Text(
                text = if (prefetching) {
                    "正在预取更早消息 $loadedNodeCount/$totalNodeCount"
                } else {
                    "更早消息准备中 $loadedNodeCount/$totalNodeCount"
                },
                style = MaterialTheme.typography.labelMedium,
                color = workspace.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AgentWaitingDot(
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    // V3: 用 chatTheme.accent 替代硬编码 workspace.blue, 跟 4 主题 (Whisper蓝/Plain黑/Paper砖红/Midnight冷靛) 联动.
    val accent = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent
    val transition = rememberInfiniteTransition(label = "agent_waiting_dot")
    val dotScale by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 680),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "agent_waiting_dot_scale",
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 680),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "agent_waiting_dot_halo",
    )
    val dotAlpha by transition.animateFloat(
        initialValue = 0.56f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 680),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "agent_waiting_dot_alpha",
    )
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .scale(dotScale)
                .clip(CircleShape)
                .background(accent.copy(alpha = haloAlpha))
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .scale(dotScale)
                .clip(CircleShape)
                .background(accent.copy(alpha = dotAlpha))
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AgentWorkingIndicator(
    processingStatus: String?,
    modifier: Modifier = Modifier,
) {
    AgentWaitingDot(modifier = modifier.size(28.dp))
}

@Composable
internal fun TimelineTailWorkingIndicator(
    processingStatus: String?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AgentWorkingIndicatorReserveHeight),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (visible) {
            AgentWorkingIndicator(
                processingStatus = processingStatus,
                modifier = Modifier.padding(bottom = 18.dp),
            )
        }
    }
}

@Composable
internal fun PostSendWaitingIndicator(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    TimelineTailWorkingIndicator(
        processingStatus = null,
        visible = visible,
        modifier = modifier,
    )
}

/**
 * Codex-style "auto-compacting in progress" divider — two hairlines flanking a
 * label whose text is painted with a horizontal gradient that sweeps left→right
 * on an infinite loop. The sweep is implemented via `TextStyle(brush=...)` with
 * three color stops anchored around `phase`, so the highlight band slides across
 * the glyphs without redrawing the whole row each frame. Once compaction
 * completes the consumer swaps this for [ContextCompactMarker] (final state).
 *
 * Why the swap instead of fading the same widget: the "completed" marker is
 * decorative (visual divider in the timeline) and lives forever; the
 * "in-progress" marker is transient feedback (~5-30s) and shouldn't accumulate
 * in chat history. Treating them as separate Composables keeps the lifetime
 * model simple.
 */
@Composable
internal fun ContextCompactInProgressMarker(
    modifier: Modifier = Modifier,
    streamingText: String = "",
) {
    val workspace = workspaceColors()
    val transition = rememberInfiniteTransition(label = "compactShimmer")
    val phase by transition.animateFloat(
        // Sweep range is wider than [0, 1] so the highlight band travels off both
        // ends — gives a brief "pause" at the edges before the next pass, which
        // reads as a deliberate rhythm rather than a frantic strobe. 1400ms is
        // the sweet spot the reviewer flagged: 1800ms felt sluggish, 1000ms
        // felt frantic. Restart (not Reverse) means the highlight always goes
        // left→right, matching Codex's reading direction.
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerPhase",
    )
    val baseColor = workspace.muted
    // Highlight tone: same hue as the base muted, but brighter so the band reads
    // as a glint rather than a different color (Codex behaviour, not a rainbow).
    val highlightColor = workspace.ink
    // No `remember` wrap here — `phase` changes every frame so caching is a
    // no-op. Build the brush directly per recomposition; the cost is 5
    // ColorStop allocations, which is negligible vs the canvas redraw the
    // gradient triggers anyway.
    // 5-stop linear gradient: muted → muted → bright → muted → muted, with
    // the bright stop anchored at `phase`. Width of the bright band is
    // ~0.15 of the total in each direction = 30% of width feels lit at any
    // given moment. Wider would wash the text, narrower would feel laser-y.
    val brush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to baseColor,
            (phase - 0.15f).coerceIn(0f, 1f) to baseColor,
            phase.coerceIn(0f, 1f) to highlightColor,
            (phase + 0.15f).coerceIn(0f, 1f) to baseColor,
            1f to baseColor,
        ),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = workspace.hairline,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Package01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = workspace.muted,
                )
                Text(
                    text = stringResource(R.string.chat_context_auto_compacting),
                    style = MaterialTheme.typography.labelLarge.copy(brush = brush),
                )
            }
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = workspace.hairline,
            )
        }
        val proseOnly = remember(streamingText) {
            CompactSummaryPayloads.timelineSummary(streamingText).orEmpty()
        }
        val tailText = remember(proseOnly) {
            if (proseOnly.length > 220) proseOnly.take(220) + "…" else proseOnly
        }
        Text(
            text = tailText.ifBlank { stringResource(R.string.chat_context_auto_compacting_subtitle) },
            style = MaterialTheme.typography.labelSmall,
            color = workspace.muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
internal fun ContextCompactMarker(
    modifier: Modifier = Modifier,
    summaryPreview: String? = null,
    @Suppress("UNUSED_PARAMETER")
    freshlyCompletedKey: String? = null,
) {
    val workspace = workspaceColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = workspace.hairline,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Package01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = workspace.muted,
            )
            Text(
                text = stringResource(R.string.chat_context_auto_compacted),
                style = MaterialTheme.typography.labelLarge,
                color = workspace.muted,
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = workspace.hairline,
        )
        }
        // Show the human timeline summary so the user can see what was
        // preserved after older messages above the divider are dimmed.
        if (summaryPreview != null) {
            Text(
                text = summaryPreview,
                style = MaterialTheme.typography.labelSmall,
                color = workspace.muted,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
internal fun PendingUserMessageBubble(
    message: PendingUserMessage,
    onCancel: () -> Unit,
    queueCount: Int? = null,
    onOpenQueue: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val borderColor = when (message.mode) {
        PendingUserMessageMode.FOLLOWUP -> workspace.muted.copy(alpha = 0.42f)
        PendingUserMessageMode.STEER -> workspace.blue.copy(alpha = 0.48f)
        PendingUserMessageMode.COLLECT -> workspace.blue.copy(alpha = 0.28f)
    }
    val textColor = when (message.mode) {
        PendingUserMessageMode.FOLLOWUP -> workspace.muted
        PendingUserMessageMode.STEER -> workspace.blue.copy(alpha = 0.82f)
        PendingUserMessageMode.COLLECT -> workspace.muted.copy(alpha = 0.9f)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopEnd,
        ) {
            val bubbleMaxWidth = maxOf(96.dp, minOf(maxWidth * 0.72f, 560.dp))
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 96.dp, max = bubbleMaxWidth)
                        .dashedRoundedBorder(borderColor, 10.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = workspace.paper.copy(alpha = 0.62f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message.previewText(),
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = "取消排队消息",
                                tint = textColor.copy(alpha = 0.72f),
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
                if (queueCount != null && queueCount > 0) {
                    Text(
                        text = "已排队 $queueCount 条",
                        modifier = Modifier
                            .padding(top = 4.dp, end = 2.dp)
                            .clickable { onOpenQueue() },
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
