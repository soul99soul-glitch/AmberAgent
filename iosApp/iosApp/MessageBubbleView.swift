import SwiftUI
import Shared
import SwiftStreamingMarkdown
import MarkdownView
import Photos

struct MessageBubbleView: View {

    let message: UIMessage
    var messageIndex: Int = 0
    var variantInfo: IOSConversationStore.VariantInfo? = nil
    var displaySetting: DisplaySetting? = nil
    var generativeUiSetting: GenerativeUiSetting? = nil
    // Branching actions (Android ChatService parity). Defaults are no-ops so
    // existing call sites / previews that don't supply them still compile.
    var onRegenerate: () -> Void = {}
    var onEdit: (String) -> Void = { _ in }
    var onDelete: () -> Void = {}
    var onSelectVariant: (Int) -> Void = { _ in }
    var onGenerativeWidgetAction: (String) -> Void = { _ in }
    var onModifyGeneratedImage: (String, String, String) -> Void = { _, _, _ in }
    var isGenerating: Bool = false
    /// True only for the last message — gates the live "thinking" timer so a stopped/older
    /// reasoning (whose finishedAt was never set on cancel) doesn't keep counting.
    var isLastMessage: Bool = false
    /// 这条消息「曾经流式过」的记忆(来自 projection 层)。当前不直接驱动 Markdown
    /// renderer:历史行被列表回收再创建时必须回到稳定同步渲染器,否则会重新出现
    /// 首帧高度跳变。可见 bubble 的完成态防闪烁由 ChatAssistantMarkdownView 内部 latch 负责。
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

