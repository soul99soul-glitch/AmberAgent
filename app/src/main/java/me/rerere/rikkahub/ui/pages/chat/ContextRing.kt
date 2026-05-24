package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 22dp Context Ring —— 顶栏右侧轻量进度环 + 点开 usage panel popup。
 *
 * 设计来源 convo-agent.jsx HeaderContextRing + ContextUsagePanel：
 *   - 22dp ring + 2.6dp stroke + leading head dot
 *   - 主题阈值色 (Paper 棕渐变 / 其他蓝-黄-红)
 *   - 点击展开 290dp 浮层：5h 额度 / 周额度 / Context / meta strip
 *   - 浮层位置：top right of screen，箭头指向 ring
 */
@Composable
fun ContextRing(
    used: Int,
    total: Int,
    modifier: Modifier = Modifier,
    // V3: 接真实 token usage. null 时 popup meta strip 显示 "—" 占位.
    // 来源 = lastAssistantMessage.usage; 计算: 本次=totalTokens,
    // 缓存命中=cachedTokens/promptTokens, 速度=totalTokens/elapsedSec.
    lastTurnTotalTokens: Int? = null,
    lastTurnCachedTokens: Int? = null,
    lastTurnPromptTokens: Int? = null,
    lastTurnElapsedMs: Long? = null,
) {
    val v = if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val empty = v <= 0.001f
    val theme = LocalChatTheme.current
    val color = when {
        empty -> theme.contextEmpty
        v < 0.50f -> theme.contextLow
        v < 0.75f -> theme.contextMid
        else -> theme.contextHigh
    }
    val trackColor = theme.contextTrack
    val ringSize = 22.dp
    val strokeDp = 2.6.dp

    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(36.dp)
            // V3: ripple 改圆形, 跟 ring 视觉一致 (默认矩形 ripple 在圆 ring 周围看着错位)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable { expanded = !expanded },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(ringSize)) {
            val s = this.size.width
            val strokePx = strokeDp.toPx()
            val r = (s - strokePx) / 2f - 1f
            val cx = s / 2f
            val cy = s / 2f
            drawCircle(
                color = if (empty) color else trackColor,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = strokePx),
            )
            if (!empty) {
                val sweep = 360f * v
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                if (v < 0.999f) {
                    val headAngle = (v * 2f - 0.5f) * PI.toFloat()
                    val headX = cx + r * cos(headAngle)
                    val headY = cy + r * sin(headAngle)
                    drawCircle(
                        color = color,
                        radius = strokePx / 2f + 0.4f,
                        center = Offset(headX, headY),
                    )
                }
            }
        }

        if (expanded) {
            ContextUsagePopup(
                used = used,
                total = total,
                progressColor = color,
                lastTurnTotalTokens = lastTurnTotalTokens,
                lastTurnCachedTokens = lastTurnCachedTokens,
                lastTurnPromptTokens = lastTurnPromptTokens,
                lastTurnElapsedMs = lastTurnElapsedMs,
                onDismiss = { expanded = false },
            )
        }
    }
}

/**
 * 用量与上下文 popup —— 290dp 宽，从 ring 下方 10dp 弹出，箭头指向 ring。
 *
 * 设计来源 convo-agent.jsx ContextUsagePanel:
 *   - 5h 额度 / 本周额度 (硬编码 placeholder，app 当前没追踪)
 *   - Context 用真实 used/total
 *   - meta strip: 本次 / 缓存命中 / 速度 (placeholder)
 */
