import SwiftUI
import Shared
import UIKit
import UniformTypeIdentifiers
import PhotosUI

enum ChatTopBarLayout {
    static let controlsHeight: CGFloat = 54
    static let toolbarButtonDiameter: CGFloat = 38
}

private enum ComposerPanel: String, Identifiable {
    case thinking
    case context

    var id: String { rawValue }
}

private struct ChatListSummarySnapshot: Equatable {
    var hasMessages = false
    var awaitingFirstAssistantChunk = false
    var lastAssistantHasOpenReasoning = false
    var firstUserTitleSeed: String?
    var activeToolStep: ChatToolStepModel?
    var failedToolStep: ChatToolStepModel?

    // 手写 == 是有意的:只比较顶部活动岛实际渲染的字段。activeToolStep 的 tool 载荷
    // (流式 output)被刻意排除,否则子代理流式输出的每个 chunk 都会刷新 summary;
    // id 稳定不随 chunk 变化,纳入它以修正「视觉相同工具替换」时的 terminalHold 匹配。
    // failedToolStep 只用于岛的失败终态匹配,比较其稳定 id 即可。
    // 若 ChatToolStepModel 新增会影响岛屿渲染的字段,必须同步加进这里的比较。
    static func == (lhs: ChatListSummarySnapshot, rhs: ChatListSummarySnapshot) -> Bool {
        lhs.hasMessages == rhs.hasMessages &&
            lhs.awaitingFirstAssistantChunk == rhs.awaitingFirstAssistantChunk &&
            lhs.lastAssistantHasOpenReasoning == rhs.lastAssistantHasOpenReasoning &&
            lhs.firstUserTitleSeed == rhs.firstUserTitleSeed &&
            lhs.activeToolStep?.id == rhs.activeToolStep?.id &&
            lhs.activeToolStep?.title == rhs.activeToolStep?.title &&
            lhs.activeToolStep?.detail == rhs.activeToolStep?.detail &&
            lhs.activeToolStep?.state == rhs.activeToolStep?.state &&
            lhs.activeToolStep?.systemImage == rhs.activeToolStep?.systemImage &&
            lhs.failedToolStep?.id == rhs.failedToolStep?.id
    }
}

private struct ChatMessageEditSheet: View {
    let messageId: String
    let onSubmit: (String, String) -> Void
    let onCancel: () -> Void

    @State private var text: String

