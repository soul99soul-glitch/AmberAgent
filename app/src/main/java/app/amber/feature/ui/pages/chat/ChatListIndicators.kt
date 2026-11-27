package app.amber.feature.ui.pages.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PackageOpen
import app.amber.agent.R
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.CompactSummaryPayloads
import app.amber.core.context.ConversationCompact
import app.amber.core.model.AssistantRegex
import app.amber.core.settings.ReplyDeliveryMode
import app.amber.feature.ui.components.message.hasVisibleChatMessageContent
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.AmberTokens
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

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
                    stringResource(
                        R.string.chat_page_loading_older_prefetching,
                        loadedNodeCount,
                        totalNodeCount,
                    )
                } else {
                    stringResource(
                        R.string.chat_page_loading_older_preparing,
                        loadedNodeCount,
                        totalNodeCount,
                    )
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
    // accent 取自聊天主题 token (随 amberBase + accent 色解析), 替代硬编码 workspace.blue.
    val accent = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent
    val transition = rememberInfiniteTransition(label = "agent_waiting_dot")
    val dotScale = transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 680),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "agent_waiting_dot_scale",
    )
    val haloAlpha = transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 680),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "agent_waiting_dot_halo",
    )
    val dotAlpha = transition.animateFloat(
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
                .graphicsLayer {
                    scaleX = dotScale.value
                    scaleY = dotScale.value
                }
                .clip(CircleShape)
                .drawBehind {
                    drawRect(accent.copy(alpha = haloAlpha.value))
                }
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer {
                    scaleX = dotScale.value
                    scaleY = dotScale.value
                }
                .clip(CircleShape)
                .drawBehind {
                    drawRect(accent.copy(alpha = dotAlpha.value))
                }
        )
    }
}

/**
 * Exact Android counterpart of iOS `ChatTaskProgressDot`:
 * 3 staggered varying-shade accent dots doing a smooth vertical sine wave loop.
 */
@Composable
internal fun ChatTaskWaveDots(
    tint: Color = LocalAmberTokens.current.accent,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "chat_task_wave_dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1366, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave_phase",
    )
    val density = LocalDensity.current
    val maxLiftPx = with(density) { 2.3.dp.toPx() }
    val tiers = floatArrayOf(1.0f, 0.72f, 0.46f)
    Row(
        modifier = modifier
            .width(23.dp)
            .height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(3.5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (dotIndex in 0 until 3) {
            val wave = sin((phase - dotIndex * 0.92f).toDouble()).toFloat()
            val normalized = (wave + 1f) * 0.5f
            val dotAlpha = ((0.38f + 0.62f * normalized) * tiers[dotIndex]).coerceIn(0.28f, 1f)
            val dotScale = 0.90f + 0.16f * normalized
            Box(
                modifier = Modifier
                    .size(5.2.dp)
                    .graphicsLayer {
                        translationY = -maxLiftPx * wave
                        scaleX = dotScale
                        scaleY = dotScale
                        alpha = dotAlpha
                    }
                    .clip(CircleShape)
                    .drawBehind { drawRect(tint) },
            )
        }
    }
}

internal data class InlineStatusShimmerColors(
    val baseInk: Color,
    val edgeInk: Color,
    val peakInk: Color,
)

internal data class InlineStatusShimmerRange(
    val startX: Float,
    val endX: Float,
    val bandWidthPx: Float,
)

/**
 * Resolves the muted secondary ink and theme-accented sweeping glint for the
 * Personal Agent single-line status indicator, matching iOS `ChatTaskProgressTextShimmer`
 * (`clear -> accentColor.opacity(0.55) -> white.opacity(0.82) -> accentColor.opacity(0.55) -> clear`):
 * base text stays softer than body copy ([AmberTokens.ink3]), shimmer shoulders pick up the
 * active theme [accentColor], and the peak lifts into a soft accent-tinted highlight across
 * both light paper and dark graphite themes.
 */
internal fun resolveInlineStatusShimmerColors(
    tokens: AmberTokens,
    accentColor: Color = tokens.accent,
): InlineStatusShimmerColors {
    val baseInk = tokens.ink3
    val edgeInk = if (tokens.isDark) {
        val liftedAccent = lerp(accentColor, Color.White, 0.24f)
        lerp(baseInk, liftedAccent, 0.65f)
    } else {
        lerp(baseInk, accentColor, 0.62f)
    }
    val peakInk = if (tokens.isDark) {
        lerp(accentColor, Color.White, 0.76f)
    } else {
        val paperWhite = lerp(tokens.bg, Color.White, 0.75f)
        lerp(accentColor, paperWhite, 0.74f)
    }
    return InlineStatusShimmerColors(
        baseInk = baseInk,
        edgeInk = edgeInk,
        peakInk = peakInk,
    )
}

