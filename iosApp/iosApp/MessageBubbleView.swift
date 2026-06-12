import SwiftUI
import Shared

struct MessageBubbleView: View {

    let message: UIMessage

    @State private var isReasoningExpanded = false

    private var isUser: Bool {
        message.role == MessageRole.USER
    }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            if !isUser { roleIcon }

            VStack(alignment: isUser ? .trailing : .leading, spacing: 6) {
                // Reasoning blocks (collapsible)
                reasoningBlocks

                // Text content
                textBubbles
            }

            if isUser { roleIcon }
        }
    }

    // MARK: - Subviews

    private var roleIcon: some View {
        Image(systemName: isUser ? "person.fill" : "sparkles")
            .font(.title3)
            .foregroundStyle(.secondary)
            .frame(width: 28, height: 28)
            .background(.ultraThinMaterial, in: Circle())
    }

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
                        .background(.tint)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                } else {
                    Text(textPart.text)
                        .font(.body)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(.ultraThinMaterial)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
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
                            .foregroundStyle(.secondary)
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(.thinMaterial)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
            }
        }
    }
}
