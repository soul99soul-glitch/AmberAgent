import SwiftUI
import Shared
import UIKit
import UniformTypeIdentifiers
import PhotosUI

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
    @State private var isAttachExpanded = false
    @State private var isCameraPresented = false
    @State private var isPhotoPickerPresented = false
    @State private var photoPickerItems: [PhotosPickerItem] = []
    @State private var isInputFocused = false
    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router
    @AppStorage(IOSDisplayPreferenceKeys.followGeneration) private var followGeneration = true
    @State private var pasteHintShown = false
    @State private var viewportState = ChatViewportState()
    @State private var streamedMessageIDs: Set<String> = []
    @State private var scrollToBottomTrigger = 0
    @State private var composerInputHeight: CGFloat = 40
    @State private var composerBarHeight: CGFloat = 0
    @State private var composerInputController = ComposerInputController()
    @State private var keyboardFollowTask: Task<Void, Never>?
    @Environment(IOSConversationStore.self) private var conversationStore
    @Environment(\.scenePhase) private var scenePhase

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

            // 视觉浮层,不参与 bottom safe-area inset 布局。否则按钮显隐会改变
            // ScrollView 的可视区域,和系统顶部/底部 rubber-band 回弹互相拉扯。
            if viewportState.showScrollToBottom && !viewModel.messages.isEmpty {
                VStack {
                    Spacer()
                    ChatScrollToBottomButton {
                        viewportState.followPaused = false
                        scrollToBottomTrigger &+= 1
                    }
                    .padding(.bottom, 10)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.scale(scale: 0.6).combined(with: .opacity))
                .zIndex(1)
            }

            // Tap-outside scrim for the attachment glass panel.
            if isAttachExpanded {
                Color.black.opacity(0.06)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture {
                        withAnimation(.bouncy(duration: 0.38, extraBounce: 0.1)) {
                            isAttachExpanded = false
                        }
                    }
                    .transition(.opacity)
            }
        }
        .safeAreaBar(edge: .top, spacing: 0) {
            topBar
        }
        // Composer pinned to the bottom safe area via `.safeAreaInset` (NOT `.safeAreaBar`).
        // safeAreaBar added an adaptive Liquid Glass bar, but it caused two problems: (a) it
        // flipped to its dark variant over the terracotta backdrop, and (b) its under-bar
        // scrolling content overlapped the composer, so the expanding model / thinking / context
        // controls were clipped and the keyboard-dismiss tap could resign the field's focus the
        // moment it gained it — hiding those controls. safeAreaInset keeps the composer correctly
        // sized on its own crisp surfaces and does not overlap the scroll content.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            // 不再强制 light:原生 `.glassEffect` 本就按系统外观渲染(深色模式下渲染为深色玻璃),
            // 若把内容强制成 light,前景图标/文字会按浅色调色板解析成深灰,贴在深色玻璃上发暗。
            // 让 composer 跟随真实外观(与顶栏一致),图标与玻璃明暗才匹配。
            inputBar
                .background {
                    GeometryReader { proxy in
                        Color.clear
                            .preference(key: ChatComposerHeightPreferenceKey.self, value: proxy.size.height)
                    }
                }
        }
        .onPreferenceChange(ChatComposerHeightPreferenceKey.self) { height in
            guard abs(composerBarHeight - height) > 0.5 else { return }
            composerBarHeight = height
        }
        .sheet(isPresented: $isModelSheetPresented) {
            ComposerModelSheet(sharedSettings: sharedSettings, currentModel: composerCurrentModelSelection) { model in
                sharedSettings.setCurrentAssistantChatModelId(model.id)
                sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
                viewModel.reasoningLevel = sharedSettings.currentAssistantReasoningLevel()
                viewModel.bumpMessageRevision(reason: .settingsRefresh)
                isModelSheetPresented = false
            }
            .presentationDetents([.fraction(0.72), .large])
            .presentationDragIndicator(.hidden)
        }
        .fileImporter(
            isPresented: $isImportingSelectedFile,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            handleSelectedFileImport(result)
        }
        .photosPicker(
            isPresented: $isPhotoPickerPresented,
            selection: $photoPickerItems,
            maxSelectionCount: ChatViewModel.maxImagesPerMessage,
            matching: .images
        )
        .onChange(of: photoPickerItems) { _, items in
            handlePhotoPickerSelection(items)
        }
        .fullScreenCover(isPresented: $isCameraPresented) {
            CameraPicker { image in
                if let image { attachPickedImage(image) }
                isCameraPresented = false
            }
            .ignoresSafeArea()
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
            viewportState = ChatViewportState()
            streamedMessageIDs.removeAll()
            viewModel.reloadFromStore(reason: .initialLoad)
            repairCurrentChatModelIfNeeded()
            if let handoff = IOSWebMountContentHandoffStore.shared.consumeChatHandoff() {
                viewModel.inputText = handoff.chatPrompt
                viewModel.selectedFileContextError = nil
            }
        }
        // 仅观察 store 的「切会话」修订号——它只在真正切到另一个会话时 +1，
        // 不受同会话落盘（生成中 tool start/result/complete）影响。
        // 这样落盘不再触发重灌历史 + 重建 ScrollView，消除抖动和「上滑看历史被甩回锚点」。
        // 消息内容同步由 generation 链路的 setMessages 负责，不靠这里。
        .onChange(of: conversationStore.conversationSwitchedRevision) { _, _ in
            streamedMessageIDs.removeAll()
            viewModel.reloadFromStore(reason: .conversationSwitch)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background {
                _ = viewModel.handoffGenerationToBackgroundIfNeeded()
            }
        }
        .onChange(of: sharedSettings.revision) { _, _ in
            repairCurrentChatModelIfNeeded()
            viewModel.bumpMessageRevision(reason: .settingsRefresh)
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

    // MARK: - Attachment panel actions

    private func presentFileImporter() {
        if documentStore != nil {
            isImportingSelectedFile = true
        } else {
            Task { await viewModel.attachSelectedFilePreviewToNextMessage() }
        }
    }

    private func presentCamera() {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            viewModel.selectedFileContextError = "此设备不支持相机。"
            return
        }
        isCameraPresented = true
    }

    private func handlePhotoPickerSelection(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty else { return }
        Task {
            for item in items {
                guard let data = try? await item.loadTransferable(type: Data.self),
                      let image = UIImage(data: data),
                      let encoded = ChatImageEncoder.encode(image) else { continue }
                await MainActor.run {
                    viewModel.addPendingImage(dataUrl: encoded.dataUrl, previewData: encoded.previewData)
                }
            }
            await MainActor.run { photoPickerItems = [] }
        }
    }

    /// Camera path (already on the main thread): compress + encode and attach.
    private func attachPickedImage(_ image: UIImage) {
        guard let encoded = ChatImageEncoder.encode(image) else {
            viewModel.selectedFileContextError = "图片处理失败。"
            return
        }
        viewModel.addPendingImage(dataUrl: encoded.dataUrl, previewData: encoded.previewData)
    }

    /// Liquid Glass attachment panel that springs out from the "+" with the native
    /// `.glassEffect` material; it sits just above the input row (composer grows upward).
    private var attachmentGlassPanel: some View {
        VStack(spacing: 0) {
            attachmentRow(title: "拍照", icon: "camera") { presentCamera() }
            attachmentDivider
            attachmentRow(title: "照片", icon: "photo.on.rectangle") { isPhotoPickerPresented = true }
            attachmentDivider
            attachmentRow(title: "文件", icon: "doc") { presentFileImporter() }
        }
        .frame(width: 220)
        .clipShape(.rect(cornerRadius: 22))
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.leading, 2)
        .padding(.bottom, 2)
    }

    private var attachmentDivider: some View {
        Divider().overlay(AmberTheme.borderSoft).padding(.leading, 52)
    }

    private func attachmentRow(title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button {
            withAnimation(.bouncy(duration: 0.36, extraBounce: 0.1)) { isAttachExpanded = false }
            action()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .regular))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 24)
                Text(title)
                    .font(.system(size: 16, weight: .regular))
                    .foregroundStyle(AmberTheme.foreground)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .frame(height: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var topBar: some View {
        ZStack {
            HStack {
                backToolbarButton

                Spacer()

                newChatToolbarButton
            }

            ChatActivityIslandView(state: topIslandState)
                .padding(.horizontal, 74)
                .frame(maxWidth: .infinity)
                .allowsHitTesting(false)
        }
        .padding(.horizontal, 18)
        .frame(height: 54)
    }

    private var topIslandState: ChatActivityIslandState {
        if let step = activeToolStepForIsland {
            return ChatActivityIslandState.activity(
                kind: step.systemImage == "photo.on.rectangle" ? .image : .tool,
                title: compactIslandText(step.title, limit: 18),
                detail: step.detail.map { compactIslandText($0, limit: 22) },
                systemImage: step.systemImage,
                tint: islandTint(for: step)
            )
        }

        if viewModel.isRecognizingImages {
            return ChatActivityIslandState.activity(
                kind: .image,
                title: "识别图片",
                detail: "整理图片上下文",
                systemImage: "viewfinder",
                tint: .cyan
            )
        }

        if isAwaitingFirstAssistantChunk {
            return ChatActivityIslandState.activity(
                kind: .waiting,
                title: "连接模型",
                detail: "等待首个响应",
                systemImage: "sparkles",
                tint: .amber
            )
        }

        if viewModel.isGenerationActive {
            if lastAssistantHasOpenReasoning {
                return ChatActivityIslandState.activity(
                    kind: .thinking,
                    title: "思考中",
                    detail: composerReasoningLabel,
                    systemImage: "brain.head.profile",
                    tint: .amber
                )
            }
            return ChatActivityIslandState.activity(
                kind: .generating,
                title: "生成回复",
                detail: nil,
                systemImage: "text.bubble",
                tint: .accent
            )
        }

        return .conversationTitle(conversationTitleForIsland)
    }

    private var activeToolStepForIsland: ChatToolStepModel? {
        for message in viewModel.messages.suffix(3).reversed() {
            let tools = message.parts.compactMap { $0 as? UIMessagePart.Tool }
            if let active = tools.reversed().first(where: { $0.output.isEmpty }) {
                return ChatToolStepModel(tool: active)
            }
        }
        return nil
    }

    private var lastAssistantHasOpenReasoning: Bool {
        guard let last = viewModel.messages.last, last.role == MessageRole.assistant else { return false }
        return last.parts.contains { part in
            guard let reasoning = part as? UIMessagePart.Reasoning else { return false }
            return reasoning.finishedAt == nil
        }
    }

    private var conversationTitleForIsland: String {
        _ = conversationStore.currentRevision
        let storedTitle = conversationStore.currentConversation?.title
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !storedTitle.isEmpty {
            return compactIslandText(storedTitle, limit: 14)
        }
        if let firstUserText = viewModel.messages.first(where: { $0.role == MessageRole.user })?
            .toText()
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !firstUserText.isEmpty {
            return compactIslandText(firstUserText, limit: 14)
        }
        return "Amber"
    }

    private func compactIslandText(_ raw: String, limit: Int) -> String {
        let compacted = raw
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard compacted.count > limit else { return compacted }
        return String(compacted.prefix(limit))
    }

    private func islandTint(for step: ChatToolStepModel) -> ChatActivityIslandTint {
        switch step.state {
        case .failed:
            return .red
        case .done:
            return .green
        case .active:
            switch step.systemImage {
            case "magnifyingglass", "globe", "globe.badge.chevron.backward":
                return .cyan
            case "photo.on.rectangle":
                return .green
            case "person.2.fill", "person.3.sequence":
                return .indigo
            case "brain.head.profile":
                return .amber
            default:
                return .accent
            }
        }
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
                // newConversation 会 bump conversationSwitchedRevision,onChange 观察者会
                // 自动触发 reloadFromStore(.conversationSwitch) + 重新落位,无需手动调。
                // (与 PlaceholderViews 的其它切换入口一致。)
                await conversationStore.newConversation()
            }
        }
    }

    // MARK: - Message List

    /// iMessage 式上屏:气泡从底部升起 + 渐显 + 从对应一侧的下角轻微放大弹出(用户=右下,
    /// 助手=左下)。仅新追加的用户消息会在 `withAnimation` 中触发此 transition;批量加载/
    /// 切换会话走数组整体赋值,不在动画事务内,故不会逐条动画。移除时仅淡出收缩。
    private func messageEntranceTransition(isUser: Bool) -> AnyTransition {
        .asymmetric(
            insertion: .move(edge: .bottom)
                .combined(with: .opacity)
                .combined(with: .scale(scale: 0.92, anchor: isUser ? .bottomTrailing : .bottomLeading)),
            removal: .opacity.combined(with: .scale(scale: 0.96))
        )
    }

    private var messageRows: [ChatMessageRowModel] {
        ChatMessageProjector.rows(
            messages: viewModel.messages,
            event: viewModel.messageUpdateSignal.event,
            streamedMessageIDs: streamedMessageIDs
        )
    }

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
                        ForEach(messageRows) { row in
                            let index = row.index
                            let message = row.message
                            VStack(spacing: 0) {
                                MessageBubbleView(
                                    message: message,
                                    messageIndex: index,
                                    variantInfo: viewModel.variantInfo(atMessageIndex: index),
                                    displaySetting: sharedSettings.displaySetting,
                                    generativeUiSetting: sharedSettings.agentRuntime.generativeUi,
                                    onRegenerate: { viewModel.regenerate(atMessageIndex: index) },
                                    onEdit: { newText in viewModel.editMessage(atMessageIndex: index, newText: newText) },
                                    onDelete: { viewModel.deleteMessage(atMessageIndex: index) },
                                    onSelectVariant: { variantIndex in viewModel.selectVariant(messageIndex: index, variantIndex: variantIndex) },
                                    onGenerativeWidgetAction: { prompt in
                                        guard !viewModel.isGenerationActive else { return }
                                        viewModel.inputText = prompt
                                        viewportState.followPaused = false
                                        viewModel.sendMessage()
                                    },
                                    onModifyGeneratedImage: { imageURL, prompt, aspectRatio in
                                        guard !viewModel.isGenerationActive else { return }
                                        viewportState.followPaused = false
                                        viewModel.modifyGeneratedImage(
                                            sourceImageURL: imageURL,
                                            prompt: prompt,
                                            aspectRatio: aspectRatio
                                        )
                                    },
                                    isGenerating: row.isLast && viewModel.isGenerationActive,
                                    isLastMessage: row.isLast,
                                    hasEverStreamed: row.hasEverStreamed,
                                    liveMarkdownRenderingEnabled: !shouldDegradeLiveAssistantRendering,
                                    reasoningLevelLabel: composerReasoningLabel
                                )

                            }
                            .id(row.messageId)
                            .transition(row.canAnimateInsertion
                                ? messageEntranceTransition(isUser: true)
                                : .opacity
                            )
                        }

                        if isAwaitingFirstAssistantChunk {
                            ChatAssistantPendingResponseView()
                                .id("assistant-pending-response")
                                .transition(.opacity)
                        }

                        if viewModel.isRecognizingImages {
                            VisionRecognitionIndicator()
                                .id("vision-recognition-indicator")
                                .transition(.opacity.combined(with: .move(edge: .trailing)))
                        }

                        if viewModel.contextCompactState.isVisible {
                            ContextCompactTimelineMarker(state: viewModel.contextCompactState)
                                .id("context-compact-\(String(describing: viewModel.contextCompactState.status))-\(viewModel.contextCompactState.updatedAt.timeIntervalSince1970)")
                                .transition(.opacity.combined(with: .move(edge: .bottom)))
                        }

                        // 内容底部的静止留白兼定位锚:进入会话/回到底部都停在它的底边,
                        // 即「内容底 + 一小段留白」,与手动上推回弹的自然停靠一致,不贴死输入框。
                        Color.clear
                            .frame(height: ChatLayout.bottomRestGap)
                            .id(ChatLayout.bottomAnchorID)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
                .padding(.horizontal, ChatLayout.contentHorizontalInset)
                .padding(.top, 12)
                .animation(.spring(response: 0.35, dampingFraction: 0.82), value: viewModel.isRecognizingImages)
                .animation(.easeOut(duration: 0.22), value: viewModel.contextCompactState)
                // Tap anywhere in the message content to dismiss the keyboard. Attached to the
                // content (not the ScrollView) so it reliably fires; simultaneousGesture so
                // message bubbles/buttons still receive their taps; contentShape makes the gaps
                // between messages tappable too.
                .frame(maxWidth: .infinity, minHeight: 0)
                .contentShape(Rectangle())
                .simultaneousGesture(
                    TapGesture().onEnded {
                        // Only dismiss when the keyboard is already up. This prevents a tap that
                        // is GAINING focus on the input field from immediately resigning it
                        // (which would hide the composer controls).
                        if isInputFocused { dismissKeyboard() }
                    }
                )
            }
            // 一开始滚动就收起键盘(查看历史消息时自动收回)。之前用 .interactively 需要把键盘
            // 往下「拖」才收,普通上滑看历史不触发,所以感觉只有点击能收。Tap-to-dismiss 仍保留
            // 在下方内容(LazyVStack)上,因为 ScrollView 自身的 TapGesture 会被 UIScrollView 吞掉。
            .scrollDismissesKeyboard(.immediately)
            .defaultScrollAnchor(viewModel.messages.isEmpty ? .top : .bottom, for: .initialOffset)
            .onScrollPhaseChange { _, phase in
                switch phase {
                case .interacting:
                    viewportState.userDragging = true
                case .idle:
                    viewportState.userDragging = false
                default:
                    break
                }
            }
            .onScrollGeometryChange(for: ChatScrollGeometryState.self) { geometry in
                let distanceToBottom = max(0, geometry.contentSize.height - geometry.visibleRect.maxY)
                let liveRenderingThreshold = max(
                    ChatLayout.liveRenderingLODMinDistance,
                    geometry.visibleRect.height * ChatLayout.liveRenderingLODScreenFactor
                )
                return ChatScrollGeometryState(
                    atBottom: distanceToBottom <= ChatLayout.bottomStickThreshold,
                    isScrollable: geometry.contentSize.height > geometry.visibleRect.height + ChatLayout.bottomStickThreshold,
                    liveRenderingFarFromBottom: distanceToBottom > liveRenderingThreshold
                )
            } action: { _, state in
                viewportState.isAtBottom = state.atBottom
                viewportState.isContentScrollable = state.isScrollable
                viewportState.liveRenderingFarFromBottom = state.liveRenderingFarFromBottom
                viewportState.followPaused = ChatViewportPolicy.followPausedAfterGeometryChange(
                    wasPaused: viewportState.followPaused,
                    userDragging: viewportState.userDragging,
                    atBottom: state.atBottom
                )
                let autoFollowingGeneration = isStreamingFollowActive && followGeneration && !viewportState.followPaused
                let shouldShow = !state.atBottom && !viewModel.messages.isEmpty && !autoFollowingGeneration
                if shouldShow != viewportState.showScrollToBottom {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.86)) {
                        viewportState.showScrollToBottom = shouldShow
                    }
                }
            }
            .onChange(of: viewModel.messageUpdateSignal) { _, signal in
                rememberStreamedRendererState(for: signal.event)
                let commands = ChatViewportReducer.reduce(
                    event: signal.event,
                    state: &viewportState,
                    environment: ChatViewportEnvironment(
                        followEnabled: followGeneration,
                        generationActive: isStreamingFollowActive
                    )
                )
                commands.forEach { executeScrollCommand($0, proxy: proxy, sourceEvent: signal.event) }
            }
            .onChange(of: viewportState.isContentScrollable) { _, scrollable in
                guard scrollable else { return }
                let command = ChatViewportPolicy.commandForContentBecameScrollable(
                    canAutoFollow: followGeneration && !viewportState.followPaused && !viewportState.userDragging,
                    isStreamingFollowActive: isStreamingFollowActive
                )
                executeScrollCommand(command, proxy: proxy)
            }
            .onChange(of: isInputFocused) { _, focused in
                // 键盘弹起时无论是否 followPaused 都应滚到底部,确保最新消息不被键盘遮挡。
                guard focused, !viewModel.messages.isEmpty else { return }
                scheduleFocusedBottomFollow(proxy: proxy, settleDelay: 0.26)
            }
            .onChange(of: composerBarHeight) { _, _ in
                guard isInputFocused, !viewModel.messages.isEmpty else { return }
                scheduleFocusedBottomFollow(proxy: proxy, settleDelay: 0.12)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { notification in
                guard isInputFocused, !viewModel.messages.isEmpty else { return }
                scheduleFocusedBottomFollow(
                    proxy: proxy,
                    settleDelay: keyboardAnimationDuration(from: notification)
                )
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidChangeFrameNotification)) { _ in
                guard isInputFocused, !viewModel.messages.isEmpty else { return }
                scheduleFocusedBottomFollow(proxy: proxy, settleDelay: 0)
            }
            // 「回到底部」悬浮按钮的触发(按钮在 composer 区,无法直接拿 proxy,用计数器桥接)。
            .onChange(of: scrollToBottomTrigger) { _, _ in
                executeScrollCommand(ChatViewportPolicy.commandForExplicitBottomRequest(), proxy: proxy)
            }
            .onDisappear {
                keyboardFollowTask?.cancel()
                keyboardFollowTask = nil
            }
            .task(id: viewportState.conversationLoadToken) {
                await scrollToBottomAfterConversationLoad(proxy: proxy)
            }
        }
        // 每加载一次会话就换新身份,让 initialOffset 锚点重新「从底部实现」落位
        // (切会话复用同一 ScrollView 时 initialOffset 不会自动重触发)。
        .id(viewportState.conversationLoadToken)
    }

    private var isStreamingFollowActive: Bool {
        viewModel.isGenerationActive || viewModel.isLoading
    }

    private var shouldDegradeLiveAssistantRendering: Bool {
        guard isStreamingFollowActive,
              viewModel.messages.last?.role == MessageRole.assistant else {
            return false
        }
        return viewportState.liveRenderingFarFromBottom
    }

    private var isAwaitingFirstAssistantChunk: Bool {
        isStreamingFollowActive && viewModel.messages.last?.role == MessageRole.user
    }

    private func scrollToLatestMessage(
        _ proxy: ScrollViewProxy,
        animated: Bool,
        deferred: Bool,
        targetBottomAnchor: Bool = false,
        animation: Animation? = nil
    ) {
        guard !viewModel.messages.isEmpty,
              let last = viewModel.messages.last else { return }
        let lastId = ChatMessageProjector.messageId(for: last)
        let scroll = {
            if targetBottomAnchor {
                proxy.scrollTo(ChatLayout.bottomAnchorID, anchor: .bottom)
            } else {
                proxy.scrollTo(lastId, anchor: .bottom)
            }
        }
        let action = {
            if animated {
                if let animation {
                    withAnimation(animation) {
                        scroll()
                    }
                } else {
                    withAnimation {
                        scroll()
                    }
                }
            } else {
                var transaction = Transaction()
                transaction.animation = nil
                withTransaction(transaction) {
                    scroll()
                }
            }
        }

        if deferred {
            Task { @MainActor in action() }
        } else {
            action()
        }
    }

    private func executeScrollCommand(
        _ command: ChatViewportScrollCommand,
        proxy: ScrollViewProxy,
        sourceEvent: ChatEvent? = nil
    ) {
        switch command {
        case .none, .initialAnchor, .showBottomButton, .resetForConversationSwitch:
            return
        case let .followBottom(animated, targetBottomAnchor, deferred):
            scrollToLatestMessage(
                proxy,
                animated: animated,
                deferred: deferred,
                targetBottomAnchor: targetBottomAnchor,
                animation: scrollAnimation(for: sourceEvent)
            )
        }
    }

    private func scrollAnimation(for event: ChatEvent?) -> Animation? {
        switch event {
        case .assistantStreamDelta:
            return .linear(duration: 0.08)
        default:
            return nil
        }
    }

    private func scrollToBottomAfterConversationLoad(proxy: ScrollViewProxy) async {
        let token = viewportState.conversationLoadToken
        guard token > 0 else { return }
        guard !viewModel.messages.isEmpty else { return }
        // After a session switch, the ScrollView gets a fresh identity. Give the
        // new LazyVStack one render pass so the bottom anchor exists before
        // issuing the explicit bottom alignment.
        try? await Task.sleep(nanoseconds: 50_000_000)
        guard !Task.isCancelled,
              viewportState.conversationLoadToken == token,
              !viewModel.messages.isEmpty else { return }
        scrollToLatestMessage(proxy, animated: false, deferred: false, targetBottomAnchor: true)
    }

    private func scheduleFocusedBottomFollow(proxy: ScrollViewProxy, settleDelay: TimeInterval) {
        guard isInputFocused, !viewModel.messages.isEmpty else { return }

        // Let the keyboard/composer move first, then align the transcript once the
        // viewport has settled. This avoids stacking a scroll jump on top of the
        // system keyboard animation.
        keyboardFollowTask?.cancel()
        keyboardFollowTask = Task { @MainActor in
            if settleDelay > 0 {
                try? await Task.sleep(nanoseconds: UInt64(settleDelay * 1_000_000_000))
            } else {
                await Task.yield()
            }
            guard !Task.isCancelled, isInputFocused, !viewModel.messages.isEmpty else { return }
            scrollToLatestMessage(
                proxy,
                animated: true,
                deferred: false,
                targetBottomAnchor: true,
                animation: .easeOut(duration: 0.18)
            )
        }
    }

    private func keyboardAnimationDuration(from notification: Notification) -> TimeInterval {
        guard let value = notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber else {
            return 0.25
        }
        return max(0, value.doubleValue)
    }

    private func rememberStreamedRendererState(for event: ChatEvent) {
        switch event {
        case .conversationLoaded, .conversationSwitched, .branchChanged:
            streamedMessageIDs.removeAll()
            return
        default:
            break
        }

        let currentIDs = Set(viewModel.messages.map { ChatMessageProjector.messageId(for: $0) })
        var retained = streamedMessageIDs
        retained.formIntersection(currentIDs)
        if event.remembersStreamingRenderer,
           let last = viewModel.messages.last,
           last.role == MessageRole.assistant {
            retained.insert(ChatMessageProjector.messageId(for: last))
        }
        streamedMessageIDs = retained
    }

    /// Forcefully dismiss the keyboard. Clears the SwiftUI focus binding and also resigns the
    /// UIKit first responder directly — the latter guarantees dismissal even if the focus
    /// binding alone does not take effect.
    private func dismissKeyboard() {
        isInputFocused = false
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil
        )
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

            if let request = viewModel.pendingIshHandoffApproval {
                IshHandoffToolApprovalCard(
                    request: request,
                    onApprove: {
                        viewModel.approvePendingIshHandoffTool()
                    },
                    onDeny: {
                        viewModel.denyPendingIshHandoffTool()
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

            if !viewModel.pendingImages.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(viewModel.pendingImages) { image in
                            ZStack(alignment: .topTrailing) {
                                if let ui = UIImage(data: image.previewData) {
                                    Image(uiImage: ui)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: 56, height: 56)
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                                Button {
                                    viewModel.removePendingImage(image.id)
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.system(size: 16))
                                        .foregroundStyle(.white, .black.opacity(0.45))
                                        .padding(3)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(.horizontal, 2)
                }
                switch viewModel.imageAttachmentState {
                case .blocked(let message):
                    Label(message, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption2)
                        .foregroundStyle(AmberTheme.accentAmber)
                        .lineLimit(2)
                        .padding(.horizontal, 2)
                case .fallback:
                    Label("当前模型不支持图片，将先用视觉模型识别后再发送", systemImage: "wand.and.stars")
                        .font(.caption2)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                        .padding(.horizontal, 2)
                case .ready, .none:
                    EmptyView()
                }
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

            if isAttachExpanded {
                attachmentGlassPanel
                    .transition(.scale(scale: 0.75, anchor: .bottomLeading).combined(with: .opacity))
            }

            if !viewModel.chatSuggestions.isEmpty, !viewModel.isGenerationActive {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(viewModel.chatSuggestions.prefix(4), id: \.self) { suggestion in
                            Button {
                                viewModel.inputText = suggestion
                                withAnimation(.easeOut(duration: 0.2)) {
                                    viewModel.chatSuggestions = []
                                }
                                isInputFocused = true
                            } label: {
                                Text(suggestion)
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.foreground2)
                                    .lineLimit(1)
                                    .padding(.horizontal, 10)
                                    .frame(height: 26)
                            }
                            .buttonStyle(.plain)
                            .amberGlass(cornerRadius: 13)
                        }
                    }
                    .padding(.horizontal, 2)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            VStack(spacing: 8) {
                    // Apple Music dock 风格:左侧输入胶囊 + 右侧独立圆形发送键,两块分离的原生
                    // Liquid Glass。`.bottom` 对齐让圆形发送键随胶囊向上增高时仍贴住底边。
                    HStack(alignment: .bottom, spacing: 8) {
                        HStack(alignment: .center, spacing: 6) {
                            Button {
                                withAnimation(.bouncy(duration: 0.42, extraBounce: 0.14)) {
                                    isAttachExpanded.toggle()
                                }
                            } label: {
                                Image(systemName: viewModel.isAttachingSelectedFile ? "paperclip.circle.fill" : "plus")
                                    .font(.system(size: 18, weight: .semibold))
                                    .foregroundStyle(AmberTheme.muted)
                                    .frame(width: 32, height: 32)
                                    .contentShape(Circle())
                                    .rotationEffect(.degrees(isAttachExpanded ? 45 : 0))
                                    .contentTransition(.symbolEffect(.replace.downUp))
                            }
                            .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.88, haptic: .lightImpact))
                            .disabled(
                                viewModel.isLoading ||
                                    viewModel.isAttachingSelectedFile ||
                                    hasPendingToolApproval
                            )

                            ZStack(alignment: .leading) {
                                ComposerInputTextView(
                                    text: $viewModel.inputText,
                                    height: $composerInputHeight,
                                    isFocused: inputFocusBinding,
                                    isEnabled: !hasPendingToolApproval,
                                    sendOnEnter: sharedSettings.displaySetting.sendOnEnter,
                                    controller: composerInputController,
                                    onSubmit: sendComposerMessage
                                )
                                .frame(height: composerInputHeight)

                                if viewModel.inputText.isEmpty {
                                    Text(inputPlaceholder)
                                        .font(.body)
                                        .foregroundStyle(AmberTheme.muted2)
                                        .allowsHitTesting(false)
                                }
                            }
                            .frame(minHeight: 40)
                            .onChange(of: viewModel.inputText) { _, newText in
                                let threshold = Int(sharedSettings.displaySetting.pasteLongTextThreshold)
                                if sharedSettings.displaySetting.pasteLongTextAsFile,
                                   newText.count > threshold,
                                   !pasteHintShown {
                                    pasteHintShown = true
                                }
                            }
                        }
                        .padding(.leading, 8)
                        .padding(.trailing, 18)
                        .padding(.vertical, 7)
                        .composerDockGlass(cornerRadius: 27)

                        ComposerDockSendButton(
                            isLoading: viewModel.isLoading,
                            sendEnabled: sendEnabled,
                            diameter: 54,
                            onSend: sendComposerMessage,
                            onStop: {
                                viewModel.cancelGeneration()
                            }
                        )
                    }

                    if showsComposerMeta {
                        HStack {
                            Button {
                                openComposerModelSheet()
                            } label: {
                                Text(composerModelLabel)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(AmberTheme.foreground2)
                                    .lineLimit(1)
                                    .padding(.horizontal, 12)
                                    .frame(height: 30)
                                    .composerDockGlass(cornerRadius: 15)
                            }
                            .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.96, haptic: .selection))
                            .contentShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
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
                                        options: availableReasoningOptions,
                                        isAvailable: reasoningIsAvailable
                                    ) { _ in activeComposerPanel = nil }
                                    .presentationCompactAdaptation(.popover)
                                }

                                ContextRingButton(
                                    snapshot: viewModel.contextSnapshot,
                                    compactState: viewModel.contextCompactState
                                ) {
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
        .animation(.spring(response: 0.26, dampingFraction: 0.86), value: showsComposerMeta)
    }

    private var sendEnabled: Bool {
        sendEnabled(for: viewModel.inputText)
    }

    private func sendEnabled(for text: String) -> Bool {
        _ = sharedSettings.revision
        return !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !viewModel.isGenerationActive &&
            !viewModel.isAttachingSelectedFile &&
            !hasPendingToolApproval &&
            configurationIssue == nil
    }

    private func sendComposerMessage() {
        let committedText = composerInputController.committedText() ?? viewModel.inputText
        if committedText != viewModel.inputText {
            viewModel.inputText = committedText
        }
        guard sendEnabled(for: committedText) else { return }
        viewportState.followPaused = false
        viewModel.sendMessage()
    }

    private func openComposerModelSheet() {
        activeComposerPanel = nil
        if let currentText = composerInputController.currentText(),
           currentText != viewModel.inputText {
            viewModel.inputText = currentText
        }
        isModelSheetPresented = true
        Task { @MainActor in
            await Task.yield()
            dismissKeyboard()
        }
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
        case .codexNotSignedIn:
            "先登录 Codex"
        case nil:
            "发消息给 Amber..."
        }
    }

    private var hasPendingToolApproval: Bool {
        viewModel.pendingMemoryApproval != nil ||
            viewModel.pendingSearchApproval != nil ||
            viewModel.pendingWebMountApproval != nil ||
            viewModel.pendingWorkspaceApproval != nil ||
            viewModel.pendingIshHandoffApproval != nil ||
            viewModel.pendingMcpApproval != nil
    }

    private var showsComposerMeta: Bool {
        isInputFocused ||
            activeComposerPanel != nil ||
            isModelSheetPresented ||
            configurationIssue != nil
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
        _ = sharedSettings.revision
        return ComposerReasoningOption(reasoningLevel: sharedSettings.currentAssistantReasoningLevel())
    }

    private var availableReasoningOptions: [ComposerReasoningOption] {
        _ = sharedSettings.revision
        let options = sharedSettings.currentAssistantReasoningLevels().map(ComposerReasoningOption.init)
        var seen = Set<ComposerReasoningOption>()
        let unique = options.filter { seen.insert($0).inserted }
        return unique.isEmpty ? [.off] : unique
    }

    private var reasoningIsAvailable: Bool {
        availableReasoningOptions.contains { $0 != .off }
    }

    // Reasoning effort shown on the thinking pill (e.g. "Auto"); nil when reasoning is off.
    private var composerReasoningLabel: String? {
        let option = selectedReasoningOption
        guard option != .off else { return nil }
        return option.title
    }

    private var selectedReasoningBinding: Binding<ComposerReasoningOption> {
        Binding(
            get: { selectedReasoningOption },
            set: { option in
                sharedSettings.updateCurrentAssistantReasoningLevel(option.reasoningLevel)
                viewModel.reasoningLevel = sharedSettings.currentAssistantReasoningLevel()
            }
        )
    }

    private var inputFocusBinding: Binding<Bool> {
        Binding(
            get: { isInputFocused },
            set: { isInputFocused = $0 }
        )
    }

    private var reasoningAccessibilityValue: String {
        reasoningIsAvailable ? selectedReasoningOption.title : "当前模型未标记 Reasoning"
    }

    private func toggleComposerPanel(_ panel: ComposerPanel) {
        activeComposerPanel = activeComposerPanel == panel ? nil : panel
    }

    private func openPrimaryConfigurationAction() {
        switch configurationIssue {
        case .missingModel:
            openModelDefaults()
        case .missingAPIKey, .invalidBaseURL, .missingProvider, .unsupportedProvider, .codexNotSignedIn, nil:
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
