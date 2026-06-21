import SwiftUI
import Shared
import UniformTypeIdentifiers

private enum ComposerPanel: String, Identifiable {
    case thinking
    case context

    var id: String { rawValue }
}

struct ChatView: View {

    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    let documentStore: DocumentAccessStore?
    let workspaceStore: IOSWorkspaceStore
    @State private var viewModel: ChatViewModel
    @State private var activeComposerPanel: ComposerPanel?
    @State private var isModelSheetPresented = false
    @State private var isImportingSelectedFile = false
    @FocusState private var isInputFocused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router
    @AppStorage(IOSDisplayPreferenceKeys.followGeneration) private var followGeneration = true
    @State private var pasteHintShown = false
    @State private var pendingInitialScrollToBottom = false
    @State private var followPaused = false
    @State private var userDragging = false
    @Environment(IOSConversationStore.self) private var conversationStore

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore = IOSSharedSettingsStore(),
        localToolExecutor: IOSLocalToolExecutor? = nil,
        documentStore: DocumentAccessStore? = nil,
        workspaceStore: IOSWorkspaceStore = .shared
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.documentStore = documentStore
        self.workspaceStore = workspaceStore
        self._viewModel = State(
            initialValue: ChatViewModel(
                settingsStore: settingsStore,
                sharedSettings: sharedSettings,
                localToolExecutor: localToolExecutor
            )
        )
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()
            messageList
        }
        .safeAreaBar(edge: .top, spacing: 0) {
            topBar
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            inputBar
        }
        .sheet(isPresented: $isModelSheetPresented) {
            ComposerModelSheet(sharedSettings: sharedSettings, currentModel: composerCurrentModelSelection) { model in
                sharedSettings.setCurrentChatModelId(model.id)
                sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
                viewModel.messageRevision &+= 1
                isModelSheetPresented = false
            }
            .presentationDetents([.fraction(0.72), .large])
            .presentationDragIndicator(.hidden)
            .presentationCornerRadius(28)
            .presentationBackground(AmberTheme.glassStrong)
        }
        .fileImporter(
            isPresented: $isImportingSelectedFile,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            handleSelectedFileImport(result)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: Binding(
            get: { conversationStore.lastIOError },
            set: { newValue in if newValue == nil { conversationStore.clearIOError() } }
        )) { error in
            Alert(
                title: Text("会话存储出错"),
                message: Text(error.message),
                dismissButton: .default(Text("好")) { conversationStore.clearIOError() }
            )
        }
        .onAppear {
            // 绑定 store（@Environment 在 init 里不可用，故在 onAppear 注入）。
            viewModel.conversationStore = conversationStore
            followPaused = false
            pendingInitialScrollToBottom = true
            viewModel.reloadFromStore()
            repairCurrentChatModelIfNeeded()
            if let handoff = IOSWebMountContentHandoffStore.shared.consumeChatHandoff() {
                viewModel.inputText = handoff.chatPrompt
                viewModel.selectedFileContextError = nil
            }
        }
        // 会话切换（store.currentRevision 变化）时重新灌入历史消息。
        // 用 Int 修订号而非 KotlinUuid——后者是否被 Swift 当作 Equatable 不可靠。
        .onChange(of: conversationStore.currentRevision) { _, _ in
            followPaused = false
            pendingInitialScrollToBottom = true
            viewModel.reloadFromStore()
        }
        .onChange(of: sharedSettings.revision) { _, _ in
            repairCurrentChatModelIfNeeded()
            viewModel.messageRevision &+= 1
        }
    }

    private func handleSelectedFileImport(_ result: Result<[URL], Error>) {
        guard let documentStore else {
            viewModel.selectedFileContextError = "文件选择器未连接。"
            return
        }

        switch result {
        case .success(let urls):
            guard let url = urls.first else {
                let message = "没有选择文件。"
                documentStore.recordSelectionError(message)
                viewModel.selectedFileContextError = message
                return
            }
            documentStore.registerPickedFile(url)
            Task {
                var workspaceImportError: String?
                do {
                    _ = try await workspaceStore.importFile(url: url, source: "chat_picker")
                } catch {
                    workspaceImportError = error.localizedDescription
                }
                await viewModel.attachSelectedFilePreviewToNextMessage()
                if let workspaceImportError, viewModel.selectedFileContextError == nil {
                    viewModel.selectedFileContextError = "已附加到本条消息，但未保存到 Workspace：\(workspaceImportError)"
                }
            }
        case .failure(let error):
            let message = "文件选择失败：\(error.localizedDescription)"
            documentStore.recordSelectionError(message)
            viewModel.selectedFileContextError = message
        }
    }

    private var topBar: some View {
        HStack {
            backToolbarButton

            Spacer()

            Text("问候")
                .font(.headline)
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            newChatToolbarButton
        }
        .padding(.horizontal, 18)
        .frame(height: 54)
    }

    private var backToolbarButton: some View {
        ChatToolbarIconButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 38, symbolSize: 18) {
            dismiss()
        }
    }

    private var newChatToolbarButton: some View {
        ChatToolbarIconButton(systemImage: "square.and.pencil", accessibilityLabel: "新建对话", size: 38, symbolSize: 16) {
            viewModel.cancelGeneration()
            Task { @MainActor in
                followPaused = false
                await conversationStore.newConversation()
                viewModel.reloadFromStore()
            }
        }
    }

    // MARK: - Message List

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 14) {
                    if viewModel.messages.isEmpty {
                        if let configurationIssue {
                            ChatConfigurationNoticeCard(
                                issue: configurationIssue,
                                onPrimary: openPrimaryConfigurationAction,
                                onModelDefaults: openModelDefaults
                            )
                            .padding(.top, 72)
                            .padding(.bottom, 150)
                        } else {
                            ChatEmptyState()
                        }
                    } else {
                        if let configurationIssue {
                            ChatConfigurationNoticeCard(
                                issue: configurationIssue,
                                compact: true,
                                onPrimary: openPrimaryConfigurationAction,
                                onModelDefaults: openModelDefaults
                            )
                        }
                        ForEach(Array(viewModel.messages.enumerated()), id: \.element.id) { index, message in
                            let isLastStreamingMessage = index == viewModel.messages.count - 1 && isStreamingFollowActive

                            VStack(spacing: 0) {
                                MessageBubbleView(
                                    message: message,
                                    messageIndex: index,
                                    variantInfo: viewModel.variantInfo(atMessageIndex: index),
                                    displaySetting: sharedSettings.displaySetting,
                                    onRegenerate: { viewModel.regenerate(atMessageIndex: index) },
                                    onEdit: { newText in viewModel.editMessage(atMessageIndex: index, newText: newText) },
                                    onDelete: { viewModel.deleteMessage(atMessageIndex: index) },
                                    onSelectVariant: { variantIndex in viewModel.selectVariant(messageIndex: index, variantIndex: variantIndex) },
                                    isGenerating: viewModel.isGenerationActive
                                )

                                if isLastStreamingMessage {
                                    Color.clear
                                        .frame(height: ChatLayout.followBottomGap)
                                        .allowsHitTesting(false)
                                        .accessibilityHidden(true)
                                }
                            }
                            .id(message.id)
                        }
                    }
                }
                .padding(.horizontal, ChatLayout.contentHorizontalInset)
                .padding(.vertical, 12)
                .padding(.bottom, 18)
            }
            .onScrollPhaseChange { _, phase in
                switch phase {
                case .interacting:
                    userDragging = true
                case .idle:
                    userDragging = false
                default:
                    break
                }
            }
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentSize.height - geometry.visibleRect.maxY <= ChatLayout.bottomStickThreshold
            } action: { _, atBottom in
                if userDragging {
                    followPaused = !atBottom
                }
            }
            .onChange(of: viewModel.messageRevision) { _, _ in
                if pendingInitialScrollToBottom {
                    pendingInitialScrollToBottom = false
                    scrollToLatestMessage(proxy, animated: false, deferred: true)
                } else if followGeneration, !followPaused {
                    scrollToLatestMessage(proxy, animated: true, deferred: false)
                }
            }
            .onChange(of: isInputFocused) { _, focused in
                guard focused, !followPaused, !viewModel.messages.isEmpty else { return }
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    guard !followPaused else { return }
                    scrollToLatestMessage(proxy, animated: true, deferred: false)
                }
            }
        }
    }

    private var isStreamingFollowActive: Bool {
        viewModel.isGenerationActive || viewModel.isLoading
    }

    private func scrollToLatestMessage(
        _ proxy: ScrollViewProxy,
        animated: Bool,
        deferred: Bool
    ) {
        guard !viewModel.messages.isEmpty, let lastId = viewModel.messages.last?.id else { return }
        let action = {
            if animated {
                withAnimation {
                    proxy.scrollTo(lastId, anchor: .bottom)
                }
            } else {
                var transaction = Transaction()
                transaction.animation = nil
                withTransaction(transaction) {
                    proxy.scrollTo(lastId, anchor: .bottom)
                }
            }
        }

        if deferred {
            Task { @MainActor in action() }
        } else {
            action()
        }
    }

    // MARK: - Input Bar

    private var inputBar: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let request = viewModel.pendingMemoryApproval {
                MemoryToolApprovalCard(
                    request: request,
                    onApprove: {
                        viewModel.approvePendingMemoryTool()
                    },
                    onDeny: {
                        viewModel.denyPendingMemoryTool()
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let request = viewModel.pendingSearchApproval {
                SearchToolApprovalCard(
                    request: request,
                    onApprove: {
                        viewModel.approvePendingSearchTool()
                    },
                    onDeny: {
                        viewModel.denyPendingSearchTool()
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let request = viewModel.pendingWebMountApproval {
                WebMountToolApprovalCard(
                    request: request,
                    onApprove: {
                        viewModel.approvePendingWebMountTool()
                    },
                    onDeny: {
                        viewModel.denyPendingWebMountTool()
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let request = viewModel.pendingWorkspaceApproval {
                WorkspaceToolApprovalCard(
                    request: request,
                    onApprove: {
                        viewModel.approvePendingWorkspaceTool()
                    },
                    onDeny: {
                        viewModel.denyPendingWorkspaceTool()
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let request = viewModel.pendingMcpApproval {
                McpToolApprovalCard(
                    request: request,
                    onApprove: {
                        viewModel.approvePendingMcpTool()
                    },
                    onDeny: {
                        viewModel.denyPendingMcpTool()
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let preview = viewModel.pendingSelectedFilePreview {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 8) {
                        Label(preview.fileName, systemImage: "doc.text")
                            .font(.caption)
                            .lineLimit(1)
                        Text(preview.isTruncated ? "\(preview.byteSummary) · 已截断" : preview.byteSummary)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button {
                            viewModel.clearPendingSelectedFilePreview()
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                        }
                        .buttonStyle(.plain)
                    }
                    Text("发送后，已解析文本会保存进此会话上下文。")
                        .font(.caption2)
                        .foregroundStyle(AmberTheme.muted)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }

            if let error = viewModel.selectedFileContextError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }

            if let error = viewModel.configurationError, configurationIssue != nil {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentAmber)
                    .lineLimit(3)
            }

            VStack(spacing: 8) {
                    HStack(alignment: .center, spacing: 8) {
                        Button {
                            if documentStore != nil {
                                isImportingSelectedFile = true
                            } else {
                                Task {
                                    await viewModel.attachSelectedFilePreviewToNextMessage()
                                }
                            }
                        } label: {
                            Image(systemName: viewModel.isAttachingSelectedFile ? "paperclip.circle.fill" : "plus")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(AmberTheme.muted)
                                .frame(width: 28, height: 28)
                                .contentShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .disabled(
                            viewModel.isLoading ||
                                viewModel.isAttachingSelectedFile ||
                                hasPendingToolApproval
                        )

                        TextField(inputPlaceholder, text: $viewModel.inputText, axis: .vertical)
                            .lineLimit(1...5)
                            .textFieldStyle(.plain)
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                            .frame(minHeight: 38)
                            .focused($isInputFocused)
                            .onSubmit {
                                if sharedSettings.displaySetting.sendOnEnter {
                                    guard sendEnabled else { return }
                                    followPaused = false
                                    viewModel.sendMessage()
                                }
                            }
                            .disabled(hasPendingToolApproval || configurationIssue != nil)
                            .onChange(of: viewModel.inputText) { _, newText in
                                let threshold = Int(sharedSettings.displaySetting.pasteLongTextThreshold)
                                if sharedSettings.displaySetting.pasteLongTextAsFile,
                                   newText.count > threshold,
                                   !pasteHintShown {
                                    pasteHintShown = true
                                }
                            }

                        if viewModel.isLoading {
                            ComposerIconButton(
                                systemImage: "stop.fill",
                                accessibilityLabel: "停止生成",
                                size: 32,
                                symbolSize: 14,
                                tint: AmberTheme.accentRed,
                                prominent: true
                            ) {
                                viewModel.cancelGeneration()
                            }
                        } else {
                            ComposerIconButton(
                                systemImage: "arrow.up",
                                accessibilityLabel: "发送消息",
                                size: 32,
                                symbolSize: 15,
                                tint: sendEnabled ? AmberTheme.accent : AmberTheme.muted2,
                                prominent: sendEnabled
                            ) {
                                followPaused = false
                                viewModel.sendMessage()
                            }
                            .disabled(!sendEnabled)
                        }
                    }
                    .padding(.leading, 8)
                    .padding(.trailing, 6)
                    .padding(.vertical, 6)
                    .composerStableSurface(cornerRadius: 25)

                    if showsComposerMeta {
                        HStack {
                            Button {
                                activeComposerPanel = nil
                                isModelSheetPresented = true
                            } label: {
                                Text(composerModelLabel)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(AmberTheme.foreground2)
                                    .lineLimit(1)
                                    .padding(.horizontal, 12)
                                    .frame(height: 30)
                            }
                            .buttonStyle(.plain)
                            .composerStableSurface(cornerRadius: 15)
                            .accessibilityLabel("切换模型，当前 \(composerModelLabel)")

                            Spacer()

                            HStack(spacing: 8) {
                                ComposerIconButton(
                                    systemImage: "brain.head.profile",
                                    accessibilityLabel: "设置思考等级",
                                    size: 34,
                                    symbolSize: 14
                                ) {
                                    toggleComposerPanel(.thinking)
                                }
                                .accessibilityValue(reasoningAccessibilityValue)
                                .popover(isPresented: popoverBinding(for: .thinking), arrowEdge: .bottom) {
                                    ComposerThinkingPanel(
                                        selectedOption: selectedReasoningBinding,
                                        isAvailable: viewModel.currentModelSupportsReasoning
                                    ) { _ in activeComposerPanel = nil }
                                    .presentationCompactAdaptation(.popover)
                                }

                                ContextRingButton(snapshot: viewModel.contextSnapshot) {
                                    toggleComposerPanel(.context)
                                }
                                .popover(isPresented: popoverBinding(for: .context), arrowEdge: .bottom) {
                                    ComposerContextPanel(snapshot: viewModel.contextSnapshot)
                                        .presentationCompactAdaptation(.popover)
                                }
                            }
                        }
                        .padding(.horizontal, 2)
                        .padding(.top, 2)
                        .transition(.move(edge: .top).combined(with: .opacity))
                    }
            }
        }
        .padding(.horizontal, ChatLayout.contentHorizontalInset)
        .padding(.top, 8)
        .padding(.bottom, 8)
        // Step 2 baseline: the previous StreamingBottomBoundaryGlass was attached here as a
        // `.background` of the whole inputBar, i.e. *behind* the `.thinMaterial` composer pills,
        // which double-frosted and washed out the controls. Removed to restore a crisp baseline.
        // The bottom Liquid Glass boundary is reintroduced in step 4 as a sibling layer that sits
        // BELOW the composer (between scroll content and controls), never behind the controls.
        .animation(.spring(response: 0.26, dampingFraction: 0.86), value: showsComposerMeta)
    }

    private var sendEnabled: Bool {
        _ = sharedSettings.revision
        return !viewModel.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !viewModel.isGenerationActive &&
            !viewModel.isAttachingSelectedFile &&
            !hasPendingToolApproval &&
            configurationIssue == nil
    }

    private var configurationIssue: ChatConfigurationIssue? {
        _ = sharedSettings.revision
        return viewModel.configurationIssue
    }

    private var inputPlaceholder: String {
        switch configurationIssue {
        case .missingAPIKey:
            "先添加 API Key"
        case .invalidBaseURL:
            "先修正服务商地址"
        case .missingModel:
            "先选择模型"
        case .missingProvider:
            "先配置服务商"
        case .unsupportedProvider:
            "先切换服务商"
        case nil:
            "发消息给 Amber..."
        }
    }

    private var hasPendingToolApproval: Bool {
        viewModel.pendingMemoryApproval != nil ||
            viewModel.pendingSearchApproval != nil ||
            viewModel.pendingWebMountApproval != nil ||
            viewModel.pendingWorkspaceApproval != nil ||
            viewModel.pendingMcpApproval != nil
    }

    private var showsComposerMeta: Bool {
        isInputFocused ||
            activeComposerPanel != nil ||
            isModelSheetPresented
    }

    private var composerModelLabel: String {
        composerCurrentModelID.isEmpty ? "未选择模型" : composerCurrentModelID
    }

    private var composerCurrentModelID: String {
        viewModel.contextSnapshot.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var composerCurrentModelSelection: String {
        _ = sharedSettings.revision
        return sharedSettings.snapshot.getCurrentChatModel()?.id.description() ?? composerCurrentModelID
    }

    private var selectedReasoningOption: ComposerReasoningOption {
        ComposerReasoningOption(reasoningLevel: viewModel.reasoningLevel)
    }

    private var selectedReasoningBinding: Binding<ComposerReasoningOption> {
        Binding(
            get: { selectedReasoningOption },
            set: { viewModel.reasoningLevel = $0.reasoningLevel }
        )
    }

    private var reasoningAccessibilityValue: String {
        viewModel.currentModelSupportsReasoning ? selectedReasoningOption.title : "当前模型未标记 Reasoning"
    }

    private func toggleComposerPanel(_ panel: ComposerPanel) {
        activeComposerPanel = activeComposerPanel == panel ? nil : panel
    }

    private func openPrimaryConfigurationAction() {
        switch configurationIssue {
        case .missingModel:
            openModelDefaults()
        case .missingAPIKey, .invalidBaseURL, .missingProvider, .unsupportedProvider, nil:
            router.navigate(to: .providers)
        }
    }

    private func openModelDefaults() {
        router.navigate(to: .modelDefaults)
    }

    @discardableResult
    private func repairCurrentChatModelIfNeeded() -> Bool {
        sharedSettings.repairCurrentChatModelIfNeeded(settingsStore)
    }

    private func popoverBinding(for panel: ComposerPanel) -> Binding<Bool> {
        Binding(
            get: { activeComposerPanel == panel },
            set: { isPresented in
                if isPresented {
                    activeComposerPanel = panel
                } else if activeComposerPanel == panel {
                    activeComposerPanel = nil
                }
            }
        )
    }
}

private extension View {
    func composerStableSurface(cornerRadius: CGFloat) -> some View {
        background(.thinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .stroke(AmberTheme.border.opacity(0.42), lineWidth: 0.5)
            }
            .shadow(color: .black.opacity(0.05), radius: 10, y: 3)
    }
}

private struct StreamingBottomBoundaryGlass: View {
    var body: some View {
        Group {
            if #available(iOS 26.0, *) {
                Rectangle()
                    .fill(AmberTheme.background.opacity(0.32))
                    .glassEffect(.regular.tint(AmberTheme.background.opacity(0.32)), in: .rect(cornerRadius: 0))
            } else {
                Rectangle()
                    .fill(.ultraThinMaterial)
                    .background(AmberTheme.background.opacity(0.32))
            }
        }
        .mask {
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .black.opacity(0.78), location: 0.28),
                    .init(color: .black, location: 1)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
    }
}

private struct ComposerIconButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var size: CGFloat = 34
    var symbolSize: CGFloat = 15
    var tint: Color = AmberTheme.foreground2
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: symbolSize, weight: .semibold))
                .foregroundStyle(prominent ? Color.white : tint)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .background(prominent ? tint : AmberTheme.surface.opacity(0.72), in: Circle())
        .overlay {
            Circle()
                .stroke(AmberTheme.border.opacity(0.42), lineWidth: 0.5)
        }
        .shadow(color: .black.opacity(0.05), radius: 10, y: 3)
        .accessibilityLabel(accessibilityLabel)
    }
}

private struct ChatToolbarIconButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var size: CGFloat
    var symbolSize: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: symbolSize, weight: .semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .background {
            circleGlass
        }
        .accessibilityLabel(accessibilityLabel)
    }

    @ViewBuilder
    private var circleGlass: some View {
        if #available(iOS 26.0, *) {
            Circle()
                .fill(AmberTheme.glass.opacity(0.16))
                .glassEffect(.regular.interactive(), in: Circle())
        } else {
            Circle()
                .fill(.ultraThinMaterial)
                .overlay {
                    Circle()
                        .stroke(AmberTheme.border.opacity(0.28), lineWidth: 0.5)
                }
        }
    }
}

private struct ContextRingButton: View {
    let snapshot: ChatContextSnapshot
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Circle()
                .stroke(AmberTheme.surface2, lineWidth: 4)
                .frame(width: 16, height: 16)
            .frame(width: 34, height: 34)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .composerStableSurface(cornerRadius: 17)
        .accessibilityLabel("上下文统计")
        .accessibilityValue("\(snapshot.messageCount) 条消息，\(snapshot.totalTokens) tokens")
    }
}

private struct ComposerModelSheet: View {
    @Environment(\.dismiss) private var dismiss

    let sharedSettings: IOSSharedSettingsStore
    let currentModel: String
    let onPick: (ComposerModelOption) -> Void

    @State private var expandedProviderIDs: Set<String>

    private var providers: [ComposerProviderGroup] {
        _ = sharedSettings.revision
        return ComposerProviderGroup.currentConfiguration(sharedSettings: sharedSettings, currentModel: currentModel)
    }

    init(sharedSettings: IOSSharedSettingsStore, currentModel: String, onPick: @escaping (ComposerModelOption) -> Void) {
        self.sharedSettings = sharedSettings
        self.currentModel = currentModel
        self.onPick = onPick
        let selectedProviderID = Self.selectedProviderID(
            for: currentModel,
            providers: ComposerProviderGroup.currentConfiguration(sharedSettings: sharedSettings, currentModel: currentModel)
        )
        self._expandedProviderIDs = State(initialValue: Set([selectedProviderID]))
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(AmberTheme.border)
                .frame(width: 36, height: 5)
                .padding(.top, 10)
                .padding(.bottom, 6)

            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("选择模型")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text("选择当前配置要使用的 Model ID")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }

                Spacer()

                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .frame(width: 34, height: 34)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .amberGlass(cornerRadius: 17)
                .accessibilityLabel("关闭模型选择")
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 14)

            Divider()
                .overlay(AmberTheme.borderSoft)

            ScrollView {
                if providers.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "cpu")
                            .font(.system(size: 24, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)
                        Text("还没有可用模型")
                            .font(.headline)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("请先在服务商详情自动获取或手动添加模型。")
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 38)
                    .padding(.horizontal, 16)
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(providers.enumerated()), id: \.element.id) { index, provider in
                            ComposerProviderGroupView(
                                provider: provider,
                                currentModel: currentModel,
                                isExpanded: expandedProviderIDs.contains(provider.id),
                                onToggle: {
                                    toggleProvider(provider.id)
                                },
                                onPick: { model in
                                    onPick(model)
                                }
                            )

                            if index < providers.count - 1 {
                                Divider()
                                    .overlay(AmberTheme.borderSoft)
                            }
                        }
                    }
                    .background(AmberTheme.background.opacity(0.72))
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 14)
                    .padding(.bottom, 28)
                }
            }
            .scrollIndicators(.hidden)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(AmberTheme.background)
        .onAppear {
            expandedProviderIDs = Set([Self.selectedProviderID(for: currentModel, providers: providers)])
        }
    }

    private func toggleProvider(_ id: String) {
        withAnimation(.easeInOut(duration: 0.2)) {
            if expandedProviderIDs.contains(id) {
                expandedProviderIDs.remove(id)
            } else {
                expandedProviderIDs.insert(id)
            }
        }
    }

    private static func selectedProviderID(for currentModel: String, providers: [ComposerProviderGroup]) -> String {
        providers.first { provider in
            provider.models.contains { $0.matches(currentModel) }
        }?.id ?? providers.first?.id ?? "current"
    }
}

