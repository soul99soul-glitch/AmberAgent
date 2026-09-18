package app.amber.feature.live.bubble

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.amber.feature.live.FILL_CONFIRM_WINDOW_MS
import app.amber.feature.live.LiveFillResult
import app.amber.feature.live.LiveModeCard
import app.amber.feature.live.LiveModeUiState
import app.amber.feature.live.LiveMotion
import app.amber.agent.R
import app.amber.feature.ui.components.ds.StreamingTextWithCursor
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

/**
 * 气泡内容（精简版交互，用户 2026-06-11 决策）：
 * 收起 = 44dp 三态点（待命灰 / 分析中呼吸 / 有新结果 sonar 脉冲）；
 * 点开 = 280dp 卡片（结论 + ≤3 要点 + 草稿 + 填入/立即分析）；长按点 = 退出伴随。
 *
 * 交互/动效约定（2026-09-17 增强）：
 * - 展开态由 Manager 持有，窗口重挂（任务气泡让位往返）不丢；收起走对称 reverse pop；
 * - 圆点拖动有 lift（缩小+半透明）反馈，松手由 BubbleWindow 弹簧贴边；
 * - 新鲜结果以 sonar halo 提示（被查看即静默），分析中 alpha+scale 双呼吸；
 * - 覆盖确认 5s 窗口以按钮下划线倒计时呈现，不再静默复位。
 */
@Composable
fun LiveBubbleContent(
    state: LiveModeUiState,
    expanded: Boolean,
    lastSeenMillis: Long,
    onExpandedChange: (Boolean) -> Unit,
    onFillDraft: () -> LiveFillResult,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onSizeChanged: () -> Unit,
    anchorEndProvider: () -> Boolean = { true },
) {
    val haptics = LocalHapticFeedback.current
    val hasFreshResult = state.card != null && state.lastUpdatedAtMillis > lastSeenMillis
    val stopWithHaptic: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onStop()
    }

    // 收起动画：expanded 变 false 时先播 130ms reverse pop，再切回圆点（展开有 pop、收起不再瞬跳）。
    var collapsing by remember { mutableStateOf(false) }
    // pop 长出方向在展开瞬间锁定（贴右从右上角、贴左从左上角）；收起反向 pop 沿用同值。
    var anchorEnd by remember { mutableStateOf(true) }
    LaunchedEffect(expanded) {
        if (expanded) {
            collapsing = false
            anchorEnd = anchorEndProvider()
        }
    }
    LaunchedEffect(collapsing) {
        if (collapsing && !expanded) {
            delay(LiveMotion.BubblePopMs.toLong())
            collapsing = false
            onSizeChanged()
        }
    }

    if (expanded || collapsing) {
        ExpandedCard(
            state = state,
            collapsing = collapsing,
            anchorEnd = anchorEnd,
            onCollapse = {
                collapsing = true
                onExpandedChange(false)
            },
            onStop = stopWithHaptic,
            onFillDraft = onFillDraft,
            onRefresh = onRefresh,
            onSizeChanged = onSizeChanged,
        )
    } else {
        CollapsedDot(
            state = state,
            hasFreshResult = hasFreshResult,
            dotDescription = stringResource(R.string.live_bubble_a11y_label),
            onExpand = { onExpandedChange(true) },
            onStop = stopWithHaptic,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
        )
    }
}

// ───────────────────────────── 收起态：三态点 ─────────────────────────────

@Composable
private fun CollapsedDot(
    state: LiveModeUiState,
    hasFreshResult: Boolean,
    dotDescription: String,
    onExpand: () -> Unit,
    onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val pulse = rememberInfiniteTransition(label = "bubblePulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "bubblePulseAlpha",
    )
    val breathe by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "bubblePulseScale",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var dragging by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = when {
            dragging -> 0.92f
            pressed -> 0.94f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "dotPressScale",
    )
    // 52dp 触摸热区（过 48dp 可达性下限）内含 44dp 视觉圆；halo 只在 44dp 圆内扩，不越窗。
    Box(
        modifier = Modifier
            .size(52.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = if (dragging) 0.85f else 1f
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDrag = { change, delta ->
                        change.consume()
                        onDrag(delta.x, delta.y)
                    },
                    onDragEnd = {
                        dragging = false
                        onDragEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        onDragEnd()
                    },
                )
            }
            .semantics { contentDescription = dotDescription }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onExpand,
                onLongClick = onStop,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(tokens.surface)
                .border(1.dp, tokens.line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (hasFreshResult) {
                // 新结果 sonar：600ms 外扩 + 1.2s 静默的节奏循环，直到被展开查看。
                val halo = rememberInfiniteTransition(label = "freshHalo")
                val haloP by halo.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 1800
                            0f at 0
                            1f at 600
                            1f at 1799
                        },
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "freshHaloP",
                )
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .graphicsLayer {
                            val s = 1f + haloP * 1.6f
                            scaleX = s
                            scaleY = s
                            alpha = 0.5f * (1f - haloP)
                        }
                        .background(tokens.accent, CircleShape),
                )
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        alpha = if (state.analyzing) pulseAlpha else 1f
                        val s = when {
                            state.analyzing -> breathe
                            hasFreshResult -> 1.15f
                            else -> 1f
                        }
                        scaleX = s
                        scaleY = s
                    }
                    .clip(CircleShape)
                    .background(
                        when {
                            state.analyzing -> tokens.accent
                            hasFreshResult -> tokens.accent
                            state.paused -> tokens.ink4
                            else -> tokens.ink3
                        }
                    ),
            )
        }
    }
}

