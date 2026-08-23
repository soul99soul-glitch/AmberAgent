package app.amber.feature.ui.context

import androidx.compose.runtime.compositionLocalOf
import app.amber.feature.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
