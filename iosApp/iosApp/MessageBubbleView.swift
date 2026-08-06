import SwiftUI
import Shared
import SwiftStreamingMarkdown
import Photos

enum ChatMarkdownOpenURLPolicy {
    static func url(from raw: String) -> URL? {
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              isAllowed(url) else {
            return nil
        }
        return url
    }

    static func isAllowed(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased() else {
            return false
        }
        return scheme == "http" || scheme == "https" || scheme == "mailto"
    }

    static func result(for url: URL) -> OpenURLAction.Result {
        isAllowed(url) ? .systemAction : .discarded
    }
}

enum ChatMarkdownRendererSelection: Equatable {
    case block
    case stable
}

enum ChatMarkdownRendererPolicy {
    static func selection(
        blockRendererEnabled: Bool
    ) -> ChatMarkdownRendererSelection {
        if blockRendererEnabled {
            return .block
        }
        return .stable
    }
}

enum ChatDataImageLoadState {
    case loading
    case success(UIImage)
    case failure

    static func resolve(urlString: String) -> ChatDataImageLoadState {
        guard let comma = urlString.firstIndex(of: ","),
              let data = Data(base64Encoded: String(urlString[urlString.index(after: comma)...])),
              let image = UIImage(data: data) else {
            return .failure
        }
        return .success(image)
    }
}

struct MessageBubbleView: View {

    let message: UIMessage
    var messageIndex: Int = 0
    var variantInfo: IOSConversationStore.VariantInfo? = nil
    var displaySetting: DisplaySetting? = nil
    var generativeUiSetting: GenerativeUiSetting? = nil
    // Branching actions (Android ChatService parity). Defaults are no-ops so
    // existing call sites / previews that don't supply them still compile.
    var onRegenerate: () -> Void = {}
    var onRequestEdit: (String) -> Void = { _ in }
    var onEdit: (String) -> Void = { _ in }
    var onDelete: () -> Void = {}
    var onSelectVariant: (Int) -> Void = { _ in }
    var onGenerativeWidgetAction: (String) -> Void = { _ in }
    var onModifyGeneratedImage: (String, String, String) -> Void = { _, _, _ in }
    var onOpenMiniApp: (String) -> Void = { _ in }
    var onOpenMiniApps: () -> Void = {}
    var isGenerating: Bool = false
    /// Global chat busy flag (any in-flight generation), used by MiniApp modify gate.
    var isChatGenerationActive: Bool = false
    /// True only for the last message — gates the live "thinking" timer so a stopped/older
    /// reasoning (whose finishedAt was never set on cancel) doesn't keep counting.
    var isLastMessage: Bool = false
    /// 这条消息「曾经流式过」的记忆(来自 projection 层)。控制层用它让完成后的
    /// 可见行继续走同一套流式 renderer,避免回收后切回同步 renderer 造成行高跳变。
    var hasEverStreamed: Bool = false
    /// False only when the live assistant message is far below the current viewport.
    /// That lets the offscreen stream freeze its last rendered snapshot instead of reparsing
    /// growing Markdown/widget payloads while the user is reading history.
    var liveMarkdownRenderingEnabled: Bool = true
    /// Snapshot captured when the streaming assistant row left the viewport.
    /// When present, offscreen/frozen rows render this stable text instead of the
    /// ever-growing live message payload.
    var frozenMarkdownSnapshot: String? = nil
    /// Reasoning effort label (e.g. "Auto") shown on the thinking pill. nil hides the suffix.
    var reasoningLevelLabel: String? = nil
    /// One-shot VoiceOver focus target issued after an external image anchor scroll succeeds.
    var imageAccessibilityFocusToolCallID: String? = nil

    @Environment(IOSWorkspaceStore.self) private var workspaceStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var workspaceSaveAlert: WorkspaceSaveAlert?
    @State private var toolDetailTarget: ToolDetailTarget?
    @AccessibilityFocusState private var focusedGeneratedImageToolCallID: String?

    private var isUser: Bool {
        message.role == MessageRole.user
    }

    private var canBranch: Bool {
        // Disable branch actions while a run is active, including tool approval
        // pauses where no stream job is currently running.
        !isGenerating
    }

    var body: some View {
        Group {
            if isUser {
                HStack {
                    Spacer(minLength: 48)

                    VStack(alignment: .trailing, spacing: 4) {
                        variantSwitcher
                        messageParts
                    }
                    .frame(maxWidth: ChatLayout.userMaxWidth, alignment: .trailing)
                }
            } else {
                ChatAssistantStack {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        ChatAgentName()
                        if variantInfo?.hasMultipleVariants == true {
                            variantBadge
                        }
                    }
                    variantSwitcher
                    fallbackThinkingCard
                    messageParts
                    annotationsBlock
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contextMenu { messageActions }
            }
        }
        .onChange(of: imageAccessibilityFocusToolCallID) { _, toolCallID in
            guard let toolCallID,
                  message.parts.contains(where: { part in
                    guard let tool = part as? UIMessagePart.Tool else { return false }
                    return tool.toolName == "generate_image" && tool.toolCallId == toolCallID
                  }) else { return }
            Task { @MainActor in
                await Task.yield()
                focusedGeneratedImageToolCallID = toolCallID
            }
        }
        .alert(item: $workspaceSaveAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("好"))
            )
        }
    }

    // MARK: - Annotations (URL citations)

    /// Renders URL-citation annotations the provider attaches to an assistant
    /// message (Android MessageAnnotations parity). The provider already parses
    /// these into `message.annotations`; iOS was just never rendering them.
    @ViewBuilder
    private var annotationsBlock: some View {
        let citations = Self.urlCitations(in: message)
        if !citations.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(citations.enumerated()), id: \.offset) { index, citation in
                    if let url = Self.safeExternalURL(from: citation.url) {
                        Link(destination: url) {
                            HStack(spacing: 4) {
                                Image(systemName: "link")
                                    .font(.caption2)
                                Text("[\(index + 1)] \(citation.title)")
                                    .font(.caption)
                                    .lineLimit(1)
                            }
                            .foregroundStyle(AmberTheme.accent)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.top, 2)
        }
    }

    /// Extracts URL-citation annotations from a message. `annotations` is a
    /// Kotlin `List<UIMessageAnnotation>` bridged as NSArray; each URL citation
    /// is a `UIMessageAnnotation.UrlCitation` sealed subclass instance.
    private static func urlCitations(in message: UIMessage) -> [(title: String, url: String)] {
        message.annotations.compactMap { annotation in
            // The sealed subclass UrlCitation bridges as
            // UIMessageAnnotation.UrlCitation in Swift.
            if let citation = annotation as? UIMessageAnnotation.UrlCitation {
                return (title: citation.title, url: citation.url)
            }
            return nil
        }
    }

    private static func safeExternalURL(from raw: String) -> URL? {
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            return nil
        }
        return url
    }

    // MARK: - Branching controls

    @ViewBuilder
    private var variantSwitcher: some View {
        if let info = variantInfo, info.hasMultipleVariants, canBranch {
            HStack(spacing: 4) {
                Button {
                    let next = info.selectedIndex - 1
                    if next >= 0 { onSelectVariant(next) }
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.caption2.weight(.semibold))
                }
                .buttonStyle(.plain)
                .disabled(info.selectedIndex <= 0)

                Text("\(info.selectedIndex + 1)/\(info.variantCount)")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(AmberTheme.muted)

                Button {
                    let next = info.selectedIndex + 1
                    if next < info.variantCount { onSelectVariant(next) }
                } label: {
                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.semibold))
                }
                .buttonStyle(.plain)
                .disabled(info.selectedIndex >= info.variantCount - 1)
            }
            .frame(maxWidth: .infinity, alignment: isUser ? .trailing : .leading)
        }
    }

    private var variantBadge: some View {
        Text("\((variantInfo?.selectedIndex ?? 0) + 1)/\(variantInfo?.variantCount ?? 1)")
            .font(.caption2.monospacedDigit())
            .foregroundStyle(AmberTheme.muted2)
    }

    @ViewBuilder
    private var messageActions: some View {
        if canBranch {
            if isUser {
                Button {
                    onRequestEdit(message.toText())
                } label: {
                    Label("编辑", systemImage: "pencil")
                }
            } else {
                Button {
                    onRegenerate()
                } label: {
                    Label("重新生成", systemImage: "arrow.clockwise")
                }
                if assistantArtifactText != nil {
                    Button {
                        saveAssistantMessageToWorkspace()
                    } label: {
                        Label("保存到 Workspace", systemImage: "tray.and.arrow.down")
                    }
                }
            }
            Button(role: .destructive) {
                onDelete()
            } label: {
                Label("删除", systemImage: "trash")
            }
            Divider()
        }
        Button {
            UIPasteboard.general.string = message.toText()
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        } label: {
            Label("复制", systemImage: "doc.on.doc")
        }
    }

    // MARK: - Subviews

    @ViewBuilder
    private var messageParts: some View {
        ForEach(Array(message.parts.enumerated()), id: \.offset) { partIndex, part in
            if let textPart = part as? UIMessagePart.Text, !textPart.text.isEmpty {
                if isUser {
                    // 气泡现在是内容尺寸(已移除其内部的 300pt 框),contextMenu 的高亮平台贴合气泡。
                    // 再用 .contentShape(.contextMenuPreview, 气泡圆角) 把平台裁成气泡形状,消除灰角。
                    ChatUserBubble(
                        text: GenerativeUiPlanner.shared.stripVisualRouteTagsForDisplay(text: textPart.text)
                    )
                        .contentShape(
                            .contextMenuPreview,
                            UnevenRoundedRectangle(
                                topLeadingRadius: 18,
                                bottomLeadingRadius: 18,
                                bottomTrailingRadius: 6,
                                topTrailingRadius: 18,
                                style: .continuous
                            )
                        )
                        .contextMenu { messageActions }
                } else {
                    ChatAssistantText {
                        ChatAssistantMarkdownView(
                            markdown: textPart.text,
                            renderCacheNamespace: "\(message.id):text:\(partIndex)",
                            displaySetting: displaySetting,
                            generativeUiSetting: generativeUiSetting,
                            isStreaming: isGenerating && isLastMessage,
                            hasEverStreamed: hasEverStreamed,
                            liveRenderingEnabled: liveMarkdownRenderingEnabled,
                            frozenMarkdownSnapshot: nonEmptyTextPartCount == 1 ? frozenMarkdownSnapshot : nil,
                            onGenerativeWidgetAction: onGenerativeWidgetAction
                        )
                    }
                }
            } else if let reasoning = part as? UIMessagePart.Reasoning {
                ChatReasoningCard(
                    bodyText: reasoning.reasoning,
                    isThinking: reasoning.finishedAt == nil && isGenerating && isLastMessage,
                    startedAt: Self.instantToDate(reasoning.createdAt),
                    finishedSeconds: Self.reasoningDurationSeconds(reasoning),
                    levelLabel: reasoningLevelLabel,
                    autoCloseThinking: displaySetting?.autoCloseThinking ?? true
                )
            } else if let image = part as? UIMessagePart.Image {
                if isUser {
                    ChatUserImageTile(urlString: image.url)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                } else {
                    ChatGeneratedImageGrid(
                        images: [image],
                        onModify: onModifyGeneratedImage
                    )
                }
            } else if let miniApp = part as? UIMessagePart.MiniApp {
                IOSMiniAppChatCard(
                    part: miniApp,
                    onRun: { onOpenMiniApp(miniApp.appId) },
                    onOpenList: onOpenMiniApps,
                    onModify: { prompt in
                        guard !isChatGenerationActive else { return false }
                        onGenerativeWidgetAction(prompt)
                        return true
                    }
                )
            } else if let tool = part as? UIMessagePart.Tool {
                ChatToolTimeline(
                    steps: [ChatToolStepModel(tool: tool)],
                    onTapStep: { _ in toolDetailTarget = ToolDetailTarget(tool: tool) }
                )
                if tool.toolName == "generate_image" {
                    let images = tool.output.compactMap { $0 as? UIMessagePart.Image }
                    if !images.isEmpty {
                        ChatGeneratedImageGrid(
                            images: images,
                            toolInput: tool.input,
                            onModify: onModifyGeneratedImage
                        )
                            .id(ChatImageGenerationAnchorTarget.id(toolCallID: tool.toolCallId))
                            .accessibilityElement(children: .contain)
                            .accessibilityLabel("图片已生成")
                            .accessibilityFocused(
                                $focusedGeneratedImageToolCallID,
                                equals: tool.toolCallId
                            )
                            .transition(.opacity)
                    } else if tool.output.isEmpty {
                        ChatGeneratedImageLoadingPlaceholder(toolInput: tool.input)
                            .id(ChatImageGenerationAnchorTarget.id(toolCallID: tool.toolCallId))
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel("正在生成图片")
                            .accessibilityFocused(
                                $focusedGeneratedImageToolCallID,
                                equals: tool.toolCallId
                            )
                            .transition(.opacity)
                    } else {
                        ChatGeneratedImageFailureCard(
                            reason: ChatToolOutputFormatter.imageFailureReason(from: tool.output)
                                ?? "图片生成工具没有返回图片。"
                        )
                        .id(ChatImageGenerationAnchorTarget.id(toolCallID: tool.toolCallId))
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel(
                            "图片生成失败：" + (
                                ChatToolOutputFormatter.imageFailureReason(from: tool.output)
                                    ?? "图片生成工具没有返回图片。"
                            )
                        )
                        .accessibilityFocused(
                            $focusedGeneratedImageToolCallID,
                            equals: tool.toolCallId
                        )
                        .transition(.opacity)
                    }
                }
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.28), value: message.parts.map(Self.partAnimationKey).joined(separator: "|"))
        .sheet(item: $toolDetailTarget) { target in
            let currentTool = toolPart(toolCallId: target.toolCallId) ?? target.initialTool
            ChatToolDetailSheet(
                tool: currentTool,
                live: currentTool.toolName.contains("subagent_dispatch")
                    ? SubAgentLiveRegistry.shared.model(forToolCallId: currentTool.toolCallId)
                    : nil
            )
        }
    }

    @ViewBuilder
    private var fallbackThinkingCard: some View {
        if !isUser, isGenerating, isLastMessage, !hasVisibleAssistantContent {
            ChatReasoningCard(
                bodyText: "",
                isThinking: true,
                levelLabel: reasoningLevelLabel,
                autoCloseThinking: displaySetting?.autoCloseThinking ?? true
            )
            .transition(.opacity)
        }
    }

    private var hasVisibleAssistantContent: Bool {
        message.parts.contains { part in
            if let text = part as? UIMessagePart.Text {
                return text.text.contains { !$0.isWhitespace }
            }
            return true
        }
    }

    private var nonEmptyTextPartCount: Int {
        message.parts.reduce(0) { count, part in
            guard let text = part as? UIMessagePart.Text, !text.text.isEmpty else { return count }
            return count + 1
        }
    }

    private func toolPart(toolCallId: String) -> UIMessagePart.Tool? {
        message.parts.lazy
            .compactMap { $0 as? UIMessagePart.Tool }
            .first { $0.toolCallId == toolCallId }
    }

    private static func partAnimationKey(_ part: UIMessagePart) -> String {
        if let tool = part as? UIMessagePart.Tool {
            let imageCount = tool.output.compactMap { $0 as? UIMessagePart.Image }.count
            return "tool:\(tool.toolCallId):\(tool.output.isEmpty):\(imageCount)"
        }
        if let miniApp = part as? UIMessagePart.MiniApp {
            return "mini_app:\(miniApp.appId):\(miniApp.version):\(miniApp.htmlHash ?? "")"
        }
        return String(describing: type(of: part))
    }

    private var assistantArtifactText: String? {
        guard !isUser else { return nil }
        let text = message.parts.compactMap { part -> String? in
            guard let textPart = part as? UIMessagePart.Text else { return nil }
            let trimmed = textPart.text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : textPart.text
        }
        .joined(separator: "\n\n")
        .trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }

    private func saveAssistantMessageToWorkspace() {
        guard let text = assistantArtifactText else { return }
        do {
            _ = try workspaceStore.saveArtifact(
                title: artifactTitle(for: text),
                content: text,
                type: .chat,
                sourceKind: "chat_message",
                sourceId: String(describing: message.id)
            )
            workspaceSaveAlert = .saved
        } catch {
            workspaceSaveAlert = .failed(error.localizedDescription)
        }
    }

    private func artifactTitle(for text: String) -> String {
        let firstLine = text
            .split(whereSeparator: \.isNewline)
            .first
            .map(String.init)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let firstLine, !firstLine.isEmpty else { return "Chat Artifact" }
        return String(firstLine.prefix(80))
    }

    private static func reasoningDurationSeconds(_ reasoning: UIMessagePart.Reasoning) -> Double? {
        guard let end = reasoning.finishedAt else { return nil }
        let start = instantToDate(reasoning.createdAt)
        let endDate = instantToDate(end)
        return max(0, endDate.timeIntervalSince(start))
    }

    static func instantToDate(_ instant: KotlinInstant) -> Date {
        Date(timeIntervalSince1970:
            Double(instant.epochSeconds) + Double(instant.nanosecondsOfSecond) / 1_000_000_000)
    }
}

