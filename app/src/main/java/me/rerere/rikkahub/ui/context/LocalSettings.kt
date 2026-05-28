package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import app.amber.core.settings.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
