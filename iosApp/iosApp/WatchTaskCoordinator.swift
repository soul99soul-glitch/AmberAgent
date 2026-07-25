import Foundation
@preconcurrency import Shared

extension Notification.Name {
    static let amberWatchOpenTask = Notification.Name("app.amber.ios.watch.openTask")
}

struct WatchTaskReconnectProjection: Equatable, Sendable {
    let runId: String
    let conversationId: String
}

/// Owns the current watch-facing task snapshot and translates Watch intents
/// into ChatViewModel / ChatGenerationCoordinator actions.
@MainActor
final class WatchTaskCoordinator: WatchTaskActionHandling {
    static let shared = WatchTaskCoordinator()

    private weak var chatViewModel: ChatViewModel?
    private let bridge: WatchConnectivityBridge
    private var currentRunId: String?
    private var currentConversationId: String?
    private var currentPresentation: AgentActivityPresentation?
    private var currentDecision: WatchDecision?
    private var currentSummary: String?
    private var pendingAskUser: WatchAskUserRequest?

    init(bridge: WatchConnectivityBridge = .shared) {
        self.bridge = bridge
    }

    func attach(
        chatViewModel: ChatViewModel,
        reconnecting: WatchTaskReconnectProjection? = nil
    ) {
        self.chatViewModel = chatViewModel
        bridge.configure(actionHandler: self)
        bridge.activateIfNeeded()
        if currentPresentation == nil {
            if let reconnecting {
                publish(
                    runId: reconnecting.runId,
                    conversationId: reconnecting.conversationId,
                    presentation: .reconnecting(kind: .response)
                )
            } else {
                bridge.clear()
            }
        } else {
            republish()
        }
    }

    func publish(
        runId: String,
        conversationId: String?,
        presentation: AgentActivityPresentation,
        summary: String? = nil,
        decision: WatchDecision? = nil
    ) {
        if currentRunId != runId {
            currentSummary = nil
            currentDecision = nil
            pendingAskUser = nil
        }
        currentRunId = runId
        currentConversationId = conversationId
        currentPresentation = presentation
        if let summary {
            currentSummary = WatchTaskText.clipped(summary, maxLength: 280)
        }
        if presentation.phase == .completed
            || presentation.phase == .failed
            || presentation.phase == .cancelled {
            currentDecision = nil
            pendingAskUser = nil
        } else if let decision {
            currentDecision = decision
        } else if presentation.phase != .waitingForUser {
            // Running/tool stages replace a previous decision node.
            currentDecision = nil
            pendingAskUser = nil
        }
        republish()
    }

    func publishWaitingApproval(
        runId: String,
        conversationId: String?,
        prompt: ChatToolApprovalPrompt
    ) {
        pendingAskUser = nil
        publish(
            runId: runId,
            conversationId: conversationId,
            presentation: .waitingForUser(kind: prompt.activityKind),
            decision: WatchTaskSnapshotBuilder.decision(from: prompt)
        )
    }

    func publishAskUser(
        runId: String,
        conversationId: String?,
        request: WatchAskUserRequest
    ) {
        pendingAskUser = request
        publish(
            runId: runId,
            conversationId: conversationId,
            presentation: .waitingForUser(kind: .workflow),
            decision: WatchTaskSnapshotBuilder.askUserDecision(from: request)
        )
    }

    func publishCompleted(
        runId: String,
        conversationId: String?,
        summary: String?,
        kind: AgentActivityKind = .response
    ) {
        publish(
            runId: runId,
            conversationId: conversationId,
            presentation: AgentActivityPresentation(
                kind: kind,
                phase: .completed,
                stage: .completed,
                action: .viewResult
            ),
            summary: summary,
            decision: nil
        )
    }

    func clear(runId: String? = nil) {
        if let runId, currentRunId != nil, currentRunId != runId {
            return
        }
        currentRunId = nil
        currentConversationId = nil
        currentPresentation = nil
        currentDecision = nil
        currentSummary = nil
        pendingAskUser = nil
        bridge.clear()
    }

    func currentSnapshot() -> WatchTaskSnapshot {
        bridge.latestSnapshot
    }