/// 唯一的 assistant Markdown 渲染入口:聊天页与模型议会共用同一组件,
/// 跟随同一组 Markdown 渲染偏好。默认走 App 自有同步渲染器(吃字体/排版偏好),
/// 实验渲染器在这里互斥切换,避免两边各渲染各的、视觉不一致。
struct ChatAssistantMarkdownView: View {
    let markdown: String
    var renderCacheNamespace: String?
    var displaySetting: DisplaySetting?
    var generativeUiSetting: GenerativeUiSetting?
    var isStreaming = false
    var hasEverStreamed = false
    var liveRenderingEnabled = true
    var frozenMarkdownSnapshot: String?
    var onGenerativeWidgetAction: (String) -> Void = { _ in }

    // 渲染器选择已硬编码：block 渲染器无条件参与竞争，coalesced 合并默认开启。
    // 不再暴露 microsoft/liyanan/streamingBlock/coalesced 开关给用户。
    @AppStorage(IOSDisplayPreferenceKeys.fontScale) private var fontScale = 1.0
    @AppStorage(IOSDisplayPreferenceKeys.chatFont) private var chatFont = IOSChatFont.default.rawValue
    @ScaledMetric(relativeTo: .body) private var scaledBodyPointSize: CGFloat = 17
    /// per-view-instance 的「这个 bubble 用过 block 流式渲染」latch，覆盖 completion 瞬间；
    /// 回收后的完成态由 projection 层 hasEverStreamed + liveRenderingEnabled 驱动。
    @State private var hasUsedBlockMarkdownRenderer = false
    @State private var renderedMarkdownSnapshot = ""
    /// 表格/widget 探测器放在引用盒子里而不是 @State 值类型:探测器每个 chunk 都要
    /// 增量消费新字节,值类型 @State 的突变会让每个 delta 额外触发一轮 body 重求值,
    /// 与 signal 驱动的那轮叠加成倍放大热路径成本。body 只依赖下面两个显式 latch。
    @State private var detection: ChatStreamingDetectionBox
    /// body 读取的 widget 探测 latch(IOSGenerativeWidgetPayloadDetector 的增量结果)。
    /// 只在 false→true 翻转时写入一次,不随每个 chunk 失效。
    @State private var mayContainWidgetPayload: Bool

    init(
        markdown: String,
        renderCacheNamespace: String? = nil,
        displaySetting: DisplaySetting? = nil,
        generativeUiSetting: GenerativeUiSetting? = nil,
        isStreaming: Bool = false,
        hasEverStreamed: Bool = false,
        liveRenderingEnabled: Bool = true,
        frozenMarkdownSnapshot: String? = nil,
        onGenerativeWidgetAction: @escaping (String) -> Void = { _ in }
    ) {
        self.markdown = markdown
        self.renderCacheNamespace = renderCacheNamespace
        self.displaySetting = displaySetting
        self.generativeUiSetting = generativeUiSetting
        self.isStreaming = isStreaming
        self.hasEverStreamed = hasEverStreamed
        self.liveRenderingEnabled = liveRenderingEnabled
        self.frozenMarkdownSnapshot = frozenMarkdownSnapshot
        self.onGenerativeWidgetAction = onGenerativeWidgetAction
        // 流式尾行的 view struct 每个 delta 重建,State(initialValue:) 首帧之后
        // 全部被丢弃——eager 种子扫描曾按"微秒级"评估保留,采样实测占主线程
        // ~6.6%(24KB×逐字节×每 delta×两次 body eval),证伪。改为:只有
        // 非流式行(历史行,首帧结果必须就位且不会每 delta 重建)做 eager 扫描;
        // 流式行从空状态起步,由 onAppear/onChange 的持久盒增量补齐——
        // 代价仅是"流式中途重进入"首帧探测未就位(下一帧补齐)。
        if isStreaming {
            let detection = ChatStreamingDetectionBox(markdown: "")
            _detection = State(initialValue: detection)
            _mayContainWidgetPayload = State(initialValue: false)
            _hasUsedBlockMarkdownRenderer = State(initialValue:
                ChatStreamingMarkdownRendererPolicy.initialBlockRendererLatch(
                    isStreaming: isStreaming,
                    hasEverStreamed: hasEverStreamed,
                    liveRenderingEnabled: liveRenderingEnabled
                )
            )
        } else {
            let detection = ChatStreamingDetectionBox(markdown: markdown)
            _detection = State(initialValue: detection)
            _mayContainWidgetPayload = State(initialValue: detection.widget.mayContainPayload)
            _hasUsedBlockMarkdownRenderer = State(initialValue:
                ChatStreamingMarkdownRendererPolicy.initialBlockRendererLatch(
                    isStreaming: isStreaming,
                    hasEverStreamed: hasEverStreamed,
                    liveRenderingEnabled: liveRenderingEnabled
                )
            )
        }
    }

