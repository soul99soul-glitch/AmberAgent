package app.amber.feature.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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

// ── LIGHT · warm off-white / graphite ──────────────────────────────────────
internal val AmberLight = AmberTokens(
    bg = Color(0xFFF4F2EC), surface = Color(0xFFFAF9F5), surface2 = Color(0xFFEFECE4), raised = Color(0xFFFFFFFF),
    ink = Color(0xFF1B1A17), ink2 = Color(0xFF57544C), ink3 = Color(0xFF8F8B80), ink4 = Color(0xFFB6B1A4),
    line = Color(0xFFE4E0D6), line2 = Color(0xFFD6D1C4),
    userBg = Color(0xFF1B1A17), userInk = Color(0xFFF6F4EE), codeBg = Color(0xFFEDEAE1),
    signal = Color(0xFF5E9C6E), accent = Color(0xFFB8623A), accentInk = Color(0xFFFFFFFF), isDark = false,
)

// ── DARK · warm graphite ───────────────────────────────────────────────────
internal val AmberDark = AmberTokens(
    bg = Color(0xFF161512), surface = Color(0xFF1C1B17), surface2 = Color(0xFF211F1A), raised = Color(0xFF23211C),
    ink = Color(0xFFECE8DF), ink2 = Color(0xFFA8A298), ink3 = Color(0xFF756F64), ink4 = Color(0xFF564F45),
    line = Color(0xFF2E2B25), line2 = Color(0xFF3A362E),
    userBg = Color(0xFFECE8DF), userInk = Color(0xFF1B1A17), codeBg = Color(0xFF211F1A),
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
 * Text/icon color drawn *on* an accent fill (design §2.3 `inkFor`):
 * green → near-black, gold → dark brown, everything else → white.
 */
fun accentInkFor(accent: Color): Color = when (accent) {
    Color(0xFF5E9C6E) -> Color(0xFF0F150E) // sage-green
    Color(0xFFD9A441), Color(0xFFC9A461) -> Color(0xFF1A1408) // gold (defensive)
    else -> Color(0xFFFFFFFF)
}

/**
 * Build the active token set: a neutral [base] with the user-chosen [accent] injected.
 *
 * `signal` is aliased to the accent (2026-06-10 decision): status/liveness indicators —
 * LiveDot, completed ticks, board/provider status dots — render in the accent instead of
 * the per-base green. The per-base `signal` greens above are kept as the design's original
 * values should the dual-color semantics ever return.
 */
fun buildAmberTokens(base: AmberBase, accent: Color): AmberTokens =
    baseTokens(base).copy(accent = accent, accentInk = accentInkFor(accent), signal = accent)
