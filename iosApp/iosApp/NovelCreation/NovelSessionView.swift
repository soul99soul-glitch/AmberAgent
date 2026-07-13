import SwiftUI

enum NovelComposerIntent: String, CaseIterable, Identifiable {
    case discuss
    case continueProse
    case wholeChapter

    var id: String { rawValue }

    var title: String {
        switch self {
        case .discuss: "讨论"
        case .continueProse: "续写"
        case .wholeChapter: "整章"
        }
    }

    init(mode: NovelSessionMode, granularity: NovelGenerationGranularity) {
        if mode == .discussPlan {
            self = .discuss
        } else {
            self = granularity == .wholeChapter ? .wholeChapter : .continueProse
        }
    }

    var requestValues: (mode: NovelSessionMode, granularity: NovelGenerationGranularity) {
        switch self {
        case .discuss: (.discussPlan, .wholeChapter)
        case .continueProse: (.writeProse, .continuation)
        case .wholeChapter: (.writeProse, .wholeChapter)
        }
    }
}

struct NovelSessionView: View {
    let workspace: NovelCreationViewModel
    let viewModel: NovelSessionViewModel
    let sharedSettings: IOSSharedSettingsStore

    @Binding var inputText: String
    @Binding var injectionOverrides: NovelInjectionOverrides
    @Binding var inputBudgetTokens: Int

    let onOpenContext: () -> Void
    let onOpenModel: () -> Void
    let onOpenCollection: (NovelCandidateID) -> Void
    let onOpenManualRewrite: (NovelCandidateID) -> Void
    let onFork: (NovelCheckpointID) -> Void
    let onOpenSettingProposals: (NovelSettingProposalRoute) -> Void

    @State private var scrollPosition = ScrollPosition()
    @State private var followState = NovelSessionBottomFollowState()
    @State private var latestAtBottom = true
    @State private var userDragging = false
    @State private var terminalSettleTask: Task<Void, Never>?
    @State private var composerInputHeight: CGFloat = 40
    @State private var composerBarHeight: CGFloat = 0
    @State private var composerInputController = ComposerInputController()
    @State private var isInputFocused = false
    @State private var pendingAbandonTransactionID: NovelPendingOperationID?
    @State private var pendingUndo: NovelPendingCommittedUndo?

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()
            transcript