@Composable
private fun ContextUsagePopup(
    used: Int,
    total: Int,
    progressColor: Color,
    lastTurnTotalTokens: Int? = null,
    lastTurnCachedTokens: Int? = null,
    lastTurnPromptTokens: Int? = null,
    lastTurnElapsedMs: Long? = null,
    onDismiss: () -> Unit,
) {
    val theme = LocalChatTheme.current
    val popoverBg = if (theme.popoverBg.isSpecified()) theme.popoverBg else theme.surface
    // panel 之前 290dp 顶到屏幕左边. 收到 260dp 留更多 margin.
    val panelWidth = 260
    val panelOffsetTop = 46  // 36dp ring 高 + 10dp gap
    val panelOffsetRight = -8
    // TODO: 当前 5h/周额度是真实接入前的预留 UI. Codex OAuth/Anthropic 限额查询
    //   需要 inject 额度 store + 当前 model 的 provider 类型判断. 接口未铺好之前
    //   默认 false, 只显示 Context 行. 接通 Codex 后传 true + 真数据.
    val quotaSupported = false

    // V3 review P3: 之前 popupPositionProvider 用硬编码 *2.5f 和 *2 作为"density approx",
    // 只在 ~xhdpi (2x) / xxhdpi (2.5x ~ 3x) 设备正确. 改用 LocalDensity 在 PopupPositionProvider
    // 构造前算好 px, 让不同 dpi 设备 (hdpi 1.5x / xxxhdpi 4x / 折叠屏) 都一致.
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val panelOffsetRightPx = (panelOffsetRight * density).toInt()
    val panelGapPx = ((panelOffsetTop - 36 + 10) * density).toInt()
    Popup(
        properties = PopupProperties(focusable = true),
        onDismissRequest = onDismiss,
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // 紧贴 ring 下方 10dp，右边界对齐 ring 右 + 8dp 偏移
                val x = anchorBounds.right + panelOffsetRightPx - popupContentSize.width
                val y = anchorBounds.bottom + panelGapPx
                return IntOffset(
                    x.coerceAtLeast(8),
                    y.coerceAtLeast(0),
                )
            }
        },
    ) {
        // V3: popup 出场动画 — fade + 轻微 scale 从顶右 (ring 位置) 扩开. 避免硬切.
        val animProgress = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) {
            animProgress.animateTo(
                1f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 180,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            )
        }
        Box(
            modifier = Modifier.graphicsLayer {
                alpha = animProgress.value
                val s = 0.94f + 0.06f * animProgress.value
                scaleX = s
                scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.92f, 0f)
            },
        ) {
            // ── 主体卡片 (箭头已删, 不再 padding top)
            Surface(
                modifier = Modifier
                    .width(panelWidth.dp)
                    .shadow(
                        elevation = 18.dp,
                        shape = RoundedCornerShape(18.dp),
                        clip = false,
                        ambientColor = Color(0x330F1419),
                        spotColor = Color(0x330F1419),
                    ),
                shape = RoundedCornerShape(18.dp),
                color = popoverBg,
                border = BorderStroke(1.dp, theme.surfaceEdge),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                ) {
                    Text(
                        text = "用量与上下文",
                        fontSize = 13.sp,
                        color = theme.inkSoft,
                        letterSpacing = 0.3.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )

                    if (quotaSupported) {
                        UsageRow(
                            label = "5 小时额度",
                            value = 0.46f,
                            caption = "46 / 100 次",
                            fillColor = theme.accent,
                            theme = theme,
                        )
                        UsageRow(
                            label = "本周额度",
                            value = 0.18f,
                            caption = "450 / 2,500 次",
                            fillColor = theme.accent,
                            theme = theme,
                        )

                        // 分隔线 (仅在显示限额行时画)
                        Box(
                            modifier = Modifier
                                .padding(top = 12.dp, bottom = 10.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(theme.hair),
                        )
                    }

                    UsageRow(
                        label = "Context",
                        value = if (total > 0) used.toFloat() / total.toFloat() else 0f,
                        caption = "${used}K / ${total}K",
                        fillColor = progressColor,
                        theme = theme,
                    )

                    // meta strip — 改 Column 排版 (label 在上, value 在下), 横向 SpaceBetween,
                    // 避免之前 Row 一行 3 个 label+value 挤换行造成 "速度" 被切到下一行.
                    Box(
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(theme.hair),
                    )
                    Row(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // V3: 本次 = lastAssistantMessage.usage.totalTokens
                        val turnVal = lastTurnTotalTokens?.let { formatTokens(it) + " tok" } ?: "—"
                        MetaStat(label = "本次", value = turnVal, theme = theme)
                        // 缓存命中率: cached / prompt * 100%, 跳过 prompt==0 (避免除零 / N/A 时显示 —)
                        val cacheVal = if ((lastTurnPromptTokens ?: 0) > 0 && lastTurnCachedTokens != null) {
                            val pct = (lastTurnCachedTokens * 100.0 / lastTurnPromptTokens!!).coerceIn(0.0, 100.0)
                            "${pct.toInt()}%"
                        } else "—"
                        MetaStat(label = "缓存命中", value = cacheVal, theme = theme)
                        // 速度: completion_tokens / elapsed_seconds → tok/s. elapsed=null 时 —
                        val speedVal = if (lastTurnElapsedMs != null && lastTurnElapsedMs > 0L && lastTurnTotalTokens != null) {
                            val tps = lastTurnTotalTokens.toDouble() / (lastTurnElapsedMs / 1000.0)
                            "${tps.toInt()} tok/s"
                        } else "—"
                        MetaStat(label = "速度", value = speedVal, theme = theme)
                    }
                }
            }

            // V3: 删除指向 ring 的菱形箭头. 之前 14dp square rotate 45° 想模拟尖角,
            // 但 panel 没把它下半遮住, 整个菱形完整显示成一个奇怪的对话框勾, 而且 anchor
            // 错位让它对不准 ring. panel 直接挂在 ring 下方即可, 不需要 anchor 指示.
        }
    }
}

@Composable
private fun UsageRow(
    label: String,
    value: Float,
    caption: String,
    fillColor: Color,
    theme: ChatTheme,
) {
    val v = value.coerceIn(0f, 1f)
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = theme.ink,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
            )
            Text(
                text = caption,
                fontSize = 11.5.sp,
                color = theme.inkFaint,
                letterSpacing = 0.3.sp,
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x0F0F1419)),  // 6% ink track
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(v)
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(fillColor),
            )
        }
    }
}

private fun formatTokens(n: Int): String {
    return if (n >= 1000) {
        // 2,420 / 12,345 风格
        "%,d".format(n)
    } else n.toString()
}

@Composable
private fun MetaStat(label: String, value: String, theme: ChatTheme) {
    // 改 Column: label 上 value 下. 紧凑且避免横排挤换行.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            color = theme.inkFaint,
            letterSpacing = 0.3.sp,
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = theme.ink,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
        )
    }
}

/** popoverBg=Color.Unspecified 时回落到 surface */
private fun Color.isSpecified(): Boolean = this != Color.Unspecified
