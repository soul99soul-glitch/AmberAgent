package app.amber.feature.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import app.amber.feature.ui.theme.PresetTheme

val AmberAgentClashThemePreset by lazy {
    PresetTheme(
        id = "amberagent_clash",
        name = {
            Text(stringResource(R.string.theme_name_amberagent_clash))
        },
        standardLight = amberagentClashLightScheme,
        standardDark = amberagentClashDarkScheme,
    )
}

private val amberagentClashLightScheme = lightColorScheme(
    primary = Color(0xFF9B4B00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDAE),
    onPrimaryContainer = Color(0xFF241400),
    secondary = Color(0xFF006B73),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA6EFF7),
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFF9F006B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7EF),
    onTertiaryContainer = Color(0xFF3E0029),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F0),
    onBackground = Color(0xFF201A12),
    surface = Color(0xFFFFF8F0),
    onSurface = Color(0xFF201A12),
    surfaceVariant = Color(0xFFEBDDCB),
    onSurfaceVariant = Color(0xFF514435),
    outline = Color(0xFF847466),
    outlineVariant = Color(0xFFD7C3B3),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF362F27),
    inverseOnSurface = Color(0xFFFFEEDB),
    inversePrimary = Color(0xFFFFB781),
    surfaceDim = Color(0xFFE8D7C6),
    surfaceBright = Color(0xFFFFF8F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0DC),
    surfaceContainer = Color(0xFFFBE8CE),
    surfaceContainerHigh = Color(0xFFF5DFC6),
    surfaceContainerHighest = Color(0xFFEFD7BC),
)

private val amberagentClashDarkScheme = darkColorScheme(
    primary = Color(0xFFFFB86B),
    onPrimary = Color(0xFF2B1800),
    primaryContainer = Color(0xFF713C00),
    onPrimaryContainer = Color(0xFFFFDDB1),
    secondary = Color(0xFF7CDDE7),
    onSecondary = Color(0xFF002023),
    secondaryContainer = Color(0xFF00363C),
    onSecondaryContainer = Color(0xFFA6EFF7),
    tertiary = Color(0xFFFFB0DD),
    onTertiary = Color(0xFF3F002B),
    tertiaryContainer = Color(0xFF7B0056),
    onTertiaryContainer = Color(0xFFFFD7F0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFEDE1D3),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFEDE1D3),
    surfaceVariant = Color(0xFF484452),
    onSurfaceVariant = Color(0xFFD0C3D8),
    outline = Color(0xFF9A8FA2),
    outlineVariant = Color(0xFF484452),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFEDE4D7),
    inverseOnSurface = Color(0xFF2F3139),
    inversePrimary = Color(0xFF8D3A00),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF30333D),
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF191B22),
    surfaceContainer = Color(0xFF20232B),
    surfaceContainerHigh = Color(0xFF2A2D36),
    surfaceContainerHighest = Color(0xFF353943),
)