    var body: some View {
        let widgetSettings = IOSGenerativeWidgetSettings(generativeUiSetting)
        let renderedMarkdown = renderedMarkdownText
        let liveStreaming = isStreaming && liveRenderingEnabled
        Group {
            // 探测走增量 latch(mayContainWidgetPayload @State),不再每次 body 求值
            // 对全文做 13+ 次 caseInsensitive 扫描(32KB 实测 46ms/次,是长内容
            // 流式掉帧的头号单项)。latch 基于完整 markdown,对 frozen snapshot
            // 只可能过检不可能欠检;过检时 parse 找不到 widget 段仍走纯文本分支。
            if widgetSettings.enabled && mayContainWidgetPayload {
                let segments = IOSGenerativeWidgetParser.parse(renderedMarkdown, streaming: liveStreaming)
                let hasWidgetSegment = segments.contains { segment in
                    switch segment {
                    case .widget, .loading:
                        return true
                    case .text:
                        return false
                    }
                }
                if hasWidgetSegment {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(segments) { segment in
                            switch segment {
                            case .text(let id, let content):
                                markdownText(
                                    content,
                                    liveStreaming: liveStreaming,
                                    cacheIdentitySuffix: "widget:\(id)"
                                )
                            case .widget(let widget):
                                IOSGenerativeWidgetCard(
                                    widget: widget,
                                    generativeUiSetting: generativeUiSetting,
                                    onAction: onGenerativeWidgetAction
                                )
                            case .loading:
                                IOSGenerativeWidgetLoadingView()
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    markdownText(renderedMarkdown, liveStreaming: liveStreaming)
                }
            } else {
                markdownText(renderedMarkdown, liveStreaming: liveStreaming)
            }
        }
        .onAppear {
            if renderedMarkdownSnapshot.isEmpty {
                renderedMarkdownSnapshot = markdown
            }
            updateWidgetPayloadLatch(with: markdown)
            if isStreaming && liveRenderingEnabled {
                updateTableRendererLatch(with: renderedMarkdown)
            }
        }
        .onChange(of: markdown) { _, newValue in
            updateWidgetPayloadLatch(with: newValue)
            if isStreaming,
               liveRenderingEnabled,
               !hasUsedBlockMarkdownRenderer {
                updateTableRendererLatch(with: newValue)
            }
        }
        .onChange(of: isStreaming) { _, newValue in
            if newValue && liveRenderingEnabled {
                renderedMarkdownSnapshot = markdown
                if !hasUsedBlockMarkdownRenderer {
                    updateTableRendererLatch(with: markdown)
                }
            } else if !newValue {
                renderedMarkdownSnapshot = markdown
                // Completion may replace the cumulative stream with one final full
                // message. Reconcile once here instead of validating the whole prefix
                // on every append-only chunk.
                reconcileFinalWidgetPayloadLatch(with: markdown)
            }
        }
        .onChange(of: liveRenderingEnabled) { _, newValue in
            // Capture once at the live/frozen boundary. Mirroring every live delta
            // into @State only schedules a duplicate body update.
            renderedMarkdownSnapshot = markdown
            if ChatStreamingMarkdownRendererPolicy.initialBlockRendererLatch(
                isStreaming: isStreaming,
                hasEverStreamed: hasEverStreamed,
                liveRenderingEnabled: newValue
            ) {
                hasUsedBlockMarkdownRenderer = true
            }
            if newValue && isStreaming {
                if !hasUsedBlockMarkdownRenderer {
                    updateTableRendererLatch(with: markdown)
                }
            }
        }
        .environment(
            \.openURL,
            OpenURLAction { url in
                ChatMarkdownOpenURLPolicy.result(for: url)
            }
        )
    }

    private var renderedMarkdownText: String {
        if !liveRenderingEnabled, let frozenMarkdownSnapshot, !frozenMarkdownSnapshot.isEmpty {
            return frozenMarkdownSnapshot
        }
        if isStreaming && !liveRenderingEnabled && !renderedMarkdownSnapshot.isEmpty {
            return renderedMarkdownSnapshot
        }
        return markdown
    }

    private func shouldUseBlockStreamingRenderer(liveStreaming: Bool) -> Bool {
        // 流式从首帧一律走同一块路径，表格前后的稳定块可以冻结复用；绝不允许
        // 中途从单文档切到块路径，否则 vendor ParagraphView 重建会让已上屏内容
        // 整段重淡入。latch 保证完成瞬间保持 renderer 连续；表格探测仍兜底
        // 回收后历史行的入场判定。
        return liveStreaming || hasUsedBlockMarkdownRenderer ||
            (hasEverStreamed && liveRenderingEnabled)
    }

    private func updateTableRendererLatch(with text: String) {
        if isStreaming, liveRenderingEnabled {
            hasUsedBlockMarkdownRenderer = true
        }
        detection.table.update(with: text)
        if detection.table.containsTable {
            hasUsedBlockMarkdownRenderer = true
        }
    }

    private func updateWidgetPayloadLatch(with text: String) {
        guard !mayContainWidgetPayload else { return }
        detection.widget.update(with: text)
        if detection.widget.mayContainPayload {
            mayContainWidgetPayload = true
        }
    }

    private func reconcileFinalWidgetPayloadLatch(with text: String) {
        guard !mayContainWidgetPayload else { return }
        detection.widget.reconcileFinalText(text)
        if detection.widget.mayContainPayload {
            mayContainWidgetPayload = true
        }
    }

    /// 流式渲染器(SwiftStreamingMarkdown)排版参数单侧对齐紧凑基准 AmberMarkdownView,
    /// 全部取设计原值:字体 17×scale、行距 4×scale(与 ChatAssistantText wrapper 同源,
    /// 见 ChatMessageListSupport.swift:112-136)、块间距 8pt(MarkdownView.swift:173)、
    /// 列表项间距 4pt(MarkdownView.swift:413/424)、表格 cell padding 水平 12/垂直 8、
    /// 表格正文 17pt(MarkdownView.swift:368-371)。heading 行距同样传 4×scale——
    /// 紧凑基准的标题(SwiftUI Text)吃 wrapper 的 .lineSpacing environment。
    /// collapsesSoftBreaks 对齐紧凑基准的 CommonMark softBreak 语义(段内单换行折叠
    /// 为空格、CJK 间折叠为空,而非硬换行)——这是行数级差异,不折叠则每个 softBreak
    /// 多出一行。
    /// 只调排版度量,不碰渲染管线——渐进渲染/逐词淡入只在真正 liveStreaming 时开启。
    /// 完成态的 view 实例可以继续使用 SwiftStreamingMarkdown 保持 renderer 连续性,但必须
    /// 退出文字动画路径,避免 UIKit 段落视图在完成/回收阶段继续走动态文字更新和尺寸缓存。
    private var boundedFontScale: Double {
        min(max(fontScale, 0.88), 1.25)
    }

    private func streamingMarkdownConfig(liveStreaming: Bool) -> SwiftStreamingMarkdown.MarkdownRenderConfig {
        let scale = boundedFontScale
        let foreground = UIColor(AmberTheme.foreground)
        let foreground2 = UIColor(AmberTheme.foreground2)
        let muted = UIColor(AmberTheme.muted)
        let surface2 = UIColor(AmberTheme.surface2)
        let border = UIColor(AmberTheme.border)
        let accent = UIColor(AmberTheme.accent)
        let bodyFonts = ChatStreamingMarkdownTypography.bodyFonts(
            chatFont: IOSChatFont(rawValue: chatFont) ?? .default,
            pointSize: scaledBodyPointSize * scale
        )
        let headingFonts = ChatStreamingMarkdownTypography.applyingChatFont(
            IOSChatFont(rawValue: chatFont) ?? .default,
            to: SwiftStreamingMarkdown.MarkdownRenderConfig.defaultHeadingStyle
        )
        let paragraphStyle = SwiftStreamingMarkdown.MarkdownRenderConfig.MarkdownTextStyle(
            textFonts: bodyFonts,
            textColor: foreground
        )
        let blockQuoteStyle = SwiftStreamingMarkdown.MarkdownRenderConfig.MarkdownTextStyle(
            textFonts: bodyFonts,
            textColor: muted
        )
        let orderedListStyle = SwiftStreamingMarkdown.MarkdownRenderConfig.MarkdownTextStyle(
            textFonts: bodyFonts,
            textColor: foreground
        )
        let headingStyle = SwiftStreamingMarkdown.MarkdownRenderConfig.MarkdownHeadingTextStyle(
            h1Font: headingFonts.h1Font,
            h2Font: headingFonts.h2Font,
            h3Font: headingFonts.h3Font,
            h4Font: headingFonts.h4Font,
            h5Font: headingFonts.h5Font,
            h6Font: headingFonts.h6Font,
            textColor: foreground
        )
        let tableStyle = SwiftStreamingMarkdown.MarkdownRenderConfig.MarkdownTableTextStyle(
            textFonts: bodyFonts,
            headerTextColor: foreground,
            regularTextColor: foreground,
            headerBackgroundColor: surface2,
            borderColor: border,
            actionButtonColor: accent
        )
        let inlineStyle = SwiftStreamingMarkdown.MarkdownRenderConfig.MarkdownInlineTextStyle(
            boldTextColor: foreground,
            linkTextFont: bodyFonts.normal,
            linkTextColor: accent,
            codeTextFont: SwiftStreamingMarkdown.MarkdownRenderConfig.defaultInlineStyle.codeTextFont,
            codeTextColor: foreground2,
            codeBackgroundColor: surface2,
            codeUnderlineColor: border
        )
        return SwiftStreamingMarkdown.MarkdownRenderConfig.default
            .withShouldAnimateText(value: liveStreaming)
            .withBlockQuoteStyle(value: blockQuoteStyle)
            .withHeadingStyle(value: headingStyle)
            .withOrderedListStyle(value: orderedListStyle)
            .withParagraphStyle(value: paragraphStyle)
            .withTableStyle(value: tableStyle)
            .withInlineStyle(value: inlineStyle)
            .withParagraphLineSpacing(value: 4 * scale)
            .withHeadingLineSpacing(value: 4 * scale)
            .withBlockSpacing(value: 8)
            .withListItemSpacing(value: 4)
            .withTableCellHorizontalPadding(value: 12)
            .withTableCellVerticalPadding(value: 8)
            .withTableMaxColumnWidth(value: 300)
            // 紧凑基准无序列表的内容缩进 = Text("•") 宽(~7pt)+ spacing 8 ≈ 15pt;
            // vendor 内容缩进 = bulletWidth + spacing 1,故 bulletWidth 取 15 对齐,
            // 消除因内容可用宽度不同导致的折行行数差。
            .withUnorderedListBulletWidth(value: 15)
            .withCollapsesSoftBreaks(value: true)
            // 相邻纯正文段落合并成一个文本视图：长回复的 ParagraphUIView 数量从
            // 「段落数」降到 1,每次流式提交不再走 O(段落数) 的 SwiftUI 子树 diff,
            // 新段落也不再是「新建视图 + 从 alpha 0 淡入」。合并块内的增长仍走
            // ParagraphUIView 的 append 快路径(行距/段距烘进属性串,视图侧传 nil)。
            // 这里是三个界面(Chat/议会/小说)共用的唯一 config 构造点。
            .withCoalescesAdjacentTextBlocks(value: true)
    }

    @ViewBuilder
    private func markdownText(
        _ content: String,
        liveStreaming: Bool,
        cacheIdentitySuffix: String = "document"
    ) -> some View {
        let config = detection.markdownConfig(
            for: ChatStreamingDetectionBox.MarkdownConfigKey(
                liveStreaming: liveStreaming,
                fontScale: boundedFontScale,
                bodyPointSize: scaledBodyPointSize,
                chatFont: chatFont,
                themePaper: AmberThemeRuntime.shared.paper.rawValue,
                themeAccentHex: AmberThemeRuntime.shared.accentHex,
                coalescedTextBlocks: true
            ),
            build: { streamingMarkdownConfig(liveStreaming: liveStreaming) }
        )
        switch ChatMarkdownRendererPolicy.selection(
            blockRendererEnabled: shouldUseBlockStreamingRenderer(liveStreaming: liveStreaming)
        ) {
        case .block:
            ChatStreamingBlockMarkdownView(
                text: content,
                config: config,
                liveStreaming: liveStreaming,
                renderCacheNamespace: renderCacheNamespace.map { "\($0):\(cacheIdentitySuffix)" }
            )
            .frame(maxWidth: .infinity, alignment: .leading)
        case .stable:
            AmberMarkdownView(markdown: content, displaySetting: displaySetting)
        }
    }
}

enum ChatStreamingMarkdownRendererPolicy {
    static func initialBlockRendererLatch(
        isStreaming: Bool,
        hasEverStreamed: Bool,
        liveRenderingEnabled: Bool
    ) -> Bool {
        (isStreaming || hasEverStreamed) && liveRenderingEnabled
    }
}

private enum ChatStreamingMarkdownTypography {
    static func bodyFonts(chatFont: IOSChatFont, pointSize: CGFloat) -> SwiftStreamingMarkdown.TextFonts {
        let regular = UIFont.systemFont(ofSize: pointSize, weight: .regular)
        let semibold = UIFont.systemFont(ofSize: pointSize, weight: .semibold)
        return SwiftStreamingMarkdown.TextFonts(
            normal: applyingChatFont(chatFont, to: regular),
            italic: applyingChatFont(chatFont, to: regular.withItalicTrait()),
            bold: applyingChatFont(chatFont, to: semibold),
            boldItalic: applyingChatFont(chatFont, to: semibold.withItalicTrait()),
            preferredLetterSpacing: 0,
            preferredLineHeight: nil
        )
    }

    static func applyingChatFont(
        _ chatFont: IOSChatFont,
        to style: SwiftStreamingMarkdown.MarkdownRenderConfig.MarkdownHeadingTextStyle
    ) -> SwiftStreamingMarkdown.MarkdownRenderConfig.MarkdownHeadingTextStyle {
        SwiftStreamingMarkdown.MarkdownRenderConfig.MarkdownHeadingTextStyle(
            h1Font: applyingChatFont(chatFont, to: style.h1Font),
            h2Font: applyingChatFont(chatFont, to: style.h2Font),
            h3Font: applyingChatFont(chatFont, to: style.h3Font),
            h4Font: applyingChatFont(chatFont, to: style.h4Font),
            h5Font: applyingChatFont(chatFont, to: style.h5Font),
            h6Font: applyingChatFont(chatFont, to: style.h6Font),
            textColor: style.textColor
        )
    }

    private static func applyingChatFont(
        _ chatFont: IOSChatFont,
        to fonts: SwiftStreamingMarkdown.TextFonts
    ) -> SwiftStreamingMarkdown.TextFonts {
        SwiftStreamingMarkdown.TextFonts(
            normal: applyingChatFont(chatFont, to: fonts.normal),
            italic: fonts.italic.map { applyingChatFont(chatFont, to: $0) },
            bold: fonts.bold.map { applyingChatFont(chatFont, to: $0) },
            boldItalic: fonts.boldItalic.map { applyingChatFont(chatFont, to: $0) },
            preferredLetterSpacing: fonts.preferredLetterSpacing,
            preferredLineHeight: fonts.preferredLineHeight
        )
    }

    private static func applyingChatFont(_ chatFont: IOSChatFont, to font: UIFont) -> UIFont {
        let design: UIFontDescriptor.SystemDesign
        switch chatFont {
        case .default:
            return font
        case .serif:
            design = .serif
        case .monospace:
            design = .monospaced
        }
        guard let descriptor = font.fontDescriptor.withDesign(design) else {
            return font
        }
        return UIFont(descriptor: descriptor, size: font.pointSize)
    }
}

#if DEBUG
enum ChatStreamingMarkdownTypographyTestSupport {
    static func bodyFontName(chatFont: IOSChatFont) -> String {
        ChatStreamingMarkdownTypography.bodyFonts(chatFont: chatFont, pointSize: 17).normal.fontName
    }
}
#endif

private struct ChatStreamingBlockMarkdownView: View {
    let text: String
    let config: SwiftStreamingMarkdown.MarkdownRenderConfig
    let liveStreaming: Bool
    let renderCacheNamespace: String?
    @StateObject private var controller: ChatStreamingMarkdownBlockController

    init(
        text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig,
        liveStreaming: Bool,
        renderCacheNamespace: String? = nil
    ) {
        self.text = text
        self.config = config
        self.liveStreaming = liveStreaming
        self.renderCacheNamespace = renderCacheNamespace
        _controller = StateObject(wrappedValue: ChatStreamingMarkdownBlockController(
            text: text,
            includeTrailingPartialTableRow: !liveStreaming
        ))
    }

    var body: some View {
        // 结构性隔离:本层每个 delta 都因 `text` 变化重求值,但块列表子树
        // 只依赖 controller 的结构发布。已稳定块继续复用；普通文本尾块把可见
        // 节奏交给内部 Markdown controller，表格尾块仍在本层限频，避免每个
        // table token 都重建整张表。
        ChatStreamingMarkdownBlockListView(
            controller: controller,
            config: config,
            renderCacheNamespace: renderCacheNamespace
        )
            .task(id: ChatStreamingMarkdownBlockParseKey(
                text: text,
                includeTrailingPartialTableRow: !liveStreaming
            )) {
                controller.scheduleParse(
                    text: text,
                    includeTrailingPartialTableRow: !liveStreaming
                )
            }
    }
}

private struct ChatStreamingMarkdownBlockListView: View {
    @ObservedObject var controller: ChatStreamingMarkdownBlockController
    let config: SwiftStreamingMarkdown.MarkdownRenderConfig
    let renderCacheNamespace: String?

    var body: some View {
        VStack(alignment: .leading, spacing: config.blockSpacing) {
            ForEach(controller.blocks) { block in
                switch block.kind {
                case .text(let content):
                    ChatStableStreamingMarkdownView(
                        text: content,
                        config: config,
                        cacheIdentity: renderCacheNamespace.map { "\($0):\(block.id)" }
                    )
                        .frame(maxWidth: .infinity, alignment: .leading)
                case .table(let table):
                    // 用 vendor 的真实 Markdown 表格渲染，保留 inline 样式、链接与
                    // shouldAnimateText 淡入；块解析只负责隐藏尚未闭合的尾行。
                    ChatStableStreamingMarkdownView(
                        text: table.markdown,
                        config: config,
                        cacheIdentity: renderCacheNamespace.map { "\($0):\(block.id)" }
                    )
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }
}

private struct ChatStreamingMarkdownBlockParseKey: Equatable {
    let text: String
    let includeTrailingPartialTableRow: Bool
}

@MainActor
private final class ChatStreamingMarkdownBlockController: ObservableObject {
    @Published private(set) var blocks: [ChatStreamingMarkdownBlock]

    private var pendingParse: (text: String, includeTrailingPartialTableRow: Bool)?
    private var parseTask: Task<Void, Never>?
    private var lastPublishAt = Date.distantPast

    init(text: String, includeTrailingPartialTableRow: Bool) {
        blocks = ChatPerfTrace.measure("MarkdownBlockSplitInitial", count: { text.utf16.count }) {
            ChatStreamingMarkdownBlockParser.blocks(
                in: text,
                includeTrailingPartialTableRow: includeTrailingPartialTableRow
            )
        }
    }

    deinit {
        parseTask?.cancel()
    }

    /// 普通文本尾块不在这里重复限频:块控制器只负责结构拆分，实际 Markdown
    /// 解析已有 single-flight/latest-wins 背压。表格尾块仍保留低频发布，避免
    /// 每个表格 token 都让整张表进入布局；半截行由 block parser 隐藏。
    private func publishInterval(for text: String) -> TimeInterval {
        var isTableTail = false
        if let tail = blocks.last, case .table = tail.kind {
            isTableTail = true
        }
        return Self.publishInterval(utf16Length: text.utf16.count, isTableTail: isTableTail)
    }

    /// 表格尾块发布档位(P1-5 止血):<12K 保持原档位,超大表格(≥12K utf16)
    /// 降到 0.22s,减少整表布局频率。
    static func publishInterval(utf16Length length: Int, isTableTail: Bool) -> TimeInterval {
        guard isTableTail else { return 0 }
        if length < 1_200 {
            return 0.09
        }
        if length < 4_000 {
            return 0.12
        }
        if length < 12_000 {
            return 0.16
        }
        return 0.22
    }

    func scheduleParse(text: String, includeTrailingPartialTableRow: Bool) {
        pendingParse = (text, includeTrailingPartialTableRow)
        guard parseTask == nil else { return }
        parseTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { parseTask = nil }
            while !Task.isCancelled, let next = pendingParse {
                pendingParse = nil

                let elapsed = Date().timeIntervalSince(lastPublishAt)
                let interval = publishInterval(for: next.text)
                if elapsed < interval {
                    do {
                        try await Task.sleep(nanoseconds: UInt64((interval - elapsed) * 1_000_000_000))
                    } catch {
                        return
                    }
                    guard !Task.isCancelled else { return }
                }
                // 节流窗内若有更新的累计文本到达,直接解析最新值。
                let target = pendingParse ?? next
                pendingParse = nil

                let parsed = await Task.detached(priority: .userInitiated) {
                    ChatPerfTrace.measure("MarkdownBlockSplit", count: { target.text.utf16.count }) {
                        ChatStreamingMarkdownBlockParser.blocks(
                            in: target.text,
                            includeTrailingPartialTableRow: target.includeTrailingPartialTableRow
                        )
                    }
                }.value
                guard !Task.isCancelled else { return }
                // 解析串行消费累计全文，当前结果一定晚于上次已发布结果。即使
                // 解析期间又来了 delta，也先发布这次前缀再继续消费最新 pending；
                // 否则长内容解析慢于 chunk 间隔时会永久没有任何结果能发布。
                lastPublishAt = Date()
                publishPreservingSettledBlocks(parsed)
            }
        }
    }

    /// 前缀块冻结的核心:新解析结果与上一次逐块比较,内容未变的块**复用旧实例**
    /// (包括其中的 String 存储)。这样 ForEach 里已定块的子视图输入按位与上一帧
    /// 完全一致,SwiftUI 直接短路整棵子树;真正重新求值/布局的只有变化中的尾部块。
    /// 全部相等时不发布,避免无效的 objectWillChange。
    private func publishPreservingSettledBlocks(_ parsed: [ChatStreamingMarkdownBlock]) {
        var merged = parsed
        var changed = merged.count != blocks.count
        for index in merged.indices {
            if index < blocks.count, blocks[index] == merged[index] {
                merged[index] = blocks[index]
            } else {
                changed = true
            }
        }
        if changed {
            blocks = merged
        }
    }
}

private struct ChatStreamingMarkdownBlock: Identifiable, Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case text(String)
        case table(ChatStreamingMarkdownTable)
    }

    let id: String
    let kind: Kind
}

private struct ChatStreamingMarkdownTable: Equatable, Sendable {
    let id: String
    let markdown: String
    let headers: [String]
    let rows: [[String]]
}

private struct ChatStreamingMarkdownParsedTable: Sendable {
    let table: ChatStreamingMarkdownTable
    let consumedLineCount: Int
}

private enum ChatStreamingMarkdownBlockParser {
    static func containsTable(in text: String) -> Bool {
        let lines = text.components(separatedBy: .newlines)
        guard lines.count >= 2 else { return false }
        var activeFenceMarker: Character?

        for index in 0..<(lines.count - 1) {
            let line = lines[index]
            if let marker = fenceMarker(in: line) {
                if activeFenceMarker == nil {
                    activeFenceMarker = marker
                } else if activeFenceMarker == marker {
                    activeFenceMarker = nil
                }
                continue
            }
            guard activeFenceMarker == nil else { continue }
            let headers = splitTableCells(line)
            if headers.count >= 2,
               isDelimiterLine(lines[index + 1], expectedCount: headers.count) {
                return true
            }
        }
        return false
    }

    static func startsWithTable(in text: String) -> Bool {
        let firstLines = text.split(separator: "\n", maxSplits: 2, omittingEmptySubsequences: false)
        guard firstLines.count >= 2 else { return false }
        let headers = splitTableCells(String(firstLines[0]))
        return headers.count >= 2 &&
            isDelimiterLine(String(firstLines[1]), expectedCount: headers.count)
    }

    static func blocks(
        in text: String,
        includeTrailingPartialTableRow: Bool = true,
        includeParsedTableCells: Bool = false
    ) -> [ChatStreamingMarkdownBlock] {
        parseBlocks(
            in: text,
            includeTrailingPartialTableRow: includeTrailingPartialTableRow,
            includeParsedTableCells: includeParsedTableCells
        )
    }

    private static func parseBlocks(
        in text: String,
        includeTrailingPartialTableRow: Bool,
        includeParsedTableCells: Bool
    ) -> [ChatStreamingMarkdownBlock] {
        let lines = text.components(separatedBy: .newlines)
        var blocks: [ChatStreamingMarkdownBlock] = []
        var textStart = 0
        var index = 0
        var blockOrdinal = 0
        var activeFenceMarker: Character?

        func flushText(until end: Int) {
            guard textStart < end else { return }
            let content = lines[textStart..<end].joined(separator: "\n")
            guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                textStart = end
                return
            }
            blocks.append(ChatStreamingMarkdownBlock(
                id: "text-\(blockOrdinal)",
                kind: .text(content)
            ))
            blockOrdinal += 1
            textStart = end
        }

        while index < lines.count {
            let line = lines[index]
            if let marker = fenceMarker(in: line) {
                if activeFenceMarker == nil {
                    activeFenceMarker = marker
                } else if activeFenceMarker == marker {
                    activeFenceMarker = nil
                }
                index += 1
                continue
            }

            if activeFenceMarker == nil,
               index + 1 < lines.count,
               let parsedTable = parseTable(
                lines: lines,
                start: index,
                sourceEndsWithNewline: text.hasSuffix("\n"),
                includeTrailingPartialTableRow: includeTrailingPartialTableRow,
                includeParsedTableCells: includeParsedTableCells
               ) {
                flushText(until: index)
                blocks.append(ChatStreamingMarkdownBlock(
                    id: "table-\(blockOrdinal)",
                    kind: .table(parsedTable.table)
                ))
                blockOrdinal += 1
                index += parsedTable.consumedLineCount
                textStart = index
                continue
            }

            index += 1
        }

        flushText(until: lines.count)
        return blocks
    }

    private static func parseTable(
        lines: [String],
        start: Int,
        sourceEndsWithNewline: Bool,
        includeTrailingPartialTableRow: Bool,
        includeParsedTableCells: Bool
    ) -> ChatStreamingMarkdownParsedTable? {
        guard start + 1 < lines.count else { return nil }
        let headers = splitTableCells(lines[start])
        guard headers.count >= 2, isDelimiterLine(lines[start + 1], expectedCount: headers.count) else {
            return nil
        }

        var rows: [[String]] = []
        var index = start + 2
        var renderedEndIndex = start + 2
        let headerUsesLeadingPipe = lines[start]
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .hasPrefix("|")
        while index < lines.count {
            let line = lines[index]
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            let isTrailingPartialLine = !includeTrailingPartialTableRow &&
                !sourceEndsWithNewline &&
                index == lines.count - 1
            let canBeNoLeadingPipePartialRow = isTrailingPartialLine && !headerUsesLeadingPipe
            guard !trimmed.isEmpty,
                  line.contains("|") || canBeNoLeadingPipePartialRow,
                  !isDelimiterLine(line, expectedCount: headers.count) else {
                break
            }
            // 流式尾行尚未闭合时只消费、不解析、不渲染。这样带/不带前导 pipe 的
            // 表格都不会先冒出半行普通文本，再瞬间重组为 table row。
            if isTrailingPartialLine {
                index += 1
                break
            }
            if includeParsedTableCells {
                let cells = ChatStreamingMarkdownTableRowCache.shared.cells(
                    for: line,
                    expectedCount: headers.count
                ) {
                    normalizedRow(splitTableCells(line), count: headers.count)
                }
                rows.append(cells)
            }
            renderedEndIndex = index + 1
            index += 1
        }
        let consumedLineCount = index - start

        return ChatStreamingMarkdownParsedTable(
            table: ChatStreamingMarkdownTable(
                id: "table-\(start)",
                markdown: lines[start..<renderedEndIndex].joined(separator: "\n"),
                headers: includeParsedTableCells ? normalizedRow(headers, count: headers.count) : [],
                rows: rows
            ),
            consumedLineCount: consumedLineCount
        )
    }

    private static func splitTableCells(_ line: String) -> [String] {
        let characters = Array(line.trimmingCharacters(in: .whitespacesAndNewlines))
        var cells: [String] = []
        var current = ""
        var index = 0
        while index < characters.count {
            let character = characters[index]
            if character == "\\",
               index + 1 < characters.count,
               characters[index + 1] == "|" {
                current.append("|")
                index += 2
                continue
            }
            if character == "|" {
                cells.append(current.trimmingCharacters(in: .whitespacesAndNewlines))
                current = ""
            } else {
                current.append(character)
            }
            index += 1
        }
        cells.append(current.trimmingCharacters(in: .whitespacesAndNewlines))
        if cells.first?.isEmpty == true {
            cells.removeFirst()
        }
        if cells.last?.isEmpty == true {
            cells.removeLast()
        }
        return cells
    }

    private static func normalizedRow(_ cells: [String], count: Int) -> [String] {
        if cells.count == count {
            return cells
        }
        if cells.count > count {
            return Array(cells.prefix(count))
        }
        return cells + Array(repeating: "", count: count - cells.count)
    }

    private static func isDelimiterLine(_ line: String, expectedCount: Int) -> Bool {
        let cells = splitTableCells(line)
        guard cells.count == expectedCount else { return false }
        return cells.allSatisfy { cell in
            let normalized = cell.replacingOccurrences(of: ":", with: "")
            return normalized.count >= 3 && normalized.allSatisfy { $0 == "-" }
        }
    }

    private static func fenceMarker(in line: String) -> Character? {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("```") { return "`" }
        if trimmed.hasPrefix("~~~") { return "~" }
        return nil
    }

    static func tableHeaderColumnCountForDetection(in line: String) -> Int? {
        let count = splitTableCells(line).count
        return count >= 2 ? count : nil
    }

    static func isDelimiterLineForDetection(_ line: String, expectedCount: Int) -> Bool {
        isDelimiterLine(line, expectedCount: expectedCount)
    }

    static func fenceMarkerForDetection(in line: String) -> Character? {
        fenceMarker(in: line)
    }
}

/// 表格/widget 增量探测器的引用盒。
/// `ChatAssistantMarkdownView` 的 body 不读取盒内字段(只读显式的 latch @State),
/// 因此每个 chunk 的增量消费不触发 SwiftUI 失效。新增探测器时保持这个约束。
@MainActor
private final class ChatStreamingDetectionBox {
    struct MarkdownConfigKey: Hashable {
        let liveStreaming: Bool
        let fontScale: Double
        let bodyPointSize: CGFloat
        let chatFont: String
        let themePaper: String
        let themeAccentHex: UInt32
        // 必须是无默认值的 `let`:其余字段都靠"漏传即编译失败"来保证任何新的
        // config 构造点都会被迫接上。给默认值会让漏传的构造点静默复用别人的
        // 缓存条目,同屏出现两种渲染策略且无红灯。
        let coalescedTextBlocks: Bool
    }