// ───────────────────────────── 展开态：精简卡片 ─────────────────────────────

@Composable
private fun ExpandedCard(
    state: LiveModeUiState,
    collapsing: Boolean,
    anchorEnd: Boolean,
    onCollapse: () -> Unit,
    onStop: () -> Unit,
    onFillDraft: () -> LiveFillResult,
    onRefresh: () -> Unit,
    onSizeChanged: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val card = state.card
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        appeared = true
        onSizeChanged()
    }
    val pop by animateFloatAsState(
        targetValue = if (!appeared || collapsing) 0.85f else 1f,
        animationSpec = tween(durationMillis = LiveMotion.BubblePopMs, easing = FastOutSlowInEasing),
        label = "bubblePop",
    )
    val fade by animateFloatAsState(
        targetValue = if (!appeared || collapsing) 0f else 1f,
        animationSpec = tween(durationMillis = LiveMotion.BubblePopMs),
        label = "bubbleFade",
    )
    Column(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pop
                scaleY = pop
                alpha = fade
                transformOrigin = if (anchorEnd) TransformOrigin(1f, 0f) else TransformOrigin(0f, 0f)
            }
            .width(280.dp)
            .heightIn(max = (LocalConfiguration.current.screenHeightDp - 48).dp)
            // 流式文本/结果回写使内容增高时也重夹窗口（屏底不越界）；requestReclamp 幂等。
            .onSizeChanged { onSizeChanged() }
            .verticalScroll(rememberScrollState())
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.surface)
            .border(1.dp, tokens.line2, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeaderRow(state = state, onCollapse = onCollapse, onStop = onStop)

        StreamingPreview(state.streamingText)

        ResultBody(card = card, state = state)

        ActionRow(state = state, card = card, onFillDraft = onFillDraft, onRefresh = onRefresh)

        // 破坏性手势的可发现性：长按退出没有视觉入口，用一行 tiny 提示兜底。
        Text(
            text = stringResource(R.string.live_bubble_exit_hint),
            style = LocalAmberType.current.meta,
            color = tokens.ink4,
        )
    }
}

@Composable
private fun HeaderRow(state: LiveModeUiState, onCollapse: () -> Unit, onStop: () -> Unit) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val labelScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "collapseLabelPress",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onCollapse,
                onLongClick = onStop,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (state.analyzing) tokens.accent else tokens.ink3),
        )
        Text(
            text = state.statusText.ifBlank { stringResource(R.string.live_bubble_status_default) },
            style = type.meta,
            color = tokens.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.live_bubble_collapse),
            style = type.meta,
            color = tokens.ink4,
            modifier = Modifier.graphicsLayer {
                scaleX = labelScale
                scaleY = labelScale
            },
        )
    }
}

/** 流式预览：出现/消失走 fade+展开；正文带行尾闪烁光标。失败后半截文本的保留逻辑在 Manager。 */
@Composable
private fun StreamingPreview(streamingText: String?) {
    val tokens = LocalAmberTokens.current
    AnimatedVisibility(
        visible = !streamingText.isNullOrBlank(),
        enter = fadeIn(tween(LiveMotion.FastMs)) + expandVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
        exit = fadeOut(tween(LiveMotion.FastMs)) + shrinkVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
    ) {
        StreamingTextWithCursor(
            text = streamingText.orEmpty(),
            style = LocalAmberType.current.meta,
            color = tokens.ink3,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.surface2)
                .padding(8.dp),
        )
    }
}

