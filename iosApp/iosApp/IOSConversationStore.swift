import Foundation
import Observation
@preconcurrency import Shared

// MARK: - IOSUserVisibleError

enum IOSUserVisibleErrorSeverity: String, Equatable {
    case info
    case warning
    case error
}

/// A user-facing error that can be surfaced through the app-level alert bus.
/// Keep it domain-neutral so storage, workspace sync, MCP, memory, and board
/// failures can share the same visible channel without inventing new alerts.
struct IOSUserVisibleError: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let message: String
    let severity: IOSUserVisibleErrorSeverity
}

// MARK: - IOSConversationIOError

/// A storage I/O failure surfaced to the UI (disk full / write failed /
/// corruption). Bound to a top-level `.alert(item:)` so the user learns a
/// save/delete/rename failed instead of silently believing it succeeded.
struct IOSConversationIOError: Identifiable, Equatable {
    let id = UUID()
    let operation: String        // e.g. "save", "delete", "rename"
    let detail: String           // human-readable summary

    var message: String {
        "会话存储「\(operation)」失败：\(detail)。请检查存储空间后重试。"
    }

    var userVisibleError: IOSUserVisibleError {
        IOSUserVisibleError(
            title: "会话存储出错",
            message: message,
            severity: .error
        )
    }
}

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

/// session_search 工具的聚合命中（每会话一条）。由 [IOSConversationStore.sessionSearchHits]
/// 从 [IOSConversationSearchResult] 派生：snippet 优先取消息命中预览（含关键词上下文），
/// messageCount 取会话摘要的真实消息数。
struct IOSSessionSearchHit {
    let conversationId: KotlinUuid
    let title: String
    let snippet: String
    let updatedAt: Int64
    let messageCount: Int32
}

struct IOSConversationWriteBaseline: Equatable {
    fileprivate let key: String
    fileprivate let sequence: UInt64
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

    /// 首页列表 meta 用的 LLM 浓缩预览（会话 id 字符串 → 单行文案）。
    /// 与 KMP `ConversationSummary` 解耦：不改 index schema，落盘在 conversations/list-previews.json。
    private(set) var listPreviewsByConversationId: [String: String] = [:]

    /// 标题 LLM 一并选出的列表图标 key（`HomeConversationIcon.llmCatalog`）。
    /// 不改 conversation schema，落盘在 conversations/list-icons.json。
    private(set) var listIconsByConversationId: [String: String] = [:]

    /// 单调递增的修订号：每次 currentConversation 被替换（新建/切换/删除回退/落盘/分支）时 +1。
    /// 供需要感知「当前会话内容可能变了」的 UI 观察（如 island 标题）。
    /// 注意：它对「同会话落盘」也会 +1，因此不能用它判断「是否切到了别的会话」——
    /// 那是 conversationSwitchedRevision 的职责。把两者混淆会导致 viewport 误把落盘当切会话，
    /// 重建 ScrollView → 抖动 + 上滑看历史被甩回锚点。
    private(set) var currentRevision: Int = 0

    /// 单调递增的「切会话」修订号：**只在真正切换到另一个会话时** +1
    /// （newConversation / selectConversation / 删除当前后退到别的会话）。
    /// 同会话落盘、分支编辑、重命名、置顶**不** bump 它。
    /// 供 viewport/ChatView 观察以判断是否需要重灌历史 + 重新从底部实现落位。
    private(set) var conversationSwitchedRevision: Int = 0

    /// 后台生成/后台工具回填落盘完成的定向信号。只在后台路径 bump——
    /// 普通前台落盘绝不碰它,否则会复发"每次落盘重灌历史"的甩回老 bug。
    private(set) var backgroundContentRevision: Int = 0
    /// 待通知的后台内容落盘会话集合。集合(而非单值)天然免疫同 tick 多会话
    /// 落盘的观察合并;消费由 ChatView 显式调用 consume,门控跳过时不消费,
    /// 生成收尾事件会补查——两类丢通知(覆盖/消费无重试)都由此闭环。
    private(set) var pendingBackgroundContentConversationIds: Set<String> = []

    /// ChatView 在真正完成"上屏"处理后调用，把该会话从待通知集合里移除。
    /// 门控跳过（前台生成中）时不应调用——保留待通知状态，等收尾事件补查。
    func consumeBackgroundContentNotification(for conversationId: String) {
        pendingBackgroundContentConversationIds.remove(conversationId)
    }

    /// 最近一次存储 I/O 错误（磁盘满/写失败/损坏）。绑定到顶层 `.alert(item:)`
    /// 让用户看到失败，避免"以为保存了实际没保存"的静默丢数据。nil = 无未读错误。
    /// 成功刷新列表不会自动清空它；只有用户 dismiss alert 时才清空。
    private(set) var lastIOError: IOSConversationIOError?

    /// App-level visible error bus. `lastIOError` remains for compatibility and
    /// tests, while new domains should publish here directly.
    private(set) var lastUserVisibleError: IOSUserVisibleError?

    /// Dismiss the current IO error (called by the alert's OK action).
    func clearIOError() {
        clearUserVisibleError()
    }

    func clearUserVisibleError() {
        lastIOError = nil
        lastUserVisibleError = nil
    }

    func publishUserVisibleError(_ error: IOSUserVisibleError) {
        lastUserVisibleError = error
    }

    private func publishIOError(operation: String, detail: String) {
        let error = IOSConversationIOError(operation: operation, detail: detail)
        lastIOError = error
        publishUserVisibleError(error.userVisibleError)
    }

