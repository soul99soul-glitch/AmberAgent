package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.PendingUserMessage
import me.rerere.rikkahub.service.PendingUserMessageMode
import me.rerere.rikkahub.service.previewText
import me.rerere.rikkahub.ui.components.ui.PigLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.workspaceColors

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
            PigLoadingIndicator(modifier = Modifier.size(18.dp))
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
internal fun AgentWorkingIndicator(
    processingStatus: String?,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PigLoadingIndicator(
            modifier = Modifier.size(28.dp)
        )
        if (!processingStatus.isNullOrBlank()) {
            Text(
                text = processingStatus,
                style = MaterialTheme.typography.labelMedium,
                color = workspace.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
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
        // 2026-05-15 (1.9.7): show only the PROSE prefix of the streaming
        // summary (everything before the first `{`), not the raw JSON tail.
        // buildCompressionPrompt asks the LLM to write a single-sentence
        // human-readable summary BEFORE the JSON body, so the early tokens
        // are prose; once the model starts emitting JSON we freeze the
        // displayed text (no more updates, since `proseOnly` stops growing).
        // Whitespace-collapsed for stable rendering height; trailing 120 chars
        // + leading ellipsis once it overflows. When stream hasn't produced
        // any prose yet (cold start, slow first token), fall back to the
        // generic subtitle so the marker isn't a void of whitespace.
        //
        // 2026-05-18: cut at first `{` (JSON object start) AND scrub any
        // ```json / ``` / [Summary…] markers from inside the prose — DeepSeek
        // and several smaller models routinely emit a code fence between
        // the preamble sentence and the JSON, which without scrubbing
        // turned the live display into a stream of "```json" gibberish.
        // Cheap String.replace beats a Regex compile per frame; the
        // markers are short and case-stable.
        val proseOnly = remember(streamingText) {
            val cutIndex = streamingText.indexOf('{')
            val rawPreamble = if (cutIndex >= 0) {
                streamingText.substring(0, cutIndex)
            } else {
                streamingText
            }
            val scrubbed = rawPreamble
                .replace("```json", " ")
                .replace("```", " ")
                .replace("[Summary of previous conversation]", " ")
                .replace("[Summary]", " ")
            COMPACT_SUMMARY_WHITESPACE_RE.replace(scrubbed, " ").trim()
        }
        // 2026-05-18 (post-review): take the FIRST 120 chars rather than the
        // last. buildCompressionPrompt mandates a single ≤100 char preamble
        // sentence — its meaning lives at the START of the buffer. takeLast
        // was a holdover from a "scrolling cursor" UX that made sense for an
        // unbounded build log; here it would surface the prose-near-JSON
        // transition (e.g. "…goals are deploy, fix bug, ") rather than the
        // actual sentence head, especially when a misbehaving model rambles
        // before emitting `{`. JSON-side content stays hidden either way
        // (cut at `{` happens before this).
        val tailText = remember(proseOnly) {
            if (proseOnly.length > 120) proseOnly.take(120) + "…" else proseOnly
        }
        Text(
            text = tailText.ifBlank { stringResource(R.string.chat_context_auto_compacting_subtitle) },
            style = MaterialTheme.typography.labelSmall,
            color = workspace.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
internal fun ContextCompactMarker(
    modifier: Modifier = Modifier,
    summaryPreview: String? = null,
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
        // 2026-05-15 (1.9.5): when shown as the transient post-compact marker
        // (bottom of LazyColumn, 8s window), show a short preview of the freshly
        // generated summary so the user can actually SEE what was compacted —
        // not just "something happened". Lightweight alternative to a full
        // streaming view (would have required 200+ lines + new flows). 80 chars
        // is enough for a sentence + ellipsis on most phone widths.
        if (summaryPreview != null) {
            Text(
                text = summaryPreview,
                style = MaterialTheme.typography.labelSmall,
                color = workspace.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
