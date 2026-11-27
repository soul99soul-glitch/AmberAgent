package app.amber.feature.ui.theme

import android.app.Activity
import android.os.Build
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.serialization.Serializable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.core.settings.themeContrast
import app.amber.core.settings.themeRgb
import app.amber.feature.ui.hooks.rememberAmoledDarkMode
import app.amber.feature.ui.hooks.rememberColorMode
import app.amber.feature.ui.hooks.rememberUserSettingsState
import app.amber.feature.ui.pages.chat.toChatTheme
import org.koin.compose.koinInject

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }
internal val LocalDarkThemePalette = compositionLocalOf { false }
val LocalAmoledDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)
internal val AmberShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp),
)
internal val AmberTypography = Typography.copy(
    displayLarge = Typography.displayLarge.copy(fontFamily = AmberSans, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    displayMedium = Typography.displayMedium.copy(fontFamily = AmberSans, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    displaySmall = Typography.displaySmall.copy(fontFamily = AmberSans, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = Typography.headlineLarge.copy(fontFamily = AmberSans, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = Typography.headlineMedium.copy(fontFamily = AmberSans, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = Typography.headlineSmall.copy(fontFamily = AmberSans, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = Typography.titleLarge.copy(fontFamily = AmberSans, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold),
    titleMedium = Typography.titleMedium.copy(fontFamily = AmberSans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    titleSmall = Typography.titleSmall.copy(fontFamily = AmberSans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Typography.bodyLarge.copy(fontFamily = AmberSans, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = Typography.bodyMedium.copy(fontFamily = AmberSans, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = Typography.bodySmall.copy(fontFamily = AmberSans, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = Typography.labelLarge.copy(fontFamily = AmberSans, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelMedium = Typography.labelMedium.copy(fontFamily = AmberSans, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = Typography.labelSmall.copy(fontFamily = AmberSans, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
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
    val settings by rememberUserSettingsState()
    val themePackageManager = koinInject<ThemePackageManager>()
    val tryOn by themePackageManager.tryOn.collectAsStateWithLifecycle()
    // A prepared Agent candidate is rendered globally, but remains only in the manager's
    // in-memory try-on. Settings/LocalSettings continue to expose the persisted value.
    val displaySetting = tryOn?.candidate ?: settings.displaySetting
    val themePack = displaySetting.themePack
    val themeDesign = themePack?.design
    val effectiveAmoledDarkMode =
        darkTheme && amoledDarkMode && themeDesign?.dark == null
    val explicitSystemBarBackground = themePack?.let { pack ->
        if (effectiveAmoledDarkMode) {
            AMOLED_DARK_BACKGROUND
        } else {
            val palette = if (darkTheme) themeDesign?.dark else themeDesign?.light
            palette?.background?.let(::parseThemeColor)
                ?: themePackPaperTokens(pack.paper, darkTheme)?.bg
        }
    }
    val visualDark = explicitSystemBarBackground?.let { color ->
        val rgb = color.toArgb() and 0xFFFFFF
        themeContrast(rgb, 0xFFFFFF) > themeContrast(rgb, 0x000000)
    } ?: darkTheme

    val colorScheme = if (visualDark) NotionDarkScheme else NotionLightScheme
    val colorSchemeConverted = remember(effectiveAmoledDarkMode, colorScheme) {
        if (effectiveAmoledDarkMode) {
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
    val extendColors = if (visualDark) ExtendDarkColors else ExtendLightColors

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
                val barStyle = if (visualDark) {
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
                    isAppearanceLightStatusBars = !visualDark
                    isAppearanceLightNavigationBars = !visualDark
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }

    // Graphite redesign (D2/D3): base family × system dark-mode → one of 4 bases; the accent is
    // an independent user setting. chatTheme is derived from the new tokens via the compat adapter
    // so existing LocalChatTheme consumers work.
    val amberBase = when {
        displaySetting.amberBaseFamily == "SAGE" && darkTheme -> AmberBase.SAGE_DARK
        displaySetting.amberBaseFamily == "SAGE" -> AmberBase.SAGE
        darkTheme -> AmberBase.DARK
        else -> AmberBase.LIGHT
    }
    val themeBaseTokens = remember(themePack?.paper, amberBase, darkTheme) {
        themePack?.let { themePackPaperTokens(it.paper, darkTheme) } ?: baseTokens(amberBase)
    }
    val amberAccent = themePack?.accentHex?.let(::parseThemeColor)
        ?: parseAccent(displaySetting.accentColor)
    val amberTokens = remember(themeBaseTokens, amberAccent, effectiveAmoledDarkMode, visualDark, themeDesign, themePack?.inkHex) {
        val tokens = buildAmberTokens(themeBaseTokens, amberAccent, themeDesign).copy(isDark = visualDark)
        val documentInk = themePack?.inkHex?.let(::parseThemeColor)
        val withDocumentInk = if (documentInk != null) {
            // Portable themes use the same accent-filled user bubble as iOS.
            tokens.copy(accentInk = documentInk, userBg = tokens.accent, userInk = documentInk)
        } else tokens
        if (effectiveAmoledDarkMode) withDocumentInk.copy(
            bg = AMOLED_DARK_BACKGROUND,
            surface = Color(0xFF050505),
            surface2 = Color(0xFF101010),
            raised = Color(0xFF141414),
            codeBg = Color(0xFF101010),
        ) else withDocumentInk
    }
    val chatTheme = remember(amberTokens) { amberTokens.toChatTheme() }
    val hasCustomPalette = (if (darkTheme) themeDesign?.dark else themeDesign?.light) != null
    val controlAccent = remember(amberTokens, hasCustomPalette) {
        if (hasCustomPalette) amberTokens.readableControlAccent() else amberTokens.accent
    }
    val onControlAccent = if (controlAccent == chatTheme.accent) chatTheme.onAccent else accentInkFor(controlAccent)

    val themedColorScheme = remember(colorSchemeConverted, chatTheme, effectiveAmoledDarkMode, controlAccent, onControlAccent) {
        run {
            // AmoledDark 时保留纯黑 bg/surface（省电），其他主题 token 仍跟 chatTheme
            val useAmoledBlack = effectiveAmoledDarkMode
            colorSchemeConverted.copy(
                background = if (useAmoledBlack) colorSchemeConverted.background else chatTheme.bg,
                onBackground = chatTheme.ink,
                surface = if (useAmoledBlack) colorSchemeConverted.surface else chatTheme.paper,
                onSurface = chatTheme.ink,
                surfaceVariant = if (useAmoledBlack) colorSchemeConverted.surfaceVariant else chatTheme.toolPillBg,
                onSurfaceVariant = chatTheme.inkSoft,
                // Keep the five surface levels aligned with the active Amber tokens.
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
                primary = controlAccent,
                onPrimary = onControlAccent,
                primaryContainer = chatTheme.accentSoft.compositeOver(chatTheme.paper),
                onPrimaryContainer = controlAccent,
                primaryFixed = chatTheme.accentSoft.compositeOver(chatTheme.paper),
                primaryFixedDim = chatTheme.accentSoft.compositeOver(chatTheme.paper),
                onPrimaryFixed = controlAccent,
                onPrimaryFixedVariant = controlAccent,
                secondary = controlAccent,
                onSecondary = onControlAccent,
                secondaryContainer = chatTheme.accentSoft.compositeOver(chatTheme.paper),
                onSecondaryContainer = controlAccent,
                secondaryFixed = chatTheme.accentSoft.compositeOver(chatTheme.paper),
                secondaryFixedDim = chatTheme.accentSoft.compositeOver(chatTheme.paper),
                onSecondaryFixed = controlAccent,
                onSecondaryFixedVariant = controlAccent,
                // Keep tertiary surfaces in the same accent family; dark themes use the softer fill.
                tertiary = controlAccent,
                onTertiary = onControlAccent,
                tertiaryContainer = if (chatTheme.isDark) chatTheme.accentSoft else chatTheme.accentTint,
                onTertiaryContainer = controlAccent,
                tertiaryFixed = if (chatTheme.isDark) chatTheme.accentSoft else chatTheme.accentTint,
                tertiaryFixedDim = if (chatTheme.isDark) chatTheme.accentSoft else chatTheme.accentTint,
                onTertiaryFixed = controlAccent,
                onTertiaryFixedVariant = controlAccent,
                // Snackbar inverse colors use the active ink/paper pair in both base modes.
                inverseSurface = chatTheme.ink,
                inverseOnSurface = chatTheme.paper,
                inversePrimary = chatTheme.accentTint,
                outline = chatTheme.outlineStrong,
                outlineVariant = chatTheme.outlineSoft,
                error = Color(0xFFC2554E),
                onError = Color.Black,
                errorContainer = Color(0xFFC2554E).copy(alpha = 0.12f).compositeOver(chatTheme.paper),
                onErrorContainer = chatTheme.ink,
            )
        }
    }

    val textWeight = settings.displaySetting.textWeight
    val fontResolver = rememberTextWeightResolver(textWeight)
    val legacyWeightOffset = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) textWeight.offset else 0
    val amberType = remember(legacyWeightOffset) { themeAmberTextStyles(null, legacyWeightOffset) }
    val typography = remember(legacyWeightOffset) { themeTypography(null, legacyWeightOffset) }

    CompositionLocalProvider(
        LocalFontFamilyResolver provides fontResolver,
        LocalLegacyTextWeightOffset provides legacyWeightOffset,
        LocalDarkMode provides visualDark,
        LocalDarkThemePalette provides darkTheme,
        LocalAmoledDarkMode provides effectiveAmoledDarkMode,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null,
        LocalAmberTokens provides amberTokens,
        LocalThemePack provides themePack,
        LocalThemeDesign provides themeDesign,
        LocalThemeCanvasStyle provides themePack?.canvasStyle,
        LocalAmberType provides amberType,
        app.amber.feature.ui.pages.chat.LocalChatTheme provides chatTheme,
        // Bug fix: M3 LocalContentColor 默认 Color.Black. 没有 Surface 显式 provide 时
        // (如 ChatPage 直接放 MarkdownBlock 的无 bubble 渲染路径), 深色模式下文字仍渲染成黑色.
        // 这里显式 provide onSurface (= chatTheme.ink) 作为全局默认前景色.
        LocalContentColor provides themedColorScheme.onSurface,
    ) {
        MaterialTheme(
            colorScheme = themedColorScheme,
            typography = typography,
            shapes = themeShapes(themeDesign),
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

private fun parseThemeColor(hex: String): Color? = themeRgb(hex)?.let(::opaqueColor)

internal fun chromeFontFamily(typeface: String?): FontFamily? = when (typeface) {
    null -> null
    "system" -> FontFamily.Default
    "rounded" -> HankenGrotesk
    "serif" -> NotoSerifSC
    "monospace" -> JetBrainsMonoFamily
    else -> null
}

internal fun themeTypography(typeface: String?, weightOffset: Int = 0): androidx.compose.material3.Typography {
    val family = chromeFontFamily(typeface)
    if (family == null && weightOffset == 0) return AmberTypography
    return AmberTypography.copy(
        displayLarge = AmberTypography.displayLarge.withTypeface(family, weightOffset),
        displayMedium = AmberTypography.displayMedium.withTypeface(family, weightOffset),
        displaySmall = AmberTypography.displaySmall.withTypeface(family, weightOffset),
        headlineLarge = AmberTypography.headlineLarge.withTypeface(family, weightOffset),
        headlineMedium = AmberTypography.headlineMedium.withTypeface(family, weightOffset),
        headlineSmall = AmberTypography.headlineSmall.withTypeface(family, weightOffset),
        titleLarge = AmberTypography.titleLarge.withTypeface(family, weightOffset),
        titleMedium = AmberTypography.titleMedium.withTypeface(family, weightOffset),
        titleSmall = AmberTypography.titleSmall.withTypeface(family, weightOffset),
        bodyLarge = AmberTypography.bodyLarge.withTypeface(family, weightOffset),
        bodyMedium = AmberTypography.bodyMedium.withTypeface(family, weightOffset),
        bodySmall = AmberTypography.bodySmall.withTypeface(family, weightOffset),
        labelLarge = AmberTypography.labelLarge.withTypeface(family, weightOffset),
        labelMedium = AmberTypography.labelMedium.withTypeface(family, weightOffset),
        labelSmall = AmberTypography.labelSmall.withTypeface(family, weightOffset),
    )
}