    // MARK: - Private

    private let storage: JsonConversationStorage
    private let listPreviewsFileURL: URL
    private let listIconsFileURL: URL
    private var deletedConversationIds: Set<String> = []
    private var writeSequences: [String: UInt64] = [:]

#if DEBUG
    var beforePersistForTesting: ((Conversation) async -> Void)?
    var beforeDeleteForTesting: (() async throws -> Void)?
#endif

    // MARK: - Init

    init(baseDirectory: URL? = nil) {
        // Documents/conversations/ —— iOS Documents 目录会被 iTunes 文件共享暴露，
        // 第一版可接受（便于调试）；后续如要隐藏可换 Application Support。
        let baseDirPath: String
        let previewsURL: URL
        let iconsURL: URL
        if let baseDirectory {
            baseDirPath = baseDirectory.path
            previewsURL = baseDirectory.appendingPathComponent("list-previews.json")
            iconsURL = baseDirectory.appendingPathComponent("list-icons.json")
        } else {
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
            let conversations = documents.appendingPathComponent("conversations")
            baseDirPath = conversations.path
            previewsURL = conversations.appendingPathComponent("list-previews.json")
            iconsURL = conversations.appendingPathComponent("list-icons.json")
        }
        let baseDir = ConversationFile(path: baseDirPath)
        self.storage = JsonConversationStorage(baseDir: baseDir)
        self.listPreviewsFileURL = previewsURL
        self.listIconsFileURL = iconsURL
        self.listPreviewsByConversationId = Self.loadListPreviews(from: previewsURL)
        self.listIconsByConversationId = Self.loadListPreviews(from: iconsURL)
    }

    // MARK: - Bootstrap

    /// App 启动时调用：加载摘要列表，选最近一条作为 current；无历史则新建空会话。
    func bootstrap() async {
        listPreviewsByConversationId = Self.loadListPreviews(from: listPreviewsFileURL)
        listIconsByConversationId = Self.loadListPreviews(from: listIconsFileURL)
        do {
            summaries = try await storage.listSummaries()
        } catch {
            // listSummaries 内部已对损坏 index 做了 rebuild 兜底；到这里的 error 是更底层
            // 的 I/O 故障。不致命：降级为空列表，下面会新建会话，内存里仍可用。
            publishIOError(operation: "读取列表", detail: "\(error)")
            summaries = []
        }

        if let mostRecent = summaries.first {
            // summaries 已按 updateAt 倒序，第一条即最近。
            await selectConversation(id: mostRecent.id)
        } else {
            await newConversation()
        }
    }

    /// 首页 meta 交错用的浓缩预览；无则返回空串。
    func listPreview(for id: KotlinUuid) -> String {
        listPreviewsByConversationId[sequenceKey(for: id)] ?? ""
    }

    /// 写入 LLM 浓缩预览（与标题生成同链路产出）。空串会清除该会话预览。
    /// 已删除会话的 tombstone 会拒绝迟到写入，避免 orphan key 复活。
    func setListPreview(id: KotlinUuid, preview: String) {
        let key = sequenceKey(for: id)
        guard !deletedConversationIds.contains(key) else { return }
        let cleaned = preview.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty {
            guard listPreviewsByConversationId.removeValue(forKey: key) != nil else { return }
        } else if listPreviewsByConversationId[key] == cleaned {
            return
        } else {
            listPreviewsByConversationId[key] = cleaned
        }
        persistListPreviews()
    }

    /// 标题 LLM 选出的列表图标 key；空串清除。
    func listIconKey(for id: KotlinUuid) -> String? {
        listIconsByConversationId[sequenceKey(for: id)]
    }

    func setListIconKey(id: KotlinUuid, key: String?) {
        let storageKey = sequenceKey(for: id)
        guard !deletedConversationIds.contains(storageKey) else { return }
        let cleaned = key?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if cleaned.isEmpty {
            guard listIconsByConversationId.removeValue(forKey: storageKey) != nil else { return }
        } else if listIconsByConversationId[storageKey] == cleaned {
            return
        } else {
            listIconsByConversationId[storageKey] = cleaned
        }
        persistListIcons()
    }

    /// Imports complete Conversation JSON documents through the storage owner.
    /// The KMP implementation validates the whole batch before taking any write
    /// and rebuilds index.json under the same operation mutex.
    @discardableResult
    func importConversationDocuments(_ documents: [String]) async throws -> Int {
        guard !documents.isEmpty else { return 0 }
        try await storage.importConversations(serializedConversations: documents)
        deletedConversationIds.removeAll()
        writeSequences.removeAll()
        await bootstrap()
        return documents.count
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
        let persisted = await persist(conversation) ?? conversation
        setCurrentAsSwitch(persisted)
        await refreshSummaries()
    }

    /// 用户入口的“新建对话”：优先复用当前或最近的空会话，避免连续点击新建制造垃圾空文件。
    /// 底层 `newConversation()` 保持强制新建语义，供测试、删除回退等内部场景使用。
    func startNewConversationReusingEmpty() async {
        if let currentConversation, Self.isReusableEmptyConversation(currentConversation) {
            return
        }

        let sourceSummaries: [ConversationSummary]
        if summaries.isEmpty {
            sourceSummaries = (try? await storage.listSummaries()) ?? []
        } else {
            sourceSummaries = summaries
        }

        if let mostRecent = sourceSummaries.first,
           currentConversation?.id != mostRecent.id,
           !isDeletedConversation(mostRecent.id),
           let conversation = try? await storage.loadConversation(id: mostRecent.id),
           Self.isReusableEmptyConversation(conversation) {
            setCurrentAsSwitch(conversation)
            return
        }

        await newConversation()
    }

