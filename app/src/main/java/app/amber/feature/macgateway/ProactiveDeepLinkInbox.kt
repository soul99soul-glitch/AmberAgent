package app.amber.feature.macgateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single-slot inbox holding a pending proactive `signalId` delivered by a deep link
 * (`amber://proactive/<signalId>`) until the UI consumes it once.
 */
class ProactiveDeepLinkInbox {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun submit(signalId: String) {
        val trimmed = signalId.trim()
        if (!ProactiveContract.isValidSignalId(trimmed)) return
        _pending.value = trimmed
    }

    fun consume(signalId: String) {
        val trimmed = signalId.trim()
        _pending.update { current -> if (current == trimmed) null else current }
    }
}