    var table: ChatStreamingTableDetectionState
    var widget: IOSGenerativeWidgetPayloadDetector
    /// 渲染 config 记忆化。此前每次 body 求值都重建 config,其中
    /// `UIColor(AmberTheme.xxx)` 每次产生新的 dynamic-provider UIColor 实例,
    /// `isEqual` 恒 false → `MarkdownRenderConfig ==` 每 delta 必假 →
    /// vendor `DocumentView` 的 Equatable 短路每 delta 被击穿,全部表格块
    /// 33Hz 重建+整表重测量(2026-07-10 缩放矩阵 tables24≈284ms/delta 的根因),
    /// 同时 configHash 漂移让 renderable 静态缓存与 `.task(id:)` 全部失效。
    /// 复用同一 config 实例后,动态色仍按 trait 在绘制时解析,明暗模式不受影响。
    private var configCache: [MarkdownConfigKey: SwiftStreamingMarkdown.MarkdownRenderConfig] = [:]

    init(markdown: String) {
        table = ChatStreamingTableDetectionState(text: markdown)
        widget = IOSGenerativeWidgetPayloadDetector(text: markdown)
    }

    func markdownConfig(
        for key: MarkdownConfigKey,
        build: () -> SwiftStreamingMarkdown.MarkdownRenderConfig
    ) -> SwiftStreamingMarkdown.MarkdownRenderConfig {
        if let cached = configCache[key] {
            return cached
        }
        let built = build()
        configCache[key] = built
        // 键随排版/主题变化，旧键不会再被读取；限个上限防设置反复横跳。
        if configCache.count > 6 {
            configCache.removeAll()
            configCache[key] = built
        }
        return built
    }
}

private struct ChatStreamingTableDetectionState {
    private static let checkpointLength = 32

    private var processedUTF8Count = 0
    private var checkpoint: [UInt8] = []
    private var pendingLineBytes: [UInt8] = []
    private var previousHeaderColumnCount: Int?
    private var activeFenceMarker: Character?
    private(set) var containsTable = false
    private(set) var totalConsumedUTF8Count = 0

    init(text: String = "") {
        if !text.isEmpty {
            update(with: text)
        }
    }

    mutating func update(with text: String) {
        guard !containsTable else { return }
        let utf8 = text.utf8
        let count = utf8.count
        guard count != processedUTF8Count || !checkpointMatches(in: utf8) else { return }

        if count < processedUTF8Count || !checkpointMatches(in: utf8) {
            resetParserState()
        }

        let start = utf8.index(utf8.startIndex, offsetBy: processedUTF8Count)
        let appended = utf8[start...]
        totalConsumedUTF8Count += appended.count
        for byte in appended {
            if byte == 0x0A {
                processCompletedLine()
                pendingLineBytes.removeAll(keepingCapacity: true)
            } else {
                pendingLineBytes.append(byte)
            }
            if containsTable { break }
        }
        processedUTF8Count = count
        checkpoint = Array(utf8.suffix(Self.checkpointLength))
        evaluatePendingDelimiter()
    }

    private func checkpointMatches(in utf8: String.UTF8View) -> Bool {
        guard processedUTF8Count > 0 else { return true }
        guard processedUTF8Count <= utf8.count, checkpoint.count <= processedUTF8Count else { return false }
        let startOffset = processedUTF8Count - checkpoint.count
        let start = utf8.index(utf8.startIndex, offsetBy: startOffset)
        let end = utf8.index(start, offsetBy: checkpoint.count)
        return utf8[start..<end].elementsEqual(checkpoint)
    }

    private mutating func resetParserState() {
        processedUTF8Count = 0
        checkpoint.removeAll(keepingCapacity: true)
        pendingLineBytes.removeAll(keepingCapacity: true)
        previousHeaderColumnCount = nil
        activeFenceMarker = nil
        containsTable = false
    }

    private mutating func processCompletedLine() {
        if pendingLineBytes.last == 0x0D {
            pendingLineBytes.removeLast()
        }
        let line = String(decoding: pendingLineBytes, as: UTF8.self)
        if let marker = ChatStreamingMarkdownBlockParser.fenceMarkerForDetection(in: line) {
            if activeFenceMarker == nil {
                activeFenceMarker = marker
            } else if activeFenceMarker == marker {
                activeFenceMarker = nil
            }
            previousHeaderColumnCount = nil
            return
        }
        guard activeFenceMarker == nil else {
            previousHeaderColumnCount = nil
            return
        }
        if let expectedCount = previousHeaderColumnCount,
           ChatStreamingMarkdownBlockParser.isDelimiterLineForDetection(
            line,
            expectedCount: expectedCount
           ) {
            containsTable = true
            return
        }
        previousHeaderColumnCount = ChatStreamingMarkdownBlockParser
            .tableHeaderColumnCountForDetection(in: line)
    }

    private mutating func evaluatePendingDelimiter() {
        guard !containsTable,
              activeFenceMarker == nil,
              let expectedCount = previousHeaderColumnCount else { return }
        let line = String(decoding: pendingLineBytes, as: UTF8.self)
        guard ChatStreamingMarkdownBlockParser.fenceMarkerForDetection(in: line) == nil else { return }
        if ChatStreamingMarkdownBlockParser.isDelimiterLineForDetection(
            line,
            expectedCount: expectedCount
        ) {
            containsTable = true
        }
    }
}

private final class ChatStreamingMarkdownTableRowCache: @unchecked Sendable {
    static let shared = ChatStreamingMarkdownTableRowCache()

    private final class Box {
        let cells: [String]

        init(_ cells: [String]) {
            self.cells = cells
        }
    }

    private let cache = NSCache<NSString, Box>()
#if DEBUG
    private let metricsLock = NSLock()
    private var hitCount = 0
    private var missCount = 0
#endif

    private init() {
        cache.countLimit = 512
    }