    /// 切换到指定会话：从磁盘 load 完整对象，设为 current。
    func selectConversation(id: KotlinUuid) async {
        _ = await selectConversationIfAvailable(id: id)
    }

    @discardableResult
    func selectConversationIfAvailable(
        id: KotlinUuid,
        commitIf: () -> Bool = { true }
    ) async -> Bool {
        guard !isDeletedConversation(id) else { return false }
        let loaded: Conversation?
        do {
            loaded = try await storage.loadConversation(id: id)
        } catch {
            publishIOError(operation: "加载会话", detail: "\(id): \(error)")
            loaded = nil
        }
        guard !isDeletedConversation(id) else { return false }
        if let loaded, commitIf() {
            setCurrentAsSwitch(loaded)
            return true
        }
        // load 失败时不切换 current，保留上一个；UI 层可提示。
        return false
    }

    /// 把当前内存里的 [messages] 回填进 currentConversation（节点合并），落盘，刷新 index。
    /// 流式结束后调用一次（不在每个 chunk 调，避免写盘抖动）。
    func saveCurrent(messages: [UIMessage]) async {
        guard let id = currentConversation?.id else { return }
        await save(messages: messages, to: id)
    }

    func writeBaseline(for id: KotlinUuid) -> IOSConversationWriteBaseline {
        let key = sequenceKey(for: id)
        return IOSConversationWriteBaseline(key: key, sequence: writeSequences[key, default: 0])
    }

    /// 把 [messages] 保存到指定会话 id。用于生成回调按 run 发起时的 conversation 归属落盘，
    /// 避免用户在流式生成期间切换/新建会话后把旧 run 写进当前新会话。
    @discardableResult
    func save(
        messages: [UIMessage],
        to id: KotlinUuid,
        ifUnchangedSince baseline: IOSConversationWriteBaseline? = nil
    ) async -> Bool {
        guard !isDeletedConversation(id) else { return false }
        guard acceptsWrite(to: id, baseline: baseline) else { return false }
        let conversation: Conversation?
        if currentConversation?.id == id {
            conversation = currentConversation
        } else {
            do {
                conversation = try await storage.loadConversation(id: id)
            } catch {
                publishIOError(operation: "保存会话", detail: "目标 \(id): \(error)")
                conversation = nil
            }
        }
        guard let conversation, !isDeletedConversation(id) else { return false }
        guard acceptsWrite(to: id, baseline: baseline) else { return false }

        // updateCurrentMessages 已做 identity 短路：消息没变时返回同一引用，节省落盘。
        var updated = conversation.updateCurrentMessages(messages: messages)
        if updated !== conversation {
            updated = conversationWithNodes(updated, nodes: updated.messageNodes)
        }

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

        guard let persisted = await persist(updated, ifUnchangedSince: baseline) else { return false }
        if currentConversation?.id == id {
            setCurrent(persisted)
        }
        await refreshSummaries()
        return true
    }

    @discardableResult
    func saveBackgroundCompletion(
        baseMessages: [UIMessage],
        completedMessages: [UIMessage],
        to id: KotlinUuid
    ) async -> Bool {
        for _ in 0..<3 {
            guard !isDeletedConversation(id) else { return false }
            let baseline = writeBaseline(for: id)
            let conversation: Conversation?
            if currentConversation?.id == id {
                conversation = currentConversation
            } else {
                do {
                    conversation = try await storage.loadConversation(id: id)
                } catch {
                    publishIOError(operation: "保存后台回复", detail: "目标 \(id): \(error)")
                    conversation = nil
                }
            }
            guard let conversation, !isDeletedConversation(id) else { return false }

            let current = conversation.currentMessages
            let nextMessages: [UIMessage]
            if Self.sameMessageIdentity(current, baseMessages) {
                nextMessages = completedMessages
            } else {
                // M1: 后台 mailbox drain 折入的信封已在 drain 持久化时进入
                // current（displayMessages 快照不含 drain）。终态保存若照旧把
                // completedMessages 的整段 suffix 追加，折入信封会二次落盘，
                // 且「会话期间已有新内容」notice 是误导（差异全部来自后台自己
                // 折入的信封，不是用户新内容）。按消息 id 去重：已落盘的折入
                // 信封跳过；差异全部来自 drain 时以 current 为终态不插 notice。
                let generatedSuffix = Array(completedMessages.dropFirst(baseMessages.count))
                let currentIds = Set(current.map { String(describing: $0.id) })
                let freshSuffix = generatedSuffix.filter { !currentIds.contains(String(describing: $0.id)) }
                let completedIds = Set(completedMessages.map { String(describing: $0.id) })
                let differenceIsOnlyDrain = current.dropFirst(baseMessages.count).allSatisfy {
                    completedIds.contains(String(describing: $0.id))
                }
                if freshSuffix.isEmpty {
                    // 终态相对 base 的新增已全部落盘（折入信封先到）——不重复写。
                    nextMessages = current
                } else if differenceIsOnlyDrain {
                    // 磁盘差异只是后台自己折入的信封：无用户侧新内容，不插 notice。
                    nextMessages = current + freshSuffix
                } else {
                    let notice = UIMessage.companion.assistant(prompt: "后台生成已完成；当前会话期间已有新内容，以下是后台完成的结果。")
                    nextMessages = current + [notice] + freshSuffix
                }
            }
            guard await save(messages: nextMessages, to: id, ifUnchangedSince: baseline) else { continue }
            pendingBackgroundContentConversationIds.insert(String(describing: id))
            backgroundContentRevision &+= 1
            return true
        }
        print("[AA-STORE] background completion save skipped after retries id=\(sequenceKey(for: id))")
        return false
    }

