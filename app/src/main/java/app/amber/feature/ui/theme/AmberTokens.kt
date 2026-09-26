package app.amber.feature.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.amber.core.settings.ThemeDesign
import app.amber.core.settings.themeContrast
import app.amber.core.settings.themeRgb

/**
 * Amber · "Terminal × Modern" graphite design tokens.
 *
 * Faithful transcription of `redesign/oc-amber.css` §2 — the single source of truth in code.
 * Four neutral bases (light/dark/sage/sage-dark). The **accent is independent of the base**
 * (design §2.3): it is injected at theme-build time via [buildAmberTokens], not baked per-base.
 *
 * New / restyled components read [LocalAmberTokens]. The same tokens are mapped onto the M3
 * `ColorScheme` in `AmberAgentTheme` so existing Material 3 widgets reskin automatically.
 */
@Immutable
data class AmberTokens(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val raised: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val ink4: Color,
    val line: Color,
    val line2: Color,
    val userBg: Color,
    val userInk: Color,
    val codeBg: Color,
    val signal: Color,
    val accent: Color,
    val accentInk: Color,
    val isDark: Boolean,
)

enum class AmberBase { LIGHT, DARK, SAGE, SAGE_DARK }

internal const val SIT_TERRACOTTA_ACCENT_HEX = "#B8623A"

// Default warm canvas: iOS "点阵 · 陶土" (AmberTheme.paperLight / paperDark).
// Accent remains independently editable; selecting the built-in restores terracotta.
internal val AmberLight = AmberTokens(
    bg = Color(0xFFEFE7D6), surface = Color(0xFFFFFDF7), surface2 = Color(0xFFF0EBE2), raised = Color(0xFFFFFFFF),
    ink = Color(0xFF1B1813), ink2 = Color(0xFF5B5449), ink3 = Color(0xFF746D62), ink4 = Color(0xFF918A80),
    line = Color(0xFFECE3D6), line2 = Color(0xFFDBCEBC),
    userBg = Color(0xFF1B1813), userInk = Color(0xFFFFFDF7), codeBg = Color(0xFFF0EBE2),
    signal = Color(0xFF5E9C6E), accent = Color(0xFFB8623A), accentInk = Color(0xFFFFFFFF), isDark = false,
)

// ── DARK · warm graphite ───────────────────────────────────────────────────
internal val AmberDark = AmberTokens(
    bg = Color(0xFF14110E), surface = Color(0xFF221E19), surface2 = Color(0xFF2E2822), raised = Color(0xFF2E2822),
    ink = Color(0xFFF5F0E8), ink2 = Color(0xFFC8BDB0), ink3 = Color(0xFFA89888), ink4 = Color(0xFF6E6258),
    line = Color(0xFF2A241E), line2 = Color(0xFF3D342C),
    userBg = Color(0xFFF5F0E8), userInk = Color(0xFF14110E), codeBg = Color(0xFF2E2822),
    signal = Color(0xFF5E9C6E), accent = Color(0xFFB8623A), accentInk = Color(0xFFFFFFFF), isDark = true,
)

// ── SAGE · green-tinted neutrals ───────────────────────────────────────────
internal val AmberSage = AmberTokens(
    bg = Color(0xFFF0F2EA), surface = Color(0xFFF6F8F0), surface2 = Color(0xFFE7EADF), raised = Color(0xFFFFFFFF),
    ink = Color(0xFF1B201A), ink2 = Color(0xFF535A4D), ink3 = Color(0xFF888F7E), ink4 = Color(0xFFB0B5A4),
    line = Color(0xFFE0E3D6), line2 = Color(0xFFD0D4C4),
    userBg = Color(0xFF1B201A), userInk = Color(0xFFF3F5EC), codeBg = Color(0xFFE8EBDF),
    signal = Color(0xFF2F8F76), accent = Color(0xFFB8623A), accentInk = Color(0xFFFFFFFF), isDark = false,
)

