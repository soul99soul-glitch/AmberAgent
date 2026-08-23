package app.amber.feature.ui.hooks

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
    shouldScroll: () -> Boolean = { true },
    onProgrammaticScrollStart: () -> Unit = {},
    onProgrammaticScrollEnd: () -> Unit = {},
) {
    val ime = WindowInsets.ime
    val localDensity = LocalDensity.current
    val shouldScrollState by rememberUpdatedState(shouldScroll)
    val onProgrammaticScrollStartState by rememberUpdatedState(onProgrammaticScrollStart)
    val onProgrammaticScrollEndState by rememberUpdatedState(onProgrammaticScrollEnd)
    var imeHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            ime.getBottom(localDensity)
        }.collect { keyboardHeight ->
            val delta = keyboardHeight - imeHeight
            if (keyboardHeight <= 0) {
                imeHeight = 0
                return@collect
            }
            // Keep the latest message visible while the keyboard rises, but do
            // not reverse-scroll during keyboard dismissal. On send, the input
            // clears, the message inserts, and IME starts closing; applying the
            // negative close delta here makes the freshly sent user bubble
            // appear to "jump" once more after it has already settled.
            if (delta > 0 && shouldScrollState()) {
                onProgrammaticScrollStartState()
                try {
                    lazyListState.scrollBy(delta.toFloat())
                } finally {
                    onProgrammaticScrollEndState()
                }
            }
            imeHeight = keyboardHeight
        }
    }
}
