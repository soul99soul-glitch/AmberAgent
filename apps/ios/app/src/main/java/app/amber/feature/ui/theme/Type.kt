package app.amber.feature.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.amber.agent.R

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

@OptIn(ExperimentalTextApi::class)
private fun jbMono(weight: FontWeight) = Font(
    resId = R.font.jetbrains_mono,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/**
 * Multi-weight JetBrains Mono family — the design-system "machine fact" font (model ids,
 * ctx, price, timings, `//` section labels, version strings, the `amber` wordmark, tool names).
 */
val JetBrainsMonoFamily = FontFamily(
    jbMono(FontWeight.Normal),
    jbMono(FontWeight.Medium),
    jbMono(FontWeight.SemiBold),
    jbMono(FontWeight.Bold),
)

// `weight = weight` is REQUIRED: it declares the font's match weight to Compose's font matcher.
// Without it the param defaults to FontWeight.Normal, so all 4 weights look identical (400) to
// the matcher — it then can't pair Hanken+Noto per weight and bold falls back to the system CJK.
@OptIn(ExperimentalTextApi::class)
private fun hanken(weight: FontWeight) = Font(
    resId = R.font.hanken_grotesk,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

// Bundled CJK sans: Noto Sans SC, GB2312 subset (~6.7k 常用汉字), variable `wght` 100–900,
// ≈3.6MB. Supplies the hanzi that the Latin-only Hanken lacks, AT EACH WEIGHT — so bold
// Chinese titles render真·bold instead of falling back to the system CJK font at normal weight.
@OptIn(ExperimentalTextApi::class)
private fun notoSans(weight: FontWeight) = Font(
    resId = R.font.noto_sans_sc,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/**
 * Multi-weight Amber sans family — Hanken Grotesk (Latin UI) + bundled Noto Sans SC (hanzi),
 * paired at every weight. Latin resolves to Hanken; hanzi to Noto Sans SC at the SAME weight,
 * so `FontWeight.Bold` yields真·加粗中文标题. Rare hanzi outside the GB2312 subset still fall
 * back to the system CJK font (normal weight).
 */
val HankenGrotesk = FontFamily(
    hanken(FontWeight.Normal), notoSans(FontWeight.Normal),
    hanken(FontWeight.Medium), notoSans(FontWeight.Medium),
    hanken(FontWeight.SemiBold), notoSans(FontWeight.SemiBold),
    hanken(FontWeight.Bold), notoSans(FontWeight.Bold),
)
