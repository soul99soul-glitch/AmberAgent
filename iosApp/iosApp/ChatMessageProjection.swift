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
        let shouldIncludePendingAssistant = includePendingAssistant ??
            ((isGenerationActive || isLoading) && messages.last?.role == MessageRole.user)
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

struct NativeTimelineMirrorInput {
    let signal: ChatMessageUpdateSignal
    let messages: [UIMessage]
    let configurationIssue: ChatConfigurationIssue?
    let isGenerationActive: Bool
    let isLoading: Bool
    let isRecognizingImages: Bool
    let contextCompactState: ChatContextCompactState
    let followGeneration: Bool
    let displaySettingSignature: String
    let generativeUiSettingSignature: String
    let reasoningLevelLabel: String?
    let scrollToBottomTrigger: Int
    let viewportState: ChatViewportState
    let variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo?

    init(
        signal: ChatMessageUpdateSignal,
        messages: [UIMessage],
        configurationIssue: ChatConfigurationIssue?,
        isGenerationActive: Bool,
        isLoading: Bool,
        isRecognizingImages: Bool,
        contextCompactState: ChatContextCompactState,
        followGeneration: Bool = false,
        displaySettingSignature: String = "",
        generativeUiSettingSignature: String = "",
        reasoningLevelLabel: String? = nil,
        scrollToBottomTrigger: Int = 0,
        viewportState: ChatViewportState = ChatViewportState(),
        variantInfoProvider: @escaping (Int) -> IOSConversationStore.VariantInfo? = { _ in nil }
    ) {
        self.signal = signal
        self.messages = messages
        self.configurationIssue = configurationIssue
        self.isGenerationActive = isGenerationActive
        self.isLoading = isLoading
        self.isRecognizingImages = isRecognizingImages
        self.contextCompactState = contextCompactState
        self.followGeneration = followGeneration
        self.displaySettingSignature = displaySettingSignature
        self.generativeUiSettingSignature = generativeUiSettingSignature
        self.reasoningLevelLabel = reasoningLevelLabel
        self.scrollToBottomTrigger = scrollToBottomTrigger
        self.viewportState = viewportState
        self.variantInfoProvider = variantInfoProvider
    }
}

struct NativeTimelineMirrorResult: Equatable {
    let projection: NativeTimelineProjection
    let insertedIDs: [String]
    let removedIDs: [String]
    let changedIDs: [String]
    let retainedStreamedMessageIDs: Set<String>
    let didProposeScrollWrite: Bool
    let environmentToken: String
    let followGeneration: Bool
    let scrollToBottomTrigger: Int
    let viewportState: ChatViewportState
}

enum NativeChatTimelineMirrorFeatureFlags {
    static let enabledKey = "chat.nativeTimeline.mirror.enabled"
    static let loggingKey = "chat.nativeTimeline.mirror.logging.enabled"

    static var isEnabled: Bool {
        UserDefaults.standard.bool(forKey: enabledKey)
    }

    static var isLoggingEnabled: Bool {
        UserDefaults.standard.bool(forKey: loggingKey)
    }
}

@MainActor
final class NativeChatTimelineMirror {
    private var lastProjection: NativeTimelineProjection?
    private var lastEnvironmentToken = ""
    private var streamedMessageIDs: Set<String> = []
    private let contentHashCache = ChatRowContentHashCache()
    private let renderStateStore = ChatRenderStateStore()

    @discardableResult
    func record(_ input: NativeTimelineMirrorInput) -> NativeTimelineMirrorResult {
        rememberStreamedRendererState(for: input.signal.event, messages: input.messages)
        let projection = NativeTimelineProjector.build(
            messages: input.messages,
            event: input.signal.event,
            configurationIssue: input.configurationIssue,
            isGenerationActive: input.isGenerationActive,
            isLoading: input.isLoading,
            isRecognizingImages: input.isRecognizingImages,
            contextCompactState: input.contextCompactState,
            viewportState: input.viewportState,
            displaySettingSignature: input.displaySettingSignature,
            generativeUiSettingSignature: input.generativeUiSettingSignature,
            reasoningLevelLabel: input.reasoningLevelLabel,
            streamedMessageIDs: streamedMessageIDs,
            renderStateStore: renderStateStore,
            variantInfoProvider: input.variantInfoProvider,
            contentHashProvider: { [contentHashCache] row, isStreamingLayout in
                isStreamingLayout
                    ? contentHashCache.streamingTailLayoutToken(for: row)
                    : contentHashCache.contentHash(for: row)
            }
        )
        let environmentToken = Self.environmentToken(for: input)
        let result = diff(
            from: lastProjection,
            previousEnvironmentToken: lastEnvironmentToken,
            to: projection,
            environmentToken: environmentToken,
            input: input
        )
        lastProjection = projection
        lastEnvironmentToken = environmentToken
        if NativeChatTimelineMirrorFeatureFlags.isLoggingEnabled {
            print(
                "[AA-NATIVE-MIRROR] event=\(input.signal.event) revision=\(input.signal.revision) "
                    + "entries=\(projection.entries.count) inserted=\(result.insertedIDs.count) "
                    + "removed=\(result.removedIDs.count) changed=\(result.changedIDs.count) "
                    + "streamed=\(result.retainedStreamedMessageIDs.count) "
                    + "follow=\(input.followGeneration) bottomTrigger=\(input.scrollToBottomTrigger) "
                    + "paused=\(input.viewportState.followPaused) dragging=\(input.viewportState.userDragging) "
                    + "scrollWrite=false"
            )
        }
        return result
    }

