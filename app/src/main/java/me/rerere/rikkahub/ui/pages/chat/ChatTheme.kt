package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * V3 设计稿 4 套主题 token —— themes.jsx 完整搬运 + 适应 Compose 类型。
 *
 * 用法：
 *   val theme = LocalChatTheme.current
 *   theme.accent / theme.sendBg / theme.bloomCore / ...
 *
 * 4 个实例：[WhisperTheme] (默认浅色) / [PlainTheme] (黑白) /
 *           [PaperTheme] (暖纸) / [MidnightTheme] (深色)
 *
 * 设备深色模式可与用户选择独立 —— Provider 层决定是否在 isDarkMode 时强制
 * 切到 [MidnightTheme]。
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

// ── Whisper · 微光（默认浅色，天蓝点缀）─────────────────────────────
val WhisperTheme = ChatTheme(
    name = "微光 · Whisper",
    bg = Color(0xFFFFFFFF),
    paper = Color(0xFFFFFFFF),
    ink = Color(0xFF0F1419),
    inkSoft = Color(0xFF5B6573),
    inkFaint = Color(0xFFA8AFBA),
    hair = Color(0x140F1419),
    surface = Color(0xFFFFFFFF),
    surfaceEdge = Color(0x0D0F1419),
    accent = Color(0xFF0E9CEB),
    accentDeep = Color(0xFF0282D6),
    accentSoft = Color(0xFFE0F2FD),
    accentTint = Color(0xFFCFE6F9),
    sendBg = Color(0xFF4EA8E8),
    sendArrow = Color.White,
    sendHalo = Color(0x8C4EA8E8),
    bloomCore = Color(0xFF7AC0FF),
    bloomSecondary = Color(0xFFA5D6FF),
    bloomHighlight = Color(0xFFBEE3FF),
    bloomMaxAlpha = 0.85f,
    showBloomInConvo = false,
    userBubble = Color(0xFFF2F4F7),
    userBubbleEdge = Color(0x0F0F1419),
    modelStatusDot = Color(0xFF5DBE8A),
    toolPillBg = Color(0xFFF4F4F4),
    toolPillEdge = Color(0x0D0F1419),
    toolLabelInk = Color(0xFF5B6573),
    toolIconInk = Color(0xFF0E9CEB),
    toolDoneBg = Color(0xFF0E9CEB),
    toolDoneBadgeInk = Color.White,
    thinkRule = Color(0x720E9CEB),
    thinkHeaderInk = Color(0xFF0E9CEB),
    thinkBodyInk = Color(0xFF5B6573),
    sheetBackdrop = Color(0x2E0F1419),
    dragHandle = Color(0x2E0F1419),
    searchBarBg = Color(0x0A0F1419),
    composerShadow = Color(0x4D0F1419),
    outlineStrong = Color(0x330F1419),
    outlineSoft = Color(0x1F0F1419),
    containerLowest = Color(0xFFFAFBFC),
    containerLow = Color(0xFFFFFFFF),
    containerMid = Color(0xFFF7F9FB),
    containerHigh = Color(0xFFF1F4F7),
    containerHighest = Color(0xFFE9EDF1),
    onAccent = Color.White,
    // themes.jsx WHISPER halo 第 1 层: radial-gradient(120% 38% at 50% 110%) —— 显式写出默认值
    bloomHeightFrac = 0.38f,
    // bg=#FFFFFF, canvas 用淡蓝 #E5ECF4 (比 bg 深一档 + accent 色相)
    widgetCanvas = Color(0xFFE5ECF4),
)

