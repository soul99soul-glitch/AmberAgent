package app.amber.feature.macgateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProactiveDetailRequest(val signalId: String, val gatewayId: String?, val deviceId: String?) {
    fun matches(connection: MacGatewayConnection?): Boolean =
        gatewayId == null && deviceId == null ||
            connection != null && gatewayId == connection.gatewayId && deviceId == connection.deviceId
}

/**
 * Single-slot inbox holding a pending proactive `signalId` delivered by a deep link
 * (`amber://proactive/<signalId>`) until the UI consumes it once.
 */
class ProactiveDeepLinkInbox {
    private val _pending = MutableStateFlow<ProactiveDetailRequest?>(null)
    val pending: StateFlow<ProactiveDetailRequest?> = _pending.asStateFlow()

    fun submit(signalId: String, gatewayId: String? = null, deviceId: String? = null) {
        val trimmed = signalId.trim()
        if (!ProactiveContract.isValidSignalId(trimmed)) return
        _pending.value = ProactiveDetailRequest(trimmed, gatewayId, deviceId)
    }

    fun consume(request: ProactiveDetailRequest) {
        _pending.compareAndSet(request, null)
    }
}