/**
 * Computes the horizontal gradient span for the sweeping shimmer band based on
 * the measured glyph width (matching iOS `ChatTaskProgressTextShimmer`), so short
 * status labels don't spend most of the 1.8s cycle sweeping through empty space.
 */
internal fun computeInlineStatusShimmerRange(
    textWidthPx: Float,
    minBandWidthPx: Float,
    progress: Float,
): InlineStatusShimmerRange {
    val clampedWidth = textWidthPx.coerceAtLeast(1f)
    val bandWidthPx = max(clampedWidth * 0.48f, minBandWidthPx.coerceAtLeast(1f))
    val clampedProgress = progress.coerceIn(0f, 1f)
    val startX = -bandWidthPx + (clampedWidth + bandWidthPx * 2f) * clampedProgress
    return InlineStatusShimmerRange(
        startX = startX,
        endX = startX + bandWidthPx,
        bandWidthPx = bandWidthPx,
    )
}

/**
 * Mirrors `ChatMessage` + `ChatMessageAssistantAvatar`'s header visibility guard so
 * the waiting/tail indicator shows the persona name before the assistant header
 * exists and yields immediately once the assistant message actually renders visible
 * content (committed segmented block, tool/thinking card, or live text).
 */
internal fun hasRenderedAssistantMessageHeader(
    lastMessage: UIMessage?,
    hasResolvedModel: Boolean,
    showModelName: Boolean,
    loading: Boolean = false,
    replyDeliveryMode: ReplyDeliveryMode = ReplyDeliveryMode.LIVE_STREAMING,
    regexes: List<AssistantRegex> = emptyList(),
): Boolean {
    if (!showModelName || !hasResolvedModel || lastMessage == null) return false
    return lastMessage.role == MessageRole.ASSISTANT &&
        lastMessage.parts.hasVisibleChatMessageContent(
            role = lastMessage.role,
            loading = loading,
            replyDeliveryMode = replyDeliveryMode,
            regexes = regexes,
        )
}

internal fun resolveWaitingIndicatorAgentName(
    isPersonalAgent: Boolean,
    showModelName: Boolean,
    hasRenderedAssistantHeader: Boolean,
    agentDisplayName: String?,
): String? {
    if (!isPersonalAgent || !showModelName || hasRenderedAssistantHeader) return null
    return agentDisplayName?.trim()?.takeIf { it.isNotEmpty() }
}

internal val PersonalAgentHeaderToBodySpacing = 6.dp
internal val InlineStatusRowHeight = 24.dp
internal val InlineStatusTextLineHeight = 20.sp
internal val InlineStatusPlatformStyle = PlatformTextStyle(includeFontPadding = false)
internal val InlineStatusLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

internal fun resolveAgentPersonaHeaderTextStyle(baseMetaStyle: TextStyle): TextStyle =
    baseMetaStyle.copy(
        fontWeight = FontWeight.Bold,
        platformStyle = InlineStatusPlatformStyle,
        lineHeightStyle = InlineStatusLineHeightStyle,
    )

internal fun resolveInlineStatusTextStyle(
    baseSecondaryStyle: TextStyle,
    brush: Brush? = null,
): TextStyle = baseSecondaryStyle.copy(
    fontSize = 14.sp,
    lineHeight = InlineStatusTextLineHeight,
    fontWeight = FontWeight.Medium,
    brush = brush,
    platformStyle = InlineStatusPlatformStyle,
    lineHeightStyle = InlineStatusLineHeightStyle,
)