    /// Replaces one background-owned message by id without replaying an old
    /// full conversation snapshot. This preserves messages or branches added
    /// while the background job was running.
    @discardableResult
    func replaceBackgroundMessage(_ replacement: UIMessage, in id: KotlinUuid) async -> Bool {
        let replacementId = String(describing: replacement.id)
        for _ in 0..<3 {
            guard !isDeletedConversation(id) else { return false }
            let baseline = writeBaseline(for: id)
            let conversation: Conversation?
            if currentConversation?.id == id {
                conversation = currentConversation
            } else {
                do {
                    conversation = try await storage.loadConversation(id: id)
                } catch {
                    publishIOError(operation: "更新后台回复", detail: "目标 \(id): \(error)")
                    conversation = nil
                }
            }
            guard let conversation, !isDeletedConversation(id) else { return false }
            var messages = conversation.currentMessages
            guard let index = messages.firstIndex(where: {
                String(describing: $0.id) == replacementId
            }) else {
                return false
            }
            messages[index] = replacement
            guard await save(messages: messages, to: id, ifUnchangedSince: baseline) else { continue }
            pendingBackgroundContentConversationIds.insert(String(describing: id))
            backgroundContentRevision &+= 1
            return true
        }
        print("[AA-STORE] background message replacement skipped after retries id=\(sequenceKey(for: id))")
        return false
    }

    @discardableResult
    func saveBackgroundToolCompletion(
        baseMessages: [UIMessage],
        completedMessages: [UIMessage],
        to id: KotlinUuid
    ) async -> Bool {
        for _ in 0..<3 {
            guard !isDeletedConversation(id) else { return false }
            let baseline = writeBaseline(for: id)
            let conversation: Conversation?
            if currentConversation?.id == id {
                conversation = currentConversation
            } else {
                do {
                    conversation = try await storage.loadConversation(id: id)
                } catch {
                    publishIOError(operation: "保存后台工具结果", detail: "目标 \(id): \(error)")
                    conversation = nil
                }
            }
            guard let conversation, !isDeletedConversation(id) else { return false }

            let current = conversation.currentMessages
            let nextMessages: [UIMessage]
            if Self.sameMessageIdentity(current, baseMessages) {
                nextMessages = completedMessages
            } else {
                let outputs = Self.completedToolOutputs(in: completedMessages)
                let patched = Self.messagesByApplyingToolOutputs(outputs, to: current)
                if patched.didApply {
                    nextMessages = patched.messages
                } else {
                    let notice = UIMessage.companion.assistant(prompt: "后台工具执行已完成；当前会话期间已有新内容，以下是后台完成的结果。")
                    let toolMessages = Self.messagesContainingToolOutputs(outputs, in: completedMessages)
                    nextMessages = current + [notice] + toolMessages
                }
            }
            guard await save(messages: nextMessages, to: id, ifUnchangedSince: baseline) else { continue }
            pendingBackgroundContentConversationIds.insert(String(describing: id))
            backgroundContentRevision &+= 1
            return true
        }
        print("[AA-STORE] background tool completion save skipped after retries id=\(sequenceKey(for: id))")
        return false
    }

    // MARK: - P1-c 线程编排（fork 会话持久化）

    /// 保存一个已构造好的 Conversation（P1-c fork 结果）。与 [save] 不同：目标
    /// 会话文件尚不存在，没有「消息写保留 metadata owner 字段」语义，直接落盘
    /// 并刷新索引。仅当目标是当前会话时才切换 current（fork 目标是新会话，不会）。
    @discardableResult
    func saveForkedConversation(_ conversation: Conversation) async -> Bool {
        guard !isDeletedConversation(conversation.id) else { return false }
        do {
            try await storage.saveConversation(conversation: conversation)
        } catch {
            publishIOError(operation: "保存 fork 会话", detail: "目标 \(conversation.id): \(error)")
            return false
        }
        if currentConversation?.id == conversation.id {
            setCurrent(conversation)
        }
        await refreshSummaries()
        return true
    }

    /// 按 id 读取完整 Conversation（P1-c fork 源读取；当前会话优先走内存）。
    func loadConversationForOrchestration(_ id: KotlinUuid) async throws -> Conversation? {
        guard !isDeletedConversation(id) else { return nil }
        return try await storage.loadConversation(id: id)
    }

    // MARK: - P2-a 会话记忆污染（memoryMode）

    /// harness 置位：ENABLED→POLLUTED（KMP 层原子 RMW，幂等只升不降，不会被
    /// 并发消息写回滚）。失败记日志不抛——置位不得阻塞工具结果。
    @discardableResult
    func markConversationMemoryPolluted(_ id: KotlinUuid) async -> Bool {
        do {
            return try await storage.updateMemoryMode(id: id, memoryMode: .polluted).boolValue
        } catch {
            publishIOError(operation: "标记会话记忆污染", detail: "目标 \(id): \(error)")
            return false
        }
    }

