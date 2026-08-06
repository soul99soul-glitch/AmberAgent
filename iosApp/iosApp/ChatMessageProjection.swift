import Foundation
import Shared

extension UIMessage {
    var singleNonEmptyTextPart: String? {
        var result: String?
        for part in parts {
            guard let text = part as? UIMessagePart.Text, !text.text.isEmpty else { continue }
            guard result == nil else { return nil }
            result = text.text
        }
        return result
    }
}

struct ChatMessageAnchor: Hashable, Sendable {
    let conversationID: String
    let messageID: String
    let toolCallID: String?

    init(
        conversationID: String,
        messageID: String,
        toolCallID: String? = nil
    ) {
        self.conversationID = conversationID
        self.messageID = messageID
        self.toolCallID = toolCallID
    }
}

enum ChatImageGenerationAnchorTarget {
    static func id(toolCallID: String) -> String {
        "image-tool-\(toolCallID)"
    }
}

enum ChatImageGenerationResumeState: Equatable, Sendable {
    case running
    case completed
}

struct ChatImageGenerationResumeContext: Equatable, Sendable, Identifiable {
    let id: String
    let conversationID: String
    let messageID: String
    let toolCallID: String
    let prompt: String
    let state: ChatImageGenerationResumeState
    let updatedAt: Date
}

enum ChatImageGenerationResumeProjection {
    static func latest(
        in messages: [UIMessage],
        conversationID: String,
        isGenerationActive: Bool
    ) -> ChatImageGenerationResumeContext? {
        preferred(in: contexts(
            in: messages,
            conversationID: conversationID,
            isGenerationActive: isGenerationActive
        ))
    }

    static func matching(
        in messages: [UIMessage],
        conversationID: String,
        messageID: String,
        toolCallID: String,
        isGenerationActive: Bool
    ) -> ChatImageGenerationResumeContext? {
        contexts(
            in: messages,
            conversationID: conversationID,
            isGenerationActive: isGenerationActive
        ).first {
            $0.messageID == messageID && $0.toolCallID == toolCallID
        }
    }

    private static func contexts(
        in messages: [UIMessage],
        conversationID: String,
        isGenerationActive: Bool
    ) -> [ChatImageGenerationResumeContext] {
        messages.enumerated().flatMap { index, message -> [ChatImageGenerationResumeContext] in
            guard message.role == MessageRole.assistant else { return [] }
            let messageID = ChatMessageProjector.messageId(for: message)
            return message.parts.compactMap { part -> ChatImageGenerationResumeContext? in
                guard let tool = part as? UIMessagePart.Tool,
                      tool.toolName == "generate_image" else {
                    return nil
                }

                let state: ChatImageGenerationResumeState
                let timestamp: Kotlinx_datetimeLocalDateTime
                if tool.output.contains(where: { $0 is UIMessagePart.Image }) {
                    state = .completed
                    timestamp = message.finishedAt ?? message.createdAt
                } else if tool.output.isEmpty,
                          isGenerationActive,
                          index == messages.count - 1 {
                    state = .running
                    timestamp = message.createdAt
                } else {
                    return nil
                }
                guard let updatedAt = date(from: timestamp) else { return nil }

                return ChatImageGenerationResumeContext(
                    id: "\(conversationID)|\(messageID)|\(tool.toolCallId)",
                    conversationID: conversationID,
                    messageID: messageID,
                    toolCallID: tool.toolCallId,
                    prompt: prompt(from: tool.input),
                    state: state,
                    updatedAt: updatedAt
                )
            }
        }
    }

    static func preferred(
        in contexts: [ChatImageGenerationResumeContext]
    ) -> ChatImageGenerationResumeContext? {
        contexts.sorted { lhs, rhs in
            if lhs.state != rhs.state { return lhs.state == .running }
            if lhs.updatedAt != rhs.updatedAt { return lhs.updatedAt > rhs.updatedAt }
            return lhs.id < rhs.id
        }.first
    }

