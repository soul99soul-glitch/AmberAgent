package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * Whisper 主题 token —— 取自 Anthropic Design "Refined Chat Screens" 设计稿
 * (/tmp/amber-design/themes.jsx THEME_WHISPER)。仅供 chat 页使用，不污染全局
 * WorkspaceColors。
 */
@Immutable
object WhisperTokens {
    val accent = Color(0xFF0E9CEB)        // sky blue, think header / accent
    val accentDeep = Color(0xFF0282D6)
    val accentSoft = Color(0xFFE0F2FD)
    val accentTint = Color(0xFFCFE6F9)
    val sendBg = Color(0xFF4EA8E8)        // clear sky blue, friendly
    val sendArrow = Color.White
    val sendHalo = Color(0x8C4EA8E8)      // 55% alpha for halo
    val ink = Color(0xFF0F1419)
    val inkSoft = Color(0xFF5B6573)
    val inkFaint = Color(0xFFA8AFBA)
    val hair = Color(0x140F1419)          // rgba(15,20,25,0.08)
    val surface = Color(0xFFFFFFFF)
    val surfaceEdge = Color(0x0D0F1419)   // rgba(15,20,25,0.05)
    val sheetBackdrop = Color(0x2E0F1419) // rgba(15,20,25,0.18)
    val searchBarBg = Color(0x0A0F1419)   // rgba(15,20,25,0.04)
    val dragHandle = Color(0x2E0F1419)    // rgba(15,20,25,0.18)
    // chat / convo tokens
    val userBubble = Color(0xFFF2F4F7)
    val userBubbleEdge = Color(0x0F0F1419) // rgba(15,20,25,0.06)
    val modelStatusDot = Color(0xFF5DBE8A) // 6px 绿点 status
    // tool pill (chat1.md L591: 纯灰底 + 浅天蓝描边 + 蓝色文字/图标/完成)
    val toolPillBg = Color(0xFFF4F4F4)
    val toolPillEdge = Color(0x0D0F1419)   // rgba(15,20,25,0.05)
    val toolLabelInk = inkSoft
    val toolIconInk = accent
    val toolDoneBg = accent
    val thinkRule = Color(0x720E9CEB)      // accent @ 45%
}

/**
 * Bottom bloom halo —— Gemini 风格的天蓝色辐射光晕，从屏幕底部 110% 处升起。
 * 放在 Scaffold 容器后面、所有内容下方；空白态显示，对话页可关闭。
 *
 * 设计来源：themes.jsx WHISPER.halo
 *   radial-gradient(120% 38% at 50% 110%,
 *     rgba(120,170,240,0.55) 0%,
 *     rgba(120,170,240,0.25) 35%,
 *     rgba(120,170,240,0.08) 65%,
 *     rgba(120,170,240,0) 90%)
 */