    func handleWatchAction(_ request: WatchTaskActionRequest) async -> WatchTaskActionResult {
        switch request.action {
        case .refresh:
            return accepted(request, message: nil)
        case .openOnPhone:
            guard let conversationId = currentConversationId, !conversationId.isEmpty else {
                return rejected(request, "当前任务没有可打开的会话")
            }
            let focus: String
            if currentDecision?.type == .approval
                || currentDecision?.type == .askUser
                || currentDecision?.type == .voiceReply {
                focus = "confirmation"
            } else if currentPresentation?.phase == .completed {
                focus = "result"
            } else {
                focus = "task"
            }
            NotificationCenter.default.post(
                name: .amberWatchOpenTask,
                object: nil,
                userInfo: [
                    "runId": request.runId,
                    "conversationId": conversationId,
                    "focus": focus
                ]
            )
            return accepted(request, message: "已在 iPhone 打开任务")
        case .cancel:
            guard let chatViewModel,
                  let runId = currentRunId,
                  request.runId == runId else {
                return rejected(request, "当前没有可取消的任务")
            }
            // The run may already belong to the background coordinator. Only
            // report success after the current foreground/background owner accepts it.
            guard chatViewModel.cancelGeneration(runId: runId) else {
                return rejected(request, "当前任务已经结束或不再由 iPhone 执行")
            }
            return accepted(request, message: "已取消")
        case .approve, .deny:
            return await handleApproval(request)
        case .choose, .answer:
            return await handleAskUser(request)
        }
    }

    private func handleApproval(_ request: WatchTaskActionRequest) async -> WatchTaskActionResult {
        guard let decision = currentDecision,
              decision.type == .approval,
              request.decisionId == decision.id else {
            return rejected(request, "这个确认步骤已失效")
        }
        guard let chatViewModel else {
            return rejected(request, "iPhone 聊天状态不可用")
        }
        guard let runId = currentRunId,
              request.runId == runId,
              chatViewModel.canOpenActivityConfirmation(runId: runId) else {
            return rejected(request, "当前没有待确认的操作")
        }

        let allow: Bool
        switch request.action {
        case .approve:
            allow = true
        case .deny:
            allow = false
        default:
            if request.optionId == "approve" {
                allow = true
            } else if request.optionId == "deny" {
                allow = false
            } else {
                return rejected(request, "无法识别审批选项")
            }
        }

        if allow {
            await chatViewModel.approveAnyPendingToolFromWatch()
        } else {
            await chatViewModel.denyAnyPendingToolFromWatch()
        }
        // Awaited finish/resume path publishes the next watch snapshot before we reply.
        return accepted(request, message: allow ? "已允许" : "已拒绝")
    }

    private func handleAskUser(_ request: WatchTaskActionRequest) async -> WatchTaskActionResult {
        guard let decision = currentDecision,
              decision.type == .askUser || decision.type == .voiceReply,
              request.decisionId == decision.id else {
            return rejected(request, "这个确认步骤已失效")
        }
        guard let chatViewModel else {
            return rejected(request, "iPhone 聊天状态不可用")
        }
        guard let runId = currentRunId,
              request.runId == runId,
              let pendingAskUser else {
            return rejected(request, "当前没有等待回答的问题")
        }

        let answer: String
        if request.action == .choose, request.optionId == "skip" {
            answer = ""
        } else if request.action == .choose,
                  let optionId = request.optionId,
                  optionId.hasPrefix("choice-"),
                  let index = Int(optionId.dropFirst("choice-".count)),
                  pendingAskUser.options.indices.contains(index) {
            answer = pendingAskUser.options[index]
        } else if let text = WatchTaskText.clipped(request.text, maxLength: 500),
                  !text.isEmpty {
            answer = text
        } else {
            return rejected(request, "请先选择选项、跳过或语音输入")
        }

        // submitWatchUserAnswer returns true only after finish+resume ownership accepts.
        // resumeAfterApproval already publishes the next snapshot; do not clear locally
        // first or report success before that Bool is true.
        let acceptedSend = chatViewModel.submitWatchUserAnswer(
            runId: runId,
            text: answer
        )
        guard acceptedSend else {
            return rejected(request, "当前无法提交回答")
        }
        return accepted(
            request,
            message: answer.isEmpty ? "已跳过" : "已提交回答"
        )
    }

    private func republish() {
        guard let runId = currentRunId,
              let presentation = currentPresentation else {
            bridge.clear()
            return
        }
        let snapshot = WatchTaskSnapshotBuilder.make(
            runId: runId,
            conversationId: currentConversationId,
            presentation: presentation,
            summary: currentSummary,
            decision: currentDecision
        )
        bridge.publish(snapshot)
    }

    private func accepted(
        _ request: WatchTaskActionRequest,
        message: String?
    ) -> WatchTaskActionResult {
        WatchTaskActionResult(
            requestId: request.requestId,
            runId: request.runId,
            accepted: true,
            message: message,
            snapshot: bridge.latestSnapshot
        )
    }

    private func rejected(
        _ request: WatchTaskActionRequest,
        _ message: String
    ) -> WatchTaskActionResult {
        WatchTaskActionResult(
            requestId: request.requestId,
            runId: request.runId,
            accepted: false,
            message: message,
            snapshot: bridge.latestSnapshot
        )
    }
}