    private static func prompt(from input: String) -> String {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let prompt = object["prompt"] as? String else {
            return trimmed
        }
        return prompt.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func date(from localDateTime: Kotlinx_datetimeLocalDateTime) -> Date? {
        var components = DateComponents()
        components.calendar = Calendar.current
        components.timeZone = TimeZone.current
        components.year = Int(localDateTime.year)
        components.month = Int(localDateTime.month.ordinal) + 1
        components.day = Int(localDateTime.day)
        components.hour = Int(localDateTime.hour)
        components.minute = Int(localDateTime.minute)
        components.second = Int(localDateTime.second)
        components.nanosecond = Int(localDateTime.nanosecond)
        return components.date
    }
}

enum ChatImageGenerationResumeConsumption {
    static let viewedCompletionIDKey = "app.amber.ios.home.viewedImageGeneration.v1"

    @discardableResult
    static func markViewedIfCompleted(
        anchor: ChatMessageAnchor,
        messages: [UIMessage],
        isGenerationActive: Bool,
        userDefaults: UserDefaults = .standard
    ) -> String? {
        guard let toolCallID = anchor.toolCallID,
              let context = ChatImageGenerationResumeProjection.matching(
                in: messages,
                conversationID: anchor.conversationID,
                messageID: anchor.messageID,
                toolCallID: toolCallID,
                isGenerationActive: isGenerationActive
              ),
              context.state == .completed else {
            return nil
        }
        userDefaults.set(context.id, forKey: viewedCompletionIDKey)
        return context.id
    }
}

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

enum ChatTimelineRendererKind: Equatable {
    case user
    case staticAssistantMarkdown
    case streamingAssistantMarkdown
}

enum ChatTimelineEntry: Equatable {
    case message(ChatTimelineMessageEntry)
    case pendingAssistant(id: String)
    case bottomAnchor(id: String)

    var id: String {
        switch self {
        case let .message(entry):
            return entry.id
        case let .pendingAssistant(id), let .bottomAnchor(id):
            return id
        }
    }
}

struct ChatTimelineMessageEntry: Equatable {
    let id: String
    let messageId: String
    let message: UIMessage
    let role: MessageRole
    let index: Int
    let isLast: Bool
    let isStreaming: Bool
    let hasEverStreamed: Bool
    let canAnimateInsertion: Bool
    let renderer: ChatTimelineRendererKind
    let renderToken: String

    var rowModel: ChatMessageRowModel {
        ChatMessageRowModel(
            rowId: messageId,
            messageId: messageId,
            message: message,
            role: role,
            parts: message.parts,
            index: index,
            isLast: isLast,
            isStreaming: isStreaming,
            hasEverStreamed: hasEverStreamed,
            canAnimateInsertion: canAnimateInsertion
        )
    }
}

struct ChatTimelinePlan: Equatable {
    let entries: [ChatTimelineEntry]
    let latestRenderToken: String

    func messageEntry(for messageId: String) -> ChatTimelineMessageEntry? {
        entries.lazy.compactMap { entry -> ChatTimelineMessageEntry? in
            guard case let .message(message) = entry else { return nil }
            return message.messageId == messageId ? message : nil
        }.first
    }
}

enum NativeTimelineEntryKind: Equatable {
    case emptyState
    case configurationIssue(compact: Bool)
    case message
    case pendingAssistant
    case visionRecognition
    case contextMarker
    case bottomAnchor
}

/// Shared, renderer-agnostic timeline projection for the future Native chat
/// timeline. This intentionally mirrors `ChatTimelinePlan` instead of inventing
/// a second message model, so the Native path starts from the same identity,
/// streaming-memory, pending-assistant, and bottom-anchor semantics as the
/// current SwiftUI/UICollectionView paths.
struct NativeTimelineEntry: Identifiable, Equatable {
    let id: String
    let kind: NativeTimelineEntryKind
    let messageId: String?
    let message: UIMessage?
    let role: MessageRole?
    let index: Int?
    let isLastMessage: Bool
    let isStreaming: Bool
    let hasEverStreamed: Bool
    let canAnimateInsertion: Bool
    let renderer: ChatTimelineRendererKind?
    let renderToken: String
    let variantInfo: NativeTimelineVariantInfo?
    let renderState: ChatRenderState?
    let renderDigest: ChatRowDigest?