    func cells(for line: String, expectedCount: Int, build: () -> [String]) -> [String] {
        let key = "\(expectedCount):\(line)" as NSString
        if let cached = cache.object(forKey: key) {
#if DEBUG
            recordHit()
#endif
            return cached.cells
        }
        let cells = build()
        cache.setObject(Box(cells), forKey: key)
#if DEBUG
        recordMiss()
#endif
        return cells
    }

#if DEBUG
    private func recordHit() {
        metricsLock.lock()
        hitCount += 1
        metricsLock.unlock()
    }

    private func recordMiss() {
        metricsLock.lock()
        missCount += 1
        metricsLock.unlock()
    }

    func resetForTesting() {
        cache.removeAllObjects()
        metricsLock.lock()
        hitCount = 0
        missCount = 0
        metricsLock.unlock()
    }

    var metricsForTesting: (hits: Int, misses: Int) {
        metricsLock.lock()
        defer { metricsLock.unlock() }
        return (hitCount, missCount)
    }
#endif
}

#if DEBUG
struct ChatStreamingMarkdownBlockParserTestBlock: Equatable {
    let kind: String
    let text: String
    let markdown: String
    let headers: [String]
    let rows: [[String]]
}

enum ChatStreamingMarkdownBlockParserTestSupport {
    static func containsTable(in text: String) -> Bool {
        ChatStreamingMarkdownBlockParser.containsTable(in: text)
    }

    static func blocks(
        in text: String,
        includeTrailingPartialTableRow: Bool
    ) -> [ChatStreamingMarkdownBlockParserTestBlock] {
        ChatStreamingMarkdownBlockParser.blocks(
            in: text,
            includeTrailingPartialTableRow: includeTrailingPartialTableRow,
            includeParsedTableCells: true
        ).map { block in
            switch block.kind {
            case .text(let content):
                return ChatStreamingMarkdownBlockParserTestBlock(
                    kind: "text",
                    text: content,
                    markdown: content,
                    headers: [],
                    rows: []
                )
            case .table(let table):
                return ChatStreamingMarkdownBlockParserTestBlock(
                    kind: "table",
                    text: "",
                    markdown: table.markdown,
                    headers: table.headers,
                    rows: table.rows
                )
            }
        }
    }

    static func productionTableCellCounts(in text: String) -> (headers: Int, rows: Int)? {
        for block in ChatStreamingMarkdownBlockParser.blocks(in: text) {
            if case .table(let table) = block.kind {
                return (table.headers.count, table.rows.count)
            }
        }
        return nil
    }

    static func resetRowCache() {
        ChatStreamingMarkdownTableRowCache.shared.resetForTesting()
    }

    static var rowCacheMetrics: (hits: Int, misses: Int) {
        ChatStreamingMarkdownTableRowCache.shared.metricsForTesting
    }
}

@MainActor
enum ChatStreamingMarkdownConfigCacheTestSupport {
    static func buildCount(themeKeys: [(paper: String, accentHex: UInt32)]) -> Int {
        let detection = ChatStreamingDetectionBox(markdown: "")
        var buildCount = 0
        for theme in themeKeys {
            _ = detection.markdownConfig(
                for: ChatStreamingDetectionBox.MarkdownConfigKey(
                    liveStreaming: true,
                    fontScale: 1,
                    bodyPointSize: 17,
                    chatFont: IOSChatFont.default.rawValue,
                    themePaper: theme.paper,
                    themeAccentHex: theme.accentHex,
                    coalescedTextBlocks: false
                ),
                build: {
                    buildCount += 1
                    return SwiftStreamingMarkdown.MarkdownRenderConfig.default
                }
            )
        }
        return buildCount
    }
}

enum ChatStreamingTableDetectionTestSupport {
    static func replay(_ texts: [String]) -> (containsTable: Bool, consumedUTF8Count: Int) {
        var detector = ChatStreamingTableDetectionState()
        for text in texts {
            detector.update(with: text)
        }
        return (detector.containsTable, detector.totalConsumedUTF8Count)
    }
}

#endif

private struct ChatStreamingMarkdownTableView: View {
    let table: ChatStreamingMarkdownTable

    @AppStorage(IOSDisplayPreferenceKeys.fontScale) private var fontScale = 1.0
    @AppStorage(IOSDisplayPreferenceKeys.chatFont) private var chatFont = IOSChatFont.default.rawValue
    @ScaledMetric(relativeTo: .body) private var scaledBodyPointSize: CGFloat = 17

    private var boundedScale: Double {
        min(max(fontScale, 0.88), 1.25)
    }

    private var columnWidth: CGFloat {
        let scale = CGFloat(boundedScale)
        switch table.headers.count {
        case 0...2:
            return 168 * scale
        case 3:
            return 142 * scale
        default:
            return 124 * scale
        }
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                ChatStreamingMarkdownTableRowView(
                    cells: table.headers,
                    isHeader: true,
                    isLastRow: table.rows.isEmpty,
                    columnWidth: columnWidth,
                    fontScale: boundedScale,
                    bodyPointSize: scaledBodyPointSize,
                    chatFontRawValue: chatFont
                )
                .equatable()
                ForEach(Array(table.rows.enumerated()), id: \.offset) { index, row in
                    ChatStreamingMarkdownTableRowView(
                        cells: normalizedRow(row),
                        isHeader: false,
                        isLastRow: index == table.rows.count - 1,
                        columnWidth: columnWidth,
                        fontScale: boundedScale,
                        bodyPointSize: scaledBodyPointSize,
                        chatFontRawValue: chatFont
                    )
                    .equatable()
                }
            }
            .background(AmberTheme.background)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(AmberTheme.border, lineWidth: 0.6)
            }
        }
        .scrollIndicators(.hidden)
    }

    private func normalizedRow(_ cells: [String]) -> [String] {
        if cells.count == table.headers.count {
            return cells
        }
        if cells.count > table.headers.count {
            return Array(cells.prefix(table.headers.count))
        }
        return cells + Array(repeating: "", count: table.headers.count - cells.count)
    }
}

private struct ChatStreamingMarkdownTableRowView: View, Equatable {
    let cells: [String]
    let isHeader: Bool
    let isLastRow: Bool
    let columnWidth: CGFloat
    let fontScale: Double
    let bodyPointSize: CGFloat
    let chatFontRawValue: String

