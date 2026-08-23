import Combine
import Foundation
import SwiftUI

@MainActor
final class WatchTaskViewModel: ObservableObject {
    @Published private(set) var snapshot: WatchTaskSnapshot = .idle
    @Published private(set) var statusMessage: String?
    @Published private(set) var isSending = false
    @Published var draftAnswer: String = ""
    @Published var isDictating = false

    private let bridge: WatchConnectivityBridge
    private var lastRequestId: String?

    init(bridge: WatchConnectivityBridge = .shared) {
        self.bridge = bridge
    }

    func start() {
        bridge.configure()
        bridge.onSnapshotUpdated = { [weak self] snapshot in
            self?.snapshot = snapshot
        }
        bridge.onActionResult = { [weak self] result in
            guard let self, result.requestId == self.lastRequestId else { return }
            self.lastRequestId = nil
            self.isSending = false
            if let snapshot = result.snapshot {
                self.snapshot = snapshot
            }
            self.statusMessage = result.message
            if result.accepted {
                self.draftAnswer = ""
                self.isDictating = false
            }
        }
        bridge.activateIfNeeded()
        bridge.requestSnapshotFromPhone()
        snapshot = bridge.latestSnapshot
    }

    func refresh() {
        bridge.requestSnapshotFromPhone()
    }

    func approve() {
        send(action: .approve, optionId: "approve")
    }

    func deny() {
        send(action: .deny, optionId: "deny")
    }

    func choose(optionId: String) {
        if optionId == "open-phone" {
            openOnPhone()
            return
        }
        if optionId == "dictate" {
            isDictating = true
            return
        }
        send(action: .choose, optionId: optionId)
    }

    func submitDraftAnswer() {
        let text = draftAnswer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            statusMessage = "请先输入或语音识别内容"
            return
        }
        send(action: .answer, text: text)
    }

    func cancel() {
        send(action: .cancel)
    }

    func openOnPhone() {
        send(action: .openOnPhone)
    }

    private func send(
        action: WatchInboundAction,
        optionId: String? = nil,
        text: String? = nil
    ) {
        guard snapshot.isActive || action == .refresh else {
            statusMessage = "当前没有任务"
            return
        }
        isSending = true
        statusMessage = nil
        let requestId = UUID().uuidString
        lastRequestId = requestId
        let request = WatchTaskActionRequest(
            requestId: requestId,
            runId: snapshot.runId,
            conversationId: snapshot.conversationId,
            decisionId: snapshot.decision?.id,
            action: action,
            optionId: optionId,
            text: text,
            createdAt: Date()
        )
        bridge.sendAction(request)
    }
}