    var hasMultipleVariants: Bool {
        variantInfo?.hasMultipleVariants == true
    }

    var renderHasEverStreamed: Bool {
        renderState?.hasEverStreamed ?? hasEverStreamed
    }

    var liveMarkdownRenderingEnabled: Bool {
        renderState?.liveRenderingEnabled ?? true
    }

    var frozenMarkdownSnapshot: String? {
        renderState?.frozenMarkdownSnapshot
    }
}

struct NativeTimelineProjection: Equatable {
    let entries: [NativeTimelineEntry]
    let latestRenderToken: String

    func messageEntry(for messageId: String) -> NativeTimelineEntry? {
        entries.first { $0.messageId == messageId }
    }
}

struct NativeTimelineVariantInfo: Equatable {
    let variantCount: Int
    let selectedIndex: Int

    var hasMultipleVariants: Bool {
        variantCount > 1
    }
}

enum NativeTimelineProjector {
    static func build(
        messages: [UIMessage],
        event: ChatEvent,
        configurationIssue: ChatConfigurationIssue? = nil,
        isGenerationActive: Bool = false,
        isLoading: Bool = false,
        isRecognizingImages: Bool = false,
        contextCompactState: ChatContextCompactState = .idle,
        viewportState: ChatViewportState = ChatViewportState(),
        displaySettingSignature: String = "",
        generativeUiSettingSignature: String = "",
        renderStateRevision: UInt64 = 0,
        reasoningLevelLabel: String? = nil,
        streamedMessageIDs: Set<String> = [],
        renderStateStore: ChatRenderStateStore? = nil,
        includePendingAssistant: Bool? = nil,
        variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo? = { _ in nil },
        contentHashProvider: (ChatMessageRowModel, Bool) -> Int = { _, _ in 0 }
    ) -> NativeTimelineProjection {
        // Standard Chat already owns waiting/thinking/search status in the top island.
        // Keep the timeline for real message content so completion never removes a
        // duplicate status row after the final text has settled.
        let shouldIncludePendingAssistant = includePendingAssistant ?? false
        let plan = ChatTimelinePlanner.build(
            messages: messages,
            event: event,
            streamedMessageIDs: streamedMessageIDs,
            includePendingAssistant: shouldIncludePendingAssistant
        )

        var entries: [NativeTimelineEntry] = []
        if messages.isEmpty {
            if let configurationIssue {
                entries.append(
                    decorationEntry(
                        id: "configuration-issue-empty",
                        kind: .configurationIssue(compact: false),
                        renderToken: "configuration-empty-\(configurationIssue.title)-\(configurationIssue.message)"
                    )
                )
            } else {
                entries.append(decorationEntry(id: "empty-state", kind: .emptyState, renderToken: "empty"))
            }
            entries.append(nativeEntry(for: .bottomAnchor(id: ChatTimelinePlanner.bottomAnchorID)))
            return NativeTimelineProjection(entries: entries, latestRenderToken: plan.latestRenderToken)
        }

        if let configurationIssue {
            entries.append(
                decorationEntry(
                    id: "configuration-issue-compact",
                    kind: .configurationIssue(compact: true),
                    renderToken: "configuration-compact-\(configurationIssue.title)-\(configurationIssue.message)"
                )
            )
        }

        for entry in plan.entries {
            switch entry {
            case .message, .pendingAssistant:
                entries.append(
                    nativeEntry(
                        for: entry,
                        isGenerationActive: isGenerationActive,
                        viewportState: viewportState,
                        displaySettingSignature: displaySettingSignature,
                        generativeUiSettingSignature: generativeUiSettingSignature,
                        renderStateRevision: renderStateRevision,
                        reasoningLevelLabel: reasoningLevelLabel,
                        renderStateStore: renderStateStore,
                        variantInfoProvider: variantInfoProvider,
                        contentHashProvider: contentHashProvider
                    )
                )
            case let .bottomAnchor(id):
                if isRecognizingImages {
                    entries.append(
                        decorationEntry(
                            id: "vision-recognition-indicator",
                            kind: .visionRecognition,
                            renderToken: "vision"
                        )
                    )
                }
                if contextCompactState.isVisible {
                    let contextID = "context-compact-\(String(describing: contextCompactState.status))-\(contextCompactState.updatedAt.timeIntervalSince1970)"
                    entries.append(
                        decorationEntry(
                            id: contextID,
                            kind: .contextMarker,
                            renderToken: "\(contextID)-\(contextCompactState.summary)"
                        )
                    )
                }
                entries.append(nativeEntry(for: .bottomAnchor(id: id)))
            }
        }

        return NativeTimelineProjection(
            entries: entries,
            latestRenderToken: plan.latestRenderToken
        )
    }

