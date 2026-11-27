package app.amber.feature.ui.pages.personalagent

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.LocalAmberTokens
import coil3.compose.AsyncImage

/**
 * Warm editorial circular persona medallion aligned with iOS `AmberAgentPersonaMedallionView`
 * and HarmonyOS `AmberPersonaMedallion`.
 *
 * Displays a custom Soul portrait if provided, otherwise draws the built-in warm editorial
 * Amber Fox mascot on Canvas with a subtle breathing halo while generating.
 */
@Composable
fun AmberPersonaMedallion(
    diameter: Dp = 64.dp,
    generating: Boolean = false,
    customAvatarUri: String? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val accent = tokens.accent
    val ringColor = if (tokens.isDark) tokens.floatingOutline else Color.White.copy(alpha = 0.9f)

    val infiniteTransition = rememberInfiniteTransition(label = "personaMedallion")
    val haloPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (generating) 1300 else 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "personaHaloPulse",
    )
    val idleTilt by infiniteTransition.animateFloat(
        initialValue = if (generating) -3.2f else -1.1f,
        targetValue = if (generating) 3.2f else 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (generating) 1400 else 4200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "personaTilt",
    )

    Box(
        modifier = modifier.size(diameter + if (generating) 8.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (generating) {
            Box(
                modifier = Modifier
                    .size(diameter + 8.dp)
                    .graphicsLayer {
                        val scale = 0.98f + 0.055f * haloPulse
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.20f + 0.24f * haloPulse
                    }
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f))
                    .border(1.25.dp, accent.copy(alpha = 0.48f), CircleShape),
            )
        }

        val medallionShadow = if (tokens.isDark) 0.dp else 6.dp
        Box(
            modifier = Modifier
                .size(diameter)
                .shadow(
                    elevation = medallionShadow,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.14f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                )
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (tokens.isDark) tokens.surface2 else Color(0xFFFDFBF7),
                            if (tokens.isDark) tokens.surface else Color(0xFFF1EAE0),
                        ),
                    ),
                )
                .border(
                    width = 1.5.dp,
                    color = if (generating) accent.copy(alpha = 0.55f) else ringColor,
                    shape = CircleShape,
                )
                .border(
                    width = 0.5.dp,
                    color = tokens.floatingOutline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val avatar = customAvatarUri?.trim()?.takeIf { it.isNotEmpty() }
            if (avatar != null) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            } else {
                AmberFoxCanvas(
                    accent = accent,
                    cream = if (tokens.isDark) Color(0xFFF5F0E8) else Color(0xFFFBF9F5),
                    ink = if (tokens.isDark) Color(0xFF14110E) else tokens.ink,
                    tintGlow = accent.copy(alpha = if (generating) 0.18f else 0.10f),
                    headTiltDegrees = idleTilt,
                    headBobFraction = if (generating) (haloPulse - 0.5f) * 0.028f else (haloPulse - 0.5f) * 0.01f,
                    modifier = Modifier.fillMaxSize().padding(diameter * 0.035f),
                )
            }
        }
    }
}

@Composable
private fun AmberFoxCanvas(
    accent: Color,
    cream: Color,
    ink: Color,
    tintGlow: Color,
    headTiltDegrees: Float,
    headBobFraction: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.53f + h * headBobFraction

        // Subtle warm radial glow behind mascot
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tintGlow, Color.Transparent),
                center = Offset(cx, h * 0.45f),
                radius = w * 0.48f,
            ),
            radius = w * 0.48f,
            center = Offset(cx, h * 0.45f),
        )

        rotate(degrees = headTiltDegrees, pivot = Offset(cx, cy)) {
            // Left & right ears (adapted from iOS AmberAgentPersonaCanvasView)
            for (sign in listOf(-1f, 1f)) {
                val tipX = cx + sign * w * 0.28f
                val tipY = cy - h * 0.34f
                val ear = Path().apply {
                    moveTo(cx + sign * w * 0.25f, cy - h * 0.04f)
                    quadraticTo(
                        cx + sign * w * 0.32f,
                        cy - h * 0.20f,
                        tipX,
                        tipY,
                    )
                    quadraticTo(
                        cx + sign * w * 0.15f,
                        cy - h * 0.29f,
                        cx + sign * w * 0.06f,
                        cy - h * 0.15f,
                    )
                    close()
                }
                drawPath(ear, color = accent)

                val innerEar = Path().apply {
                    moveTo(cx + sign * w * 0.21f, cy - h * 0.10f)
                    lineTo(tipX - sign * w * 0.02f, tipY + h * 0.04f)
                    lineTo(cx + sign * w * 0.11f, cy - h * 0.16f)
                    close()
                }
                drawPath(innerEar, color = cream.copy(alpha = 0.90f))
            }

            // Head silhouette
            val headW = w * 0.56f
            val headH = h * 0.43f
            drawRoundRect(
                color = accent,
                topLeft = Offset(cx - headW * 0.5f, cy - headH * 0.48f),
                size = Size(headW, headH),
                cornerRadius = CornerRadius(headH * 0.46f, headH * 0.46f),
            )

            // Cream cheek / muzzle
            val cheekW = w * 0.46f
            val cheekH = h * 0.23f
            drawRoundRect(
                color = cream,
                topLeft = Offset(cx - cheekW * 0.5f, cy - cheekH * 0.08f),
                size = Size(cheekW, cheekH),
                cornerRadius = CornerRadius(cheekH * 0.48f, cheekH * 0.48f),
            )

            // Eyes with specular highlight
            val eyeY = cy - h * 0.04f
            val eyeOffset = w * 0.11f
            val eyeR = (w * 0.042f).coerceAtLeast(2.2f)
            for (sign in listOf(-1f, 1f)) {
                val ex = cx + sign * eyeOffset
                drawCircle(
                    color = ink,
                    radius = eyeR,
                    center = Offset(ex, eyeY),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.92f),
                    radius = (eyeR * 0.36f).coerceAtLeast(1f),
                    center = Offset(ex - eyeR * 0.28f, eyeY - eyeR * 0.30f),
                )
            }

            // Nose dot
            val noseR = (w * 0.028f).coerceAtLeast(1.8f)
            drawCircle(
                color = ink,
                radius = noseR,
                center = Offset(cx, cy + h * 0.045f),
            )
        }
    }
}
