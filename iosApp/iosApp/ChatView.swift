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
    @FocusState private var isInputFocused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router
    @AppStorage(IOSDisplayPreferenceKeys.followGeneration) private var followGeneration = true
    @State private var pasteHintShown = false
    @State private var pendingInitialScrollToBottom = false
    @State private var followPaused = false
    @State private var userDragging = false
    @State private var showScrollToBottom = false
    @State private var scrollToBottomTrigger = 0
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
            VStack(spacing: 0) {
                // 看历史(已上滑离开底部)时,在输入框正上方居中浮出「回到底部」玻璃圆按钮。
                if showScrollToBottom && !viewModel.messages.isEmpty {
                    ChatScrollToBottomButton {
                        followPaused = false
                        scrollToBottomTrigger &+= 1
                    }
                    .padding(.bottom, 10)
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
                }
                inputBar
            }
            .animation(.spring(response: 0.32, dampingFraction: 0.86), value: showScrollToBottom)
        }
        .sheet(isPresented: $isModelSheetPresented) {
            ComposerModelSheet(sharedSettings: sharedSettings, currentModel: composerCurrentModelSelection) { model in
                sharedSettings.setCurrentChatModelId(model.id)
                sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
                viewModel.messageRevision &+= 1
                isModelSheetPresented = false
            }
            .presentationDetents([.fraction(0.72), .large])
            .presentationDragIndicator(.visible)
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
        HStack {
            backToolbarButton

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
                                    isGenerating: viewModel.isGenerationActive,
                                    isLastMessage: index == viewModel.messages.count - 1,
                                    reasoningLevelLabel: composerReasoningLabel
                                )

                                if isLastStreamingMessage {
                                    Color.clear
                                        .frame(height: ChatLayout.followBottomGap)
                                        .allowsHitTesting(false)
                                        .accessibilityHidden(true)
                                }
                            }
                            .id(message.id)
                            .transition(messageEntranceTransition(isUser: message.role == MessageRole.user))
                        }

                        if viewModel.isRecognizingImages {
                            VisionRecognitionIndicator()
                                .id("vision-recognition-indicator")
                                .transition(.opacity.combined(with: .move(edge: .trailing)))
                        }
                    }
                }
                .padding(.horizontal, ChatLayout.contentHorizontalInset)
                .padding(.vertical, 12)
                .padding(.bottom, 18)
                .animation(.spring(response: 0.35, dampingFraction: 0.82), value: viewModel.isRecognizingImages)
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
                let shouldShow = !atBottom && !viewModel.messages.isEmpty
                if shouldShow != showScrollToBottom {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.86)) {
                        showScrollToBottom = shouldShow
                    }
                }
            }
            .onChange(of: viewModel.messageRevision) { _, _ in
                if pendingInitialScrollToBottom {
                    pendingInitialScrollToBottom = false
                    scrollToLatestSettled(proxy)
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
            // 「回到底部」悬浮按钮的触发(按钮在 composer 区,无法直接拿 proxy,用计数器桥接)。
            .onChange(of: scrollToBottomTrigger) { _, _ in
                scrollToLatestMessage(proxy, animated: true, deferred: false)
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

    /// Initial-load / conversation-switch scroll. A single deferred `scrollTo` to the last row
    /// can land short on a long conversation, because LazyVStack may not have realized/measured
    /// that row on the first runloop hop. So jump once immediately, then once more after layout
    /// settles. Both jumps are non-animated. This runs only when `pendingInitialScrollToBottom`
    /// was set (open/switch a session) — it is a one-shot, not a follow loop, and never reads
    /// inputBar height.
    private func scrollToLatestSettled(_ proxy: ScrollViewProxy) {
        func jump() {
            guard let lastId = viewModel.messages.last?.id else { return }
            var transaction = Transaction()
            transaction.animation = nil
            withTransaction(transaction) {
                proxy.scrollTo(lastId, anchor: .bottom)
            }
        }
        Task { @MainActor in
            // A long LazyVStack realizes/measures its rows progressively, so one or two
            // jumps can land short of the true bottom. Re-jump across a short settling
            // window until the last row is realized and measured.
            jump()
            for stepMs in [60, 80, 140, 200, 240] {
                try? await Task.sleep(nanoseconds: UInt64(stepMs) * 1_000_000)
                jump()
            }
        }
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
                                .frame(minHeight: 40)
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
                        }
                        .padding(.leading, 8)
                        .padding(.trailing, 18)
                        .padding(.vertical, 7)
                        .composerDockGlass(cornerRadius: 27)

                        ComposerDockSendButton(
                            isLoading: viewModel.isLoading,
                            sendEnabled: sendEnabled,
                            diameter: 54,
                            onSend: {
                                followPaused = false
                                viewModel.sendMessage()
                            },
                            onStop: {
                                viewModel.cancelGeneration()
                            }
                        )
                    }

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
                            .composerDockGlass(cornerRadius: 15)
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

    // Reasoning effort shown on the thinking pill (e.g. "Auto"); nil when reasoning is off.
    private var composerReasoningLabel: String? {
        let option = selectedReasoningOption
        guard option != .off else { return nil }
        return option.title
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
    /// 原生 Liquid Glass 输入胶囊:`.regular` 提供半透折射,`.interactive()` 提供触控时的
    /// HDR 高光/透镜响应。低于 iOS 26 时回退到 `.thinMaterial`。
    @ViewBuilder
    func composerDockGlass(cornerRadius: CGFloat) -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(.regular.interactive(), in: .rect(cornerRadius: cornerRadius))
        } else {
            background(.thinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .stroke(AmberTheme.border.opacity(0.42), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.06), radius: 12, y: 4)
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
        // 与输入条/发送键统一为原生 Liquid Glass:中性按钮用无色调玻璃,prominent 时染 tint。
        .modifier(ComposerDockCircleGlass(tint: prominent ? tint : nil))
        .accessibilityLabel(accessibilityLabel)
    }
}

/// 「回到底部」悬浮玻璃圆键 —— 上滑看历史时浮现在输入框正上方,点击跳回最新消息。
/// 复用 composer 的原生 Liquid Glass 圆形样式,保持视觉统一。
private struct ChatScrollToBottomButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "chevron.down")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: 38, height: 38)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .modifier(ComposerDockCircleGlass(tint: nil))
        .accessibilityLabel("回到最新消息")
    }
}

