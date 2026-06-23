import SwiftUI
@preconcurrency import Shared

/// Live output model for a running subagent, observed by `ChatToolDetailSheet`.
///
/// When a subagent is still running, this streams its generation token-by-token
/// from the KMP `SubAgentManager` live flow. When no live flow is available
/// (run finished and evicted, or never started), the sheet falls back to the
/// stored `tool.output`. Wired to the KMP `observeLive` bridge in the live
/// streaming pass; until then `start()`/`stop()` are inert and the sheet shows
/// the stored output.
@MainActor
@Observable
final class SubAgentLiveModel {
    private(set) var text: String = ""
    private(set) var isRunning: Bool = false

    func start() async {}
    func stop() {}
}
