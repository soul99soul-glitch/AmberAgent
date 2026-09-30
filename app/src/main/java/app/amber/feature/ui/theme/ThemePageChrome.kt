package app.amber.feature.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import app.amber.core.settings.ThemePackDocument

/** Route-local visual role used by the portable canvas and chrome settings. */
internal enum class ThemePageChrome {
    Home,
    Shell,
    Content,
}

internal val LocalThemePageChrome = staticCompositionLocalOf { ThemePageChrome.Content }

internal const val THEME_PAGE_CHROME_METADATA_KEY = "app.amber.theme.pageChrome"

internal fun themePageChromeMetadata(chrome: ThemePageChrome): Map<String, String> =
    mapOf(THEME_PAGE_CHROME_METADATA_KEY to chrome.name)

/** Missing metadata is Content so newly added routes stay inside the narrowest scope. */
internal fun themePageChromeFrom(metadata: Map<String, Any>): ThemePageChrome =
    when (metadata[THEME_PAGE_CHROME_METADATA_KEY]) {
        ThemePageChrome.Home.name -> ThemePageChrome.Home
        ThemePageChrome.Shell.name -> ThemePageChrome.Shell
        else -> ThemePageChrome.Content
    }

/** No portable pack keeps the legacy app-wide canvas; omitted v1 scope defaults to homeOnly. */
internal fun allowsThemeCanvasOverlay(
    themePack: ThemePackDocument?,
    pageChrome: ThemePageChrome,
): Boolean {
    themePack ?: return true
    return when (themePack.canvasScope ?: "homeOnly") {
        "homeOnly" -> pageChrome == ThemePageChrome.Home
        "shell" -> pageChrome != ThemePageChrome.Content
        "appWide" -> true
        else -> false
    }
}

@Composable
internal fun rememberThemePageChromeDecorator(): NavEntryDecorator<NavKey> = remember {
    NavEntryDecorator<NavKey>(decorate = { entry ->
        val pageChrome = themePageChromeFrom(entry.metadata)
        ProvideThemePageChrome(pageChrome) {
            entry.Content()
        }
    })
}

@Composable
private fun ProvideThemePageChrome(
    pageChrome: ThemePageChrome,
    content: @Composable () -> Unit,
) {
    val themePack = LocalThemePack.current
    val usesDocumentTypeface = when (pageChrome) {
        ThemePageChrome.Home -> themePack != null
        ThemePageChrome.Shell -> themePack?.settingsChrome == true
        ThemePageChrome.Content -> false
    }
    CompositionLocalProvider(LocalThemePageChrome provides pageChrome) {
        if (themePack == null || !usesDocumentTypeface) {
            content()
        } else {
            val typeface = themePack.chromeTypeface
            CompositionLocalProvider(LocalAmberType provides themeAmberTextStyles(typeface)) {
                MaterialTheme(typography = themeTypography(typeface), content = content)
            }
        }
    }
}