    /// 用户手动复位：POLLUTED→ENABLED（KMP 层唯一允许的降级路径）。
    @discardableResult
    func resetConversationMemoryPollution(_ id: KotlinUuid) async -> Bool {
        do {
            let updated = try await storage.updateMemoryMode(id: id, memoryMode: .enabled).boolValue
            if updated {
                await refreshSummaries()
            }
            return updated
        } catch {
            publishIOError(operation: "恢复会话记忆", detail: "目标 \(id): \(error)")
            return false
        }
    }

    /// 只列出 POLLUTED 会话（设置页「受外部内容影响的会话」列表投影）。实时读盘，
    /// 不依赖内存 currentConversation。
    func pollutedConversationSummaries() async -> [ConversationSummary] {
        let all = (try? await storage.listSummaries()) ?? []
        return all.filter { $0.memoryMode == .polluted }
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
            memoryMode: conversation.memoryMode,
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

    /// 删除会话：只有磁盘删除确认后才执行 owner 清理并切换 current。
    @discardableResult
    func deleteConversation(
        id: KotlinUuid,
        onDeletionCommitted: () -> Void = {}
    ) async -> Bool {
        markDeletedConversation(id)
        do {
#if DEBUG
            if let beforeDeleteForTesting {
                try await beforeDeleteForTesting()
            }
#endif
            try await storage.deleteConversation(id: id)
        } catch {
            let conversationStillExists: Bool
            do {
                conversationStillExists = try await storage.loadConversation(id: id) != nil
            } catch {
                conversationStillExists = true
            }
            guard !conversationStillExists else {
                unmarkDeletedConversation(id)
                publishIOError(operation: "删除会话", detail: "\(id): \(error)")
                return false
            }
            publishIOError(operation: "删除会话", detail: "\(id): \(error)")
        }

        onDeletionCommitted()
        pendingBackgroundContentConversationIds.remove(String(describing: id))
        if listPreviewsByConversationId.removeValue(forKey: sequenceKey(for: id)) != nil {
            persistListPreviews()
        }
        if listIconsByConversationId.removeValue(forKey: sequenceKey(for: id)) != nil {
            persistListIcons()
        }
        await refreshSummaries()

        if currentConversation?.id == id {
            if let next = summaries.first {
                await selectConversation(id: next.id)
            } else {
                await newConversation()
            }
        }
        return true
    }

    /// 重命名：partial update，不改 messages。
    func renameConversation(id: KotlinUuid, title: String) async {
        _ = await updateConversationTitle(id: id, title: title, expectedCurrentTitle: nil)
    }

    /// 自动标题只能替换生成开始时看到的标题，避免迟到结果覆盖用户手工重命名。
    @discardableResult
    func renameConversation(
        id: KotlinUuid,
        title: String,
        ifCurrentTitleMatches expectedTitle: String
    ) async -> Bool {
        await updateConversationTitle(
            id: id,
            title: title,
            expectedCurrentTitle: expectedTitle
        )
    }

    private func updateConversationTitle(
        id: KotlinUuid,
        title: String,
        expectedCurrentTitle: String?
    ) async -> Bool {
        do {
            if let expectedCurrentTitle {
                let didUpdate = try await storage.updateTitleIfCurrentTitleMatches(
                    id: id,
                    title: title,
                    expectedTitle: expectedCurrentTitle
                ).boolValue
                guard didUpdate else { return false }
            } else {
                try await storage.updateMetadata(id: id, title: title, isPinned: nil)
            }
            advanceWriteSequence(for: id)
        } catch {
            publishIOError(operation: "重命名", detail: "\(id): \(error)")
            return false
        }
        await refreshSummaries()
        if currentConversation?.id == id, let refreshed = try? await storage.loadConversation(id: id) {
            setCurrent(refreshed)
        }
        return true
    }

    /// 置顶/取消置顶切换。
    func togglePin(id: KotlinUuid) async {
        let currentPinned = summaries.first(where: { $0.id == id })?.isPinned ?? false
        let newPinned = KotlinBoolean(value: !currentPinned)
        do {
            try await storage.updateMetadata(id: id, title: nil, isPinned: newPinned)
            advanceWriteSequence(for: id)
        } catch {
            publishIOError(operation: "置顶", detail: "\(id): \(error)")
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

    func messages(for id: KotlinUuid) async -> [UIMessage]? {
        if currentConversation?.id == id {
            return currentMessages
        }
        guard !isDeletedConversation(id) else { return nil }
        do {
            return try await storage.loadConversation(id: id)?.currentMessages as? [UIMessage]
        } catch {
            publishIOError(operation: "加载会话", detail: "\(id): \(error)")
            return nil
        }
    }

    func currentConversationDeepReadSource(maxMessages: Int = 12) throws -> IOSDeepReadSource {
        let messages = currentMessages.suffix(max(maxMessages, 1)).compactMap { message -> String? in
            let text = message.toText().trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            return "\(Self.boardRoleName(message.role)): \(String(text.prefix(1_200)))"
        }
        let title = currentConversation?.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            ? currentConversation?.title ?? "当前会话"
            : "当前会话"
        return try IOSDeepReadSourceNormalizer.conversationSource(
            title: title,
            messages: messages
        )
    }

    @discardableResult
    func appendDeepReadResultToCurrentConversation(_ task: IOSDeepReadTask) async -> Bool {
        guard let conversation = currentConversation else { return false }
        let markdown = task.resultMarkdown.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !markdown.isEmpty else { return false }

        var nextMessages = currentMessages
        let message = UIMessage.companion.assistant(
            prompt: """
            已保存深度阅读结果：\(task.title)

            \(markdown)
            """
        )
        nextMessages.append(message)
        await save(messages: nextMessages, to: conversation.id)
        return true
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

    /// session_search 工具用：把 [searchConversations] 的逐条命中聚合为每会话一条。
    /// 只读（不切当前会话、不 bump revision、不写盘）；snippet 优先取消息命中预览
    /// （含关键词上下文），标题命中兜底；messageCount 取会话摘要的真实消息数。
    func sessionSearchHits(query: String, limit: Int = 8) async -> [IOSSessionSearchHit] {
        let results = await searchConversations(query: query, limit: limit)
        guard !results.isEmpty else { return [] }

        let sourceSummaries: [ConversationSummary]
        if summaries.isEmpty {
            sourceSummaries = (try? await storage.listSummaries()) ?? []
        } else {
            sourceSummaries = summaries
        }
        let countByConversation = Dictionary(
            sourceSummaries.map { ($0.id.toHexDashString(), $0.messageCount) },
            uniquingKeysWith: { first, _ in first }
        )

        var orderedKeys: [String] = []
        var byConversation: [String: (id: KotlinUuid, title: String, titleSnippet: String?, messageSnippet: String?, updatedAt: Int64)] = [:]
        for result in results {
            let key = result.conversationId.toHexDashString()
            if byConversation[key] == nil {
                orderedKeys.append(key)
                byConversation[key] = (result.conversationId, result.title, nil, nil, result.updateAt)
            }
            guard var entry = byConversation[key] else { continue }
            if result.kind == .message {
                if entry.messageSnippet == nil { entry.messageSnippet = result.preview }
            } else if entry.titleSnippet == nil {
                entry.titleSnippet = result.preview
            }
            entry.updatedAt = max(entry.updatedAt, result.updateAt)
            byConversation[key] = entry
        }

        var hits: [IOSSessionSearchHit] = []
        for key in orderedKeys {
            guard let entry = byConversation[key] else { continue }
            hits.append(IOSSessionSearchHit(
                conversationId: entry.id,
                title: entry.title,
                snippet: entry.messageSnippet ?? entry.titleSnippet ?? "",
                updatedAt: entry.updatedAt,
                messageCount: countByConversation[key] ?? 0
            ))
        }
        return hits
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

    private static func isReusableEmptyConversation(_ conversation: Conversation) -> Bool {
        !searchableMessages(in: conversation).contains { $0.role == MessageRole.user }
    }

    private static func sameMessageIdentity(_ lhs: [UIMessage], _ rhs: [UIMessage]) -> Bool {
        guard lhs.count == rhs.count else { return false }
        return zip(lhs, rhs).allSatisfy { left, right in
            String(describing: left.id) == String(describing: right.id)
        }
    }

    private static func completedToolOutputs(in messages: [UIMessage]) -> [String: UIMessagePart.Tool] {
        var outputs: [String: UIMessagePart.Tool] = [:]
        for message in messages where message.role == MessageRole.assistant {
            for case let tool as UIMessagePart.Tool in message.parts where !tool.output.isEmpty {
                outputs[chatToolCallKey(tool)] = tool
            }
        }
        return outputs
    }

    private static func messagesByApplyingToolOutputs(
        _ outputs: [String: UIMessagePart.Tool],
        to messages: [UIMessage]
    ) -> (messages: [UIMessage], didApply: Bool) {
        guard !outputs.isEmpty else { return (messages, false) }
        var didApply = false
        let updated = messages.map { message -> UIMessage in
            guard message.role == MessageRole.assistant else { return message }
            var changed = false
            let parts = message.parts.map { part -> UIMessagePart in
                guard let tool = part as? UIMessagePart.Tool,
                      tool.output.isEmpty,
                      let completed = outputs[chatToolCallKey(tool)] else {
                    return part
                }
                changed = true
                didApply = true
                return completed
            }
            guard changed else { return message }
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
        return (updated, didApply)
    }

    private static func messagesContainingToolOutputs(
        _ outputs: [String: UIMessagePart.Tool],
        in messages: [UIMessage]
    ) -> [UIMessage] {
        guard !outputs.isEmpty else { return [] }
        let keys = Set(outputs.keys)
        return messages.filter { message in
            guard message.role == MessageRole.assistant else { return false }
            return message.parts.contains { part in
                guard let tool = part as? UIMessagePart.Tool else { return false }
                return keys.contains(chatToolCallKey(tool))
            }
        }
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

    /// 替换 currentConversation 并 bump currentRevision（驱动需要感知内容变化的 UI）。
    /// 不 bump conversationSwitchedRevision——调用方若代表「切到了别的会话」，
    /// 应改用 setCurrentAsSwitch。
    private func setCurrent(_ conversation: Conversation?) {
        currentConversation = conversation
        currentRevision &+= 1
    }

    /// 切换到另一个会话时调用：同时 bump currentRevision 与 conversationSwitchedRevision，
    /// 让 viewport 能区分「真切换」与「同会话落盘/编辑」。
    private func setCurrentAsSwitch(_ conversation: Conversation?) {
        currentConversation = conversation
        currentRevision &+= 1
        conversationSwitchedRevision &+= 1
    }

    // MARK: - Message branching (Android ChatService parity)
    //
    // The KMP `Conversation` already carries a `List<MessageNode>` tree where
    // each node holds sibling `UIMessage` variants + a `selectIndex`. iOS loads
    // and persists this tree today; it just never creates or navigates
    // branches. These operations mirror Android's `ChatService` semantics
    // (selectMessageNode / editMessage / regenerateAtMessage / deleteMessage):
    //
    //   - flat message index == node index (currentMessages = messageNodes.map
    //     { node.messages[selectIndex] }), so callers address a branch by the
    //     index of the message it shows in the flat chat list.
    //   - edit/regenerate = APPEND a new variant to the node + select it (the
    //     original is retained and reachable via selectVariant).
    //   - delete = remove the message from its node; drop the node if it
    //     becomes empty.

    /// Variant info for a node, exposed so the UI can show `< 2/3 >` only when
    /// a node actually carries more than one sibling.
    struct VariantInfo: Equatable {
        let variantCount: Int
        let selectedIndex: Int
        var hasMultipleVariants: Bool { variantCount > 1 }
    }

    func variantInfo(forMessageIndex index: Int) -> VariantInfo? {
        guard let conversation = currentConversation,
              index >= 0, index < conversation.messageNodes.count else {
            return nil
        }
        let node = conversation.messageNodes[index]
        // selectIndex bridges as Int32 (Kotlin Int → Obj-C Int32).
        return VariantInfo(variantCount: node.messages.count, selectedIndex: Int(node.selectIndex))
    }

    /// Switch the selected variant of a node (cheap, no generation). Mirrors
    /// `ChatService.selectMessageNode`.
    func selectVariant(messageIndex: Int, variantIndex: Int) async {
        guard let conversation = currentConversation,
              messageIndex >= 0, messageIndex < conversation.messageNodes.count else {
            return
        }
        var nodes = conversation.messageNodes
        let node = nodes[messageIndex]
        guard variantIndex >= 0, variantIndex < node.messages.count, variantIndex != node.selectIndex else {
            return
        }
        nodes[messageIndex] = MessageNode(
            id: node.id,
            messages: node.messages,
            selectIndex: Int32(variantIndex),
            isFavorite: node.isFavorite
        )
        let updated = conversationWithNodes(conversation, nodes: nodes)
        guard let persisted = await persist(updated) else { return }
        setCurrent(persisted)
    }

    /// Append a new variant to a node and select it. This is the shared
    /// primitive for edit-message and regenerate-assistant. Mirrors
    /// `ChatService.editMessage` / the append path of `mergeGeneratedMessagesByIndex`.
    /// Returns the node index so the caller (e.g. ChatViewModel) can target
    /// generation at it.
    @discardableResult
    func appendVariant(messageIndex: Int, message: UIMessage) async -> Int? {
        guard let conversation = currentConversation,
              messageIndex >= 0, messageIndex < conversation.messageNodes.count else {
            return nil
        }
        var nodes = conversation.messageNodes
        let node = nodes[messageIndex]
        var newMessages = node.messages
        newMessages.append(message)
        // Kotlin List bridges to NSArray-ish; use count-1 instead of lastIndex
        // to avoid ambiguity. selectIndex is Int32 (Kotlin Int → Obj-C Int32).
        nodes[messageIndex] = MessageNode(
            id: node.id,
            messages: newMessages,
            selectIndex: Int32(newMessages.count - 1),
            isFavorite: node.isFavorite
        )
        let updated = conversationWithNodes(conversation, nodes: nodes)
        guard let persisted = await persist(updated) else { return nil }
        setCurrent(persisted)
        return messageIndex
    }

    /// Append an assistant regeneration as a sibling variant of the original
    /// assistant node, select it, and drop stale nodes after that reply.
    @discardableResult
    func appendVariantAndTruncateAfter(
        messageIndex: Int,
        message: UIMessage,
        trailingMessages: [UIMessage] = [],
        conversationId: KotlinUuid
    ) async -> Bool {
        let conversation: Conversation?
        if currentConversation?.id == conversationId {
            conversation = currentConversation
        } else {
            do {
                conversation = try await storage.loadConversation(id: conversationId)
            } catch {
                publishIOError(operation: "保存重新生成分支", detail: "\(conversationId): \(error)")
                conversation = nil
            }
        }
        guard let conversation,
              messageIndex >= 0,
              messageIndex < conversation.messageNodes.count else {
            return false
        }
        var nodes = Array(conversation.messageNodes.prefix(messageIndex + 1))
        let node = nodes[messageIndex]
        var variants = node.messages
        variants.append(message)
        nodes[messageIndex] = MessageNode(
            id: node.id,
            messages: variants,
            selectIndex: Int32(variants.count - 1),
            isFavorite: node.isFavorite
        )
        nodes.append(contentsOf: trailingMessages.map { trailingMessage in
            MessageNode(
                id: KotlinUuid.companion.random(),
                messages: [trailingMessage],
                selectIndex: 0,
                isFavorite: false
            )
        })
        let updated = conversationWithNodes(conversation, nodes: nodes)
        guard let persisted = await persist(updated) else { return false }
        if currentConversation?.id == conversationId {
            setCurrent(persisted)
        }
        await refreshSummaries()
        return true
    }

    /// Remove a message from its node. If the node becomes empty it is removed
    /// (and selectIndex clamped). Mirrors `ChatService.deleteMessage`.
    func deleteMessage(messageIndex: Int) async {
        guard let conversation = currentConversation,
              messageIndex >= 0, messageIndex < conversation.messageNodes.count else {
            return
        }
        var nodes = conversation.messageNodes
        // A node only ever holds the currently-selected variant for deletion
        // purposes on iOS (the flat list shows the selected variant); drop the
        // whole node. If we later support per-variant delete, this is where the
        // `node.messages.remove(at:)` + clamp logic would go.
        nodes.remove(at: messageIndex)
        let updated = conversationWithNodes(conversation, nodes: nodes)
        guard let persisted = await persist(updated) else { return }
        setCurrent(persisted)
        await refreshSummaries()
    }

    /// Truncate the conversation to end at (and include) the given node index.
    /// Used by regenerate-from-USER-message: Android drops everything after the
    /// user turn, then re-runs generation. Mirrors `ChatService.regenerateAtMessage`
    /// (role == USER branch).
    func truncateAfter(messageIndex: Int) async {
        guard let conversation = currentConversation,
              messageIndex >= 0, messageIndex < conversation.messageNodes.count else {
            return
        }
        let kept = Array(conversation.messageNodes.prefix(messageIndex + 1))
        guard kept.count != conversation.messageNodes.count else { return }
        let updated = conversationWithNodes(conversation, nodes: kept)
        guard let persisted = await persist(updated) else { return }
        setCurrent(persisted)
    }

    /// Rebuild a Conversation with a different `messageNodes` list (full-field
    /// constructor, same pattern as `retitledCopy`). updateAt refreshed.
    private func conversationWithNodes(_ conversation: Conversation, nodes: [MessageNode]) -> Conversation {
        Conversation(
            id: conversation.id,
            assistantId: conversation.assistantId,
            title: conversation.title,
            messageNodes: nodes,
            chatSuggestions: conversation.chatSuggestions,
            isPinned: conversation.isPinned,
            autoApproveToolCalls: conversation.autoApproveToolCalls,
            memoryMode: conversation.memoryMode,
            createAt: conversation.createAt,
            updateAt: nowInstant(),
            newConversation: conversation.newConversation
        )
    }

    @discardableResult
    private func persist(
        _ conversation: Conversation,
        ifUnchangedSince baseline: IOSConversationWriteBaseline? = nil
    ) async -> Conversation? {
        do {
            guard !isDeletedConversation(conversation.id) else { return nil }
            guard acceptsWrite(to: conversation.id, baseline: baseline) else { return nil }
#if DEBUG
            if let beforePersistForTesting {
                await beforePersistForTesting(conversation)
            }
#endif
            guard !isDeletedConversation(conversation.id) else { return nil }
            guard acceptsWrite(to: conversation.id, baseline: baseline) else { return nil }
            try await storage.saveConversation(conversation: conversation)
            advanceWriteSequence(for: conversation.id)
            return try await storage.loadConversation(id: conversation.id) ?? conversation
        } catch {
            // 写盘失败：不丢内存会话（currentConversation 仍由调用方更新），
            // 但上抛给用户，避免"以为保存了实际没保存"的静默丢数据。
            publishIOError(operation: "保存会话", detail: "\(error)")
            return nil
        }
    }

    private func acceptsWrite(to id: KotlinUuid, baseline: IOSConversationWriteBaseline?) -> Bool {
        guard let baseline else { return true }
        let key = sequenceKey(for: id)
        guard key == baseline.key else { return false }
        guard writeSequences[key, default: 0] == baseline.sequence else {
            print("[AA-STORE] skip stale snapshot write id=\(key) baseline=\(baseline.sequence) current=\(writeSequences[key, default: 0])")
            return false
        }
        return true
    }

    private func advanceWriteSequence(for id: KotlinUuid) {
        let key = sequenceKey(for: id)
        writeSequences[key, default: 0] &+= 1
    }

    private func sequenceKey(for id: KotlinUuid) -> String {
        String(describing: id)
    }

    private static func loadListPreviews(from url: URL) -> [String: String] {
        guard let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([String: String].self, from: data) else {
            return [:]
        }
        return decoded.filter { !$0.key.isEmpty && !$0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    private func persistListPreviews() {
        do {
            let directory = listPreviewsFileURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(listPreviewsByConversationId)
            try data.write(to: listPreviewsFileURL, options: .atomic)
        } catch {
            publishIOError(operation: "保存会话预览", detail: "\(error)")
        }
    }

    private func persistListIcons() {
        do {
            let directory = listIconsFileURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(listIconsByConversationId)
            try data.write(to: listIconsFileURL, options: .atomic)
        } catch {
            publishIOError(operation: "保存会话图标", detail: "\(error)")
        }
    }

    private func refreshSummaries() async {
        do {
            summaries = try await storage.listSummaries()
                .filter { !isDeletedConversation($0.id) }
        } catch {
            publishIOError(operation: "刷新列表", detail: "\(error)")
        }
    }

    private func markDeletedConversation(_ id: KotlinUuid) {
        deletedConversationIds.insert(String(describing: id))
    }

    private func unmarkDeletedConversation(_ id: KotlinUuid) {
        deletedConversationIds.remove(String(describing: id))
    }

    private func isDeletedConversation(_ id: KotlinUuid) -> Bool {
        deletedConversationIds.contains(String(describing: id))
    }
}

extension IOSConversationStore: IOSBoardConversationSignalSource {}
