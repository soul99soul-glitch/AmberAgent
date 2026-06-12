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

    // MARK: - Private Helpers

    private func appendMessage(_ message: UIMessage) {
        messages.append(message)
        if let conv = conversation {
            conversation = conv.updateCurrentMessages(messages: messages)
        }
    }

    private func mockResponse(for userText: String, html: String) -> UIMessage {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let createdAt = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: now)
        let finishedAt = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: now)

        let reasoning = UIMessagePart.Reasoning(
            reasoning: "User said: \(userText)",
            createdAt: createdAt,
            finishedAt: finishedAt,
            metadata: nil
        )
        let textPart = UIMessagePart.Text(
            text: "**Echo (HTML):** \(html)\n\n> This is a mock response. Real AI integration comes next.",
            metadata: nil
        )

        // Kotlin data class with defaults → designated init requires all params
        // id=random, createdAt=now, annotations=[], usage=nil, etc.
        let msgId = KotlinUuid.companion.random()
        let localNow = kotlinLocalDateTime(now: now)

        return UIMessage(
            id: msgId,
            role: MessageRole.assistant,
            parts: [reasoning, textPart],
            annotations: [],
            createdAt: localNow,
            finishedAt: localNow,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    /// Crude bridge: epoch millis → kotlinx.datetime.LocalDateTime
    /// TODO: Replace with proper kotlinx-datetime init once verified in Xcode.
    private func kotlinLocalDateTime(now: Int64) -> Kotlinx_datetimeLocalDateTime {
        // Use the KotlinInstant path since LocalDateTime construction is complex
        // For now create via the companion factory if available, else fall back
        let instant = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: now)
        // KotlinInstant doesn't directly give us LocalDateTime; this is a known gap.
        // As a workaround, use the Companion's parse or a hard-coded epoch.
        // Real fix: expose a Kotlin helper in ai-core or use Swift Date bridging.
        return Kotlinx_datetimeLocalDateTime.companion.parse(
            isoString: ISO8601DateFormatter().string(from: Date())
        )
    }
}
