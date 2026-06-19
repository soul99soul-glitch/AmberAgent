import SwiftUI
import Shared

struct MessageBubbleView: View {

    let message: UIMessage
    var displaySetting: DisplaySetting? = nil
    @Environment(IOSWorkspaceStore.self) private var workspaceStore

    private var isUser: Bool {
        message.role == MessageRole.user
    }

    var body: some View {
        if isUser {
            HStack {
                Spacer(minLength: 48)

                VStack(alignment: .trailing, spacing: 4) {
                    messageParts
                }
                .frame(maxWidth: ChatLayout.userMaxWidth, alignment: .trailing)
            }
        } else {
            ChatAssistantStack {
                ChatAgentName()
                messageParts
            }
        }
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