private struct ComposerProviderGroupView: View {
    let provider: ComposerProviderGroup
    let currentModel: String
    let isExpanded: Bool
    let onToggle: () -> Void
    let onPick: (ComposerModelOption) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Button(action: onToggle) {
                HStack(spacing: 10) {
                    Text(provider.name)
                        .font(.subheadline.weight(providerContainsSelection ? .semibold : .regular))
                        .foregroundStyle(providerContainsSelection ? AmberTheme.accent : AmberTheme.foreground)

                    Spacer()

                    Image(systemName: "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(AmberTheme.muted)
                        .rotationEffect(.degrees(isExpanded ? 180 : 0))
                }
                .padding(.horizontal, 16)
                .frame(height: 50)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(provider.name) 模型分组")
            .accessibilityValue(isExpanded ? "已展开" : "已收起")

            if isExpanded {
                VStack(spacing: 0) {
                    ForEach(Array(provider.models.enumerated()), id: \.element.id) { index, model in
                        if index > 0 {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 36)
                        }

                        ComposerModelRow(
                            model: model,
                            isSelected: model.matches(currentModel)
                        ) {
                            onPick(model)
                        }
                    }
                }
                .padding(.bottom, 6)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private var providerContainsSelection: Bool {
        provider.models.contains { $0.matches(currentModel) }
    }
}

private struct ComposerModelRow: View {
    let model: ComposerModelOption
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(model.name)
                    .font(.subheadline.weight(isSelected ? .semibold : .regular))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if let context = model.context {
                    Text(context)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AmberTheme.muted)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(AmberTheme.surface2.opacity(0.72), in: Capsule())
                }

                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 18)
                    .opacity(isSelected ? 1 : 0)
            }
            .padding(.leading, 36)
            .padding(.trailing, 16)
            .frame(minHeight: 46)
            .background(isSelected ? AmberTheme.accentTint : Color.clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("选择模型 \(model.name)")
        .accessibilityValue(isSelected ? "已选" : "未选")
    }
}

