package app.amber.feature.ui.theme

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.serialization.Serializable
import app.amber.feature.ui.hooks.rememberAmoledDarkMode
import app.amber.feature.ui.hooks.rememberColorMode
import app.amber.feature.ui.hooks.rememberUserSettingsState
import app.amber.feature.ui.pages.chat.toChatTheme

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }
val LocalAmoledDarkMode = compositionLocalOf { false }

private val NotionShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(15.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
private val NotionTypography = Typography.copy(
    displayLarge = Typography.displayLarge.copy(fontFamily = HankenGrotesk, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    displayMedium = Typography.displayMedium.copy(fontFamily = HankenGrotesk, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    displaySmall = Typography.displaySmall.copy(fontFamily = HankenGrotesk, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = Typography.headlineLarge.copy(fontFamily = HankenGrotesk, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = Typography.headlineMedium.copy(fontFamily = HankenGrotesk, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = Typography.headlineSmall.copy(fontFamily = HankenGrotesk, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    // Keep the existing AmberTextStyles UI scale: 19/16/15/13. Markdown's display/headline
    // tiers above intentionally remain untouched so document headings keep their hierarchy.
    titleLarge = Typography.titleLarge.copy(fontFamily = AmberSans, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
    titleMedium = Typography.titleMedium.copy(fontFamily = AmberSans, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
    titleSmall = Typography.titleSmall.copy(fontFamily = AmberSans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Typography.bodyLarge.copy(fontFamily = AmberSans, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = Typography.bodyMedium.copy(fontFamily = AmberSans, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = Typography.bodySmall.copy(fontFamily = AmberSans, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = Typography.labelLarge.copy(fontFamily = AmberSans, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelMedium = Typography.labelMedium.copy(fontFamily = AmberSans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = Typography.labelSmall.copy(fontFamily = AmberSans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
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
fun AmberAgentTheme(
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
    val colorSchemeConverted = colorScheme
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // Status / nav bar icon contrast follows the *app* theme (not system night mode).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Skip non-Activity hosts (e.g. accessibility bubble windows).
            val activity = view.context as? ComponentActivity
            val window = activity?.window ?: (view.context as? Activity)?.window
            if (window != null) {
                // Re-apply edge-to-edge with an explicit light/dark style. Using
                // SystemBarStyle.auto() keys off system night mode and re-fires on
                // insets/config updates — on ColorOS that races and restores white
                // icons on a light app canvas. light()/dark() pin icon polarity.
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                activity?.enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle,
                )
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    // true → dark icons (for light backgrounds)
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }

    val settings by rememberUserSettingsState()

    // Graphite redesign (D2/D3): base family × system dark-mode → one of 4 bases; the accent is
    // an independent user setting. Legacy chatThemeChoice is no longer consulted. chatTheme is
    // derived from the new tokens via the compat adapter so existing LocalChatTheme consumers work.
    val amberBase = when {
        settings.displaySetting.amberBaseFamily == "SAGE" && darkTheme -> AmberBase.SAGE_DARK
        settings.displaySetting.amberBaseFamily == "SAGE" -> AmberBase.SAGE
        darkTheme -> AmberBase.DARK
        else -> AmberBase.LIGHT
    }
    val amberAccent = parseAccent(settings.displaySetting.accentColor)
    val amberTokens = remember(amberBase, amberAccent, darkTheme, amoledDarkMode) {
        buildAmberTokens(amberBase, amberAccent).let { tokens ->
            if (darkTheme && amoledDarkMode) tokens.withAmoledSurfaces() else tokens
        }
    }
    val chatTheme = remember(amberTokens) { amberTokens.toChatTheme() }

    val themedColorScheme = remember(colorSchemeConverted, chatTheme, amoledDarkMode, darkTheme) {
        run {
            colorSchemeConverted.copy(
                background = chatTheme.bg,
                onBackground = chatTheme.ink,
                surface = chatTheme.paper,
                onSurface = chatTheme.ink,
                surfaceVariant = chatTheme.toolPillBg,
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
                // Keep error/errorContainer from the base scheme: destructive feedback remains
                // semantically red when the user changes the accent.
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
        LocalAmberTokens provides amberTokens,
        LocalAmberType provides defaultAmberTextStyles(),
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

/** Parse a stored accent hex (e.g. "#B8623A") into a Compose Color; falls back to terracotta. */
private fun parseAccent(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
} catch (e: IllegalArgumentException) {
    Color(0xFFB8623A)
}
