import SwiftUI
import UIKit

enum NovelComposerIntent: String, CaseIterable, Identifiable {
    case discuss
    case continueProse
    case wholeChapter

    var id: String { rawValue }

    static let discussionOptions: [NovelComposerIntent] = [.discuss]
    static let proseOptions: [NovelComposerIntent] = [.continueProse, .wholeChapter]

    var title: String {
        switch self {
        case .discuss: "讨论"
        case .continueProse: "写一段"
        case .wholeChapter: "写整章"
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

enum NovelSessionBottomProximityPolicy {
    static func isNearBottom(distanceToBottom: CGFloat) -> Bool {
        distanceToBottom <= ChatLayout.nearBottomResumeThreshold
    }
}

enum NovelSessionScrollGeometryPolicy {
    static func events(
        previousContentHeight: CGFloat,
        currentContentHeight: CGFloat,
        userDragging: Bool,
        isLiveTail: Bool,
        isSettlingTerminal: Bool,
        previousIsAtBottom: Bool,
        currentIsAtBottom: Bool
    ) -> [NovelSessionBottomFollowEvent] {
        if currentContentHeight - previousContentHeight > 0.5 {
            guard !userDragging else { return [] }
            if isLiveTail {
                return [.measuredStreamGrowth(isAtBottom: currentIsAtBottom)]
            }
            if isSettlingTerminal {
                return [.measuredTerminalGrowth(isAtBottom: currentIsAtBottom)]
            }
            guard previousIsAtBottom != currentIsAtBottom else { return [] }
            return [.staticContentGrowth(isAtBottom: currentIsAtBottom)]
        }
        guard previousIsAtBottom != currentIsAtBottom else { return [] }
        return [.viewportChanged(isAtBottom: currentIsAtBottom)]
    }
}

struct NovelSessionView: View {
    let workspace: NovelCreationViewModel
    let viewModel: NovelSessionViewModel
    let sharedSettings: IOSSharedSettingsStore

    @Binding var inputText: String
    @Binding var injectionOverrides: NovelInjectionOverrides
    @Binding var inputBudgetTokens: Int
    let composerInputController: ComposerInputController

    let onOpenModel: () -> Void
    let onOpenCollection: (NovelCandidateID) -> Void
    let onOpenManualRewrite: (NovelCandidateID) -> Void
    let onFork: (NovelCheckpointID) -> Void
    let onOpenSettingProposals: (NovelSettingProposalRoute) -> Void
    let onAcceptSettingProposal: (NovelSettingProposalRecord) -> Void
    let onArchiveDiscussion: () -> Void

    @Environment(\.accessibilityReduceMotion) private var accessibilityReduceMotion
    @AppStorage(IOSDisplayPreferenceKeys.followGeneration) private var followGeneration = true
    @State private var scrollPosition = ScrollPosition()
    @State private var followState = NovelSessionBottomFollowState()
    @State private var latestAtBottom = true
    @State private var latestNearBottom = true
    @State private var userDragging = false
    @State private var terminalSettleTask: Task<Void, Never>?
    @State private var explicitBottomAnimationTask: Task<Void, Never>?
    @State private var explicitBottomFollowPending = false
    @State private var scrollDriver = NativeTimelineScrollDriver()
    @State private var nativeScrollFallbackReason: NativeTimelineScrollFallbackReason?
    @State private var isNativeScrollSurfaceVisible = false
    @State private var composerInputHeight: CGFloat = 40
    @State private var composerBarHeight: CGFloat = 0
    @State private var isInputFocused = false
    @State private var isContextPanelPresented = false
    @State private var pendingRecoveryAbandonTransactionIDs: [NovelPendingOperationID] = []
    @State private var recoveryAbandonTask: Task<Void, Never>?
    @State private var pendingUndo: NovelPendingCommittedUndo?
    @State private var historyWindowLimit = NovelSessionHistoryWindowPolicy.initialLimit
    @State private var expandedArchiveIDs: Set<NovelMessageID> = []
    @State private var streamingTailVisibility = ChatSwiftUIStreamingTailVisibilityState()
    @State private var suspendedStreamingTailRow: NovelSessionRowModel?

    var body: some View {
        let listModel = projectedListModel()
        let listSignal = makeListSignal(from: listModel)

        ZStack {
            AmberThemePageBackground(surface: .app)
            transcript(listModel: listModel, listSignal: listSignal)

            if followState.showsBottomButton, !(listModel?.rows.isEmpty ?? true) {
                VStack {
                    Spacer()
                    ChatScrollToBottomButton {
                        releaseSuspendedStreamingTail()
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
            VStack(spacing: 0) {
                if NovelSessionComposerPolicy.showsGenerationStatus(
                    isRunning: viewModel.isRunning,
                    activeRunKind: viewModel.activeRunKind
                ) {
                    generationStatusStrip
                }
                composer(listModel: listModel)
            }
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
        .onAppear {
            isNativeScrollSurfaceVisible = true
            // 重新进入页面时清除上一次的 fallback 粘连，给原生滚动 driver 一次重试机会。
            nativeScrollFallbackReason = nil
        }
        .task(id: bindingTaskID) {
            await viewModel.bindToCurrentSelection()
        }
        .task(id: listSignal.sessionID) {
            await Task.yield()
            guard !Task.isCancelled else { return }
            presentInitialRowsIfNeeded(listSignal)
        }
        .onChange(of: listSignal) { oldValue, newValue in
            handleListSignalChange(from: oldValue, to: newValue)
        }
        .onDisappear {
            if let committed = composerInputController.committedText(),
               committed != inputText {
                inputText = committed
            }
            isNativeScrollSurfaceVisible = false
            terminalSettleTask?.cancel()
            cancelExplicitBottomAnimation()
            scrollDriver.invalidate()
        }
        .onChange(of: followGeneration) { _, enabled in
            scrollDriver.setAutomaticFollowEnabled(enabled)
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

    private func transcript(
        listModel: NovelSessionListModel?,
        listSignal: NovelSessionListSignal
    ) -> some View {
        let rows = listModel?.rows ?? []
        let historicalRows = listModel?.historicalRows ?? []
        let activeRunRows = listModel?.activeRunRows ?? []
        let historyStartIndex = NovelSessionHistoryWindowPolicy.startIndex(
            totalCount: historicalRows.count,
            limit: historyWindowLimit
        )
        let visibleHistoricalRows = historicalRows.dropFirst(historyStartIndex)
        let hiddenHistoryCount = historyStartIndex

        return ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if rows.isEmpty {
                    Text("新的创作对话")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 56)
                } else {
                    if hiddenHistoryCount > 0 {
                        Button {
                            revealEarlierHistory(
                                totalCount: historicalRows.count,
                                preserving: visibleHistoricalRows.first?.id
                            )
                        } label: {
                            Label(
                                "更早的创作记录（\(hiddenHistoryCount)）",
                                systemImage: "clock.arrow.circlepath"
                            )
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(AmberTheme.muted)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                        }
                        .buttonStyle(.plain)
                    }

                    if !visibleHistoricalRows.isEmpty {
                        LazyVStack(alignment: .leading, spacing: 14) {
                            ForEach(visibleHistoricalRows) { row in
                                transcriptRow(row, activeTailID: listModel?.activeTailID)
                            }
                        }
                    }

                    ForEach(activeRunRows) { row in
                        transcriptRow(row, activeTailID: listModel?.activeTailID)
                    }

                    ForEach(viewModel.pendingCharacterIdentityMentions) { mention in
                        let activeProposal = viewModel.activeCharacterProposal(for: mention.name)
                        NovelCharacterIdentityQuestionCard(
                            mention: mention,
                            choices: viewModel.characterIdentityChoices,
                            activeProposal: activeProposal,
                            relatedProposalCount: viewModel.relatedCharacterProposalCount(
                                for: mention.name
                            ),
                            isDisabled: viewModel.isBusy || viewModel.isRunning,
                            onSelect: { materialID in
                                Task { @MainActor in
                                    _ = await viewModel.associateCharacterAlias(
                                        mention.name,
                                        with: materialID
                                    )
                                }
                            },
                            onIgnore: {
                                Task { @MainActor in
                                    _ = await viewModel.ignoreCharacterIdentityMention(mention.name)
                                }
                            },
                            onClarify: { clarification in
                                Task { @MainActor in
                                    _ = await viewModel.clarifyCharacterIdentityMention(
                                        mention.name,
                                        clarification: clarification
                                    )
                                }
                            },
                            onGenerate: { guidance in
                                Task { @MainActor in
                                    _ = await viewModel.startCharacterProposal(
                                        for: mention.name,
                                        guidance: guidance
                                    )
                                }
                            },
                            onOpenProposal: {
                                if let activeProposal {
                                    onAcceptSettingProposal(activeProposal)
                                }
                            }
                        )
                    }
                }

                Color.clear
                    .frame(height: ChatLayout.bottomRestGap)
                    .id(Self.bottomAnchorID)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, ChatLayout.contentHorizontalInset)
            .padding(.top, 12)
            .padding(.bottom, 8)
            .scrollTargetLayout()
            .background {
                if isNativeScrollDriverDesired {
                    NativeTimelineScrollViewResolver(
                        onResolve: handleNativeScrollViewResolved,
                        onMetricsChanged: {
                            guard isNativeScrollDriverActive else { return }
                            scrollDriver.handleLayoutMetricsChanged()
                        }
                    )
                }
            }
        }
        .scrollPosition($scrollPosition)
        .defaultScrollAnchor(.bottom, for: .initialOffset)
        // 2026-07-26 撤锚:`.defaultScrollAnchor(.bottom, for: .sizeChanges)` 曾被当作
        // 流式底部锚点的唯一所有者,依据是早期合成探针的「逐帧零欠账」——
        // 但那套探针用 Color.frame(height:) 代理流式气泡,只覆盖 SwiftUI
        // 单次干净布局 pass,从未测过生产 ChatAssistantMarkdownView → vendor
        // ParagraphUIView(UITextView 增量 TextKit 布局 + 异步 invalidateIntrinsicContentSize)
        // 的真实异步增量路径。真机录屏(15fps 逐帧对齐)在小说「写整章」实测到连续
        // 三次 −391/−398/−385px 的结构性跳变,与标准 Chat 撤锚前实测的 893pt 底部
        // 欠账同源(见 ChatCollectionMessageList.swift:1510 附近注释)。现在撤掉这枚
        // 锚,把增长所有权交回下方 onScrollGeometryChange 的 measured-geometry 回调
        // (唯一写者,经 NovelSessionBottomFollowPolicy.reduce 的 .measuredStreamGrowth
        // 分支发出无动画的 .followBottom(animated: false))。
        .defaultScrollAnchor(.top, for: .alignment)
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .contentShape(Rectangle())
        .simultaneousGesture(
            TapGesture().onEnded {
                dismissKeyboard()
            }
        )
        .onScrollPhaseChange { _, phase in
            switch phase {
            case .tracking, .interacting:
                if isNativeScrollDriverActive {
                    guard NativeChatTimelineView.shouldBeginNativeUserDrag(
                        phase: phase,
                        isUIKitUserInteracting: scrollDriver.isUIKitUserInteracting
                    ) else { return }
                }
                guard !userDragging else { return }
                userDragging = true
                dismissKeyboard()
                if isNativeScrollDriverActive {
                    scrollDriver.submit(.userDragBegan)
                }
                dispatchFollowEvent(.userDragBegan(isAtBottom: latestAtBottom))
            case .idle:
                guard userDragging else { return }
                userDragging = false
                let returnedToBottom = NativeTimelineScrollReturnPolicy.returnedToBottom(
                    liveDistanceToBottom: isNativeScrollDriverActive
                        ? scrollDriver.distanceToBottomNow()
                        : nil,
                    cachedNearBottom: latestNearBottom,
                    threshold: ChatLayout.nearBottomResumeThreshold
                )
                if isNativeScrollDriverActive {
                    scrollDriver.submit(.userDragEnded(isAtBottom: returnedToBottom))
                }
                if returnedToBottom {
                    releaseSuspendedStreamingTail()
                }
                dispatchFollowEvent(.userDragEnded(isAtBottom: returnedToBottom))
            case .animating, .decelerating:
                break
            @unknown default:
                break
            }
        }
        .onScrollGeometryChange(for: NovelSessionScrollGeometrySignal.self) { geometry in
            let distanceToBottom = geometry.contentSize.height - geometry.visibleRect.maxY
            return NovelSessionScrollGeometrySignal(
                contentHeight: geometry.contentSize.height,
                isAtBottom: distanceToBottom <= ChatLayout.bottomStickThreshold,
                isNearBottom: NovelSessionBottomProximityPolicy.isNearBottom(
                    distanceToBottom: distanceToBottom
                )
            )
        } action: { oldValue, newValue in
            latestAtBottom = newValue.isAtBottom
            latestNearBottom = newValue.isNearBottom
            let followEvents = NovelSessionScrollGeometryPolicy.events(
                previousContentHeight: oldValue.contentHeight,
                currentContentHeight: newValue.contentHeight,
                userDragging: userDragging,
                isLiveTail: isLiveTailPhase(listSignal.activeTailPhase),
                isSettlingTerminal: isSettlingTerminal,
                previousIsAtBottom: oldValue.isAtBottom,
                currentIsAtBottom: newValue.isAtBottom
            )
            for event in followEvents {
                dispatchFollowEvent(event)
            }
        }
        .transaction(value: listSignal.activeTailDigest) { transaction in
            if isLiveTailPhase(listSignal.activeTailPhase) {
                transaction.animation = nil
            }
        }
    }

    private func transcriptRow(
        _ row: NovelSessionRowModel,
        activeTailID: NovelMessageID?
    ) -> some View {
        let messageID = row.id.description
        let tracksStreamingTail = row.id == activeTailID ||
            streamingTailVisibility.messageID == messageID
        let updatesSuspended = ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
            isLastAssistant: tracksStreamingTail && row.role == .assistant,
            hasEverStreamed: tracksStreamingTail,
            messageID: messageID,
            visibility: streamingTailVisibility
        )
        let renderedRow = updatesSuspended && suspendedStreamingTailRow?.id == row.id
            ? suspendedStreamingTailRow ?? row
            : row

        return NovelSessionRowView(
            row: renderedRow,
            adoptingPolishCandidateID: viewModel.adoptingPolishCandidateID,
            askUserBlocker: askUserBlocker,
            runtimeActionBlocker: NovelSessionComposerPolicy.runtimeActionBlocker(
                requiresReload: workspace.requiresReload,
                hasRefreshError: viewModel.hasRefreshError,
                isBusy: viewModel.isBusy
            ),
            polishRetryTransactionID: viewModel.polishRetryTransactionID,
            onAction: handleRowAction,
            onCancelPolishRetry: viewModel.cancelPolishRetry,
            onAnswerAskUser: handleAskUserAnswer,
            onToggleArchive: toggleArchive
        )
            .equatable()
            .fixedSize(horizontal: false, vertical: true)
            .id(row.id)
            .modifier(NovelSessionStreamingTailVisibilityModifier(
                active: tracksStreamingTail,
                onVisibilityChanged: { isVisible in
                    updateStreamingTailVisibility(row: row, isVisible: isVisible)
                }
            ))
    }

    private func handleAskUserAnswer(
        _ messageID: NovelMessageID,
        _ answer: String
    ) {
        guard askUserBlocker == nil else { return }
        dismissKeyboard()
        Task { @MainActor in
            _ = await viewModel.answerAskUser(
                promptMessageID: messageID,
                answer: answer
            )
        }
    }

    private func composer(listModel: NovelSessionListModel?) -> some View {
        VStack(spacing: 8) {
            if let transaction = viewModel.unresolvedBranchPolishTransactions.first {
                polishRecoveryBanner(transaction)
            }

            if let projectID = workspace.selectedProjectID,
               let branchID = workspace.selectedBranchID,
               let activity = workspace.stateSyncActivity,
               activity.projectID == projectID,
               activity.branchID == branchID {
                stateSyncProgressBanner(activity)
            } else if let projectID = workspace.selectedProjectID,
                      let branchID = workspace.selectedBranchID,
                      workspace.canCancelAutomaticStateSync(
                          projectID: projectID,
                          branchID: branchID
                      ) ||
                      workspace.isStateSyncStopping(
                          projectID: projectID,
                          branchID: branchID
                      ) {
                // Preparing / stopping before activity is published, or after Stop
                // while teardown finishes — keep Stop reachable and block explained.
                stateSyncLightweightBanner(projectID: projectID, branchID: branchID)
            } else if let projectID = workspace.selectedProjectID,
                      let branchID = workspace.selectedBranchID,
                      let failure = workspace.stateSyncRecoveryMessage(
                          projectID: projectID,
                          branchID: branchID
                      ) {
                automaticStateSyncFailureBanner(
                    failure,
                    projectID: projectID,
                    branchID: branchID
                )
            } else if !viewModel.retryableBranchPendingOperations.isEmpty && !viewModel.isBusy {
                synchronizationBanner
            }

            if let recovery = quickStartRecovery(listModel: listModel) {
                quickStartRecoveryBanner(recovery)
            }

            if let error = viewModel.errorMessage {
                errorBanner(error)
            }

            // 代笔状态条：中断/失败后主界面也能直接「继续」，不必打开管理面板。
            if let ghostwrite = viewModel.ghostwriteProgress {
                ghostwriteStatusBar(ghostwrite)
            }

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
                        .frame(height: max(44, composerInputHeight))

                        if inputText.isEmpty {
                            Text(inputPlaceholder)
                                .font(.body)
                                .foregroundStyle(AmberTheme.muted)
                                .allowsHitTesting(false)
                        }
                    }
                    .frame(minHeight: 44)
                }
                .padding(.leading, 18)
                .padding(.trailing, 18)
                // 与 Chat 一致：内容行 44 + 上下 5 → 外高 54，对齐发送键。
                .padding(.vertical, 5)
                .composerDockGlass(cornerRadius: 27)

                ComposerDockSendButton(
                    isLoading: viewModel.isRunning && viewModel.canStop,
                    sendEnabled: sendEnabled,
                    diameter: 54,
                    onSend: send,
                    onStop: stop
                )
            }

            if showsComposerMeta {
                composerMetaControls
                    .transition(
                        accessibilityReduceMotion
                            ? .identity
                            : .move(edge: .top).combined(with: .opacity)
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
        .animation(
            accessibilityReduceMotion ? nil : .spring(response: 0.26, dampingFraction: 0.86),
            value: showsComposerMeta
        )
    }

    private var synchronizationBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.arrow.triangle.2.circlepath")
                .foregroundStyle(AmberTheme.accentAmber)

            Text(syncBannerText)
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(maxWidth: .infinity, alignment: .leading)

            if let pending = viewModel.retryableBranchPendingOperations.first {
                Button("重试") {
                    Task { @MainActor in
                        await viewModel.retryPending(pending.id)
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                .disabled(
                    viewModel.isRunning || viewModel.isBusy || workspace.requiresReload ||
                        viewModel.access != .readWrite
                )
            }
        }
        .padding(.horizontal, 4)
    }

    private func automaticStateSyncFailureBanner(
        _ message: String,
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.arrow.triangle.2.circlepath")
                .foregroundStyle(AmberTheme.accentAmber)

            Text(message)
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button("重试同步") {
                workspace.retryStateSync(
                    projectID: projectID,
                    branchID: branchID
                )
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
            .disabled(
                workspace.isProjectSelectionBlocked ||
                    viewModel.isRunning ||
                    viewModel.isBusy ||
                    workspace.requiresReload ||
                    viewModel.access != .readWrite
            )
        }
        .padding(.horizontal, 4)
    }

    private func polishRecoveryBanner(
        _ transaction: NovelPendingPolishTransactionRecord
    ) -> some View {
        let unresolvedTransactions = viewModel.unresolvedBranchPolishTransactions
        let unresolvedCount = unresolvedTransactions.count
        let blocker = NovelSessionComposerPolicy.polishTransactionBlocker(
            access: viewModel.access,
            requiresReload: workspace.requiresReload,
            isRunning: viewModel.isRunning,
            isBusy: viewModel.isPerformingAction || workspace.isPerforming
        )
        let retryBlocker = blocker ?? viewModel.polishTransactionSourceBlocker(transaction.id)
        let isRetrying = viewModel.polishRetryTransactionID == transaction.id
        return HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(AmberTheme.accentAmber)

            VStack(alignment: .leading, spacing: 2) {
                Text(unresolvedCount > 1
                    ? "还有 \(unresolvedCount) 项润色需要处理"
                    : "上次润色还需要处理")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                if isRetrying {
                    Text("正在检查剧情一致性…")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                } else {
                    Text(recoveryAbandonTask == nil
                        ? polishRecoveryDetail(transaction, blocker: retryBlocker)
                        : "正在放弃未完成的润色…")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if isRetrying {
                HStack(spacing: 6) {
                    ProgressView()
                        .controlSize(.small)
                    Button("停止") {
                        viewModel.cancelPolishRetry()
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
            } else {
                Menu("处理") {
                    if transaction.status == .pending || transaction.status == .retryable {
                        Button("重试检查", systemImage: "arrow.clockwise") {
                            viewModel.startPolishRetry(transaction.id)
                        }
                        .disabled(retryBlocker != nil)
                    }
                    Button("放弃这次润色", systemImage: "xmark.circle", role: .destructive) {
                        pendingRecoveryAbandonTransactionIDs = [transaction.id]
                    }
                    if unresolvedCount > 1 {
                        Button(
                            "放弃全部 \(unresolvedCount) 项",
                            systemImage: "xmark.circle.fill",
                            role: .destructive
                        ) {
                            pendingRecoveryAbandonTransactionIDs = unresolvedTransactions.map(\.id)
                        }
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
                .disabled(blocker != nil || recoveryAbandonTask != nil)
                .confirmationDialog(
                    pendingRecoveryAbandonTransactionIDs.count > 1
                        ? "放弃全部 \(pendingRecoveryAbandonTransactionIDs.count) 项润色？"
                        : "放弃这次润色？",
                    isPresented: recoveryAbandonConfirmationBinding,
                    titleVisibility: .visible
                ) {
                    Button(
                        pendingRecoveryAbandonTransactionIDs.count > 1 ? "全部放弃" : "放弃润色",
                        role: .destructive
                    ) {
                        let transactionIDs = pendingRecoveryAbandonTransactionIDs
                        pendingRecoveryAbandonTransactionIDs = []
                        abandonRecoveryTransactions(transactionIDs)
                    }
                    Button("取消", role: .cancel) {
                        pendingRecoveryAbandonTransactionIDs = []
                    }
                } message: {
                    Text("候选会保留在创作记录中，但不能再作为润色版采用。")
                }
            }
        }
        .padding(.horizontal, 4)
    }

    private var recoveryAbandonConfirmationBinding: Binding<Bool> {
        Binding(
            get: { !pendingRecoveryAbandonTransactionIDs.isEmpty },
            set: { presented in
                if !presented { pendingRecoveryAbandonTransactionIDs = [] }
            }
        )
    }

    private func abandonRecoveryTransactions(
        _ transactionIDs: [NovelPendingOperationID]
    ) {
        guard !transactionIDs.isEmpty,
              recoveryAbandonTask == nil else { return }
        recoveryAbandonTask = Task { @MainActor in
            _ = await viewModel.abandonPolishTransactions(transactionIDs)
            recoveryAbandonTask = nil
        }
    }

    private func polishRecoveryDetail(
        _ transaction: NovelPendingPolishTransactionRecord,
        blocker: NovelSessionActionBlocker?
    ) -> String {
        if let blocker {
            return "当前不可处理：\(blocker.displayName)"
        }
        if let message = transaction.lastFailure?.message.trimmingCharacters(
            in: .whitespacesAndNewlines
        ), !message.isEmpty {
            return message
        }
        return transaction.status == .blocked
            ? "这次润色已被阻止，可以放弃后继续创作。"
            : "剧情一致性检查未完成，可以重试或放弃。"
    }

    private func stateSyncLightweightBanner(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> some View {
        HStack(spacing: 8) {
            ProgressView()
                .controlSize(.small)
                .tint(AmberTheme.accentAmber)

            Text(
                workspace.stateSyncStatusTitle(projectID: projectID, branchID: branchID)
                    ?? "正在准备剧情状态"
            )
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(maxWidth: .infinity, alignment: .leading)

            if workspace.canCancelAutomaticStateSync(
                projectID: projectID,
                branchID: branchID
            ) {
                Button("停止") {
                    workspace.cancelAutomaticStateSync(
                        projectID: projectID,
                        branchID: branchID
                    )
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
        }
        .padding(.horizontal, 4)
    }

    private func stateSyncProgressBanner(_ activity: NovelStateSyncActivity) -> some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let fraction = activity.displayedCompletionFraction
            let percent = fraction.map { Int(($0 * 100).rounded(.down)) }
            let waitingSince = activity.requestStartedAt ?? activity.startedAt
            let elapsed = Int(max(0, context.date.timeIntervalSince(waitingSince)))

            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                        .tint(AmberTheme.accentAmber)

                    Text(
                        workspace.stateSyncStatusTitle(
                            projectID: activity.projectID,
                            branchID: activity.branchID
                        )
                            ?? (activity.phase == .preparing
                                ? "正在准备剧情状态"
                                : "正在同步剧情状态")
                    )
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AmberTheme.foreground2)

                    Spacer(minLength: 8)

                    if workspace.canCancelAutomaticStateSync(
                        projectID: activity.projectID,
                        branchID: activity.branchID
                    ) {
                        Button("停止") {
                            workspace.cancelAutomaticStateSync(
                                projectID: activity.projectID,
                                branchID: activity.branchID
                            )
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }

                    if let percent {
                        Text("\(percent)%")
                            .font(.footnote.weight(.semibold).monospacedDigit())
                            .foregroundStyle(AmberTheme.foreground2)
                    }
                }

                if let fraction {
                    ProgressView(value: fraction)
                        .tint(AmberTheme.accentAmber)
                }

                Text(stateSyncProgressDetail(
                    activity: activity,
                    percent: percent,
                    elapsed: elapsed
                ))
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .monospacedDigit()
            }
            .padding(.horizontal, 4)
        }
    }

    private func stateSyncProgressDetail(
        activity: NovelStateSyncActivity,
        percent: Int?,
        elapsed: Int
    ) -> String {
        guard let percent else {
            if activity.phase == .analyzing {
                return "正在分析第 \(activity.completedChunks + 1) 段 · 已等待 \(elapsed) 秒"
            }
            return "已等待 \(elapsed) 秒 · 正在准备请求"
        }
        let chunkDetail = activity.completedChunks > 0
            ? " · 已完成 \(activity.completedChunks) 段"
            : ""
        return "正文已处理 \(percent)%\(chunkDetail) · 已等待 \(elapsed) 秒"
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
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            } else if viewModel.canRetryPendingTerminal {
                Button("重试保存") {
                    Task { @MainActor in await viewModel.retryPendingTerminal() }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            } else if viewModel.canRetryLastTerminal {
                Button("重试") {
                    Task { @MainActor in _ = await viewModel.retryLastTerminal() }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            } else {
                Button {
                    viewModel.clearError()
                } label: {
                    Image(systemName: "xmark")
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("关闭错误提示")
            }
        }
        .padding(.horizontal, 4)
    }

    /// 主界面代笔状态条：状态 + 详情 + 暂停/继续。复用会话 VM 既有入口，
    /// 不引入新状态；继续键沿用与面板一致的 `canStartGhostwriteChapter` 门。
    private func ghostwriteStatusBar(_ progress: NovelGhostwriteProgress) -> some View {
        HStack(spacing: 10) {
            Image(systemName: viewModel.isGhostwriting ? "pencil.and.scribble" : "pause.circle")
                .foregroundStyle(AmberTheme.accentAmber)
            VStack(alignment: .leading, spacing: 2) {
                Text(progress.statusLabel)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let detail = progress.detailMessage, !detail.isEmpty {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }

            if viewModel.isGhostwriting {
                Button("暂停") {
                    viewModel.pauseGhostwrite()
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            } else if progress.shouldContinueSameBatch {
                Button("继续") {
                    _ = viewModel.continueGhostwriteChapter()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                .disabled(!viewModel.canStartGhostwriteChapter)
            }
        }
        .padding(.horizontal, 4)
        .accessibilityElement(children: .contain)
    }

    private func quickStartRecovery(
        listModel: NovelSessionListModel?
    ) -> NovelSessionQuickStartRecovery? {
        guard workspace.projectSnapshot?.project.creationMode == .quickStart else { return nil }
        switch workspace.quickStartStatus {
        case .failed(let message):
            let hasDurableRetryOwner = workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.branchID == workspace.selectedBranchID &&
                    $0.kind == .quickStart &&
                    ($0.status == .failed || $0.status == .interrupted)
            }) == true
            return hasDurableRetryOwner ? nil : .retry(message: message)
        case .refreshFailed(let message):
            return .reload(message: message)
        case .persistenceBlocked(let runID, let message):
            let hasDurableRow = listModel?.rows.contains(where: { $0.runID == runID }) == true
            return hasDurableRow ? nil : .retryPersistence(runID: runID, message: message)
        case .idle, .starting, .generating, .awaitingUser:
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
            .frame(minHeight: 44)
            .contentShape(Rectangle())
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

    private var composerMetaControls: some View {
        HStack(spacing: 8) {
            Button(action: onOpenModel) {
                Text(composerModelLabel)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(1)
                    .padding(.horizontal, 12)
                    .frame(height: 30)
                    .composerDockGlass(cornerRadius: 15)
            }
            .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.96, haptic: .selection))
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
            .disabled(controlsDisabled)
            .accessibilityLabel("切换模型，当前 \(composerModelLabel)")

            Spacer(minLength: 0)

            Menu {
                Picker("创作方式", selection: composerIntentBinding) {
                    Section("构思") {
                        ForEach(NovelComposerIntent.discussionOptions) { intent in
                            Text(intent.title).tag(intent)
                        }
                    }
                    Section("写正文") {
                        ForEach(NovelComposerIntent.proseOptions) { intent in
                            Text(intent.title).tag(intent)
                        }
                    }
                }
                Divider()
                Button("归档当前讨论", systemImage: "archivebox") {
                    onArchiveDiscussion()
                }
                .disabled(
                    !viewModel.hasArchivableDiscussion ||
                        viewModel.needsSync ||
                        viewModel.isBusy
                )
            } label: {
                Text(currentComposerIntent.title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .padding(.horizontal, 12)
                    .frame(height: 30)
                    .composerDockGlass(cornerRadius: 15)
            }
            .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.96, haptic: .selection))
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
            .disabled(controlsDisabled)
            .accessibilityLabel("创作方式，当前 \(currentComposerIntent.title)")

            ContextRingButton(
                snapshot: contextRingSnapshot,
                compactState: .idle,
                action: { isContextPanelPresented.toggle() }
            )
            .disabled(controlsDisabled)
            .popover(isPresented: $isContextPanelPresented, arrowEdge: .bottom) {
                ComposerContextPanel(
                    snapshot: contextRingSnapshot,
                    novelInjection: contextPanelModel
                )
                    .presentationCompactAdaptation(.popover)
            }
        }
        .padding(.horizontal, 2)
        .padding(.top, 2)
    }

    private var controlsDisabled: Bool {
        viewModel.access != .readWrite ||
            workspace.requiresReload ||
            viewModel.isRunning ||
            viewModel.isBusy
    }

    private func projectedListModel() -> NovelSessionListModel? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else { return nil }
        return viewModel.projectedListModel(
            project: project,
            branch: branch,
            expandedArchiveIDs: expandedArchiveIDs
        )
    }

    private func makeListSignal(
        from model: NovelSessionListModel?
    ) -> NovelSessionListSignal {
        let tail = model?.activeTailID.flatMap { tailID in
            model?.rows.first(where: { $0.id == tailID })
        }
        return NovelSessionListSignal(
            sessionID: model?.sessionID,
            rowCount: model?.rows.count ?? 0,
            activeTailID: model?.activeTailID,
            activeTailDigest: tail?.digest,
            activeTailPhase: tail?.transientPhase,
            activeRunRowCount: model?.activeRunRows.count ?? 0,
            lastRowDigest: model?.rows.last?.digest
        )
    }

    private var bindingTaskID: String {
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

    private var currentComposerIntent: NovelComposerIntent {
        composerIntentBinding.wrappedValue
    }

    private var showsComposerMeta: Bool {
        isInputFocused ||
            isContextPanelPresented ||
            !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            injectionOverrides != .none ||
            viewModel.isRunning
    }

    private var composerModelLabel: String {
        guard let project = workspace.projectSnapshot?.project else { return "选择模型" }
        _ = sharedSettings.revision
        let configured = project.configuredModelPolicy(for: .creation)
        let policy: NovelProjectModelPolicy
        if case .global = configured {
            policy = NovelCreationModelPreferences.shared.policy(for: .creation)
        } else {
            policy = configured
        }
        return NovelPresentation.modelDisplayName(for: policy, sharedSettings: sharedSettings)
    }

    private var contextRingSnapshot: ChatContextSnapshot {
        let receipt = latestContextReceipt
        let estimatedTokens = receipt?.estimatedInputTokens ?? 0
        return ChatContextSnapshot(
            messageCount: viewModel.durableMessages.count,
            modelId: receipt?.modelID ?? composerModelLabel,
            supportsReasoning: false,
            pendingSelectedFileName: nil,
            pendingSelectedFileBytesText: nil,
            promptTokens: estimatedTokens,
            completionTokens: 0,
            totalTokens: estimatedTokens,
            cachedTokens: 0,
            tokensPerSecond: nil,
            contextWindowTokens: receipt?.maxEstimatedInputTokens,
            currentContextTokens: estimatedTokens
        )
    }

    private var contextPanelModel: NovelInjectionPanelModel {
        NovelInjectionPanelPresentation.project(latestContextReceipt)
    }

    private var latestContextReceipt: NovelInjectionReceiptRecord? {
        guard let project = workspace.projectSnapshot,
              let branchID = viewModel.binding?.branchID ?? workspace.selectedBranchID else { return nil }
        return project.injectionReceipts
            .filter { $0.branchID == branchID && $0.factTransaction == nil }
            .max { $0.createdAt < $1.createdAt }
    }

    private var inputFocusBinding: Binding<Bool> {
        Binding(get: { isInputFocused }, set: { isInputFocused = $0 })
    }

    private var sendOnEnter: Bool {
        _ = sharedSettings.revision
        return sharedSettings.displaySetting.sendOnEnter
    }

    private var sendEnabled: Bool {
        sendEnabled(for: inputText)
    }

    private func sendEnabled(for text: String) -> Bool {
        NovelSessionComposerPolicy.canSubmit(canSend: viewModel.canSend, text: text)
    }

    private var askUserBlocker: NovelSessionActionBlocker? {
        NovelSessionComposerPolicy.askUserBlocker(
            access: viewModel.access,
            requiresReload: workspace.requiresReload || viewModel.hasRefreshError,
            isBusy: viewModel.isBusy || viewModel.isRunning
        )
    }

    private var inputPlaceholder: String {
        if viewModel.mode == .discussPlan { return "想聊哪段剧情、人物或设定？" }
        if workspace.projectSnapshot?.project.collaborationMode == .ghostwrite,
           viewModel.granularity == .wholeChapter,
           let branchID = viewModel.binding?.branchID,
           workspace.projectSnapshot?.confirmedChapterPlan(for: branchID) == nil {
            return "代笔写整章前，请先在标题面板确认本章计划"
        }
        if viewModel.needsSync {
            return "请先同步剧情状态，再写正文"
        }
        return viewModel.granularity == .wholeChapter
            ? "描述下一章的目标或关键事件"
            : "描述接下来发生什么"
    }

    private var syncBannerText: String {
        if let pending = viewModel.retryableBranchPendingOperations.first {
            let failure = pending.lastError?.trimmingCharacters(in: .whitespacesAndNewlines)
            if let failure, !failure.isEmpty {
                let reason = NovelPresentation.stateSyncFailureMessage(failure)
                if pending.kind == .manualSync {
                    return "\(reason) 可继续讨论；写正文前请先重试同步。"
                }
                return reason
            }
            return pending.kind == .collection
                ? "旧版收录的剧情状态同步未完成"
                : "上次剧情同步未完成。可继续讨论；写正文前请先重试同步。"
        }
        return "剧情状态同步尚未完成。可继续讨论；写正文前请先完成同步。"
    }

    private func send() {
        let committed = composerInputController.committedText() ?? inputText
        if committed != inputText {
            inputText = committed
        }
        guard sendEnabled(for: committed) else { return }
        guard !committed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let overrides = injectionOverrides
        let budget = inputBudgetTokens
        let draftOwner = workspace.selectedProjectID.flatMap { projectID in
            workspace.selectedBranchID.map {
                NovelComposerDraftOwner(projectID: projectID, branchID: $0)
            }
        }
        dismissKeyboard()
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
            if let draftOwner {
                workspace.saveComposerDraft(
                    .empty,
                    projectID: draftOwner.projectID,
                    branchID: draftOwner.branchID
                )
            }
        }
    }

    private func dismissKeyboard() {
        isInputFocused = false
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil
        )
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
            viewModel.startPolishRetry(transactionID)
        case .abandonPolish(let transactionID):
            Task { @MainActor in await viewModel.abandonPolishTransaction(transactionID) }
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
            releaseSuspendedStreamingTail(resetIdentity: true)
            historyWindowLimit = NovelSessionHistoryWindowPolicy.initialLimit
            expandedArchiveIDs.removeAll()
            if isNativeScrollDriverActive {
                scrollDriver.submit(.conversationReset)
            }
            dispatchFollowEvent(.reset)
            dispatchFollowEvent(.initialRowsPresented(hasRows: newValue.rowCount > 0))
            return
        }
        if let activeTailID = newValue.activeTailID,
           streamingTailVisibility.messageID != activeTailID.description {
            streamingTailVisibility = ChatSwiftUIStreamingTailVisibilityState(
                messageID: activeTailID.description,
                isVisible: nil
            )
            suspendedStreamingTailRow = nil
        } else if newValue.activeTailID == nil, suspendedStreamingTailRow == nil {
            streamingTailVisibility = ChatSwiftUIStreamingTailVisibilityState()
        }
        // 无条件吸收:贴底与否不改变「已渲染的行不得中途被窗口踢出」这条不变量。
        // 口径必须与 `startIndex` 一致——那里用的是 **historicalRows**。若用含活动
        // run 的 rowCount,run 开始时活动行会被算进吸收量,startIndex 反而净下降
        // (每轮 −activeRunRowCount),等于在视口上方插入旧历史。
        historyWindowLimit = NovelSessionHistoryWindowPolicy.limitAfterRowsAppended(
            currentLimit: historyWindowLimit,
            previousRowCount: oldValue.rowCount - oldValue.activeRunRowCount,
            currentRowCount: newValue.rowCount - newValue.activeRunRowCount
        )
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
            historyWindowLimit = NovelSessionHistoryWindowPolicy.limitAfterActiveRunReturnsToHistory(
                currentLimit: historyWindowLimit,
                activeRunRowCount: oldValue.activeRunRowCount,
                previousRowCount: oldValue.rowCount,
                currentRowCount: newValue.rowCount
            )
            dispatchFollowEvent(.terminalReached)
        } else if oldValue.rowCount != newValue.rowCount || oldValue.lastRowDigest != newValue.lastRowDigest {
            dispatchFollowEvent(.terminalLayoutChanged)
        }
    }

    private func presentInitialRowsIfNeeded(_ signal: NovelSessionListSignal) {
        guard signal.sessionID != nil,
              followState.mode == .awaitingInitialRows else { return }
        dispatchFollowEvent(.initialRowsPresented(hasRows: signal.rowCount > 0))
    }

    private func isLiveTailPhase(_ phase: NovelSessionTransientTailPhase?) -> Bool {
        phase == .waitingForFirstToken || phase == .streaming
    }

    private func revealEarlierHistory(
        totalCount: Int,
        preserving anchorID: NovelMessageID?
    ) {
        if isNativeScrollDriverActive {
            scrollDriver.submit(.userDragBegan)
        }
        dispatchFollowEvent(.userDragBegan(isAtBottom: false))
        historyWindowLimit = NovelSessionHistoryWindowPolicy.expandedLimit(
            currentLimit: historyWindowLimit,
            totalCount: totalCount
        )
        guard let anchorID else { return }
        Task { @MainActor in
            await Task.yield()
            var transaction = Transaction()
            transaction.animation = nil
            withTransaction(transaction) {
                scrollPosition.scrollTo(id: anchorID, anchor: .top)
            }
        }
    }

    private func toggleArchive(_ archive: NovelDiscussionArchivePresentation) {
        if expandedArchiveIDs.contains(archive.id) {
            expandedArchiveIDs.remove(archive.id)
        } else {
            historyWindowLimit = NovelSessionHistoryWindowPolicy.limitAfterArchiveExpansion(
                currentLimit: historyWindowLimit,
                revealedRowCount: archive.revealedRowCount
            )
            expandedArchiveIDs.insert(archive.id)
        }
    }

    private func dispatchFollowEvent(_ event: NovelSessionBottomFollowEvent) {
        switch event {
        case .reset, .userDragBegan:
            cancelExplicitBottomAnimation()
        default:
            break
        }
        let transition = NovelSessionBottomFollowPolicy.reduce(
            state: followState,
            event: event,
            followEnabled: followGeneration
        )
        followState = transition.state
        for command in transition.commands {
            executeFollowCommand(command)
        }
    }

    private func executeFollowCommand(_ command: NovelSessionBottomFollowCommand) {
        switch command {
        case .anchorBottom:
            if isNativeScrollDriverActive {
                scrollDriver.submit(
                    .explicitBottom(source: .button, animated: false, keyboardToken: nil)
                )
                return
            }
            var transaction = Transaction()
            transaction.animation = nil
            withTransaction(transaction) {
                scrollPosition.scrollTo(id: Self.bottomAnchorID, anchor: .bottom)
            }
        case .followBottom(let animated):
            if isNativeScrollDriverActive {
                if animated {
                    scrollDriver.submit(
                        .explicitBottom(
                            source: .button,
                            animated: !accessibilityReduceMotion,
                            keyboardToken: nil
                        )
                    )
                } else {
                    scrollDriver.submit(.streamContentGrew)
                }
                return
            }
            if animated {
                startExplicitBottomAnimation()
            } else if explicitBottomAnimationTask != nil {
                explicitBottomFollowPending = true
            } else {
                performLiveBottomFollow()
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

    private func startExplicitBottomAnimation() {
        cancelExplicitBottomAnimation()
        guard !accessibilityReduceMotion else {
            scrollToBottomWithoutAnimation()
            return
        }
        withAnimation(.easeOut(duration: 0.2)) {
            scrollPosition.scrollTo(id: Self.bottomAnchorID, anchor: .bottom)
        }
        explicitBottomAnimationTask = Task { @MainActor in
            do {
                try await Task.sleep(for: .seconds(0.2))
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            let shouldReplay = explicitBottomFollowPending &&
                followGeneration &&
                !userDragging
            explicitBottomFollowPending = false
            explicitBottomAnimationTask = nil
            if shouldReplay {
                performLiveBottomFollow()
            }
        }
    }

    private func cancelExplicitBottomAnimation() {
        explicitBottomFollowPending = false
        explicitBottomAnimationTask?.cancel()
        explicitBottomAnimationTask = nil
    }

    private func performLiveBottomFollow() {
        if isNativeScrollDriverActive {
            scrollDriver.submit(.streamContentGrew)
            return
        }
        scrollToBottomWithoutAnimation()
    }

    private func scrollToBottomWithoutAnimation() {
        if isNativeScrollDriverActive {
            scrollDriver.submit(
                .explicitBottom(source: .button, animated: false, keyboardToken: nil)
            )
            return
        }
        var transaction = Transaction()
        transaction.animation = nil
        withTransaction(transaction) {
            scrollPosition.scrollTo(id: Self.bottomAnchorID, anchor: .bottom)
        }
    }

    private var isNativeScrollDriverDesired: Bool {
        nativeScrollFallbackReason == nil &&
            isNativeScrollSurfaceVisible
    }

    private var isNativeScrollDriverActive: Bool {
        isNativeScrollDriverDesired && scrollDriver.isAttached
    }

    private func handleNativeScrollViewResolved(_ scrollView: UIScrollView) {
        guard isNativeScrollDriverDesired else { return }
        scrollDriver.setAutomaticFollowEnabled(followGeneration)
        scrollDriver.onFallback = { reason, shouldReplayBottom in
            guard nativeScrollFallbackReason == nil else { return }
            nativeScrollFallbackReason = reason
            guard shouldReplayBottom, !userDragging else { return }
            scrollToBottomWithoutAnimation()
        }
        let didAttach = scrollDriver.attach(scrollView)
        guard didAttach, scrollDriver.isAttached else { return }
        if userDragging || isBrowsingHistory {
            scrollDriver.submit(.userDragBegan)
        } else {
            scrollDriver.submit(
                .explicitBottom(source: .button, animated: false, keyboardToken: nil)
            )
        }
    }

    private var isBrowsingHistory: Bool {
        if case .browsingHistory = followState.mode {
            return true
        }
        return false
    }

    private var isSettlingTerminal: Bool {
        if case .settlingTerminal = followState.mode {
            return true
        }
        return false
    }

    private static let bottomAnchorID = "novel-session-bottom-anchor"

    private func updateStreamingTailVisibility(
        row: NovelSessionRowModel,
        isVisible: Bool
    ) {
        let messageID = row.id.description
        streamingTailVisibility = ChatSwiftUIStreamingTailVisibilityState(
            messageID: messageID,
            isVisible: isVisible
        )
        if isVisible {
            suspendedStreamingTailRow = nil
        } else if suspendedStreamingTailRow?.id != row.id {
            suspendedStreamingTailRow = row
        }
    }

    private func releaseSuspendedStreamingTail(resetIdentity: Bool = false) {
        suspendedStreamingTailRow = nil
        if resetIdentity {
            streamingTailVisibility = ChatSwiftUIStreamingTailVisibilityState()
        } else if let messageID = streamingTailVisibility.messageID {
            streamingTailVisibility = ChatSwiftUIStreamingTailVisibilityState(
                messageID: messageID,
                isVisible: nil
            )
        }
    }

    /// 文案必须区分「重写」与「续写/整章」:重新生成的候选默认收录方式是
    /// **替换原章**,若沿用整章文案会显示「收录后成为新章」,与实际行为相反。
    private var generationStatusText: String {
        guard let kind = viewModel.activeRunKind else { return "正在生成" }
        if kind == .regenerate { return "重写本章 · 收录后替换原文" }
        if kind == .polish { return "正在润色本章 · 完成后检查剧情一致性" }
        return viewModel.activeRunGranularity == .wholeChapter
            ? "完整章节 · 收录后成为新章"
            : "正文片段 · 收录后进入本章"
    }

    private var generationStatusIcon: String {
        switch viewModel.activeRunKind {
        case .regenerate: "arrow.triangle.2.circlepath"
        case .polish: "wand.and.sparkles"
        default: "doc.text"
        }
    }

    /// 生成中的候选状态条。原本挂在气泡里正文的正下方,正文每增长一次它就被
    /// 重新布局并被跟随逻辑推动,肉眼就是小幅上下抖动;移到输入框上方后它的
    /// 位置与正文增长解耦。只在生成中出现。
    private var generationStatusStrip: some View {
        // 粒度必须取**活动 run 的记录值**,不能取 composer 的当前设置:
        // `start(_:)` 不回写 viewModel.granularity,重试一个失败的续写 run 时
        // composer 可能已被改成「整章」,导致提示与实际收录目标相反。
        Label(generationStatusText, systemImage: generationStatusIcon)
        .font(.caption)
        .foregroundStyle(AmberTheme.muted)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, ChatLayout.contentHorizontalInset)
        .padding(.bottom, 6)
    }
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
    let activeRunRowCount: Int
    let lastRowDigest: NovelSessionRowDigest?
}

private struct NovelSessionScrollGeometrySignal: Equatable {
    let contentHeight: CGFloat
    let isAtBottom: Bool
    let isNearBottom: Bool
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
    let adoptingPolishCandidateID: NovelCandidateID?
    let askUserBlocker: NovelSessionActionBlocker?
    let runtimeActionBlocker: NovelSessionActionBlocker?
    let polishRetryTransactionID: NovelPendingOperationID?
    let onAction: (NovelSessionRowAction) -> Void
    let onCancelPolishRetry: () -> Void
    let onAnswerAskUser: (NovelMessageID, String) -> Void
    let onToggleArchive: (NovelDiscussionArchivePresentation) -> Void

    nonisolated static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.row.id == rhs.row.id && lhs.row.digest == rhs.row.digest &&
            lhs.adoptingPolishCandidateID == rhs.adoptingPolishCandidateID &&
            lhs.askUserBlocker == rhs.askUserBlocker &&
            lhs.runtimeActionBlocker == rhs.runtimeActionBlocker &&
            lhs.polishRetryTransactionID == rhs.polishRetryTransactionID
    }

    var body: some View {
        if let archive = row.archive {
            NovelDiscussionArchiveCard(archive: archive) {
                onToggleArchive(archive)
            }
        } else {
            NovelSessionBubble(
                messageID: row.id,
                role: row.role,
                kind: row.kind,
                granularity: row.granularity,
                content: row.content,
                isStreaming: row.isStreaming,
                transientPhase: row.transientPhase,
                hasEverStreamed: row.role == .assistant,
                runStatus: row.runStatus,
                candidateStatus: row.candidate?.status,
                isRegeneration: row.candidate?.kind == .prose
                    && row.candidate?.sourceChapterVersionID != nil,
                polishTransactionStatus: row.candidate?.polishTransactionStatus,
                isAdoptingPolish: row.candidate?.id == adoptingPolishCandidateID,
                committedChange: row.committedChange,
                askUser: row.askUser,
                askUserBlocker: askUserBlocker,
                runtimeActionBlocker: runtimeActionBlocker,
                retryingPolishTransactionID: polishRetryTransactionID,
                onCancelPolishRetry: onCancelPolishRetry,
                actions: row.actions,
                onAction: onAction,
                onAnswerAskUser: onAnswerAskUser
            )
        }
    }
}

private struct NovelSessionStreamingTailVisibilityModifier: ViewModifier {
    let active: Bool
    let onVisibilityChanged: (Bool) -> Void

    @ViewBuilder
    func body(content: Content) -> some View {
        if active {
            content
                .onGeometryChange(for: Bool?.self) { proxy in
                    guard let viewportBounds = proxy.bounds(of: .scrollView) else { return nil }
                    return proxy.frame(in: .local).intersects(viewportBounds)
                } action: { isVisible in
                    guard let isVisible else { return }
                    onVisibilityChanged(isVisible)
                }
                .onDisappear {
                    onVisibilityChanged(false)
                }
        } else {
            content
        }
    }
}

private struct NovelDiscussionArchiveCard: View {
    let archive: NovelDiscussionArchivePresentation
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            VStack(alignment: .leading, spacing: 8) {
                Label(archive.title, systemImage: "archivebox.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(archive.summary)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.foreground2)
                    .multilineTextAlignment(.leading)
                Label(
                    archive.isExpanded ? "收起讨论" : "展开讨论",
                    systemImage: archive.isExpanded ? "chevron.up" : "chevron.down"
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.accent)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .amberGlass(cornerRadius: 8, interactive: true)
        }
        .buttonStyle(.plain)
    }
}

private struct NovelCharacterIdentityQuestionCard: View {
    let mention: NovelCharacterIdentityMention
    let choices: [(material: NovelMaterialRecord, title: String)]
    let activeProposal: NovelSettingProposalRecord?
    let relatedProposalCount: Int
    let isDisabled: Bool
    let onSelect: (NovelMaterialID) -> Void
    let onIgnore: () -> Void
    let onClarify: (String) -> Void
    let onGenerate: (String) -> Void
    let onOpenProposal: () -> Void

    @State private var isClarificationFieldPresented = false
    @State private var isProposalFieldPresented = false
    @State private var clarification = ""
    @State private var proposalGuidance = ""
    @FocusState private var isClarificationFocused: Bool
    @FocusState private var isProposalGuidanceFocused: Bool

    private var normalizedClarification: String {
        clarification.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "person.crop.circle.badge.questionmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 36, height: 36)
                    .background(AmberTheme.accent.opacity(0.10), in: Circle())

                VStack(alignment: .leading, spacing: 2) {
                    Text("确认人物身份")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text("关联已有角色，或为新人物生成建议")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
            }

            Text("正文中的“\(mention.name)”对应哪位角色？确认后，已有经历会自动归入同一人物。")
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .fixedSize(horizontal: false, vertical: true)

            if let activeProposal {
                Button(action: onOpenProposal) {
                    HStack(spacing: 10) {
                        Image(systemName: "sparkles")
                            .foregroundStyle(AmberTheme.accent)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(activeProposal.title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Text(relatedProposalCount == 0
                                ? "人物建议已生成，确认后才会建档"
                                : "另有 \(relatedProposalCount) 条关系、世界观或剧情建议")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                        }
                        Spacer(minLength: 8)
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .padding(.horizontal, 12)
                    .frame(maxWidth: .infinity, minHeight: 54, alignment: .leading)
                    .background(AmberTheme.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(isDisabled)
            } else if !choices.isEmpty {
                ForEach(choices, id: \.material.id) { choice in
                    Button {
                        onSelect(choice.material.id)
                    } label: {
                        HStack {
                            Text(choice.title)
                                .foregroundStyle(AmberTheme.foreground)
                            Spacer(minLength: 0)
                            Image(systemName: "link")
                                .foregroundStyle(AmberTheme.accent)
                        }
                        .padding(.horizontal, 12)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .disabled(isDisabled)
                }
            }

            if activeProposal == nil {
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) {
                        isProposalFieldPresented.toggle()
                    }
                    isProposalGuidanceFocused = isProposalFieldPresented
                } label: {
                    Label("新建人物", systemImage: "person.crop.circle.badge.plus")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.white)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(isDisabled)
            }

            HStack(spacing: 10) {
                Button(action: onIgnore) {
                    Label("忽略", systemImage: "person.crop.circle.badge.xmark")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(AmberTheme.surface, in: Capsule())
                }

                Button {
                    withAnimation(.easeInOut(duration: 0.18)) {
                        isClarificationFieldPresented.toggle()
                    }
                    isClarificationFocused = isClarificationFieldPresented
                } label: {
                    Label("补充说明", systemImage: "square.and.pencil")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AmberTheme.accent)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(AmberTheme.accent.opacity(0.10), in: Capsule())
                }
            }
            .buttonStyle(.plain)
            .disabled(isDisabled)

            if isProposalFieldPresented && activeProposal == nil {
                VStack(alignment: .trailing, spacing: 8) {
                    TextField(
                        "可选：补充身份、关系或剧情方向",
                        text: $proposalGuidance,
                        axis: .vertical
                    )
                    .lineLimit(2 ... 5)
                    .focused($isProposalGuidanceFocused)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 12))

                    Button {
                        NovelTextInputCommitter.perform {
                            onGenerate(proposalGuidance)
                            isProposalGuidanceFocused = false
                        }
                    } label: {
                        Label("生成建议", systemImage: "arrow.up")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(Color.white)
                            .frame(minHeight: 44)
                            .padding(.horizontal, 16)
                            .background(AmberTheme.accent, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(isDisabled)
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }

            if isClarificationFieldPresented {
                VStack(alignment: .trailing, spacing: 8) {
                    TextField(
                        "例如：一次性路人，不需要建档",
                        text: $clarification,
                        axis: .vertical
                    )
                    .lineLimit(2 ... 4)
                    .focused($isClarificationFocused)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 12))

                    Button {
                        NovelTextInputCommitter.perform {
                            onClarify(normalizedClarification)
                            isClarificationFocused = false
                        }
                    } label: {
                        Label("确认说明", systemImage: "checkmark")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(minHeight: 44)
                            .padding(.horizontal, 14)
                            .background(AmberTheme.accent.opacity(0.10), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(isDisabled)
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }

            if isDisabled {
                Text("停止或等待当前任务完成后，即可处理人物身份。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
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
}