@Composable
internal fun AgentWorkingIndicator(
    processingStatus: String?,
    agentDisplayName: String? = null,
    modifier: Modifier = Modifier,
) {
    val isPersonalAgent = LocalChatStageMode.current is ChatStageMode.PersonalAgent
    if (!isPersonalAgent) {
        AgentWaitingDot(modifier = modifier.size(28.dp))
        return
    }
    val statusText = processingStatus?.trim()?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.chat_inline_status_preparing_reply)
    val headerLabel = agentDisplayName?.trim()?.takeIf { it.isNotEmpty() }
    val tokens = LocalAmberTokens.current
    val amberType = LocalAmberType.current
    val chatTheme = LocalChatTheme.current
    val shimmerColors = remember(tokens, chatTheme.accent) {
        resolveInlineStatusShimmerColors(tokens, chatTheme.accent)
    }
    val shimmerTransition = rememberInfiniteTransition(label = "inline_status_shimmer")
    val shimmerProgress by shimmerTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_progress",
    )
    val density = LocalDensity.current
    val fallbackTextWidthPx = with(density) { 112.dp.toPx() }
    val minBandWidthPx = with(density) { 36.dp.toPx() }
    var lastMeasuredTextWidthPx by remember { mutableFloatStateOf(fallbackTextWidthPx) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PersonalAgentHeaderToBodySpacing),
    ) {
        if (headerLabel != null) {
            Text(
                text = headerLabel,
                style = resolveAgentPersonaHeaderTextStyle(amberType.meta),
                color = chatTheme.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(InlineStatusRowHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChatTaskWaveDots(tint = chatTheme.accent)
            AnimatedContent(
                targetState = statusText,
                modifier = Modifier
                    .weight(1f)
                    .height(InlineStatusRowHeight),
                contentAlignment = Alignment.CenterStart,
                transitionSpec = {
                    (
                        fadeIn(tween(180, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(tween(140, easing = FastOutSlowInEasing))
                    ).using(null)
                },
                label = "inline_working_status",
            ) { line ->
                var measuredTextWidthPx by remember(line) {
                    mutableFloatStateOf(lastMeasuredTextWidthPx)
                }
                val effectiveWidthPx = measuredTextWidthPx.coerceAtLeast(1f)
                val shimmerRange = computeInlineStatusShimmerRange(
                    textWidthPx = effectiveWidthPx,
                    minBandWidthPx = minBandWidthPx,
                    progress = shimmerProgress,
                )
                val textBrush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.00f to shimmerColors.baseInk,
                        0.18f to shimmerColors.baseInk,
                        0.35f to shimmerColors.edgeInk,
                        0.50f to shimmerColors.peakInk,
                        0.65f to shimmerColors.edgeInk,
                        0.82f to shimmerColors.baseInk,
                        1.00f to shimmerColors.baseInk,
                    ),
                    start = Offset(shimmerRange.startX, 0f),
                    end = Offset(shimmerRange.endX, 0f),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(InlineStatusRowHeight),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = line,
                        style = resolveInlineStatusTextStyle(
                            baseSecondaryStyle = amberType.secondary,
                            brush = textBrush,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layoutResult ->
                            if (layoutResult.lineCount > 0) {
                                val width = (layoutResult.getLineRight(0) - layoutResult.getLineLeft(0))
                                    .coerceAtLeast(1f)
                                if (width != measuredTextWidthPx) {
                                    measuredTextWidthPx = width
                                }
                                lastMeasuredTextWidthPx = width
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun TimelineTailWorkingIndicator(
    processingStatus: String?,
    visible: Boolean,
    agentDisplayName: String? = null,
    modifier: Modifier = Modifier,
) {
    val isPersonalAgent = LocalChatStageMode.current is ChatStageMode.PersonalAgent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AgentWorkingIndicatorReserveHeight),
        // Top-aligned: the dot/status line hugs the streaming content above it.
        // Personal Agent left-aligns the indicator with the assistant reading column (iOS/HarmonyOS parity).
        contentAlignment = if (isPersonalAgent) Alignment.TopStart else Alignment.TopCenter,
    ) {
        // Crossfade instead of popping: below 0.01 alpha the dot leaves
        // composition entirely so the retained (visible=false) reserve doesn't
        // keep an infinite pulse transition running.
        val dotAlpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(durationMillis = TailIndicatorHandoffFadeMs),
            label = "tail_indicator_handoff",
        )
        if (dotAlpha > 0.01f) {
            AgentWorkingIndicator(
                processingStatus = processingStatus,
                agentDisplayName = agentDisplayName,
                modifier = Modifier.alpha(dotAlpha),
            )
        }
    }
}

@Composable
internal fun PostSendWaitingIndicator(
    visible: Boolean,
    processingStatus: String? = null,
    agentDisplayName: String? = null,
    modifier: Modifier = Modifier,
) {
    if (!visible) {
        // Zero-height when the follow-animation setting is off — a 56dp blank
        // placeholder here opened a gap between the sent bubble and the input
        // bar for the whole wait-for-first-token window.
        Spacer(modifier = modifier.fillMaxWidth())
        return
    }
    TimelineTailWorkingIndicator(
        processingStatus = processingStatus,
        visible = true,
        agentDisplayName = agentDisplayName,
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
    val compactingSubtitle = stringResource(R.string.chat_context_auto_compacting_subtitle)
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
                    imageVector = Lucide.PackageOpen,
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
            text = tailText.ifBlank { compactingSubtitle },
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
                imageVector = Lucide.PackageOpen,
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