/// Apple Music dock 风格的独立圆形发送/停止键 —— 与输入胶囊分离的原生 Liquid Glass。
/// 启用时给玻璃染上 accent 色调,触控时由 `.interactive()` 产生 HDR 透镜高光。
private struct ComposerDockSendButton: View {
    var isLoading: Bool
    var sendEnabled: Bool
    var diameter: CGFloat = 54
    let onSend: () -> Void
    let onStop: () -> Void

    private var isActionable: Bool { isLoading || sendEnabled }

    var body: some View {
        Button {
            if isLoading { onStop() } else { onSend() }
        } label: {
            Image(systemName: isLoading ? "stop.fill" : "arrow.up")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(iconColor)
                .frame(width: diameter, height: diameter)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .modifier(ComposerDockCircleGlass(tint: glassTint))
        .disabled(!isActionable)
        .animation(.easeOut(duration: 0.18), value: isLoading)
        .animation(.easeOut(duration: 0.18), value: sendEnabled)
        .accessibilityLabel(isLoading ? "停止生成" : "发送消息")
    }

    private var iconColor: Color {
        if isLoading { return .white }
        // 启用时白色箭头叠在 accent 玻璃上;禁用时用 muted(与左侧「+」同档),
        // 比更淡的 muted2 在深色玻璃上更清晰,不再暗淡。
        return sendEnabled ? .white : AmberTheme.muted
    }

    private var glassTint: Color? {
        if isLoading { return AmberTheme.accentRed }
        return sendEnabled ? AmberTheme.accent : nil
    }
}

private struct ComposerDockCircleGlass: ViewModifier {
    var tint: Color?

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            // 单一 glassEffect 调用 —— 只让可空的 tint 参数在 accent ↔ nil 间变化,保持视图身份
            // 不变。若按 tint 有无拆成两条分支,SwiftUI 会移除/插入两个不同身份的玻璃视图并做
            // 交叉淡入,删字回到清玻璃时会闪过一帧发白。
            content.glassEffect(.regular.tint(tint).interactive(), in: Circle())
        } else {
            content
                .background {
                    Circle().fill(tint.map { AnyShapeStyle($0) } ?? AnyShapeStyle(.thinMaterial))
                }
                .overlay {
                    Circle().stroke(AmberTheme.border.opacity(0.42), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.06), radius: 12, y: 4)
        }
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
            ZStack {
                // 轨道:强调色(用户可调的主题色,不一定是琥珀)的「很浅」版本,由 mix 混白得到。
                // 不能用 accent.opacity(...):半透明强调色会和背后的玻璃混色,深色玻璃会把它压暗,
                // 所以调透明度看着都一样。mix(with:.white) 才是真正把强调色调浅成不透明、背景无关的浅色。
                Circle()
                    .stroke(AmberTheme.accent.mix(with: .white, by: 0.82), lineWidth: 3)
                // 进度:随上下文增长用强调色覆盖填充,呈现增长效果。填充上限按模型真实
                // contextWindow 计算(见 snapshot.contextFillFraction)。
                Circle()
                    .trim(from: 0, to: snapshot.contextFillFraction)
                    .stroke(AmberTheme.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            .frame(width: 18, height: 18)
            .frame(width: 34, height: 34)
            .contentShape(Circle())
            .animation(.easeOut(duration: 0.3), value: snapshot.contextFillFraction)
        }
        .buttonStyle(.plain)
        .modifier(ComposerDockCircleGlass(tint: nil))
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
            HStack(spacing: 8) {
                Text(model.name)
                    .font(.subheadline.weight(isSelected ? .semibold : .regular))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)

                if let context = model.context {
                    Text(context)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AmberTheme.muted)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(AmberTheme.surface2.opacity(0.72), in: Capsule())
                        .layoutPriority(1)
                }

