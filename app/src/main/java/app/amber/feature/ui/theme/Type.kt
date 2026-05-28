package app.amber.feature.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import me.rerere.rikkahub.R

// Set of Material typography styles to start with
val Typography = Typography()

@OptIn(ExperimentalTextApi::class)
val JetbrainsMono = FontFamily(
    Font(
        resId = R.font.jetbrains_mono,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Normal.weight),
        )
    )
)

/**
 * Bundled CJK serif. Source: Noto Serif SC SubsetOTF (Regular weight only) from
 * notofonts/noto-cjk. Single weight ≈ 11MB; covers the SC subset (about 7000 hanzi
 * + the standard ASCII/punct ranges) which is plenty for chat / PPT-skill rendering.
 *
 * Single-`Font` `FontFamily` has no Compose-level fallback chain — glyphs outside the
 * Subset SC range render via Android's system Noto fallback (sans, not serif). For TC
 * / JP / KR serif support, drop additional weight files into res/font and extend the
 * FontFamily list, or layer multiple FontFamily instances at the call site.
 */
val NotoSerifSC = FontFamily(
    Font(
        resId = R.font.noto_serif_sc,
        weight = FontWeight.Normal,
    )
)
