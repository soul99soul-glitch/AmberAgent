import Foundation
import Shared

@Observable
final class ChatViewModel {

    // MARK: - Published State

    var messages: [UIMessage] = []
    var inputText: String = ""
    var isLoading: Bool = false

    // MARK: - Private

    private var conversation: Conversation?

    // MARK: - Init

    init() {
        // Build initial conversation using KMP types
        let systemMsg = UIMessage.system(prompt: "You are a helpful assistant.")
        let systemNode = MessageNode.of(message: systemMsg)

        let conversationId = Uuid.random()
        let assistantId = Uuid.random()

        conversation = Conversation.ofId(
            id: conversationId,
            assistantId: assistantId,
            messages: [systemNode]
        )

        messages = conversation?.currentMessages as? [UIMessage] ?? []
    }

    // MARK: - Actions

    func sendMessage() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let userMsg = UIMessage.user(prompt: text)
        appendMessage(userMsg)
        inputText = ""

        // Demonstrate AmberNativeBridge — Rust FFI markdown rendering
        let html = AmberNativeBridge().markdownToHtml(text: text) ?? text

        // Simulate an assistant response (real AI provider wired in later milestone)
        isLoading = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
            guard let self else { return }
            let response = self.mockResponse(for: text, html: html)
            self.appendMessage(response)
            self.isLoading = false
        }
    }

    // MARK: - Private Helpers

    private func appendMessage(_ message: UIMessage) {
        messages.append(message)
        if let conv = conversation {
            conversation = conv.updateCurrentMessages(messages: messages)
        }
    }

    private func mockResponse(for userText: String, html: String) -> UIMessage {
        // Build an assistant message that includes Text and Reasoning parts
        // to exercise the UIMessagePart sealed-class hierarchy from KMP.
        let reasoning = UIMessagePart.Reasoning(
            reasoning: "User said: \(userText)",
            createdAt: Instant.Companion.fromEpochMilliseconds(
                epochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000)
            ),
            finishedAt: Instant.Companion.fromEpochMilliseconds(
                epochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000)
            )
        )
        let textPart = UIMessagePart.Text(
            text: "**Echo (HTML):** \(html)\n\n> This is a mock response. Real AI integration comes next."
        )
        return UIMessage(
            role: MessageRole.ASSISTANT,
            parts: [reasoning, textPart]
        )
    }
}
