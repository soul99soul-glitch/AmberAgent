import Foundation
@preconcurrency import Shared

/// 首页会话行 LLM 浓缩预览：与标题辅助链路共用 titleModelId。
/// 前台 `ChatViewModel` 与后台 handoff 成功落盘后共用此入口，避免只挂 FG `generationSucceeded`。
@MainActor
enum ConversationListPreviewGenerator {
    static let defaultPrompt = """
        I will give you dialogue content in the <content> block.
        Write one short line that condenses the assistant's latest useful outcome for a chat-list subtitle.
        1. Language must match the user's primary language ({locale})
        2. One line only; no quotes, no markdown, no bullet prefix
        3. Prefer the assistant's result or next-step hook; do not restate the user's question
        4. At most 28 characters for CJK (or ~40 Latin letters); keep it scannable
        5. Reply with the line only

        <content>
        {content}
        </content>
        """

    /// per-conversation latest-wins，避免连发两轮时旧 aux 覆盖新预览。
    private static var requestTokens: [String: UUID] = [:]
    private static let textProvider: any IOSAgentTextProvider = OpenAIKmpProviderAdapter()

    static func schedule(
        conversationId: KotlinUuid,
        messages: [UIMessage],
        store: IOSConversationStore,
        settings: IOSSharedSettingsStore
    ) {
        let content = conversationText(messages, maxMessages: 6)
        guard !content.isEmpty else { return }
        let snapshot = settings.snapshot
        guard let model = snapshot.findModelById(uuid: snapshot.titleModelId) ?? snapshot.getCurrentChatModel() else {
            return
        }
        guard let provider = ChatProviderConfiguration.provider(
            for: model,
            providers: snapshot.providers
        ), ChatProviderConfiguration.issue(for: model, provider: provider) == nil else {
            return
        }

        let key = String(describing: conversationId)
        let token = UUID()
        requestTokens[key] = token

        let locale = Locale.current.localizedString(forIdentifier: Locale.current.identifier)
            ?? Locale.current.identifier
        let prompt = defaultPrompt
            .replacingOccurrences(of: "{locale}", with: locale)
            .replacingOccurrences(of: "{content}", with: content)
        let assistant = snapshot.getCurrentAssistant()
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.off,
            customHeaders: assistant.customHeaders + model.customHeaders,
            customBody: assistant.customBodies + model.customBodies
        )

        Task {
            let raw: String?
            do {
                let chunk = try await textProvider.generateText(
                    providerSetting: provider,
                    messages: [UIMessage.companion.user(prompt: prompt)],
                    params: params
                )
                raw = chunk.choices.first?.message?.toText()
                    .trimmingCharacters(in: .whitespacesAndNewlines)
            } catch {
                raw = nil
            }
            guard let raw else { return }
            let preview = sanitize(raw)
            guard !preview.isEmpty else { return }
            await MainActor.run {
                guard requestTokens[key] == token else { return }
                store.setListPreview(id: conversationId, preview: preview)
            }
        }
    }

    static func sanitize(_ raw: String) -> String {
        var line = raw
            .split(separator: "\n").first.map(String.init)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        line = line.trimmingCharacters(in: CharacterSet(charactersIn: "\"'“”『』「」《》·•-–— "))
        while line.hasPrefix("#") || line.hasPrefix("*") || line.hasPrefix("-") {
            line = String(line.dropFirst()).trimmingCharacters(in: .whitespaces)
        }
        if line.count > 28 { line = String(line.prefix(28)) }
        return line
    }

    private static func conversationText(_ messages: [UIMessage], maxMessages: Int) -> String {
        messages.suffix(maxMessages).compactMap { message -> String? in
            let text = message.toText().trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            switch message.role {
            case MessageRole.user: return "User: \(text)"
            case MessageRole.assistant: return "Assistant: \(text)"
            default: return nil
            }
        }.joined(separator: "\n\n")
    }
}