            if followState.showsBottomButton, !(listModel?.rows.isEmpty ?? true) {
                VStack {
                    Spacer()
                    ChatScrollToBottomButton {
                        dispatchFollowEvent(.explicitBottomRequested)
                    }
                    .padding(.bottom, max(10, composerBarHeight + 10))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.scale(scale: 0.7).combined(with: .opacity))
                .zIndex(10)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            composer
                .background {
                    GeometryReader { proxy in
                        Color.clear.preference(
                            key: ChatComposerHeightPreferenceKey.self,
                            value: proxy.size.height
                        )
                    }
                }
        }
        .onPreferenceChange(ChatComposerHeightPreferenceKey.self) { height in
            guard abs(composerBarHeight - height) > 0.5 else { return }
            composerBarHeight = height
        }
        .task(id: selectionTaskID) {
            await viewModel.bindToCurrentSelection()
            dispatchFollowEvent(.reset)
            dispatchFollowEvent(.initialRowsPresented(hasRows: !(listModel?.rows.isEmpty ?? true)))
        }
        .task(id: runningRunTaskID) {
            await viewModel.bindToCurrentSelection()
        }
        .onChange(of: listSignal) { oldValue, newValue in
            handleListSignalChange(from: oldValue, to: newValue)
        }
        .onDisappear {
            terminalSettleTask?.cancel()
        }
        .confirmationDialog(
            "放弃这次润色？",
            isPresented: abandonConfirmationBinding,
            titleVisibility: .visible
        ) {
            Button("放弃润色", role: .destructive) {
                guard let transactionID = pendingAbandonTransactionID else { return }
                pendingAbandonTransactionID = nil
                Task { @MainActor in
                    await viewModel.abandonPolishTransaction(transactionID)
                }
            }
            Button("取消", role: .cancel) {
                pendingAbandonTransactionID = nil
            }
        } message: {
            Text("候选气泡会保留在创作记录中，但不能再作为润色版采用。")
        }
        .confirmationDialog(
            pendingUndo?.kind == .polish ? "撤销这次润色？" : "撤销这次收录？",
            isPresented: Binding(
                get: { pendingUndo != nil },
                set: { if !$0 { pendingUndo = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(pendingUndo?.kind == .polish ? "撤销润色" : "撤销收录", role: .destructive) {
                let request = pendingUndo
                pendingUndo = nil
                Task { @MainActor in
                    guard let request,
                          workspace.branchSnapshot?.branch.headCheckpointID == request.checkpointID else {
                        workspace.presentError(NovelError.invalidInput("当前分支已经变化，请重新选择要撤销的记录。"))
                        return
                    }
                    await workspace.undoBranchHead()
                    await viewModel.bindToCurrentSelection()
                }
            }
            Button("取消", role: .cancel) { pendingUndo = nil }
        } message: {
            Text("正文、剧情状态和相关资料会一起回到上一个存档点，不会删除历史记录。")
        }
    }

    private var transcript: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                if let rows = listModel?.rows, rows.isEmpty {
                    Text("新的创作对话")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 56)
                } else {
                    ForEach(listModel?.rows ?? []) { row in
                        NovelSessionRowView(row: row, onAction: handleRowAction)
                            .equatable()
                            .disabled(
                                viewModel.isBusy ||
                                    viewModel.hasRefreshError ||
                                    workspace.requiresReload
                            )
                    }
                }

                Color.clear
                    .frame(height: viewModel.isRunning
                        ? ChatLayout.followBottomGap
                        : ChatLayout.bottomRestGap)
                    .id(Self.bottomAnchorID)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, ChatLayout.contentHorizontalInset)
            .padding(.top, 12)
            .padding(.bottom, 8)
            .scrollTargetLayout()
        }
        .scrollPosition($scrollPosition)
        .defaultScrollAnchor(.bottom, for: .initialOffset)
        .defaultScrollAnchor(.top, for: .alignment)
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .contentShape(Rectangle())
        .simultaneousGesture(
            TapGesture().onEnded {
                isInputFocused = false
            }
        )
        .onScrollPhaseChange { _, phase in
            switch phase {
            case .tracking, .interacting:
                guard !userDragging else { return }
                userDragging = true
                isInputFocused = false
                dispatchFollowEvent(.userDragBegan(isAtBottom: latestAtBottom))
            case .idle:
                guard userDragging else { return }
                userDragging = false
                dispatchFollowEvent(.userDragEnded(isAtBottom: latestAtBottom))
            case .animating, .decelerating:
                break
            @unknown default:
                break
            }
        }
        .onScrollGeometryChange(for: NovelSessionScrollGeometrySignal.self) { geometry in
            NovelSessionScrollGeometrySignal(
                contentHeight: geometry.contentSize.height,
                isAtBottom: geometry.contentSize.height - geometry.visibleRect.maxY <=
                    ChatLayout.bottomStickThreshold
            )
        } action: { oldValue, newValue in
            latestAtBottom = newValue.isAtBottom
            if oldValue.isAtBottom != newValue.isAtBottom {
                dispatchFollowEvent(.viewportChanged(isAtBottom: newValue.isAtBottom))
            }
            if abs(oldValue.contentHeight - newValue.contentHeight) > 0.5,
               !userDragging,
               !isLiveTailPhase(listSignal.activeTailPhase) {
                dispatchFollowEvent(.terminalLayoutChanged)
            }
        }
    }

    private var composer: some View {
        VStack(spacing: 8) {
            if viewModel.needsSync || !viewModel.branchPendingOperations.isEmpty {
                synchronizationBanner
            }

            if let recovery = quickStartRecovery {
                quickStartRecoveryBanner(recovery)
            }

            if let error = viewModel.errorMessage {
                errorBanner(error)
            }

            sessionControls

            HStack(alignment: .bottom, spacing: 8) {
                HStack(alignment: .center, spacing: 6) {
                    ZStack(alignment: .leading) {
                        ComposerInputTextView(
                            text: $inputText,
                            height: $composerInputHeight,
                            isFocused: inputFocusBinding,
                            isEnabled: viewModel.access == .readWrite && !viewModel.isRunning,
                            sendOnEnter: sendOnEnter,
                            controller: composerInputController,
                            onSubmit: send
                        )
                        .frame(height: composerInputHeight)

                        if inputText.isEmpty {
                            Text(inputPlaceholder)
                                .font(.body)
                                .foregroundStyle(AmberTheme.muted2)
                                .allowsHitTesting(false)
                        }
                    }
                    .frame(minHeight: 40)
                }
                .padding(.leading, 18)
                .padding(.trailing, 18)
                .padding(.vertical, 7)
                .composerDockGlass(cornerRadius: 27)

                ComposerDockSendButton(
                    isLoading: viewModel.isRunning && viewModel.canStop,
                    sendEnabled: sendEnabled,
                    diameter: 54,
                    onSend: send,
                    onStop: stop
                )
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background {
            LinearGradient(
                colors: [AmberTheme.background.opacity(0.78), AmberTheme.background],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
        }
    }

    private var synchronizationBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.arrow.triangle.2.circlepath")
                .foregroundStyle(AmberTheme.accentAmber)

            Text(syncBannerText)
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(maxWidth: .infinity, alignment: .leading)

            if let pending = viewModel.branchPendingOperations.first {
                Button(pending.status == .retryable ? "重试" : "继续") {
                    Task { @MainActor in
                        await viewModel.retryPending(pending.id)
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(
                    viewModel.isBusy || viewModel.isRunning ||
                        workspace.requiresReload || viewModel.access != .readWrite
                )
            } else if viewModel.needsSync {
                Button("同步") {
                    Task { @MainActor in
                        await viewModel.syncManualEdits()
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(
                    viewModel.isBusy || viewModel.isRunning ||
                        workspace.requiresReload || viewModel.access != .readWrite
                )
            }
        }
        .padding(.horizontal, 4)
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle")
                .foregroundStyle(AmberTheme.accentAmber)
            Text(message)
                .font(.footnote)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)

            if viewModel.hasRefreshError {
                Button("重新载入") {
                    Task { @MainActor in _ = await viewModel.refresh() }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            } else if viewModel.canRetryPendingTerminal {
                Button("重试保存") {
                    Task { @MainActor in await viewModel.retryPendingTerminal() }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            } else if viewModel.canRetryLastTerminal {
                Button("重试") {
                    Task { @MainActor in _ = await viewModel.retryLastTerminal() }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            } else {
                Button {
                    viewModel.clearError()
                } label: {
                    Image(systemName: "xmark")
                }
                .buttonStyle(.plain)
                .accessibilityLabel("关闭错误提示")
            }
        }
        .padding(.horizontal, 4)
    }

    private var quickStartRecovery: NovelSessionQuickStartRecovery? {
        guard workspace.projectSnapshot?.project.creationMode == .quickStart else { return nil }
        switch workspace.quickStartStatus {
        case .failed(let message):
            let hasDurableRetryOwner = workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.kind == .quickStart &&
                    ($0.status == .failed || $0.status == .interrupted)
            }) == true
            return hasDurableRetryOwner ? nil : .retry(message: message)
        case .refreshFailed(let message):
            return .reload(message: message)
        case .persistenceBlocked(let runID, let message):
            let hasDurableRow = listModel?.rows.contains(where: { $0.runID == runID }) == true
            return hasDurableRow ? nil : .retryPersistence(runID: runID, message: message)
        case .idle, .starting, .generating:
            return nil
        }
    }

    private func quickStartRecoveryBanner(
        _ recovery: NovelSessionQuickStartRecovery
    ) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "sparkles")
                .foregroundStyle(AmberTheme.accentAmber)
            Text(recovery.message)
                .font(.footnote)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(recovery.actionTitle) {
                Task { @MainActor in
                    switch recovery {
                    case .retry:
                        await workspace.startQuickStartSuggestions()
                    case .reload:
                        await workspace.reloadQuickStartProject()
                    case .retryPersistence(let runID, _):
                        await workspace.retryQuickStartPersistence(runID: runID)
                    }
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .disabled(
                workspace.isPerforming ||
                    workspace.requiresReload ||
                    viewModel.isRunning ||
                    viewModel.isBusy ||
                    viewModel.access != .readWrite
            )
        }
        .padding(.horizontal, 4)
    }

    private var sessionControls: some View {
        HStack(spacing: 8) {
            Picker("创作方式", selection: composerIntentBinding) {
                ForEach(NovelComposerIntent.allCases) { intent in
                    Text(intent.title).tag(intent)
                }
            }
            .pickerStyle(.segmented)
            .disabled(controlsDisabled)

            Menu {
                Button(action: onOpenContext) {
                    Label("本次上下文…", systemImage: "shippingbox")
                }

                Menu {
                    Button {
                        Task { await workspace.setModelPolicy(.global) }
                    } label: {
                        Label("跟随全局模型", systemImage: "arrow.triangle.2.circlepath")
                    }
                    Button(action: onOpenModel) {
                        Label("选择固定模型…", systemImage: "cpu")
                    }
                } label: {
                    Label("项目模型…", systemImage: "cpu")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(injectionOverrides == .none ? AmberTheme.foreground2 : Color.white)
                    .frame(width: 34, height: 34)
                    .contentShape(Circle())
                    .modifier(ComposerDockCircleGlass(
                        tint: injectionOverrides == .none ? nil : AmberTheme.accent
                    ))
            }
            .disabled(controlsDisabled)
            .accessibilityLabel("更多创作选项")

        }
    }

    private var controlsDisabled: Bool {
        viewModel.access != .readWrite ||
            workspace.requiresReload ||
            viewModel.isRunning ||
            viewModel.isBusy
    }

    private var listModel: NovelSessionListModel? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              viewModel.binding?.projectID == project.project.id,
              viewModel.binding?.branchID == branch.branch.id else { return nil }
        return NovelSessionPresentation.project(NovelSessionProjectionInput(
            project: project,
            branch: branch,
            transientTail: viewModel.transientTail
        ))
    }

    private var listSignal: NovelSessionListSignal {
        let model = listModel
        let tail = model?.activeTailID.flatMap { tailID in
            model?.rows.first(where: { $0.id == tailID })
        }
        return NovelSessionListSignal(
            sessionID: model?.sessionID,
            rowCount: model?.rows.count ?? 0,
            activeTailID: model?.activeTailID,
            activeTailDigest: tail?.digest,
            activeTailPhase: tail?.transientPhase,
            lastRowDigest: model?.rows.last?.digest
        )
    }

    private var selectionTaskID: String {
        let branchID = workspace.selectedBranchID
        return "\(workspace.selectedProjectID?.description ?? "none"):" +
            "\(branchID?.description ?? "none")"
    }

    private var runningRunTaskID: String {
        let branchID = workspace.selectedBranchID
        let phase: String
        if let running = workspace.projectSnapshot?.activeRuns.first(where: {
            $0.branchID == branchID && $0.status == .running
        }) {
            phase = "durable:\(running.id.description)"
        } else if let starting = workspace.quickStartStartingRun,
                  workspace.quickStartStartingProjectID == workspace.selectedProjectID,
                  starting.branchID == branchID {
            phase = "starting:\(starting.id.description)"
        } else {
            phase = "none"
        }
        return "\(workspace.selectedProjectID?.description ?? "none"):" +
            "\(branchID?.description ?? "none"):" +
            phase
    }

    private var composerIntentBinding: Binding<NovelComposerIntent> {
        Binding(
            get: {
                NovelComposerIntent(mode: viewModel.mode, granularity: viewModel.granularity)
            },
            set: { intent in
                let values = intent.requestValues
                viewModel.mode = values.mode
                if intent != .discuss {
                    viewModel.granularity = values.granularity
                }
            }
        )
    }

    private var inputFocusBinding: Binding<Bool> {
        Binding(get: { isInputFocused }, set: { isInputFocused = $0 })
    }

    private var sendOnEnter: Bool {
        _ = sharedSettings.revision
        return sharedSettings.displaySetting.sendOnEnter
    }

    private var sendEnabled: Bool {
        viewModel.canSend && !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var inputPlaceholder: String {
        if viewModel.mode == .discussPlan { return "和 Agent 讨论剧情与设定" }
        return viewModel.granularity == .wholeChapter ? "描述这一章要发生什么" : "描述接下来要写什么"
    }

    private var syncBannerText: String {
        if !viewModel.branchPendingOperations.isEmpty { return "正文状态尚未完整提交" }
        return "手动改写后需要同步剧情状态"
    }

    private var abandonConfirmationBinding: Binding<Bool> {
        Binding(
            get: { pendingAbandonTransactionID != nil },
            set: { presented in
                if !presented { pendingAbandonTransactionID = nil }
            }
        )
    }

    private func send() {
        guard let committed = composerInputController.committedText() else { return }
        guard !committed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let overrides = injectionOverrides
        let budget = inputBudgetTokens
        Task { @MainActor in
            let started = await viewModel.send(
                text: committed,
                injectionOverrides: overrides,
                inputBudgetTokens: budget
            )
            guard started else { return }
            inputText = ""
            injectionOverrides = .none
            inputBudgetTokens = 16_000
            isInputFocused = false
        }
    }

    private func stop() {
        Task { @MainActor in
            await viewModel.stop(reason: .user)
        }
    }

    private func handleRowAction(_ action: NovelSessionRowAction) {
        switch action {
        case .collectProse(let candidateID):
            onOpenCollection(candidateID)
        case .adoptPolish(let candidateID):
            Task { @MainActor in await viewModel.adoptPolishCandidate(candidateID) }
        case .retryGeneration(let runID):
            Task { @MainActor in _ = await viewModel.retryGeneration(runID: runID) }
        case .retryTerminalPersistence(let runID):
            guard viewModel.transientTail?.runID == runID else { return }
            Task { @MainActor in await viewModel.retryPendingTerminal() }
        case .retryPending(let pendingID):
            Task { @MainActor in await viewModel.retryPending(pendingID) }
        case .retryPolish(let transactionID):
            Task { @MainActor in await viewModel.retryPolishTransaction(transactionID) }
        case .abandonPolish(let transactionID):
            pendingAbandonTransactionID = transactionID
        case .convertPolishToManualRewrite(let candidateID, _):
            onOpenManualRewrite(candidateID)
        case .cloneCollectedProse(let candidateID):
            Task { @MainActor in
                guard let clonedID = await viewModel.cloneCollectedProse(candidateID) else { return }
                onOpenCollection(clonedID)
            }
        case .forkFromCheckpoint(let checkpointID):
            onFork(checkpointID)
        case .viewSettingProposals(let kind):
            onOpenSettingProposals(kind)
        case .undoCommittedChange(let checkpointID, let kind):
            pendingUndo = NovelPendingCommittedUndo(checkpointID: checkpointID, kind: kind)
        }
    }

    private func handleListSignalChange(
        from oldValue: NovelSessionListSignal,
        to newValue: NovelSessionListSignal
    ) {
        guard oldValue.sessionID == newValue.sessionID else {
            dispatchFollowEvent(.reset)
            dispatchFollowEvent(.initialRowsPresented(hasRows: newValue.rowCount > 0))
            return
        }
        if oldValue.activeTailID == nil, newValue.activeTailID != nil {
            dispatchFollowEvent(.streamStarted)
        } else if oldValue.activeTailID == newValue.activeTailID,
                  newValue.activeTailID != nil,
                  oldValue.activeTailDigest != newValue.activeTailDigest {
            if isLiveTailPhase(oldValue.activeTailPhase),
               !isLiveTailPhase(newValue.activeTailPhase) {
                dispatchFollowEvent(.terminalReached)
            } else if isLiveTailPhase(newValue.activeTailPhase) {
                dispatchFollowEvent(.streamDelta)
            } else {
                dispatchFollowEvent(.terminalLayoutChanged)
            }
        } else if oldValue.activeTailID != nil, newValue.activeTailID == nil {
            dispatchFollowEvent(.terminalReached)
        } else if oldValue.rowCount != newValue.rowCount || oldValue.lastRowDigest != newValue.lastRowDigest {
            dispatchFollowEvent(.terminalLayoutChanged)
        }
    }

    private func isLiveTailPhase(_ phase: NovelSessionTransientTailPhase?) -> Bool {
        phase == .waitingForFirstToken || phase == .streaming
    }

    private func dispatchFollowEvent(_ event: NovelSessionBottomFollowEvent) {
        let transition = NovelSessionBottomFollowPolicy.reduce(state: followState, event: event)
        followState = transition.state
        for command in transition.commands {
            executeFollowCommand(command)
        }
    }

    private func executeFollowCommand(_ command: NovelSessionBottomFollowCommand) {
        switch command {
        case .anchorBottom:
            var transaction = Transaction()
            transaction.animation = nil
            withTransaction(transaction) {
                scrollPosition.scrollTo(id: Self.bottomAnchorID, anchor: .bottom)
            }
        case .followBottom(let animated):
            if animated {
                withAnimation(.easeOut(duration: 0.2)) {
                    scrollPosition.scrollTo(id: Self.bottomAnchorID, anchor: .bottom)
                }
            } else {
                var transaction = Transaction()
                transaction.animation = nil
                withTransaction(transaction) {
                    scrollPosition.scrollTo(id: Self.bottomAnchorID, anchor: .bottom)
                }
            }
        case .setBottomButton:
            break
        case .scheduleTerminalQuietSettle(let token, let delay):
            terminalSettleTask?.cancel()
            terminalSettleTask = Task { @MainActor in
                try? await Task.sleep(for: .seconds(delay))
                guard !Task.isCancelled else { return }
                dispatchFollowEvent(.terminalQuietElapsed(token: token))
            }
        }
    }

    private static let bottomAnchorID = "novel-session-bottom-anchor"
}

private struct NovelPendingCommittedUndo {
    let checkpointID: NovelCheckpointID
    let kind: NovelCandidateKind
}

private struct NovelSessionListSignal: Equatable {
    let sessionID: NovelSessionID?
    let rowCount: Int
    let activeTailID: NovelMessageID?
    let activeTailDigest: NovelSessionRowDigest?
    let activeTailPhase: NovelSessionTransientTailPhase?
    let lastRowDigest: NovelSessionRowDigest?
}

private struct NovelSessionScrollGeometrySignal: Equatable {
    let contentHeight: CGFloat
    let isAtBottom: Bool
}

private enum NovelSessionQuickStartRecovery {
    case retry(message: String)
    case reload(message: String)
    case retryPersistence(runID: NovelRunID, message: String)

    var message: String {
        switch self {
        case .retry(let message), .reload(let message), .retryPersistence(_, let message):
            return message
        }
    }

    var actionTitle: String {
        switch self {
        case .retry: "重新生成"
        case .reload: "重新载入"
        case .retryPersistence: "重试保存"
        }
    }
}

private struct NovelSessionRowView: View, Equatable {
    let row: NovelSessionRowModel
    let onAction: (NovelSessionRowAction) -> Void

    nonisolated static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.row.id == rhs.row.id && lhs.row.digest == rhs.row.digest
    }

    var body: some View {
        NovelSessionBubble(
            messageID: row.id,
            role: row.role,
            kind: row.kind,
            content: row.content,
            isStreaming: row.isStreaming,
            transientPhase: row.transientPhase,
            hasEverStreamed: row.runID != nil,
            runStatus: row.runStatus,
            candidateStatus: row.candidate?.status,
            polishTransactionStatus: row.candidate?.polishTransactionStatus,
            committedChange: row.committedChange,
            actions: row.actions,
            onAction: onAction
        )
    }
}
