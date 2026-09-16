package app.amber.feature.bubble

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

/**
 * 任务气泡内容（与 LiveBubbleContent 同一交互骨架）：
 * 收起 = 44dp 状态点（运行呼吸 / 等待批准快闪 / 完成 / 失败）；
 * 点开 = 280dp 卡片（状态 + 当前步骤 + 回复预览 + 批准/拒绝 + 停止/打开会话）；
 * 长按点 = 停止任务。
 */
@Composable
fun AgentTaskBubbleContent(
    state: AgentBubbleUiState,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onStop: () -> Unit,
    onOpenConversation: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onSizeChanged: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val errorColor = MaterialTheme.colorScheme.error
    var expanded by remember { mutableStateOf(false) }

    if (!expanded) {
        // ── 收起态：状态点 ──
        val waiting = state.phase == AgentBubblePhase.RUNNING && state.waitingApproval
        val pulse = rememberInfiniteTransition(label = "taskBubblePulse")
        val pulseAlpha by pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(if (waiting) 350 else 700),
                RepeatMode.Reverse,
            ),
            label = "taskBubblePulseAlpha",
        )
        val dotColor = when {
            state.phase == AgentBubblePhase.FINISHED && state.failed -> errorColor
            state.phase == AgentBubblePhase.FINISHED -> tokens.accent
            state.waitingApproval -> tokens.accent
            state.phase == AgentBubblePhase.RUNNING -> tokens.accent
            else -> tokens.ink3
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(tokens.surface)
                .border(1.dp, tokens.line2, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            onDrag(delta.x, delta.y)
                        },
                        onDragEnd = { onDragEnd() },
                    )
                }
                .combinedClickable(
                    onClick = {
                        expanded = true
                        onSizeChanged()
                    },
                    onLongClick = onStop,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val settled = state.phase == AgentBubblePhase.FINISHED
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer { alpha = if (settled) 1f else pulseAlpha }
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    } else {
        // ── 展开态：任务卡片 ──
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            appeared = true
            onSizeChanged()
        }
        val pop by animateFloatAsState(
            targetValue = if (appeared) 1f else 0.85f,
            animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
            label = "taskBubblePop",
        )
        val fade by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(durationMillis = 130),
            label = "taskBubbleFade",
        )
        Column(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = pop
                    scaleY = pop
                    alpha = fade
                    transformOrigin = TransformOrigin(1f, 0f)
                }
                .width(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.surface)
                .border(1.dp, tokens.line2, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val statusText = when {
                state.phase == AgentBubblePhase.RUNNING && state.waitingApproval && state.waitingAskUser ->
                    stringResource(R.string.task_bubble_status_waiting_reply)
                state.phase == AgentBubblePhase.RUNNING && state.waitingApproval ->
                    stringResource(R.string.task_bubble_status_waiting)
                state.phase == AgentBubblePhase.FINISHED && state.failed ->
                    stringResource(R.string.task_bubble_status_failed)
                state.phase == AgentBubblePhase.FINISHED ->
                    stringResource(R.string.task_bubble_status_done)
                else -> stringResource(R.string.task_bubble_status_running)
            }
            val running = state.phase == AgentBubblePhase.RUNNING
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            expanded = false
                            onSizeChanged()
                        },
                        onLongClick = onStop,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                state.phase == AgentBubblePhase.FINISHED && state.failed -> errorColor
                                running -> tokens.accent
                                else -> tokens.ink3
                            }
                        ),
                )
                Text(
                    text = statusText,
                    style = LocalAmberType.current.meta,
                    color = tokens.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.task_bubble_collapse),
                    style = LocalAmberType.current.meta,
                    color = tokens.ink4,
                )
            }

            if (running && state.stepTitle.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "·", color = tokens.accent, fontSize = 13.sp)
                    Text(
                        text = state.stepTitle,
                        fontSize = 13.sp,
                        color = tokens.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (running && state.waitingApproval && !state.waitingAskUser) {
                Text(
                    text = state.approvalToolTitle.replace('_', ' '),
                    fontSize = 13.sp,
                    color = tokens.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onApprove) {
                        Text(
                            stringResource(R.string.task_bubble_approve),
                            fontSize = 13.sp,
                            color = tokens.accent,
                        )
                    }
                    TextButton(onClick = onDeny) {
                        Text(
                            stringResource(R.string.task_bubble_deny),
                            fontSize = 13.sp,
                            color = tokens.ink3,
                        )
                    }
                }
            }

            if (running && state.waitingApproval && state.waitingAskUser) {
                Text(
                    text = stringResource(R.string.task_bubble_waiting_reply_hint),
                    fontSize = 13.sp,
                    color = tokens.ink2,
                )
            }

            if (state.replyPreview.isNotBlank()) {
                Text(
                    text = state.replyPreview,
                    fontSize = 13.sp,
                    color = tokens.ink,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.surface2)
                        .padding(8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.task_bubble_no_reply),
                    style = LocalAmberType.current.secondary,
                    color = tokens.ink3,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenConversation) {
                    Text(
                        stringResource(R.string.task_bubble_open_conversation),
                        fontSize = 13.sp,
                        color = tokens.accent,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onStop, enabled = running) {
                    Text(
                        stringResource(R.string.task_bubble_stop),
                        fontSize = 13.sp,
                        color = if (running) tokens.ink3 else tokens.ink4,
                    )
                }
            }
        }
    }
}