    static func replacingStreamingTail(
        in previous: NativeTimelineProjection,
        messages: [UIMessage],
        event: ChatEvent,
        isGenerationActive: Bool,
        viewportState: ChatViewportState,
        displaySettingSignature: String = "",
        generativeUiSettingSignature: String = "",
        renderStateRevision: UInt64 = 0,
        reasoningLevelLabel: String? = nil,
        streamedMessageIDs: Set<String> = [],
        renderStateStore: ChatRenderStateStore? = nil,
        variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo? = { _ in nil },
        contentHashProvider: (ChatMessageRowModel, Bool) -> Int = { _, _ in 0 }
    ) -> NativeTimelineProjection? {
        guard event == .assistantStreamDelta,
              let message = messages.last,
              message.role == MessageRole.assistant else { return nil }

        let messageID = ChatMessageProjector.messageId(for: message)
        guard let entryIndex = previous.entries.lastIndex(where: { $0.messageId == messageID }) else {
            return nil
        }

        let row = ChatMessageRowModel(
            rowId: messageID,
            messageId: messageID,
            message: message,
            role: message.role,
            parts: message.parts,
            index: messages.count - 1,
            isLast: true,
            isStreaming: true,
            hasEverStreamed: true,
            canAnimateInsertion: false
        )
        let renderState = renderStateStore?.stateForRow(
            row,
            isLiveRenderingFarFromBottom: viewportState.liveRenderingFarFromBottom
        ) ?? ChatRenderState(
            rendererMode: viewportState.liveRenderingFarFromBottom ? .frozen : .streamingMarkdown,
            hasEverStreamed: true,
            liveRenderingEnabled: !viewportState.liveRenderingFarFromBottom,
            frozenMarkdownSnapshot: nil
        )
        let variantInfo = variantInfoProvider(row.index).map {
            NativeTimelineVariantInfo(
                variantCount: $0.variantCount,
                selectedIndex: $0.selectedIndex
            )
        }
        let contentHash = frozenStreamingContentHashIfNeeded(row: row, renderState: renderState) ??
            contentHashProvider(row, true)
        let digest = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: contentHash,
            isGenerationActive: isGenerationActive,
            displaySettingSignature: displaySettingSignature,
            generativeUiSettingSignature: "\(generativeUiSettingSignature):renderState=\(renderStateRevision)",
            hasMultipleVariants: variantInfo?.hasMultipleVariants == true,
            reasoningLevelLabel: reasoningLevelLabel
        )