private struct ComposerProviderGroup: Identifiable {
    let id: String
    let name: String
    let models: [ComposerModelOption]

    static func currentConfiguration(sharedSettings: IOSSharedSettingsStore, currentModel: String) -> [ComposerProviderGroup] {
        sharedSettings.snapshot.providers.compactMap { provider in
            guard provider.enabled, ChatProviderConfiguration.supportsChatStreaming(provider) else { return nil }
            let models = provider.models
                .filter { $0.type == ModelType.chat }
                .map { model in
                    ComposerModelOption(
                        id: model.id.description(),
                        name: displayName(for: model),
                        modelId: model.modelId,
                        context: contextLabel(for: model)
                    )
                }
            guard !models.isEmpty else { return nil }
            return ComposerProviderGroup(id: provider.id.description(), name: provider.name, models: models)
        }
    }

    private static func displayName(for model: Model) -> String {
        let name = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? model.modelId : name
    }

    private static func contextLabel(for model: Model) -> String? {
        guard let tokens = model.contextWindowTokens else { return nil }
        return "\(Int(truncating: tokens)) ctx"
    }
}

private struct ComposerModelOption: Identifiable, Hashable {
    let id: String
    let name: String
    let modelId: String
    let context: String?

    func matches(_ value: String) -> Bool {
        let normalizedValue = Self.normalize(value)
        return Self.normalize(id) == normalizedValue ||
            Self.normalize(name) == normalizedValue ||
            Self.normalize(modelId) == normalizedValue
    }

