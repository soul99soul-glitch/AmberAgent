import SwiftUI
@preconcurrency import Shared

/// Live output model for a running subagent, observed by `ChatToolDetailSheet`.
///
/// The subagent engine (`IOSAgentToolEngine`) streams each turn and pushes the
/// accumulating assistant text here via `ingest`. The sheet shows `text` live and
/// flips off the running spinner on `finish`. When no live model is registered
/// for a tool call (e.g. the run finished before the app session, or it was
/// evicted), the sheet falls back to the stored `tool.output`.
@MainActor
@Observable
final class SubAgentLiveModel {
    private(set) var text: String = ""
    private(set) var isRunning: Bool = true

    func ingest(_ newText: String) {
        // The engine streams the FULL accumulated text each time, and the
        // per-chunk `Task { @MainActor }` hops aren't ordered — so a stale
        // (shorter) update can land after a newer one. Only move forward.
        guard newText.count >= text.count else { return }
        text = newText
    }

    func finish() {
        isRunning = false
    }

    // The engine drives updates; these satisfy the sheet's `.task` / `.onDisappear`.
    func start() async {}
    func stop() {}
}

/// Process-wide registry linking a subagent dispatch tool call (by `toolCallId`)
/// to its live model, so the chat detail sheet can find the live stream for the
/// capsule the user tapped. The engine path (SubAgentRunner) registers a model
/// when a dispatch starts; the sheet looks it up by the tool's id.
@MainActor
final class SubAgentLiveRegistry {
    static let shared = SubAgentLiveRegistry()

    private var models: [String: SubAgentLiveModel] = [:]

    func register(toolCallId: String, _ model: SubAgentLiveModel) {
        guard !toolCallId.isEmpty else { return }
        // Crude cap — live models are transient and small; avoid unbounded growth.
        if models.count > 64 { models.removeAll() }
        models[toolCallId] = model
    }

    func model(forToolCallId id: String) -> SubAgentLiveModel? {
        models[id]
    }
}
