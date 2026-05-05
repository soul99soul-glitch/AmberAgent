package me.rerere.rikkahub.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)
private val NotionShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)
private val NotionTypography = Typography.copy(
    displayLarge = Typography.displayLarge.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    displayMedium = Typography.displayMedium.copy(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    displaySmall = Typography.displaySmall.copy(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = Typography.headlineLarge.copy(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = Typography.headlineMedium.copy(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = Typography.headlineSmall.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = Typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = Typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleSmall = Typography.titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodyMedium = Typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = Typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = Typography.labelLarge.copy(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelMedium = Typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = Typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

private val NotionLightScheme = lightColorScheme(
    primary = Color(0xFF2383E2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF4FF),
    onPrimaryContainer = Color(0xFF0B4D84),
    secondary = Color(0xFF37352F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7F7F5),
    onSecondaryContainer = Color(0xFF37352F),
    tertiary = Color(0xFFB45F06),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF2CC),
    onTertiaryContainer = Color(0xFF3A2A00),
    error = Color(0xFFC93A2F),
    onError = Color.White,
    errorContainer = Color(0xFFFDEBEC),
    onErrorContainer = Color(0xFF5B1917),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF1F1EF),
    onSurfaceVariant = Color(0xFF6B6761),
    outline = Color(0xFFD9D9D6),
    outlineVariant = Color(0xFFEDEDEB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFBFA),
    surfaceContainer = Color(0xFFF7F7F5),
    surfaceContainerHigh = Color(0xFFF1F1EF),
    surfaceContainerHighest = Color(0xFFEDEDEB),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF1F1EF),
)

private val NotionDarkScheme = darkColorScheme(
    primary = Color(0xFFEDECE9),
    onPrimary = Color(0xFF191918),
    primaryContainer = Color(0xFF333230),
    onPrimaryContainer = Color(0xFFEDECE9),
    secondary = Color(0xFFC9C6C0),
    onSecondary = Color(0xFF191918),
    secondaryContainer = Color(0xFF2B2A28),
    onSecondaryContainer = Color(0xFFEDECE9),
    tertiary = Color(0xFFE0A34D),
    onTertiary = Color(0xFF211600),
    tertiaryContainer = Color(0xFF3A2A12),
    onTertiaryContainer = Color(0xFFFFE0A3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4D1816),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191918),
    onBackground = Color(0xFFEDECE9),
    surface = Color(0xFF20201E),
    onSurface = Color(0xFFEDECE9),
    surfaceVariant = Color(0xFF3E3C38),
    onSurfaceVariant = Color(0xFFC9C6C0),
    outline = Color(0xFF55524D),
    outlineVariant = Color(0xFF3A3834),
    surfaceContainerLowest = Color(0xFF171716),
    surfaceContainerLow = Color(0xFF20201E),
    surfaceContainer = Color(0xFF252523),
    surfaceContainerHigh = Color(0xFF2E2D2A),
    surfaceContainerHighest = Color(0xFF383633),
    surfaceBright = Color(0xFF252523),
    surfaceDim = Color(0xFF171716),
)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val colorMode by rememberColorMode()
    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val colorScheme = when {
        BuildConfig.NOTION_LIKE -> if (darkTheme) NotionDarkScheme else NotionLightScheme
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> findPresetTheme(settings.themeId).getColorScheme(dark = true)
        else -> findPresetTheme(settings.themeId).getColorScheme(dark = false)
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null
    ) {
        if (BuildConfig.NOTION_LIKE) {
            MaterialTheme(
                colorScheme = colorSchemeConverted,
                typography = NotionTypography,
                shapes = NotionShapes,
                content = content,
            )
        } else {
            MaterialExpressiveTheme(
                colorScheme = colorSchemeConverted,
                typography = Typography,
                content = content,
                motionScheme = MotionScheme.expressive()
            )
        }
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
