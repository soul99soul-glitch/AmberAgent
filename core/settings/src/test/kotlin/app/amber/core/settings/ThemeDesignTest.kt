package app.amber.core.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDesignTest {
    private val light = ThemeDesign.Palette(
        background = "#F3F0EB",
        surface = "0xFAF9F7",
        foreground = "1C1B19",
        mutedForeground = "#6A6560",
        border = "#D4CFC7",
    )
    private val dark = ThemeDesign.Palette(
        background = "#14110E",
        surface = "#221E19",
        foreground = "#F5F0E8",
        mutedForeground = "#A89888",
        border = "#3D342C",
    )

    @Test
    fun `portable design round trips all render and preserved brand fields`() {
        val design = ThemeDesign(
            light = light,
            dark = dark,
            gradient = ThemeDesign.Gradient(
                colors = listOf("#F3F0EB", "#FAF9F7"),
                darkColors = listOf("#14110E", "#221E19"),
                angle = 37.0,
            ),
            patterns = listOf(ThemeDesign.Pattern("dots", "#D4CFC7", 0.12, 18.0, 1.5)),
            components = ThemeDesign.Components(
                cardRadius = 14.0,
                brandText = "Amber",
                brandSize = 28.0,
                brandTracking = 1.25,
            ),
        )

        val encoded = Json.encodeToString(design)
        val decoded = Json.decodeFromString<ThemeDesign>(encoded)

        assertEquals(design, decoded)
        assertTrue(decoded.validationIssues().toString(), decoded.validationIssues().isEmpty())
        assertTrue(encoded.contains("brandText"))
    }

    @Test
    fun `invalid text contrast and missing paired gradient palettes are reported`() {
        val design = ThemeDesign(
            light = light.copy(foreground = light.background),
            gradient = ThemeDesign.Gradient(
                colors = listOf("#FFFFFF", "#FFFFFF"),
                darkColors = listOf("#111111", "#111111"),
            ),
        )

        val issues = design.validationIssues()

        assertTrue(issues.any { it.contains("light.foreground") })
        assertTrue(issues.any { it.contains("gradient 需要同时提供 light 和 dark") })
    }

    @Test
    fun `rgb parser accepts portable forms and rejects alpha`() {
        assertEquals(0xB8623A, themeRgb("#B8623A"))
        assertEquals(0xB8623A, themeRgb("0xB8623A"))
        assertEquals(0xB8623A, themeRgb("b8623a"))
        assertEquals(null, themeRgb("#FFB8623A"))
        assertEquals(21.0, themeContrast(0x000000, 0xFFFFFF), 0.0001)
        assertFalse(ThemeDesign().validationIssues().isNotEmpty())
    }
}
