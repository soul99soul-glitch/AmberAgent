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
 *   theme.accent / theme.sendBg / theme.bloomCore / ...
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
    val sendHalo: Color,
    // bottom ambient bloom (multi-layer flowing radial gradient)
    val bloomCore: Color,        // primary bloom color
    val bloomSecondary: Color,   // mid-layer accent
    val bloomHighlight: Color,   // top highlight
    val bloomMaxAlpha: Float,    // intensity ceiling at peak
    val showBloomInConvo: Boolean, // some themes keep faint bloom in convo
    // user bubble
    val userBubble: Color,
    val userBubbleEdge: Color,
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
    // composer pill shadow color (depth indicator; needs theme-aware contrast)
    val composerShadow: Color,
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
    // top ambient halo (themes.jsx halo 第 2 层 radial-gradient at 50% 0%)
    // Paper/Midnight 设计稿有；Whisper/Plain 没有顶层，默认 Transparent/0 = 不绘制
    val topHaloCore: Color = Color.Transparent,
    val topHaloAlpha: Float = 0f,
    // bottom bloom 垂直覆盖比例 (themes.jsx halo 第 1 层 radial-gradient 高度参数)
    //   Whisper 38% / Plain 50% / Paper 55% / Midnight 55%
    // 之前所有主题硬编码 0.32 (基本是 Whisper)，导致 Paper/Midnight 底光晕只占 1/3 高
    // 中段还是白/黑，跟设计稿不符
    val bloomHeightFrac: Float = 0.38f,
    // hero greeting 字体参数 (themes.jsx heroSize/heroWeight/heroLetter)
    //   Whisper 26/500/0.2 / Plain 25/500/0.6 / Paper 26/600/0.5 / Midnight 26/500/0.2
    val heroSize: Int = 26,
    val heroWeight: Int = 500,
    val heroLetter: Float = 0.2f,
    // context ring 阈值色 (convo-agent.jsx ContextRing) —— Paper 是棕色阶梯，其他默认蓝/黄/红
    val contextEmpty: Color = Color(0xFFD6D9DE),
    val contextLow: Color = Color(0xFF3D8FD4),
    val contextMid: Color = Color(0xFFE6A23C),
    val contextHigh: Color = Color(0xFFD9534F),
    val contextTrack: Color = Color(0x1A0F1419),
    // popover 浮层底色 (Midnight 专用 #1A1F2A，其他用 surface 即可)
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
 * so existing `LocalChatTheme.current.xxx` reads keep working during migration. Decoration
 * fields (bloom / halo / send-halo) are flattened to transparent: the "Terminal × Modern"
 * design forbids them (anti-goals §1). These flattened fields are removed once their consumers
 * (SendOrb / WhisperHalo / Background) are deleted in Phase 1.3.
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
    sendHalo = Color.Transparent,
    bloomCore = Color.Transparent,
    bloomSecondary = Color.Transparent,
    bloomHighlight = Color.Transparent,
    bloomMaxAlpha = 0f,
    showBloomInConvo = false,
    userBubble = userBg,
    userBubbleEdge = line,
    modelStatusDot = signal,
    modelLogoBg = surface2,
    toolPillBg = codeBg,
    toolPillEdge = line,
    toolLabelInk = accent,
    toolIconInk = accent,
    toolDoneBg = accent,
    toolDoneBadgeInk = accentInk,
    thinkRule = line2,
    thinkHeaderInk = accent,
    thinkBodyInk = ink3,
    sheetBackdrop = if (isDark) Color(0x99000000) else Color(0x52000000),
    dragHandle = line2,
    searchBarBg = surface2,
    composerShadow = Color.Transparent,
    outlineStrong = line2,
    outlineSoft = line,
    containerLowest = bg,
    containerLow = surface,
    containerMid = surface2,
    containerHigh = surface2,
    containerHighest = raised,
    onAccent = accentInk,
    isDark = isDark,
    topHaloCore = Color.Transparent,
    topHaloAlpha = 0f,
    bloomHeightFrac = 0f,
    contextEmpty = line2,
    contextLow = accent,
    contextMid = accent,
    contextHigh = accent,
    contextTrack = line,
    popoverBg = surface,
    widgetCanvas = surface2,
    widgetCanvasBorder = line,
)
