package app.amber.feature.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import app.amber.feature.ui.theme.ColorMode

@Composable
fun rememberColorMode(): MutableState<ColorMode> {
    val preference = rememberSharedPreferenceString("colorMode", "SYSTEM")
    return remember(preference) {
        object : MutableState<ColorMode> {
            override var value: ColorMode
                get() = ColorMode.entries.firstOrNull { it.name == preference.value } ?: ColorMode.SYSTEM
                set(value) {
                    preference.value = value.name
                }

            override fun component1(): ColorMode = value

            override fun component2(): (ColorMode) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberAmoledDarkMode(): MutableState<Boolean> {
    return rememberSharedPreferenceBoolean("amoledDark", false)
}
