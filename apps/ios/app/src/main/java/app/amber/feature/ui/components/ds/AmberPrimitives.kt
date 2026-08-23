package app.amber.feature.ui.components.ds

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

/**
 * Amber · "Terminal × Modern" design-system primitives. Stateless, token-driven building blocks
 * (oc-amber.css §6.1 + design §6.2). All read [LocalAmberTokens] / [LocalAmberType]; never a raw hex.
 */

/** Press feedback: scale(.975) on press. Add to every tappable control (design §5 `.pressable`). */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, label = "pressable")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/** Default container: surface + hairline + 14dp radius (oc-amber `.card`). */
@Composable
fun AmberCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalAmberTokens.current
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface)
            .border(1.dp, t.line, RoundedCornerShape(14.dp)),
        content = content,
    )
}

/** 1dp hairline divider (oc-amber `.hair`). */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    Box(modifier.fillMaxWidth().height(1.dp).background(t.line))
}

/** Primary action: ink fill, bg text, 15dp radius (oc-amber `.btn-ink`; superellipse → rounded). */
@Composable
fun BtnInk(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier
            .clip(RoundedCornerShape(15.dp))
            .background(t.ink)
            .pressable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = t.bg, style = type.body.copy(fontWeight = FontWeight.SemiBold)) }
}

/** Accent action: accent fill, accent-ink text (oc-amber `.btn-accent`). */
@Composable
fun BtnAccent(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier
            .clip(RoundedCornerShape(15.dp))
            .background(t.accent)
            .pressable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = t.accentInk, style = type.body.copy(fontWeight = FontWeight.SemiBold)) }
}

/** Live dot with breathing halo (oc-amber `.dot`); [idle] = grey, no halo. Signal-green = liveness. */
@Composable
fun LiveDot(modifier: Modifier = Modifier, idle: Boolean = false, dotSize: Dp = 7.dp) {
    val t = LocalAmberTokens.current
    val color = if (idle) t.ink4 else t.signal
    Box(modifier.size(dotSize * 2.2f), contentAlignment = Alignment.Center) {
        if (!idle) {
            val tr = rememberInfiniteTransition(label = "dotHalo")
            val p by tr.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
                label = "p",
            )
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        val s = 1f + p * 1.1f
                        scaleX = s
                        scaleY = s
                        alpha = 0.35f * (1f - p)
                    }
                    .background(color, CircleShape)
            )
        }
        Box(Modifier.size(dotSize).background(color, CircleShape))
    }
}

/** Blinking terminal cursor (oc-amber `.cursor`). Defaults to accent; pass [color] for ink/signal. */
@Composable
fun BlinkingCursor(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    width: Dp = 8.dp,
    height: Dp = 18.dp,
) {
    val t = LocalAmberTokens.current
    val c = if (color == Color.Unspecified) t.accent else color
    val tr = rememberInfiniteTransition(label = "cursor")
    val on by tr.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1050
                1f at 0
                1f at 524
                0f at 525
                0f at 1049
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "blink",
    )
    Box(
        modifier
            .size(width, height)
            .graphicsLayer { alpha = on }
            .background(c, RoundedCornerShape(1.dp))
    )
}

/** Mono uppercase eyebrow prefixed with an accent `//` (oc-amber section label). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("//", style = type.eyebrow, color = t.accent)
        Text(" " + text.uppercase(), style = type.eyebrow, color = t.ink3)
    }
}
