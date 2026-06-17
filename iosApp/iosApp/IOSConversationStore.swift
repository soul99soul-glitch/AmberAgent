import Foundation
import Observation
@preconcurrency import Shared

// MARK: - IOSConversationStore

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

    init() {
        // Documents/conversations/ —— iOS Documents 目录会被 iTunes 文件共享暴露，
        // 第一版可接受（便于调试）；后续如要隐藏可换 Application Support。
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let baseDirPath = documents.appendingPathComponent("conversations").path
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
        guard let conversation = currentConversation else { return }
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
        setCurrent(updated)
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

    // MARK: - Private helpers

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