        var entries = previous.entries
        entries[entryIndex] = NativeTimelineEntry(
            id: "message-\(messageID)",
            kind: .message,
            messageId: messageID,
            message: message,
            role: message.role,
            index: row.index,
            isLastMessage: true,
            isStreaming: true,
            hasEverStreamed: true,
            canAnimateInsertion: false,
            renderer: .streamingAssistantMarkdown,
            renderToken: streamingTailRenderToken(row: row, streamedMessageIDs: streamedMessageIDs),
            variantInfo: variantInfo,
            renderState: renderState,
            renderDigest: digest
        )
        return NativeTimelineProjection(
            entries: entries,
            latestRenderToken: "\(messages.count):\(messageID):tail:\(row.parts.count):\(contentHash)"
        )
    }

    private static func nativeEntry(
        for entry: ChatTimelineEntry,
        isGenerationActive: Bool = false,
        viewportState: ChatViewportState = ChatViewportState(),
        displaySettingSignature: String = "",
        generativeUiSettingSignature: String = "",
        renderStateRevision: UInt64 = 0,
        reasoningLevelLabel: String? = nil,
        renderStateStore: ChatRenderStateStore? = nil,
        variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo? = { _ in nil },
        contentHashProvider: (ChatMessageRowModel, Bool) -> Int = { _, _ in 0 }
    ) -> NativeTimelineEntry {
        switch entry {
        case let .message(message):
            let row = message.rowModel
            let renderState = renderStateStore?.stateForEntry(
                message,
                isLiveRenderingFarFromBottom: viewportState.liveRenderingFarFromBottom
            ) ?? renderState(for: message, viewportState: viewportState)
            let variantInfo = variantInfoProvider(message.index).map {
                NativeTimelineVariantInfo(
                    variantCount: $0.variantCount,
                    selectedIndex: $0.selectedIndex
                )
            }
            let contentHash = frozenStreamingContentHashIfNeeded(row: row, renderState: renderState) ??
                contentHashProvider(row, row.isStreaming)
            let digest = ChatRowDigests.digest(
                row: row,
                renderState: renderState,
                contentHash: contentHash,
                isGenerationActive: isGenerationActive,
                displaySettingSignature: displaySettingSignature,
                generativeUiSettingSignature: row.isLast && row.role == MessageRole.assistant
                    ? "\(generativeUiSettingSignature):renderState=\(renderStateRevision)"
                    : generativeUiSettingSignature,
                hasMultipleVariants: variantInfo?.hasMultipleVariants == true,
                reasoningLevelLabel: reasoningLevelLabel
            )
            return NativeTimelineEntry(
                id: message.id,
                kind: .message,
                messageId: message.messageId,
                message: message.message,
                role: message.role,
                index: message.index,
                isLastMessage: message.isLast,
                isStreaming: message.isStreaming,
                hasEverStreamed: message.hasEverStreamed,
                canAnimateInsertion: message.canAnimateInsertion,
                renderer: message.renderer,
                renderToken: message.renderToken,
                variantInfo: variantInfo,
                renderState: renderState,
                renderDigest: digest
            )
        case let .pendingAssistant(id):
            return NativeTimelineEntry(
                id: id,
                kind: .pendingAssistant,
                messageId: nil,
                message: nil,
                role: nil,
                index: nil,
                isLastMessage: false,
                isStreaming: false,
                hasEverStreamed: false,
                canAnimateInsertion: false,
                renderer: nil,
                renderToken: id,
                variantInfo: nil,
                renderState: nil,
                renderDigest: nil
            )
        case let .bottomAnchor(id):
            return NativeTimelineEntry(
                id: id,
                kind: .bottomAnchor,
                messageId: nil,
                message: nil,
                role: nil,
                index: nil,
                isLastMessage: false,
                isStreaming: false,
                hasEverStreamed: false,
                canAnimateInsertion: false,
                renderer: nil,
                renderToken: id,
                variantInfo: nil,
                renderState: nil,
                renderDigest: nil
            )
        }
    }

