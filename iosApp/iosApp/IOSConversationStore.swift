import Foundation
import Observation
@preconcurrency import Shared

// MARK: - IOSConversationStore

struct IOSConversationSearchResult: Identifiable {
    enum Kind: String {
        case conversation
        case message

        var title: String {
            switch self {
            case .conversation: "会话"
            case .message: "消息"
            }
        }
    }

    let id: String
    let conversationId: KotlinUuid
    let kind: Kind
    let title: String
    let preview: String
    let highlight: String
    let updateAt: Int64
}

/// iOS 端会话生命周期管理器。把 [JsonConversationStorage]（KMP 文件 JSON 存储）包成
/// SwiftUI 可观察的状态：持有当前会话 + 会话摘要列表。
///
/// 并发契约（与 `JsonConversationStorage` 的 @MainActor 单写者假设一致）：
/// 本类标注 `@MainActor`，所有 storage 调用串行发生在主线程。后台 Task.detached 不可
/// 调用本类方法——会破坏 index.json 的 read-modify-write。
///
/// 设计参照 PLAN_CONVERSATION_PERSISTENCE.md Phase 2。
@MainActor
@Observable
final class IOSConversationStore {

    // MARK: - Observable state

    /// 当前选中的会话。App 启动时由 [bootstrap] 选最近一条或新建。
    private(set) var currentConversation: Conversation?

    /// 会话摘要列表（按 updateAt 倒序、置顶优先，由 KMP 层排序）。
    private(set) var summaries: [ConversationSummary] = []

    /// 单调递增的修订号：每次 currentConversation 被替换（新建/切换/删除回退）时 +1。
    /// 供 SwiftUI `.onChange` 观察——KotlinUuid 是否被 Swift 当作 Equatable 不可靠，
    /// 用 Int 修订号保证切换会话一定能触发 reload。
    private(set) var currentRevision: Int = 0

    // MARK: - Private

    private let storage: JsonConversationStorage

    // MARK: - Init

    init(baseDirectory: URL? = nil) {
        // Documents/conversations/ —— iOS Documents 目录会被 iTunes 文件共享暴露，
        // 第一版可接受（便于调试）；后续如要隐藏可换 Application Support。
        let baseDirPath: String
        if let baseDirectory {
            baseDirPath = baseDirectory.path
        } else {
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
            baseDirPath = documents.appendingPathComponent("conversations").path
        }
        let baseDir = ConversationFile(path: baseDirPath)
        self.storage = JsonConversationStorage(baseDir: baseDir)
    }

    // MARK: - Bootstrap

    /// App 启动时调用：加载摘要列表，选最近一条作为 current；无历史则新建空会话。
    func bootstrap() async {
        do {
            summaries = try await storage.listSummaries()
        } catch {
            // listSummaries 内部已对损坏 index 做了 rebuild 兜底；到这里的 error 是更底层
            // 的 I/O 故障。不致命：降级为空列表，下面会新建会话，内存里仍可用。
            print("[IOSConversationStore] listSummaries failed: \(error)")
            summaries = []
        }

        if let mostRecent = summaries.first {
            // summaries 已按 updateAt 倒序，第一条即最近。
            await selectConversation(id: mostRecent.id)
        } else {
            await newConversation()
        }
    }

    // MARK: - CRUD

    /// 新建空会话：生成新 Conversation（assistantId = DEFAULT_ASSISTANT_ID），落盘，设为 current。
    func newConversation() async {
        let conversation = Conversation.companion.ofId(
            id: KotlinUuid.companion.random(),
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: true
        )
        await persist(conversation)
        setCurrent(conversation)
        await refreshSummaries()
    }

    /// 切换到指定会话：从磁盘 load 完整对象，设为 current。
    func selectConversation(id: KotlinUuid) async {
        let loaded: Conversation?
        do {
            loaded = try await storage.loadConversation(id: id)
        } catch {
            print("[IOSConversationStore] loadConversation failed for \(id): \(error)")
            loaded = nil
        }
        if let loaded {
            setCurrent(loaded)
        }
        // load 失败时不切换 current，保留上一个；UI 层可提示。
    }

    /// 把当前内存里的 [messages] 回填进 currentConversation（节点合并），落盘，刷新 index。
    /// 流式结束后调用一次（不在每个 chunk 调，避免写盘抖动）。
    func saveCurrent(messages: [UIMessage]) async {
        guard let id = currentConversation?.id else { return }
        await save(messages: messages, to: id)
    }

