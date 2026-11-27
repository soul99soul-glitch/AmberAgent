package app.amber.feature.ui.theme

import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import app.amber.core.settings.TextWeight

internal val TextWeight.offset: Int
    get() = when (this) {
        TextWeight.SYSTEM -> 0
        TextWeight.LIGHTER -> -100
        TextWeight.HEAVIER -> 100
    }

internal val LocalLegacyTextWeightOffset = staticCompositionLocalOf { 0 }

@Composable
internal fun rememberTextWeightResolver(weight: TextWeight): FontFamily.Resolver {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || weight == TextWeight.SYSTEM) {
        return LocalFontFamilyResolver.current
    }
    val context = LocalContext.current
    val adjustment = LocalConfiguration.current.fontWeightAdjustment
    return remember(context, adjustment, weight) {
        val systemOffset = if (adjustment == Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) 0 else adjustment
        // Compose applies the combined adjustment once when resolving each requested weight.
        val override = Configuration().apply { fontWeightAdjustment = systemOffset + weight.offset }
        createFontFamilyResolver(context.createConfigurationContext(override))
    }
}

internal fun TextStyle.withTypeface(family: FontFamily?, weightOffset: Int): TextStyle = copy(
    fontFamily = family ?: fontFamily,
    fontWeight = if (weightOffset == 0) fontWeight else {
        FontWeight(((fontWeight ?: FontWeight.Normal).weight + weightOffset).coerceIn(1, 1000))
    },
)