    private static func normalize(_ value: String) -> String {
        value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }
}

private enum ComposerReasoningOption: String, CaseIterable, Identifiable {
    case off
    case auto
    case low
    case medium
    case high
    case xhigh
    case max

    var id: String { rawValue }

    var title: String {
        switch self {
        case .off: "关闭"
        case .auto: "Auto"
        case .low: "Low"
        case .medium: "Medium"
        case .high: "High"
        case .xhigh: "X High"
        case .max: "Max"
        }
    }

    var reasoningLevel: ReasoningLevel {
        switch self {
        case .off: .off
        case .auto: .auto_
        case .low: .low
        case .medium: .medium
        case .high: .high
        case .xhigh: .xhigh
        case .max: .max
        }
    }

    init(reasoningLevel: ReasoningLevel) {
        switch reasoningLevel.name.lowercased() {
        case "auto": self = .auto
        case "low": self = .low
        case "medium": self = .medium
        case "high": self = .high
        case "xhigh": self = .xhigh
        case "max": self = .max
        default: self = .off
        }
    }
}

private struct ComposerThinkingPanel: View {
    @Binding var selectedOption: ComposerReasoningOption
    let isAvailable: Bool
    let onPick: (ComposerReasoningOption) -> Void

    var body: some View {
        ComposerPopoverSurface(width: 180) {
            if isAvailable {
                VStack(spacing: 0) {
                    ForEach(Array(ComposerReasoningOption.allCases.enumerated()), id: \.element.id) { index, option in
                        ComposerPopoverDivider(index: index)

                        Button {
                            selectedOption = option
                            onPick(option)
                        } label: {
                            HStack {
                                Text(option.title)
                                    .font(.subheadline.weight(option == selectedOption ? .semibold : .regular))
                                    .foregroundStyle(option == selectedOption ? AmberTheme.accent : AmberTheme.foreground)

                                Spacer()

                                if option == selectedOption {
                                    Image(systemName: "checkmark")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundStyle(AmberTheme.accent)
                                }
                            }
                            .padding(.horizontal, 16)
                            .frame(height: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Reasoning 未启用")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text("当前模型未标记支持 Reasoning")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(14)
            }
        }
    }
}

private struct ComposerContextPanel: View {
    let snapshot: ChatContextSnapshot

    var body: some View {
        ComposerPopoverSurface(width: 248) {
            HStack(spacing: 14) {
                VStack {
                    ZStack {
                        Circle()
                            .stroke(AmberTheme.surface2, lineWidth: 8)
                        Circle()
                            // [Slice 5] 用量环按已用/上限比例填充（上限 8K 作视觉参考，
                            // 非硬上限）。0 token 时环为空（诚实）。
                            .trim(from: 0, to: min(CGFloat(snapshot.totalTokens) / 8_000.0, 1.0))
                            .stroke(AmberTheme.accent, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                    }
                    .frame(width: 52, height: 52)
                }
                .frame(width: 68)

                VStack(spacing: 8) {
                    ComposerContextCompactStatRow(label: "总消息数", value: "\(snapshot.messageCount)")
                    ComposerContextCompactStatRow(label: "总 token", value: "\(snapshot.totalTokens)")
                    ComposerContextCompactStatRow(label: "速度", value: speedText)
                    ComposerContextCompactStatRow(label: "缓存命中率", value: cacheHitRateText)
                }
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 18)
        }
    }

    private var cacheHitRateText: String {
        guard snapshot.promptTokens > 0 else {
            return "0%"
        }
        let rate = Double(snapshot.cachedTokens) / Double(snapshot.promptTokens)
        return "\(Int((rate * 100).rounded()))%"
    }

    private var speedText: String {
        guard let tokensPerSecond = snapshot.tokensPerSecond else {
            return "暂无"
        }
        return String(format: "%.1f token/s", tokensPerSecond)
    }
}

private struct ComposerContextCompactStatRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)

            Spacer(minLength: 10)

            Text(value)
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
    }
}

private struct ComposerPopoverHeader: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)