// ── Plain · 素白（接近零色彩；纯白底，editorial 风）────────────────
val PlainTheme = ChatTheme(
    name = "素白 · Plain",
    bg = Color(0xFFFFFFFF),
    paper = Color(0xFFFFFFFF),
    ink = Color(0xFF0E0E0E),
    inkSoft = Color(0xFF5A5A5A),
    inkFaint = Color(0xFFB0B0B0),
    hair = Color(0x14000000),
    surface = Color(0xFFFFFFFF),
    surfaceEdge = Color(0x1A000000),
    accent = Color(0xFF0E0E0E),
    accentDeep = Color(0xFF0E0E0E),
    accentSoft = Color(0xFFF2F2F2),
    accentTint = Color(0xFFE6E6E6),
    sendBg = Color(0xFF0E0E0E),
    sendArrow = Color.White,
    sendHalo = Color(0x66666666),
    bloomCore = Color(0xFF888888),
    bloomSecondary = Color(0xFFCCCCCC),
    bloomHighlight = Color(0xFFEEEEEE),
    bloomMaxAlpha = 0.10f,
    showBloomInConvo = false,
    userBubble = Color(0xFFF4F4F4),
    userBubbleEdge = Color(0x0D000000),
    modelStatusDot = Color(0xFF7A7A7A),
    toolPillBg = Color(0xFFF4F4F4),
    toolPillEdge = Color(0x0D000000),
    toolLabelInk = Color(0xFF0E0E0E),
    toolIconInk = Color(0xFF5A5A5A),
    toolDoneBg = Color(0xFF0E0E0E),
    toolDoneBadgeInk = Color.White,
    thinkRule = Color(0x2E000000),
    thinkHeaderInk = Color(0xFF5A5A5A),
    thinkBodyInk = Color(0xFF5A5A5A),
    sheetBackdrop = Color(0x33000000),
    dragHandle = Color(0x2E000000),
    searchBarBg = Color(0x0A000000),
    composerShadow = Color(0x4D000000),
    outlineStrong = Color(0x33000000),
    outlineSoft = Color(0x1F000000),
    containerLowest = Color(0xFFFAFAFA),
    containerLow = Color(0xFFFFFFFF),
    containerMid = Color(0xFFF8F8F8),
    containerHigh = Color(0xFFF2F2F2),
    containerHighest = Color(0xFFEBEBEB),
    onAccent = Color.White,
    // themes.jsx PLAIN halo: radial-gradient(110% 50% at 50% 108%)
    bloomHeightFrac = 0.50f,
    // themes.jsx PLAIN heroSize 25 / heroWeight 500 / heroLetter 0.6
    heroSize = 25,
    heroLetter = 0.6f,
    // bg=#FFFFFF, canvas 用浅灰 #EDEEF1 (零色彩, 跟主题保持冷静)
    widgetCanvas = Color(0xFFEDEEF1),
)

