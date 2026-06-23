import SwiftUI
import Shared
import SwiftStreamingMarkdown

struct MessageBubbleView: View {

    let message: UIMessage
    var messageIndex: Int = 0
    var variantInfo: IOSConversationStore.VariantInfo? = nil
    var displaySetting: DisplaySetting? = nil
    // Branching actions (Android ChatService parity). Defaults are no-ops so
    // existing call sites / previews that don't supply them still compile.
    var onRegenerate: () -> Void = {}
    var onEdit: (String) -> Void = { _ in }
    var onDelete: () -> Void = {}
    var onSelectVariant: (Int) -> Void = { _ in }
    var isGenerating: Bool = false
    /// True only for the last message — gates the live "thinking" timer so a stopped/older
    /// reasoning (whose finishedAt was never set on cancel) doesn't keep counting.
    var isLastMessage: Bool = false
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
                        ChatAssistantMarkdownView(markdown: textPart.text, displaySetting: displaySetting)
                    }
                }
            } else if let reasoning = part as? UIMessagePart.Reasoning, !reasoning.reasoning.isEmpty {
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
                    ChatGeneratedImageGrid(images: [image])
                }
            } else if let tool = part as? UIMessagePart.Tool {
                ChatToolTimeline(
                    steps: [ChatToolStepModel(tool: tool)],
                    onTapStep: { _ in toolDetailTarget = ToolDetailTarget(tool: tool) }
                )
                if tool.toolName == "generate_image" {
                    let images = tool.output.compactMap { $0 as? UIMessagePart.Image }
                    if !images.isEmpty {
                        ChatGeneratedImageGrid(images: images)
                    }
                }
            }
        }
        .sheet(item: $toolDetailTarget) { target in
            ChatToolDetailSheet(tool: target.tool)
        }
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

private struct ChatAssistantMarkdownView: View {
    let markdown: String
    var displaySetting: DisplaySetting?

    @AppStorage(IOSDisplayPreferenceKeys.microsoftStreamingMarkdown) private var microsoftStreamingMarkdown = false

    var body: some View {
        if microsoftStreamingMarkdown {
            SwiftStreamingMarkdown.MarkdownView(text: markdown)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            MarkdownView(markdown: markdown, displaySetting: displaySetting)
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

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: 140), spacing: 8)]
    }

    var body: some View {
        LazyVGrid(columns: columns, spacing: 8) {
            ForEach(Array(images.enumerated()), id: \.offset) { _, image in
                ChatGeneratedImageTile(urlString: image.url)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ChatGeneratedImageTile: View {
    let urlString: String
    @Environment(IOSWorkspaceStore.self) private var workspaceStore
    @State private var saved = false
    @State private var decodedDataImage: UIImage?

    private var isDataURL: Bool { urlString.hasPrefix("data:") }

    private var url: URL? {
        URL(string: urlString) ?? URL(fileURLWithPath: urlString)
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
                            .scaledToFill()
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
                                .scaledToFill()
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
            .frame(height: 176)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous))
            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous))
            .task(id: urlString) {
                if isDataURL, decodedDataImage == nil {
                    decodedDataImage = Self.decodeDataURL(urlString)
                }
            }

            if let url {
                HStack(spacing: 6) {
                    ShareLink(item: url) {
                        Label("分享", systemImage: "square.and.arrow.up")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(maxWidth: .infinity)
                            .frame(height: 28)
                            .background(AmberTheme.accentTint, in: Capsule())
                    }

                    Button {
                        saveImageArtifact()
                    } label: {
                        Image(systemName: saved ? "checkmark" : "tray.and.arrow.down")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(saved ? AmberTheme.accentGreen : AmberTheme.accent)
                            .frame(width: 36, height: 28)
                            .background((saved ? AmberTheme.accentGreen.opacity(0.10) : AmberTheme.accentTint), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(saved)
                    .accessibilityLabel("保存图片到 Workspace")
                }
            }
        }
    }

    private func saveImageArtifact() {
        do {
            _ = try workspaceStore.saveArtifact(
                title: "Generated Image",
                content: "![Generated Image](\(urlString))",
                type: .image,
                sourceKind: "image_generation",
                sourceId: urlString
            )
            saved = true
        } catch {
            saved = false
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
    private var url: URL? { URL(string: urlString) ?? URL(fileURLWithPath: urlString) }

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
        }
    }
}
