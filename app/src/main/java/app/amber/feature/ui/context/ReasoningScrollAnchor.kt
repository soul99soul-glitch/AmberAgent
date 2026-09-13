package app.amber.feature.ui.context

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Only explicit reasoning toggles opt in; streaming growth keeps the timeline's normal anchor. */
internal val LocalReasoningScrollAnchor = staticCompositionLocalOf<((() -> Float?) -> Unit)?> { null }

@Composable
internal fun rememberReasoningScrollAnchor(state: LazyListState): (() -> Float?) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(state, scope) {
        var adjustment: Job? = null
        { position: () -> Float? ->
            val target = position()
            if (target != null) {
                adjustment?.cancel()
                adjustment = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    // A user drag cancels this Default-priority mutation. Keep following the
                    // title only for the duration of the existing expand/shrink animations.
                    state.scroll(MutatePriority.Default) {
                        val started = withFrameNanos { it }
                        var frame = started
                        while (frame - started < 650_000_000L) {
                            val current = position() ?: break
                            val delta = target - current
                            if (abs(delta) > 0.5f) scrollBy(delta)
                            frame = withFrameNanos { it }
                        }
                    }
                }
            }
        }
    }
}