            Text(subtitle)
                .font(.caption2)
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

private struct ComposerPopoverDivider: View {
    let index: Int

    var body: some View {
        if index > 0 {
            Divider()
                .overlay(AmberTheme.borderSoft)
                .padding(.leading, 44)
        }
    }
}

private struct ComposerPopoverSurface<Content: View>: View {
    let width: CGFloat
    let content: Content

    init(width: CGFloat, @ViewBuilder content: () -> Content) {
        self.width = width
        self.content = content()
    }

    var body: some View {
        content
            .frame(width: width)
            .amberGlass(cornerRadius: 14, interactive: false)
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(AmberTheme.border.opacity(0.75), lineWidth: 0.5)
            }
            .shadow(color: .black.opacity(0.12), radius: 22, y: 5)
    }
}

enum ChatLayout {
    static let contentHorizontalInset: CGFloat = 22
    static let userMaxWidth: CGFloat = 300
    static let streamingBoundaryFadeHeight: CGFloat = 220
    static let streamingBoundaryBottomOffset: CGFloat = 54
    static let followBottomGap: CGFloat = 96
    static let bottomStickThreshold: CGFloat = 40
}

struct ChatAssistantStack<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ChatAgentName: View {
    @AppStorage(IOSDisplayPreferenceKeys.agentName) private var agentName = true