    func reset() {
        lastProjection = nil
        lastEnvironmentToken = ""
        streamedMessageIDs.removeAll()
        contentHashCache.removeAll()
        renderStateStore.removeAll()
    }

    func markVisible(messageID: String) {
        renderStateStore.markVisible(messageID)
    }

    func freeze(messageID: String, latestText: String?) {
        renderStateStore.freeze(messageID: messageID, latestText: latestText)
    }

    private func diff(
        from previous: NativeTimelineProjection?,
        previousEnvironmentToken: String,
        to next: NativeTimelineProjection,
        environmentToken: String,
        input: NativeTimelineMirrorInput
    ) -> NativeTimelineMirrorResult {
        let previousEntries = Dictionary(uniqueKeysWithValues: (previous?.entries ?? []).map { ($0.id, $0) })
        let nextEntries = Dictionary(uniqueKeysWithValues: next.entries.map { ($0.id, $0) })
        let previousIDs = Set(previousEntries.keys)
        let nextIDs = Set(nextEntries.keys)
        let inserted = next.entries.map(\.id).filter { !previousIDs.contains($0) }
        let removed = (previous?.entries ?? []).map(\.id).filter { !nextIDs.contains($0) }
        let environmentChanged = !previousEnvironmentToken.isEmpty &&
            previousEnvironmentToken != environmentToken
        let changed = next.entries.compactMap { entry -> String? in
            guard let old = previousEntries[entry.id] else { return nil }
            return !environmentChanged &&
                old.renderToken == entry.renderToken &&
                old.kind == entry.kind &&
                old.isStreaming == entry.isStreaming &&
                old.hasEverStreamed == entry.hasEverStreamed &&
                old.variantInfo == entry.variantInfo &&
                old.renderDigest == entry.renderDigest
                ? nil
                : entry.id
        }
        return NativeTimelineMirrorResult(
            projection: next,
            insertedIDs: inserted,
            removedIDs: removed,
            changedIDs: changed,
            retainedStreamedMessageIDs: streamedMessageIDs,
            didProposeScrollWrite: false,
            environmentToken: environmentToken,
            followGeneration: input.followGeneration,
            scrollToBottomTrigger: input.scrollToBottomTrigger,
            viewportState: input.viewportState
        )
    }

    private static func environmentToken(for input: NativeTimelineMirrorInput) -> String {
        [
            "display=\(input.displaySettingSignature)",
            "generative=\(input.generativeUiSettingSignature)",
            "reasoning=\(input.reasoningLevelLabel ?? "")",
            "follow=\(input.followGeneration)",
            "issue=\(String(describing: input.configurationIssue))"
        ].joined(separator: "|")
    }

    private func rememberStreamedRendererState(for event: ChatEvent, messages: [UIMessage]) {
        let currentIDs = Set(messages.map(ChatMessageProjector.messageId(for:)))
        switch event {
        case .conversationLoaded, .conversationSwitched, .branchChanged:
            streamedMessageIDs.removeAll()
            lastProjection = nil
            contentHashCache.removeAll()
            renderStateStore.removeAll()
        default:
            break
        }

        streamedMessageIDs = streamedMessageIDs.intersection(currentIDs)
        contentHashCache.retain(ids: currentIDs)
        renderStateStore.retain(ids: currentIDs)
        if event.remembersStreamingRenderer,
           let lastAssistant = messages.last(where: { $0.role == MessageRole.assistant }) {
            streamedMessageIDs.insert(ChatMessageProjector.messageId(for: lastAssistant))
        }
    }
}

enum ChatTimelinePlanner {
    static let bottomAnchorID = ChatLayout.bottomAnchorID
    static let pendingAssistantID = "timeline-pending-assistant"

    /// `includeRenderTokens` 控制是否计算逐行 renderToken。只有 native mirror diff
    /// 消费 token(ChatMessageProjection 内 nativeEntry 与 diff 两处);SwiftUI clean
    /// list 与 collection 路径从不读取,传 false 跳过尾行每个 chunk 都要 O(文本长度)
    /// 扫一遍的 token 计算。
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
                    id: "message-\(row.messageId)",
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