                Spacer(minLength: 8)
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
        return formatContextWindow(Int(truncating: tokens))
    }

    /// 紧凑显示上下文窗口:≥100万写 1M(必要时带一位小数),≥1000 写 XK,否则原数。
    static func formatContextWindow(_ tokens: Int) -> String {
        if tokens >= 1_000_000 {
            return trimmedDecimal(Double(tokens) / 1_000_000) + "M"
        }
        if tokens >= 1_000 {
            return "\(Int((Double(tokens) / 1_000).rounded()))K"
        }
        return "\(tokens)"
    }

    private static func trimmedDecimal(_ value: Double) -> String {
        let rounded = (value * 10).rounded() / 10
        if rounded == rounded.rounded() {
            return "\(Int(rounded))"
        }
        return String(format: "%.1f", rounded)
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
                            // 用量环按已用/上限比例填充。上限按模型真实 contextWindow 计算,
                            // 模型未声明时回退 8K 视觉参考(见 snapshot.contextFillFraction)。
                            // 0 token 时环为空（诚实）。
                            .trim(from: 0, to: snapshot.contextFillFraction)
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
            // 不在这里 cap 宽度:气泡保持内容尺寸,长按 contextMenu 的高亮平台才会贴合气泡而非
            // 撑成 300pt 灰条。宽度上限由各调用方的父容器负责(消息流是 MessageBubbleView 的 VStack)。
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
    let bodyText: String
    var isThinking: Bool = false
    var startedAt: Date? = nil
    var finishedSeconds: Double? = nil
    var levelLabel: String? = nil
    var autoCloseThinking: Bool = true
    @State private var isExpanded: Bool
    @State private var userToggled = false

    init(
        bodyText: String,
        isThinking: Bool = false,
        startedAt: Date? = nil,
        finishedSeconds: Double? = nil,
        levelLabel: String? = nil,
        autoCloseThinking: Bool = true
    ) {
        self.bodyText = bodyText
        self.isThinking = isThinking
        self.startedAt = startedAt
        self.finishedSeconds = finishedSeconds
        self.levelLabel = levelLabel
        self.autoCloseThinking = autoCloseThinking
        // Expanded while streaming so the reasoning is visible as it generates; otherwise follow
        // the autoCloseThinking setting.
        self._isExpanded = State(initialValue: isThinking ? true : !autoCloseThinking)
    }

    private var levelSuffix: String {
        guard let levelLabel, !levelLabel.isEmpty else { return "" }
        return " · \(levelLabel)"
    }

    private func titleText(elapsed: Int?) -> String {
        if isThinking {
            if let elapsed { return "思考中 \(elapsed) 秒\(levelSuffix)" }
            return "思考中\(levelSuffix)"
        }
        if let finishedSeconds { return "思考了 \(Self.formatFinishedSeconds(finishedSeconds)) 秒\(levelSuffix)" }
        return "思考过程\(levelSuffix)"
    }

    /// 不足 1 秒按 0.1 精度显示(最小 0.1,避免「0 秒」/「0.0 秒」);≥1 秒显示整数。
    private static func formatFinishedSeconds(_ seconds: Double) -> String {
        let rounded = (seconds * 10).rounded() / 10
        if rounded >= 1 { return "\(Int(rounded.rounded()))" }
        return String(format: "%.1f", max(0.1, rounded))
    }

    @ViewBuilder
    private var titleLabel: some View {
        if isThinking, let startedAt {
            // Live ticking elapsed counter while the model is thinking.
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text(titleText(elapsed: Int(max(0, context.date.timeIntervalSince(startedAt)))))
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
            }
        } else {
            Text(titleText(elapsed: nil))
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
        }
    }

    // Compact cream pill: amber clock + "思考中 N 秒 · Auto" (live) / "思考了 N 秒 · Auto" (done) +
    // chevron. Expands to a height-capped, auto-scrolling view of the streaming reasoning text.
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                userToggled = true
                withAnimation(.easeInOut(duration: 0.22)) { isExpanded.toggle() }
            } label: {
                HStack(spacing: 7) {
                    Image(systemName: "clock")
                        .font(.system(size: 12.5, weight: .semibold))
                        .foregroundStyle(AmberTheme.accentAmber)

                    titleLabel

                    // Collapsed: hug content (chevron sits right after the title). Expanded: push
                    // the chevron to the right edge, matching the full-width reading area below.
                    if isExpanded { Spacer(minLength: 6) }

                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(AmberTheme.muted)
                        .rotationEffect(.degrees(isExpanded ? 180 : 0))
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isExpanded {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(bodyText)
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .lineSpacing(3)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Color.clear.frame(height: 1).id("reasoning-bottom")
                        }
                        .padding(.horizontal, 12)
                        .padding(.top, 2)
                        .padding(.bottom, 10)
                    }
                    .frame(maxHeight: 220)
                    .onChange(of: bodyText) { _, _ in
                        // Follow the streaming reasoning tail while it generates.
                        if isThinking {
                            withAnimation(.linear(duration: 0.15)) {
                                proxy.scrollTo("reasoning-bottom", anchor: .bottom)
                            }
                        }
                    }
                }
                // Fade in place (no upward move) so the collapsing text doesn't slide up over
                // the header.
                .transition(.opacity)
            }
        }
        .background(
            AmberTheme.surface,
            in: RoundedRectangle(cornerRadius: isExpanded ? AmberTheme.radiusLarge : 17, style: .continuous)
        )
        // Clip to the capsule so the collapsing content can never render outside / through it.
        .clipShape(RoundedRectangle(cornerRadius: isExpanded ? AmberTheme.radiusLarge : 17, style: .continuous))
        .onChange(of: isThinking) { _, nowThinking in
            // Auto-collapse when generation finishes, unless the user opened it themselves.
            if !nowThinking, autoCloseThinking, !userToggled {
                withAnimation(.easeInOut(duration: 0.22)) { isExpanded = false }
            }
        }
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
    let isSubAgent: Bool
    /// Carried for subagent steps so the detail sheet can read the live prompt + streaming output.
    let tool: UIMessagePart.Tool?

    init(
        systemImage: String,
        title: String,
        detail: String? = nil,
        state: ChatToolStepState,
        isSubAgent: Bool = false,
        tool: UIMessagePart.Tool? = nil
    ) {
        self.systemImage = systemImage
        self.title = title
        self.detail = detail
        self.state = state
        self.isSubAgent = isSubAgent
        self.tool = tool
    }

    init(tool: UIMessagePart.Tool) {
        // `.contains` (不是 `==`):流式合并偶发把工具名拼成 "subagent_dispatchsubagent_dispatch",
        // 用包含匹配才不会漏判、掉进裸名回退。
        if tool.toolName.contains("subagent_dispatch") {
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "person.2.fill",
                title: Self.subAgentTitle(from: tool.input),
                detail: Self.subAgentDetail(from: tool.input),
                state: executed ? .done : .active,
                isSubAgent: true,
                tool: tool
            )
            return
        }

        if tool.toolName == "search_web" {
            let query = Self.searchQuery(from: tool.input)
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "magnifyingglass",
                title: Self.combinedLine(executed ? "已搜索" : "正在搜索", query),
                detail: executed ? Self.searchResultSummary(from: tool.output) : query.map { "关键词：\($0)" },
                state: executed ? .done : .active
            )
            return
        }

        if tool.toolName == "scrape_web" {
            let url = Self.scrapeURL(from: tool.input)
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "globe",
                title: Self.combinedLine(executed ? "已读取网页" : "正在读取网页", url),
                detail: executed ? Self.searchResultSummary(from: tool.output) : url.map { "链接：\($0)" },
                state: executed ? .done : .active
            )
            return
        }

        if tool.toolName == "memory_tool" {
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "brain.head.profile",
                title: executed ? "已更新核心记忆" : "正在更新核心记忆",
                detail: nil,
                state: executed ? .done : .active
            )
            return
        }

        if tool.toolName == "mcp_call" {
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "puzzlepiece.extension",
                title: Self.combinedLine(executed ? "已调用 MCP" : "正在调用 MCP", Self.mcpName(from: tool.input)),
                detail: nil,
                state: executed ? .done : .active
            )
            return
        }

        if tool.toolName == "model_council_run" {
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "person.3.sequence",
                title: executed ? "模型议会已完成" : "模型议会进行中",
                detail: nil,
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
                title: Self.combinedLine(executed ? "图片已生成" : "正在生成图片", prompt),
                detail: executed ? "\(max(imageCount, 1)) 张图片" : prompt.map { "提示词：\($0)" },
                state: executed ? .done : .active
            )
            return
        }

        if tool.toolName.hasPrefix("wm_") {
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "globe.badge.chevron.backward",
                title: Self.combinedLine(
                    executed ? Self.webMountCompletedTitle(for: tool) : Self.webMountPendingTitle(for: tool.toolName),
                    Self.webMountInputSummary(from: tool.input)
                ),
                detail: executed ? Self.webMountResultSummary(from: tool.output) : Self.webMountInputSummary(from: tool.input),
                state: executed ? .done : .active
            )
            return
        }

        if IOSWorkspaceToolCatalog.supportedToolNames.contains(tool.toolName) {
            let executed = !tool.output.isEmpty
            self.init(
                systemImage: "folder",
                title: Self.combinedLine(
                    executed ? Self.workspaceCompletedTitle(for: tool.toolName) : Self.workspacePendingTitle(for: tool.toolName),
                    Self.workspaceInputSummary(from: tool.input)
                ),
                detail: executed ? Self.workspaceResultSummary(from: tool.output) : Self.workspaceInputSummary(from: tool.input),
                state: executed ? .done : .active
            )
            return
        }

        let executed = !tool.output.isEmpty
        self.init(
            systemImage: Self.icon(for: tool.toolName),
            title: Self.friendlyToolTitle(tool.toolName, executed: executed),
            detail: tool.input.isEmpty ? nil : tool.input,
            state: executed ? .done : .active
        )
    }

    /// 未单独映射的工具:给一个友好中文标签,不显示裸工具名。状态由胶囊上的对勾/转圈表示,不再加文字。
    private static func friendlyToolTitle(_ name: String, executed: Bool) -> String {
        let known: [String: String] = [
            "file_read_selected": "读取选中文件",
            "permissions_status": "查看权限状态",
            "tools_list": "列出可用工具",
            "subagent_report": "子智能体汇报",
            "read_health": "读取健康数据"
        ]
        if let mapped = known[name] { return mapped }
        if name.hasPrefix("mcp__") { return "MCP " + name.replacingOccurrences(of: "mcp__", with: "") }
        return name.isEmpty ? "工具调用" : "调用 \(name)"
    }

    private static func scrapeURL(from input: String) -> String? {
        guard let args = subAgentArgs(from: input) else {
            let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : String(trimmed.prefix(48))
        }
        for key in ["url", "link", "target"] {
            if let value = args[key] as? String, !value.trimmingCharacters(in: .whitespaces).isEmpty {
                return Self.shortURL(value)
            }
        }
        if let urls = args["urls"] as? [Any], let first = urls.first as? String {
            return Self.shortURL(first)
        }
        return nil
    }

    /// 取域名 + 路径首段,去掉协议与 query,胶囊里更易读。
    private static func shortURL(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let comps = URLComponents(string: trimmed), let host = comps.host {
            let firstPath = comps.path.split(separator: "/").first.map { "/\($0)" } ?? ""
            return host + firstPath
        }
        return String(trimmed.prefix(48))
    }

    private static func mcpName(from input: String) -> String? {
        guard let args = subAgentArgs(from: input) else { return nil }
        for key in ["tool", "tool_name", "name", "server"] {
            if let value = args[key] as? String, !value.trimmingCharacters(in: .whitespaces).isEmpty {
                return value
            }
        }
        return nil
    }

    private static func subAgentArgs(from input: String) -> [String: Any]? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    static func subAgentRole(from input: String) -> String? {
        let args = subAgentArgs(from: input)
        let role = (args?["role_id"] as? String) ?? (args?["subagent_id"] as? String)
        guard let role, !role.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return role
    }

    static func subAgentTask(from input: String) -> String? {
        guard let args = subAgentArgs(from: input) else {
            // 解析失败(含流式未完成的截断 JSON):是 JSON 形态就不回退原始串,避免把 `{"objective"...`
            // 塞进胶囊标题;纯文本任务才原样用。
            let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
            return (trimmed.isEmpty || trimmed.hasPrefix("{") || trimmed.hasPrefix("[")) ? nil : trimmed
        }
        // 顶层字符串键
        for key in ["task", "prompt", "instruction", "objective", "input", "query"] {
            if let value = args[key] as? String,
               !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return value
            }
        }
        // 嵌套 task.objective(Android custom_subagent 的 task 结构)
        if let task = args["task"] as? [String: Any] {
            for key in ["objective", "prompt", "instruction"] {
                if let value = task[key] as? String,
                   !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    return value
                }
            }
        }
        return nil
    }

    private static func subAgentTitle(from input: String) -> String {
        // 胶囊只显示「标签 + 简短目标」,不再把整段 prompt 原样塞进标题。完整目标/输出留给详情 sheet。
        let role = subAgentRole(from: input)
        let label = role.map { "子智能体 @\($0)" } ?? "派发子任务"
        let objective = subAgentTask(from: input).map {
            String($0.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces).prefix(16))
        }
        return combinedLine(label, objective)
    }

    private static func subAgentDetail(from input: String) -> String? {
        guard let task = subAgentTask(from: input) else { return nil }
        return String(task.replacingOccurrences(of: "\n", with: " ").prefix(80))
    }

    /// "<verb> <subject>" on one line; the subject (query / prompt / target / task) is folded in
    /// so the pill reads like the action, not just a status word. Trimmed and length-capped.
    private static func combinedLine(_ verb: String, _ subject: String?) -> String {
        guard let subject else { return verb }
        let oneLine = subject.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
        guard !oneLine.isEmpty else { return verb }
        return "\(verb) \(String(oneLine.prefix(56)))"
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
    /// Tapping a step (used for subagent steps, which open a detail sheet). nil = not tappable.
    var onTapStep: ((ChatToolStepModel) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(steps) { step in
                let tappable = onTapStep != nil
                if tappable {
                    Button { onTapStep?(step) } label: { row(step, chevron: true) }
                        .buttonStyle(.plain)
                } else {
                    row(step, chevron: false)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 2)
    }

    // Cream capsule: colored tool icon (no backing square) + title (+ optional detail) + trailing
    // status (green check when done, spinner while active, ! on failure). Matches the requested
    // pill style shared with the reasoning card.
    @ViewBuilder
    private func row(_ step: ChatToolStepModel, chevron: Bool) -> some View {
        HStack(spacing: 7) {
            Image(systemName: step.systemImage)
                .font(.system(size: 12.5, weight: .semibold))
                .foregroundStyle(step.state.color)
                .frame(width: 16)

            Text(step.title)
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(1)
                .truncationMode(.tail)

            trailingStatus(for: step.state)

            if chevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(AmberTheme.muted)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        // Hug content (chip-style); a long title still truncates because the message column
        // bounds the available width. No maxWidth:.infinity → pills don't stretch full-width.
        .background(AmberTheme.surface, in: Capsule(style: .continuous))
        .contentShape(Capsule(style: .continuous))
    }

    @ViewBuilder
    private func trailingStatus(for state: ChatToolStepState) -> some View {
        switch state {
        case .done:
            Image(systemName: "checkmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(AmberTheme.accentGreen)
        case .active:
            ProgressView()
                .controlSize(.mini)
                .tint(AmberTheme.accent)
        case .failed:
            Image(systemName: "exclamationmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(AmberTheme.accentRed)
        }
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
                .frame(maxWidth: ChatLayout.userMaxWidth, alignment: .trailing)
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
                bodyText: "我正在整理界面状态、消息记录和工具结果，确保这次回复能继续当前上下文。",
                finishedSeconds: 3
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

// MARK: - Image attachment helpers

/// Compresses an image into a self-contained `data:` URL (sent to the model) plus a small
/// JPEG used only for the composer thumbnail. Downscaling keeps the persisted payload small.
private enum ChatImageEncoder {
    static let maxSendDimension: CGFloat = 1536
    static let maxThumbnailDimension: CGFloat = 160

    static func encode(_ image: UIImage) -> (dataUrl: String, previewData: Data)? {
        let sized = downscaled(image, maxDimension: maxSendDimension)
        guard let jpeg = sized.jpegData(compressionQuality: 0.7) else { return nil }
        let dataUrl = "data:image/jpeg;base64,\(jpeg.base64EncodedString())"
        let thumb = downscaled(image, maxDimension: maxThumbnailDimension)
        let previewData = thumb.jpegData(compressionQuality: 0.6) ?? jpeg
        return (dataUrl, previewData)
    }

    private static func downscaled(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > maxDimension, longest > 0 else { return image }
        let scale = maxDimension / longest
        let target = CGSize(width: size.width * scale, height: size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }
}

/// Right-aligned "visual recognition in progress" indicator shown on the user side while
/// the OCR-fallback vision model reads the image, with a breathing animation.
private struct VisionRecognitionIndicator: View {
    @State private var pulse = false

    var body: some View {
        HStack {
            Spacer(minLength: 40)
            HStack(spacing: 7) {
                Image(systemName: "sparkles")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .scaleEffect(pulse ? 1.18 : 0.86)
                    .opacity(pulse ? 1.0 : 0.55)
                Text("视觉识别中…")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(AmberTheme.surface, in: Capsule())
            .overlay(Capsule().stroke(AmberTheme.borderSoft, lineWidth: 1))
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }
}

/// Thin SwiftUI wrapper over `UIImagePickerController` for the 拍照 (camera) path.
private struct CameraPicker: UIViewControllerRepresentable {
    let onComplete: (UIImage?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onComplete: onComplete) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onComplete: (UIImage?) -> Void
        init(onComplete: @escaping (UIImage?) -> Void) { self.onComplete = onComplete }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            onComplete(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onComplete(nil)
        }
    }
}