    var body: some View {
        if agentName {
            Text("Amber")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
        }
    }
}

struct ChatMetaLine: View {
    let text: String
    var alignment: Alignment = .leading

    var body: some View {
        Text(text)
            .font(.caption2.monospacedDigit())
            .foregroundStyle(AmberTheme.muted2)
            .frame(maxWidth: .infinity, alignment: alignment)
    }
}

struct ChatUserBubble: View {
    let text: String
    @AppStorage(IOSDisplayPreferenceKeys.fontScale) private var fontScale = 1.0
    @AppStorage(IOSDisplayPreferenceKeys.chatFont) private var chatFont = IOSChatFont.default.rawValue

    private var boundedScale: Double {
        min(max(fontScale, 0.88), 1.25)
    }

    private var selectedFont: IOSChatFont {
        IOSChatFont(rawValue: chatFont) ?? .default
    }

    var body: some View {
        Text(text)
            .font(.system(size: 17 * boundedScale, design: selectedFont.design))
            .foregroundStyle(.white)
            .lineSpacing(3 * boundedScale)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(
                AmberTheme.accent,
                in: UnevenRoundedRectangle(
                    topLeadingRadius: 18,
                    bottomLeadingRadius: 18,
                    bottomTrailingRadius: 6,
                    topTrailingRadius: 18,
                    style: .continuous
                )
            )
            .frame(maxWidth: ChatLayout.userMaxWidth, alignment: .trailing)
    }
}

struct ChatAssistantText<Content: View>: View {
    let content: Content
    @AppStorage(IOSDisplayPreferenceKeys.fontScale) private var fontScale = 1.0
    @AppStorage(IOSDisplayPreferenceKeys.chatFont) private var chatFont = IOSChatFont.default.rawValue

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    private var boundedScale: Double {
        min(max(fontScale, 0.88), 1.25)
    }

    private var selectedFont: IOSChatFont {
        IOSChatFont(rawValue: chatFont) ?? .default
    }

    var body: some View {
        content
            .font(.system(size: 17 * boundedScale, design: selectedFont.design))
            .foregroundStyle(AmberTheme.foreground)
            .lineSpacing(4 * boundedScale)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ChatReasoningCard: View {
    let title: String
    let bodyText: String
    var autoCloseThinking: Bool = true
    @State private var isExpanded: Bool

    init(title: String, bodyText: String, autoCloseThinking: Bool = true) {
        self.title = title
        self.bodyText = bodyText
        self.autoCloseThinking = autoCloseThinking
        // When autoCloseThinking is true (default), reasoning starts collapsed.
        // When false, reasoning starts expanded so user sees it immediately.
        self._isExpanded = State(initialValue: !autoCloseThinking)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.22)) {
                    isExpanded.toggle()
                }
            } label: {
                HStack {
                    Text(title)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(AmberTheme.muted)

                    Spacer()

                    Image(systemName: "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(AmberTheme.foreground)
                        .rotationEffect(.degrees(isExpanded ? 180 : -90))
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isExpanded {
                Text(bodyText)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineSpacing(3)
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                    .padding(.bottom, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(alignment: .top) {
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                    }
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
    }
}

enum ChatToolStepState {
    case done
    case active
    case failed

    var iconName: String {
        switch self {
        case .done:
            "checkmark"
        case .active:
            "circle.fill"
        case .failed:
            "exclamationmark"
        }
    }

    var iconSize: CGFloat {
        switch self {
        case .done, .failed:
            11
        case .active:
            7
        }
    }

    var color: Color {
        switch self {
        case .done:
            AmberTheme.accentGreen
        case .active:
            AmberTheme.accent
        case .failed:
            AmberTheme.accentRed
        }
    }

    var rowFill: Color {
        switch self {
        case .done:
            AmberTheme.surface
        case .active:
            AmberTheme.background
        case .failed:
            AmberTheme.background
        }
    }

    var iconFill: Color {
        switch self {
        case .done:
            AmberTheme.accentGreen.opacity(0.10)
        case .active:
            AmberTheme.accentTint
        case .failed:
            AmberTheme.accentRed.opacity(0.10)
        }
    }

    var stroke: Color {
        switch self {
        case .done:
            AmberTheme.borderSoft
        case .active:
            AmberTheme.accent.opacity(0.72)
        case .failed:
            AmberTheme.accentRed.opacity(0.72)
        }
    }
}

struct ChatToolStepModel: Identifiable {
    let id = UUID()
    let systemImage: String
    let title: String
    let detail: String?
    let state: ChatToolStepState

    init(systemImage: String, title: String, detail: String? = nil, state: ChatToolStepState) {
        self.systemImage = systemImage
        self.title = title
        self.detail = detail
        self.state = state
    }

    init(tool: UIMessagePart.Tool) {
        if tool.toolName == "search_web" {
            let query = Self.searchQuery(from: tool.input)
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "magnifyingglass",
                title: executed ? "搜索完成" : "正在搜索",
                detail: executed ? Self.searchResultSummary(from: tool.output) : query.map { "关键词：\($0)" },
                state: executed ? .done : .active
            )
            return
        }

        if tool.toolName == "generate_image" {
            let prompt = Self.imagePrompt(from: tool.input)
            let imageCount = tool.output.compactMap { $0 as? UIMessagePart.Image }.count
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "photo.on.rectangle",
                title: executed ? "图片已生成" : "正在生成图片",
                detail: executed ? "\(max(imageCount, 1)) 张图片" : prompt.map { "提示词：\($0)" },
                state: executed ? .done : .active
            )
            return
        }

        if tool.toolName.hasPrefix("wm_") {
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "globe.badge.chevron.backward",
                title: executed ? Self.webMountCompletedTitle(for: tool) : Self.webMountPendingTitle(for: tool.toolName),
                detail: executed ? Self.webMountResultSummary(from: tool.output) : Self.webMountInputSummary(from: tool.input),
                state: executed ? .done : .active
            )
            return
        }

        if IOSWorkspaceToolCatalog.supportedToolNames.contains(tool.toolName) {
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "folder",
                title: executed ? Self.workspaceCompletedTitle(for: tool.toolName) : Self.workspacePendingTitle(for: tool.toolName),
                detail: executed ? Self.workspaceResultSummary(from: tool.output) : Self.workspaceInputSummary(from: tool.input),
                state: executed ? .done : .active
            )
            return
        }

        let title = tool.toolName.isEmpty ? "工具调用" : tool.toolName
        self.init(
            systemImage: Self.icon(for: title),
            title: title,
            detail: tool.input.isEmpty ? nil : tool.input,
            state: tool.output.isEmpty ? .active : .done
        )
    }

