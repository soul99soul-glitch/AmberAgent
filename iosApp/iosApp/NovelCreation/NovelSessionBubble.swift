import SwiftUI

struct NovelSessionBubble: View {
    let messageID: NovelMessageID
    let role: NovelSessionRole
    let kind: NovelSessionMessageKind
    let granularity: NovelGenerationGranularity?
    let content: String
    let isStreaming: Bool
    let transientPhase: NovelSessionTransientTailPhase?
    let hasEverStreamed: Bool
    let runStatus: NovelRunStatus?
    let candidateStatus: NovelCandidateStatus?
    let polishTransactionStatus: NovelPolishTransactionStatus?
    let committedChange: NovelSessionCommittedChangeSummary?
    let actions: [NovelSessionRowActionAvailability]
    let onAction: (NovelSessionRowAction) -> Void

    var body: some View {
        switch role {
        case .user:
            userBubble
        case .assistant:
            assistantBubble
        case .system:
            systemMessage
        }
    }

    private var userBubble: some View {
        HStack {
            Spacer(minLength: 44)
            ChatUserBubble(text: content)
                .frame(maxWidth: ChatLayout.userMaxWidth, alignment: .trailing)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.vertical, ChatLayout.userMessageRowVerticalPadding)
    }

    @ViewBuilder
    private var assistantBubble: some View {
        if isStreaming && content.isEmpty {
            ChatAssistantPendingResponseView()
        } else {
            ChatAssistantStack {
                ChatAgentName()

                if content.isEmpty {
                    ChatAssistantText {
                        Text(emptyAssistantText)
                            .foregroundStyle(AmberTheme.muted)
                    }
                } else {
                    ChatAssistantMarkdownView(
                        markdown: content,
                        renderCacheNamespace: "novel:session:\(messageID)",
                        isStreaming: isStreaming,
                        hasEverStreamed: hasEverStreamed
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                statusLine
                    .font(.caption)

                if !actions.isEmpty {
                    actionBar
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private var statusLine: some View {
        if case .some(.persistenceBlocked) = transientPhase {
            Label("回复已生成，等待重试保存", systemImage: "externaldrive.badge.exclamationmark")
                .foregroundStyle(AmberTheme.accentAmber)
        } else if transientPhase == .terminalAwaitingRefresh {
            Label("正在保存创作记录", systemImage: "arrow.triangle.2.circlepath")
                .foregroundStyle(AmberTheme.muted)
        } else if representsFailure {
            Label(
                content.isEmpty
                    ? "生成失败 · 正文与剧情状态未改变"
                    : "生成失败 · 已保留草稿，正文与剧情状态未改变",
                systemImage: "exclamationmark.triangle"
            )
            .foregroundStyle(AmberTheme.accentRed)
        } else if transientPhase == .interrupted ||
                    runStatus == .interrupted ||
                    kind == .interruptedDraft {
            Label("生成已中断 · 草稿不会进入正文", systemImage: "pause.circle")
                .foregroundStyle(AmberTheme.accentAmber)
        } else {
            switch kind {
            case .proseCandidate:
                if committedChange != nil {
                    Label("已收录为正式正文", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(AmberTheme.accentGreen)
                } else {
                    proseCandidateStatus
                }
            case .polishCandidate:
                if committedChange != nil {
                    Label("润色版已采用", systemImage: "checkmark.seal.fill")
                        .foregroundStyle(AmberTheme.accentGreen)
                } else {
                    polishCandidateStatus
                }
            case .discussion, .userInput, .interruptedDraft, .error:
                EmptyView()
            }
        }
    }

    private var emptyAssistantText: String {
        if representsFailure {
            return "生成失败，未输出正文"
        }
        if transientPhase == .interrupted || kind == .interruptedDraft {
            return "生成在输出内容前已中断"
        }
        return "正在准备回复"
    }

    private var representsFailure: Bool {
        if runStatus == .failed || kind == .error { return true }
        if case .some(.failed) = transientPhase { return true }
        return false
    }

    private var actionBar: some View {
        VStack(alignment: .leading, spacing: 6) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    actionButtons
                }

                VStack(alignment: .leading, spacing: 8) {
                    actionButtons
                }
            }

            if let blocker = actions.compactMap(\.blocker).first {
                Text(blocker.displayName)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentAmber)
            }
        }
        .padding(.top, 2)
    }

    @ViewBuilder
    private var actionButtons: some View {
        ForEach(actions, id: \.action) { item in
            if item.action.isPrimary {
                actionButton(item)
                    .buttonStyle(.borderedProminent)
            } else {
                actionButton(item)
                    .buttonStyle(.bordered)
            }
        }
    }

    @ViewBuilder
    private var proseCandidateStatus: some View {
        switch candidateStatus {
        case .collected:
            Label("已收录为正式正文", systemImage: "checkmark.circle.fill")
                .foregroundStyle(AmberTheme.accentGreen)
        case .inheritedReadOnly:
            Label("继承的历史候选 · 仅供参考", systemImage: "clock.arrow.circlepath")
                .foregroundStyle(AmberTheme.muted)
        case .superseded:
            Label("候选已过期", systemImage: "clock.badge.exclamationmark")
                .foregroundStyle(AmberTheme.accentAmber)
        case .interrupted:
            Label("候选生成已中断", systemImage: "pause.circle")
                .foregroundStyle(AmberTheme.accentAmber)
        case .available, .adopted, nil:
            Label(proseCandidateLabel, systemImage: "doc.text")
                .foregroundStyle(AmberTheme.muted)
        }
    }

    private var proseCandidateLabel: String {
        switch granularity {
        case .continuation:
            "正文片段 · 收录后进入本章"
        case .wholeChapter:
            "完整章节 · 收录后成为新章"
        case nil:
            "正文候选 · 收录后才进入正式剧情"
        }
    }

    @ViewBuilder
    private var polishCandidateStatus: some View {
        switch polishTransactionStatus {
        case .incompatible:
            Label("检测到剧情漂移 · 不能按润色采用", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(AmberTheme.accentRed)
        case .retryable:
            Label("剧情一致性检查失败 · 可以重试", systemImage: "arrow.clockwise.circle")
                .foregroundStyle(AmberTheme.accentAmber)
        case .blocked:
            Label("剧情一致性检查已阻止采用", systemImage: "hand.raised.fill")
                .foregroundStyle(AmberTheme.accentRed)
        case .pending:
            Label("正在检查剧情一致性", systemImage: "checkmark.shield")
                .foregroundStyle(AmberTheme.muted)
        case .abandoned:
            Label("已放弃这次润色", systemImage: "xmark.circle")
                .foregroundStyle(AmberTheme.muted)
        case .completed, nil:
            polishCandidateStatusByCandidate
        }
    }

    @ViewBuilder
    private var polishCandidateStatusByCandidate: some View {
        switch candidateStatus {
        case .adopted:
            Label("润色版已采用", systemImage: "checkmark.seal.fill")
                .foregroundStyle(AmberTheme.accentGreen)
        case .superseded:
            Label("润色候选已过期", systemImage: "clock.badge.exclamationmark")
                .foregroundStyle(AmberTheme.accentAmber)
        case .interrupted:
            Label("润色生成已中断", systemImage: "pause.circle")
                .foregroundStyle(AmberTheme.accentAmber)
        case .inheritedReadOnly:
            Label("继承的历史润色候选", systemImage: "clock.arrow.circlepath")
                .foregroundStyle(AmberTheme.muted)
        case .available, .collected, nil:
            Label("整章润色候选", systemImage: "wand.and.sparkles")
                .foregroundStyle(AmberTheme.accent)
        }
    }

    private func actionButton(_ item: NovelSessionRowActionAvailability) -> some View {
        Button {
            onAction(item.action)
        } label: {
            Label(
                item.action.displayTitle(granularity: granularity),
                systemImage: item.action.systemImage
            )
                .font(.footnote.weight(.semibold))
                .lineLimit(1)
        }
        .controlSize(.small)
        .disabled(!item.isEnabled)
        .accessibilityHint(item.blocker?.displayName ?? "")
    }

    private var systemMessage: some View {
        Text(content)
            .font(.caption.weight(.medium))
            .foregroundStyle(AmberTheme.muted)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(AmberTheme.surface, in: Capsule())
            .frame(maxWidth: .infinity)
            .accessibilityLabel(content)
    }
}

private extension NovelSessionRowAction {
    func displayTitle(granularity: NovelGenerationGranularity?) -> String {
        switch self {
        case .collectProse:
            switch granularity {
            case .continuation: "收录到本章"
            case .wholeChapter: "作为新章收录"
            case nil: "收录正文"
            }
        case .adoptPolish: "采用润色版"
        case .retryGeneration: "重新生成"
        case .retryTerminalPersistence: "重试保存"
        case .retryPending: "继续收录"
        case .retryPolish: "重试检查"
        case .abandonPolish: "放弃润色"
        case .convertPolishToManualRewrite: "保存为剧情改写"
        case .cloneCollectedProse: "再次收录"
        case .forkFromCheckpoint: "从这里 Fork"
        case .viewSettingProposals: "查看并确认设定建议"
        case .undoCommittedChange(_, let kind): kind == .polish ? "撤销润色" : "撤销收录"
        }
    }

    var systemImage: String {
        switch self {
        case .collectProse: "text.badge.checkmark"
        case .adoptPolish: "checkmark.seal"
        case .retryGeneration, .retryTerminalPersistence, .retryPending, .retryPolish:
            "arrow.clockwise"
        case .abandonPolish: "xmark.circle"
        case .convertPolishToManualRewrite: "square.and.pencil"
        case .cloneCollectedProse: "doc.on.doc"
        case .forkFromCheckpoint: "arrow.triangle.branch"
        case .viewSettingProposals: "books.vertical"
        case .undoCommittedChange: "arrow.uturn.backward"
        }
    }

    var isPrimary: Bool {
        switch self {
        case .collectProse, .adoptPolish, .viewSettingProposals: true
        default: false
        }
    }
}

private extension NovelSessionActionBlocker {
    var displayName: String {
        switch self {
        case .projectReadOnly: "项目当前只读"
        case .branchInactive: "分支已不可编辑"
        case .branchNeedsSync: "剧情状态同步未完成"
        case .generationRunning: "请先停止当前生成"
        case .pendingOperation: "有正文操作正在处理"
        case .transactionInProgress: "操作正在处理"
        case .transactionBlocked: "操作已被阻止"
        case .staleCandidate: "当前剧情已变化，候选已过期"
        case .sourceChapterChanged: "源章节版本已经变化"
        case .failureNotRetryable: "该失败不能直接重试"
        }
    }
}