    init(
        draft: ChatMessageEditDraft,
        onSubmit: @escaping (String, String) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.messageId = draft.messageId
        self.onSubmit = onSubmit
        self.onCancel = onCancel
        self._text = State(initialValue: draft.text)
    }

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .font(.body)
                .padding(8)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .padding(16)
                .navigationTitle("编辑消息")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消", action: onCancel)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("发送") {
                            onSubmit(messageId, text)
                        }
                        .disabled(trimmedText.isEmpty)
                    }
                }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(false)
        .presentationBackground(.regularMaterial)
        .presentationCornerRadius(30)
        .presentationContentInteraction(.resizes)
        .scrollDismissesKeyboard(.interactively)
    }

    private var trimmedText: String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private struct ChatMessageEditDraft: Identifiable {
    let messageId: String
    var text: String

    var id: String { messageId }
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
    @State private var fileImporterConversationId: String?
    @State private var photoPickerConversationId: String?
    @State private var isInputFocused = false
    @Environment(RouterPath.self) private var router
    @AppStorage(IOSDisplayPreferenceKeys.followGeneration) private var followGeneration = true
    @State private var viewportState = ChatViewportState()
    @State private var scrollToBottomTrigger = 0
    @State private var scrollToBottomSource: NativeTimelineBottomIntentSource = .button
    @State private var islandPresentation: ChatIslandPresentation?
    @State private var islandHoldToken = 0
    @State private var composerInputHeight: CGFloat = 40
    @State private var composerBarHeight: CGFloat = 0
    @State private var composerInputController = ComposerInputController()
    @State private var chatListSummary = ChatListSummarySnapshot()
    @State private var messageEditDraft: ChatMessageEditDraft?
    @State private var pendingDeleteMessageId: String?
    @Environment(IOSConversationStore.self) private var conversationStore
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore = IOSSharedSettingsStore(),
        localToolExecutor: IOSLocalToolExecutor? = nil,
        documentStore: DocumentAccessStore? = nil,
        workspaceStore: IOSWorkspaceStore = .shared,
        viewModel: ChatViewModel? = nil
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.documentStore = documentStore
        self.workspaceStore = workspaceStore
        let resolvedViewModel = viewModel ?? ChatViewModel(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            localToolExecutor: localToolExecutor
        )
        self._viewModel = State(
            initialValue: resolvedViewModel
        )
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()
            messageList

            // 视觉浮层,不参与 bottom safe-area inset 布局。否则按钮显隐会改变
            // ScrollView 的可视区域,和系统顶部/底部 rubber-band 回弹互相拉扯。
            if viewportState.showScrollToBottom && chatListSummary.hasMessages {
                VStack {
                    Spacer()
                    ChatScrollToBottomButton {
                        scrollToBottomSource = .button
                        scrollToBottomTrigger &+= 1
                    }
                    .padding(.bottom, max(10, composerBarHeight + 10))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.scale(scale: 0.6).combined(with: .opacity))
                .zIndex(10)
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
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(item: $messageEditDraft) { draft in
            ChatMessageEditSheet(draft: draft) { messageId, newText in
                viewModel.editMessage(messageId: messageId, newText: newText)
                messageEditDraft = nil
            } onCancel: {
                messageEditDraft = nil
            }
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
        .alert(item: userVisibleErrorBinding) { error in
            Alert(
                title: Text(error.title),
                message: Text(error.message),
                dismissButton: .default(Text("好")) { conversationStore.clearUserVisibleError() }
            )
        }
        .confirmationDialog(
            "删除这条消息？",
            isPresented: Binding(
                get: { pendingDeleteMessageId != nil },
                set: { if !$0 { pendingDeleteMessageId = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                if let id = pendingDeleteMessageId {
                    viewModel.deleteMessage(messageId: id)
                }
                pendingDeleteMessageId = nil
            }
            Button("取消", role: .cancel) { pendingDeleteMessageId = nil }
        } message: {
            Text("删除后不可恢复。")
        }
        .onAppear(perform: handleChatAppear)
        // 仅观察 store 的「切会话」修订号——它只在真正切到另一个会话时 +1，
        // 不受同会话落盘（生成中 tool start/result/complete）影响。
        // 这样落盘不再触发重灌历史 + 重建 ScrollView，消除抖动和「上滑看历史被甩回锚点」。
        // 消息内容同步由 generation 链路的 setMessages 负责，不靠这里。
        .onChange(of: conversationStore.conversationSwitchedRevision) { _, _ in
            handleConversationSwitch()
        }
        .onChange(of: conversationStore.backgroundContentRevision) { _, _ in
            handleBackgroundContentLanded()
        }
        .onReceive(NotificationCenter.default.publisher(for: .amberChatBackgroundJobDidTerminate)) { notification in
            guard let event = notification.object as? IOSChatBackgroundJobTerminalEvent else { return }
            handleBackgroundJobTerminated(event)
        }
        .onChange(of: viewModel.messageUpdateSignal) { _, signal in
            handleMessageUpdateSignal(signal)
        }
        .onChange(of: scenePhase) { _, phase in
            handleScenePhaseChange(phase)
        }
        .onChange(of: isInputFocused) { wasFocused, isFocused in
            guard !wasFocused, isFocused else { return }
            handleComposerFocusStarted()
        }
        .onChange(of: sharedSettings.revision) { _, _ in
            handleSharedSettingsRevisionChange()
        }
    }

    private func handleChatAppear() {
        // 绑定 store（@Environment 在 init 里不可用，故在 onAppear 注入）。
        viewModel.conversationStore = conversationStore
        viewportState = ChatViewportState()
        if !viewModel.isForegroundGenerationActiveForCurrentConversation {
            viewModel.reloadFromStore(reason: .initialLoad)
        }
        refreshChatListSummary(resetTitleSeed: true)
        repairCurrentChatModelIfNeeded()
        if let handoff = IOSWebMountContentHandoffStore.shared.consumeChatHandoff() {
            viewModel.inputText = handoff.chatPrompt
            viewModel.selectedFileContextError = nil
        }
    }

    private func handleConversationSwitch() {
        if viewModel.isForegroundGenerationActiveForCurrentConversation {
            refreshChatListSummary(resetTitleSeed: true)
            return
        }
        viewModel.reloadFromStore(reason: .conversationSwitch)
        refreshChatListSummary(resetTitleSeed: true)
    }

    private func handleBackgroundJobTerminated(_ event: IOSChatBackgroundJobTerminalEvent) {
        guard let currentConversationId = currentConversationIdString,
              event.conversationId == currentConversationId else { return }
        handleBackgroundContentLanded(forceReload: true)
    }

    /// 后台生成/工具回填落盘后的定向上屏:三重门控,绝不打扰进行中的前台流式,
    /// 也绝不因别的会话的后台完成而重灌当前会话。
    private func handleBackgroundContentLanded(forceReload: Bool = false) {
        guard let currentId = conversationStore.currentConversation?.id else { return }
        let idString = String(describing: currentId)
        let hasPendingContent = conversationStore.pendingBackgroundContentConversationIds.contains(idString)
        guard hasPendingContent || forceReload else { return }
        // 前台生成中不动 messages,也**不消费**——收尾事件会带着未消费的 pending 再进来。
        guard !viewModel.isGenerationActive, !viewModel.isLoading else { return }
        if hasPendingContent {
            conversationStore.consumeBackgroundContentNotification(for: idString)
        }
        // .branchChange:语义=消息树被外部改写;affectsViewport=false 不发滚动命令;
        // 且会清 contentHashCache——后台工具回填正是「同 id 原地变更」路径,必须清。
        viewModel.reloadFromStore(reason: .branchChange)
        refreshChatListSummary(resetTitleSeed: false)
    }

    private func handleMessageUpdateSignal(_ signal: ChatMessageUpdateSignal) {
        refreshChatListSummary(
            resetTitleSeed: signal.event == .conversationLoaded ||
                signal.event == .conversationSwitched ||
                signal.event == .branchChanged
        )
        // 后台内容若在本轮前台生成期间落盘,门控当时跳过且未消费;收尾时补查上屏。
        switch signal.event {
        case .generationCompleted, .generationFailed, .generationCancelled:
            handleBackgroundContentLanded()
        default:
            break
        }
    }

    private func handleScenePhaseChange(_ phase: ScenePhase) {
        if phase == .background {
            // 握着后台执行权就别交接，让正在跑的流自己跑完。
            _ = viewModel.handoffGenerationToBackgroundIfNeeded(honorKeepAliveLease: true)
        }
    }

    private func handleComposerFocusStarted() {
        // Composer focus is a direct navigation intent: the user is preparing to type
        // at the tail. Do not depend on ChatListSummary here; during first session
        // entry that summary can lag behind the message list by one render pass.
        scrollToBottomSource = .composerFocus
        scrollToBottomTrigger &+= 1
    }

    private func handleSharedSettingsRevisionChange() {
        repairCurrentChatModelIfNeeded()
        viewModel.bumpMessageRevision(reason: .settingsRefresh)
    }

    private var userVisibleErrorBinding: Binding<IOSUserVisibleError?> {
        Binding(
            get: { conversationStore.lastUserVisibleError },
            set: { newValue in
                if newValue == nil {
                    conversationStore.clearUserVisibleError()
                }
            }
        )
    }

    private func handleSelectedFileImport(_ result: Result<[URL], Error>) {
        let selectionConversationId = fileImporterConversationId
        fileImporterConversationId = nil
        guard selectionConversationId == currentConversationIdString else { return }
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
                guard selectionConversationId == currentConversationIdString else { return }
                await viewModel.attachSelectedFilePreviewToNextMessage(
                    expectedConversationId: selectionConversationId
                )
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
        let selectionConversationId = currentConversationIdString
        if documentStore != nil {
            fileImporterConversationId = selectionConversationId
            isImportingSelectedFile = true
        } else {
            Task {
                await viewModel.attachSelectedFilePreviewToNextMessage(
                    expectedConversationId: selectionConversationId
                )
            }
        }
    }

    private func presentPhotosPicker() {
        photoPickerConversationId = currentConversationIdString
        isPhotoPickerPresented = true
    }

    private func presentCamera() {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            viewModel.selectedFileContextError = "此设备不支持相机。"
            return
        }
        isCameraPresented = true
    }

    private func handlePhotoPickerSelection(_ items: [PhotosPickerItem]) {
        let selectionConversationId = photoPickerConversationId
        photoPickerConversationId = nil
        guard !items.isEmpty else { return }
        Task {
            var failedImageCount = 0
            for item in items {
                let encoded: (dataUrl: String, previewData: Data)?
                do {
                    if let data = try await item.loadTransferable(type: Data.self),
                       let image = UIImage(data: data) {
                        encoded = ChatImageEncoder.encode(image)
                    } else {
                        encoded = nil
                    }
                } catch {
                    encoded = nil
                }
                guard selectionConversationId == currentConversationIdString else {
                    photoPickerItems = []
                    return
                }
                guard let encoded else {
                    failedImageCount += 1
                    continue
                }
                viewModel.addPendingImage(dataUrl: encoded.dataUrl, previewData: encoded.previewData)
            }
            photoPickerItems = []
            guard selectionConversationId == currentConversationIdString else { return }
            if failedImageCount > 0 {
                viewModel.selectedFileContextError = "有 \(failedImageCount) 张图片处理失败。"
            }
        }
    }

    private var currentConversationIdString: String? {
        viewModel.currentConversationId.map { String(describing: $0) }
    }

    /// Camera path (already on the main thread): compress + encode and attach.
    private func attachPickedImage(_ image: UIImage) {
        guard let encoded = ChatImageEncoder.encode(image) else {
            viewModel.selectedFileContextError = "图片处理失败。"
            return
        }
        viewModel.addPendingImage(dataUrl: encoded.dataUrl, previewData: encoded.previewData)
    }

    private var attachmentGlassPanel: some View {
        ComposerAttachmentGlassPanel(
            onCamera: presentCamera,
            onPhotos: presentPhotosPicker,
            onFiles: presentFileImporter,
            onDismiss: { isAttachExpanded = false }
        )
    }

    private var chatImageAttachmentStatus: ComposerAttachmentStatus? {
        switch viewModel.imageAttachmentState {
        case .blocked(let message):
            return .warning(message)
        case .fallback:
            return .muted("当前模型不支持图片，将先用视觉模型识别后再发送", systemImage: "wand.and.stars")
        case .ready, .none:
            return nil
        }
    }

    private var topBar: some View {
        ZStack(alignment: .bottom) {
            HStack {
                backToolbarButton

                Spacer()

                newChatToolbarButton
            }

            ChatActivityIslandView(presentation: islandPresentation ?? .idle(topIslandState))
                .padding(.horizontal, 74)
                .frame(maxWidth: .infinity)
                .allowsHitTesting(false)
                .onAppear { syncIslandPresentation() }
                .onChange(of: topIslandState) { _, _ in syncIslandPresentation() }
        }
        .padding(.horizontal, 18)
        .frame(height: ChatTopBarLayout.controlsHeight, alignment: .bottom)
    }

    private var topIslandState: ChatActivityIslandState {
        // 等待用户永远最先：审批/问答暂停时，岛同步停下来等。
        if viewModel.hasPendingUserGate {
            return ChatActivityIslandState.activity(
                kind: .awaitingUser,
                title: "等待确认",
                detail: viewModel.pendingAskUser != nil ? "回答问题" : "工具审批",
                systemImage: "checkmark.circle",
                tint: .amber
            )
        }

        if let step = chatListSummary.activeToolStep {
            return ChatActivityIslandState.activity(
                kind: step.systemImage == "photo.on.rectangle" ? .image : .tool,
                title: compactIslandText(step.title, limit: 18),
                detail: step.detail.map { compactIslandText($0, limit: 20) },
                systemImage: step.systemImage,
                tint: islandTint(for: step),
                toolID: step.id
            )
        }

        if viewModel.isRecognizingImages {
            return ChatActivityIslandState.activity(
                kind: .image,
                title: "识别图片",
                detail: viewModel.visionRecognitionImageCount > 1
                    ? "共 \(viewModel.visionRecognitionImageCount) 张" : nil,
                systemImage: "viewfinder",
                tint: .cyan
            )
        }

        if chatListSummary.awaitingFirstAssistantChunk {
            return ChatActivityIslandState.activity(
                kind: .waiting,
                title: "正在连接",
                detail: viewModel.islandModelDisplayName,
                systemImage: "sparkles",
                tint: .amber
            )
        }

        if viewModel.isGenerationActive {
            if chatListSummary.lastAssistantHasOpenReasoning {
                return ChatActivityIslandState.activity(
                    kind: .thinking,
                    title: "正在思考",
                    systemImage: "brain.head.profile",
                    tint: .amber
                )
            }
            return ChatActivityIslandState.activity(
                kind: .generating,
                title: "正在生成回复",
                systemImage: "text.bubble",
                tint: .accent
            )
        }

        return .conversationTitle(conversationTitleForIsland)
    }

    private func refreshChatListSummary(resetTitleSeed: Bool = false) {
        let messages = viewModel.messages
        var next = chatListSummary
        next.hasMessages = !messages.isEmpty
        next.awaitingFirstAssistantChunk = isStreamingFollowActive && messages.last?.role == MessageRole.user
        next.activeToolStep = activeToolStepForIsland(messages: messages)
        next.failedToolStep = failedToolStepForIsland(messages: messages)
        next.lastAssistantHasOpenReasoning = lastAssistantHasOpenReasoning(messages: messages)
        if resetTitleSeed || next.firstUserTitleSeed == nil {
            next.firstUserTitleSeed = messages.first(where: { $0.role == MessageRole.user })?
                .toText()
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if next != chatListSummary {
            chatListSummary = next
        }
    }

    private func activeToolStepForIsland(messages: [UIMessage]) -> ChatToolStepModel? {
        for message in messages.suffix(3).reversed() {
            let tools = message.parts.compactMap { $0 as? UIMessagePart.Tool }
            if let active = tools.reversed().first(where: { $0.output.isEmpty }) {
                return ChatToolStepModel(tool: active)
            }
        }
        return nil
    }

    /// 最近一个失败的工具（suffix(3) 窗口内）：输出含失败原因时才构造模型，
    /// 仅供岛在 active→idle 边缘匹配 terminalHold，不参与渲染。
    private func failedToolStepForIsland(messages: [UIMessage]) -> ChatToolStepModel? {
        for message in messages.suffix(3).reversed() {
            let tools = message.parts.compactMap { $0 as? UIMessagePart.Tool }
            if let failed = tools.reversed().first(where: {
                ChatToolOutputFormatter.failureReason(from: $0.output) != nil
            }) {
                return ChatToolStepModel(tool: failed)
            }
        }
        return nil
    }

    /// 活动岛呈现的唯一入口：topIslandState 变化 → presentation（含 settle/terminalHold）。
    private func syncIslandPresentation() {
        let now = ProcessInfo.processInfo.systemUptime
        let previous = islandPresentation ?? .idle(topIslandState)
        let next = ChatIslandPresentationReducer.stateChanged(
            prev: previous,
            next: topIslandState,
            failedToolID: chatListSummary.failedToolStep?.id,
            now: now,
            reduceMotion: reduceMotion
        )
        guard next != previous else { return }
        islandPresentation = next
        guard let deadline = next.holdDeadline else { return }
        islandHoldToken &+= 1
        let token = islandHoldToken
        Task {
            try? await Task.sleep(for: .seconds(max(0, deadline - now)))
            guard !Task.isCancelled, token == islandHoldToken else { return }
            islandPresentation = ChatIslandPresentationReducer.timeout(
                prev: islandPresentation ?? .idle(topIslandState),
                now: ProcessInfo.processInfo.systemUptime
            )
        }
    }

    private func lastAssistantHasOpenReasoning(messages: [UIMessage]) -> Bool {
        guard let last = messages.last, last.role == MessageRole.assistant else { return false }
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
        if let firstUserText = chatListSummary.firstUserTitleSeed,
           !firstUserText.isEmpty {
            return compactIslandText(firstUserText, limit: 14)
        }
        return "Amber"
    }

    private func compactIslandText(_ raw: String, limit: Int) -> String {
        ChatActivityIslandMapping.compactText(raw, limit: limit)
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
        ChatToolbarIconButton(
            systemImage: "chevron.left",
            accessibilityLabel: "返回",
            size: ChatTopBarLayout.toolbarButtonDiameter,
            symbolSize: 18
        ) {
            router.goBack()
        }
    }

    private var newChatToolbarButton: some View {
        ChatToolbarIconButton(
            systemImage: "square.and.pencil",
            accessibilityLabel: "新建对话",
            size: ChatTopBarLayout.toolbarButtonDiameter,
            symbolSize: 16
        ) {
            guard viewModel.prepareForConversationChange() else { return }
            Task { @MainActor in
                // newConversation 会 bump conversationSwitchedRevision,onChange 观察者会
                // 自动触发 reloadFromStore(.conversationSwitch) + 重新落位,无需手动调。
                // (与 PlaceholderViews 的其它切换入口一致。)
                await conversationStore.startNewConversationReusingEmpty()
            }
        }
    }

    // MARK: - Message List

    private var messageList: some View {
        NativeChatTimelineView(
            signal: viewModel.messageUpdateSignal,
            configurationIssue: configurationIssue,
            isGenerationActive: viewModel.isGenerationActive,
            isLoading: viewModel.isLoading,
            isRecognizingImages: viewModel.isRecognizingImages,
            contextCompactState: viewModel.contextCompactState,
            followGeneration: followGeneration,
            displaySetting: sharedSettings.displaySetting,
            generativeUiSetting: sharedSettings.agentRuntime.generativeUi,
            reasoningLevelLabel: composerReasoningLabel,
            workspaceStore: workspaceStore,
            scrollToBottomTrigger: scrollToBottomTrigger,
            scrollToBottomSource: scrollToBottomSource,
            messagesProvider: { viewModel.messages },
            variantInfoProvider: { index in viewModel.variantInfo(atMessageIndex: index) },
            onAction: handleChatListAction,
            onViewportStateChange: applyCollectionViewportState,
            onDismissKeyboard: dismissKeyboard
        )
        .id(NativeChatTimelineSessionIdentity.viewID(conversationId: conversationStore.currentConversation?.id))
    }

    private var isStreamingFollowActive: Bool {
        viewModel.isGenerationActive || viewModel.isLoading
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

            if let request = viewModel.pendingCouncilApproval {
                CouncilToolApprovalCard(
                    request: request,
                    onApprove: {
                        viewModel.approvePendingCouncilTool()
                    },
                    onDeny: {
                        viewModel.denyPendingCouncilTool()
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let request = viewModel.pendingAskUser {
                ChatAskUserCard(
                    request: request,
                    onAnswer: { answer in
                        viewModel.answerPendingAskUser(answer)
                    },
                    onSkip: {
                        viewModel.skipPendingAskUser()
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if !viewModel.pendingImages.isEmpty {
                ComposerPendingImageStrip(
                    items: viewModel.pendingImages.map {
                        .init(id: $0.id, previewData: $0.previewData)
                    },
                    onRemove: { viewModel.removePendingImage($0) },
                    status: chatImageAttachmentStatus
                )
            }

            if let preview = viewModel.pendingSelectedFilePreview {
                ComposerPendingFileCard(
                    fileName: preview.fileName,
                    byteSummary: preview.byteSummary,
                    isTruncated: preview.isTruncated,
                    footnote: "发送后，已解析文本会保存进此会话上下文。",
                    onRemove: { viewModel.clearPendingSelectedFilePreview() }
                )
            }

            if let error = viewModel.selectedFileContextError {
                ComposerAttachmentStatusLabel(status: .error(error))
            }

            if let error = viewModel.configurationError {
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
                            .amberGlass(cornerRadius: 13, interactive: false)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                    }
                    .padding(.horizontal, 2)
                    .padding(.vertical, 3)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            VStack(spacing: 8) {
                    // Apple Music dock 风格:左侧输入胶囊 + 右侧独立圆形发送键,两块分离的原生
                    // Liquid Glass。`.bottom` 对齐让圆形发送键随胶囊向上增高时仍贴住底边。
                    HStack(alignment: .bottom, spacing: 8) {
                        HStack(alignment: .center, spacing: 6) {
                            ComposerAttachToggleButton(
                                isExpanded: isAttachExpanded,
                                isBusy: viewModel.isAttachingSelectedFile,
                                isDisabled: viewModel.isLoading
                                    || viewModel.isAttachingSelectedFile
                                    || hasPendingToolApproval
                            ) {
                                withAnimation(.bouncy(duration: 0.42, extraBounce: 0.14)) {
                                    isAttachExpanded.toggle()
                                }
                            }

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
                        }
                        .padding(.leading, 8)
                        .padding(.trailing, 18)
                        .padding(.vertical, 7)
                        .composerDockGlass(cornerRadius: 27)

                        ComposerDockSendButton(
                            isLoading: isCurrentConversationRunActive || viewModel.isRecognizingImages,
                            sendEnabled: sendEnabled,
                            diameter: 54,
                            onSend: sendComposerMessage,
                            onStop: {
                                if viewModel.isRecognizingImages {
                                    viewModel.cancelVisionRecognition()
                                } else {
                                    viewModel.cancelGeneration()
                                }
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
                            .frame(minHeight: 44)
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
        return viewModel.composerSendBlockReason(for: text) == nil
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
        case .some(.missingAPIKey):
            "先添加 API Key"
        case .some(.invalidBaseURL):
            "先修正服务商地址"
        case .some(.missingModel):
            "先选择模型"
        case .some(.missingProvider):
            "先配置服务商"
        case .some(.unsupportedProvider):
            "先切换服务商"
        case .some(.codexNotSignedIn):
            "先登录 Codex"
        case .some(.grokNotSignedIn):
            "先登录 Grok"
        case .some(.providerDisabled):
            "先启用服务商"
        case .none:
            "发消息给 Amber..."
        }
    }

    private var hasPendingToolApproval: Bool {
        viewModel.pendingMemoryApproval != nil ||
            viewModel.pendingSearchApproval != nil ||
            viewModel.pendingWebMountApproval != nil ||
            viewModel.pendingWorkspaceApproval != nil ||
            viewModel.pendingIshHandoffApproval != nil ||
            viewModel.pendingMcpApproval != nil ||
            viewModel.pendingCouncilApproval != nil ||
            viewModel.pendingAskUser != nil
    }

    private var isCurrentConversationRunActive: Bool {
        viewModel.isGenerationActiveForCurrentConversation ||
            hasPendingToolApproval ||
            viewModel.isLoading
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

    private func applyCollectionViewportState(_ newState: ChatViewportState) {
        viewModel.streamPresentationPacingEnabled =
            !newState.followPaused && !newState.userDragging && !newState.liveRenderingFarFromBottom
        guard viewportState != newState else { return }
        var transaction = Transaction()
        transaction.animation = nil
        withTransaction(transaction) {
            viewportState = newState
        }
    }

    private func handleChatListAction(_ action: ChatListAction) {
        switch action {
        case let .regenerate(messageId):
            viewModel.regenerate(messageId: messageId)
        case let .requestEdit(messageId, currentText):
            messageEditDraft = ChatMessageEditDraft(messageId: messageId, text: currentText)
        case let .edit(messageId, newText):
            viewModel.editMessage(messageId: messageId, newText: newText)
        case let .delete(messageId):
            pendingDeleteMessageId = messageId
        case let .selectVariant(messageId, variantIndex):
            viewModel.selectVariant(messageId: messageId, variantIndex: variantIndex)
        case let .generativeWidget(prompt):
            guard !viewModel.isGenerationActive else { return }
            viewModel.inputText = prompt
            viewportState.followPaused = false
            viewModel.sendMessage()
        case let .modifyGeneratedImage(urlString, prompt, aspectRatio):
            guard !viewModel.isGenerationActive else { return }
            viewportState.followPaused = false
            viewModel.modifyGeneratedImage(
                sourceImageURL: urlString,
                prompt: prompt,
                aspectRatio: aspectRatio
            )
        case .primaryConfiguration:
            openPrimaryConfigurationAction()
        case .modelDefaults:
            openModelDefaults()
        }
    }

    private func openPrimaryConfigurationAction() {
        switch configurationIssue {
        case .some(.missingModel):
            openModelDefaults()
        case .some(.missingAPIKey), .some(.invalidBaseURL), .some(.missingProvider),
             .some(.unsupportedProvider), .some(.codexNotSignedIn), .some(.grokNotSignedIn),
             .some(.providerDisabled), .none:
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