    private static func frozenStreamingContentHashIfNeeded(
        row: ChatMessageRowModel,
        renderState: ChatRenderState
    ) -> Int? {
        guard row.isStreaming, !renderState.liveRenderingEnabled else { return nil }
        var hasher = Hasher()
        hasher.combine(row.messageId)
        hasher.combine(renderState.rendererMode.rawValue)
        hasher.combine(renderState.frozenMarkdownSnapshot?.utf16.count ?? 0)
        return hasher.finalize()
    }

    private static func streamingTailRenderToken(
        row: ChatMessageRowModel,
        streamedMessageIDs: Set<String>
    ) -> String {
        var hasher = Hasher()
        hasher.combine(row.messageId)
        hasher.combine(row.parts.count)
        hasher.combine(streamedMessageIDs.contains(row.messageId))
        for part in row.parts {
            switch part {
            case let text as UIMessagePart.Text:
                hasher.combine("text")
                hasher.combine(text.text.utf16.count)
                hasher.combine(String(text.text.suffix(16)))
            case let reasoning as UIMessagePart.Reasoning:
                hasher.combine("reasoning")
                hasher.combine(reasoning.reasoning.utf16.count)
                hasher.combine(reasoning.finishedAt != nil)
            case let tool as UIMessagePart.Tool:
                hasher.combine("tool")
                hasher.combine(tool.toolCallId)
                hasher.combine(tool.output.count)
                hasher.combine(tool.isExecuted)
            default:
                hasher.combine(String(describing: type(of: part)))
            }
        }
        return "\(row.messageId):stream-tail:\(hasher.finalize())"
    }

    private static func renderState(
        for entry: ChatTimelineMessageEntry,
        viewportState: ChatViewportState
    ) -> ChatRenderState {
        guard entry.renderer == .streamingAssistantMarkdown else {
            return ChatRenderState(
                rendererMode: .staticMarkdown,
                hasEverStreamed: false,
                liveRenderingEnabled: true,
                frozenMarkdownSnapshot: nil
            )
        }
        let live = entry.isLast &&
            entry.role == MessageRole.assistant &&
            !viewportState.liveRenderingFarFromBottom
        return ChatRenderState(
            rendererMode: live ? .streamingMarkdown : .frozen,
            hasEverStreamed: true,
            liveRenderingEnabled: live,
            frozenMarkdownSnapshot: nil
        )
    }

    private static func decorationEntry(
        id: String,
        kind: NativeTimelineEntryKind,
        renderToken: String
    ) -> NativeTimelineEntry {
        NativeTimelineEntry(
            id: id,
            kind: kind,
            messageId: nil,
            message: nil,
            role: nil,
            index: nil,
            isLastMessage: false,
            isStreaming: false,
            hasEverStreamed: false,
            canAnimateInsertion: false,
            renderer: nil,
            renderToken: renderToken,
            variantInfo: nil,
            renderState: nil,
            renderDigest: nil
        )
    }
}

enum ChatTimelinePlanner {
    static let bottomAnchorID = ChatLayout.bottomAnchorID
    static let pendingAssistantID = "timeline-pending-assistant"
    static let messageEntryIDPrefix = "message-"