    private static func searchQuery(from input: String) -> String? {
        let trimmedInput = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { return nil }
        if let data = trimmedInput.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let query = object["query"] as? String {
            let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmedQuery.isEmpty ? nil : trimmedQuery
        }
        return trimmedInput
    }

    private static func imagePrompt(from input: String) -> String? {
        let trimmedInput = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { return nil }
        if let data = trimmedInput.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let prompt = object["prompt"] as? String {
            let trimmedPrompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmedPrompt.isEmpty ? nil : String(trimmedPrompt.prefix(120))
        }
        return String(trimmedInput.prefix(120))
    }

    private static func searchResultSummary(from output: [UIMessagePart]) -> String? {
        let text = output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined(separator: "\n")
        guard !text.isEmpty else { return "已返回搜索结果" }
        let firstLine = text.split(separator: "\n", omittingEmptySubsequences: true).first.map(String.init)
        return firstLine ?? "已返回搜索结果"
    }

    private static func webMountPendingTitle(for toolName: String) -> String {
        switch toolName {
        case "wm_open": "准备打开网页"
        case "wm_tab_list": "准备读取网页标签页"
        case "wm_tab_new": "准备新建网页标签页"
        case "wm_tab_close": "准备关闭网页标签页"
        case "wm_observe": "准备观察网页"
        case "wm_extract": "准备提取网页"
        case "wm_get": "准备读取网页节点"
        case "wm_visual_snapshot": "准备读取视觉快照"
        case "wm_screenshot": "准备截取网页视口"
        case "wm_state": "准备读取网页状态"
        case "wm_back": "准备后退"
        case "wm_forward": "准备前进"
        case "wm_clear_session": "准备清理 WebMount Session"
        case "wm_site_add": "准备添加 WebMount 站点"
        case "wm_site_remove": "准备移除 WebMount 站点"
        case "wm_stations": "准备读取 WebMount 站点"
        default: toolName
        }
    }

    private static func webMountCompletedTitle(for tool: UIMessagePart.Tool) -> String {
        switch tool.toolName {
        case "wm_open": "网页已打开"
        case "wm_tab_list": "网页标签页已读取"
        case "wm_tab_new": "网页标签页已新建"
        case "wm_tab_close": "网页标签页已关闭"
        case "wm_observe": "网页观察已完成"
        case "wm_extract": "网页内容已提取"
        case "wm_get": "网页节点已读取"
        case "wm_visual_snapshot": "视觉快照已读取"
        case "wm_screenshot": "网页视口截图已保存"
        case "wm_state": "网页状态已读取"
        case "wm_back": "WebMount 已后退"
        case "wm_forward": "WebMount 已前进"
        case "wm_clear_session": "WebMount Session 已处理"
        case "wm_site_add": "WebMount 站点已添加"
        case "wm_site_remove": "WebMount 站点已移除"
        case "wm_stations": "WebMount 站点已读取"
        default: tool.toolName
        }
    }

