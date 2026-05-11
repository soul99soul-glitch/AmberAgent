package me.rerere.rikkahub.data.agent.webmount.primitives

/**
 * Routes JsBridge network events into a per-session [NetworkLog]. Wired into
 * each pooled session by [WebViewPool] so the bridge's `onNetworkEvent` JS
 * callback ends up in the buffer that `wm_state` reads.
 */
internal class NetworkLogObserver(private val log: NetworkLog) : JsBridge.Observer {
    override fun onEvent(event: JsBridge.BridgeEvent) {
        if (event is JsBridge.BridgeEvent.Network) {
            log.record(event.payload)
        }
    }
}
