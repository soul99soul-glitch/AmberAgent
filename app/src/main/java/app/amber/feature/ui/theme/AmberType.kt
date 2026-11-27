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
 * [AmberSans] lets Android select the default Latin, CJK and locale-specific fallback faces.
 */
val AmberSans: FontFamily = FontFamily.SansSerif
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

/** Compact UI scale; small metadata stays legible. Mono enables tabular + slashed-zero. */
fun defaultAmberTextStyles(): AmberTextStyles = AmberTextStyles(
    screenTitle = TextStyle(
        fontFamily = AmberSans, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 23.sp,
    ),
    sessionTitle = TextStyle(
        fontFamily = AmberSans, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp,
    ),
    body = TextStyle(
        fontFamily = AmberSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp,
    ),
    secondary = TextStyle(
        fontFamily = AmberSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp,
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

/** Home chrome, and settings only when requested by the portable document. */
internal fun themeAmberTextStyles(typeface: String?, weightOffset: Int = 0): AmberTextStyles {
    val base = defaultAmberTextStyles()
    val family = chromeFontFamily(typeface)
    if (family == null && weightOffset == 0) return base
    return base.copy(
        screenTitle = base.screenTitle.withTypeface(family, weightOffset),
        sessionTitle = base.sessionTitle.withTypeface(family, weightOffset),
        body = base.body.withTypeface(family, weightOffset),
        secondary = base.secondary.withTypeface(family, weightOffset),
        meta = base.meta.withTypeface(family, weightOffset),
        eyebrow = base.eyebrow.withTypeface(family, weightOffset),
        tinyTag = base.tinyTag.withTypeface(family, weightOffset),
    )
}