    /// 把 [messages] 保存到指定会话 id。用于生成回调按 run 发起时的 conversation 归属落盘，
    /// 避免用户在流式生成期间切换/新建会话后把旧 run 写进当前新会话。
    func save(messages: [UIMessage], to id: KotlinUuid) async {
        let conversation: Conversation?
        if currentConversation?.id == id {
            conversation = currentConversation
        } else {
            do {
                conversation = try await storage.loadConversation(id: id)
            } catch {
                print("[IOSConversationStore] loadConversation failed for save target \(id): \(error)")
                conversation = nil
            }
        }
        guard let conversation else { return }

        // updateCurrentMessages 已做 identity 短路：消息没变时返回同一引用，节省落盘。
        var updated = conversation.updateCurrentMessages(messages: messages)

        // 标题派生（PLAN 阶段4的简化版，放在这里因为这是最低成本的标题来源，且与 save 同一调用点）：
        // 首条 user message 后若标题仍空，取前 30 字。后续若需小模型总结再升级。
        if updated.title.isEmpty {
            let firstUserText = messages
                .first(where: { $0.role == MessageRole.user })?
                .toText()
            if let title = firstUserText, !title.isEmpty {
                updated = retitledCopy(of: updated, title: String(title.prefix(30)))
            }
        }

        await persist(updated)
        if currentConversation?.id == id {
            setCurrent(updated)
        }
        await refreshSummaries()
    }

    /// 重建一个仅 title 不同的 Conversation（Swift 侧无 partial copy，用全字段构造器）。
    /// 所有其他字段保持原值；updateAt 刷新到当前时刻以反映「最近修改」。
    private func retitledCopy(of conversation: Conversation, title: String) -> Conversation {
        Conversation(
            id: conversation.id,
            assistantId: conversation.assistantId,
            title: title,
            messageNodes: conversation.messageNodes,
            chatSuggestions: conversation.chatSuggestions,
            isPinned: conversation.isPinned,
            autoApproveToolCalls: conversation.autoApproveToolCalls,
            createAt: conversation.createAt,
            updateAt: nowInstant(),
            newConversation: conversation.newConversation
        )
    }

