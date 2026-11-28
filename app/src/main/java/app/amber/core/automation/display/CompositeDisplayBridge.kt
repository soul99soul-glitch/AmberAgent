package app.amber.core.automation.display

import kotlinx.coroutines.CancellationException

/** Choose a transport once at session creation. A live session never migrates between transports. */
class CompositeDisplayBridge(
    private val primary: DisplayBridge,
    private val fallback: DisplayBridge,
) : DisplayBridge {
    override suspend fun connect(onDisconnected: (String) -> Unit): Result<ConnectedDisplayBridge> {
        val first = primary.connect(onDisconnected)
        val failure = first.exceptionOrNull() ?: return first
        if (failure is CancellationException) throw failure
        if (failure is DisplayHelperBusyException) return first
        val second = fallback.connect(onDisconnected)
        val fallbackError = second.exceptionOrNull() ?: return second
        if (fallbackError is CancellationException) throw fallbackError
        return Result.failure(IllegalStateException("wireless_adb: ${failure.message}, shizuku: ${fallbackError.message}", failure))
    }
}