    @Environment(IOSWorkspaceStore.self) private var workspaceStore
    @State private var editing: Bool = false
    @State private var editDraft: String = ""
    @State private var workspaceSaveAlert: WorkspaceSaveAlert?
    @State private var toolDetailTarget: ToolDetailTarget?

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
                .sheet(isPresented: $editing) {
                    editSheet
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
                    if let url = URL(string: citation.url) {
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
        Text("\(variantInfo?.selectedIndex ?? 0 + 1)/\(variantInfo?.variantCount ?? 1)")
            .font(.caption2.monospacedDigit())
            .foregroundStyle(AmberTheme.muted2)
    }

    @ViewBuilder
    private var messageActions: some View {
        if canBranch {
            if isUser {
                Button {
                    editDraft = message.toText()
                    editing = true
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
        } label: {
            Label("复制", systemImage: "doc.on.doc")
        }
    }

    /// Edit-user-message sheet (Android editMessage parity). On submit it calls
    /// `onEdit` with the trimmed draft; ChatViewModel appends the edited text
    /// as a new variant, truncates the stale reply, and re-runs generation.
    private var editSheet: some View {
        NavigationStack {
            VStack {
                TextEditor(text: $editDraft)
                    .font(.body)
                    .padding(8)
                    .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .padding(16)
            }
            .navigationTitle("编辑消息")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { editing = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("发送") {
                        onEdit(editDraft)
                        editing = false
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - Subviews

    @ViewBuilder
    private var messageParts: some View {
        ForEach(Array(message.parts.enumerated()), id: \.offset) { _, part in
            if let textPart = part as? UIMessagePart.Text, !textPart.text.isEmpty {
                if isUser {
                    // 气泡现在是内容尺寸(已移除其内部的 300pt 框),contextMenu 的高亮平台贴合气泡。
                    // 再用 .contentShape(.contextMenuPreview, 气泡圆角) 把平台裁成气泡形状,消除灰角。
                    ChatUserBubble(text: textPart.text)
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
                            .transition(.opacity)
                    } else if tool.output.isEmpty {
                        ChatGeneratedImageLoadingPlaceholder(toolInput: tool.input)
                            .transition(.opacity)
                    } else {
                        ChatGeneratedImageFailureCard(
                            reason: ChatToolOutputFormatter.imageFailureReason(from: tool.output)
                                ?? "图片生成工具没有返回图片。"
                        )
                        .transition(.opacity)
                    }
                }
            }
        }
        .animation(.easeInOut(duration: 0.28), value: message.parts.map(Self.partAnimationKey).joined(separator: "|"))
        .sheet(item: $toolDetailTarget) { target in
            ChatToolDetailSheet(
                tool: target.tool,
                live: target.tool.toolName.contains("subagent_dispatch")
                    ? SubAgentLiveRegistry.shared.model(forToolCallId: target.tool.toolCallId)
                    : nil
            )
        }
    }

    @ViewBuilder
    private var fallbackThinkingCard: some View {
        if !isUser, isGenerating, isLastMessage, !hasReasoningPart {
            ChatReasoningCard(
                bodyText: "",
                isThinking: true,
                levelLabel: reasoningLevelLabel,
                autoCloseThinking: displaySetting?.autoCloseThinking ?? true
            )
            .transition(.opacity)
        }
    }

    private var hasReasoningPart: Bool {
        message.parts.contains { $0 is UIMessagePart.Reasoning }
    }

    private var nonEmptyTextPartCount: Int {
        message.parts.reduce(0) { count, part in
            guard let text = part as? UIMessagePart.Text, !text.text.isEmpty else { return count }
            return count + 1
        }
    }

    private static func partAnimationKey(_ part: UIMessagePart) -> String {
        if let tool = part as? UIMessagePart.Tool {
            let imageCount = tool.output.compactMap { $0 as? UIMessagePart.Image }.count
            return "tool:\(tool.toolCallId):\(tool.output.isEmpty):\(imageCount)"
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
    var displaySetting: DisplaySetting?
    var generativeUiSetting: GenerativeUiSetting?
    var isStreaming = false
    var hasEverStreamed = false
    var liveRenderingEnabled = true
    var frozenMarkdownSnapshot: String?
    var onGenerativeWidgetAction: (String) -> Void = { _ in }

    @AppStorage(IOSDisplayPreferenceKeys.microsoftStreamingMarkdown) private var microsoftStreamingMarkdown = false
    @AppStorage(IOSDisplayPreferenceKeys.liyananStreamingMarkdown) private var liyananStreamingMarkdown = false
    /// per-view-instance 的「这个 bubble 曾经流式过」latch。
    /// 关键特性:列表回收行后,新 view 实例的 @State 重置为 false → 历史消息回滚到
    /// 高度稳定的同步渲染器 AmberMarkdownView;而可视区内刚完成的消息仍保持流式渲染器
    /// (latch=true),避免 completion 瞬间切渲染器闪烁。用 ChatView 层持久化的 hasEverStreamed
    /// 代替它会破坏这个特性——回收的行依然收到 true → 用异步渲染器 → 首帧高度 0 → 上滑跳动。
    @State private var hasUsedStreamingMarkdownRenderer = false
    @State private var renderedMarkdownSnapshot = ""

    var body: some View {
        let widgetSettings = IOSGenerativeWidgetSettings(generativeUiSetting)
        let renderedMarkdown = renderedMarkdownText
        let liveStreaming = isStreaming && liveRenderingEnabled
        Group {
            if widgetSettings.enabled && IOSGenerativeWidgetParser.mayContainWidgetPayload(renderedMarkdown) {
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
                            case .text(_, let content):
                                markdownText(content, liveStreaming: liveStreaming)
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
            if isStreaming && liveRenderingEnabled {
                hasUsedStreamingMarkdownRenderer = true
            }
        }
        .onChange(of: markdown) { _, newValue in
            if liveRenderingEnabled || renderedMarkdownSnapshot.isEmpty {
                renderedMarkdownSnapshot = newValue
            }
        }
        .onChange(of: isStreaming) { _, newValue in
            if newValue && liveRenderingEnabled {
                hasUsedStreamingMarkdownRenderer = true
                renderedMarkdownSnapshot = markdown
            } else if !newValue {
                renderedMarkdownSnapshot = markdown
            }
        }
        .onChange(of: liveRenderingEnabled) { _, newValue in
            if newValue {
                renderedMarkdownSnapshot = markdown
            }
            if newValue && isStreaming {
                hasUsedStreamingMarkdownRenderer = true
            }
        }
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

    private func shouldUseExperimentalMarkdownRenderer(liveStreaming: Bool) -> Bool {
        // 异步渲染器(SwiftStreamingMarkdown/Liyanan)的首帧高度为 0(controller.renderable==nil
        // → empty document)。列表上滑回收再实现历史行时,这个 0→完整的高度跳变会让
        // contentSize 骤变,把视图弹过 agent 消息。
        // 因此:异步渲染器只用于「正在流式」的消息(isStreaming)或「本 view 实例曾流式过」
        // 的消息(hasUsedStreamingMarkdownRenderer latch)。已完成的历史消息(列表回收后
        // 新实例 latch 重置为 false)回退到同步、高度稳定的 AmberMarkdownView。
        return liveStreaming || hasUsedStreamingMarkdownRenderer
    }

    private func shouldUseFadeStreamingRenderer(liveStreaming: Bool) -> Bool {
        // 逐词淡入目前只有 SwiftStreamingMarkdown 提供真实 glyph fade。
        // LiYanan MarkdownView 的 StreamingMarkdownReader 负责增量解析,但没有文字淡入层。
        // 所以可见的流式正文统一先走 SwiftStreamingMarkdown;列表回收后的历史行仍回到
        // AmberMarkdownView,避免异步 renderer 的首帧空高度拖累历史滚动。
        liveStreaming || hasUsedStreamingMarkdownRenderer
    }

    private func streamingMarkdownConfig(liveStreaming: Bool) -> SwiftStreamingMarkdown.MarkdownRenderConfig {
        SwiftStreamingMarkdown.MarkdownRenderConfig.default
            .withShouldAnimateText(value: liveStreaming || hasUsedStreamingMarkdownRenderer)
    }

    @ViewBuilder
    private func markdownText(_ content: String, liveStreaming: Bool) -> some View {
        if shouldUseFadeStreamingRenderer(liveStreaming: liveStreaming) {
            SwiftStreamingMarkdown.MarkdownView(text: content, config: streamingMarkdownConfig(liveStreaming: liveStreaming))
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if liyananStreamingMarkdown && shouldUseExperimentalMarkdownRenderer(liveStreaming: liveStreaming) {
            LiyananStreamingMarkdownContentView(content: content)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if microsoftStreamingMarkdown && shouldUseExperimentalMarkdownRenderer(liveStreaming: liveStreaming) {
            SwiftStreamingMarkdown.MarkdownView(text: content, config: streamingMarkdownConfig(liveStreaming: liveStreaming))
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            AmberMarkdownView(markdown: content, displaySetting: displaySetting)
        }
    }
}

private struct LiyananStreamingMarkdownContentView: View {
    let content: String
    @State private var source = StreamingMarkdownSource()

    var body: some View {
        StreamingMarkdownReader(source) { parseResult in
            MarkdownView(parseResult)
        }
        .onAppear {
            source.text = content
        }
        .onChange(of: content) { _, newValue in
            source.text = newValue
        }
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
        let card = ChatGeneratedImageDotPlaceholder(aspectRatio: display.aspectRatio)
        if display.requestedCount > 1 {
            card.frame(maxWidth: display.multiCardWidth, alignment: .leading)
        } else if let maxWidth = display.singleCardMaxWidth {
            card.frame(maxWidth: maxWidth, alignment: .leading)
        } else {
            card.frame(maxWidth: .infinity, alignment: .leading)
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
    @State private var decodedDataImage: UIImage?
    @State private var previewTarget: ChatGeneratedImagePreviewTarget?
    @State private var editTarget: ChatGeneratedImageEditTarget?

    private var isDataURL: Bool { urlString.hasPrefix("data:") }

    private var url: URL? {
        IOSImageGenerationRepository.resolvedImageURL(from: urlString)
    }

    /// Decodes a `data:<mime>;base64,...` URL (used by chat image attachments) into a UIImage.
    /// `AsyncImage` cannot load `data:` URLs, so these are decoded once and cached in state.
    private static func decodeDataURL(_ string: String) -> UIImage? {
        guard let comma = string.firstIndex(of: ",") else { return nil }
        let base64 = String(string[string.index(after: comma)...])
        guard let data = Data(base64Encoded: base64) else { return nil }
        return UIImage(data: data)
    }

    var body: some View {
        VStack(spacing: 6) {
            Group {
                if isDataURL {
                    if let decodedDataImage {
                        Image(uiImage: decodedDataImage)
                            .resizable()
                            .scaledToFit()
                            .modifier(ChatGeneratedImageAppearModifier())
                    } else {
                        ProgressView()
                            .tint(AmberTheme.accent)
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
                if isDataURL, decodedDataImage == nil {
                    decodedDataImage = Self.decodeDataURL(urlString)
                }
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
    @State private var decodedDataImage: UIImage?
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
            guard isDataURL, decodedDataImage == nil else { return }
            decodedDataImage = Self.decodeDataURL(urlString)
        }
    }

    @ViewBuilder
    private var imageContent: some View {
        if isDataURL {
            if let decodedDataImage {
                Image(uiImage: decodedDataImage)
                    .resizable()
                    .scaledToFit()
            } else {
                ProgressView()
                    .tint(.white)
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

    private static func decodeDataURL(_ string: String) -> UIImage? {
        guard let comma = string.firstIndex(of: ",") else { return nil }
        let base64 = String(string[string.index(after: comma)...])
        guard let data = Data(base64Encoded: base64) else { return nil }
        return UIImage(data: data)
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
    @State private var decoded: UIImage?

    private var isDataURL: Bool { urlString.hasPrefix("data:") }
    private var url: URL? { IOSImageGenerationRepository.resolvedImageURL(from: urlString) }

    private static let maxW: CGFloat = 220
    private static let maxH: CGFloat = 300

    var body: some View {
        imageView
            .task(id: urlString) {
                guard isDataURL, decoded == nil,
                      let comma = urlString.firstIndex(of: ","),
                      let data = Data(base64Encoded: String(urlString[urlString.index(after: comma)...]))
                else { return }
                decoded = UIImage(data: data)
            }
    }

    @ViewBuilder
    private var imageView: some View {
        if let decoded {
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
        } else if !isDataURL, let url {
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
            placeholder(failed: false)
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
        }
    }
}