// ── SAGE DARK · deep forest graphite ───────────────────────────────────────
internal val AmberSageDark = AmberTokens(
    bg = Color(0xFF131711), surface = Color(0xFF191D15), surface2 = Color(0xFF1E2219), raised = Color(0xFF20241B),
    ink = Color(0xFFE6EBDF), ink2 = Color(0xFFA0A896), ink3 = Color(0xFF6E7563), ink4 = Color(0xFF515845),
    line = Color(0xFF2A2E22), line2 = Color(0xFF353A2C),
    userBg = Color(0xFFE6EBDF), userInk = Color(0xFF1B201A), codeBg = Color(0xFF1E2219),
    signal = Color(0xFF4CAF8E), accent = Color(0xFFB8623A), accentInk = Color(0xFFFFFFFF), isDark = true,
)

fun baseTokens(b: AmberBase): AmberTokens = when (b) {
    AmberBase.LIGHT -> AmberLight
    AmberBase.DARK -> AmberDark
    AmberBase.SAGE -> AmberSage
    AmberBase.SAGE_DARK -> AmberSageDark
}

val LocalAmberTokens = staticCompositionLocalOf { AmberLight }

// ── Curated accents (design §2.3) — independent of base ─────────────────────
data class AmberAccent(val hex: Color, val label: String)

val AmberAccents: List<AmberAccent> = listOf(
    AmberAccent(Color(0xFFB8623A), "terracotta"),
    AmberAccent(Color(0xFF5E9C6E), "sage-green"),
    AmberAccent(Color(0xFF4F86D6), "blue"),
    AmberAccent(Color(0xFF9277C4), "purple"),
    AmberAccent(Color(0xFFC2607A), "rose"),
)

/**
 * Text/icon color drawn on an accent fill. Curated accents use a dark ink when white misses AA contrast.
 */
fun accentInkFor(accent: Color): Color {
    val rgb = accent.toArgb() and 0xFFFFFF
    return if (themeContrast(rgb, 0x000000) >= themeContrast(rgb, 0xFFFFFF)) Color.Black else Color.White
}

/**
 * Build the active token set: a neutral [base] with the user-chosen [accent] injected.
 *
 * `signal` is aliased to the accent (2026-06-10 decision): status/liveness indicators —
 * LiveDot, completed ticks, board/provider status dots — render in the accent instead of
 * the per-base green. The per-base `signal` greens above are kept as the design's original
 * values should the dual-color semantics ever return.
 */
fun buildAmberTokens(
    base: AmberBase,
    accent: Color,
    design: ThemeDesign? = null,
): AmberTokens = buildAmberTokens(baseTokens(base), accent, design)

internal fun buildAmberTokens(
    base: AmberTokens,
    accent: Color,
    design: ThemeDesign?,
): AmberTokens {
    val palette = if (base.isDark) design?.dark else design?.light
    val themedBase = palette?.toTokens(base) ?: base
    return themedBase.copy(accent = accent, accentInk = accentInkFor(accent), signal = accent)
}