    /// `includeRenderTokens` 控制是否计算逐行 renderToken。Native timeline
    /// projection 消费 token；旧 SwiftUI/collection 路径只取 row model，传 false
    /// 跳过尾行每个 chunk 都要 O(文本长度) 扫一遍的 token 计算。
    static func build(
        messages: [UIMessage],
        event: ChatEvent,
        streamedMessageIDs: Set<String> = [],
        includePendingAssistant: Bool = false,
        includeRenderTokens: Bool = true
    ) -> ChatTimelinePlan {
        let rows = ChatMessageProjector.rows(
            messages: messages,
            event: event,
            streamedMessageIDs: streamedMessageIDs
        )
        var entries = rows.map { row in
            ChatTimelineEntry.message(
                ChatTimelineMessageEntry(
                    id: messageEntryIDPrefix + row.messageId,
                    messageId: row.messageId,
                    message: row.message,
                    role: row.role,
                    index: row.index,
                    isLast: row.isLast,
                    isStreaming: row.isStreaming,
                    hasEverStreamed: row.hasEverStreamed,
                    canAnimateInsertion: row.canAnimateInsertion,
                    renderer: rendererKind(for: row),
                    renderToken: includeRenderTokens ? renderToken(for: row) : ""
                )
            )
        }

        if includePendingAssistant {
            entries.append(.pendingAssistant(id: pendingAssistantID))
        }
        entries.append(.bottomAnchor(id: bottomAnchorID))

        return ChatTimelinePlan(
            entries: entries,
            latestRenderToken: includeRenderTokens
                ? latestRenderToken(rows: rows, includePendingAssistant: includePendingAssistant)
                : ""
        )
    }

    private static func rendererKind(for row: ChatMessageRowModel) -> ChatTimelineRendererKind {
        switch row.role {
        case MessageRole.user:
            return .user
        case MessageRole.assistant:
            return row.isStreaming || row.hasEverStreamed ? .streamingAssistantMarkdown : .staticAssistantMarkdown
        default:
            return .staticAssistantMarkdown
        }
    }

    private static func latestRenderToken(rows: [ChatMessageRowModel], includePendingAssistant: Bool) -> String {
        guard let row = rows.last else {
            return includePendingAssistant ? "empty:pending" : "empty"
        }
        return "\(rows.count):\(row.messageId):\(renderToken(for: row)):\(includePendingAssistant)"
    }

    private static func renderToken(for row: ChatMessageRowModel) -> String {
        let partTokens = row.parts.map { compactRenderToken(for: $0) }.joined(separator: "|")
        return "\(row.messageId):\(row.role.name):\(row.isStreaming):\(row.hasEverStreamed):\(partTokens)"
    }

    private static func compactRenderToken(for part: UIMessagePart) -> String {
        switch part {
        case let text as UIMessagePart.Text:
            // utf16.count 而非 count:Character 计数是 O(n) 字素簇遍历,流式尾行
            // 每个 chunk 都要为 32KB 级文本付一次;token 只需要"长度变了就变"。
            return "text:\(text.text.utf16.count):\(String(text.text.suffix(16)))"
        case let reasoning as UIMessagePart.Reasoning:
            return "reasoning:\(reasoning.reasoning.utf16.count):\(reasoning.finishedAt != nil)"
        case let image as UIMessagePart.Image:
            return "image:\(image.url.count):\(String(describing: image.metadata).hashValue)"
        case let document as UIMessagePart.Document:
            return "document:\(document.fileName):\(document.url.count):\(String(describing: document.metadata).hashValue)"
        case let miniApp as UIMessagePart.MiniApp:
            return "mini_app:\(miniApp.appId):\(miniApp.version):\(miniApp.htmlHash ?? ""):\(miniApp.title)"
        case let tool as UIMessagePart.Tool:
            let outputToken = tool.output.last.map { compactRenderToken(for: $0) } ?? "empty"
            return "tool:\(tool.toolCallId):\(tool.toolName):\(tool.isExecuted):\(tool.output.count):\(outputToken)"
        default:
            return "\(type(of: part)):\(String(describing: part).hashValue)"
        }
    }
}

enum ChatMessageProjector {
    static func messageId(for message: UIMessage) -> String {
        // String(describing:) 走桥接 description 路径(单次 ~25µs,逐行×每 delta);
        // toHexDashString() 是直接访问器,产出同样的 8-4-4-4-12 字符串
        // (格式等价由 ChatMessageProjectionTests 的 canary 锁定)。
        message.id.toHexDashString()
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
