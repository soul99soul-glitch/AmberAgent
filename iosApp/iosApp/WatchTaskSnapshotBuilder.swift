import Foundation

enum WatchTaskSnapshotBuilder {
    static func idle(now: Date = Date()) -> WatchTaskSnapshot {
        var snapshot = WatchTaskSnapshot.idle
        snapshot.updatedAt = now
        return snapshot
    }

    static func make(
        runId: String,
        conversationId: String?,
        presentation: AgentActivityPresentation,
        summary: String? = nil,
        decision: WatchDecision? = nil,
        now: Date = Date()
    ) -> WatchTaskSnapshot {
        let phase = presentation.phase.rawValue
        let stage = presentation.stage.rawValue
        let kind = presentation.kind.rawValue
        let headline = presentation.kind.title
        let detail = presentation.stage.title
        let metricText = presentation.metric.shortText
        let clippedSummary = WatchTaskText.clipped(summary, maxLength: 280)
        var visibleDecision = decision
        if conversationId == nil {
            visibleDecision?.options.removeAll { $0.style == .openOnPhone }
        }

        var actions: [WatchAction] = []
        if conversationId != nil {
            actions.append(.openOnPhone)
        }
        if let decision = visibleDecision {
            switch decision.type {
            case .approval:
                actions.append(contentsOf: [.approve, .deny])
            case .askUser:
                actions.append(contentsOf: [.choose, .dictate])
            case .voiceReply:
                actions.append(.dictate)
            }
        }
        if presentation.phase == .running
            || presentation.phase == .waitingForUser
            || presentation.phase == .reconnecting {
            actions.append(.cancel)
        }

        var seen = Set<WatchAction>()
        actions = actions.filter { seen.insert($0).inserted }

        return WatchTaskSnapshot(
            runId: runId,
            conversationId: conversationId,
            kind: kind,
            phase: phase,
            stage: stage,
            headline: headline,
            detail: WatchTaskText.singleLine(detail, maxLength: 80),
            summary: clippedSummary,
            metricText: metricText,
            decision: visibleDecision,
            actions: actions,
            updatedAt: now,
            isStale: false
        )
    }

    static func decision(from prompt: ChatToolApprovalPrompt) -> WatchDecision {
        switch prompt {
        case .memory(let request):
            return approvalDecision(
                id: request.id,
                title: request.title,
                body: body(
                    primary: request.contentPreview,
                    fallback: request.reason,
                    chips: [request.action, request.scope, request.kind].compactMap { $0 }
                ),
                risk: .medium
            )
        case .search(let request):
            return approvalDecision(
                id: request.id,
                title: request.title,
                body: body(
                    primary: request.target,
                    fallback: request.reason,
                    chips: [request.providerName]
                ),
                risk: .medium
            )
        case .webMount(let request):
            return approvalDecision(
                id: request.id,
                title: request.title,
                body: body(
                    primary: "\(request.siteName) · \(request.host)",
                    fallback: request.reason,
                    chips: [request.toolName]
                ),
                risk: .high
            )
        case .workspace(let request):
            return approvalDecision(
                id: request.id,
                title: request.title,
                body: body(
                    primary: request.target,
                    fallback: request.reason,
                    chips: [request.action, request.toolName]
                ),
                risk: request.isWrite ? .high : .medium
            )
        case .ish(let request):
            return approvalDecision(
                id: request.id,
                title: request.title,
                body: body(
                    primary: request.commandPreview,
                    fallback: request.reason,
                    chips: [request.mode.rawValue, request.filename]
                ),
                risk: .high
            )
        case .mcp(let request):
            let decisionBody: String
            if let preview = request.skillImportPreview {
                decisionBody = skillImportBody(preview)
            } else {
                decisionBody = body(
                    primary: "\(request.serverName).\(request.toolName)",
                    fallback: request.reason,
                    chips: [WatchTaskText.singleLine(request.argumentsPreview, maxLength: 80)].compactMap { $0 }
                )
            }
            return approvalDecision(
                id: request.id,
                title: request.title,
                body: decisionBody,
                risk: .high
            )
        case .council(let request):
            return approvalDecision(
                id: request.id,
                title: request.title,
                body: body(
                    primary: request.objectivePreview,
                    fallback: request.reason,
                    chips: request.maxSeats.map { ["席位 \($0)"] } ?? []
                ),
                risk: .medium
            )
        case .askUser(let request):
            return askUserDecision(
                from: WatchAskUserRequest(
                    id: request.id,
                    question: request.question,
                    options: request.options
                )
            )
        }
    }

    static func askUserDecision(from request: WatchAskUserRequest) -> WatchDecision {
        // Match schema maxItems=6; keep free-text/dictate for anything beyond.
        let options = request.options.prefix(6).enumerated().map { index, title in
            WatchDecisionOption(
                id: "choice-\(index)",
                title: WatchTaskText.singleLine(title, maxLength: 28) ?? "选项 \(index + 1)",
                style: .choice
            )
        }
        var allOptions = Array(options)
        allOptions.append(
            WatchDecisionOption(
                id: "skip",
                title: "跳过",
                style: .deny
            )
        )
        allOptions.append(
            WatchDecisionOption(
                id: "dictate",
                title: "语音回答",
                style: .dictate
            )
        )
        allOptions.append(
            WatchDecisionOption(
                id: "open-phone",
                title: "在 iPhone 回答",
                style: .openOnPhone
            )
        )

        return WatchDecision(
            id: request.id,
            type: options.isEmpty ? .voiceReply : .askUser,
            title: "需要你的回答",
            body: WatchTaskText.clipped(request.question, maxLength: 160) ?? "模型正在等待你的回答。",
            options: allOptions,
            riskLevel: .low,
            allowsVoice: true
        )
    }

    private static func approvalDecision(
        id: String,
        title: String,
        body: String,
        risk: WatchRiskLevel
    ) -> WatchDecision {
        WatchDecision(
            id: id,
            type: .approval,
            title: WatchTaskText.singleLine(title, maxLength: 40) ?? "等待确认",
            body: body,
            options: [
                WatchDecisionOption(id: "deny", title: "拒绝", style: .deny),
                WatchDecisionOption(id: "approve", title: "允许", style: .approve),
                WatchDecisionOption(id: "open-phone", title: "在 iPhone 查看", style: .openOnPhone)
            ],
            riskLevel: risk,
            allowsVoice: false
        )
    }

    private static func body(
        primary: String?,
        fallback: String,
        chips: [String]
    ) -> String {
        let primaryLine = WatchTaskText.singleLine(primary, maxLength: 120)
        let fallbackLine = WatchTaskText.singleLine(fallback, maxLength: 120)
        let chipLine = WatchTaskText.singleLine(
            chips
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .joined(separator: " · "),
            maxLength: 80
        )
        return [primaryLine, chipLine, fallbackLine]
            .compactMap { $0 }
            .first ?? "需要你在手表上确认这一步。"
    }

    private static func skillImportBody(_ preview: McpSkillImportPreview) -> String {
        let action = preview.mutationKind == .new ? "新建" : "更新"
        let skillName = WatchTaskText.singleLine(preview.skillName, maxLength: 28) ?? "未命名 Skill"
        let baseHash = preview.baseHash.map { String($0.prefix(8)) } ?? "无"
        let candidateHash = String(preview.candidateHash.prefix(8))
        return "\(action) \(skillName) · \(preview.changedFiles.count) 处变更 · \(baseHash)→\(candidateHash)"
    }
}

struct WatchAskUserRequest: Equatable, Sendable {
    let id: String
    let question: String
    let options: [String]
}
