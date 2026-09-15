package app.amber.feature.ui.pages.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.amber.feature.ui.theme.AmberAccents
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.AmberTokens
import app.amber.feature.ui.theme.buildAmberTokens

/**
 * V3 设计稿主题 token —— themes.jsx 完整搬运 + 适应 Compose 类型。
 *
 * 用法：
 *   val theme = LocalChatTheme.current
 *   theme.accent / theme.sendBg / ...
 *
 * 设备深色模式可与用户选择独立 —— Provider 层按当前系统模式解析浅色/深色主题。
 */
@Immutable
data class ChatTheme(
    val name: String,
    // base surface
    val bg: Color,
    val paper: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val hair: Color,
    val surface: Color,
    val surfaceEdge: Color,
    // accent
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val accentTint: Color,
    // send button
    val sendBg: Color,
    val sendArrow: Color,
    // user bubble
    val userBubble: Color,
    val userBubbleEdge: Color,
    // 用户气泡内的文字色（深底→浅字）。之前缺这一项，contentColor 退回到 ink(深) → 黑底黑字。
    val userBubbleInk: Color = Color(0xFFF6F4EE),
    // agent header status dot
    val modelStatusDot: Color,
    // provider/model logo circular background
    val modelLogoBg: Color = Color.Unspecified,
    // tool pill
    val toolPillBg: Color,
    val toolPillEdge: Color,
    val toolLabelInk: Color,
    val toolIconInk: Color,
    val toolDoneBg: Color,
    val toolDoneBadgeInk: Color,
    // thinking strip
    val thinkRule: Color,
    val thinkHeaderInk: Color,
    val thinkBodyInk: Color,
    // sheet
    val sheetBackdrop: Color,
    val dragHandle: Color,
    val searchBarBg: Color,
    // M3 outline / outline-variant (form边界 visibility)
    val outlineStrong: Color,
    val outlineSoft: Color,
    // 5-level surface container hierarchy (M3 elevation depth)
    val containerLowest: Color,
    val containerLow: Color,
    val containerMid: Color,
    val containerHigh: Color,
    val containerHighest: Color,
    // primary readable text on the accent (FAB / FilledButton)
    val onAccent: Color,
    // dark theme flag —— 用于驱动需要"反向"处理的视觉（如 Material shadow 在深色上
    // 会渲染成白晕，需要改走 border + tonal step 替代）
    val isDark: Boolean = false,
    // context ring 阈值色 (convo-agent.jsx ContextRing)
    val contextEmpty: Color = Color(0xFFD6D9DE),
    val contextLow: Color = Color(0xFF3D8FD4),
    val contextMid: Color = Color(0xFFE6A23C),
    val contextHigh: Color = Color(0xFFD9534F),
    val contextTrack: Color = Color(0x1A0F1419),
    // popover 浮层底色（Unspecified 时消费方回落到 surface）
    val popoverBg: Color = Color.Unspecified,
    // SVG / HTML / Slides / 生图 widget 卡片底色 (画板风, 比 bg 略深, 暗色略浅).
    // 取消硬黑边, 用 widgetCanvasBorder 是否非透明决定要不要画 1dp 描边.
    val widgetCanvas: Color = Color.Unspecified,
    val widgetCanvasBorder: Color = Color.Transparent,
)


/** 全局 CompositionLocal —— UI 消费侧读 [LocalChatTheme.current]. 默认值为 Graphite（light + terracotta）。 */
val LocalChatTheme = staticCompositionLocalOf {
    buildAmberTokens(AmberBase.LIGHT, AmberAccents[0].hex).toChatTheme()
}

/**
 * Graphite compatibility adapter (D1) —— build a legacy [ChatTheme] from the new [AmberTokens]
 * so existing `LocalChatTheme.current.xxx` reads keep working during migration.
 */
fun AmberTokens.toChatTheme(): ChatTheme = ChatTheme(
    name = "Graphite",
    bg = bg,
    paper = surface,
    ink = ink,
    inkSoft = ink2,
    inkFaint = ink3,
    hair = line,
    surface = surface,
    surfaceEdge = line,
    accent = accent,
    accentDeep = accent,
    accentSoft = accent.copy(alpha = 0.14f),
    accentTint = accent.copy(alpha = 0.22f),
    sendBg = accent,
    sendArrow = accentInk,
    userBubble = userBg,
    userBubbleEdge = line,
    userBubbleInk = userInk,
    modelStatusDot = signal,
    modelLogoBg = surface2,
    toolPillBg = codeBg,
    toolPillEdge = line,
    toolLabelInk = accent,
    toolIconInk = if (isDark) Color(0xFF8AD39A) else Color(0xFF4B9866),
    toolDoneBg = accent,
    // 2026-06-10: fixed near-black (graphite ink) instead of accentInk — badge glyphs
    // (tick/cross/clock) stay one color across all accents rather than flipping white/black.
    toolDoneBadgeInk = Color(0xFF1B1A17),
    thinkRule = line2,
    thinkHeaderInk = if (isDark) Color(0xFFE4AD61) else Color(0xFFD68A28),
    thinkBodyInk = ink3,
    sheetBackdrop = if (isDark) Color(0x99000000) else Color(0x52000000),
    dragHandle = line2,
    searchBarBg = surface2,
    outlineStrong = line2,
    outlineSoft = line,
    containerLowest = bg,
    containerLow = surface,
    containerMid = surface2,
    containerHigh = surface2,
    containerHighest = raised,
    onAccent = accentInk,
    isDark = isDark,
    contextEmpty = line2,
    contextLow = accent,
    contextMid = accent,
    contextHigh = accent,
    contextTrack = line,
    popoverBg = surface,
    widgetCanvas = surface2,
    widgetCanvasBorder = line,
)
