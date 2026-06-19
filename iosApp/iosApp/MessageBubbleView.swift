import SwiftUI
import Shared

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

    @Environment(IOSWorkspaceStore.self) private var workspaceStore
    @State private var editing: Bool = false
    @State private var editDraft: String = ""

    private var isUser: Bool {
        message.role == MessageRole.user
    }

    private var canBranch: Bool {
        // Disable branching actions while a generation is running, mirroring
        // the ChatViewModel guard (streamJob == nil).
        !isGenerating
    }

    var body: some View {
        if isUser {
            HStack {
                Spacer(minLength: 48)

                VStack(alignment: .trailing, spacing: 4) {
                    variantSwitcher
                    messageParts
                }
                .frame(maxWidth: ChatLayout.userMaxWidth, alignment: .trailing)
            }
            .contextMenu { messageActions }
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
            .contextMenu { messageActions }
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
                    ChatUserBubble(text: textPart.text)
                } else {
                    ChatAssistantText {
                        MarkdownView(markdown: textPart.text, displaySetting: displaySetting)
                    }
                    ChatMessageArtifactButton(
                        text: textPart.text,
                        messageId: String(describing: message.id),
                        workspaceStore: workspaceStore
                    )
                }
            } else if let reasoning = part as? UIMessagePart.Reasoning, !reasoning.reasoning.isEmpty {
                ChatReasoningCard(
                    title: "思考 · \(reasoning.reasoning.count) chars",
                    bodyText: reasoning.reasoning,
                    autoCloseThinking: displaySetting?.autoCloseThinking ?? true
                )
            } else if let image = part as? UIMessagePart.Image {
                ChatGeneratedImageGrid(images: [image])
            } else if let tool = part as? UIMessagePart.Tool {
                ChatToolTimeline(steps: [ChatToolStepModel(tool: tool)])
                if tool.toolName == "generate_image" {
                    let images = tool.output.compactMap { $0 as? UIMessagePart.Image }
                    if !images.isEmpty {
                        ChatGeneratedImageGrid(images: images)
                    }
                }
            }
        }
    }
}

private struct ChatMessageArtifactButton: View {
    let text: String
    let messageId: String
    @Bindable var workspaceStore: IOSWorkspaceStore

    @State private var statusText: String?

    var body: some View {
        Button {
            save()
        } label: {
            Label(statusText ?? "保存到 Workspace", systemImage: statusText == nil ? "tray.and.arrow.down" : "checkmark")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(statusText == nil ? AmberTheme.accent : AmberTheme.accentGreen)
                .lineLimit(1)
                .padding(.horizontal, 9)
                .frame(height: 26)
                .background((statusText == nil ? AmberTheme.accentTint : AmberTheme.accentGreen.opacity(0.10)), in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(statusText != nil)
        .accessibilityLabel("保存助手回复到 Workspace")
    }

    private func save() {
        do {
            _ = try workspaceStore.saveArtifact(
                title: artifactTitle,
                content: text,
                type: .chat,
                sourceKind: "chat_message",
                sourceId: messageId
            )
            statusText = "已保存"
        } catch {
            statusText = "保存失败"
        }
    }

    private var artifactTitle: String {
        let firstLine = text
            .split(whereSeparator: \.isNewline)
            .first
            .map(String.init)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let firstLine, !firstLine.isEmpty else { return "Chat Artifact" }
        return String(firstLine.prefix(80))
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

    private var url: URL? {
        URL(string: urlString) ?? URL(fileURLWithPath: urlString)
    }

    var body: some View {
        VStack(spacing: 6) {
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
            .frame(height: 176)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous))
            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous))

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
        }
    }
}
