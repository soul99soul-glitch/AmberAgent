import Foundation
import Shared

@Observable
final class ChatViewModel {

    // MARK: - State

    var messages: [UIMessage] = []
    var inputText: String = ""
    var isLoading: Bool = false

    // MARK: - Private

    private var conversation: Conversation?

    // MARK: - Init

    init() {
        let systemMsg = UIMessage.companion.system(prompt: "You are a helpful assistant.")
        let systemNode = MessageNode.companion.of(message: systemMsg)

        let conversationId = KotlinUuid.companion.random()
        let assistantId = KotlinUuid.companion.random()

        conversation = Conversation.companion.ofId(
            id: conversationId,
            assistantId: assistantId,
            messages: [systemNode],
            newConversation: true
        )

        messages = conversation?.currentMessages as? [UIMessage] ?? []
    }

    // MARK: - Actions

    func sendMessage() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let userMsg = UIMessage.companion.user(prompt: text)
        appendMessage(userMsg)
        inputText = ""

        // Demonstrate AmberNativeBridge — Rust FFI markdown rendering
        let html = AmberNativeBridge.shared.markdownToHtml(text: text) ?? text

        // Simulate an assistant response (real AI provider wired in later milestone)
        isLoading = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
            guard let self else { return }
            let response = self.mockResponse(for: text, html: html)
            self.appendMessage(response)
            self.isLoading = false
        }
    }

    // MARK: - Private

    private func appendMessage(_ message: UIMessage) {
        messages.append(message)
        if let conv = conversation {
            conversation = conv.updateCurrentMessages(messages: messages)
        }
    }

    private func nowInstant() -> KotlinInstant {
        KotlinInstant.companion.fromEpochMilliseconds(
            epochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }

    private func nowLocalDateTime() -> Kotlinx_datetimeLocalDateTime {
        let now = Date()
        let cal = Calendar.current
        return Kotlinx_datetimeLocalDateTime(
            year: Int32(cal.component(.year, from: now)),
            month: Int32(cal.component(.month, from: now)),
            day: Int32(cal.component(.day, from: now)),
            hour: Int32(cal.component(.hour, from: now)),
            minute: Int32(cal.component(.minute, from: now)),
            second: Int32(cal.component(.second, from: now)),
            nanosecond: Int32(cal.component(.nanosecond, from: now))
        )
    }

    private func mockResponse(for userText: String, html: String) -> UIMessage {
        let instant = nowInstant()
        let localDt = nowLocalDateTime()

        let reasoning = UIMessagePart.Reasoning(
            reasoning: "User said: \(userText)",
            createdAt: instant,
            finishedAt: instant,
            metadata: nil
        )

        let textPart = UIMessagePart.Text(
            text: "**Echo (HTML):** \(html)\n\n> This is a mock response. Real AI integration comes next.",
            metadata: nil
        )

        return UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [reasoning, textPart],
            annotations: [],
            createdAt: localDt,
            finishedAt: localDt,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }
}
