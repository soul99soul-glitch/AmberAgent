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
    /// 该候选来自「整章重新生成」:收录后替换原章,而不是新开一章。
    /// 判据是 prose 候选带着来源章版本(只有重写会带)。
    let isRegeneration: Bool
    let polishTransactionStatus: NovelPolishTransactionStatus?
    /// 该候选正在被采用（漂移检查模型调用中）。
    let isAdoptingPolish: Bool
    let committedChange: NovelSessionCommittedChangeSummary?
    let askUser: NovelAskUserPresentation?
    var askUserBlocker: NovelSessionActionBlocker? = nil
    var runtimeActionBlocker: NovelSessionActionBlocker? = nil
    var retryingPolishTransactionID: NovelPendingOperationID? = nil
    var onCancelPolishRetry: () -> Void = {}
    let actions: [NovelSessionRowActionAvailability]
    let onAction: (NovelSessionRowAction) -> Void
    let onAnswerAskUser: (NovelMessageID, String) -> Void

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

    /// Candidate manuscript should never render as Chat code cards when the model
    /// mistakenly wraps it in ```html / ```markdown fences.
    private static func displayMarkdown(
        _ content: String,
        kind: NovelSessionMessageKind,
        isStreaming: Bool
    ) -> String {
        switch kind {
        case .proseCandidate, .polishCandidate, .interruptedDraft:
            return isStreaming
                ? NovelPromptCatalog.normalizedStreamingCandidateProse(content)
                : NovelPromptCatalog.normalizedCandidateProse(content)
        case .discussion, .userInput, .error:
            return content
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
            ChatAssistantPendingResponseView(label: { elapsed in
                NovelSessionPendingPresentation.label(for: transientPhase, elapsed: elapsed)
            })
        } else {
            ChatAssistantStack {
                ChatAgentName()

                if content.isEmpty, askUser == nil {
                    ChatAssistantText {
                        Text(emptyAssistantText)
                            .foregroundStyle(AmberTheme.muted)
                    }
                } else {
                    ChatAssistantMarkdownView(
                        markdown: Self.displayMarkdown(
                            content,
                            kind: kind,
                            isStreaming: isStreaming
                        ),
                        renderCacheNamespace: "novel:session:\(messageID)",
                        isStreaming: isStreaming,
                        hasEverStreamed: hasEverStreamed
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                if let askUser {
                    NovelAskUserCard(
                        presentation: askUser,
                        blocker: askUserBlocker
                    ) { answers in
                        onAnswerAskUser(messageID, answers)
                    }
                }

                statusLine
                    .font(.caption)

                if !effectiveActions.isEmpty {
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
                .foregroundStyle(AmberTheme.foreground2)
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
            let canCollect = actions.contains {
                if case .collectProse = $0.action { return true }
                return false
            }
            Label(
                canCollect ? "生成已中断 · 可收录已生成部分" : "生成已中断",
                systemImage: "pause.circle"
            )
            .foregroundStyle(AmberTheme.foreground2)
        } else {
            switch kind {
            case .proseCandidate:
                if committedChange != nil {
                    Label("已收录为正式正文", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(AmberTheme.accentGreen)
                } else if !isStreaming {
                    // 生成中不在气泡里挂候选状态行:它跟在不断增长的正文下方,
                    // 每次增长都要重新布局并被跟随逻辑推着走,表现为小幅上下抖动。
                    // 生成期间改由输入框上方的常驻状态条承担(NovelSessionView)。
                    proseCandidateStatus
                }
            case .polishCandidate:
                if committedChange != nil {
                    Label("润色版已采用", systemImage: "checkmark.seal.fill")
                        .foregroundStyle(AmberTheme.accentGreen)
                } else if !isStreaming {
                    // 与正文候选同一理由:生成中状态行跟在增长的正文下方会被
                    // 反复重新布局并被跟随逻辑推动,表现为小幅上下抖动。
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

            if let blocker = effectiveActions.compactMap(\.blocker).first {
                Text(blocker.displayName)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.foreground2)
            } else if committedChange?.branchSyncStatus == .synchronized {
                // committedChange is only non-nil for a committed row, so this never
                // renders on discussion/plain-message rows. See branchSyncStatus's doc
                // comment in NovelSessionPresentation.swift for why this is a
                // branch-level fact (shared by every committed row) rather than a
                // per-row history stamp.
                Label("剧情状态已同步", systemImage: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentGreen)
            }
        }
        .padding(.top, 2)
    }

    private var actionButtons: some View {
        NovelSessionActionButtons(
            actions: effectiveActions,
            granularity: granularity,
            retryingPolishTransactionID: retryingPolishTransactionID,
            onCancelPolishRetry: onCancelPolishRetry,
            onAction: onAction
        )
    }

    private var effectiveActions: [NovelSessionRowActionAvailability] {
        actions.map { item in
            guard item.blocker == nil,
                  item.action.requiresMutation,
                  let runtimeActionBlocker else { return item }
            return NovelSessionRowActionAvailability(
                action: item.action,
                blocker: runtimeActionBlocker
            )
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
                .foregroundStyle(AmberTheme.foreground2)
        case .interrupted:
            Label("候选生成已中断", systemImage: "pause.circle")
                .foregroundStyle(AmberTheme.foreground2)
        case .available, .adopted, nil:
            Label(proseCandidateLabel, systemImage: "doc.text")
                .foregroundStyle(AmberTheme.muted)
        }
    }

    private var proseCandidateLabel: String {
        if isRegeneration { return "重写本章 · 收录后替换原文" }
        switch granularity {
        case .continuation:
            return "正文片段 · 收录后进入本章"
        case .wholeChapter:
            return "完整章节 · 收录后成为新章"
        case nil:
            return "正文候选 · 收录后才进入正式剧情"
        }
    }

    @ViewBuilder
    private var polishCandidateStatus: some View {
        if isAdoptingPolish || isRetryingPolish {
            HStack(spacing: 6) {
                ProgressView().controlSize(.small)
                Text("正在检查剧情一致性…")
            }
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
        } else {
            polishTransactionStatusView
        }
    }

    private var isRetryingPolish: Bool {
        guard let retryingPolishTransactionID else { return false }
        return actions.contains {
            $0.action == .retryPolish(retryingPolishTransactionID)
        }
    }

    @ViewBuilder
    private var polishTransactionStatusView: some View {
        switch polishTransactionStatus {
        case .incompatible:
            Label("检测到剧情漂移 · 不能按润色采用", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(AmberTheme.accentRed)
        case .retryable:
            Label("剧情一致性检查失败 · 可以重试", systemImage: "arrow.clockwise.circle")
                .foregroundStyle(AmberTheme.foreground2)
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
                .foregroundStyle(AmberTheme.foreground2)
        case .interrupted:
            Label("润色生成已中断", systemImage: "pause.circle")
                .foregroundStyle(AmberTheme.foreground2)
        case .inheritedReadOnly:
            Label("继承的历史润色候选", systemImage: "clock.arrow.circlepath")
                .foregroundStyle(AmberTheme.muted)
        case .available, .collected, nil:
            Label("整章润色候选", systemImage: "wand.and.sparkles")
                .foregroundStyle(AmberTheme.accent)
        }
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

private struct NovelSessionActionButtons: View {
    let actions: [NovelSessionRowActionAvailability]
    let granularity: NovelGenerationGranularity?
    let retryingPolishTransactionID: NovelPendingOperationID?
    let onCancelPolishRetry: () -> Void
    let onAction: (NovelSessionRowAction) -> Void

    @State private var pendingAbandonTransactionID: NovelPendingOperationID?

    var body: some View {
        ForEach(actions, id: \.action) { item in
            if item.action.isPrimary {
                actionButton(item)
                    .buttonStyle(.borderedProminent)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            } else {
                actionButton(item)
                    .buttonStyle(.bordered)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
        }
    }

    @ViewBuilder
    private func actionButton(_ item: NovelSessionRowActionAvailability) -> some View {
        if case .retryPolish(let transactionID) = item.action,
           retryingPolishTransactionID == transactionID {
            HStack(spacing: 6) {
                ProgressView()
                    .controlSize(.small)
                Button("停止检查") {
                    onCancelPolishRetry()
                }
                .frame(minHeight: 44)
            }
        } else if case .abandonPolish(let transactionID) = item.action {
            baseButton(item) {
                pendingAbandonTransactionID = transactionID
            }
            .confirmationDialog(
                "放弃这次润色？",
                isPresented: Binding(
                    get: { pendingAbandonTransactionID == transactionID },
                    set: { presented in
                        if !presented { pendingAbandonTransactionID = nil }
                    }
                ),
                titleVisibility: .visible
            ) {
                Button("放弃润色", role: .destructive) {
                    pendingAbandonTransactionID = nil
                    onAction(item.action)
                }
                Button("取消", role: .cancel) {
                    pendingAbandonTransactionID = nil
                }
            } message: {
                Text("候选气泡会保留在创作记录中，但不能再作为润色版采用。")
            }
        } else {
            baseButton(item) {
                onAction(item.action)
            }
        }
    }

    private func baseButton(
        _ item: NovelSessionRowActionAvailability,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
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
}

private struct NovelAskUserCard: View {
    let presentation: NovelAskUserPresentation
    let blocker: NovelSessionActionBlocker?
    let onSubmit: (String) -> Void

    @State private var selectedOption: String?
    @State private var customValue = ""
    @State private var validationMessage: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(
                presentation.isAnswered ? "已回答" : "需要你决定",
                systemImage: presentation.isAnswered
                    ? "checkmark.circle.fill"
                    : "questionmark.bubble.fill"
            )
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(presentation.isAnswered ? AmberTheme.accentGreen : AmberTheme.accent)

            if let response = presentation.response {
                Text(response.answer)
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.foreground2)
            } else {
                questionEditor
                    .disabled(blocker != nil)

                if let blocker {
                    Text(blocker.displayName)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                }

                if let validationMessage {
                    Text(validationMessage)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.accentRed)
                }

                Button("确认选择") {
                    NovelTextInputCommitter.perform { submit() }
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .disabled(blocker != nil)
            }
        }
        .padding(16)
        .amberGlass(cornerRadius: 18, interactive: false)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AmberTheme.accent.opacity(0.18), lineWidth: 0.75)
                .allowsHitTesting(false)
        }
    }

    @ViewBuilder
    private var questionEditor: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(presentation.prompt.question)
                .font(.body.weight(.medium))
                .foregroundStyle(AmberTheme.foreground)
                .fixedSize(horizontal: false, vertical: true)

            ForEach(presentation.prompt.options, id: \.self) { option in
                Button {
                    select(option)
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: selectedOption == option
                            ? "checkmark.circle.fill"
                            : "circle")
                            .foregroundStyle(selectedOption == option
                                ? AmberTheme.accent
                                : AmberTheme.muted)
                        Text(option)
                            .foregroundStyle(AmberTheme.foreground)
                            .multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 12)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .background(
                        selectedOption == option
                            ? AmberTheme.accentTint
                            : AmberTheme.surface,
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
                }
                .buttonStyle(.plain)
            }

            TextField(
                presentation.prompt.options.isEmpty ? "输入你的想法" : "或者直接输入自己的选择",
                text: customInput,
                axis: .vertical
            )
            .lineLimit(2...6)
            .textFieldStyle(.plain)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 10))
        }
    }

    private var answer: String {
        let custom = customValue.trimmingCharacters(in: .whitespacesAndNewlines)
        return custom.isEmpty ? selectedOption ?? "" : custom
    }

    private func select(_ option: String) {
        customValue = ""
        selectedOption = option
        validationMessage = nil
    }

    private func submit() {
        let committedAnswer = answer
        guard !committedAnswer.isEmpty else {
            validationMessage = "请选择一个选项或输入你的想法。"
            return
        }
        validationMessage = nil
        onSubmit(committedAnswer)
    }

    private var customInput: Binding<String> {
        Binding(
            get: { customValue },
            set: {
                customValue = $0
                if !$0.isEmpty { selectedOption = nil }
                if !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    validationMessage = nil
                }
            }
        )
    }
}

private extension NovelSessionRowAction {
    var requiresMutation: Bool {
        if case .viewSettingProposals = self { return false }
        return true
    }

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

extension NovelSessionActionBlocker {
    var displayName: String {
        switch self {
        case .projectReadOnly: "项目当前只读"
        case .reloadRequired: "请先重新载入项目"
        case .branchInactive: "分支已不可编辑"
        case .branchNeedsSync: "剧情状态同步未完成"
        case .chapterPlanRequired: "代笔写整章前，请先确认本章计划"
        case .ghostwriteRequirementsMissing: "代笔条件尚未满足"
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
