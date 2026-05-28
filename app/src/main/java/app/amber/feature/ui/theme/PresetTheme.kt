package app.amber.feature.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import app.amber.feature.ui.theme.presets.AmberAgentClashThemePreset
import app.amber.feature.ui.theme.presets.AutumnThemePreset
import app.amber.feature.ui.theme.presets.BlackThemePreset
import app.amber.feature.ui.theme.presets.OceanThemePreset
import app.amber.feature.ui.theme.presets.SakuraThemePreset
import app.amber.feature.ui.theme.presets.SpringThemePreset

data class PresetTheme(
    val id: String,
    val name: @Composable () -> Unit,
    val standardLight: ColorScheme,
    val standardDark: ColorScheme,
) {
    fun getColorScheme(dark: Boolean): ColorScheme {
        return if (dark) standardDark else standardLight
    }
}

val PresetThemes by lazy {
    listOf(
        AmberAgentClashThemePreset,
        SakuraThemePreset,
        OceanThemePreset,
        SpringThemePreset,
        AutumnThemePreset,
        BlackThemePreset,
    )
}

fun findPresetTheme(id: String): PresetTheme {
    return PresetThemes.find { it.id == id } ?: AmberAgentClashThemePreset
}