@Composable
fun WhisperBottomBloom(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
) {
    // Theme-aware bloom：从 LocalChatTheme.current 读 bloomCore/Secondary/Highlight，
    // 自动跟随 Whisper / Plain / Paper / Midnight 切换。
    val theme = LocalChatTheme.current
    val core = theme.bloomCore
    val secondary = theme.bloomSecondary
    val accent2 = theme.bloomHighlight
    val themeIntensity = intensity * theme.bloomMaxAlpha

    // 用户反馈："底部 1/6 到 1/3 范围内呼吸 + 像波浪一样左右流动"。
    // 主光晕中心放在屏幕底边 (cy = h*1.0)，radius ≈ h*0.30；
    // 使得 0% 色边界恰好落在 y = h*0.70（即从底起 1/3 位置）；
    // 而 50% 色边界落在 y = h*0.85（从底起 1/6 位置，是呼吸最强处）。
    // 用户反馈："蓝色应该满底均匀，流动是波纹叠在上面，不是整片蓝左右挪"。
    // 架构：(1) 基础蓝层完全静态 + 居中对称 + 仅半径/alpha 呼吸；
    //       (2) 顶部加几个小光斑做波纹流动，绝不让基础蓝缺失。
    val transition = rememberInfiniteTransition(label = "bloomFlow")
    // 基础层半径呼吸：0.78 ↔ 1.25 大幅伸缩（原 0.92↔1.05 律动太微弱）
    val radiusBreath by transition.animateFloat(
        initialValue = 0.78f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radiusBreath",
    )
    // 基础层 alpha 呼吸：0.55 ↔ 1.30 显著明暗对比（原 0.88↔1.12 看不出脉冲）
    val alphaBreath by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alphaBreath",
    )
    // 波纹高光层相位（左右流动但不影响 base 满底）
    val rippleA by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(5_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rippleA",
    )
    val rippleB by transition.animateFloat(
        initialValue = (Math.PI).toFloat(), targetValue = (3 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(6_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rippleB",
    )
    val rippleC by transition.animateFloat(
        initialValue = (Math.PI / 2).toFloat(),
        targetValue = (2.5 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rippleC",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val effective = themeIntensity * alphaBreath

        // ── 层 0：顶部 ambient halo ── (themes.jsx halo 第 2 层 radial-gradient at 50% 0%)
        // Paper / Midnight 有，给整个 bg 加暖意 / 冷靛蓝氛围；Whisper / Plain topHaloAlpha=0 跳过。
        // 静态不呼吸 —— 顶层是环境光，不能跟着底部波纹一起脉冲，否则视觉嘈杂。
        if (theme.topHaloAlpha > 0f) {
            // CSS 原 radial-gradient(90% 40% at 50% 0%, color X%, transparent 70%)
            // 90% 宽 × 40% 高 → 椭圆，但 Compose radial 只能圆。
            // 取较大的轴半径 (h * 0.45) 平衡覆盖范围。
            drawRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to theme.topHaloCore.copy(alpha = theme.topHaloAlpha * intensity),
                        0.70f to Color.Transparent,
                    ),
                    center = Offset(w / 2f, 0f),
                    radius = h * 0.45f,
                ),
                topLeft = Offset.Zero,
                size = Size(w, h),
            )
        }

        // ── 层 1：基础底色 ——按主题 bloomHeightFrac 决定垂直覆盖比例 ──
        // Whisper 0.38 / Plain 0.50 / Paper 0.55 / Midnight 0.55 (themes.jsx halo 1st gradient)
        // 中心 y = h*1.0（贴底）、x = w/2（不漂移）、radius = h * bloomHeightFrac ± 呼吸
        val baseRadius = h * theme.bloomHeightFrac * radiusBreath
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to core.copy(alpha = 0.80f * effective),
                    0.40f to core.copy(alpha = 0.48f * effective),
                    0.70f to core.copy(alpha = 0.18f * effective),
                    0.90f to core.copy(alpha = 0.05f * effective),
                    1.00f to Color.Transparent,
                ),
                center = Offset(w / 2f, h * 1.00f),
                radius = baseRadius,
            ),
            topLeft = Offset.Zero,
            size = Size(w, h),
        )

        // ── 层 2：辅助横向带 —— 与 base 同色对称分布，加强"满底"感 ──
        // 用一对镜像的偏中心 bloom，永远左右等亮，不会出现"一边白一边蓝"。
        val sideOffset = w * 0.30f
        // 副 bloom 半径按主题 fraction 略小，保持与主 bloom 比例
        val sideRadius = h * (theme.bloomHeightFrac * 0.62f) * radiusBreath
        listOf(w / 2f - sideOffset, w / 2f + sideOffset).forEach { cx ->
            drawRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to secondary.copy(alpha = 0.35f * effective),
                        0.55f to secondary.copy(alpha = 0.12f * effective),
                        1.00f to Color.Transparent,
                    ),
                    center = Offset(cx, h * 1.02f),
                    radius = sideRadius,
                ),
                topLeft = Offset.Zero,
                size = Size(w, h),
            )
        }

        // ── 层 3：波纹高光斑 —— 3 个小亮点在 base 之上"游动"，
        // 但每个亮点本身体积小、范围限制在底部，因此 base 的满底不被掏空。
        val ripples = listOf(
            Triple(rippleA, 0.18f, 1.05f),  // (相位, 振幅 fraction, cy fraction)
            Triple(rippleB, 0.24f, 1.02f),
            Triple(rippleC, 0.15f, 1.08f),
        )
        ripples.forEach { (phase, amp, cyFrac) ->
            val cx = w / 2f + w * amp * sin(phase)
            val cy = h * cyFrac
            val r = h * 0.10f
            drawRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to accent2.copy(alpha = 0.45f * effective),
                        0.60f to accent2.copy(alpha = 0.12f * effective),
                        1.00f to Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                topLeft = Offset.Zero,
                size = Size(w, h),
            )
        }
    }
}
