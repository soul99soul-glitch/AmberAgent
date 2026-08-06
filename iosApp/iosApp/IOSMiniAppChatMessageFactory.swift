import Foundation
import Shared

/// Aligns iOS chat MiniApp finish handling with Android `MiniAppOutputTransformer`:
/// replace the assistant JSON text with a short status + `UIMessagePart.MiniApp` card ref.
enum IOSMiniAppChatMessageFactory {
    static func mightContainMiniApp(_ text: String) -> Bool {
        let hasJSONFields = text.contains("\"html\"") && text.contains("\"title\"") && text.contains("\"description\"")
        if hasJSONFields { return true }
        let lowered = text.lowercased()
        return lowered.contains("<!doctype html") || lowered.contains("<html")
    }

    /// Newest MiniApp-looking assistant in the current turn (after `afterUserIndex`), skipping trailing notices.
    static func assistantCandidateIndex(in messages: [UIMessage], afterUserIndex: Int) -> Int? {
        guard afterUserIndex >= messages.startIndex,
              afterUserIndex < messages.endIndex else { return nil }
        let start = messages.index(after: afterUserIndex)
        guard start < messages.endIndex else { return nil }
        for index in (start..<messages.endIndex).reversed() {
            let message = messages[index]
            guard message.role == MessageRole.assistant else { continue }
            if message.parts.contains(where: { $0 is UIMessagePart.MiniApp }) {
                return nil
            }
            if let text = message.parts.reversed().compactMap({ ($0 as? UIMessagePart.Text)?.text }).first,
               mightContainMiniApp(text) {
                return index
            }
        }
        return nil
    }

    static func parseFailureAssistant(
        _ message: UIMessage,
        textPartIndex: Int,
        reason: String
    ) -> UIMessage {
        let textMetadata = (message.parts[textPartIndex] as? UIMessagePart.Text)?.metadata
        var parts = message.parts
        parts[textPartIndex] = UIMessagePart.Text(
            text: "小应用保存失败：\(reason)",
            metadata: textMetadata
        )
        return UIMessage(
            id: message.id,
            role: message.role,
            parts: parts,
            annotations: message.annotations,
            createdAt: message.createdAt,
            finishedAt: message.finishedAt,
            modelId: message.modelId,
            usage: message.usage,
            translation: message.translation
        )
    }

    static func revisionChangeNote(from userText: String) -> String {
        let marker = "用户修改意见："
        let body: String
        if let range = userText.range(of: marker) {
            body = String(userText[range.upperBound...])
        } else {
            body = userText
        }
        let note = body
            .split(separator: "\n", omittingEmptySubsequences: false)
            .prefix { !$0.hasPrefix("请基于") }
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = note.isEmpty ? "MiniApp revision" : note
        return String(normalized.prefix(240))
    }

    static func revisionPrompt(
        appId: String,
        title: String,
        version: Int,
        request: String
    ) -> String {
        """
        修改小应用
        appId: \(appId)
        currentVersion: \(version)
        title: \(title)

        用户修改意见：
        \(request.trimmingCharacters(in: .whitespacesAndNewlines))

        请基于这个已保存小应用生成新版，并保留适合的能力声明。
        """
    }

    static func miniAppPart(from record: IOSMiniAppRecord) -> UIMessagePart.MiniApp {
        UIMessagePart.MiniApp(
            appId: record.id,
            title: record.title,
            description: record.description,
            iconEmoji: record.iconEmoji,
            category: record.category,
            permissions: record.permissions,
            htmlHash: record.htmlHash,
            version: Int32(record.version),
            metadata: nil
        )
    }

    static func persistedConversationReferences(
        in messages: [UIMessage]
    ) -> Set<IOSMiniAppConversationReference> {
        Set(messages.flatMap(\.parts).compactMap { part in
            guard let miniApp = part as? UIMessagePart.MiniApp,
                  let htmlHash = miniApp.htmlHash?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !htmlHash.isEmpty else {
                return nil
            }
            return IOSMiniAppConversationReference(
                appId: miniApp.appId,
                version: Int(miniApp.version),
                htmlHash: htmlHash
            )
        })
    }

    static func updatedAssistant(
        _ message: UIMessage,
        textPartIndex: Int,
        statusText: String,
        record: IOSMiniAppRecord
    ) -> UIMessage {
        let textMetadata = (message.parts[textPartIndex] as? UIMessagePart.Text)?.metadata
        var parts: [UIMessagePart] = []
        parts.reserveCapacity(message.parts.count + 1)
        for (index, part) in message.parts.enumerated() {
            if index == textPartIndex {
                parts.append(UIMessagePart.Text(text: statusText, metadata: textMetadata))
                parts.append(miniAppPart(from: record))
            } else {
                parts.append(part)
            }
        }
        return UIMessage(
            id: message.id,
            role: message.role,
            parts: parts,
            annotations: message.annotations,
            createdAt: message.createdAt,
            finishedAt: message.finishedAt,
            modelId: message.modelId,
            usage: message.usage,
            translation: message.translation
        )
    }

    static func revisionFailedAssistant(
        _ message: UIMessage,
        textPartIndex: Int
    ) -> UIMessage {
        let textMetadata = (message.parts[textPartIndex] as? UIMessagePart.Text)?.metadata
        let parts = message.parts.enumerated().map { index, part -> UIMessagePart in
            if index == textPartIndex {
                return UIMessagePart.Text(
                    text: "小应用更新失败：目标小应用不存在，或已经被更新。请打开最新的小应用卡片后重新点击「修改」。",
                    metadata: textMetadata
                )
            }
            return part
        }
        return UIMessage(
            id: message.id,
            role: message.role,
            parts: parts,
            annotations: message.annotations,
            createdAt: message.createdAt,
            finishedAt: message.finishedAt,
            modelId: message.modelId,
            usage: message.usage,
            translation: message.translation
        )
    }
}
