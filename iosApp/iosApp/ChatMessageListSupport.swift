import SwiftUI
import Shared

enum ChatLayout {
    static let contentHorizontalInset: CGFloat = 22
    static let userMaxWidth: CGFloat = 300
    static let followBottomGap: CGFloat = 96
    static let bottomStickThreshold: CGFloat = 40
    /// 内容底部的静止留白:进入会话定位、回到底部时最后一条与输入框之间留出的小距离,
    /// 和「手动上推→回弹」的自然停靠位一致(不贴死输入框)。
    static let bottomRestGap: CGFloat = 26
    /// 内容最底部的不可见锚点 id:定位到它(而非最后一条气泡)即停在「带留白的内容底」。
    static let bottomAnchorID = "chat-bottom-rest-anchor"
}

struct ChatScrollGeometryState: Equatable {
    let atBottom: Bool
    let isScrollable: Bool
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

struct ChatAssistantPendingResponseView: View {
    @State private var startedAt = Date()

    var body: some View {
        ChatAssistantStack {
            ChatAgentName()
            HStack(spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentAmber)

                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let elapsed = Int(max(0, context.date.timeIntervalSince(startedAt)))
                    Text(elapsed >= 2 ? "正在等待模型响应 \(elapsed) 秒" : "正在连接模型")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AmberTheme.foreground2)
                }

                TypingDots()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
                AmberTheme.surface,
                in: RoundedRectangle(cornerRadius: 17, style: .continuous)
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ContextCompactTimelineMarker: View {
    let state: ChatContextCompactState

    private var title: String {
        switch state.status {
        case .planning:
            return "准备压缩上下文"
        case .compacting:
            return "正在压缩上下文"
        case .completed:
            return "上下文已压缩"
        case .failed:
            return "上下文压缩失败"
        case .idle:
            return ""
        }
    }

    private var icon: String {
        switch state.status {
        case .completed:
            return "checkmark.circle"
        case .failed:
            return "exclamationmark.triangle"
        default:
            return "shippingbox"
        }
    }

    private var tint: Color {
        switch state.status {
        case .completed:
            return AmberTheme.accent
        case .failed:
            return .red
        default:
            return .blue
        }
    }

    private var preview: String {
        let text = state.summary
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count > 220 else { return text }
        return String(text.prefix(220)) + "..."
    }

    var body: some View {
        VStack(spacing: 7) {
            HStack(spacing: 10) {
                Rectangle()
                    .fill(AmberTheme.border.opacity(0.55))
                    .frame(height: 0.5)
                HStack(spacing: 6) {
                    Image(systemName: icon)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(tint)
                    Text(title)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(tint)
                    if state.isActive {
                        TypingDots()
                    }
                }
                Rectangle()
                    .fill(AmberTheme.border.opacity(0.55))
                    .frame(height: 0.5)
            }

            if !preview.isEmpty {
                Text(preview)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.horizontal, 24)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

struct TypingDots: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 20.0)) { timeline in
            HStack(spacing: 3) {
                ForEach(0..<3, id: \.self) { index in
                    Circle()
                        .fill(AmberTheme.muted.opacity(dotOpacity(index: index, date: timeline.date)))
                        .frame(width: 4, height: 4)
                }
            }
        }
        .frame(width: 20, height: 8)
    }

    private func dotOpacity(index: Int, date: Date) -> Double {
        let phase = (date.timeIntervalSinceReferenceDate * 1.8 + Double(index) * 0.28)
            .truncatingRemainder(dividingBy: 1)
        return 0.25 + 0.55 * (0.5 + 0.5 * sin(phase * .pi * 2))
    }
}