// ── Paper · 暖纸（米黄底 + 砖红 accent + 琥珀光晕）────────────────
// 用户反馈"太淡了"->推过头->"过暖了，自适应色温会再加一档"——回到设计稿 spec #FDFAF3，
// 只通过 topHaloAlpha 略加强（0.22→0.30）补 Compose 单 pass 渲染的衰减，不动 bg 本体
val PaperTheme = ChatTheme(
    name = "暖纸 · Paper",
    bg = Color(0xFFFDFAF3),
    paper = Color(0xFFFBF7EE),
    ink = Color(0xFF2A241B),
    inkSoft = Color(0xFF7A6E5C),
    inkFaint = Color(0xFFB8AC95),
    hair = Color(0x1A2A241B),
    surface = Color(0xFFFBF7EE),
    surfaceEdge = Color(0x142A241B),
    accent = Color(0xFFB5683A),
    accentDeep = Color(0xFF7A3F1C),
    accentSoft = Color(0xFFEADFC8),
    accentTint = Color(0xFFF0E0BF),
    sendBg = Color(0xFF3B2418),
    sendArrow = Color(0xFFFBF7EE),
    // 砖红 #B5683A + 低 alpha (~50%), 清淡呼吸感
    sendHalo = Color(0x80B5683A),
    bloomCore = Color(0xFFE6A564),
    bloomSecondary = Color(0xFFF0CBA0),
    bloomHighlight = Color(0xFFF5E5C8),
    bloomMaxAlpha = 0.55f,
    showBloomInConvo = true,
    userBubble = Color(0xFFF7EFDD),
    userBubbleEdge = Color(0x0D2A241B),
    modelStatusDot = Color(0xFFA88D5E),
    toolPillBg = Color(0xFFF7EFDD),
    toolPillEdge = Color(0x102A241B),
    toolLabelInk = Color(0xFFB5683A),
    toolIconInk = Color(0xFFB5683A),
    toolDoneBg = Color(0xFFB5683A),
    toolDoneBadgeInk = Color(0xFFFBF7EE),
    thinkRule = Color(0x4DB5683A),
    thinkHeaderInk = Color(0xFFB5683A),
    thinkBodyInk = Color(0xFF7A6E5C),
    sheetBackdrop = Color(0x382A241B),
    dragHandle = Color(0x332A241B),
    searchBarBg = Color(0x0A2A241B),
    composerShadow = Color(0x4D2A241B),
    outlineStrong = Color(0x33B5683A),
    outlineSoft = Color(0x1A2A241B),
    containerLowest = Color(0xFFFDFAF3),
    containerLow = Color(0xFFFBF7EE),
    containerMid = Color(0xFFF7F0E0),
    containerHigh = Color(0xFFF1E8D2),
    containerHighest = Color(0xFFE8DFC6),
    onAccent = Color(0xFFFBF7EE),
    // themes.jsx Paper halo 第 2 层 spec 0.22，Compose 单 pass 偏弱，提到 0.30
    // 再高用户反馈过暖（"自适应色温会再加一档"），0.30 是 sweet spot
    topHaloCore = Color(0xFFF5E5C8),
    topHaloAlpha = 0.30f,
    // themes.jsx Paper halo 第 1 层：radial-gradient(120% 55% at 50% 108%, rgba(230,165,100,0.28))
    bloomHeightFrac = 0.55f,
    // themes.jsx Paper heroSize 26 / heroWeight 600 / heroLetter 0.5 (W600 比其他更粗，editorial)
    heroSize = 26,
    heroWeight = 600,
    heroLetter = 0.5f,
    // themes.jsx Paper context ring：棕色阶梯（非默认蓝/黄/红）
    contextEmpty = Color(0xFFD8CFBE),
    contextLow = Color(0xFFC9A375),
    contextMid = Color(0xFFA0703F),
    contextHigh = Color(0xFF6A3A18),
    contextTrack = Color(0x1A2A241B),
    // bg=#FDFAF3, canvas 用深一档暖褐 #E6D8B5 (跟 accent 砖红同色相, 比 bg 明显深)
    widgetCanvas = Color(0xFFE6D8B5),
)

