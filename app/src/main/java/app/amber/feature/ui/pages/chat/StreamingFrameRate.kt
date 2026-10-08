package app.amber.feature.ui.pages.chat

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.preferredFrameRate

internal const val StreamingFrameRateHz = 120f

@Composable
private fun rememberAdaptiveRefreshRateSupport(): Boolean {
    val display = LocalView.current.display
    return remember(display) {
        Build.VERSION.SDK_INT >= 36 && display?.hasArrSupport() == true
    }
}

/** Let ARR schedule redraws; use a window hint only without reported ARR support. */
@Composable
internal fun PreferStreamingFrameRate(
    active: Boolean,
    adaptiveRefreshRate: Boolean = rememberAdaptiveRefreshRateSupport(),
) {
    val activity = LocalContext.current as? Activity ?: return
    PreferPma110StreamingFrameRate(active)
    val requestWindowRate = active && !adaptiveRefreshRate
    DisposableEffect(activity.window, requestWindowRate) {
        val window = activity.window
        val previous = window.attributes.preferredRefreshRate
        if (requestWindowRate) {
            window.attributes = window.attributes.apply {
                preferredRefreshRate = StreamingFrameRateHz
            }
        }
        onDispose {
            if (requestWindowRate) {
                window.attributes = window.attributes.apply {
                    preferredRefreshRate = previous
                }
            }
        }
    }
}

/** Vote only on redraws; ARR can choose a rate within the current display mode. */
@Composable
internal fun Modifier.streamingFrameRate(active: Boolean): Modifier {
    val adaptiveRefreshRate = rememberAdaptiveRefreshRateSupport()
    return when {
        !active -> this
        adaptiveRefreshRate -> preferredFrameRate(FrameRateCategory.High)
        else -> preferredFrameRate(StreamingFrameRateHz)
    }
}