    private var selectedFont: IOSChatFont {
        IOSChatFont(rawValue: chatFontRawValue) ?? .default
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(Array(cells.enumerated()), id: \.offset) { index, cell in
                tableCell(cell, isHeader: isHeader, isLastColumn: index == cells.count - 1)
            }
        }
        .background(isHeader ? AmberTheme.surface2 : Color.clear)
        .overlay(alignment: .bottom) {
            if !isLastRow {
                Rectangle()
                    .fill(AmberTheme.border)
                    .frame(height: 0.6)
            }
        }
    }

    private func tableCell(_ rawText: String, isHeader: Bool, isLastColumn: Bool) -> some View {
        Text(inlineDisplayText(rawText))
            .font(.system(
                size: bodyPointSize * (isHeader ? 16 / 17 : 16.5 / 17) * fontScale,
                weight: isHeader ? .semibold : .regular,
                design: selectedFont.design
            ))
            .foregroundStyle(AmberTheme.foreground)
            .lineSpacing(3 * fontScale)
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .frame(width: columnWidth, alignment: .topLeading)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .overlay(alignment: .trailing) {
                if !isLastColumn {
                    Rectangle()
                        .fill(AmberTheme.border)
                        .frame(width: 0.6)
                }
            }
    }

    private func inlineDisplayText(_ text: String) -> String {
        text
            .replacingOccurrences(of: #"**"#, with: "")
            .replacingOccurrences(of: #"`"#, with: "")
            .replacingOccurrences(of: #"<br>"#, with: "\n")
            .replacingOccurrences(of: #"<br/>"#, with: "\n")
            .replacingOccurrences(of: #"<br />"#, with: "\n")
    }
}

private struct ChatStableStreamingMarkdownView: View {
    let text: String
    let config: SwiftStreamingMarkdown.MarkdownRenderConfig
    var cacheIdentity: String? = nil
    @StateObject private var controller = ChatStableStreamingMarkdownController()

    var body: some View {
        let resolution = controller.resolution(
            for: text,
            config: config,
            cacheIdentity: cacheIdentity
        )
        // 占位按空行拆段：空行不再按整行文本高度渲染，段间距由 BlockView 的
        // blockSpacing 提供，使冷行首帧高度贴近异步解析后的真实高度，
        // 消除历史行首次实例化时"占位偏高→解析后整章收缩"的可见位移。
        let renderable = resolution.renderable
            ?? SwiftStreamingMarkdown.RenderableDocument(
                plainText: text,
                id: "0",
                config: config,
                splittingParagraphsOnBlankLines: true
            )
        SwiftStreamingMarkdown.DocumentView(
            renderableDocument: renderable,
            config: config,
            animateInitialText: !resolution.suppressesInitialFade,
            usesLayerBackedTableAnimation: config.shouldAnimateText,
            usesTextKit1ForAttachmentFreeText: true
        )
            .task(id: ChatStableStreamingMarkdownParseKey(
                text: text,
                animate: config.shouldAnimateText,
                configHash: config.hashValue,
                cacheIdentity: cacheIdentity
            )) {
                controller.scheduleParse(text: text, config: config, cacheIdentity: cacheIdentity)
            }
    }
}

private struct ChatStableStreamingMarkdownParseKey: Equatable {
    let text: String
    let animate: Bool
    let configHash: Int
    let cacheIdentity: String?
}

@MainActor
private final class ChatStableStreamingMarkdownController: ObservableObject {
    private struct RenderSignature: Hashable {
        let visualConfigHash: Int
        let speculative: Bool
    }

    private struct RenderableCacheKey: Hashable {
        let text: String
        let signature: RenderSignature
    }

    private struct IdentityCacheKey: Hashable {
        let identity: String
        let visualConfigHash: Int
    }

    private struct IdentityCacheEntry {
        let text: String
        let renderable: SwiftStreamingMarkdown.RenderableDocument
    }

    private static var renderableCache: [RenderableCacheKey: SwiftStreamingMarkdown.RenderableDocument] = [:]
    private static var renderableCacheOrder: [RenderableCacheKey] = []
    private static let renderableCacheLimit = 24
    private static var identityCache: [IdentityCacheKey: IdentityCacheEntry] = [:]
    private static var identityCacheOrder: [IdentityCacheKey] = []
    // 长篇(小说/长对话)可有上百个段落 block。完成态重挂载/LOD 翻转时,只要 identity
    // 缓存还持有该段落的 renderable,resolution 就返回 suppressesInitialFade=true,不再
    // 重新淡入(闪烁)。64 会让靠前的段落在后续解析中被挤出 → 完成时重新淡入;提到 256
    // 让整篇已完成段落基本都能命中,消除完成瞬间的整屏闪烁。
    private static let identityCacheLimit = 256

    @Published private(set) var revision = 0
    private var renderedText: String?
    private var renderableDocument: SwiftStreamingMarkdown.RenderableDocument?
    private var renderedSignature: RenderSignature?
    private var pendingParse: (
        text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig,
        cacheIdentity: String?
    )?
    private var parseTask: Task<Void, Never>?
    private var parseTaskGeneration: UInt64 = 0
    private var lastLiveParseAt = Date.distantPast

    deinit {
        parseTask?.cancel()
    }

    func renderable(
        for text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig
    ) -> SwiftStreamingMarkdown.RenderableDocument? {
        resolution(for: text, config: config, cacheIdentity: nil).renderable
    }

    func resolution(
        for text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig,
        cacheIdentity: String?
    ) -> (renderable: SwiftStreamingMarkdown.RenderableDocument?, suppressesInitialFade: Bool) {
        let signature = Self.renderSignature(for: config)
        // 完全匹配:返回最新解析结果。utf16.count 先行短路,避免流式期每次
        // body 求值都对不等长文本做 O(n) 字符串比较。
        if let renderedSignature,
           renderedSignature.visualConfigHash == signature.visualConfigHash,
           let rendered = renderedText,
           rendered.utf16.count == text.utf16.count,
           rendered == text {
            return (
                renderableDocument,
                renderedSignature.speculative != signature.speculative
            )
        }
        // 解析落后于最新 delta:返回上一次成功解析的结果(对应稍旧的文本前缀),
        // 让已格式化的内容持续显示,新增尾部文本会在下次解析完成后补上。
        // 之前用严格相等导致高速 delta 下 renderedText 永远落后于 text,
        // 恒返回 nil → 退回纯文本 fallback(用户看不到表格/代码块等格式)。
        //
        // 顺序说明:本实例的 stale-prefix 命中放在静态缓存之前——流式期几乎
        // 每次都命中这条,静态缓存查询(全文 Hasher + 最多 12 次 hasPrefix,
        // 32KB 实测 ~0.2ms)只留给实例重建/LOD 翻转等冷路径。追加式流式下
        // 静态缓存不可能持有比本实例更新的精确条目(旧条目都是当前文本的前缀)。
        if renderedSignature == signature,
           signature.speculative,
           let renderedText,
           !renderedText.isEmpty,
           text.utf16.count >= renderedText.utf16.count,
           text.hasPrefix(renderedText) {
            return (renderableDocument, false)
        }
        if let cacheIdentity,
           let cached = Self.cachedIdentityRenderable(
            for: cacheIdentity,
            text: text,
            signature: signature
           ) {
            return (cached, true)
        }
        if let cached = Self.cachedRenderable(for: text, config: config) {
            return (cached, true)
        }
        return (nil, false)
    }

    func scheduleParse(
        text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig,
        cacheIdentity: String? = nil
    ) {
        if !config.shouldAnimateText {
            pendingParse = nil
            parseTaskGeneration &+= 1
            let generation = parseTaskGeneration
            parseTask?.cancel()
            parseTask = Task { @MainActor [weak self] in
                guard let self else { return }
                await self.parseNow(text: text, config: config, cacheIdentity: cacheIdentity)
                guard self.parseTaskGeneration == generation else { return }
                self.parseTask = nil
                self.startPendingAnimatedParseIfNeeded()
            }
            return
        }

        pendingParse = (text, config, cacheIdentity)
        startPendingAnimatedParseIfNeeded()
    }

    private func startPendingAnimatedParseIfNeeded() {
        guard parseTask == nil, pendingParse != nil else { return }
        parseTaskGeneration &+= 1
        let generation = parseTaskGeneration
        parseTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.parseTaskGeneration == generation {
                    self.parseTask = nil
                    self.startPendingAnimatedParseIfNeeded()
                }
            }
            while !Task.isCancelled {
                guard let next = self.pendingParse else { return }
                self.pendingParse = nil

                let elapsed = Date().timeIntervalSince(self.lastLiveParseAt)
                let liveParseInterval = self.liveParseInterval(for: next.text)
                if elapsed < liveParseInterval {
                    let delay = UInt64((liveParseInterval - elapsed) * 1_000_000_000)
                    do {
                        try await Task.sleep(nanoseconds: delay)
                    } catch {
                        return
                    }
                    guard !Task.isCancelled else { return }
                }

                self.lastLiveParseAt = Date()
                await self.parseNow(
                    text: next.text,
                    config: next.config,
                    cacheIdentity: next.cacheIdentity
                )
            }
        }
    }

    private func liveParseInterval(for text: String) -> TimeInterval {
        Self.liveParseInterval(
            utf16Length: text.utf16.count,
            isTable: ChatStreamingMarkdownBlockParser.startsWithTable(in: text)
        )
    }

    /// 表格越大发布越慢,降低整表布局产生 79ms 级主线程卡顿的频率(P1-5 止血)。
    /// 根因(增量表格布局)在 vendor 侧,这里只是频域降频;<12K 档位保持原值,
    /// 只给超大表格新增 0.5s 档。
    static func liveParseInterval(utf16Length length: Int, isTable: Bool) -> TimeInterval {
        // 普通文本已经经过 coordinator 的 48ms snapshot gate。这里不能再加
        // 独立定时门：两个窗口错相时会把可见高度更新合并到约 132ms，表现为
        // 累计数行后整段上跳。解析仍由 single-flight/latest-wins 自然背压。
        guard isTable else { return 0 }
        if length < 1_200 {
            return 0.12
        }
        if length < 4_000 {
            return 0.20
        }
        if length < 12_000 {
            return 0.32
        }
        return 0.5
    }

    private func parseNow(
        text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig,
        cacheIdentity: String?
    ) async {
        // S3: 流式场景(shouldAnimateText=true)启用 speculativeRewrite,
        // 让 PartialTableMarkupPostParsingRewriter/PartialStrongMarkupPostParsingRewriter 生效,
        // 半截表格/未闭合强调不会渲染成乱码,而是降级成段落,等后续 delta 补齐再升级。
        let animate = config.shouldAnimateText
        let signature = Self.renderSignature(for: config)
        let previousText = renderedText
        let previousRenderable = renderedSignature?.visualConfigHash == signature.visualConfigHash
            ? renderableDocument
            : nil
        let renderable = await Task.detached(priority: .userInitiated) {
            let parser = SwiftStreamingMarkdown.MarkdownParserImpl()
            let option = SwiftStreamingMarkdown.MarkdownParseOption(speculativeRewrite: animate)
            let result = await ChatPerfTrace.measure("MarkdownParse", count: { text.utf16.count }) {
                await parser.parse(text: text, option: option)
            }
            let converted = await ChatPerfTrace.measure("MarkdownConvert", count: { text.utf16.count }) {
                await SwiftStreamingMarkdown.RenderableDocument(
                    document: result.document,
                    config: config
                )
            }
            guard let previousText,
                  let previousRenderable,
                  text.utf16.count >= previousText.utf16.count,
                  text.hasPrefix(previousText) else { return converted }
            return converted.reusingUnchangedPrefix(from: previousRenderable)
        }.value
        guard !Task.isCancelled else { return }
        guard renderedText != text || renderedSignature != signature || renderableDocument != renderable else {
            return
        }
        ChatPerfTrace.measure("MarkdownPublish", count: { text.utf16.count }) {
            renderedText = text
            renderableDocument = renderable
            renderedSignature = signature
            Self.storeCachedRenderable(renderable, for: text, config: config)
            if let cacheIdentity {
                Self.storeIdentityRenderable(
                    renderable,
                    for: cacheIdentity,
                    text: text,
                    signature: signature
                )
            }
            revision &+= 1
        }
    }

    private static func cachedRenderable(
        for text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig
    ) -> SwiftStreamingMarkdown.RenderableDocument? {
        let signature = renderSignature(for: config)
        let exactKey = RenderableCacheKey(
            text: text,
            signature: signature
        )
        if let exact = renderableCache[exactKey] {
            // 命中移到队尾(LRU):多界面并发流式时,静态缓存按纯插入序驱逐会把
            // 仍在活跃复用的条目挤出,复出成本是整段全文重解析。
            if let index = renderableCacheOrder.lastIndex(of: exactKey) {
                renderableCacheOrder.remove(at: index)
                renderableCacheOrder.append(exactKey)
            }
            return exact
        }
        guard signature.speculative else { return nil }
        return renderableCacheOrder.reversed().lazy.compactMap { key -> SwiftStreamingMarkdown.RenderableDocument? in
            guard key.signature == signature,
                  text.hasPrefix(key.text),
                  !key.text.isEmpty else { return nil }
            return renderableCache[key]
        }.first
    }

    private static func storeCachedRenderable(
        _ renderable: SwiftStreamingMarkdown.RenderableDocument,
        for text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig
    ) {
        let key = RenderableCacheKey(
            text: text,
            signature: renderSignature(for: config)
        )
        if renderableCache[key] == nil {
            renderableCacheOrder.append(key)
        }
        renderableCache[key] = renderable
        while renderableCacheOrder.count > renderableCacheLimit {
            let removed = renderableCacheOrder.removeFirst()
            renderableCache.removeValue(forKey: removed)
        }
    }

    private static func cachedIdentityRenderable(
        for identity: String,
        text: String,
        signature: RenderSignature
    ) -> SwiftStreamingMarkdown.RenderableDocument? {
        let key = IdentityCacheKey(identity: identity, visualConfigHash: signature.visualConfigHash)
        guard let entry = identityCache[key] else { return nil }
        if entry.text.utf16.count == text.utf16.count, entry.text == text {
            return entry.renderable
        }
        guard signature.speculative,
              !entry.text.isEmpty,
              text.utf16.count >= entry.text.utf16.count,
              text.hasPrefix(entry.text) else {
            return nil
        }
        return entry.renderable
    }

    private static func storeIdentityRenderable(
        _ renderable: SwiftStreamingMarkdown.RenderableDocument,
        for identity: String,
        text: String,
        signature: RenderSignature
    ) {
        let key = IdentityCacheKey(identity: identity, visualConfigHash: signature.visualConfigHash)
        if identityCache[key] != nil {
            identityCacheOrder.removeAll { $0 == key }
        }
        identityCache[key] = IdentityCacheEntry(text: text, renderable: renderable)
        identityCacheOrder.append(key)
        while identityCacheOrder.count > identityCacheLimit {
            let removed = identityCacheOrder.removeFirst()
            identityCache.removeValue(forKey: removed)
        }
    }

    private static func renderSignature(
        for config: SwiftStreamingMarkdown.MarkdownRenderConfig
    ) -> RenderSignature {
        RenderSignature(
            visualConfigHash: visualConfigHash(for: config),
            speculative: config.shouldAnimateText
        )
    }

    private static func visualConfigHash(for config: SwiftStreamingMarkdown.MarkdownRenderConfig) -> Int {
        config.withShouldAnimateText(value: false).hashValue
    }

#if DEBUG
    var renderedTextForTesting: String? {
        renderedText
    }

    func seedRenderableForTesting(
        text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig,
        cacheIdentity: String? = nil
    ) {
        renderedText = text
        let renderable = SwiftStreamingMarkdown.RenderableDocument(plainText: text, config: config)
        let signature = Self.renderSignature(for: config)
        renderableDocument = renderable
        renderedSignature = signature
        if let cacheIdentity {
            Self.storeIdentityRenderable(
                renderable,
                for: cacheIdentity,
                text: text,
                signature: signature
            )
        }
    }

    static func resetRenderableCacheForTesting() {
        renderableCache.removeAll()
        renderableCacheOrder.removeAll()
        identityCache.removeAll()
        identityCacheOrder.removeAll()
    }

    static func storeRenderableForTesting(
        text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig
    ) {
        storeCachedRenderable(
            SwiftStreamingMarkdown.RenderableDocument(plainText: text, config: config),
            for: text,
            config: config
        )
    }

    static func hasCachedRenderableForTesting(
        text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig
    ) -> Bool {
        cachedRenderable(for: text, config: config) != nil
    }

    static func storeIdentityRenderableForTesting(
        identity: String,
        text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig
    ) {
        storeIdentityRenderable(
            SwiftStreamingMarkdown.RenderableDocument(plainText: text, config: config),
            for: identity,
            text: text,
            signature: renderSignature(for: config)
        )
    }

    static func hasCachedIdentityRenderableForTesting(
        identity: String,
        text: String,
        config: SwiftStreamingMarkdown.MarkdownRenderConfig
    ) -> Bool {
        cachedIdentityRenderable(
            for: identity,
            text: text,
            signature: renderSignature(for: config)
        ) != nil
    }
#endif
}

#if DEBUG
@MainActor
enum ChatStableStreamingMarkdownCacheTestSupport {
    static func reset() {
        ChatStableStreamingMarkdownController.resetRenderableCacheForTesting()
    }

    static func store(text: String, animate: Bool) {
        ChatStableStreamingMarkdownController.storeRenderableForTesting(
            text: text,
            config: SwiftStreamingMarkdown.MarkdownRenderConfig.default.withShouldAnimateText(value: animate)
        )
    }

    static func hasCachedRenderable(text: String, animate: Bool) -> Bool {
        ChatStableStreamingMarkdownController.hasCachedRenderableForTesting(
            text: text,
            config: SwiftStreamingMarkdown.MarkdownRenderConfig.default.withShouldAnimateText(value: animate)
        )
    }

    static func storeIdentity(identity: String, text: String, animate: Bool) {
        ChatStableStreamingMarkdownController.storeIdentityRenderableForTesting(
            identity: identity,
            text: text,
            config: SwiftStreamingMarkdown.MarkdownRenderConfig.default.withShouldAnimateText(value: animate)
        )
    }

    static func hasCachedIdentity(identity: String, text: String, animate: Bool) -> Bool {
        ChatStableStreamingMarkdownController.hasCachedIdentityRenderableForTesting(
            identity: identity,
            text: text,
            config: SwiftStreamingMarkdown.MarkdownRenderConfig.default.withShouldAnimateText(value: animate)
        )
    }
}

@MainActor
enum ChatStreamingMarkdownThrottleTestSupport {
    static func liveParseInterval(utf16Length: Int, isTable: Bool) -> TimeInterval {
        ChatStableStreamingMarkdownController.liveParseInterval(utf16Length: utf16Length, isTable: isTable)
    }

    static func blockPublishInterval(utf16Length: Int, isTableTail: Bool) -> TimeInterval {
        ChatStreamingMarkdownBlockController.publishInterval(utf16Length: utf16Length, isTableTail: isTableTail)
    }
}

@MainActor
enum ChatStableStreamingMarkdownControllerTestSupport {
    static func renderedTextAfterNonAnimatedThenAnimatedParse() async -> String? {
        let controller = ChatStableStreamingMarkdownController()
        let initialText = "initial completed text"
        let updatedText = "initial completed text with live delta"

        controller.scheduleParse(
            text: initialText,
            config: SwiftStreamingMarkdown.MarkdownRenderConfig.default.withShouldAnimateText(value: false)
        )
        guard await waitUntilRendered(initialText, by: controller) else {
            return controller.renderedTextForTesting
        }

        controller.scheduleParse(
            text: updatedText,
            config: SwiftStreamingMarkdown.MarkdownRenderConfig.default.withShouldAnimateText(value: true)
        )
        _ = await waitUntilRendered(updatedText, by: controller)
        return controller.renderedTextForTesting
    }

    static func renderedTextWhenAnimatedParseArrivesDuringNonAnimatedParse() async -> String? {
        let controller = ChatStableStreamingMarkdownController()
        let updatedText = "non-animated parse followed immediately by live delta"

        controller.scheduleParse(
            text: "non-animated parse",
            config: SwiftStreamingMarkdown.MarkdownRenderConfig.default.withShouldAnimateText(value: false)
        )
        controller.scheduleParse(
            text: updatedText,
            config: SwiftStreamingMarkdown.MarkdownRenderConfig.default.withShouldAnimateText(value: true)
        )

        _ = await waitUntilRendered(updatedText, by: controller)
        return controller.renderedTextForTesting
    }

    static func hasStaleRenderable(
        renderedText: String,
        requestedText: String
    ) -> Bool {
        ChatStableStreamingMarkdownController.resetRenderableCacheForTesting()
        let controller = ChatStableStreamingMarkdownController()
        let config = SwiftStreamingMarkdown.MarkdownRenderConfig.default
            .withShouldAnimateText(value: true)
        controller.seedRenderableForTesting(text: renderedText, config: config)
        return controller.renderable(for: requestedText, config: config) != nil
    }

    static func instanceResolutionAfterSpeculativeModeChange() -> (
        hasRenderable: Bool,
        suppressesInitialFade: Bool
    ) {
        ChatStableStreamingMarkdownController.resetRenderableCacheForTesting()
        let controller = ChatStableStreamingMarkdownController()
        let streamingConfig = SwiftStreamingMarkdown.MarkdownRenderConfig.default
            .withShouldAnimateText(value: true)
        let completedConfig = streamingConfig.withShouldAnimateText(value: false)
        controller.seedRenderableForTesting(text: "same text", config: streamingConfig)
        let resolution = controller.resolution(
            for: "same text",
            config: completedConfig,
            cacheIdentity: nil
        )
        return (resolution.renderable != nil, resolution.suppressesInitialFade)
    }

    /// 跨 speculative 模式复用是刻意取舍：完成瞬间即使文本停在未闭合语法
    /// （中断/超时终止），也先复用流式 renderable 保持画面连续，随后由
    /// scheduleParse 的非动画分支立即重解析纠正。此函数把该取舍固化为契约。
    static func instanceResolutionAfterSpeculativeModeChangeWithUnclosedMarkup() -> (
        hasRenderable: Bool,
        suppressesInitialFade: Bool
    ) {
        ChatStableStreamingMarkdownController.resetRenderableCacheForTesting()
        let controller = ChatStableStreamingMarkdownController()
        let streamingConfig = SwiftStreamingMarkdown.MarkdownRenderConfig.default
            .withShouldAnimateText(value: true)
        let unclosedText = "结尾停在未闭合的 **强调 与半截表格\n| 列一 | 列二 |\n|---|---|\n| 单元格 |"
        controller.seedRenderableForTesting(text: unclosedText, config: streamingConfig)
        let resolution = controller.resolution(
            for: unclosedText,
            config: streamingConfig.withShouldAnimateText(value: false),
            cacheIdentity: nil
        )
        return (resolution.renderable != nil, resolution.suppressesInitialFade)
    }

    static func coldCompletionIdentityResolution() -> (
        hasRenderable: Bool,
        suppressesInitialFade: Bool
    ) {
        ChatStableStreamingMarkdownController.resetRenderableCacheForTesting()
        let streamingConfig = SwiftStreamingMarkdown.MarkdownRenderConfig.default
            .withShouldAnimateText(value: true)
        let cacheIdentity = "message:text-0"
        let firstController = ChatStableStreamingMarkdownController()
        firstController.seedRenderableForTesting(
            text: "completed text",
            config: streamingConfig,
            cacheIdentity: cacheIdentity
        )

        let completedController = ChatStableStreamingMarkdownController()
        let resolution = completedController.resolution(
            for: "completed text",
            config: streamingConfig.withShouldAnimateText(value: false),
            cacheIdentity: cacheIdentity
        )
        return (resolution.renderable != nil, resolution.suppressesInitialFade)
    }

    static func hasInstanceRenderableAfterVisualConfigChange() -> Bool {
        ChatStableStreamingMarkdownController.resetRenderableCacheForTesting()
        let controller = ChatStableStreamingMarkdownController()
        let initialConfig = SwiftStreamingMarkdown.MarkdownRenderConfig.default
            .withShouldAnimateText(value: true)
        let changedConfig = initialConfig.withParagraphLineSpacing(value: 9)
        controller.seedRenderableForTesting(text: "same text", config: initialConfig)
        return controller.renderable(for: "same text", config: changedConfig) != nil
    }

    static func coldReentryIdentityPrefixResolution() -> (
        hasRenderable: Bool,
        suppressesInitialFade: Bool
    ) {
        ChatStableStreamingMarkdownController.resetRenderableCacheForTesting()
        let config = SwiftStreamingMarkdown.MarkdownRenderConfig.default
            .withShouldAnimateText(value: true)
        let cacheIdentity = "message:text-0"
        let firstController = ChatStableStreamingMarkdownController()
        firstController.seedRenderableForTesting(
            text: "already rendered prefix",
            config: config,
            cacheIdentity: cacheIdentity
        )

        let reenteredController = ChatStableStreamingMarkdownController()
        let resolution = reenteredController.resolution(
            for: "already rendered prefix with new delta",
            config: config,
            cacheIdentity: cacheIdentity
        )
        return (resolution.renderable != nil, resolution.suppressesInitialFade)
    }

    private static func waitUntilRendered(
        _ expectedText: String,
        by controller: ChatStableStreamingMarkdownController
    ) async -> Bool {
        for _ in 0..<200 {
            if controller.renderedTextForTesting == expectedText {
                return true
            }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return false
    }
}
#endif

private struct ChatSelfSizingInvalidationBridge: UIViewRepresentable {
    let token: Int

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isHidden = true
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        guard token != context.coordinator.lastToken else { return }
        context.coordinator.lastToken = token
        DispatchQueue.main.async { [weak view] in
            guard let view,
                  let cell = view.chatAncestor(of: UICollectionViewCell.self),
                  let collectionView = cell.chatAncestor(of: UICollectionView.self) else { return }
            cell.setNeedsLayout()
            cell.contentView.setNeedsLayout()
            cell.layoutIfNeeded()
            cell.contentView.layoutIfNeeded()
            if let indexPath = collectionView.indexPath(for: cell) {
                let attributes = (
                    collectionView.layoutAttributesForItem(at: indexPath)?
                        .copy() as? UICollectionViewLayoutAttributes
                ) ?? UICollectionViewLayoutAttributes(forCellWith: indexPath)
                let measuredWidth = cell.bounds.width > 1 ? cell.bounds.width : collectionView.bounds.width
                attributes.size.width = measuredWidth
                _ = cell.preferredLayoutAttributesFitting(attributes)
                let context = collectionView.collectionViewLayout
                    .invalidationContext(forBoundsChange: collectionView.bounds)
                context.invalidateItems(at: [indexPath])
                collectionView.collectionViewLayout.invalidateLayout(with: context)
            } else {
                collectionView.collectionViewLayout.invalidateLayout()
            }
            collectionView.setNeedsLayout()
        }
    }

    final class Coordinator {
        var lastToken = -1
    }
}

private extension UIView {
    func chatAncestor<T: UIView>(of type: T.Type) -> T? {
        var current = superview
        while let view = current {
            if let match = view as? T {
                return match
            }
            current = view.superview
        }
        return nil
    }
}

private extension UIFont {
    /// 给已有字体叠加斜体符号特征,用于构造流式渲染器排版对齐所需的 boldItalic 变体。
    func withItalicTrait() -> UIFont {
        let traits = fontDescriptor.symbolicTraits.union(.traitItalic)
        guard let descriptor = fontDescriptor.withSymbolicTraits(traits) else {
            return self
        }
        return UIFont(descriptor: descriptor, size: pointSize)
    }
}

private enum WorkspaceSaveAlert: Identifiable {
    case saved
    case failed(String)

    var id: String {
        switch self {
        case .saved: "saved"
        case .failed(let message): "failed-\(message)"
        }
    }

    var title: String {
        switch self {
        case .saved: "已保存"
        case .failed: "保存失败"
        }
    }

    var message: String {
        switch self {
        case .saved:
            "助手回复已保存到 Workspace。"
        case .failed(let message):
            message
        }
    }
}

private struct ChatGeneratedImageGrid: View {
    let images: [UIMessagePart.Image]
    var toolInput: String?
    var onModify: (String, String, String) -> Void = { _, _, _ in }

    private var display: ChatGeneratedImageRequestDisplay {
        ChatGeneratedImageRequestDisplay(toolInput: toolInput)
    }

    var body: some View {
        Group {
            if images.count <= 1 {
                if let image = images.first {
                    singleCard {
                        ChatGeneratedImageTile(
                            urlString: image.url,
                            display: display,
                            onModify: onModify
                        )
                    }
                }
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(images.enumerated()), id: \.offset) { _, image in
                            ChatGeneratedImageTile(
                                urlString: image.url,
                                display: display,
                                onModify: onModify
                            )
                                .frame(width: display.multiCardWidth)
                        }
                    }
                    .padding(.trailing, 16)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func singleCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        if let maxWidth = display.singleCardMaxWidth {
            content().frame(maxWidth: maxWidth, alignment: .leading)
        } else {
            content().frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct ChatGeneratedImageLoadingPlaceholder: View {
    let toolInput: String

    private var display: ChatGeneratedImageRequestDisplay {
        ChatGeneratedImageRequestDisplay(toolInput: toolInput)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            placeholder
        }
        .padding(.top, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var placeholder: some View {
        if display.requestedCount > 1 {
            LazyVGrid(
                columns: [GridItem(.flexible()), GridItem(.flexible())],
                spacing: 8
            ) {
                ForEach(0..<display.requestedCount, id: \.self) { _ in
                    ChatGeneratedImageDotPlaceholder(aspectRatio: display.aspectRatio)
                }
            }
            .frame(maxWidth: display.multiCardWidth, alignment: .leading)
        } else if let maxWidth = display.singleCardMaxWidth {
            ChatGeneratedImageDotPlaceholder(aspectRatio: display.aspectRatio)
                .frame(maxWidth: maxWidth, alignment: .leading)
        } else {
            ChatGeneratedImageDotPlaceholder(aspectRatio: display.aspectRatio)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct ChatGeneratedImageFailureCard: View {
    let reason: String

    var body: some View {
        HStack {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(width: 18, height: 18)

                Text(reason)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.foreground2)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
            .background(AmberTheme.accentRed.opacity(0.08), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(AmberTheme.accentRed.opacity(0.20), lineWidth: 0.5)
            }
        }
        .padding(.top, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ChatGeneratedImageRequestDisplay {
    var aspectRatio: CGFloat
    var aspectRatioTitle: String
    var requestedCount: Int

    var singleCardMaxWidth: CGFloat? {
        aspectRatio < 1 ? 236 : nil
    }

    let multiCardWidth: CGFloat = 280

    init(toolInput: String?) {
        let object = Self.jsonObject(from: toolInput)
        let parsedAspect = IOSImageAspectRatio(toolValue: object?["aspect_ratio"] as? String)
        self.aspectRatio = CGFloat(parsedAspect.renderedAspectRatio)
        self.aspectRatioTitle = parsedAspect.title
        self.requestedCount = min(max(Self.intValue(object?["count"]) ?? 1, 1), 4)
    }

    private static func intValue(_ value: Any?) -> Int? {
        if let int = value as? Int { return int }
        if let number = value as? NSNumber { return number.intValue }
        if let string = value as? String { return Int(string) }
        return nil
    }

    private static func jsonObject(from input: String?) -> [String: Any]? {
        guard let input else { return nil }
        guard let data = input.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

}

private struct ChatGeneratedImageDotPlaceholder: View {
    let aspectRatio: CGFloat

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let period: TimeInterval = 2.2

    var body: some View {
        Group {
            if reduceMotion {
                Canvas { context, size in
                    drawDots(context: context, size: size, phase: 0.28)
                }
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
                    Canvas { context, size in
                        drawDots(context: context, size: size, phase: phase(at: timeline.date))
                    }
                }
            }
        }
        .modifier(ChatWidthDrivenAspectRatio(aspectRatio: aspectRatio))
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func phase(at date: Date) -> CGFloat {
        CGFloat(date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period)
    }

    private func drawDots(context: GraphicsContext, size: CGSize, phase: CGFloat) {
        let padding: CGFloat = 20
        let spacing: CGFloat = 14
        let dotRadius: CGFloat = 1.6
        let drawingWidth = max(size.width - padding * 2, spacing)
        let drawingHeight = max(size.height - padding * 2, spacing)
        let columns = max(Int(drawingWidth / spacing), 1)
        let rows = max(Int(drawingHeight / spacing), 1)
        let xOffset = padding + (drawingWidth - CGFloat(columns - 1) * spacing) / 2
        let yOffset = padding + (drawingHeight - CGFloat(rows - 1) * spacing) / 2
        let diagonalRange = max(CGFloat(columns + rows), 1)

        for column in 0..<columns {
            for row in 0..<rows {
                let diagonal = CGFloat(column + row) / diagonalRange
                let wavePhase = (phase + diagonal).truncatingRemainder(dividingBy: 1)
                let distance = abs(wavePhase - 0.5) * 2
                let energy = max(0, 1 - distance)
                let alpha = min(max(0.10 + 0.40 * energy, 0.08), 0.54)
                let radius = dotRadius + 0.7 * energy
                let color = energy > 0.64
                    ? AmberTheme.accent.opacity(0.18 + 0.18 * energy)
                    : AmberTheme.muted.opacity(alpha)
                let x = xOffset + CGFloat(column) * spacing
                let y = yOffset + CGFloat(row) * spacing
                let rect = CGRect(
                    x: x - radius,
                    y: y - radius,
                    width: radius * 2,
                    height: radius * 2
                )
                context.fill(
                    Path(ellipseIn: rect),
                    with: .color(color)
                )
            }
        }
    }
}

/// 宽度驱动的宽高比:自定义 Layout 在同一布局 pass 内由宽度 proposal 直接推导
/// 高度(height = width / aspectRatio),首帧即正确,消除旧实现「fallback 220 →
/// onGeometryChange 回填宽度再修正」的一帧高度跳变(cell 新建/复用重进视口都会重演)。
/// UIHostingConfiguration self-sizing 的垂直 proposal 是 estimated cell 高度,不可信;
/// 宽度 proposal 始终正确,所以只信宽度。
private struct ChatWidthDrivenAspectRatio: ViewModifier {
    let aspectRatio: CGFloat

    func body(content: Content) -> some View {
        ChatWidthAspectLayout(aspectRatio: aspectRatio) {
            content
        }
    }
}

private struct ChatWidthAspectLayout: Layout {
    let aspectRatio: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let safeAspect = max(aspectRatio, 0.1)
        let width: CGFloat
        if let proposed = proposal.width, proposed.isFinite, proposed > 0 {
            width = proposed
        } else {
            // 宽度 proposal 缺失/无穷时的兜底,等效旧实现的 220 高 fallback。
            width = 220 * safeAspect
        }
        return CGSize(width: width, height: width / safeAspect)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for subview in subviews {
            subview.place(
                at: CGPoint(x: bounds.midX, y: bounds.midY),
                anchor: .center,
                proposal: ProposedViewSize(width: bounds.width, height: bounds.height)
            )
        }
    }
}

private struct ChatGeneratedImageAppearModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    func body(content: Content) -> some View {
        content
            .opacity(appeared ? 1 : 0)
            .scaleEffect(appeared ? 1 : 0.985)
            .onAppear {
                guard !appeared else { return }
                withAnimation(reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.86)) {
                    appeared = true
                }
            }
    }
}

private struct ChatGeneratedImageActionLabel: View {
    let title: String
    let systemImage: String
    let foreground: Color
    let fill: Color
    var isWorking = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 5) {
            if isWorking {
                ProgressView()
                    .controlSize(.mini)
                    .tint(foreground)
            } else {
                Image(systemName: systemImage)
                    .contentTransition(.symbolEffect(.replace.downUp))
            }

            Text(title)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(foreground)
        .frame(maxWidth: .infinity)
        .frame(height: 28)
        .background(fill, in: Capsule())
        .animation(reduceMotion ? nil : .spring(response: 0.30, dampingFraction: 0.82), value: title)
        .animation(reduceMotion ? nil : .spring(response: 0.30, dampingFraction: 0.82), value: systemImage)
    }
}

private struct ChatGeneratedImageTile: View {
    let urlString: String
    var display = ChatGeneratedImageRequestDisplay(toolInput: nil)
    var onModify: (String, String, String) -> Void = { _, _, _ in }
    @State private var saveState: ChatGeneratedImagePhotoSaveState = .idle
    @State private var saveAlert: ChatGeneratedImageSaveAlert?
    @State private var dataImageState: ChatDataImageLoadState = .loading
    @State private var previewTarget: ChatGeneratedImagePreviewTarget?
    @State private var editTarget: ChatGeneratedImageEditTarget?

    private var isDataURL: Bool { urlString.hasPrefix("data:") }

    private var url: URL? {
        IOSImageGenerationRepository.resolvedImageURL(from: urlString)
    }

    var body: some View {
        VStack(spacing: 6) {
            Group {
                if isDataURL {
                    switch dataImageState {
                    case .success(let decodedDataImage):
                        Image(uiImage: decodedDataImage)
                            .resizable()
                            .scaledToFit()
                            .modifier(ChatGeneratedImageAppearModifier())
                    case .loading:
                        ProgressView()
                            .tint(AmberTheme.accent)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    case .failure:
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 24, weight: .semibold))
                            .foregroundStyle(AmberTheme.accentRed)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                } else {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image
                                .resizable()
                                .scaledToFit()
                                .modifier(ChatGeneratedImageAppearModifier())
                        case .failure:
                            Image(systemName: "exclamationmark.triangle")
                                .font(.system(size: 24, weight: .semibold))
                                .foregroundStyle(AmberTheme.accentRed)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        case .empty:
                            ProgressView()
                                .tint(AmberTheme.accent)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        @unknown default:
                            EmptyView()
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .modifier(ChatWidthDrivenAspectRatio(aspectRatio: display.aspectRatio))
            .contentShape(Rectangle())
            .onTapGesture {
                previewTarget = ChatGeneratedImagePreviewTarget(urlString: urlString)
            }
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous))
            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .task(id: urlString) {
                guard isDataURL else { return }
                dataImageState = .loading
                dataImageState = ChatDataImageLoadState.resolve(urlString: urlString)
            }

            if url != nil || isDataURL {
                HStack(spacing: 6) {
                    if let url {
                        ShareLink(item: url) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AmberTheme.accent)
                                .frame(width: 36, height: 28)
                                .background(AmberTheme.accentTint, in: Capsule())
                        }
                        .accessibilityLabel("分享图片")
                        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.94, haptic: .lightImpact))
                    }

                    Button {
                        saveImageToPhotos()
                    } label: {
                        ChatGeneratedImageActionLabel(
                            title: saveState.title,
                            systemImage: saveState.systemImage,
                            foreground: saveState == .saved ? AmberTheme.accentGreen : AmberTheme.accent,
                            fill: saveState == .saved ? AmberTheme.accentGreen.opacity(0.10) : AmberTheme.accentTint,
                            isWorking: saveState == .saving
                        )
                    }
                    .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.96, haptic: .lightImpact))
                    .disabled(saveState != .idle)
                    .accessibilityLabel("保存图片到相册")

                    Button {
                        editTarget = ChatGeneratedImageEditTarget(
                            urlString: urlString,
                            aspectRatio: display.aspectRatioTitle
                        )
                    } label: {
                        ChatGeneratedImageActionLabel(
                            title: "修改",
                            systemImage: "wand.and.stars",
                            foreground: AmberTheme.accent,
                            fill: AmberTheme.accentTint
                        )
                    }
                    .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.96, haptic: .selection))
                    .accessibilityLabel("修改图片")
                }
            }
        }
        .fullScreenCover(item: $previewTarget) { target in
            ChatGeneratedImagePreview(urlString: target.urlString)
        }
        .sheet(item: $editTarget) { target in
            ChatGeneratedImageEditSheet { prompt in
                onModify(target.urlString, prompt, target.aspectRatio)
            }
        }
        .alert(item: $saveAlert) { alert in
            Alert(
                title: Text("保存失败"),
                message: Text(alert.message),
                dismissButton: .default(Text("好"))
            )
        }
    }

    private func saveImageToPhotos() {
        guard saveState == .idle else { return }
        saveState = .saving
        Task {
            do {
                try await Self.writeImageToPhotoLibrary(urlString)
                await MainActor.run {
                    saveState = .saved
                    AmberHaptics.trigger(.success)
                }
            } catch {
                await MainActor.run {
                    saveState = .idle
                    saveAlert = ChatGeneratedImageSaveAlert(message: error.localizedDescription)
                    AmberHaptics.trigger(.error)
                }
            }
        }
    }

    private nonisolated static func writeImageToPhotoLibrary(_ urlString: String) async throws {
        let resolvedStatus = await photoAuthorizationStatus()
        guard resolvedStatus == .authorized || resolvedStatus == .limited else {
            throw ChatGeneratedImagePhotoSaveError.notAuthorized
        }

        let source = try photoSaveSourceURL(from: urlString)
        defer {
            if source.removeAfterSave {
                try? FileManager.default.removeItem(at: source.url)
            }
        }

        try await saveImageFileToPhotoLibrary(source.url)
    }

    @MainActor
    private static func photoAuthorizationStatus() async -> PHAuthorizationStatus {
        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        guard status == .notDetermined else { return status }
        return await PHPhotoLibrary.requestAuthorization(for: .addOnly)
    }

    private nonisolated static func photoSaveSourceURL(from urlString: String) throws -> PhotoSaveSource {
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)

        if let resolvedURL = IOSImageGenerationRepository.resolvedImageURL(from: trimmed),
           resolvedURL.isFileURL {
            guard FileManager.default.fileExists(atPath: resolvedURL.path) else {
                throw ChatGeneratedImagePhotoSaveError.missingImageFile
            }
            return PhotoSaveSource(url: resolvedURL, removeAfterSave: false)
        }

        let data = try IOSImageGenerationRepository.imageData(from: trimmed)
        guard !data.isEmpty else {
            throw ChatGeneratedImagePhotoSaveError.invalidImage
        }

        let temporaryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("amber-generated-\(UUID().uuidString)")
            .appendingPathExtension(imageFileExtension(for: data))
        try data.write(to: temporaryURL, options: [.atomic])
        return PhotoSaveSource(url: temporaryURL, removeAfterSave: true)
    }

    private nonisolated static func saveImageFileToPhotoLibrary(_ fileURL: URL) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            PHPhotoLibrary.shared().performChanges {
                let request = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                options.shouldMoveFile = false
                request.addResource(with: .photo, fileURL: fileURL, options: options)
            } completionHandler: { success, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if success {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: ChatGeneratedImagePhotoSaveError.unknown)
                }
            }
        }
    }

    private nonisolated static func imageFileExtension(for data: Data) -> String {
        if data.starts(with: [0xFF, 0xD8, 0xFF]) {
            return "jpg"
        }
        if data.starts(with: [0x89, 0x50, 0x4E, 0x47]) {
            return "png"
        }
        if data.starts(with: [0x47, 0x49, 0x46]) {
            return "gif"
        }
        if data.count >= 12,
           data[0] == 0x52, data[1] == 0x49, data[2] == 0x46, data[3] == 0x46,
           data[8] == 0x57, data[9] == 0x45, data[10] == 0x42, data[11] == 0x50 {
            return "webp"
        }
        return "png"
    }
}