// ── Midnight · 夜墨（深色，冷靛蓝点缀）────────────────────────────
val MidnightTheme = ChatTheme(
    name = "夜墨 · Midnight",
    bg = Color(0xFF0B0E14),
    paper = Color(0xFF0B0E14),
    ink = Color(0xFFE8EAEF),
    inkSoft = Color(0xFF8A93A3),
    inkFaint = Color(0xFF4A5160),
    hair = Color(0x1AE8EAEF),
    surface = Color(0xFF15171D),
    surfaceEdge = Color(0x1AE8EAEF),
    accent = Color(0xFF8FA9E0),
    accentDeep = Color(0xFF2A4FA8),
    accentSoft = Color(0x248FA9E0),
    accentTint = Color(0xFFD8E6FF),
    sendBg = Color(0x8C8FA9E0),
    sendArrow = Color.White,
    sendHalo = Color(0x8C8FA9E0),
    bloomCore = Color(0xFF6E8CDC),
    bloomSecondary = Color(0xFF8FA9E0),
    bloomHighlight = Color(0xFFB7D5FF),
    bloomMaxAlpha = 0.40f,
    showBloomInConvo = true,
    // Subagent review: 原 5% 白 (0x0DFFFFFF) 落在 #0B0E14 上对比度逼近 WCAG 下限，
    // 提到 12% 白以保证 user bubble / tool pill 在深色下可读。
    userBubble = Color(0x1FFFFFFF),
    userBubbleEdge = Color(0x14E8EAEF),
    modelStatusDot = Color(0xFF7BD49E),
    toolPillBg = Color(0x1FFFFFFF),
    toolPillEdge = Color(0x14E8EAEF),
    toolLabelInk = Color(0xFF8FA9E0),
    toolIconInk = Color(0xFF8FA9E0),
    toolDoneBg = Color(0xFF8FA9E0),
    toolDoneBadgeInk = Color(0xFF0B0E14),
    thinkRule = Color(0x4D8FA9E0),
    thinkHeaderInk = Color(0xFF8FA9E0),
    thinkBodyInk = Color(0xFF8A93A3),
    sheetBackdrop = Color(0x8C000000),
    dragHandle = Color(0x38FFFFFF),
    searchBarBg = Color(0x0DFFFFFF),
    // Subagent #10: dark bg 上的墨灰阴影看不见，改半透白 → 让 pill 顶部高光
    composerShadow = Color(0x40FFFFFF),
    outlineStrong = Color(0x33E8EAEF),
    outlineSoft = Color(0x1FE8EAEF),
    // Subagent #6: 5 级 surface hierarchy 让 NavDrawer / Card / Sheet 有视觉深度
    containerLowest = Color(0xFF070A0F),
    containerLow = Color(0xFF0B0E14),
    containerMid = Color(0xFF11151D),
    containerHigh = Color(0xFF181D26),
    containerHighest = Color(0xFF20262F),
    // Subagent #3: Midnight accent #8FA9E0 (light indigo) 上配纯白文字对比度低，
    // 改用深底色 #0B0E14 作为 onAccent (FilledButton 上 = 浅蓝底 + 深字)
    onAccent = Color(0xFF0B0E14),
    isDark = true,
    // themes.jsx Midnight halo 第 2 层：radial-gradient(90% 40% at 50% 0%, rgba(60,80,130,0.20))
    topHaloCore = Color(0xFF3C5082),
    topHaloAlpha = 0.20f,
    // themes.jsx Midnight halo 第 1 层：radial-gradient(120% 55% at 50% 108%, rgba(110,140,220,0.40))
    bloomHeightFrac = 0.55f,
    // themes.jsx Midnight context ring：保留默认蓝/黄/红，但 track 在深底上需要更亮才能看见
    contextTrack = Color(0x38E8EAEF), // rgba(232,234,239,0.22)
    // contextEmpty 默认值 0xFFD6D9DE (浅灰) 在 Midnight 深底上几乎是白色——
    // 流式生成时 used=0 → empty=true → ring 渲染成"白色"看着像 loading 占位.
    // Midnight 用浅灰白 22% (跟 contextTrack 同色) 让 empty 时跟 track 同色, 不再"亮白".
    contextEmpty = Color(0x38E8EAEF),
    // themes.jsx Midnight popoverBg #1A1F2A —— 浮层在深底上需要 solid surface
    popoverBg = Color(0xFF1A1F2A),
    // bg=#0B0E14, dark 反向: canvas 用 #1B1C22 比 bg 略浅, 区分出"画板"
    widgetCanvas = Color(0xFF1B1C22),
)

/**
 * 主题枚举 —— 存到 DataStore 的 stringKey 值。
 * Midnight 可被 system dark mode override 触发；其他三个由用户在设置里选。
 */
enum class ChatThemeChoice(val instance: ChatTheme, val displayName: String) {
    WHISPER(WhisperTheme, "微光"),
    PLAIN(PlainTheme, "素白"),
    PAPER(PaperTheme, "暖纸"),
    MIDNIGHT(MidnightTheme, "夜墨"),
}

/** 全局 CompositionLocal —— UI 消费侧读 [LocalChatTheme.current]. */
val LocalChatTheme = staticCompositionLocalOf { WhisperTheme }
