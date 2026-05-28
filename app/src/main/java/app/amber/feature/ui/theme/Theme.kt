package app.amber.feature.ui.theme

import android.app.Activity
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import app.amber.feature.ui.hooks.rememberAmoledDarkMode
import app.amber.feature.ui.hooks.rememberColorMode
import app.amber.feature.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }
val LocalAmoledDarkMode = compositionLocalOf { false }

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
    primary = Color(0xFF4EA6FF),
    onPrimary = Color(0xFF071B2E),
    primaryContainer = Color(0xFF10263A),
    onPrimaryContainer = Color(0xFFD8EAFF),
    secondary = Color(0xFFB7BBC3),
    onSecondary = Color(0xFF181A1D),
    secondaryContainer = Color(0xFF24272B),
    onSecondaryContainer = Color(0xFFE4E6EA),
    tertiary = Color(0xFFE5B567),
    onTertiary = Color(0xFF251805),
    tertiaryContainer = Color(0xFF352715),
    onTertiaryContainer = Color(0xFFFFE0A3),
    error = Color(0xFFFF8F86),
    onError = Color(0xFF3F0605),
    errorContainer = Color(0xFF3A1715),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111315),
    onBackground = Color(0xFFEDEFF2),
    surface = Color(0xFF181A1D),
    onSurface = Color(0xFFEDEFF2),
    surfaceVariant = Color(0xFF2A2D32),
    onSurfaceVariant = Color(0xFFA7ABB2),
    outline = Color(0xFF3E424A),
    outlineVariant = Color(0xFF2A2D32),
    surfaceContainerLowest = Color(0xFF0D0F11),
    surfaceContainerLow = Color(0xFF16181B),
    surfaceContainer = Color(0xFF1C1F23),
    surfaceContainerHigh = Color(0xFF23262B),
    surfaceContainerHighest = Color(0xFF2C3036),
    surfaceBright = Color(0xFF202328),
    surfaceDim = Color(0xFF0D0F11),
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
    val colorMode by rememberColorMode()
    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val colorScheme = if (darkTheme) NotionDarkScheme else NotionLightScheme
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = Color(0xFF050505),
                surfaceVariant = Color(0xFF101010),
                surfaceContainerLowest = AMOLED_DARK_BACKGROUND,
                surfaceContainerLow = Color(0xFF050505),
                surfaceContainer = Color(0xFF080808),
                surfaceContainerHigh = Color(0xFF0D0D0D),
                surfaceContainerHighest = Color(0xFF141414),
                surfaceBright = Color(0xFF0D0D0D),
                surfaceDim = AMOLED_DARK_BACKGROUND,
                secondaryContainer = Color(0xFF101010),
                outline = Color(0xFF34383F),
                outlineVariant = Color(0xFF20242A),
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

    val settings by rememberUserSettingsState()
    val chatTheme = app.amber.feature.ui.pages.chat.ChatThemeChoice
        .resolve(settings.displaySetting.chatThemeChoice, darkTheme)
        .instance

    val themedColorScheme = remember(colorSchemeConverted, chatTheme, amoledDarkMode, darkTheme) {
        run {
            // AmoledDark 时保留纯黑 bg/surface（省电），其他主题 token 仍跟 chatTheme
            val useAmoledBlack = amoledDarkMode && darkTheme
            colorSchemeConverted.copy(
                background = if (useAmoledBlack) colorSchemeConverted.background else chatTheme.bg,
                onBackground = chatTheme.ink,
                surface = if (useAmoledBlack) colorSchemeConverted.surface else chatTheme.paper,
                onSurface = chatTheme.ink,
                surfaceVariant = if (useAmoledBlack) colorSchemeConverted.surfaceVariant else chatTheme.toolPillBg,
                onSurfaceVariant = chatTheme.inkSoft,
                // Subagent #6: 5 级 hierarchy 让 NavDrawer / Card / BottomSheet 有深度
                surfaceContainerLowest = chatTheme.containerLowest,
                surfaceContainerLow = chatTheme.containerLow,
                surfaceContainer = chatTheme.containerMid,
                surfaceContainerHigh = chatTheme.containerHigh,
                surfaceContainerHighest = chatTheme.containerHighest,
                // surfaceBright / surfaceDim 之前没 override, dynamicLight 默认给浅蓝 tinted,
                // 导致 cardColorsOnSurfaceContainer (= surfaceBright) 出现浅蓝底. 跟 chatTheme.
                surfaceBright = chatTheme.containerHighest,
                surfaceDim = chatTheme.containerLowest,
                surfaceTint = chatTheme.accent,
                primary = chatTheme.accent,
                // Subagent #3: Midnight 用深字反白 (chatTheme.onAccent)；其他主题仍是白字
                onPrimary = chatTheme.onAccent,
                primaryContainer = chatTheme.accentSoft,
                onPrimaryContainer = chatTheme.accentDeep,
                secondary = chatTheme.accent,
                secondaryContainer = chatTheme.accentSoft,
                onSecondaryContainer = chatTheme.accentDeep,
                // Subagent #8 补齐 tertiary 用同主题 accent 体系。深色主题的 accentTint 是浅色提示色，
                // 直接当 container 会在标签/搜索高亮/预览浮层里冒出亮块；深色下用透明 accentSoft。
                tertiary = chatTheme.accentDeep,
                onTertiary = chatTheme.onAccent,
                tertiaryContainer = if (chatTheme.isDark) chatTheme.accentSoft else chatTheme.accentTint,
                onTertiaryContainer = if (chatTheme.isDark) chatTheme.accent else chatTheme.accentDeep,
                // Subagent #9 / 终审 #1：Snackbar 用 inverseSurface 应为"反相"色——
                // 深色主题给亮底 + 深字; 浅色主题给深底 + 亮字。当前 chatTheme.ink/paper
                // 在浅色 = 深字/白底 (正确)；在深色 = 浅字/深底 (反了)。所以深色下交换。
                inverseSurface = if (darkTheme) chatTheme.paper else chatTheme.ink,
                inverseOnSurface = if (darkTheme) chatTheme.ink else chatTheme.paper,
                inversePrimary = chatTheme.accentTint,
                // Subagent #4
                outline = chatTheme.outlineStrong,
                outlineVariant = chatTheme.outlineSoft,
            )
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalAmoledDarkMode provides (darkTheme && amoledDarkMode),
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null,
        app.amber.feature.ui.pages.chat.LocalChatTheme provides chatTheme,
        // Bug fix: M3 LocalContentColor 默认 Color.Black. 没有 Surface 显式 provide 时
        // (如 ChatPage 直接放 MarkdownBlock 的无 bubble 渲染路径), 深色模式下文字仍渲染成黑色.
        // 这里显式 provide onSurface (= chatTheme.ink) 作为全局默认前景色.
        LocalContentColor provides themedColorScheme.onSurface,
    ) {
        MaterialTheme(
            colorScheme = themedColorScheme,
            typography = NotionTypography,
            shapes = NotionShapes,
            content = content,
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