private struct PhotoSaveSource {
    let url: URL
    let removeAfterSave: Bool
}

private enum ChatGeneratedImagePhotoSaveState: Equatable {
    case idle
    case saving
    case saved

    var title: String {
        switch self {
        case .idle: "保存"
        case .saving: "保存中"
        case .saved: "已保存"
        }
    }

    var systemImage: String {
        switch self {
        case .idle: "square.and.arrow.down"
        case .saving: "arrow.triangle.2.circlepath"
        case .saved: "checkmark"
        }
    }
}

private struct ChatGeneratedImageSaveAlert: Identifiable {
    let id = UUID()
    let message: String
}

private enum ChatGeneratedImagePhotoSaveError: LocalizedError {
    case notAuthorized
    case missingImageFile
    case invalidImage
    case unknown

    var errorDescription: String? {
        switch self {
        case .notAuthorized:
            "没有相册写入权限。请在系统设置里允许 AmberAgent 添加照片。"
        case .missingImageFile:
            "当前图片文件已经不存在。重装 App 会删除 App 沙盒内的历史图片，需要重新生成后再保存。"
        case .invalidImage:
            "当前图片文件无法解码，可能已经被系统或重装流程删除。"
        case .unknown:
            "系统相册没有返回明确原因。"
        }
    }
}

