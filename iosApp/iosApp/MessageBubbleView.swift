import SwiftUI
import Shared

struct MessageBubbleView: View {

    let message: UIMessage

    @State private var isReasoningExpanded = false

    private var isUser: Bool {
        message.role == MessageRole.user
    }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 48) }

            VStack(alignment: isUser ? .trailing : .leading, spacing: 7) {
                if !isUser {
                    Text("Amber")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted)
                }

                // Reasoning blocks (collapsible)
                reasoningBlocks

                // Text content
                textBubbles
            }
            .frame(maxWidth: 320, alignment: isUser ? .trailing : .leading)

            if !isUser { Spacer(minLength: 48) }
        }
    }

    // MARK: - Subviews

    @ViewBuilder
    private var textBubbles: some View {
        ForEach(Array(message.parts.enumerated()), id: \.offset) { _, part in
            if let textPart = part as? UIMessagePart.Text, !textPart.text.isEmpty {
                if isUser {
                    Text(textPart.text)
                        .font(.body)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(AmberTheme.accent, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                } else {
                    MarkdownView(markdown: textPart.text)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
        }
    }

    @ViewBuilder
    private var reasoningBlocks: some View {
        ForEach(Array(message.parts.enumerated()), id: \.offset) { _, part in
            if let reasoning = part as? UIMessagePart.Reasoning {
                VStack(alignment: .leading, spacing: 4) {
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            isReasoningExpanded.toggle()
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: isReasoningExpanded ? "chevron.down" : "chevron.right")
                                .font(.caption2)
                            Text("Reasoning")
                                .font(.caption)
                                .fontWeight(.medium)
                        }
                        .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)

                    if isReasoningExpanded {
                        Text(reasoning.reasoning)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
                .padding(10)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        }
    }
}
