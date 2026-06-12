package app.amber.feature.ui.pages.chat

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

internal fun createStreamingBottomFollowEvents(): MutableSharedFlow<String> =
    MutableSharedFlow(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