/** Returns one of the five portable document paper presets for the active color mode. */
internal fun themePackPaperTokens(paper: String, isDark: Boolean): AmberTokens? {
    val colors = when (paper to isDark) {
        "paper" to false -> PaperPalette(0xEFE7D6, 0xFFFDF7, 0xF0EBE2, 0x1B1813, 0x5B5449, 0x746D62, 0x918A80, 0xDBCEBC, 0xECE3D6)
        "neutral" to false -> PaperPalette(0xECE8E4, 0xF6F5F3, 0xEDEBE7, 0x161514, 0x55524D, 0x716D67, 0x8F8B85, 0xD9D5CF, 0xE4E1DC)
        "white" to false -> PaperPalette(0xF5F5F4, 0xFFFFFF, 0xEEEEED, 0x1A1A1A, 0x5C5C5C, 0x737373, 0x8E8E8E, 0xD4D4D4, 0xE5E5E5)
        "pi" to false -> PaperPalette(0xF3F0EB, 0xFAF9F7, 0xEBE7E0, 0x1C1B19, 0x4A4640, 0x6A6560, 0x9A948C, 0xD4CFC7, 0xE8E4DC)
        "notion" to false -> PaperPalette(0xF6F5F4, 0xFFFFFF, 0xEFEEEC, 0x1A1918, 0x31302E, 0x615D59, 0xA39E98, 0xE6E5E3, 0xF0EFED)
        "paper" to true -> PaperPalette(0x14110E, 0x221E19, 0x2E2822, 0xF5F0E8, 0xC8BDB0, 0xA89888, 0x6E6258, 0x3D342C, 0x2A241E)
        "neutral" to true -> PaperPalette(0x0E0D10, 0x1F1D23, 0x2B2930, 0xF4F1ED, 0xC3BEC5, 0xAAA5AD, 0x6E6760, 0x3A3741, 0x2A2830)
        "white" to true -> PaperPalette(0x111111, 0x1C1C1C, 0x282828, 0xF5F5F5, 0xBDBDBD, 0x8E8E8E, 0x6B6B6B, 0x383838, 0x2A2A2A)
        "pi" to true -> PaperPalette(0x12110F, 0x1E1C18, 0x2A2722, 0xF3F0EB, 0xC4BEB4, 0x9A948C, 0x6A6560, 0x3A3630, 0x28251F)
        "notion" to true -> PaperPalette(0x191919, 0x252525, 0x2F2F2F, 0xEBEBEB, 0xB4B4B4, 0x9B9B9B, 0x6F6F6F, 0x373737, 0x2C2C2C)
        else -> return null
    }
    return AmberTokens(
        bg = colors.background.color(),
        surface = colors.surface.color(),
        surface2 = colors.surface2.color(),
        raised = colors.surface.color(),
        ink = colors.foreground.color(),
        ink2 = colors.foreground2.color(),
        ink3 = colors.muted.color(),
        ink4 = colors.muted2.color(),
        line = colors.border.color(),
        line2 = colors.borderSoft.color(),
        userBg = colors.foreground.color(),
        userInk = colors.background.color(),
        codeBg = colors.surface2.color(),
        signal = Color(0xFF5E9C6E),
        accent = Color(0xFFB8623A),
        accentInk = accentInkFor(Color(0xFFB8623A)),
        isDark = isDark,
    )
}

private data class PaperPalette(
    val background: Int,
    val surface: Int,
    val surface2: Int,
    val foreground: Int,
    val foreground2: Int,
    val muted: Int,
    val muted2: Int,
    val border: Int,
    val borderSoft: Int,
)

private fun Int.color(): Color = Color(0xFF000000L or toLong())

private fun ThemeDesign.Palette.toTokens(base: AmberTokens): AmberTokens? {
    val background = background.colorOrNull() ?: return null
    val surface = surface.colorOrNull() ?: return null
    val foreground = foreground.colorOrNull() ?: return null
    val muted = mutedForeground.colorOrNull() ?: return null
    val border = border.colorOrNull() ?: return null
    val surface2 = midpoint(background, surface)
    return base.copy(
        bg = background,
        surface = surface,
        surface2 = surface2,
        raised = surface,
        ink = foreground,
        ink2 = muted,
        ink3 = muted,
        ink4 = muted,
        line = border,
        line2 = border,
        userBg = foreground,
        userInk = background,
        codeBg = surface2,
    )
}

private fun String.colorOrNull(): Color? = themeRgb(this)?.let { Color(0xFF000000L or it.toLong()) }

private fun midpoint(first: Color, second: Color): Color {
    val a = first.toArgb()
    val b = second.toArgb()
    fun channel(shift: Int): Int = (((a shr shift) and 0xFF) + ((b shr shift) and 0xFF)) / 2
    val rgb = (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    return Color(0xFF000000L or rgb.toLong())
}
