package app.amber.feature.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Amber · "Terminal × Modern" typography — the mono/sans split IS the brand signature.
 *
 * - [AmberMono] (JetBrains Mono) → machine facts ONLY: model ids, ctx sizes, prices, timings,
 *   `//` section eyebrows, version strings, the `amber` wordmark, tool names.
 * - [AmberSans] → everything a person reads (titles, body, descriptions, labels).
 *
 * Apply via [LocalAmberType] styles at call sites; never mono-set human prose, never sans-set
 * machine facts (design §3).
 *
 * [AmberSans] is Hanken Grotesk (bundled variable font, Latin UI). Hanken carries no hanzi, so
 * Chinese glyphs fall back to Android's system Noto Sans CJK (≈ Noto Sans SC) — on-brand today.
 * The mono signature is exact via bundled JetBrains Mono.
 *
 * TODO(fonts/D5): bundle a Noto Sans SC subset for pixel-exact CN rendering (future nicety; the
 * system CJK fallback is already close).
 */
val AmberSans: FontFamily = HankenGrotesk
val AmberMono: FontFamily = JetBrainsMonoFamily

@Immutable
data class AmberTextStyles(
    val screenTitle: TextStyle,
    val sessionTitle: TextStyle,
    val body: TextStyle,
    val secondary: TextStyle,
    val meta: TextStyle,
    val eyebrow: TextStyle,
    val tinyTag: TextStyle,
)

/** Scale from design §3 (px @ 380-wide). Mono styles enable tabular + slashed-zero. */
fun defaultAmberTextStyles(): AmberTextStyles = AmberTextStyles(
    screenTitle = TextStyle(
        fontFamily = AmberSans, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp,
    ),
    sessionTitle = TextStyle(
        fontFamily = AmberSans, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp,
    ),
    body = TextStyle(
        fontFamily = AmberSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp,
    ),
    secondary = TextStyle(
        fontFamily = AmberSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp,
    ),
    meta = TextStyle(
        fontFamily = AmberMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp,
        fontFeatureSettings = "tnum, zero",
    ),
    eyebrow = TextStyle(
        fontFamily = AmberMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp,
        letterSpacing = 1.3.sp, fontFeatureSettings = "tnum, zero",
    ),
    tinyTag = TextStyle(
        fontFamily = AmberSans, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 13.sp,
    ),
)

val LocalAmberType = staticCompositionLocalOf { defaultAmberTextStyles() }