/** 占位 ↔ 结果、旧结果 ↔ 新结果：交叉淡化过渡（新结果到达不再是硬切文本）。 */
@Composable
private fun ResultBody(card: LiveModeCard?, state: LiveModeUiState) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val uncertainResultText = stringResource(R.string.live_result_uncertain)
    AnimatedContent(
        targetState = card,
        transitionSpec = {
            fadeIn(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)) togetherWith
                fadeOut(tween(LiveMotion.FastMs))
        },
        label = "bubbleResultBody",
    ) { target ->
        if (target == null) {
            Text(
                text = stringResource(R.string.live_bubble_no_result),
                style = type.secondary,
                color = tokens.ink3,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = target.watching.ifBlank { uncertainResultText },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                target.keyPoints.take(3).forEach { point ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "·", color = tokens.accent, fontSize = 14.sp)
                        Text(text = point, fontSize = 14.sp, color = tokens.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val draft = target.suggestions.firstOrNull()?.takeIf { it.isNotBlank() }
                if (draft != null && state.completedAction == "写回复") {
                    Text(
                        text = draft,
                        fontSize = 14.sp,
                        color = tokens.ink,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.surface2)
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    state: LiveModeUiState,
    card: LiveModeCard?,
    onFillDraft: () -> LiveFillResult,
    onRefresh: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        val draftAvailable = card != null &&
            (card.suggestions.firstOrNull()?.isNotBlank() == true || card.watching.isNotBlank())
        if (draftAvailable) {
            // 填入/复制（蓝图 §7.3 P1-2）：CHAT 白名单内显示"填入"，其余"复制"；
            // NEEDS_CONFIRM 切"覆盖"确认态，5s 窗口以下划线倒计时呈现。降级复制由 Manager 仲裁。
            FillTextButton(state = state, onFillDraft = onFillDraft)
            Spacer(modifier = Modifier.width(4.dp))
        }
        val refreshEnabled = state.active && !state.paused && !state.analyzing
        TextButton(onClick = onRefresh, enabled = refreshEnabled) {
            Text(
                stringResource(R.string.live_analyze_now),
                fontSize = 14.sp,
                color = if (refreshEnabled) tokens.accent else tokens.ink4,
            )
        }
    }
}

@Composable
private fun FillTextButton(state: LiveModeUiState, onFillDraft: () -> LiveFillResult) {
    val tokens = LocalAmberTokens.current
    val context = LocalContext.current
    val fillDraftFilledMessage = stringResource(R.string.live_fill_result_filled)
    val fillDraftCopiedMessage = stringResource(R.string.live_fill_result_copied_short)
    val fillDraftMissingMessage = stringResource(R.string.live_fill_result_missing)
    val fillNeedConfirmMessage = stringResource(R.string.live_fill_toast_need_confirm)
    val fillStaleCopiedMessage = stringResource(R.string.live_fill_toast_stale_copied)
    val fillAllowed = state.fillAllowed
    var confirmOverwrite by remember { mutableStateOf(false) }
    // 窗口内二次确认时 confirmOverwrite 不变位，用轮次号驱动下划线重新起算（checker P2）。
    var confirmRound by remember { mutableStateOf(0) }
    val confirmProgress = remember { Animatable(1f) }
    LaunchedEffect(confirmOverwrite, confirmRound) {
        if (confirmOverwrite) {
            val progressJob = launch {
                confirmProgress.snapTo(1f)
                confirmProgress.animateTo(0f, tween(FILL_CONFIRM_WINDOW_MS.toInt(), easing = LinearEasing))
            }
            delay(FILL_CONFIRM_WINDOW_MS)
            progressJob.cancel()
            confirmOverwrite = false
        }
    }
    val label = when {
        confirmOverwrite -> stringResource(R.string.live_fill_confirm_overwrite)
        fillAllowed -> stringResource(R.string.live_fill_action_fill)
        else -> stringResource(R.string.live_fill_action)
    }
    TextButton(onClick = {
        val message = when (onFillDraft()) {
            LiveFillResult.FILLED -> {
                confirmOverwrite = false
                fillDraftFilledMessage
            }
            LiveFillResult.NEEDS_CONFIRM -> {
                confirmOverwrite = true
                confirmRound++
                fillNeedConfirmMessage
            }
            LiveFillResult.REJECTED_STALE -> {
                confirmOverwrite = false
                fillStaleCopiedMessage
            }
            LiveFillResult.COPIED -> {
                confirmOverwrite = false
                fillDraftCopiedMessage
            }
            LiveFillResult.NO_DRAFT -> fillDraftMissingMessage
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }) {
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.width(IntrinsicSize.Min)) {
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    fadeIn(tween(LiveMotion.FastMs)) togetherWith fadeOut(tween(LiveMotion.MicroMs))
                },
                label = "fillLabel",
            ) { text ->
                Text(text = text, fontSize = 14.sp, color = tokens.accent)
            }
            AnimatedVisibility(
                visible = confirmOverwrite,
                enter = fadeIn(tween(LiveMotion.MicroMs)),
                exit = fadeOut(tween(LiveMotion.MicroMs)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(confirmProgress.value.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(tokens.accent, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}
