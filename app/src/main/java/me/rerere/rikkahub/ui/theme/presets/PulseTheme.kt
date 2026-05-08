package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

/**
 * Pulse Performance design system — fitness-tech aesthetic.
 *
 * Cream surfaces, near-black ink, neon-chartreuse for active/in-progress states,
 * sport-orange for hero numerals & critical CTAs. Reject Material You dynamic
 * color: this palette IS the brand.
 *
 * The Material 3 [ColorScheme] is wired to map our semantic intents onto M3 slots
 * so most stock components automatically pick up the look:
 *   - primary             -> chartreuse  (active, in-progress, primary affordances)
 *   - primaryContainer    -> chartreuse with mild surface tint  (selected backgrounds)
 *   - secondary           -> sport-orange (emphasis numerals + secondary CTAs)
 *   - tertiary            -> ink (light) / nightCard (dark)  (spotlight cards & floating nav)
 *   - background/surface  -> warm cream
 *   - surfaceVariant      -> tan  (modular cards)
 *   - onSurfaceVariant    -> warm grey  (metadata + ALL-CAPS labels)
 *   - error               -> sport-orange (we use the same hue for warn states; the
 *                             design intentionally has no separate red)
 *
 * Anything that should pop "primary" — send buttons, active nav pills, running
 * tool stripes — uses MaterialTheme.colorScheme.primary. Anything that should
 * scream "value/numeral/critical" uses .secondary. Spotlight surfaces (the
 * floating bottom nav, terminal cards, current-tool spotlight) use .tertiary
 * + .onTertiary.
 */
val PulseThemePreset by lazy {
    PresetTheme(
        id = "pulse",
        name = {
            Text(stringResource(id = R.string.theme_name_pulse))
        },
        standardLight = pulseLight,
        standardDark = pulseDark,
    )
}

// ── Tokens ──────────────────────────────────────────────────────────────────

// Cream surface family (warm off-white, slight peach undertone)
private val cream = Color(0xFFF5F1EA)
private val tan = Color(0xFFEDE5D8)
private val tan2 = Color(0xFFE5DCD0)
private val tan3 = Color(0xFFD9CDBC)

// Ink (near-black, never pure black)
private val ink = Color(0xFF1A1A1A)
private val ink2 = Color(0xFF2A2A2A)

// Warm grey for secondary text + ALL-CAPS labels
private val grey = Color(0xFF7A716A)
private val grey2 = Color(0xFFA89F95)

// Chartreuse (the "active" accent — neon yellow-green)
private val chartreuse = Color(0xFFD6FF3F)
private val chartreuse2 = Color(0xFFBFEC2E)
private val chartreuseDark = Color(0xFF8FB81C)
private val chartreuseContainer = Color(0xFFE8FF8C)

// Sport-orange (hero numerals + critical CTAs)
private val orange = Color(0xFFE85D2F)
private val orange2 = Color(0xFFD14C24)
private val orangeDark = Color(0xFF8C2E0F)
private val orangeContainer = Color(0xFFFFCFBA)

// ── Light scheme ────────────────────────────────────────────────────────────

private val pulseLight = lightColorScheme(
    // primary slot = chartreuse (active/primary)
    primary = chartreuse,
    onPrimary = ink,
    primaryContainer = chartreuseContainer,
    onPrimaryContainer = ink,
    inversePrimary = chartreuse2,

    // secondary slot = sport-orange (numerals/critical)
    secondary = orange,
    onSecondary = cream,
    secondaryContainer = orangeContainer,
    onSecondaryContainer = orangeDark,

    // tertiary slot = ink (spotlight cards, floating nav)
    tertiary = ink,
    onTertiary = cream,
    tertiaryContainer = ink2,
    onTertiaryContainer = chartreuse,

    // backgrounds
    background = cream,
    onBackground = ink,
    surface = cream,
    onSurface = ink,
    surfaceVariant = tan,
    onSurfaceVariant = grey,

    // outlines (subtle tan border around cards & inputs)
    outline = tan3,
    outlineVariant = tan2,

    scrim = ink,
    inverseSurface = ink,
    inverseOnSurface = cream,

    // surface containers — used by Cards, Sheets, NavigationBar etc
    surfaceContainerLowest = cream,
    surfaceContainerLow = cream,
    surfaceContainer = tan,
    surfaceContainerHigh = tan2,
    surfaceContainerHighest = tan3,
    surfaceBright = cream,
    surfaceDim = tan,

    // error reuses sport-orange — design has no separate red
    error = orange,
    onError = cream,
    errorContainer = orangeContainer,
    onErrorContainer = orangeDark,
)

// ── Dark scheme ─────────────────────────────────────────────────────────────
//
// Pulse Dark inverts the cream/ink relationship: ink-family backgrounds, with
// chartreuse and sport-orange popping at higher saturation against the dark
// canvas. Cream becomes the "on-surface" color.

private val nightBg = Color(0xFF121212)
private val nightSurface = Color(0xFF1A1A1A)
private val nightCard = Color(0xFF222222)
private val nightCard2 = Color(0xFF2A2A2A)
private val nightCard3 = Color(0xFF333333)
private val nightOutline = Color(0xFF3D3D3D)
private val nightInk = Color(0xFFE5E1DA)
private val nightGrey = Color(0xFF8A8780)

private val pulseDark = darkColorScheme(
    primary = chartreuse,
    onPrimary = ink,
    primaryContainer = chartreuseDark,
    onPrimaryContainer = chartreuse,
    inversePrimary = chartreuse2,

    secondary = orange,
    onSecondary = cream,
    secondaryContainer = orangeDark,
    onSecondaryContainer = orangeContainer,

    tertiary = nightCard,
    onTertiary = cream,
    tertiaryContainer = nightCard,
    onTertiaryContainer = chartreuse,

    background = nightBg,
    onBackground = nightInk,
    surface = nightSurface,
    onSurface = nightInk,
    surfaceVariant = nightCard,
    onSurfaceVariant = nightGrey,

    outline = nightOutline,
    outlineVariant = nightCard,

    scrim = Color(0xFF000000),
    inverseSurface = cream,
    inverseOnSurface = ink,

    surfaceContainerLowest = nightBg,
    surfaceContainerLow = nightSurface,
    surfaceContainer = nightCard,
    surfaceContainerHigh = nightCard2,
    surfaceContainerHighest = nightCard3,
    surfaceBright = nightCard2,
    surfaceDim = nightBg,

    error = orange,
    onError = cream,
    errorContainer = orangeDark,
    onErrorContainer = orangeContainer,
)

// ── Public design-token accessors ───────────────────────────────────────────
//
// For places where the M3 ColorScheme slot mapping isn't expressive enough
// (e.g. dedicated "warm grey for ALL-CAPS labels" or "tan-3 outline for inputs"),
// expose the raw tokens. Use sparingly — prefer MaterialTheme.colorScheme first.

object PulseTokens {
    val Cream get() = cream
    val Tan get() = tan
    val Tan2 get() = tan2
    val Tan3 get() = tan3
    val Ink get() = ink
    val Ink2 get() = ink2
    val Grey get() = grey
    val Grey2 get() = grey2
    val Chartreuse get() = chartreuse
    val Chartreuse2 get() = chartreuse2
    val ChartreuseContainer get() = chartreuseContainer
    val Orange get() = orange
    val OrangeContainer get() = orangeContainer
}
