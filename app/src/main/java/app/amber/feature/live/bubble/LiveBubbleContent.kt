package app.amber.feature.live.bubble

import android.widget.Toast
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.live.LiveFillResult
import app.amber.feature.live.LiveModeUiState
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

/**
 * 气泡内容（精简版交互，用户 2026-06-11 决策）：
 * 收起 = 44dp 三态点（待命灰 / 分析中呼吸 / 有新结果亮强调色）；
 * 点开 = 280dp 卡片（结论 + ≤3 要点 + 草稿 + 填入/立即分析）；长按点 = 退出伴随。
 */
@Composable
fun LiveBubbleContent(
    state: LiveModeUiState,
    onFillDraft: () -> LiveFillResult,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onSizeChanged: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var lastSeenMillis by remember { mutableLongStateOf(0L) }
    val hasFreshResult = state.card != null && state.lastUpdatedAtMillis > lastSeenMillis

    if (!expanded) {
        // ── 收起态：三态点 ──
        val pulse = rememberInfiniteTransition(label = "bubblePulse")
        val pulseAlpha by pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "bubblePulseAlpha",
        )
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
                        lastSeenMillis = state.lastUpdatedAtMillis
                        onSizeChanged()
                    },
                    onLongClick = onStop,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer { alpha = if (state.analyzing) pulseAlpha else 1f }
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
    } else {
        // ── 展开态：精简卡片 ──
        // 窗口尺寸瞬切（WRAP_CONTENT 一次 relayout），卡片自身做 130ms scale+fade
        // 弹出（从贴边的右上角长出），既快又有"从气泡里弹出来"的感觉。
        val card = state.card
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            appeared = true
            onSizeChanged()
        }
        val pop by animateFloatAsState(
            targetValue = if (appeared) 1f else 0.85f,
            animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
            label = "bubblePop",
        )
        val fade by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(durationMillis = 130),
            label = "bubbleFade",
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
                        .background(if (state.analyzing) tokens.accent else tokens.ink3),
                )
                Text(
                    text = state.statusText.ifBlank { "Amber 伴随" },
                    style = LocalAmberType.current.meta,
                    color = tokens.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "收起",
                    style = LocalAmberType.current.meta,
                    color = tokens.ink4,
                )
            }

            if (card == null) {
                Text(
                    text = "还没有分析结果",
                    style = LocalAmberType.current.secondary,
                    color = tokens.ink3,
                )
            } else {
                Text(
                    text = card.watching.ifBlank { "不确定" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                )
                card.keyPoints.take(3).forEach { point ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "·", color = tokens.ink3, fontSize = 13.sp)
                        Text(text = point, fontSize = 13.sp, color = tokens.ink2)
                    }
                }
                val draft = card.suggestions.firstOrNull()?.takeIf { it.isNotBlank() }
                if (draft != null && state.completedAction == "写回复") {
                    Text(
                        text = draft,
                        fontSize = 13.sp,
                        color = tokens.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.surface2)
                            .padding(8.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val draftAvailable = card != null &&
                    (card.suggestions.firstOrNull()?.isNotBlank() == true || card.watching.isNotBlank())
                if (draftAvailable) {
                    TextButton(onClick = {
                        val message = when (onFillDraft()) {
                            LiveFillResult.FILLED -> "已填入，发送请自己按"
                            LiveFillResult.COPIED -> "没找到输入框，已复制"
                            LiveFillResult.NO_DRAFT -> "还没有可填入的草稿"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }) {
                        Text("填入", fontSize = 13.sp, color = tokens.accent)
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onRefresh) {
                    Text("立即分析", fontSize = 13.sp, color = tokens.accent)
                }
            }
        }
    }
}