    private static func webMountInputSummary(from input: String) -> String? {
        let trimmedInput = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { return nil }
        if let data = trimmedInput.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let redacted = IOSWebMountRedactor.redactedJSONObject(object)
            let json = IOSWebMountController.json(redacted)
            return String(json.prefix(160))
        }
        return String(IOSWebMountRedactor.redactedText(trimmedInput).prefix(160))
    }

    private static func workspacePendingTitle(for toolName: String) -> String {
        switch toolName {
        case "workspace_file_read": "准备读取 Workspace 文件"
        case "workspace_file_write": "准备写入 Workspace 文件"
        case "workspace_artifact_read": "准备读取 Artifact"
        case "workspace_artifact_delete": "准备删除 Artifact"
        default: toolName
        }
    }

    private static func workspaceCompletedTitle(for toolName: String) -> String {
        switch toolName {
        case "workspace_file_read": "Workspace 文件已读取"
        case "workspace_file_write": "Workspace 文件已写入"
        case "workspace_artifact_read": "Artifact 已读取"
        case "workspace_artifact_delete": "Artifact 已删除"
        default: toolName
        }
    }

    private static func workspaceInputSummary(from input: String) -> String? {
        let trimmedInput = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { return nil }
        if let data = trimmedInput.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            return String(text.prefix(160))
        }
        return String(trimmedInput.prefix(160))
    }

    private static func workspaceResultSummary(from output: [UIMessagePart]) -> String? {
        let text = output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined(separator: "\n")
        guard !text.isEmpty else { return "已返回 Workspace 结果" }
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return String(text.prefix(160))
        }
        if object["denied"] as? Bool == true {
            return "已拒绝：\((object["reason"] as? String) ?? "Workspace 权限限制")"
        }
        if let path = object["path"] as? String {
            return path
        }
        if let title = object["title"] as? String {
            return title
        }
        if let error = object["error"] as? String {
            return "失败：\(error)"
        }
        return "已返回 Workspace 结果"
    }

    private static func webMountResultSummary(from output: [UIMessagePart]) -> String? {
        let text = output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined(separator: "\n")
        guard !text.isEmpty else { return "已返回 WebMount 结果" }
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return String(IOSWebMountRedactor.redactedText(text).prefix(160))
        }
        if object["denied"] as? Bool == true {
            return "已拒绝：\((object["reason"] as? String) ?? "WebMount 权限限制")"
        }
        if object["unsupported"] as? Bool == true {
            return "iOS 暂不支持：\((object["tool"] as? String) ?? "WebMount 工具")"
        }
        if let status = object["status"] as? String {
            let url = object["url"] as? String
            return [status, url].compactMap { $0?.nilIfBlank }.joined(separator: " · ")
        }
        if let artifact = object["artifact"] as? [String: Any],
           let artifactId = artifact["artifact_id"] as? String {
            let size = artifact["size_bytes"].map { "\($0) bytes" }
            return [artifactId, size].compactMap { $0?.nilIfBlank }.joined(separator: " · ")
        }
        if let closed = object["closed_session_id"] as? String {
            return "已关闭 \(closed)"
        }
        if let count = object["count"] as? Int {
            if object["sessions"] != nil {
                return "\(count) 个网页会话"
            }
            return "\(count) 个站点"
        }
        if let sessionId = object["session_id"] as? String {
            return "会话：\(sessionId)"
        }
        if let siteId = object["site_id"] as? String {
            return "站点：\(siteId)"
        }
        return "已返回 WebMount 结果"
    }

    private static func icon(for title: String) -> String {
        let lowercased = title.lowercased()

        if lowercased.contains("search") || title.contains("搜索") {
            return "magnifyingglass"
        }
        if lowercased.contains("read") || lowercased.contains("file") || title.contains("读取") {
            return "doc.text"
        }
        if lowercased.contains("code") || lowercased.contains("swift") || title.contains("生成") {
            return "chevron.left.forwardslash.chevron.right"
        }
        return "wrench.and.screwdriver"
    }
}

struct ChatToolTimeline: View {
    let steps: [ChatToolStepModel]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(steps) { step in
                HStack(spacing: 8) {
                    Image(systemName: step.systemImage)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(step.state.color)
                        .frame(width: 20, height: 20)
                        .background(step.state.iconFill, in: RoundedRectangle(cornerRadius: 5, style: .continuous))

                    VStack(alignment: .leading, spacing: 2) {
                        Text(step.title)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.foreground2)
                            .lineLimit(1)

                        if let detail = step.detail, !detail.isEmpty {
                            Text(detail)
                                .font(.caption2)
                                .foregroundStyle(AmberTheme.muted)
                                .lineLimit(2)
                        }
                    }

                    Spacer(minLength: 8)

                    Image(systemName: step.state.iconName)
                        .font(.system(size: step.state.iconSize, weight: .bold))
                        .foregroundStyle(step.state.color)
                        .frame(width: 16, height: 16)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    step.state.rowFill,
                    in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous)
                        .stroke(step.state.stroke, lineWidth: 0.5)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 2)
    }
}

private struct ChatEmptyState: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "sparkles")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(AmberTheme.accent)

            Text("Amber")
                .font(.title3.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)

            Text("准备好了")
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 96)
        .padding(.bottom, 180)
    }
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

private struct OpenDesignChatSample: View {
    var body: some View {
        VStack(spacing: 14) {
            SampleUserTurn(
                text: "Thinking 的部分默认是折叠的,然后可以点击小三角展开",
                time: "09:38"
            )
            SampleAssistantTurn()
            SampleUserTurn(text: "让 UI 具有高级感，但不要把所有东西都变成玻璃。", time: "09:41")
            SampleAssistantBubble(
                text: [
                    "确实如此。Liquid Glass 材质仅适用于临时性的系统界面：如输入框辅助栏、",
                    "Sheet 弹窗以及工具栏组合。消息文本、代码块、",
                    "设置表单和列表应保持完全不透明，",
                    "以确保最高的阅读和交互可读性。"
                ].joined(),
                time: "09:41"
            )
        }
    }
}

private struct SampleUserTurn: View {
    let text: String
    let time: String

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            ChatUserBubble(text: text)
            ChatMetaLine(text: time, alignment: .trailing)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}

private struct SampleAssistantBubble: View {
    let text: String
    let time: String

    var body: some View {
        ChatAssistantStack {
            ChatAgentName()
            ChatAssistantText {
                Text(text)
            }
            ChatMetaLine(text: time)
        }
    }
}

private struct SampleAssistantTurn: View {
    var body: some View {
        ChatAssistantStack {
            ChatAgentName()
            ToolTimelineSample()
            ChatReasoningCard(
                title: "思考了 3.0 秒 · auto",
                bodyText: "我正在整理界面状态、消息记录和工具结果，确保这次回复能继续当前上下文。"
            )
            ChatAssistantText {
                Text("已经实现了。代码里 `mutableStateOf(false)` 就是默认折叠，点击箭头展开。")
            }
            ChatMetaLine(text: "09:40")
        }
    }
}

private struct ToolTimelineSample: View {
    private let steps: [ChatToolStepModel] = [
        .init(systemImage: "magnifyingglass", title: "搜索 iOS 设计规范", state: .done),
        .init(systemImage: "doc.text", title: "读取 DESIGN_SYSTEM.md", state: .done),
        .init(systemImage: "chevron.left.forwardslash.chevron.right", title: "生成 SwiftUI 代码", state: .active)
    ]

    var body: some View {
        ChatToolTimeline(steps: steps)
    }
}
