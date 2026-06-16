import SwiftUI
import Shared

struct MessageBubbleView: View {

    let message: UIMessage
    var displaySetting: DisplaySetting? = nil

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
                }
            } else if let reasoning = part as? UIMessagePart.Reasoning, !reasoning.reasoning.isEmpty {
                ChatReasoningCard(
                    title: "思考 · \(reasoning.reasoning.count) chars",
                    bodyText: reasoning.reasoning,
                    autoCloseThinking: displaySetting?.autoCloseThinking ?? true
                )
            } else if let tool = part as? UIMessagePart.Tool {
                ChatToolTimeline(steps: [ChatToolStepModel(tool: tool)])
            }
        }
    }
}