private struct ChatGeneratedImagePreviewTarget: Identifiable {
    let id = UUID()
    let urlString: String
}

private struct ChatGeneratedImageEditTarget: Identifiable {
    let id = UUID()
    let urlString: String
    let aspectRatio: String
}

private struct ChatGeneratedImagePreview: View {
    let urlString: String
    @Environment(\.dismiss) private var dismiss
    @State private var dataImageState: ChatDataImageLoadState = .loading
    @State private var dragOffset: CGFloat = 0

    private var isDataURL: Bool { urlString.hasPrefix("data:") }
    private var url: URL? { IOSImageGenerationRepository.resolvedImageURL(from: urlString) }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black
                .ignoresSafeArea()

            imageContent
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .offset(y: dragOffset)
                .opacity(max(0.45, 1 - abs(dragOffset) / 360))
                .gesture(
                    DragGesture(minimumDistance: 20)
                        .onChanged { value in
                            dragOffset = value.translation.height
                        }
                        .onEnded { value in
                            let shouldDismiss = abs(value.translation.height) > 140
                                || abs(value.predictedEndTranslation.height) > 220
                            if shouldDismiss {
                                dismiss()
                            } else {
                                withAnimation(.spring(response: 0.28, dampingFraction: 0.86)) {
                                    dragOffset = 0
                                }
                            }
                        }
                )

            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(.white.opacity(0.18), in: Circle())
            }
            .buttonStyle(.plain)
            .padding(.top, 20)
            .padding(.trailing, 16)
            .accessibilityLabel("关闭大图")
        }
        .task(id: urlString) {
            guard isDataURL else { return }
            dataImageState = .loading
            dataImageState = ChatDataImageLoadState.resolve(urlString: urlString)
        }
    }

    @ViewBuilder
    private var imageContent: some View {
        if isDataURL {
            switch dataImageState {
            case .success(let decodedDataImage):
                Image(uiImage: decodedDataImage)
                    .resizable()
                    .scaledToFit()
            case .loading:
                ProgressView()
                    .tint(.white)
            case .failure:
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(.white)
            }
        } else if let url {
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .scaledToFit()
                case .failure:
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(.white)
                case .empty:
                    ProgressView()
                        .tint(.white)
                @unknown default:
                    EmptyView()
                }
            }
        } else {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(.white)
        }
    }

}

private struct ChatGeneratedImageEditSheet: View {
    let onSubmit: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var prompt = ""
    @FocusState private var focused: Bool

    private var trimmedPrompt: String {
        prompt.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                Text("修改要求")
                    .font(.headline)
                    .foregroundStyle(AmberTheme.foreground)

                ZStack(alignment: .topLeading) {
                    TextEditor(text: $prompt)
                        .focused($focused)
                        .scrollContentBackground(.hidden)
                        .padding(8)
                        .frame(minHeight: 160)
                        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                        }

                    if prompt.isEmpty {
                        Text("例如：保留构图，把背景改成雪山，把服装改成蓝色。")
                            .font(.body)
                            .foregroundStyle(AmberTheme.muted)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 16)
                            .allowsHitTesting(false)
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(18)
            .navigationTitle("修改图片")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("生成") {
                        let value = trimmedPrompt
                        guard !value.isEmpty else { return }
                        onSubmit(value)
                        dismiss()
                    }
                    .disabled(trimmedPrompt.isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
        .task {
            focused = true
        }
    }
}

/// A user-sent image attachment, right-aligned and width-constrained like a chat bubble
/// (no share/save chrome). Sized to the image's real aspect ratio so the rounded clip
/// hugs the picture (no transparent letterbox). Decodes `data:` base64 URLs.
private struct ChatUserImageTile: View {
    let urlString: String
    @State private var dataImageState: ChatDataImageLoadState = .loading

    private var isDataURL: Bool { urlString.hasPrefix("data:") }
    private var url: URL? { IOSImageGenerationRepository.resolvedImageURL(from: urlString) }

    private static let maxW: CGFloat = 220
    private static let maxH: CGFloat = 300

    var body: some View {
        imageView
            .task(id: urlString) {
                guard isDataURL else { return }
                dataImageState = .loading
                dataImageState = ChatDataImageLoadState.resolve(urlString: urlString)
            }
    }

    @ViewBuilder
    private var imageView: some View {
        if isDataURL {
            switch dataImageState {
            case .success(let decoded):
            // Fixed frame sized to the image's real aspect (fitted within max), so the
            // view bounds == the picture and the rounded clip hugs it — no letterbox.
                let size = Self.fittedSize(decoded.size)
                Image(uiImage: decoded)
                    .resizable()
                    .frame(width: size.width, height: size.height)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                    )
            case .loading:
                placeholder(failed: false)
            case .failure:
                placeholder(failed: true)
            }
        } else if let url {
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().scaledToFit()
                        .frame(maxWidth: Self.maxW, maxHeight: Self.maxH)
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                case .failure:
                    placeholder(failed: true)
                default:
                    placeholder(failed: false)
                }
            }
        } else {
            placeholder(failed: true)
        }
    }

    private func placeholder(failed: Bool) -> some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(AmberTheme.surface2)
            .frame(width: 150, height: 190)
            .overlay(
                Group {
                    if failed {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(AmberTheme.accentRed)
                    } else {
                        ProgressView().tint(AmberTheme.accent)
                    }
                }
            )
    }

    private static func fittedSize(_ s: CGSize) -> CGSize {
        guard s.width > 0, s.height > 0 else { return CGSize(width: maxW, height: maxW) }
        let scale = min(maxW / s.width, maxH / s.height)
        return CGSize(width: (s.width * scale).rounded(), height: (s.height * scale).rounded())
    }
}

struct ChatConfigurationNoticeCard: View {
    let issue: ChatConfigurationIssue
    var compact = false
    let onPrimary: () -> Void
    let onModelDefaults: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 10 : 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: iconName)
                    .font(.system(size: compact ? 17 : 20, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 32, height: 32)
                    .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(issue.title)
                        .font(compact ? .subheadline.weight(.semibold) : .headline)
                        .foregroundStyle(AmberTheme.foreground)

                    Text(issue.message)
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            HStack(spacing: 8) {
                Button(action: onPrimary) {
                    Label(primaryTitle, systemImage: primaryIconName)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .padding(.horizontal, 12)
                        .frame(height: 34)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)

                if issue != .missingModel {
                    Button(action: onModelDefaults) {
                        Label("选择模型", systemImage: "cpu")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground2)
                            .lineLimit(1)
                            .padding(.horizontal, 12)
                            .frame(height: 34)
                            .background(AmberTheme.surface2, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AmberTheme.surface.opacity(0.92), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
        .accessibilityElement(children: .combine)
    }

    private var iconName: String {
        switch issue {
        case .missingAPIKey:
            "key"
        case .invalidBaseURL:
            "link.badge.plus"
        case .missingModel:
            "cpu"
        case .missingProvider:
            "server.rack"
        case .unsupportedProvider:
            "exclamationmark.triangle"
        case .codexNotSignedIn:
            "person.crop.circle"
        case .grokNotSignedIn:
            "bolt.circle"
        case .providerDisabled:
            "pause.circle"
        }
    }

    private var primaryIconName: String {
        switch issue {
        case .missingAPIKey:
            "key.fill"
        case .invalidBaseURL:
            "server.rack"
        case .missingModel:
            "cpu"
        case .missingProvider:
            "server.rack"
        case .unsupportedProvider:
            "exclamationmark.triangle.fill"
        case .codexNotSignedIn:
            "person.crop.circle.fill"
        case .grokNotSignedIn:
            "bolt.circle.fill"
        case .providerDisabled:
            "pause.circle.fill"
        }
    }

    private var primaryTitle: String {
        switch issue {
        case .missingAPIKey:
            "添加 API Key"
        case .invalidBaseURL:
            "修正服务商"
        case .missingModel:
            "选择模型"
        case .missingProvider:
            "配置服务商"
        case .unsupportedProvider:
            "切换服务商"
        case .codexNotSignedIn:
            "登录 Codex"
        case .grokNotSignedIn:
            "登录 Grok"
        case .providerDisabled:
            "启用服务商"
        }
    }
}