    /// 当前时刻的 KotlinInstant。用 epoch 毫秒构造，避免 ISO 字符串 parse 的边界风险
    ///（不同平台对 fractional seconds / 时区偏移的容忍度不一致）。KotlinInstant companion
    /// 的 fromEpochMilliseconds 是无歧义的。
    private func nowInstant() -> KotlinInstant {
        let epochMs = Int64(Date().timeIntervalSince1970 * 1000)
        return KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: epochMs)
    }

    /// 删除会话：磁盘删除 + 刷新 summaries。若删的是 current，自动切到下一条或新建。
    func deleteConversation(id: KotlinUuid) async {
        do {
            try await storage.deleteConversation(id: id)
        } catch {
            print("[IOSConversationStore] deleteConversation failed for \(id): \(error)")
        }
        await refreshSummaries()

        if currentConversation?.id == id {
            if let next = summaries.first {
                await selectConversation(id: next.id)
            } else {
                await newConversation()
            }
        }
    }

    /// 重命名：partial update，不改 messages。
    func renameConversation(id: KotlinUuid, title: String) async {
        do {
            try await storage.updateMetadata(id: id, title: title, isPinned: nil)
        } catch {
            print("[IOSConversationStore] rename failed for \(id): \(error)")
        }
        await refreshSummaries()
        if currentConversation?.id == id, let refreshed = try? await storage.loadConversation(id: id) {
            setCurrent(refreshed)
        }
    }

    /// 置顶/取消置顶切换。
    func togglePin(id: KotlinUuid) async {
        let currentPinned = summaries.first(where: { $0.id == id })?.isPinned ?? false
        let newPinned = KotlinBoolean(value: !currentPinned)
        do {
            try await storage.updateMetadata(id: id, title: nil, isPinned: newPinned)
        } catch {
            print("[IOSConversationStore] togglePin failed for \(id): \(error)")
        }
        await refreshSummaries()
        if currentConversation?.id == id, let refreshed = try? await storage.loadConversation(id: id) {
            setCurrent(refreshed)
        }
    }

    // MARK: - Loading messages into ChatViewModel

    /// 当前会话的 messages，供 ChatViewModel 在切换会话时灌入。
    var currentMessages: [UIMessage] {
        currentConversation?.currentMessages as? [UIMessage] ?? []
    }

    /// Global search over persisted conversation titles and message text.
    ///
    /// Read-only by design: it does not switch current conversation, bump revision,
    /// or mutate metadata. SearchView decides whether to open a result.
    func searchConversations(query rawQuery: String, limit: Int = 60) async -> [IOSConversationSearchResult] {
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty, limit > 0 else { return [] }

        let sourceSummaries: [ConversationSummary]
        if summaries.isEmpty {
            sourceSummaries = (try? await storage.listSummaries()) ?? []
        } else {
            sourceSummaries = summaries
        }

        var results: [IOSConversationSearchResult] = []
        for summary in sourceSummaries {
            let title = summary.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "新对话" : summary.title
            let updateAt = summary.updateAt.toEpochMilliseconds()

            if title.localizedCaseInsensitiveContains(query) {
                results.append(IOSConversationSearchResult(
                    id: "\(summary.id)-title",
                    conversationId: summary.id,
                    kind: .conversation,
                    title: title,
                    preview: "\(summary.messageCount) 条消息",
                    highlight: query,
                    updateAt: updateAt
                ))
                if results.count >= limit { break }
            }

            guard let conversation = try? await storage.loadConversation(id: summary.id) else { continue }
            let messages = Self.searchableMessages(in: conversation)
            for (messageIndex, message) in messages.enumerated() {
                let text = message.toText().trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty, text.localizedCaseInsensitiveContains(query) else { continue }
                results.append(IOSConversationSearchResult(
                    id: "\(summary.id)-message-\(messageIndex)",
                    conversationId: summary.id,
                    kind: .message,
                    title: title,
                    preview: Self.searchPreview(from: text, around: query),
                    highlight: query,
                    updateAt: updateAt
                ))
                if results.count >= limit { break }
            }
            if results.count >= limit { break }
        }
        return results
    }

    /// Read-only export for iOS Board chat-history collection.
    ///
    /// This does not switch `currentConversation`, bump `currentRevision`, mutate metadata,
    /// or write back to disk. It loads recent conversations by summary id and returns a
    /// compact tail window for the Board collector's local heuristics.
    func boardSignalCandidates(limit: Int = 30) async -> [IOSBoardConversationCandidate] {
        let sourceSummaries: [ConversationSummary]
        if summaries.isEmpty {
            sourceSummaries = (try? await storage.listSummaries()) ?? []
        } else {
            sourceSummaries = summaries
        }

        var candidates: [IOSBoardConversationCandidate] = []
        for summary in sourceSummaries.prefix(max(limit, 0)) {
            guard let conversation = try? await storage.loadConversation(id: summary.id) else { continue }
            let title = conversation.title.isEmpty ? summary.title : conversation.title
            let tailNodes = Array(conversation.messageNodes.suffix(6))
            let tailTexts = tailNodes.flatMap { node in
                node.messages.compactMap { message -> String? in
                    let text = message.toText().trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return nil }
                    return "\(Self.boardRoleName(message.role)): \(String(text.prefix(500)))"
                }
            }

            let fallbackTexts = conversation.currentMessages.compactMap { message -> String? in
                let text = message.toText().trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { return nil }
                return "\(Self.boardRoleName(message.role)): \(String(text.prefix(500)))"
            }

            candidates.append(IOSBoardConversationCandidate(
                id: String(describing: conversation.id),
                title: title.isEmpty ? "未命名对话" : title,
                updateAt: conversation.updateAt.toEpochMilliseconds(),
                nodeCount: conversation.messageNodes.count,
                tailTexts: tailTexts.isEmpty ? fallbackTexts : tailTexts
            ))
        }
        return candidates
    }

    // MARK: - Private helpers

    private static func boardRoleName(_ role: MessageRole) -> String {
        if role == MessageRole.user { return "user" }
        if role == MessageRole.assistant { return "assistant" }
        if role == MessageRole.system { return "system" }
        if role == MessageRole.tool { return "tool" }
        return String(describing: role).lowercased()
    }

    private static func searchableMessages(in conversation: Conversation) -> [UIMessage] {
        let nodeMessages = conversation.messageNodes.flatMap { node in
            node.messages
        }
        if !nodeMessages.isEmpty {
            return nodeMessages
        }
        return conversation.currentMessages
    }

    private static func searchPreview(from text: String, around query: String, maxLength: Int = 96) -> String {
        let compact = text
            .split(whereSeparator: \.isNewline)
            .joined(separator: " ")
        guard compact.count > maxLength else { return compact }

        guard let range = compact.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) else {
            return String(compact.prefix(maxLength)) + "..."
        }

        let prefix = String(compact[..<range.lowerBound].suffix(28))
        let remainingLength = max(maxLength - prefix.count, 24)
        let suffix = String(compact[range.lowerBound...].prefix(remainingLength))
        let leading = compact.distance(from: compact.startIndex, to: range.lowerBound) > prefix.count ? "..." : ""
        let trailing = compact.distance(from: range.lowerBound, to: compact.endIndex) > suffix.count ? "..." : ""
        return leading + prefix + suffix + trailing
    }

    /// 替换 currentConversation 并 bump 修订号（驱动 SwiftUI onChange reload）。
    private func setCurrent(_ conversation: Conversation?) {
        currentConversation = conversation
        currentRevision &+= 1
    }

    private func persist(_ conversation: Conversation) async {
        do {
            try await storage.saveConversation(conversation: conversation)
        } catch {
            // 写盘失败：不丢内存会话（currentConversation 仍由调用方更新），
            // 仅打印——PLAN 阶段4的「磁盘满降级」留待后续。
            print("[IOSConversationStore] saveConversation failed: \(error)")
        }
    }

    private func refreshSummaries() async {
        do {
            summaries = try await storage.listSummaries()
        } catch {
            print("[IOSConversationStore] refreshSummaries failed: \(error)")
        }
    }
}

extension IOSConversationStore: IOSBoardConversationSignalSource {}
