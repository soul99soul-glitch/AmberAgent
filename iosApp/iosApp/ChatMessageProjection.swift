import Foundation
import Shared

struct ChatMessageRowModel: Identifiable {
    let rowId: String
    let messageId: String
    let message: UIMessage
    let role: MessageRole
    let parts: [UIMessagePart]
    let index: Int
    let isLast: Bool
    let isStreaming: Bool
    let hasEverStreamed: Bool
    let canAnimateInsertion: Bool

    var id: String { rowId }
}

enum ChatMessageProjector {
    static func messageId(for message: UIMessage) -> String {
        String(describing: message.id)
    }

    static func rows(
        messages: [UIMessage],
        event: ChatEvent,
        streamedMessageIDs: Set<String> = []
    ) -> [ChatMessageRowModel] {
        messages.enumerated().map { index, message in
            let messageId = messageId(for: message)
            let isLast = index == messages.count - 1
            let isLastAssistant = isLast && message.role == MessageRole.assistant
            let isStreaming = event == .assistantStreamDelta && isLastAssistant
            let hasEverStreamed = isStreaming || streamedMessageIDs.contains(messageId)
            let canAnimateInsertion = event == .userMessageAppended &&
                isLast &&
                message.role == MessageRole.user

            return ChatMessageRowModel(
                rowId: messageId,
                messageId: messageId,
                message: message,
                role: message.role,
                parts: message.parts,
                index: index,
                isLast: isLast,
                isStreaming: isStreaming,
                hasEverStreamed: hasEverStreamed,
                canAnimateInsertion: canAnimateInsertion
            )
        }
    }
}

enum ChatInsertionAnimationPolicy {
    static func animatedInsertionItemIDs(
        previousItemIDs: [String],
        rows: [ChatMessageRowModel]
    ) -> [String] {
        let previous = Set(previousItemIDs)
        return rows.compactMap { row in
            guard row.canAnimateInsertion else { return nil }
            let itemID = "message-\(row.messageId)"
            return previous.contains(itemID) ? nil : itemID
        }
    }
}
