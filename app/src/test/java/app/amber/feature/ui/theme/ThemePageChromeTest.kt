package app.amber.feature.ui.theme

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import app.amber.agent.Screen
import app.amber.core.settings.ThemePackDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePageChromeTest {
    @Test
    fun `entry metadata classifies home shell and content entries`() {
        val provider = entryProvider<NavKey> {
            entry<Screen.SessionHome>(metadata = themePageChromeMetadata(ThemePageChrome.Home)) {}
            entry<Screen.Setting>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {}
            entry<Screen.SettingAppearance>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {}
            entry<Screen.Chat> {}
        }

        val home = provider(Screen.SessionHome)
        val shell = provider(Screen.Setting)
        val appearance = provider(Screen.SettingAppearance)
        val content = provider(Screen.Chat(id = "conversation"))

        assertTrue(home.contentKey is String)
        assertEquals(ThemePageChrome.Home, themePageChromeFrom(home.metadata))
        assertEquals(ThemePageChrome.Shell, themePageChromeFrom(shell.metadata))
        assertEquals(ThemePageChrome.Shell, themePageChromeFrom(appearance.metadata))
        assertEquals(ThemePageChrome.Content, themePageChromeFrom(content.metadata))
    }

    @Test
    fun `canvas scope defaults to home only while no pack keeps legacy canvas everywhere`() {
        val omittedScope = ThemePackDocument(
            id = "omitted-scope",
            displayName = "Omitted scope",
            paper = "paper",
            accentHex = "#B8623A",
            inkHex = "#000000",
            canvasStyle = "dotGrid",
            brandMark = "systemWordmark",
            shortcutIconStyle = "systemOutline",
            chromeTypeface = "system",
        )

        assertTrue(allowsThemeCanvasOverlay(omittedScope, ThemePageChrome.Home))
        assertFalse(allowsThemeCanvasOverlay(omittedScope, ThemePageChrome.Shell))
        assertFalse(allowsThemeCanvasOverlay(omittedScope, ThemePageChrome.Content))
        val shellScope = omittedScope.copy(canvasScope = "shell")
        assertTrue(allowsThemeCanvasOverlay(shellScope, ThemePageChrome.Home))
        assertTrue(allowsThemeCanvasOverlay(shellScope, ThemePageChrome.Shell))
        assertFalse(allowsThemeCanvasOverlay(shellScope, ThemePageChrome.Content))
        assertTrue(allowsThemeCanvasOverlay(omittedScope.copy(canvasScope = "appWide"), ThemePageChrome.Content))
        assertTrue(allowsThemeCanvasOverlay(null, ThemePageChrome.Home))
        assertTrue(allowsThemeCanvasOverlay(null, ThemePageChrome.Shell))
        assertTrue(allowsThemeCanvasOverlay(null, ThemePageChrome.Content))
    }
}
