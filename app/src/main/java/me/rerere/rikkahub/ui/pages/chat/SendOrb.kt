package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01

/**
 * SendOrb —— 48dp 圆形发送按钮，外加双层呼吸光晕。
 *
 * 设计来源：phone-screen.jsx SendButton
 *   - outer halo: inset:-14（即半径 = 48/2 + 14 = 38px），3.6s tween，
 *     scale 0.95↔1.35, opacity 0.30↔0.70
 *   - inner halo: inset:-4（半径 = 48/2 + 4 = 28px），2.8s tween，
 *     scale 1.0↔1.18, opacity 0.55↔0.95
 *   - core orb: 48px 圆 + shadow + sky-blue background + 白色 24px 向上箭头
 *
 * 按 reduced motion 偏好关闭呼吸：检测 [breathingEnabled]。
 *
 * 三态：
 *   - active（!isEmpty 或 isQueueSend）：sendBg 满色 + 呼吸光晕
 *   - loading & empty：amber + 取消图标，光晕变暖
 *   - idle (state.isEmpty 且非 loading)：sky-blue 软色 + 呼吸光晕但更弱
 */
@Composable
fun SendOrb(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isEmpty: Boolean,
    loading: Boolean,
    breathingEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val isStopState = loading && isEmpty
    val theme = LocalChatTheme.current

    // chat1.md L257 final: "按钮回到干净的纯色圆形"——非 stop 态用 theme.sendBg + theme.sendArrow；
    // 仅 stop 态(loading & empty) 切到 amber。Empty 状态不再褪色。
    val targetCore = when {
        isStopState && theme.isDark -> Color(0xFF8F6535)
        isStopState -> Color(0xFFE5B567)            // amber stop
        else -> theme.sendBg
    }
    val targetHalo = when {
        isStopState && theme.isDark -> Color(0x668F6535)
        isStopState -> Color(0xCCE5B567)
        else -> theme.sendHalo
    }
    val targetIconTint = when {
        isStopState && theme.isDark -> Color(0xFF351D10)
        isStopState -> Color(0xFFD37A00)
        else -> theme.sendArrow
    }
    val coreColor by animateColorAsState(targetCore, label = "orbCore")
    val haloColor by animateColorAsState(targetHalo, label = "orbHalo")
    val iconTint by animateColorAsState(targetIconTint, label = "orbIcon")

    Box(
        modifier = modifier.size(76.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (breathingEnabled) {
            // V3 review P3: 4 个 rememberInfiniteTransition.animateFloat 提到独立 composable,
            // breathingEnabled=false 时整个不 enter composition → 不产生持续 state changes /
            // recomposition (跟 WhisperBottomBloom 类似问题).
            BreathingHalos(haloColor = haloColor)
        }

        // The orb itself — 48dp, clickable, opaque core
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(coreColor)
                .combinedClickable(
                    enabled = loading || !isEmpty,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val icon: ImageVector = if (isStopState) HugeIcons.Cancel01 else HugeIcons.ArrowUp02
            Icon(
                imageVector = icon,
                contentDescription = if (isStopState) "stop" else "send",
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun BreathingHalos(haloColor: Color) {
    val transition = rememberInfiniteTransition(label = "orbBreath")
    val outerScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.50f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "outerScale",
    )
    val outerAlpha by transition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "outerAlpha",
    )
    val innerScale by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "innerScale",
    )
    val innerAlpha by transition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "innerAlpha",
    )
    Box(contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(76.dp)
                .graphicsLayer {
                    scaleX = outerScale
                    scaleY = outerScale
                    alpha = outerAlpha
                },
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to haloColor,
                        0.70f to Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
        Canvas(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = innerScale
                    scaleY = innerScale
                    alpha = innerAlpha
                },
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to haloColor,
                        0.65f to Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
    }
}
