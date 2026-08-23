import Foundation
@preconcurrency import Shared

/// Visible-decode clock for one LLM round. Reset when a new model stream starts
/// so tool and approval waits are not counted.
struct ChatGenerationSpeedClock: Equatable {
    private(set) var firstVisibleAt: Date?

    mutating func resetRound() {
        firstVisibleAt = nil
    }

    mutating func noteVisibleDelta(at date: Date = Date()) {
        if firstVisibleAt == nil {
            firstVisibleAt = date
        }
    }

    func duration(until date: Date = Date()) -> TimeInterval? {
        guard let firstVisibleAt else { return nil }
        let value = date.timeIntervalSince(firstVisibleAt)
        return value > 0 ? value : nil
    }

    static func chunkHasVisibleContent(_ chunk: MessageChunk) -> Bool {
        let choice = chunk.choices.first
        let parts = choice?.delta?.parts ?? choice?.message?.parts ?? []
        return parts.contains(where: isVisibleContent)
    }

    static func messagesHaveVisibleAssistantContent(_ messages: [UIMessage]) -> Bool {
        guard let assistant = messages.last(where: { $0.role == MessageRole.assistant }) else {
            return false
        }
        return assistant.parts.contains(where: isVisibleContent)
    }

    private static func isVisibleContent(_ part: UIMessagePart) -> Bool {
        if let text = part as? UIMessagePart.Text {
            return !text.text.isEmpty
        }
        if let reasoning = part as? UIMessagePart.Reasoning {
            return !reasoning.reasoning.isEmpty
        }
        return false
    }
}

extension UIMessage {
    func applyingGenerationDurationMs(_ milliseconds: Int) -> UIMessage {
        guard milliseconds > 0 else { return self }
        let existing = usage
        return UIMessage(
            id: id,
            role: role,
            parts: parts,
            annotations: annotations,
            createdAt: createdAt,
            finishedAt: finishedAt,
            modelId: modelId,
            usage: TokenUsage(
                promptTokens: existing?.promptTokens ?? 0,
                completionTokens: existing?.completionTokens ?? 0,
                cachedTokens: existing?.cachedTokens ?? 0,
                totalTokens: existing?.totalTokens ?? 0,
                generationDurationMs: Int32(milliseconds)
            ),
            translation: translation
        )
    }
}

extension Array where Element == UIMessage {
    func applyingLastAssistantGenerationDuration(_ duration: TimeInterval?) -> [UIMessage] {
        guard let duration, duration > 0,
              let index = lastIndex(where: { message in
                  guard message.role == MessageRole.assistant, let usage = message.usage else {
                      return false
                  }
                  return usage.promptTokens > 0 || usage.completionTokens > 0
              }) else {
            return self
        }
        var messages = self
        messages[index] = messages[index].applyingGenerationDurationMs(Int((duration * 1000).rounded()))
        return messages
    }

    func clearingLastAssistantGenerationDuration() -> [UIMessage] {
        guard let index = lastIndex(where: {
            $0.role == MessageRole.assistant && ($0.usage?.generationDurationMs ?? 0) > 0
        }), let usage = self[index].usage else {
            return self
        }
        var messages = self
        messages[index] = UIMessage(
            id: self[index].id,
            role: self[index].role,
            parts: self[index].parts,
            annotations: self[index].annotations,
            createdAt: self[index].createdAt,
            finishedAt: self[index].finishedAt,
            modelId: self[index].modelId,
            usage: TokenUsage(
                promptTokens: usage.promptTokens,
                completionTokens: usage.completionTokens,
                cachedTokens: usage.cachedTokens,
                totalTokens: usage.totalTokens,
                generationDurationMs: 0
            ),
            translation: self[index].translation
        )
        return messages
    }
}
