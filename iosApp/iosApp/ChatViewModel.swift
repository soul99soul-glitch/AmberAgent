import Foundation
import CryptoKit
import Observation
import OSLog
import SwiftUI
@preconcurrency import Shared

// MARK: - ChatViewModel

private let chatLedgerLogger = Logger(subsystem: "app.amber.ios", category: "chat-ledger")

struct ChatContextSnapshot {
    let messageCount: Int
    let modelId: String
    let supportsReasoning: Bool
    let pendingSelectedFileName: String?
    let pendingSelectedFileBytesText: String?
    // [Slice 5] Real token aggregation from messages.usage (TokenUsage on
    // each UIMessage; aggregated by reduce over messages). 0 when no usage
    // recorded (e.g. no API key / no completed runs yet) — honest, not faked.
    let promptTokens: Int
    let completionTokens: Int
    let totalTokens: Int
    let cachedTokens: Int
    let tokensPerSecond: Double?
    /// 当前模型的真实上下文窗口(token)。模型未声明时为 nil。
    let contextWindowTokens: Int?
    /// 当前上下文实际占用(token):取最近一轮的 promptTokens + completionTokens。
    /// 每轮的 promptTokens 已含到该轮为止的全部历史,所以这就是"现在窗口里有多少",
    /// 用它驱动用量环 —— 不能用 totalTokens(那是逐轮累加和,会严重重复计数)。
    let currentContextTokens: Int
}

enum ChatContextCompactStatus: Equatable {
    case idle
    case planning
    case compacting
    case completed
    case failed
}

struct ChatContextCompactState: Equatable {
    var status: ChatContextCompactStatus
    var summary: String
    var updatedAt: Date

    static let idle = ChatContextCompactState(status: .idle, summary: "", updatedAt: Date.distantPast)

    var isActive: Bool {
        status == .planning || status == .compacting
    }

    var isVisible: Bool {
        isActive || status == .completed || status == .failed
    }
}

extension ChatContextSnapshot {
    /// 未声明上下文窗口的模型的默认兜底(token)。当下主流模型基本都 ≥100万,
    /// 几乎不存在比 20万更小的,故用 20万作保守默认 —— 比旧的 8K 合理得多。
    static let defaultContextWindowTokens: Double = 200_000

    /// 上下文用量比例 [0,1],驱动用量环与上下文面板的填充。分子是"当前占用"
    /// (currentContextTokens,非累加和);分母优先用模型真实 contextWindow,模型未声明
    /// 窗口时回退到 defaultContextWindowTokens(20万)。
    var contextFillFraction: CGFloat {
        let ceiling = contextWindowTokens.flatMap { $0 > 0 ? Double($0) : nil } ?? Self.defaultContextWindowTokens
        return min(CGFloat(Double(currentContextTokens) / ceiling), 1.0)
    }
}

private struct PendingAssistantRegeneration {
    let conversationId: KotlinUuid
    let targetMessageIndex: Int
    let generatedMessageIndex: Int
}

enum ChatComposerSendBlockReason: Equatable {
    case emptyInput
    case generationActive
    case steerQueueFull
    case attachingSelectedFile
    case recognizingImages
    case pendingApproval
    /// P1-e: 当前会话是编排子线程（存在 thread_edge 即只读，含 interrupt 取消/
    /// 已完成线程）。UI 只消费 disabled 态；文案由 VM 提供（零 UI 改动）。
    case orchestratedThread
    case configuration(ChatConfigurationIssue)

    /// P1-e: 用户可见文案（现有 UI 只按 `== nil` 禁用发送键；文案留给后续
    /// UI 分支/语音播报，测试断言使用）。
    var userVisibleMessage: String? {
        switch self {
        case .orchestratedThread:
            return "此会话由父线程编排，暂不支持直接输入"
        case .configuration(let issue):
            return issue.message
        default:
            return nil
        }
    }
}

@MainActor
@Observable
final class ChatViewModel {

    private struct ImageGenerationResumeCacheEntry {
        let summaryUpdatedAtMillis: Int64
        let isGenerationActive: Bool
        let context: ChatImageGenerationResumeContext?
    }

    // MARK: - State

    var messages: [UIMessage] = []
    var inputText: String = ""
    var isLoading: Bool = false
    /// 助手回复完成后用 suggestionModelId 生成的对话建议(输入框上方胶囊)。
    /// 用户发送或切换会话时清空。
    var chatSuggestions: [String] = []
    var isAttachingSelectedFile: Bool = false
    var pendingSelectedFilePreview: SelectedDocumentReadResult?
    var pendingImages: [PendingChatImage] = []
    var isRecognizingImages = false
    var selectedFileContextError: String?
    var reasoningLevel: ReasoningLevel = .off
    var messageRevision: Int = 0
    var messageUpdateSignal = ChatMessageUpdateSignal()
    /// token 聚合只在结构性消息事件后重算；流式 delta 不改 usage，跳过 O(n) 扫描。
    @ObservationIgnored private var tokenRevision: Int = 0
    @ObservationIgnored private var cachedTokenSnapshot: ChatContextSnapshot?
    @ObservationIgnored private var cachedTokenRevision: Int = -1
    @ObservationIgnored private var imageGenerationResumeCache: [String: ImageGenerationResumeCacheEntry] = [:]
    @ObservationIgnored private var imageGenerationResumeCacheStoreID: ObjectIdentifier?
    /// UI presentation pacing is useful only while the live tail is being watched.
    /// The authoritative stream accumulator is independent from this flag.
    var streamPresentationPacingEnabled = true
    var pendingMemoryApproval: MemoryToolApprovalRequest?
    var pendingSearchApproval: SearchToolApprovalRequest?
    var pendingWebMountApproval: WebMountToolApprovalRequest?
    var pendingWorkspaceApproval: WorkspaceToolApprovalRequest?
    var pendingIshHandoffApproval: IshHandoffToolApprovalRequest?
    var pendingMcpApproval: McpToolApprovalRequest?
    var pendingCouncilApproval: CouncilToolApprovalRequest?
    var pendingAskUser: ChatAskUserRequest?
    /// Wave B2: recipe 审批卡（mutation step / recipe_import）。
    var pendingRecipeApproval: RecipeToolApprovalRequest?

    var configurationError: String?
    var contextCompactState: ChatContextCompactState = .idle

    // MARK: - Steer 队列（P1-a）

    /// 生成激活期间排队、待折入下一轮模型请求的 user 消息（v1 仅文本 STEER）。
    /// 内存队列唯一 owner 是本 ViewModel；`ChatGenerationCoordinator` 在工具循环
    /// 边界经 bindings 消费，不持有第二份队列。切会话由 `reloadFromStore()` 重灌。
    private(set) var steerQueue: [IOSSteerQueueEntry] = []
    /// 磁盘镜像（Documents/steer-queue/{conversationId}.json），进程死亡后队列不丢。
    @ObservationIgnored private let steerQueueStore: IOSSteerQueueStore
    /// P1-b: mailbox 信封访问层（Room 即真相，无内存态；drain 事务化 exactly-once）。
    @ObservationIgnored private let mailboxStore: IOSMailboxStore
    /// P1-d: mailbox 活动广播（wait_agent 事件源；steer 打断 wait 的信号点）。
    @ObservationIgnored private let mailboxActivityCenter: IOSMailboxActivityCenter

    /// 顶部活动岛「等待确认」聚合信号：与 sendMessage 的 pending 门禁集合保持一致。
    var hasPendingUserGate: Bool {
        pendingMemoryApproval != nil || pendingSearchApproval != nil ||
            pendingWebMountApproval != nil || pendingWorkspaceApproval != nil ||
            pendingIshHandoffApproval != nil || pendingMcpApproval != nil ||
            pendingCouncilApproval != nil || pendingAskUser != nil ||
            pendingRecipeApproval != nil
    }

    /// 顶部活动岛等待连接副标题（取不到则 nil，文案规则：宁缺毋滥）。
    var islandModelDisplayName: String? { currentModel?.displayName }

    /// 识别图片期间的总张数（>1 时才作为副标题事实展示）。
    private(set) var visionRecognitionImageCount = 0
    private static let visionRecognitionPendingMessage = "图片识别中，请稍候"
    @ObservationIgnored private var visionRecognitionTask: Task<Void, Never>?
    private var visionRecognitionRequestId: UUID?
    private var visionRecognitionConversationId: KotlinUuid?

    /// 持久化存储（由 AppShell 注入）。nil 时退化为纯内存模式（向后兼容旧调用方）。
    weak var conversationStore: IOSConversationStore?

    /// 当前会话 id，与 conversationStore.currentConversation.id 同步。
    /// 留作快速访问；切换会话后由 reloadFromStore() 刷新。
    var currentConversationId: KotlinUuid? {
        conversationStore?.currentConversation?.id
    }

    // MARK: - P1-e 编排子线程只读缓存

    /// 当前会话是否编排子线程（存在 thread_edge）。composerSendBlockReason 每条
    /// keystroke 都读，必须缓存；会话切换时由 reloadFromStore() 异步刷新（经
    /// 注入的编排服务查一次 Room）。测试可 `await orchestratedStatusRefreshTask?.value`。
    @ObservationIgnored private(set) var currentConversationIsOrchestratedChild = false
    @ObservationIgnored private(set) var orchestratedStatusRefreshTask: Task<Void, Never>?
    /// 当前会话是否参与线程树（是子线程或有子线程）。参与的会话才注入 mailbox
    /// 语义说明（普通会话不付这笔 token）；与只读判定同一刷新任务。
    @ObservationIgnored private(set) var currentConversationHasOrchestrationLinks = false

    /// 刷新当前会话的子线程判定（Room 查询一次）。reloadFromStore 与测试共用。
    func refreshCurrentConversationOrchestratedStatus() async {
        guard let conversationId = currentConversationId else {
            currentConversationIsOrchestratedChild = false
            currentConversationHasOrchestrationLinks = false
            return
        }
        let isChild = await orchestrationToolService.isOrchestratedChild(conversationId: conversationId)
        currentConversationIsOrchestratedChild = isChild
        if isChild {
            currentConversationHasOrchestrationLinks = true
        } else {
            currentConversationHasOrchestrationLinks = await orchestrationToolService
                .hasOrchestrationChildren(conversationId: conversationId)
        }
    }

    /// P1-e: 供会话列表徽标/只读标识查询（ConversationsView 在用户 WIP 中，本轮
    /// 不加视觉徽标；此查询留待后续 UI 接线）。
    func isOrchestratedChild(conversationId: KotlinUuid) async -> Bool {
        await orchestrationToolService.isOrchestratedChild(conversationId: conversationId)
    }

    var contextSnapshot: ChatContextSnapshot {
        // O(n) token 聚合按 tokenRevision 缓存：流式 delta 不改 usage，跳过重算。
        // 廉价字段（modelId、filePreview）每次 live 读取，不进缓存。
        let cached: ChatContextSnapshot
        if let hit = cachedTokenSnapshot, cachedTokenRevision == tokenRevision {
            cached = hit
        } else {
            cached = Self.aggregateTokenSnapshot(messages)
            cachedTokenSnapshot = cached
            cachedTokenRevision = tokenRevision
        }
        return ChatContextSnapshot(
            messageCount: messages.count,
            modelId: currentModelId,
            supportsReasoning: currentModelSupportsReasoning,
            pendingSelectedFileName: pendingSelectedFilePreview?.fileName,
            pendingSelectedFileBytesText: pendingSelectedFilePreview?.byteSummary,
            promptTokens: cached.promptTokens,
            completionTokens: cached.completionTokens,
            totalTokens: cached.totalTokens,
            cachedTokens: cached.cachedTokens,
            tokensPerSecond: cached.tokensPerSecond,
            contextWindowTokens: currentModel?.contextWindowTokens.map { Int(truncating: $0) },
            currentContextTokens: cached.currentContextTokens
        )
    }

    private static func aggregateTokenSnapshot(_ messages: [UIMessage]) -> ChatContextSnapshot {
        var prompt = 0
        var completion = 0
        var cached = 0
        var timedCompletionTokens = 0
        var generationDuration = 0.0
        var latestContextTokens = 0
        for message in messages {
            guard let usage = message.usage else { continue }
            prompt += Int(usage.promptTokens)
            completion += Int(usage.completionTokens)
            cached += Int(usage.cachedTokens)
            latestContextTokens = Int(usage.promptTokens) + Int(usage.completionTokens)
            if let duration = durationSeconds(from: message.createdAt, to: message.finishedAt),
               duration > 0 {
                timedCompletionTokens += Int(usage.completionTokens)
                generationDuration += duration
            }
        }
        return ChatContextSnapshot(
            messageCount: messages.count,
            modelId: "",
            supportsReasoning: false,
            pendingSelectedFileName: nil,
            pendingSelectedFileBytesText: nil,
            promptTokens: prompt,
            completionTokens: completion,
            totalTokens: prompt + completion,
            cachedTokens: cached,
            tokensPerSecond: generationDuration > 0 ? Double(timedCompletionTokens) / generationDuration : nil,
            contextWindowTokens: nil,
            currentContextTokens: latestContextTokens
        )
    }

    private static func durationSeconds(
        from start: Kotlinx_datetimeLocalDateTime,
        to end: Kotlinx_datetimeLocalDateTime?
    ) -> TimeInterval? {
        guard let end,
              let startDate = date(from: start),
              let endDate = date(from: end) else {
            return nil
        }
        return endDate.timeIntervalSince(startDate)
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

    var currentModelSupportsReasoning: Bool {
        sharedSettings.currentAssistantReasoningLevels().contains { $0 != .off }
    }

#if DEBUG
    var generationActiveOverrideForTesting: ((KotlinUuid?) -> Bool)?
#endif
    var isGenerationActive: Bool {
#if DEBUG
        if generationActiveOverrideForTesting?(currentConversationId) == true { return true }
#endif
        return generationCoordinator.isRunning || hasActiveBackgroundGenerationForCurrentConversation
    }

    var isForegroundGenerationActiveForCurrentConversation: Bool {
        guard generationCoordinator.isRunning else { return false }
        switch (currentConversationId, generationCoordinator.activeConversationId) {
        case (nil, nil):
            return true
        case let (current?, active?):
            return String(describing: current) == String(describing: active)
        default:
            return false
        }
    }

    var isGenerationActiveForCurrentConversation: Bool {
        guard let currentConversationId else { return false }
        if generationCoordinator.isRunning,
           let activeConversationId = generationCoordinator.activeConversationId,
           String(describing: currentConversationId) == String(describing: activeConversationId) {
            return true
        }
        return IOSChatBackgroundGenerationCoordinator.shared.hasActiveJob(conversationId: currentConversationId)
    }

    func isGenerationActive(conversationId: KotlinUuid) -> Bool {
#if DEBUG
        if generationActiveOverrideForTesting?(conversationId) == true { return true }
#endif
        if generationCoordinator.isRunning,
           let activeConversationId = generationCoordinator.activeConversationId,
           conversationId == activeConversationId {
            return true
        }
        return IOSChatBackgroundGenerationCoordinator.shared.hasActiveJob(conversationId: conversationId)
    }

    func latestImageGenerationResumeContext() async -> ChatImageGenerationResumeContext? {
        guard let conversationStore else { return nil }
        let storeID = ObjectIdentifier(conversationStore)
        if imageGenerationResumeCacheStoreID != storeID {
            imageGenerationResumeCache.removeAll()
            imageGenerationResumeCacheStoreID = storeID
        }
        let summaries = conversationStore.summaries
        let liveConversationIDs = Set(summaries.map { $0.id.toHexDashString() })
        imageGenerationResumeCache = imageGenerationResumeCache.filter {
            liveConversationIDs.contains($0.key)
        }
        var candidates: [ChatImageGenerationResumeContext] = []

        for summary in summaries {
            guard !Task.isCancelled else { return nil }
            let conversationID = summary.id.toHexDashString()
            let summaryUpdatedAtMillis = summary.updateAt.toEpochMilliseconds()
            let generationActive = isGenerationActive(conversationId: summary.id)
            let context: ChatImageGenerationResumeContext?
            if let cached = imageGenerationResumeCache[conversationID],
               cached.summaryUpdatedAtMillis == summaryUpdatedAtMillis,
               cached.isGenerationActive == generationActive {
                context = cached.context
            } else if let messages = await conversationStore.messages(for: summary.id) {
                context = ChatImageGenerationResumeProjection.latest(
                    in: messages,
                    conversationID: conversationID,
                    isGenerationActive: generationActive
                )
                imageGenerationResumeCache[conversationID] = ImageGenerationResumeCacheEntry(
                    summaryUpdatedAtMillis: summaryUpdatedAtMillis,
                    isGenerationActive: generationActive,
                    context: context
                )
            } else {
                continue
            }
            if let context {
                candidates.append(context)
            }
        }
        return ChatImageGenerationResumeProjection.preferred(in: candidates)
    }

    func reconcilePendingMiniAppMutationsAfterConversationBootstrap() async {
        guard miniAppRepository.hasPendingConversationMutations,
              let conversationStore,
              conversationStore.lastIOError == nil else {
            return
        }
        let pendingMutationIds = miniAppRepository.pendingConversationMutationIds()
        guard !pendingMutationIds.isEmpty else { return }

        var references = Set<IOSMiniAppConversationReference>()
        for summary in conversationStore.summaries {
            guard let storedMessages = await conversationStore.messages(for: summary.id) else {
                // Absence is only authoritative when every conversation could be read.
                return
            }
            references.formUnion(
                IOSMiniAppChatMessageFactory.persistedConversationReferences(in: storedMessages)
            )
        }

        do {
            try miniAppRepository.reconcilePendingConversationMutations(
                referenced: references,
                mutationIds: pendingMutationIds
            )
        } catch {
            NSLog("[AmberChat] Failed to reconcile pending MiniApp transactions: \(error)")
        }
    }

    private var hasActiveBackgroundGenerationForCurrentConversation: Bool {
        guard let currentConversationId else { return false }
        return IOSChatBackgroundGenerationCoordinator.shared.hasActiveJob(conversationId: currentConversationId)
    }

    @discardableResult
    func prepareForConversationChange(to targetConversationId: KotlinUuid?) -> Bool {
        let canChangeConversation: Bool
        if !generationCoordinator.isRunning {
            canChangeConversation = true
        } else if let targetConversationId,
                  let activeConversationId = generationCoordinator.activeConversationId,
                  String(describing: targetConversationId) == String(describing: activeConversationId) {
            canChangeConversation = true
        } else {
            canChangeConversation = handoffGenerationToBackgroundIfNeeded()
        }
        guard canChangeConversation else {
            // 点按静默无效的收口：交接被拒（生成中且当前在途工具不满足交接条件）时，
            // 把原因送到既有 app-level 用户可见错误通道（ChatView 已绑定
            // conversationStore.lastUserVisibleError 的 alert）。首页列表自身的
            // 渲染（homeContinueError）在 UI 层，这里只发布数据，不改 UI 文件。
            conversationStore?.publishUserVisibleError(
                IOSUserVisibleError(
                    title: "暂时无法切换会话",
                    message: "当前会话仍在生成中，正在执行的工具完成后才能切换。",
                    severity: .warning
                )
            )
            return false
        }

        let changesConversation: Bool
        if let targetConversationId {
            changesConversation = currentConversationId.map {
                String(describing: $0) != String(describing: targetConversationId)
            } ?? true
        } else {
            changesConversation = true
        }
        if changesConversation {
            invalidateSuggestionRequest()
            discardSelectedFileContextForConversationChange()
        }
        return true
    }

    func recordedAgentRunBelongsToConversation(
        runId: String,
        conversationId: String
    ) async -> Bool {
        await withCheckedContinuation { continuation in
            db.agentRuntimeDao().getRun(id: runId) { result, _ in
                let belongs = result?.conversationId?.caseInsensitiveCompare(conversationId) == .orderedSame
                continuation.resume(returning: belongs)
            }
        }
    }

    func canOpenActivityConfirmation(runId: String) -> Bool {
        generationCoordinator.hasPendingApproval(runId: runId)
    }

    func prepareForConversationDeletion(_ conversationId: KotlinUuid) {
        if currentConversationId.map({ String(describing: $0) }) == String(describing: conversationId) {
            discardSelectedFileContextForConversationChange()
            if generationCoordinator.activeConversationId.map({ String(describing: $0) })
                == String(describing: conversationId) {
                cancelGeneration()
            }
        }
        // P1-a: 删除会话时清掉其队列 sidecar，避免孤儿条目。
        steerQueueStore.removeAll(for: conversationId)
        if currentConversationId.map({ String(describing: $0) }) == String(describing: conversationId) {
            steerQueue = []
        }
        IOSChatBackgroundGenerationCoordinator.shared.cancelJobs(conversationId: conversationId)
    }

    private func discardSelectedFileContextForConversationChange() {
        cancelVisionRecognitionForConversationChange()
        inputText = ""
        clearPendingImages()
        pendingSelectedFilePreview = nil
        selectedFileContextError = nil
        guard let requestId = attachRequestId else {
            isAttachingSelectedFile = false
            return
        }

        attachRequestId = nil
        isAttachingSelectedFile = false
        let activityRunId = "tool-\(requestId.uuidString)"
        Task { @MainActor [liveActivityController] in
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .cancelled(toolTitle: "文档读取"),
                dismissalDelay: 1
            )
        }
    }

    var configurationIssue: ChatConfigurationIssue? {
        if let currentModel {
            return ChatProviderConfiguration.issue(
                for: currentModel,
                provider: ChatProviderConfiguration.provider(
                    for: currentModel,
                    providers: sharedSettings.snapshot.providers
                )
            )
        }

        if !ChatProviderConfiguration.configuredChatModels(in: sharedSettings.snapshot.providers).isEmpty {
            return .missingModel
        }

        // Legacy scalar fallback for callers that have not selected a KMP
        // provider-backed chat model yet.
        return ChatProviderConfiguration.issue(
            baseUrl: settingsStore.baseUrl,
            apiKey: settingsStore.apiKey,
            modelId: settingsStore.modelId
        )
    }

    /// One derived contract shared by the composer and the send action.
    func composerSendBlockReason(for text: String) -> ChatComposerSendBlockReason? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty || !pendingImages.isEmpty || pendingSelectedFilePreview != nil else {
            return .emptyInput
        }
        // P1-e: 编排子线程只读（存在 thread_edge 即拦截，含生成中/已取消/已完成；
        // 缓存判定在会话切换时刷新，不逐 keystroke 查 Room）。已知取舍：切换后到
        // 刷新完成前的毫秒级窗口内按缓存（可能为 false）放行——接受该窗口（checker
        // 评级低），未决期拦截的方案会让 Room 查询挂起/变慢时 composer 永久死锁，弃用。
        if currentConversationIsOrchestratedChild {
            return .orchestratedThread
        }
        if isGenerationActive {
            // 生成中发送改为入队（含图/附件）；队列满时回到禁用态。
            return steerQueue.count >= IOSSteerQueueStore.maxPendingUserMessages
                ? .steerQueueFull
                : nil
        }
        guard !isAttachingSelectedFile else { return .attachingSelectedFile }
        guard !isRecognizingImages else { return .recognizingImages }
        guard !hasPendingUserGate else { return .pendingApproval }
        if autoGenerateResponses, let configurationIssue {
            return .configuration(configurationIssue)
        }
        return nil
    }

    // MARK: - Private

    private let settingsStore: SettingsStore
    private let sharedSettings: IOSSharedSettingsStore
    private let localToolExecutor: IOSLocalToolExecutor?
    private let searchTransport: any IOSSearchHTTPTransport
    private let miniAppRepository: IOSMiniAppRepository
    private let workspaceStore: IOSWorkspaceStore
    private let autoGenerateResponses: Bool
    private let auxiliaryTextProvider: any IOSAgentTextProvider
    private let liveActivityController: AgentLiveActivityController
    @ObservationIgnored private lazy var db: AgentRuntimeDatabase = IosDatabaseFactory.shared.createDatabase()
    @ObservationIgnored private let mcpManager: IOSMcpManager
    @ObservationIgnored private lazy var generationCoordinator = makeGenerationCoordinator()
    private var attachRequestId: UUID?

    private var currentModel: Model? {
        sharedSettings.snapshot.getCurrentChatModel()
    }

    private var currentModelId: String {
        (currentModel?.modelId ?? settingsStore.modelId).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var currentModelAbilities: [ModelAbility] {
        if let abilities = currentModel?.abilities, !abilities.isEmpty {
            return abilities
        }
        return ModelRegistry.shared.MODEL_ABILITIES.getData(modelId: currentModelId) as? [ModelAbility] ?? []
    }

    private var liveActivityPreferenceEnabled: Bool {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: IOSExecutionPreferenceKeys.liveActivity) != nil else { return true }
        return defaults.bool(forKey: IOSExecutionPreferenceKeys.liveActivity)
    }

    private var pendingAssistantRegeneration: PendingAssistantRegeneration?
    @ObservationIgnored private var suggestionRequestToken: UUID?
    @ObservationIgnored private var suggestionGenerationTask: Task<Void, Never>?
    /// P0-a: the bridge built by the latest makeTextGenerationParams() assembly;
    /// generateResponse hands it to the run coordinator, which owns it for the
    /// whole run (run-level reuse is what makes tool_search hits callable on
    /// the next round). Rebuilt on every message send — a new run gets a new
    /// bridge with reset exposure.
    @ObservationIgnored private var lastAssembledToolExposureBridge: IosToolExposureBridge?
    /// P1-c: 线程编排工具服务（spawn/list/interrupt + FINAL_ANSWER 回传）。
    /// 测试可注入隔离 DAO 的服务实例（照 mailboxStore 注入先例）；否则用默认
    /// 懒实例（共享 Room + 当前 VM 依赖）。
    @ObservationIgnored private let injectedOrchestrationToolService: IOSThreadOrchestrationToolService?
    /// Phase 3 Wave 2: the experience curator injected into every round's
    /// runtime-context assembly. The store default-resolves to Documents
    /// (`IOSEvolutionExperienceStore()`); retrieval runs FRESH per round via
    /// `ChatRuntimeContextBuilder` — no cross-round cache.
    @ObservationIgnored private let experienceCurator = IOSEvolutionExperienceCurator(
        store: IOSEvolutionExperienceStore()
    )
    @ObservationIgnored private lazy var defaultOrchestrationToolService: IOSThreadOrchestrationToolService = {
        let runtimeDao = db.agentRuntimeDao()
        let mailboxDao = db.mailboxDao()
        let threadEdgeDao = db.threadEdgeDao()
        return IOSThreadOrchestrationToolService(
            conversationStoreProvider: { [weak self] in self?.conversationStore },
            mailboxDaoProvider: { mailboxDao },
            threadEdgeDaoProvider: { threadEdgeDao },
            agentRuntimeDaoProvider: { runtimeDao },
            backgroundCoordinator: IOSChatBackgroundGenerationCoordinator.shared,
            makeBackgroundToolRuntime: { [weak self] in
                guard let self else {
                    // self 已释放时无编排服务可注入（服务本体随 VM 析构），
                    // 显式 nil 保证构造点不静默缺参；该分支只在 VM 销毁后才可达。
                    return ChatToolRuntime(
                        settingsStore: SettingsStore(),
                        sharedSettings: IOSSharedSettingsStore(),
                        localToolExecutor: nil,
                        searchTransport: IOSURLSessionSearchHTTPTransport(),
                        mcpManager: IOSMcpManager(sharedSettings: IOSSharedSettingsStore(), configStore: .shared),
                        orchestrationToolService: nil
                    )
                }
                return self.makeBackgroundToolRuntime()
            },
            currentConversationId: { [weak self] in self?.currentConversationId },
            foregroundActiveRunId: { [weak self] childHex in
                self?.generationCoordinator.activeForegroundRunId(matchingHex: childHex)
            },
            cancelForegroundRun: { [weak self] runId in
                self?.generationCoordinator.cancel(runId: runId) ?? false
            },
            // P1-e: 前台活跃 run 全局 0/1（任意会话；与 P1-c activeForegroundRunId
            // 同一来源 isRunning）。
            foregroundRunActive: { [weak self] in
                self?.generationCoordinator.isRunning ?? false
            },
            // M4: role_assistant_id 存在性校验——对照当前设置快照的 assistants。
            roleAssistantExists: { [weak self] assistantId in
                guard let self else { return false }
                return self.sharedSettings.snapshot.assistants.contains { $0.id == assistantId }
            }
        )
    }()
    private var orchestrationToolService: IOSThreadOrchestrationToolService {
        injectedOrchestrationToolService ?? defaultOrchestrationToolService
    }

    /// P1-c: 后台 job 工具 runtime 构造（spawnAgent 的 `makeBackgroundToolRuntime`
    /// 闭包与测试缝共用同一构造点）。子线程在后台引擎里同样注册三编排工具，
    /// 必须注入本 VM 的编排服务，否则孙线程 spawn 恒报「不可用」。
    private func makeBackgroundToolRuntime() -> ChatToolRuntime {
        ChatToolRuntime(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            localToolExecutor: localToolExecutor,
            searchTransport: searchTransport,
            mcpManager: mcpManager,
            orchestrationToolService: orchestrationToolService,
            memoryPollutionMarker: memoryPollutionMarker,
            conversationStoreProvider: { [weak self] in self?.conversationStore }
        )
    }

    /// P2-a: harness 拥有的记忆污染置位接线。run 的工具输出收口处判定成功后回调
    /// 这里，把 run 锚定会话写 POLLUTED（KMP 原子 RMW 只升不降）。fire-and-forget：
    /// 失败只记日志，不阻塞工具结果；store 未接好时静默跳过（不会标记到别的会话）。
    private var memoryPollutionMarker: ((KotlinUuid, String) -> Void)? {
        { [weak self] conversationId, _ in
            guard let self else { return }
            Task { @MainActor [weak self] in
                guard let self, let store = self.conversationStore else { return }
                _ = await store.markConversationMemoryPolluted(conversationId)
            }
        }
    }

#if DEBUG
    /// 测试缝：直接取真实闭包路径构造的后台工具 runtime（不驱动 spawn 全链路）。
    func makeBackgroundToolRuntimeForTesting() -> ChatToolRuntime {
        makeBackgroundToolRuntime()
    }
#endif

    // MARK: - Init

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore = IOSSharedSettingsStore(),
        localToolExecutor: IOSLocalToolExecutor? = nil,
        searchTransport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport(),
        miniAppRepository: IOSMiniAppRepository? = nil,
        workspaceStore: IOSWorkspaceStore? = nil,
        autoGenerateResponses: Bool = true,
        auxiliaryTextProvider: any IOSAgentTextProvider = OpenAIKmpProviderAdapter(),
        liveActivityController: AgentLiveActivityController? = nil,
        mcpManager: IOSMcpManager? = nil,
        steerQueueStore: IOSSteerQueueStore? = nil,
        mailboxStore: IOSMailboxStore? = nil,
        orchestrationToolService: IOSThreadOrchestrationToolService? = nil,
        mailboxActivityCenter: IOSMailboxActivityCenter? = nil
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.localToolExecutor = localToolExecutor
        self.searchTransport = searchTransport
        self.miniAppRepository = miniAppRepository ?? IOSMiniAppRepository.shared
        self.workspaceStore = workspaceStore ?? IOSWorkspaceStore.shared
        self.autoGenerateResponses = autoGenerateResponses
        self.auxiliaryTextProvider = auxiliaryTextProvider
        self.liveActivityController = liveActivityController ?? .shared
        // Build from the shared config store (same UserDefaults key as
        // McpServersView) so callTool reaches the same configured servers;
        // tests inject a manager with a deterministic directory instead.
        self.mcpManager = mcpManager ?? IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared)
        self.steerQueueStore = steerQueueStore ?? IOSSteerQueueStore()
        self.mailboxStore = mailboxStore ?? IOSMailboxStore()
        self.injectedOrchestrationToolService = orchestrationToolService
        // P1-d: mailbox 活动广播（wait_agent 事件源）。测试注入独立实例隔离信号；
        // 生产与编排服务共用 `.shared`。
        self.mailboxActivityCenter = mailboxActivityCenter ?? .shared
        // P1-c: 后台 job 终态（FINAL_ANSWER）由本 VM 的编排服务回传父线程。
        // 单例协调器在 App 内只有一份 job 面；VM 重建时后注册者生效（App 实际
        // 只有单个 VM，测试不驱动真实后台 job）。
        IOSChatBackgroundGenerationCoordinator.shared.onRunTerminal = {
            [weak self] conversationId, runId, finalMessages in
            await self?.orchestrationToolService.notifyRunTerminal(
                conversationId: conversationId,
                runId: runId,
                finalMessages: finalMessages
            )
        }
    }

    private func makeGenerationCoordinator() -> ChatGenerationCoordinator {
        ChatGenerationCoordinator(
            dependencies: ChatGenerationDependencies(
                settingsStore: settingsStore,
                sharedSettings: sharedSettings,
                localToolExecutor: localToolExecutor,
                searchTransport: searchTransport,
                liveActivityController: liveActivityController,
                autoGenerateResponses: autoGenerateResponses,
                mcpManager: mcpManager,
                orchestrationToolService: orchestrationToolService,
                memoryPollutionMarker: memoryPollutionMarker,
                conversationStoreProvider: { [weak self] in self?.conversationStore }
            ),
            bindings: ChatGenerationBindings(
                getMessages: { [weak self] in
                    self?.messages ?? []
                },
                setMessages: { [weak self] messages in
                    self?.messages = messages
                },
                bumpMessageRevision: { [weak self] reason in
                    self?.bumpMessageRevision(reason: reason)
                },
                shouldPaceStreamPresentation: { [weak self] in
                    self?.streamPresentationPacingEnabled ?? false
                },
                setIsLoading: { [weak self] isLoading in
                    self?.isLoading = isLoading
                },
                setPendingMemoryApproval: { [weak self] request in
                    self?.pendingMemoryApproval = request
                },
                setPendingSearchApproval: { [weak self] request in
                    self?.pendingSearchApproval = request
                },
                setPendingWebMountApproval: { [weak self] request in
                    self?.pendingWebMountApproval = request
                },
                setPendingWorkspaceApproval: { [weak self] request in
                    self?.pendingWorkspaceApproval = request
                },
                setPendingIshHandoffApproval: { [weak self] request in
                    self?.pendingIshHandoffApproval = request
                },
                setPendingMcpApproval: { [weak self] request in
                    self?.pendingMcpApproval = request
                },
                setPendingCouncilApproval: { [weak self] request in
                    self?.pendingCouncilApproval = request
                },
                setPendingAskUser: { [weak self] request in
                    self?.pendingAskUser = request
                },
                setPendingRecipeApproval: { [weak self] request in
                    self?.pendingRecipeApproval = request
                },
                setContextCompactState: { [weak self] state in
                    withAnimation(.easeOut(duration: 0.22)) {
                        self?.contextCompactState = state
                    }
                },
                persistMessages: { [weak self] conversationId in
                    guard let self else { return false }
                    return await self.persistMessages(conversationId: conversationId)
                },
                capturePersistMessagesBaseline: { [weak self] conversationId in
                    guard let store = self?.conversationStore,
                          let targetConversationId = conversationId ?? store.currentConversation?.id else {
                        return nil
                    }
                    return store.writeBaseline(for: targetConversationId)
                },
                persistMessagesSnapshot: { [weak self] snapshot, conversationId, writeBaseline in
                    guard let self else { return false }
                    return await self.persistMessages(
                        snapshot,
                        conversationId: conversationId,
                        writeBaseline: writeBaseline
                    )
                },
                recordRun: { [weak self] runId, startedAt, status, inputDigest, conversationId in
                    await self?.recordRun(
                        runId: runId,
                        startedAt: startedAt,
                        status: status,
                        inputDigest: inputDigest,
                        conversationId: conversationId
                    )
                },
                markRunAwaitingPermission: { [weak self] runId, toolCallId in
                    guard let self else { return false }
                    return await self.markRunAwaitingPermission(
                        runId: runId,
                        toolCallId: toolCallId
                    )
                },
                startLiveActivity: { [weak self] runId, conversationId, presentation in
                    self?.startLiveActivity(
                        runId: runId,
                        conversationId: conversationId,
                        presentation: presentation
                    )
                },
                saveMiniAppIfPresent: { [weak self] messages, conversationId in
                    self?.applyMiniAppOutputIfPresentPublic(to: messages, conversationId: conversationId)
                },
                messagesByInjectingRuntimeContext: { [weak self] messages in
                    self?.messagesByInjectingRuntimeContext(messages) ?? messages
                },
                userFacingGenerationError: { rawMessage, modelId in
                    ChatViewModel.userFacingGenerationError(rawMessage, modelId: modelId)
                },
                memoryRecordIdsForRuntimeContext: { [weak self] messages in
                    self?.memoryRecordIdsForRuntimeContext(messages) ?? []
                },
                recordMemoryUsage: { [weak self] ids, force in
                    self?.recordMemoryUsage(ids, force: force)
                },
                generationSucceeded: { [weak self] in
                    self?.onGenerationCompleted()
                },
                drainSteerQueue: { [weak self] conversationId in
                    self?.drainSteerQueue(conversationId: conversationId) ?? []
                },
                drainMailbox: { [weak self] conversationId in
                    await self?.drainMailbox(conversationId: conversationId) ?? []
                },
                handleSteerQueueAtTerminal: { [weak self] conversationId, autoContinue in
                    self?.handleSteerQueueAtRunTerminal(
                        for: conversationId,
                        autoContinue: autoContinue
                    )
                },
                restoreSteerQueueLeftover: { [weak self] conversationId in
                    self?.restoreSteerQueueLeftoverToComposer(for: conversationId)
                },
                onRunTerminal: { [weak self] conversationId, runId, finalMessages in
                    await self?.orchestrationToolService.notifyRunTerminal(
                        conversationId: conversationId,
                        runId: runId,
                        finalMessages: finalMessages
                    )
                },
                refreshOrchestrationLinks: { [weak self] in
                    await self?.refreshCurrentConversationOrchestratedStatus()
                }
            )
        )
    }

    // MARK: - Actions

    /// 从 store 的 currentConversation 灌入 messages（切换会话 / App 启动时调用）。
    /// 必须在主线程；调用方负责确保 store 已 bootstrap。
    /// 不变量:任何对 `messages` 的写入都必须紧跟一次本方法调用。
    /// chat 列表的 ChatCollectionUpdateKey 以 signal(revision+reason)判断是否重建快照,
    /// 漏掉 bump 会静默漏刷新(key 不变 → apply 被跳过 → 列表停在旧内容),无编译期提示。
    func bumpMessageRevision(reason: ChatMessageUpdateReason) {
        messageRevision &+= 1
        messageUpdateSignal = ChatMessageUpdateSignal(revision: messageRevision, reason: reason)
        // token usage 只在结构性事件后变化；streamDelta/toolDelta 不改 usage。
        switch reason {
        case .streamDelta, .toolDelta:
            break
        default:
            tokenRevision &+= 1
        }
    }

    func reloadFromStore(reason: ChatMessageUpdateReason = .initialLoad) {
        guard let store = conversationStore else { return }
        invalidateSuggestionRequest()
        let storedMessages = store.currentMessages
        messages = messagesByTerminatingStaleSearches(in: storedMessages, store: store) ?? storedMessages
        contextCompactState = .idle
        // P1-a: 队列随会话切换重灌（冷启动恢复：只进队列 UI，不自动发送）。
        steerQueue = steerQueueStore.load(conversationId: currentConversationId)
        bumpMessageRevision(reason: reason)
        chatSuggestions = []
        // P1-e: 会话切换时异步刷新子线程只读缓存（Room 一次查询；composer 判定
        // 在切换后立即生效）。
        orchestratedStatusRefreshTask = Task { @MainActor [weak self] in
            await self?.refreshCurrentConversationOrchestratedStatus()
        }
    }

    private func messagesByTerminatingStaleSearches(
        in storedMessages: [UIMessage],
        store: IOSConversationStore
    ) -> [UIMessage]? {
        guard let conversationId = store.currentConversation?.id,
              !isGenerationActive(conversationId: conversationId) else {
            return nil
        }
        let pendingSearches = storedMessages.flatMap { message -> [UIMessagePart.Tool] in
            guard message.role == MessageRole.assistant, message.finishedAt != nil else { return [] }
            return message.parts.compactMap { $0 as? UIMessagePart.Tool }.filter {
                IOSSearchExecutor.supportedToolNames.contains($0.toolName) && $0.output.isEmpty
            }
        }
        guard !pendingSearches.isEmpty else { return nil }

        let runtime = ChatToolRuntime(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            localToolExecutor: localToolExecutor,
            searchTransport: searchTransport,
            mcpManager: mcpManager
        )
        let recoveredMessages = pendingSearches.reduce(storedMessages) { messages, toolCall in
            runtime.messagesByFinishingToolCall(
                toolCall,
                outputText: ChatToolOutputFormatter.toolFailureJSON(
                    toolName: toolCall.toolName,
                    reason: "The previous generation ended before the tool call completed.",
                    cancelled: true
                ),
                in: messages
            )
        }
        let writeBaseline = store.writeBaseline(for: conversationId)
        Task { @MainActor [weak self, weak store] in
            guard let self, let store,
                  !self.isGenerationActive(conversationId: conversationId) else { return }
            _ = await store.save(
                messages: recoveredMessages,
                to: conversationId,
                ifUnchangedSince: writeBaseline
            )
        }
        return recoveredMessages
    }

    func terminateRecoveredPendingApprovals(
        _ descriptors: [IOSPendingApprovalRecoveryDescriptor]
    ) async {
        guard let conversationStore, !descriptors.isEmpty else { return }
        let runtime = ChatToolRuntime(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            localToolExecutor: localToolExecutor,
            searchTransport: searchTransport,
            mcpManager: mcpManager
        )
        var didUpdateCurrentConversation = false

        for descriptor in descriptors {
            guard let conversationId = conversationStore.summaries.first(where: {
                $0.id.toHexDashString() == descriptor.conversationId
            })?.id else {
                await IOSRunRecovery.completePendingApprovalRecovery(runId: descriptor.runId)
                continue
            }
            guard let storedMessages = await conversationStore.messages(for: conversationId) else {
                continue
            }

            // W3 state-machine check (docs/IOS_AGENT_HARDENING_PLAN_2026-07-29.md
            // §W3, invariant I-3): approving a tool never flips `agent_run.status`
            // back to "running" — `executeApprovedAsyncTool` records the ledger's
            // Started/Finished pair for the real execution but never calls
            // `recordRun`, so a crash during the post-approval execution leaves
            // the run stuck at status "awaiting_permission", indistinguishable at
            // the AgentRunEntity level from "user never tapped approve". Only the
            // tool-call ledger tells the two apart: a genuinely-still-pending call
            // has exactly one Started→Finished(paused_for_approval) pair; an
            // approved-then-crashed call has a SECOND Started after that with no
            // Finished. Consult it before assuming "cancelled" is the honest story
            // — a side-effect tool that was actually approved and mid-execution
            // must land as "outcome unknown", never a plain cancellation.
            //
            // F1 fix: this MUST run unconditionally, before any short-circuit on
            // `descriptor`'s own tool call. A single run can have the descriptor's
            // tc-1 (already approved, output present) followed by a second call
            // tc-2 the model issued right after — if tc-2 died mid-execution, it
            // has a dangling ledger Started with no Finished, but its tool part's
            // `output` is also empty, so it looks exactly like "never approved".
            // Skipping this plan step (as the old `guard pendingTool ... else
            // continue` did whenever tc-1 no longer had empty output) would leave
            // tc-2 unmarked forever: this run is excluded from AppShell's
            // interrupted-run sweep once `completePendingApprovalRecovery` below
            // marks it "interrupted", so tc-2 would silently re-fire (a real
            // sideEffect re-run) the next time the user resumes this chat —
            // violating I-3. Apply the FULL actions map (every toolCallId this
            // run's ledger has an opinion on), not just descriptor.toolCallId.
            guard let ledgerActions = await IOSRunRecovery.planToolCallRecovery(
                runId: descriptor.runId,
                messages: storedMessages
            ) else {
                continue
            }
            var recoveredMessages = IOSToolCallRecoveryApplier.apply(ledgerActions, to: storedMessages)
            var didMutateRecovered = !ledgerActions.isEmpty

            // descriptor's own tool call: only fall back to the plain "App
            // restarted while waiting for confirmation" cancellation notice if
            // it's still sitting with empty output AND the ledger sweep above
            // had no opinion on it (i.e. genuinely never approved — unchanged
            // S2 behavior). If the ledger already produced an action for it,
            // the loop above already wrote its (possibly "outcome unknown")
            // resolution and this branch must not overwrite that with a plain
            // cancellation.
            let descriptorToolStillPending = storedMessages
                .flatMap(\.parts)
                .compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { $0.toolCallId == descriptor.toolCallId && $0.output.isEmpty })
            if let descriptorToolStillPending, ledgerActions[descriptor.toolCallId] == nil {
                let failure = ChatToolOutputFormatter.toolFailureJSON(
                    toolName: descriptorToolStillPending.toolName,
                    reason: "App restarted while waiting for confirmation.",
                    cancelled: true
                )
                recoveredMessages = runtime.messagesByFinishingToolCall(
                    descriptorToolStillPending,
                    outputText: failure,
                    in: recoveredMessages
                )
                let noticeSeed = UIMessage.companion.assistant(prompt: "")
                recoveredMessages.append(UIMessage(
                    id: noticeSeed.id,
                    role: noticeSeed.role,
                    parts: [MessageKt.localGenerationErrorTextPart(
                        text: "待确认操作因 App 重启已终止，请重新生成。"
                    )],
                    annotations: noticeSeed.annotations,
                    createdAt: noticeSeed.createdAt,
                    finishedAt: noticeSeed.finishedAt,
                    modelId: noticeSeed.modelId,
                    usage: noticeSeed.usage,
                    translation: noticeSeed.translation
                ))
                didMutateRecovered = true
            }

            if didMutateRecovered {
                guard await conversationStore.save(messages: recoveredMessages, to: conversationId) else {
                    continue
                }
                didUpdateCurrentConversation = didUpdateCurrentConversation || currentConversationId == conversationId
            }
            await IOSRunRecovery.completePendingApprovalRecovery(runId: descriptor.runId)
        }

        if didUpdateCurrentConversation {
            reloadFromStore(reason: .branchChange)
        }
    }

    /// W3 (§ crash-recovery UX): for interrupted runs that were never
    /// awaiting approval (the ordinary "died mid tool HTTP call / mid plain
    /// generation" case), read each run's ledger and apply the resulting
    /// per-toolCallId recovery action to that run's conversation. Mirrors
    /// `terminateRecoveredPendingApprovals`'s conversation-lookup/save
    /// pattern; a run whose conversation can no longer be found (deleted by
    /// the user in the meantime) is skipped silently, same convention as
    /// that method.
    func applyToolCallLedgerRecovery(
        forInterruptedRuns pairs: [(runId: String, conversationId: String)]
    ) async -> Set<String> {
        guard let conversationStore, !pairs.isEmpty else { return [] }
        var didUpdateCurrentConversation = false
        var reconciledRunIds = Set<String>()

        for pair in pairs {
            guard let conversationId = conversationStore.summaries.first(where: {
                $0.id.toHexDashString() == pair.conversationId
            })?.id else {
                print("[AmberChat] W3 recovery: conversation \(pair.conversationId) for run \(pair.runId) not found, skipping (may have been deleted).")
                // A deleted conversation has no pending tool node left to replay.
                reconciledRunIds.insert(pair.runId)
                continue
            }
            guard let storedMessages = await conversationStore.messages(for: conversationId) else { continue }
            guard let actions = await IOSRunRecovery.planToolCallRecovery(
                runId: pair.runId,
                messages: storedMessages
            ) else { continue }
            guard !actions.isEmpty else {
                reconciledRunIds.insert(pair.runId)
                continue
            }

            let recoveredMessages = IOSToolCallRecoveryApplier.apply(actions, to: storedMessages)
            guard await conversationStore.save(messages: recoveredMessages, to: conversationId) else { continue }
            reconciledRunIds.insert(pair.runId)
            didUpdateCurrentConversation = didUpdateCurrentConversation || currentConversationId == conversationId
        }

        if didUpdateCurrentConversation {
            reloadFromStore(reason: .branchChange)
        }
        return reconciledRunIds
    }

    /// 把当前 messages 落盘（节流：只在流式结束/取消/切换时调，不在每个 chunk 调）。
    private func persistMessages(conversationId: KotlinUuid? = nil) async -> Bool {
        guard let store = conversationStore else { return true }
        let snapshot = messages
        let targetConversationId = conversationId ?? store.currentConversation?.id
        let pendingRegeneration = pendingAssistantRegeneration
        let writeBaseline = targetConversationId.map { store.writeBaseline(for: $0) }
        return await persistMessagesSnapshot(
            snapshot,
            targetConversationId: targetConversationId,
            pendingRegeneration: pendingRegeneration,
            store: store,
            writeBaseline: writeBaseline
        )
    }

    private func persistMessages(
        _ snapshot: [UIMessage],
        conversationId: KotlinUuid? = nil,
        writeBaseline: IOSConversationWriteBaseline?
    ) async -> Bool {
        guard let store = conversationStore else { return true }
        let targetConversationId = conversationId ?? store.currentConversation?.id
        let pendingRegeneration = pendingAssistantRegeneration
        return await persistMessagesSnapshot(
            snapshot,
            targetConversationId: targetConversationId,
            pendingRegeneration: pendingRegeneration,
            store: store,
            writeBaseline: writeBaseline
        )
    }

    private func persistMessagesSnapshot(
        _ snapshot: [UIMessage],
        targetConversationId: KotlinUuid?,
        pendingRegeneration: PendingAssistantRegeneration?,
        store: IOSConversationStore,
        writeBaseline: IOSConversationWriteBaseline?
    ) async -> Bool {
        if let pendingRegeneration,
           let targetConversationId,
           String(describing: targetConversationId) == String(describing: pendingRegeneration.conversationId) {
            let generatedSuffix: [UIMessage] = pendingRegeneration.generatedMessageIndex >= 0 &&
                pendingRegeneration.generatedMessageIndex < snapshot.count
                ? Array(snapshot[pendingRegeneration.generatedMessageIndex...])
                : []

            if let errorMessage = generatedSuffix.first(where: Self.isLocalGenerationError) {
                self.pendingAssistantRegeneration = nil
                let saved = await store.save(
                    messages: store.currentMessages + [errorMessage],
                    to: pendingRegeneration.conversationId,
                    ifUnchangedSince: writeBaseline
                )
                if saved {
                    self.messages = store.currentMessages
                    self.bumpMessageRevision(reason: .branchChange)
                }
                return saved
            }

            if let regeneratedIndex = generatedSuffix.lastIndex(where: Self.isRegeneratedAnswerCandidate) {
                let regenerated = generatedSuffix[regeneratedIndex]
                let trailingMessages = Array(generatedSuffix.dropFirst(regeneratedIndex + 1))
                let saved = await store.appendVariantAndTruncateAfter(
                    messageIndex: pendingRegeneration.targetMessageIndex,
                    message: regenerated,
                    trailingMessages: trailingMessages,
                    conversationId: pendingRegeneration.conversationId
                )
                if saved {
                    self.pendingAssistantRegeneration = nil
                    self.messages = store.currentMessages
                    self.bumpMessageRevision(reason: .branchChange)
                    return true
                }
                self.pendingAssistantRegeneration = nil
                self.messages = store.currentMessages
                self.bumpMessageRevision(reason: .branchChange)
                return false
            }
            if let outputLimitNotice = generatedSuffix.last(where: Self.isOutputLimitNotice) {
                let saved = await store.save(
                    messages: store.currentMessages + [outputLimitNotice],
                    to: pendingRegeneration.conversationId,
                    ifUnchangedSince: writeBaseline
                )
                self.pendingAssistantRegeneration = nil
                self.messages = store.currentMessages
                self.bumpMessageRevision(reason: .branchChange)
                return saved
            }
            self.pendingAssistantRegeneration = nil
            self.messages = store.currentMessages
            self.bumpMessageRevision(reason: .branchChange)
            return false
        }
        if let targetConversationId {
            return await store.save(
                messages: snapshot,
                to: targetConversationId,
                ifUnchangedSince: writeBaseline
            )
        }
        return true
    }

    @discardableResult
    func sendMessage() -> Bool {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasImages = !pendingImages.isEmpty
        if let blockReason = composerSendBlockReason(for: text) {
            if case .configuration(let issue) = blockReason {
                configurationError = issue.message
            }
            return false
        }
        // 生成中：图/附件整包入队，OCR/能力校验延到折入后再走正常发送路径
        // （不走 fallback 直发抢跑）。仅硬拦超张数，避免无模型时误杀入队。
        if isGenerationActive {
            if hasImages, pendingImages.count > Self.maxImagesPerMessage {
                selectedFileContextError = "一次最多发送 \(Self.maxImagesPerMessage) 张图片"
                return false
            }
            return enqueueSteerMessage(text: text)
        }
        // 空闲发送：按模型视觉能力判断（对齐安卓 ImageAttachmentValidator）：
        // ready→直接发；fallback→先用视觉模型识别成文字再发；blocked→拦下提示，绝不静默丢弃。
        if hasImages {
            switch imageAttachmentState {
            case .blocked(let reason):
                selectedFileContextError = reason
                return false
            case .fallback:
                configurationError = nil
                startVisionFallbackAndSend(text: text, images: pendingImages)
                return true
            case .ready, .none:
                break
            }
        }
        configurationError = nil
        sendUserMessage(text: text, images: pendingImages)
        return true
    }

    func modifyGeneratedImage(sourceImageURL: String, prompt: String, aspectRatio: String) {
        let trimmedPrompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedSource = sourceImageURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !rejectVisionRecognitionMutationIfNeeded() else { return }
        guard !trimmedPrompt.isEmpty, !trimmedSource.isEmpty else { return }
        guard !isGenerationActive else {
            selectedFileContextError = "请等当前回复结束后再修改图片"
            return
        }

        configurationError = nil
        let input = Self.imageEditToolInput(
            prompt: trimmedPrompt,
            sourceImageURL: trimmedSource,
            aspectRatio: aspectRatio
        )
        generationCoordinator.runImageTool(
            input: input,
            conversationId: currentConversationId,
            providerSetting: makeProviderSetting(),
            params: makeTextGenerationParams()
        )
    }

    /// Appends the user message (keeping image parts for in-bubble display) and persists it.
    /// Returns the input digest + conversation id so the caller can start generation.
    @discardableResult
    private func appendUserMessage(text: String, images: [PendingChatImage]) -> (digest: String, conversationId: KotlinUuid?, messageId: String) {
        let prompt = Self.promptText(userText: text, selectedFilePreview: pendingSelectedFilePreview)
        let digest = chatInputDigest(for: prompt.isEmpty ? "[image]" : prompt)
        let userMsg = makeUserMessage(prompt: prompt, images: images)
        pendingAssistantRegeneration = nil
        // iMessage 式上屏:仅把这条用户消息的插入放进动画事务,驱动消息行的入场 transition。
        // 批量加载/切换会话走 `messages = store.currentMessages`(不在事务内),不会逐条动画。
        withAnimation(.spring(response: 0.34, dampingFraction: 0.8)) {
            messages.append(userMsg)
            bumpMessageRevision(reason: .userAppend)
        }
        inputText = ""
        invalidateSuggestionRequest()
        chatSuggestions = []
        pendingSelectedFilePreview = nil
        clearPendingImages()
        selectedFileContextError = nil
        let runConversationId = currentConversationId
        // 用户消息立即落盘：即使随后生成崩溃/被杀进程，用户输入也不会丢。
        Task { @MainActor [weak self] in
            _ = await self?.persistMessages(conversationId: runConversationId)
        }
        return (digest, runConversationId, ChatMessageProjector.messageId(for: userMsg))
    }

    /// Sends the user message and kicks off generation. For non-vision models the image
    /// parts are swapped for the cached recognition text inside
    /// `messagesByInjectingRuntimeContext` before the request leaves for the provider.
    private func sendUserMessage(text: String, images: [PendingChatImage]) {
        let (digest, conversationId, _) = appendUserMessage(text: text, images: images)
        guard autoGenerateResponses else { return }
        generateResponse(inputDigest: digest, conversationId: conversationId)
    }

    // MARK: - Steer 队列（P1-a）

    /// 生成激活期间把 composer 内容入队（文本 / 图 / 选中文件）；队列满返回 false。
    /// 与 appendUserMessage 的输入收尾一致：清空输入框、建议与附件预览。
    @discardableResult
    func enqueueSteerMessage(text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let images = pendingImages
        let selectedFile = pendingSelectedFilePreview
        guard !trimmed.isEmpty || !images.isEmpty || selectedFile != nil else { return false }
        guard isGenerationActive else { return false }
        guard steerQueue.count < IOSSteerQueueStore.maxPendingUserMessages else { return false }
        steerQueue.append(IOSSteerQueueEntry(
            id: UUID().uuidString,
            text: trimmed,
            createdAt: Date(),
            images: images.map { IOSSteerQueueImage(dataUrl: $0.dataUrl, previewData: $0.previewData) },
            selectedFile: selectedFile.map(IOSSteerQueuedFile.init)
        ))
        persistSteerQueue()
        // P1-d: steer 入队即打断同会话的 wait_agent（信号在入队成功后发射；
        // wait_agent 事件后复查 mailbox 为空 → 返回 "Wait interrupted by new input."）。
        if let conversationId = currentConversationId {
            let center = mailboxActivityCenter
            let hex = conversationId.toHexDashString()
            Task { @MainActor in
                await center.signal(conversationIdHex: hex)
            }
        }
        inputText = ""
        invalidateSuggestionRequest()
        chatSuggestions = []
        pendingSelectedFilePreview = nil
        clearPendingImages()
        selectedFileContextError = nil
        return true
    }

    /// 撤销一条排队消息；同步更新 sidecar（exactly once）。
    func removeSteerMessage(id: String) {
        guard steerQueue.contains(where: { $0.id == id }) else { return }
        steerQueue.removeAll { $0.id == id }
        persistSteerQueue()
    }

    /// 工具循环边界消费：出队全部排队消息 → 作为真实 user 消息上屏并按既有路径
    /// 持久化进会话，返回生成的消息供下一轮 upload 折入。队列为空或 run 不属于
    /// 当前会话时零操作（不同会话的队列不串）。
    @discardableResult
    func drainSteerQueue(conversationId: KotlinUuid?) -> [UIMessage] {
        guard !steerQueue.isEmpty else { return [] }
        guard isCurrentConversation(conversationId) else { return [] }
        let entries = steerQueue
        steerQueue = []
        persistSteerQueue()
        let runConversationId = currentConversationId
        let drained = entries.map { entry -> UIMessage in
            let prompt = Self.promptText(
                userText: entry.text,
                selectedFilePreview: entry.selectedFile?.asSelectedDocument
            )
            let images = entry.images.map {
                PendingChatImage(dataUrl: $0.dataUrl, previewData: $0.previewData)
            }
            return makeUserMessage(prompt: prompt, images: images)
        }
        // 与 appendUserMessage 同款上屏：真实 user 消息进 timeline（后续会话落盘
        // 与工具轮次持久化共用既有 persistMessages 路径）。
        withAnimation(.spring(response: 0.34, dampingFraction: 0.8)) {
            messages.append(contentsOf: drained)
            bumpMessageRevision(reason: .userAppend)
        }
        invalidateSuggestionRequest()
        chatSuggestions = []
        selectedFileContextError = nil
        Task { @MainActor [weak self] in
            _ = await self?.persistMessages(conversationId: runConversationId)
        }
        return drained
    }

    /// run 终态：成功则出队头一条上屏并自动开下一轮；取消/失败则回填 composer。
    func handleSteerQueueAtRunTerminal(for conversationId: KotlinUuid?, autoContinue: Bool) {
        guard !steerQueue.isEmpty else { return }
        guard isCurrentConversation(conversationId) else { return }
        if autoContinue {
            sendNextSteerQueueEntry(conversationId: conversationId)
            return
        }
        restoreSteerQueueLeftoverToComposer(for: conversationId)
    }

    /// 出队头一条 → 上屏 →（若开启自动生成）立刻开下一轮。剩余条目继续留在队列。
    private func sendNextSteerQueueEntry(conversationId: KotlinUuid?) {
        guard let entry = steerQueue.first else { return }
        steerQueue.removeFirst()
        persistSteerQueue()
        let images = entry.images.map {
            PendingChatImage(dataUrl: $0.dataUrl, previewData: $0.previewData)
        }
        let prompt = Self.promptText(
            userText: entry.text,
            selectedFilePreview: entry.selectedFile?.asSelectedDocument
        )
        let digest = chatInputDigest(for: prompt.isEmpty ? "[image]" : prompt)
        let userMsg = makeUserMessage(prompt: prompt, images: images)
        withAnimation(.spring(response: 0.34, dampingFraction: 0.8)) {
            messages.append(userMsg)
            bumpMessageRevision(reason: .userAppend)
        }
        invalidateSuggestionRequest()
        chatSuggestions = []
        selectedFileContextError = nil
        let runConversationId = currentConversationId
        Task { @MainActor [weak self] in
            _ = await self?.persistMessages(conversationId: runConversationId)
        }
        guard autoGenerateResponses else { return }
        generateResponse(inputDigest: digest, conversationId: conversationId ?? runConversationId)
    }

    /// 取消/失败终态：纯文本 leftover 拼回 composer；含附件条目留队防丢媒体。
    func restoreSteerQueueLeftoverToComposer(for conversationId: KotlinUuid?) {
        guard !steerQueue.isEmpty else { return }
        guard isCurrentConversation(conversationId) else { return }
        let textOnly = steerQueue.filter { !$0.hasAttachments }
        let withAttachments = steerQueue.filter(\.hasAttachments)
        steerQueue = withAttachments
        persistSteerQueue()
        guard !textOnly.isEmpty else { return }
        let joined = textOnly.map(\.text).joined(separator: "\n")
        let current = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        inputText = current.isEmpty ? joined : current + "\n" + joined
    }

    // MARK: - Mailbox 消费（P1-b）

    /// 工具循环/新 run 首轮边界消费 mailbox：Room 事务 drain 未投递信封（exactly-once，
    /// 二次调用为空），渲染为带结构头的真实 user 消息上屏并按既有路径持久化进会话，
    /// 返回生成的消息供下一轮 upload 折入。与 steer 不同：未消费信封不回 composer
    /// （留在 Room 等下次 run），本阶段不写 UI。队列为空或 run 不属于当前会话时零操作。
    @discardableResult
    func drainMailbox(conversationId: KotlinUuid?) async -> [UIMessage] {
        guard isCurrentConversation(conversationId) else { return [] }
        let envelopes = await mailboxStore.drainPending(forConversationId: conversationId)
        guard !envelopes.isEmpty else { return [] }
        let runConversationId = currentConversationId
        let drained = envelopes.map { envelope in
            makeUserMessage(
                prompt: MailboxEnvelopeKt.renderMailboxEnvelopeToUserText(
                    authorThreadId: envelope.authorThreadId,
                    type: envelope.type,
                    payload: envelope.payload
                ),
                images: []
            )
        }
        // 与 drainSteerQueue 同款上屏：真实 user 消息进 timeline（后续会话落盘
        // 与工具轮次持久化共用既有 persistMessages 路径）。
        withAnimation(.spring(response: 0.34, dampingFraction: 0.8)) {
            messages.append(contentsOf: drained)
            bumpMessageRevision(reason: .userAppend)
        }
        invalidateSuggestionRequest()
        chatSuggestions = []
        selectedFileContextError = nil
        Task { @MainActor [weak self] in
            _ = await self?.persistMessages(conversationId: runConversationId)
        }
        return drained
    }

    private func persistSteerQueue() {
        steerQueueStore.persist(steerQueue, for: currentConversationId)
    }

    private static func imageEditToolInput(
        prompt: String,
        sourceImageURL: String,
        aspectRatio: String
    ) -> String {
        let payload: [String: Any] = [
            "prompt": prompt,
            "aspect_ratio": aspectRatio,
            "count": 1,
            "mode": "edit",
            "source_image_url": sourceImageURL,
        ]
        guard let data = try? JSONSerialization.data(
            withJSONObject: payload,
            options: [.sortedKeys, .withoutEscapingSlashes]
        ),
        let text = String(data: data, encoding: .utf8) else {
            return prompt
        }
        return text
    }

    /// OCR fallback: the user message (with its image) is shown in the chat immediately;
    /// a recognition indicator runs on the user side while the vision model reads each
    /// image, then the main model responds. On failure the message stays and an error shows.
    private func startVisionFallbackAndSend(text: String, images: [PendingChatImage]) {
        let (digest, conversationId, userMessageId) = appendUserMessage(text: text, images: images)
        guard autoGenerateResponses else { return }
        let requestId = UUID()
        visionRecognitionRequestId = requestId
        visionRecognitionConversationId = conversationId
        isRecognizingImages = true
        visionRecognitionImageCount = images.count
        visionRecognitionTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let result = await self.performVisionRecognition(images: images)
            guard self.visionRecognitionRequestId == requestId else { return }
            self.clearVisionRecognitionOperation()
            self.clearVisionRecognitionPendingPrompt()
            switch result {
            case .failure(let error):
                await self.applyVisionRecognitionFailure(
                    message: error.message,
                    conversationId: conversationId,
                    userMessageId: userMessageId
                )
            case .success(let texts):
                guard self.shouldApplyVisionRecognitionResult(
                    conversationId: conversationId,
                    userMessageId: userMessageId
                ) else { return }
                self.cacheVisionRecognitionTexts(texts)
                self.generateResponse(inputDigest: digest, conversationId: conversationId)
            }
        }
    }

    private func cancelVisionRecognitionForConversationChange() {
        guard visionRecognitionRequestId != nil else { return }
        let ownerConversationId = visionRecognitionConversationId
        let ownerMessages = messages
        visionRecognitionTask?.cancel()
        clearVisionRecognitionOperation()
        guard let ownerConversationId, let conversationStore else { return }
        let notice = Self.visionRecognitionFailureMessage("图片识别已取消，请重新发送图片")
        Task { @MainActor in
            _ = await conversationStore.saveBackgroundCompletion(
                baseMessages: ownerMessages,
                completedMessages: ownerMessages + [notice],
                to: ownerConversationId
            )
        }
    }

    func cancelVisionRecognition() {
        guard visionRecognitionRequestId != nil else { return }
        visionRecognitionTask?.cancel()
        clearVisionRecognitionOperation()
        clearVisionRecognitionPendingPrompt()
        selectedFileContextError = "图片识别已取消，请重新发送图片"
    }

    private func clearVisionRecognitionOperation() {
        visionRecognitionTask = nil
        visionRecognitionRequestId = nil
        visionRecognitionConversationId = nil
        isRecognizingImages = false
        visionRecognitionImageCount = 0
    }

    private func shouldApplyVisionRecognitionResult(
        conversationId: KotlinUuid?,
        userMessageId: String
    ) -> Bool {
        let sameConversation: Bool
        if let conversationId {
            sameConversation = currentConversationId.map { String(describing: $0) } == String(describing: conversationId)
        } else {
            sameConversation = currentConversationId == nil
        }
        guard sameConversation else { return false }
        guard currentConversationContainsMessage(id: userMessageId) else { return false }
        if let conversationId {
            return !isGenerationActive(conversationId: conversationId)
        }
        return !isGenerationActive
    }

    private func applyVisionRecognitionFailure(
        message: String,
        conversationId: KotlinUuid?,
        userMessageId: String
    ) async {
        guard shouldApplyVisionRecognitionResult(conversationId: conversationId, userMessageId: userMessageId) else {
            if !isCurrentConversation(conversationId),
               let conversationId,
               let conversationStore,
               var ownerMessages = await conversationStore.messages(for: conversationId),
               ownerMessages.contains(where: {
                   ChatMessageProjector.messageId(for: $0) == userMessageId
               }) {
                ownerMessages.append(Self.visionRecognitionFailureMessage(message))
                _ = await conversationStore.save(messages: ownerMessages, to: conversationId)
            }
            return
        }
        selectedFileContextError = message
    }

    private func isCurrentConversation(_ conversationId: KotlinUuid?) -> Bool {
        if let conversationId {
            return currentConversationId.map { String(describing: $0) } == String(describing: conversationId)
        }
        return currentConversationId == nil
    }

    private static func visionRecognitionFailureMessage(_ text: String) -> UIMessage {
        let seed = UIMessage.companion.assistant(prompt: "")
        return UIMessage(
            id: seed.id,
            role: seed.role,
            parts: [MessageKt.localGenerationErrorTextPart(text: text)],
            annotations: seed.annotations,
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )
    }

    private func clearVisionRecognitionPendingPrompt() {
        if selectedFileContextError == Self.visionRecognitionPendingMessage {
            selectedFileContextError = nil
        }
    }

    private func currentConversationContainsMessage(id messageId: String) -> Bool {
        if messages.contains(where: { ChatMessageProjector.messageId(for: $0) == messageId }) {
            return true
        }
        guard let currentMessages = conversationStore?.currentConversation?.currentMessages else {
            return false
        }
        return currentMessages.contains(where: { ChatMessageProjector.messageId(for: $0) == messageId })
    }

    @discardableResult
    private func rejectVisionRecognitionMutationIfNeeded() -> Bool {
        guard isRecognizingImages else { return false }
        selectedFileContextError = Self.visionRecognitionPendingMessage
        return true
    }

    struct VisionRecognitionError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// Calls the configured vision model once per image and returns the wrapped
    /// recognition text keyed by the image's `data:` URL (Android OcrTransformer parity).
    private func performVisionRecognition(
        images: [PendingChatImage]
    ) async -> Result<[String: String], VisionRecognitionError> {
        let snapshot = sharedSettings.snapshot
        guard let visionModel = snapshot.findModelById(uuid: snapshot.ocrModelId) else {
            return .failure(VisionRecognitionError(message: "请先在「默认模型 → 辅助任务」配置视觉识别模型"))
        }
        guard let providerSetting = ChatProviderConfiguration.provider(
            for: visionModel,
            providers: snapshot.providers
        ), ChatProviderConfiguration.issue(for: visionModel, provider: providerSetting) == nil else {
            return .failure(VisionRecognitionError(message: "视觉识别模型的服务商不可用"))
        }
        let prompt = OcrPromptKt.resolveVisionRecognitionPrompt(prompt: snapshot.ocrPrompt)
        let assistant = snapshot.getCurrentAssistant()
        let params = Self.makeAuxiliaryTextGenerationParams(
            model: visionModel,
            assistantHeaders: assistant.customHeaders,
            assistantBodies: assistant.customBodies
        )
        var results: [String: String] = [:]
        for image in images {
            if let cached = cachedVisionRecognitionText(for: image.dataUrl) {
                results[image.dataUrl] = cached
                continue
            }
            let requestMessages = [
                UIMessage.companion.system(prompt: prompt),
                makeImageOnlyUserMessage(dataUrl: image.dataUrl)
            ]
            do {
                let chunk = try await auxiliaryTextProvider.generateText(
                    providerSetting: providerSetting,
                    messages: requestMessages,
                    params: params
                )
                let text = chunk.choices.first?.message?.toText()
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                guard !text.isEmpty else {
                    return .failure(VisionRecognitionError(message: "视觉识别模型没有返回可用内容"))
                }
                results[image.dataUrl] = """
                <image_context>
                \(text)
                </image_context>
                * 以上 image_context 是对用户上传图片的视觉识别结果，不是用户的提问。
                * 若用户要根据这张附图做风格转换、改图或垫图生图，调用 generate_image 时必须设置 use_attached_image=true；宿主会注入原图像素，不要只根据文字描述重画。
                """
            } catch {
                return .failure(VisionRecognitionError(message: "视觉识别失败：\((error as NSError).localizedDescription)"))
            }
        }
        return .success(results)
    }

    // MARK: - Auxiliary generation (title + chat suggestions + list preview)

    /// 一轮生成完成后触发:总是尝试生成对话建议与列表浓缩预览;首轮(仅 1 条用户消息)再生成标题。
    /// 工具审批暂停期间(有 pending approval)不触发;最后一条非助手消息也不触发。
    private func onGenerationCompleted() {
        guard pendingMemoryApproval == nil, pendingSearchApproval == nil,
              pendingWebMountApproval == nil, pendingWorkspaceApproval == nil,
              pendingIshHandoffApproval == nil,
              pendingMcpApproval == nil,
              pendingCouncilApproval == nil,
              pendingAskUser == nil,
              pendingRecipeApproval == nil else { return }
        guard let last = messages.last, last.role == MessageRole.assistant,
              !last.toText().trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        // 完成触觉：单次刚性轻触。iOS 本地偏好（默认开）；不复用 KMP
        // enableMessageGenerationHapticEffect——那是 Android-only 接线且默认关，
        // 动它会改变 Android 默认行为。
        let hapticKey = IOSDisplayPreferenceKeys.completionHaptic
        let defaults = UserDefaults.standard
        let hapticEnabled = defaults.object(forKey: hapticKey) == nil
            ? true
            : defaults.bool(forKey: hapticKey)
        if hapticEnabled {
            AmberHaptics.trigger(.rigidImpact)
        }

        generateChatSuggestions()
        let userMessageCount = messages.filter { $0.role == MessageRole.user }.count
        if userMessageCount == 1 { generateConversationTitle() }
        // 与标题共用 titleModelId；后台成功路径走同一 generator。
        generateConversationListPreview()
    }

    /// 辅助模型:优先用指定的辅助模型,未设置时回退当前聊天模型(对齐 Android resolveTaskChatModel)。
    private func resolveAuxModel(_ auxId: KotlinUuid) -> Model? {
        sharedSettings.snapshot.findModelById(uuid: auxId) ?? sharedSettings.snapshot.getCurrentChatModel()
    }

    /// 最近 [maxMessages] 条消息拼成带角色前缀的文本,填入提示词的 {content}。
    private func auxConversationText(maxMessages: Int) -> String {
        messages.suffix(maxMessages).compactMap { message -> String? in
            let text = message.toText().trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            let role: String
            switch message.role {
            case MessageRole.user: role = "User"
            case MessageRole.assistant: role = "Assistant"
            default: return nil
            }
            return "\(role): \(text)"
        }.joined(separator: "\n\n")
    }

    private func auxLocaleName() -> String {
        Locale.current.localizedString(forIdentifier: Locale.current.identifier) ?? Locale.current.identifier
    }

    /// 用辅助模型跑一次单轮文本生成(OpenAIKmpProviderAdapter 内部按服务商类型分发 OpenAI/Claude)。
    private func runAuxModel(model: Model, prompt: String) async -> String? {
        let snapshot = sharedSettings.snapshot
        guard let provider = ChatProviderConfiguration.provider(
            for: model,
            providers: snapshot.providers
        ), ChatProviderConfiguration.issue(for: model, provider: provider) == nil else { return nil }
        let assistant = snapshot.getCurrentAssistant()
        let params = Self.makeAuxiliaryTextGenerationParams(
            model: model,
            assistantHeaders: assistant.customHeaders,
            assistantBodies: assistant.customBodies
        )
        do {
            let chunk = try await auxiliaryTextProvider.generateText(
                providerSetting: provider,
                messages: [UIMessage.companion.user(prompt: prompt)],
                params: params
            )
            return chunk.choices.first?.message?.toText().trimmingCharacters(in: .whitespacesAndNewlines)
        } catch {
            return nil
        }
    }

    private static func makeAuxiliaryTextGenerationParams(
        model: Model,
        assistantHeaders: [CustomHeader],
        assistantBodies: [CustomBody]
    ) -> TextGenerationParams {
        TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.off,
            customHeaders: assistantHeaders + model.customHeaders,
            customBody: assistantBodies + model.customBodies
        )
    }

    private func generateConversationTitle() {
        let snapshot = sharedSettings.snapshot
        guard let model = resolveAuxModel(snapshot.titleModelId),
              let conversationId = currentConversationId,
              let conversationStore,
              let expectedTitle = conversationStore.currentConversation?.title else { return }
        let content = auxConversationText(maxMessages: 4)
        guard !content.isEmpty else { return }
        // 标题 LLM 顺带选 icon key：列表优先用它，避免事后猜标题关键词。
        let prompt = snapshot.titlePrompt
            .replacingOccurrences(of: "{locale}", with: auxLocaleName())
            .replacingOccurrences(of: "{content}", with: content)
            + HomeConversationIcon.llmIconInstructionBlock()
        Task { [weak self] in
            guard let self else { return }
            guard let raw = await self.runAuxModel(model: model, prompt: prompt) else { return }
            let parsed = Self.parseTitleAndIcon(raw)
            let title = Self.sanitizeTitle(parsed.title)
            // Title rename is optional (LLM may return only icon:). Icon write follows
            // rename success when a title is present — never after user rename mismatch.
            if !title.isEmpty {
                let renamed = await conversationStore.renameConversation(
                    id: conversationId,
                    title: title,
                    ifCurrentTitleMatches: expectedTitle
                )
                guard renamed else { return }
            }
            if let iconKey = parsed.iconKey {
                conversationStore.setListIconKey(id: conversationId, key: iconKey)
            }
        }
    }

    /// 列表浓缩预览：复用标题辅助模型（titleModelId → 当前聊天模型回退）。
    private func generateConversationListPreview() {
        guard let conversationId = currentConversationId,
              let conversationStore else { return }
        ConversationListPreviewGenerator.schedule(
            conversationId: conversationId,
            messages: messages,
            store: conversationStore,
            settings: sharedSettings
        )
    }

    private func generateChatSuggestions() {
        let snapshot = sharedSettings.snapshot
        guard let model = resolveAuxModel(snapshot.suggestionModelId) else { return }
        let content = auxConversationText(maxMessages: 8)
        guard !content.isEmpty else { return }
        let prompt = snapshot.suggestionPrompt
            .replacingOccurrences(of: "{locale}", with: auxLocaleName())
            .replacingOccurrences(of: "{content}", with: content)
        let conversationId = currentConversationId
        let requestToken = beginSuggestionRequest()
        suggestionGenerationTask = Task { [weak self] in
            guard let self else { return }
            guard let raw = await self.runAuxModel(model: model, prompt: prompt) else { return }
            guard !Task.isCancelled else { return }
            let suggestions = raw
                .split(separator: "\n")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .prefix(5)
            self.applySuggestions(
                Array(suggestions),
                requestToken: requestToken,
                conversationId: conversationId
            )
        }
    }

    private func beginSuggestionRequest() -> UUID {
        suggestionGenerationTask?.cancel()
        let token = UUID()
        suggestionRequestToken = token
        return token
    }

    private func invalidateSuggestionRequest() {
        suggestionGenerationTask?.cancel()
        suggestionGenerationTask = nil
        suggestionRequestToken = nil
    }

    private func applySuggestions(
        _ suggestions: [String],
        requestToken: UUID,
        conversationId: KotlinUuid?
    ) {
        guard suggestionRequestToken == requestToken,
              isCurrentConversation(conversationId),
              !isGenerationActive else { return }
        suggestionGenerationTask = nil
        withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
            chatSuggestions = suggestions
        }
    }

    /// 清洗标题:取首行、去引号/首尾标点、限长。
    static func sanitizeTitle(_ raw: String) -> String {
        var title = raw
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first(where: { !$0.isEmpty && !$0.lowercased().hasPrefix("icon:") })
            ?? ""
        title = title.trimmingCharacters(in: CharacterSet(charactersIn: "\"'“”『』「」《》 "))
        if title.count > 24 { title = String(title.prefix(24)) }
        return title
    }

    /// 解析标题 LLM 两行输出：`标题` + 可选 `icon:<key>`。
    static func parseTitleAndIcon(_ raw: String) -> (title: String, iconKey: String?) {
        let lines = raw
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        var iconKey: String?
        var titleLines: [String] = []
        for line in lines {
            let lower = line.lowercased()
            if lower.hasPrefix("icon:") {
                let key = String(line.dropFirst(5)).trimmingCharacters(in: .whitespacesAndNewlines)
                // 只存 catalog 规范化 slug，避免 lower 后的 camelCase 回读失败。
                if let canonical = HomeConversationIcon.canonicalLLMKey(key) {
                    iconKey = canonical
                }
                continue
            }
            titleLines.append(line)
        }
        return (titleLines.first ?? "", iconKey)
    }

    /// 清洗列表浓缩预览：与 `ConversationListPreviewGenerator.sanitize` 同源。
    static func sanitizeListPreview(_ raw: String) -> String {
        ConversationListPreviewGenerator.sanitize(raw)
    }

    private func makeImageOnlyUserMessage(dataUrl: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [UIMessagePart.Image(url: dataUrl, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    /// Builds the user message: a plain text message when there are no images, or a
    /// multi-part message (text + `UIMessagePart.Image`) the KMP providers turn into
    /// real image blocks. Image URLs are self-contained `data:` URLs.
    private func makeUserMessage(prompt: String, images: [PendingChatImage]) -> UIMessage {
        guard !images.isEmpty else {
            return UIMessage.companion.user(prompt: prompt)
        }
        var parts: [UIMessagePart] = []
        if !prompt.isEmpty {
            parts.append(UIMessagePart.Text(text: prompt, metadata: nil))
        }
        for image in images {
            parts.append(UIMessagePart.Image(url: image.dataUrl, metadata: nil))
        }
        return UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: parts,
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    static func editedUserMessage(original: UIMessage, newText: String) -> UIMessage {
        var parts: [UIMessagePart] = []
        var didInsertText = false
        for part in original.parts {
            if part is UIMessagePart.Text {
                if !didInsertText {
                    parts.append(UIMessagePart.Text(text: newText, metadata: nil))
                    didInsertText = true
                }
                continue
            }
            parts.append(part)
        }
        if !didInsertText {
            parts.insert(UIMessagePart.Text(text: newText, metadata: nil), at: 0)
        }
        return UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: parts,
            annotations: original.annotations,
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

#if DEBUG
    static func editedUserMessageForTesting(original: UIMessage, newText: String) -> UIMessage {
        editedUserMessage(original: original, newText: newText)
    }
#endif

    func attachSelectedFilePreviewToNextMessage(expectedConversationId: String? = nil) async {
        guard expectedConversationId == nil || expectedConversationId == currentConversationId.map({
            String(describing: $0)
        }) else { return }
        guard !isAttachingSelectedFile else { return }
        guard let localToolExecutor else {
            selectedFileContextError = "Local iOS tool executor is unavailable."
            return
        }

        let requestId = UUID()
        attachRequestId = requestId
        isAttachingSelectedFile = true
        let activityRunId = "tool-\(requestId.uuidString)"
        startLiveActivity(
            runId: activityRunId,
            conversationId: currentConversationId,
            presentation: .readingSelectedFile
        )
        defer {
            if attachRequestId == requestId {
                isAttachingSelectedFile = false
                attachRequestId = nil
            }
        }

        let request = localToolExecutor.requestForCurrentSelectedFile(isUserInitiated: true)
        let output = await localToolExecutor.execute(request)
        guard attachRequestId == requestId,
              expectedConversationId == nil || expectedConversationId == currentConversationId.map({
                  String(describing: $0)
              }) else { return }
        switch output {
        case .selectedFilePreview(let result):
            pendingSelectedFilePreview = result
            selectedFileContextError = nil
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadCompleted,
                dismissalDelay: 4
            )
        case .needsUserAction(let reason):
            selectedFileContextError = reason
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        case .denied(let reason):
            selectedFileContextError = reason
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        case .failed(let message):
            selectedFileContextError = message
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        case .permissionsStatus:
            selectedFileContextError = "permissions_status cannot be attached to a chat message."
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        case .webMountResult:
            selectedFileContextError = "WebMount tool output cannot be attached to a chat message."
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        case .workspaceResult:
            selectedFileContextError = "Workspace tool output cannot be attached to a chat message."
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        case .ishExecuteResult, .ishHandoffResult:
            selectedFileContextError = "iSH tool output cannot be attached to a chat message."
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        }
    }

    func clearPendingSelectedFilePreview() {
        pendingSelectedFilePreview = nil
        selectedFileContextError = nil
    }

    // MARK: - Image attachments

    static let maxImagesPerMessage = 4

    struct PendingChatImage: Identifiable, Equatable {
        let id = UUID()
        /// `data:<mime>;base64,...` — self-contained so it survives persistence and regeneration.
        let dataUrl: String
        /// Small JPEG bytes used only to render the composer thumbnail.
        let previewData: Data
    }

    enum ImageAttachmentState: Equatable {
        case none
        /// The current chat model accepts image input — send the image directly.
        case ready
        /// The current model is text-only but a vision model is configured — the image
        /// is recognized into text by the vision model first, then sent to the current model.
        case fallback
        case blocked(String)
    }

    /// Cached `<image_context>` recognition text per image digest, so the vision
    /// model is called once per image (covers regeneration within the session too).
    @ObservationIgnored private var visionRecognitionTexts: [String: String] = [:]
    @ObservationIgnored private var visionRecognitionTextKeys: [String] = []
    private static let maxVisionRecognitionCacheEntries = 16

    private func cachedVisionRecognitionText(for dataUrl: String) -> String? {
        let key = Self.visionRecognitionCacheKey(for: dataUrl)
        guard let text = visionRecognitionTexts[key] else { return nil }
        visionRecognitionTextKeys.removeAll { $0 == key }
        visionRecognitionTextKeys.append(key)
        return text
    }

    private func cacheVisionRecognitionTexts(_ texts: [String: String]) {
        for (dataUrl, value) in texts {
            let key = Self.visionRecognitionCacheKey(for: dataUrl)
            visionRecognitionTexts[key] = value
            visionRecognitionTextKeys.removeAll { $0 == key }
            visionRecognitionTextKeys.append(key)
        }

        while visionRecognitionTextKeys.count > Self.maxVisionRecognitionCacheEntries {
            let evicted = visionRecognitionTextKeys.removeFirst()
            visionRecognitionTexts.removeValue(forKey: evicted)
        }
    }

    private static func visionRecognitionCacheKey(for dataUrl: String) -> String {
        let digest = SHA256.hash(data: Data(dataUrl.utf8))
        let prefix = digest.prefix(16).map { String(format: "%02x", $0) }.joined()
        return "vision:\(prefix)"
    }

#if DEBUG
    static func visionRecognitionCacheKeyForTesting(_ dataUrl: String) -> String {
        visionRecognitionCacheKey(for: dataUrl)
    }
#endif

    func addPendingImage(dataUrl: String, previewData: Data) {
        guard pendingImages.count < Self.maxImagesPerMessage else {
            selectedFileContextError = "一次最多发送 \(Self.maxImagesPerMessage) 张图片"
            return
        }
        pendingImages.append(PendingChatImage(dataUrl: dataUrl, previewData: previewData))
        selectedFileContextError = nil
    }

    func removePendingImage(_ id: PendingChatImage.ID) {
        pendingImages.removeAll { $0.id == id }
    }

    func clearPendingImages() {
        pendingImages.removeAll()
    }

    /// Mirrors Android `ImageAttachmentValidator`: the current model having image input
    /// means the image is ready to send; otherwise a configured vision model is required,
    /// and failing that the send is blocked with a prompt (never silently dropped).
    var imageAttachmentState: ImageAttachmentState {
        guard !pendingImages.isEmpty else { return .none }
        if pendingImages.count > Self.maxImagesPerMessage {
            return .blocked("一次最多发送 \(Self.maxImagesPerMessage) 张图片")
        }
        let snapshot = sharedSettings.snapshot
        guard let model = snapshot.getCurrentChatModel() else {
            return .blocked("请先选择模型")
        }
        if Self.modelSupportsImageInput(model) { return .ready }
        // The user explicitly configured a vision model for this purpose — trust it
        // (capability metadata isn't reliably known for every model id), only requiring
        // that it resolves and has a usable provider.
        if let vision = snapshot.findModelById(uuid: snapshot.ocrModelId),
           let provider = ChatProviderConfiguration.provider(for: vision, providers: snapshot.providers),
           ChatProviderConfiguration.issue(for: vision, provider: provider) == nil {
            return .fallback
        }
        return .blocked("当前模型不支持图片，请先在「默认模型 → 辅助任务」配置视觉识别模型")
    }

    private static func modelSupportsImageInput(_ model: Model) -> Bool {
        let modalities = model.inputModalities.isEmpty
            ? (ModelRegistry.shared.MODEL_INPUT_MODALITIES.getData(modelId: model.modelId) as? [Modality] ?? [])
            : model.inputModalities
        return modalities.contains { $0.name == "IMAGE" }
    }

    func approvePendingMemoryTool() {
        generationCoordinator.approvePendingMemoryTool()
    }

    func denyPendingMemoryTool() {
        generationCoordinator.denyPendingMemoryTool()
    }

    func approvePendingSearchTool() {
        Task { @MainActor in
            await generationCoordinator.approvePendingSearchTool()
        }
    }

    func denyPendingSearchTool() {
        Task { @MainActor in
            await generationCoordinator.denyPendingSearchTool()
        }
    }

    func approvePendingWebMountTool() {
        Task { @MainActor in
            await generationCoordinator.approvePendingWebMountTool()
        }
    }

    func denyPendingWebMountTool() {
        Task { @MainActor in
            await generationCoordinator.denyPendingWebMountTool()
        }
    }

    func approvePendingWorkspaceTool() {
        Task { @MainActor in
            await generationCoordinator.approvePendingWorkspaceTool()
        }
    }

    func denyPendingWorkspaceTool() {
        Task { @MainActor in
            await generationCoordinator.denyPendingWorkspaceTool()
        }
    }

    func approvePendingIshHandoffTool() {
        Task { @MainActor in
            await generationCoordinator.approvePendingIshHandoffTool()
        }
    }

    func denyPendingIshHandoffTool() {
        Task { @MainActor in
            await generationCoordinator.denyPendingIshHandoffTool()
        }
    }

    func approvePendingMcpTool(requestId: String) {
        Task { @MainActor in
            await generationCoordinator.approvePendingMcpTool(requestId: requestId)
        }
    }

    func denyPendingMcpTool(requestId: String) {
        Task { @MainActor in
            await generationCoordinator.denyPendingMcpTool(requestId: requestId)
        }
    }

    /// Wave B2: recipe 审批卡（mutation step / recipe_import）。
    func approvePendingRecipeTool(requestId: String) {
        Task { @MainActor in
            await generationCoordinator.approvePendingRecipeTool(requestId: requestId)
        }
    }

    func denyPendingRecipeTool(requestId: String) {
        Task { @MainActor in
            await generationCoordinator.denyPendingRecipeTool(requestId: requestId)
        }
    }

    func approvePendingCouncilTool() {
        Task { @MainActor in
            await generationCoordinator.approvePendingCouncilTool()
        }
    }

    func denyPendingCouncilTool() {
        Task { @MainActor in
            await generationCoordinator.denyPendingCouncilTool()
        }
    }

    @discardableResult
    func answerPendingAskUser(_ answer: String) -> Bool {
        generationCoordinator.answerPendingAskUser(answer)
    }

    @discardableResult
    func skipPendingAskUser() -> Bool {
        generationCoordinator.answerPendingAskUser("")
    }

    func approveAnyPendingToolFromWatch() async {
        if pendingMemoryApproval != nil {
            approvePendingMemoryTool()
        } else if pendingSearchApproval != nil {
            await generationCoordinator.approvePendingSearchTool()
        } else if pendingWebMountApproval != nil {
            await generationCoordinator.approvePendingWebMountTool()
        } else if pendingWorkspaceApproval != nil {
            await generationCoordinator.approvePendingWorkspaceTool()
        } else if pendingIshHandoffApproval != nil {
            await generationCoordinator.approvePendingIshHandoffTool()
        } else if pendingMcpApproval != nil {
            await generationCoordinator.approvePendingMcpTool()
        } else if pendingCouncilApproval != nil {
            await generationCoordinator.approvePendingCouncilTool()
        } else if let request = pendingRecipeApproval {
            // Slice B（B2）：Watch 路径也把 UI 展示的 request id 传回，
            // 消费前核对当前 pending request id。
            await generationCoordinator.approvePendingRecipeTool(requestId: request.id)
        }
    }

    func denyAnyPendingToolFromWatch() async {
        if pendingMemoryApproval != nil {
            denyPendingMemoryTool()
        } else if pendingSearchApproval != nil {
            await generationCoordinator.denyPendingSearchTool()
        } else if pendingWebMountApproval != nil {
            await generationCoordinator.denyPendingWebMountTool()
        } else if pendingWorkspaceApproval != nil {
            await generationCoordinator.denyPendingWorkspaceTool()
        } else if pendingIshHandoffApproval != nil {
            await generationCoordinator.denyPendingIshHandoffTool()
        } else if pendingMcpApproval != nil {
            await generationCoordinator.denyPendingMcpTool()
        } else if pendingCouncilApproval != nil {
            await generationCoordinator.denyPendingCouncilTool()
        } else if pendingAskUser != nil {
            skipPendingAskUser()
        } else if let request = pendingRecipeApproval {
            // Slice B（B2）：Watch deny 同样传回 UI 展示的 request id。
            await generationCoordinator.denyPendingRecipeTool(requestId: request.id)
        }
    }

    @discardableResult
    func submitWatchUserAnswer(runId: String, text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // First-class ask_user pending node resumes the current tool, not a new user turn.
        // Empty text is an explicit skip (denied tool output), matching iPhone skip.
        if pendingAskUser != nil, canOpenActivityConfirmation(runId: runId) {
            return answerPendingAskUser(trimmed)
        }
        guard !trimmed.isEmpty else { return false }
        // Tool approvals must use allow/deny, not free text.
        if canOpenActivityConfirmation(runId: runId) {
            return false
        }
        guard !isGenerationActive else { return false }
        inputText = trimmed
        return sendMessage()
    }

    func cancelGeneration() {
        // cancel() itself publishes the cancelled watch snapshot; do not clear first.
        if generationCoordinator.isRunning {
            generationCoordinator.cancel()
            return
        }
        guard let currentConversationId else { return }
        _ = IOSChatBackgroundGenerationCoordinator.shared.cancelActiveJob(
            conversationId: currentConversationId
        )
    }

    @discardableResult
    func cancelGeneration(runId: String) -> Bool {
        if generationCoordinator.cancel(runId: runId) {
            return true
        }
        return IOSChatBackgroundGenerationCoordinator.shared.cancelJob(runId: runId)
    }

    @discardableResult
    func handoffGenerationToBackgroundIfNeeded(honorKeepAliveLease: Bool = false) -> Bool {
        generationCoordinator.handoffCurrentGenerationToBackground(
            conversationStore: conversationStore,
            honorKeepAliveLease: honorKeepAliveLease
        )
    }

    @discardableResult
    func prepareForConversationChange() -> Bool {
        prepareForConversationChange(to: nil)
    }

    // MARK: - Message branching actions (Android ChatService parity)
    //
    // These operate on the Conversation tree via IOSConversationStore. After
    // mutating the tree they re-sync `self.messages` (the flat currentMessages
    // projection) so the chat list reflects the new branch, then re-run
    // generation for regenerate. Mirrors Android ChatService.regenerateAtMessage
    // / editMessage.

    /// Regenerate the assistant reply that follows a given message index.
    /// - If the target is a USER message: drop everything after it and re-run
    ///   generation (Android's USER-regenerate path).
    /// - If the target is an ASSISTANT message: drop it + everything after, then
    ///   re-run from the preceding user turn (produces a fresh variant; the old
    ///   reply is replaced in this minimal implementation — full variant
    ///   retention is surfaced via selectVariant for nodes that already have
    ///   multiple siblings).
    func regenerate(atMessageIndex index: Int) {
        guard !rejectVisionRecognitionMutationIfNeeded(), !isGenerationActive,
              !currentConversationIsOrchestratedChild else { return }
        guard let store = conversationStore,
              let conversation = store.currentConversation,
              index >= 0, index < conversation.messageNodes.count else { return }

        let targetNode = conversation.messageNodes[index]
        if targetNode.role == MessageRole.user {
            Task { @MainActor in
                pendingAssistantRegeneration = nil
                await store.truncateAfter(messageIndex: index)
                // Re-sync the flat projection from the mutated tree.
                if let updated = store.currentConversation {
                    self.messages = updated.currentMessages
                    self.bumpMessageRevision(reason: .branchChange)
                }
                let digest = chatInputDigest(for: regenerateDigestSeed())
                generateResponse(inputDigest: digest, conversationId: currentConversationId)
            }
        } else {
            // Assistant: regenerate from the user turn immediately before it.
            // Keep the original assistant node in storage. The new assistant
            // turn is appended as a variant after streaming completes.
            // messageNodes is a Kotlin List — convert to Swift Array so we can
            // use Swift's lastIndex(where:) on the prefix.
            let nodes = Array(conversation.messageNodes.prefix(index))
            guard let precedingUser = nodes.lastIndex(where: { $0.role == MessageRole.user }) else {
                return
            }
            Task { @MainActor in
                let uploadMessages = Array(conversation.currentMessages.prefix(precedingUser + 1))
                self.pendingAssistantRegeneration = PendingAssistantRegeneration(
                    conversationId: conversation.id,
                    targetMessageIndex: index,
                    generatedMessageIndex: uploadMessages.count
                )
                self.messages = uploadMessages
                self.bumpMessageRevision(reason: .branchChange)
                let digest = chatInputDigest(for: regenerateDigestSeed())
                generateResponse(inputDigest: digest, conversationId: conversation.id)
            }
        }
    }

    /// Edit a user message in place and re-run generation from it. The edited
    /// text replaces the selected variant; the conversation is truncated after
    /// the edited user turn (Android's editMessage = append-variant + re-run).
    func editMessage(atMessageIndex index: Int, newText: String) {
        guard !rejectVisionRecognitionMutationIfNeeded(), !isGenerationActive,
              !currentConversationIsOrchestratedChild else { return }
        let trimmed = newText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard let store = conversationStore,
              let conversation = store.currentConversation,
              index >= 0, index < conversation.messageNodes.count else { return }

        let node = conversation.messageNodes[index]
        guard node.role == MessageRole.user else { return }

        let original = store.currentMessages.indices.contains(index)
            ? store.currentMessages[index]
            : node.messages[Int(node.selectIndex)]
        let edited = Self.editedUserMessage(original: original, newText: trimmed)
        Task { @MainActor in
            pendingAssistantRegeneration = nil
            await store.appendVariant(messageIndex: index, message: edited)
            // Truncate anything after the edited user turn (drop stale reply).
            await store.truncateAfter(messageIndex: index)
            if let updated = store.currentConversation {
                self.messages = updated.currentMessages
                self.bumpMessageRevision(reason: .branchChange)
                _ = await self.persistMessages(conversationId: currentConversationId)
            }
            let digest = chatInputDigest(for: trimmed)
            generateResponse(inputDigest: digest, conversationId: currentConversationId)
        }
    }

    /// Delete a single message (and its node). No generation.
    func deleteMessage(atMessageIndex index: Int) {
        guard !rejectVisionRecognitionMutationIfNeeded(), !isGenerationActive,
              !currentConversationIsOrchestratedChild, let store = conversationStore else { return }
        Task { @MainActor in
            await store.deleteMessage(messageIndex: index)
            if let updated = store.currentConversation {
                self.messages = updated.currentMessages
                self.bumpMessageRevision(reason: .branchChange)
            }
            _ = await self.persistMessages(conversationId: currentConversationId)
        }
    }

    /// Switch the visible variant of a node (no generation).
    func selectVariant(messageIndex: Int, variantIndex: Int) {
        guard !rejectVisionRecognitionMutationIfNeeded(), !isGenerationActive,
              !currentConversationIsOrchestratedChild, let store = conversationStore else { return }
        Task { @MainActor in
            await store.selectVariant(messageIndex: messageIndex, variantIndex: variantIndex)
            if let updated = store.currentConversation {
                self.messages = updated.currentMessages
                self.bumpMessageRevision(reason: .branchChange)
            }
        }
    }

    /// Variant info for the UI (to render `< n/m >` when a node has siblings).
    func variantInfo(atMessageIndex index: Int) -> IOSConversationStore.VariantInfo? {
        conversationStore?.variantInfo(forMessageIndex: index)
    }

    func messageIndex(forMessageId messageId: String) -> Int? {
        messages.firstIndex { ChatMessageProjector.messageId(for: $0) == messageId }
    }

    func regenerate(messageId: String) {
        guard let index = messageIndex(forMessageId: messageId) else { return }
        regenerate(atMessageIndex: index)
    }

    func editMessage(messageId: String, newText: String) {
        guard let index = messageIndex(forMessageId: messageId) else { return }
        editMessage(atMessageIndex: index, newText: newText)
    }

    func deleteMessage(messageId: String) {
        guard let index = messageIndex(forMessageId: messageId) else { return }
        deleteMessage(atMessageIndex: index)
    }

    func selectVariant(messageId: String, variantIndex: Int) {
        guard let index = messageIndex(forMessageId: messageId) else { return }
        selectVariant(messageIndex: index, variantIndex: variantIndex)
    }

#if DEBUG
    func generateResponseForTesting(inputDigest: String, conversationId: KotlinUuid?) {
        generateResponse(inputDigest: inputDigest, conversationId: conversationId)
    }

    /// P1-a 测试缝：访问 ViewModel 自己的 generationCoordinator（其 bindings 指向本
    /// ViewModel，drainSteerQueue/drainMailbox/restoreSteerQueueLeftover 走真实实现）。
    var generationCoordinatorForTesting: ChatGenerationCoordinator {
        generationCoordinator
    }

    /// P1-a/P1-b 测试缝：工具循环边界消费（mailbox 先于 steer）+ 下一轮 upload 组装
    /// （复用 continueAfterToolResult 的真实生产函数，不发起流式请求）。
    func nextRoundMessagesAfterMailboxAndSteerConsumptionForTesting(
        baseMessages: [UIMessage]
    ) async -> [UIMessage] {
        await generationCoordinator.nextRoundMessagesAfterMailboxAndSteerConsumptionForTesting(
            baseMessages: baseMessages,
            conversationId: currentConversationId
        )
    }

    func shouldApplyVisionRecognitionResultForTesting(
        conversationId: KotlinUuid?,
        userMessageId: String
    ) -> Bool {
        shouldApplyVisionRecognitionResult(conversationId: conversationId, userMessageId: userMessageId)
    }

    func applyVisionRecognitionFailureForTesting(
        message: String,
        conversationId: KotlinUuid?,
        userMessageId: String
    ) async {
        await applyVisionRecognitionFailure(
            message: message,
            conversationId: conversationId,
            userMessageId: userMessageId
        )
    }

    func applyVisionRecognitionSuccessForTesting(
        conversationId: KotlinUuid?,
        userMessageId: String
    ) {
        guard shouldApplyVisionRecognitionResult(conversationId: conversationId, userMessageId: userMessageId) else { return }
        clearVisionRecognitionPendingPrompt()
    }
#endif

    private func regenerateDigestSeed() -> String {
        messages.last(where: { $0.role == MessageRole.user })?.toText() ?? ""
    }

    // MARK: - Private

    private func generateResponse(inputDigest: String, conversationId: KotlinUuid?) {
        if let conversationId {
            guard !isGenerationActive(conversationId: conversationId) else { return }
        } else {
            guard !isGenerationActive else { return }
        }
        isLoading = true

        let providerSetting = makeProviderSetting()
        let params = makeTextGenerationParams()

        guard let resolvedProvider = providerSetting else {
            isLoading = false
            configurationError = configurationIssue?.message ?? ChatConfigurationIssue.missingProvider.message
            return
        }

        generationCoordinator.start(
            providerSetting: resolvedProvider,
            params: params,
            inputDigest: inputDigest,
            conversationId: conversationId,
            uploadMessages: messages,
            toolExposureBridge: lastAssembledToolExposureBridge
        )
    }

    /// P1 管线闭环：参与线程树的会话注入的 mailbox 语义说明（仅在
    /// currentConversationHasOrchestrationLinks 为真时出现在上传上下文）。
    static let orchestrationContextPrompt = """
    Thread orchestration is active for this conversation.
    - Messages prefixed `[mailbox MESSAGE|NEW_TASK|FINAL_ANSWER from /root/...]` are inter-agent mail, not user input; FINAL_ANSWER is a child thread's completion report.
    - Child threads run in the background and report back automatically; call wait_agent to block for new mail mid-run, send_message/followup_task to contact a child, interrupt_agent to stop one (the thread stays addressable).
    - The user cannot type into child threads; relay important results to the user yourself.
    """

    private func messagesByInjectingRuntimeContext(_ messages: [UIMessage]) -> [UIMessage] {
        let uploadableMessages = messages.filter { !Self.isLocalGenerationError($0) }
        // P0-a Fix A: when the current run bridge is in lazy mode, prepend the
        // tool_search discovery guidance as a standalone system fragment (the
        // builder below runs with coalesceSystemMessages: false, so fragments
        // stay separate), so the model knows hidden tools are "not callable
        // until" tool_search exposes them. The bridge is assembled by
        // makeTextGenerationParams() before this runs — messages are only
        // injected inside a started run, after the bridge exists.
        var uploadableWithGuidance = uploadableMessages
        if let guidance = lastAssembledToolExposureBridge?.discoveryGuidance(),
           !guidance.isEmpty {
            uploadableWithGuidance = [UIMessage.companion.system(prompt: guidance)] + uploadableWithGuidance
        }
        // 线程编排语境（管线闭环）：只有参与线程树的会话才注入 mailbox 语义——
        // 信封以 user 消息形态折入（`[mailbox TYPE from ...]`），模型需要知道
        // 这不是用户输入、子线程会自动回报，否则会把子线程报告当成用户话语。
        if currentConversationHasOrchestrationLinks {
            uploadableWithGuidance = [UIMessage.companion.system(prompt: Self.orchestrationContextPrompt)] + uploadableWithGuidance
        }
        let withContext = ChatRuntimeContextBuilder(
            sharedSettings: sharedSettings,
            mcpTools: mcpManager.tools,
            miniAppRepository: miniAppRepository,
            miniAppRuntimeEnabled: isMiniAppRuntimeEnabled,
            experienceCurator: experienceCurator
        ).injectingRuntimeContext(into: uploadableWithGuidance, coalesceSystemMessages: false)
        return replacingImagesForNonVisionModel(withContext)
    }

    private func memoryRecordIdsForRuntimeContext(_ messages: [UIMessage]) -> [Int32] {
        let uploadableMessages = messages.filter { !Self.isLocalGenerationError($0) }
        return ChatRuntimeContextBuilder(
            sharedSettings: sharedSettings,
            mcpTools: mcpManager.tools,
            miniAppRepository: miniAppRepository,
            miniAppRuntimeEnabled: isMiniAppRuntimeEnabled
        ).memoryRecallResult(for: uploadableMessages).ids
    }

    private func recordMemoryUsage(_ ids: [Int32], force: Bool = false) {
        // P2-b: 注入即使用（injected into upload = used）。去抖与原子写在
        // IOSMemoryPersistence.markUsed 内：同一 run 同集合不重复写盘。
        // P2-c 修复 2：模型显式引用（citation flush）传 force: true，绕过
        // 同集去抖——引用是模型信号，与召回标记语义不同，应始终生效。
        IOSMemoryPersistence.shared.markUsed(ids: Set(ids), force: force)
    }

    private static func isLocalGenerationError(_ message: UIMessage) -> Bool {
        guard message.role == MessageRole.assistant else { return false }
        return message.parts.contains { part in
            guard let text = part as? UIMessagePart.Text else { return false }
            return text.metadata?[MessageKt.LOCAL_GENERATION_ERROR_METADATA_KEY]?
                .jsonPrimitiveOrNull?.content == MessageKt.LOCAL_GENERATION_ERROR_METADATA_VALUE
        }
    }

    private static func isRegeneratedAnswerCandidate(_ message: UIMessage) -> Bool {
        guard message.role == MessageRole.assistant,
              !isLocalGenerationError(message),
              !isOutputLimitNotice(message) else { return false }
        return !message.toText().trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private static func isOutputLimitNotice(_ message: UIMessage) -> Bool {
        message.parts.contains { part in
            guard let text = part as? UIMessagePart.Text else { return false }
            return text.metadata?[MessageKt.LOCAL_GENERATION_ERROR_METADATA_KEY]?
                .jsonPrimitiveOrNull?.content == MessageKt.LOCAL_OUTPUT_LIMIT_NOTICE_METADATA_VALUE
        }
    }

    /// The stored/displayed messages keep their image parts (so the user sees the photo in
    /// the bubble), but a text-only chat model cannot receive image blocks. When the current
    /// model has no image input, swap each image part for its cached vision-recognition text
    /// before the request leaves for the provider.
    private func replacingImagesForNonVisionModel(_ messages: [UIMessage]) -> [UIMessage] {
        guard let model = sharedSettings.snapshot.getCurrentChatModel(),
              !Self.modelSupportsImageInput(model) else {
            return messages
        }
        guard messages.contains(where: { $0.parts.contains { $0 is UIMessagePart.Image } }) else {
            return messages
        }
        return messages.map { message in
            guard message.parts.contains(where: { $0 is UIMessagePart.Image }) else { return message }
            let newParts: [UIMessagePart] = message.parts.map { part in
                guard let image = part as? UIMessagePart.Image else { return part }
                let recognized = cachedVisionRecognitionText(for: image.url) ?? "[图片未能识别]"
                return UIMessagePart.Text(text: recognized, metadata: nil)
            }
            return UIMessage(
                id: message.id,
                role: message.role,
                parts: newParts,
                annotations: message.annotations,
                createdAt: message.createdAt,
                finishedAt: message.finishedAt,
                modelId: message.modelId,
                usage: message.usage,
                translation: message.translation
            )
        }
    }

#if DEBUG
    func preparedUploadMessagesForTesting(_ messages: [UIMessage]) -> [UIMessage] {
        messagesByInjectingRuntimeContext(messages)
    }
#endif

    /// App-level background recovery uses the same transform as foreground completion.
    func applyMiniAppOutputIfPresentPublic(
        to messages: [UIMessage],
        conversationId: KotlinUuid?
    ) -> ChatMiniAppOutputApplication? {
        applyMiniAppOutputIfPresent(to: messages, conversationId: conversationId)
    }

    /// Returns a full replaced message list when MiniApp output is applied; nil means unchanged.
    private func applyMiniAppOutputIfPresent(
        to messages: [UIMessage],
        conversationId: KotlinUuid?
    ) -> ChatMiniAppOutputApplication? {
        guard isMiniAppRuntimeEnabled else { return nil }
        guard let turn = ChatRuntimeContextBuilder.miniAppTurnContext(in: messages) else {
            return nil
        }
        let currentTurn = messages.index(after: turn.currentUserIndex)..<messages.endIndex
        guard !messages[currentTurn].contains(where: { message in
            message.role == MessageRole.assistant && message.parts.contains { $0 is UIMessagePart.MiniApp }
        }) else {
            return nil
        }
        guard let assistantIndex = IOSMiniAppChatMessageFactory.assistantCandidateIndex(
                in: messages,
                afterUserIndex: turn.currentUserIndex
              ) ?? messages[currentTurn].lastIndex(where: { $0.role == MessageRole.assistant }) else {
            return nil
        }
        let assistant = messages[assistantIndex]
        guard let textPartIndex = assistant.parts.lastIndex(where: { $0 is UIMessagePart.Text }),
              let textPart = assistant.parts[textPartIndex] as? UIMessagePart.Text else {
            return nil
        }
        let userText = turn.requestText
        guard IOSMiniAppChatMessageFactory.mightContainMiniApp(textPart.text) else {
            if let targetAppId = ChatRuntimeContextBuilder.revisionAppId(in: userText) {
                let current = miniAppRepository.get(targetAppId)
                let requestedVersion = ChatRuntimeContextBuilder.revisionVersion(in: userText)
                if current == nil || requestedVersion.map({ $0 != current?.version }) == true {
                    // The injected revision instruction explicitly asks for a short
                    // explanation (and no JSON) when the target is missing or stale.
                    return nil
                }
            }
            var updated = messages
            updated[assistantIndex] = IOSMiniAppChatMessageFactory.parseFailureAssistant(
                assistant,
                textPartIndex: textPartIndex,
                reason: "模型没有返回完整的 MiniApp JSON 或 HTML。请重试，或调高最大输出长度后重新生成。"
            )
            return ChatMiniAppOutputApplication(messages: updated, outcome: .failed)
        }

        let parser = IOSMiniAppOutputParser()
        let output: IOSMiniAppGeneratedOutput
        do {
            output = try parser.parse(textPart.text)
        } catch {
            let reason = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            var updated = messages
            updated[assistantIndex] = IOSMiniAppChatMessageFactory.parseFailureAssistant(
                assistant,
                textPartIndex: textPartIndex,
                reason: reason
            )
            return ChatMiniAppOutputApplication(messages: updated, outcome: .failed)
        }

        let sourceConversationId = conversationId.map { String(describing: $0) }
        let sourceMessageId = String(describing: assistant.id)
        let revisionAppId = ChatRuntimeContextBuilder.revisionAppId(in: userText)

        do {
            let mutation: IOSMiniAppRepository.Mutation
            if let targetAppId = revisionAppId {
                guard let revision = try miniAppRepository.saveRevisionMutation(
                    appId: targetAppId,
                    output: output,
                    expectedBaseVersion: ChatRuntimeContextBuilder.revisionVersion(in: userText),
                    sourceMessageId: sourceMessageId,
                    changeNote: IOSMiniAppChatMessageFactory.revisionChangeNote(from: userText)
                ) else {
                    var updated = messages
                    updated[assistantIndex] = IOSMiniAppChatMessageFactory.revisionFailedAssistant(
                        assistant,
                        textPartIndex: textPartIndex
                    )
                    return ChatMiniAppOutputApplication(messages: updated, outcome: .failed)
                }
                mutation = revision
            } else {
                mutation = try miniAppRepository.saveGeneratedMutation(
                    output,
                    sourceConversationId: sourceConversationId,
                    sourceMessageId: sourceMessageId
                )
            }
            let record = mutation.record
            let statusText = revisionAppId != nil
                ? "已更新小应用：\(record.title) v\(record.version)"
                : "已生成小应用：\(record.title)"
            var updated = messages
            updated[assistantIndex] = IOSMiniAppChatMessageFactory.updatedAssistant(
                assistant,
                textPartIndex: textPartIndex,
                statusText: statusText,
                record: record
            )
            var rollbackMessages = messages
            rollbackMessages[assistantIndex] = IOSMiniAppChatMessageFactory.parseFailureAssistant(
                assistant,
                textPartIndex: textPartIndex,
                reason: "会话保存失败，因此此次小应用生成未保留。请检查存储空间后重试。"
            )
            return ChatMiniAppOutputApplication(
                messages: updated,
                rollbackMessages: rollbackMessages,
                commit: { [miniAppRepository, mutation] in
                    do {
                        return try miniAppRepository.commit(mutation)
                    } catch {
                        NSLog("[AmberChat] Failed to commit MiniApp transaction: \(error)")
                        return false
                    }
                },
                rollback: { [miniAppRepository, mutation] in
                    do {
                        return try miniAppRepository.rollback(mutation)
                    } catch {
                        NSLog("[AmberChat] Failed to roll back MiniApp mutation: \(error)")
                        return false
                    }
                },
                syncWorkspace: { [workspaceStore, updated, assistant, assistantIndex, textPartIndex, statusText, record] in
                    do {
                        _ = try workspaceStore.saveArtifact(
                            title: record.title,
                            content: record.htmlContent,
                            type: .miniApp,
                            sourceKind: "miniapp",
                            sourceId: record.id
                        )
                        return nil
                    } catch {
                        let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                        var failedMessages = updated
                        failedMessages[assistantIndex] = IOSMiniAppChatMessageFactory.updatedAssistant(
                            assistant,
                            textPartIndex: textPartIndex,
                            statusText: "\(statusText)\nWorkspace 同步失败：\(message)",
                            record: record
                        )
                        return ChatMiniAppWorkspaceSyncFailure(
                            messages: failedMessages,
                            replacementMessage: failedMessages[assistantIndex]
                        )
                    }
                }
            )
        } catch IOSMiniAppStoreError.notFound {
            var updated = messages
            updated[assistantIndex] = IOSMiniAppChatMessageFactory.revisionFailedAssistant(
                assistant,
                textPartIndex: textPartIndex
            )
            return ChatMiniAppOutputApplication(messages: updated, outcome: .failed)
        } catch {
            let reason = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            var updated = messages
            updated[assistantIndex] = IOSMiniAppChatMessageFactory.parseFailureAssistant(
                assistant,
                textPartIndex: textPartIndex,
                reason: reason
            )
            return ChatMiniAppOutputApplication(messages: updated, outcome: .failed)
        }
    }

#if DEBUG
    func finishedToolCallMessagesForTesting(
        _ targetToolCall: UIMessagePart.Tool,
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        generationCoordinator.finishedToolCallMessagesForTesting(
            targetToolCall,
            outputText: outputText,
            in: messages
        )
    }

    func memoryToolOutputForTesting(input: String) -> String {
        generationCoordinator.memoryToolOutputForTesting(input: input)
    }

    func memoryApprovalRequestForTesting(input: String) -> MemoryToolApprovalRequest? {
        generationCoordinator.memoryApprovalRequestForTesting(input: input)
    }

    func memoryToolApprovalOutputForTesting(input: String, allow: Bool) -> String {
        generationCoordinator.memoryToolApprovalOutputForTesting(input: input, allow: allow)
    }

    func webMountToolOutputForTesting(
        toolName: String,
        input: String,
        isUserInitiated: Bool = false
    ) async -> String {
        await generationCoordinator.webMountToolOutputForTesting(
            toolName: toolName,
            input: input,
            isUserInitiated: isUserInitiated
        )
    }

    func webMountApprovalRequestForTesting(
        toolName: String,
        input: String
    ) async -> WebMountToolApprovalRequest? {
        await generationCoordinator.webMountApprovalRequestForTesting(toolName: toolName, input: input)
    }

    func webMountToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        await generationCoordinator.webMountToolApprovalOutputForTesting(
            toolName: toolName,
            input: input,
            allow: allow
        )
    }

    func searchApprovalRequestForTesting(
        toolName: String,
        input: String
    ) -> SearchToolApprovalRequest? {
        generationCoordinator.searchApprovalRequestForTesting(toolName: toolName, input: input)
    }

    func searchToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        await generationCoordinator.searchToolApprovalOutputForTesting(
            toolName: toolName,
            input: input,
            allow: allow
        )
    }
#endif

    private func startLiveActivity(
        runId: String,
        conversationId: KotlinUuid?,
        presentation: AgentActivityPresentation
    ) {
        let conversationHex = conversationId?.toHexDashString()
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: conversationHex,
            presentation: presentation
        )
        guard liveActivityPreferenceEnabled else { return }
        // 只在 conversationId 匹配当前会话时传标题，避免审批恢复/后台 handoff
        // 时用户已切会话导致灵动岛显示错误标题。
        let isCurrentConversation = conversationId == currentConversationId
        liveActivityController.start(
            runId: runId,
            conversationId: conversationHex,
            conversationTitle: isCurrentConversation
                ? conversationStore?.currentConversation?.title
                : nil,
            presentation: presentation
        )
    }

    static func promptText(
        userText: String,
        selectedFilePreview: SelectedDocumentReadResult?
    ) -> String {
        guard let selectedFilePreview else { return userText }
        let status = selectedFilePreview.statusSummary
        return """
        \(userText)

        [文件上下文]
        来源文件：\(selectedFilePreview.fileName)
        类型：\(selectedFilePreview.fileType)
        大小：\(selectedFilePreview.totalBytes) bytes
        已读取：\(selectedFilePreview.bytesRead) bytes
        文本字符：\(selectedFilePreview.characterCount)
        状态：\(status)
        内容：
        \(selectedFilePreview.preview)
        [/文件上下文]
        """
    }

    private func recordRun(
        runId: String,
        startedAt: Int64,
        status: String,
        inputDigest: String,
        conversationId: String?
    ) async {
        let dao = db.agentRuntimeDao()

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // A "running" record is the up-front, not-yet-finished row written at
        // start, so an interrupted mid-stream run is detectable by the recovery
        // sweep; it carries no finishedAt. Terminal statuses finish the run and,
        // because insertRun is OnConflict.REPLACE, overwrite the running row.
        let finishedAtValue: KotlinLong? = status == "running" ? nil : KotlinLong(value: now)
        let interruptedReason: String? = status == "interrupted" ? "user_cancelled" : nil

        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: conversationId,
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: status,
            inputDigest: inputDigest,
            inputSnapshotRef: nil,
            inputSchemaVersion: 1,
            startedAt: startedAt,
            finishedAt: finishedAtValue,
            interruptedReason: interruptedReason
        )

        do {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                dao.insertRun(run: run) { error in
                    if let error { cont.resume(throwing: error) }
                    else { cont.resume() }
                }
            }
        } catch {
            // agent_run 是强杀恢复（applyToolCallLedgerRecovery）依赖的账本，
            // 写失败必须走用户可见错误通道，不能只 print 静默吞掉。
            let detail = "未能写入运行账本：\(error)"
            if let conversationStore {
                conversationStore.publishUserVisibleError(
                    IOSUserVisibleError(title: "运行状态记录失败", message: detail, severity: .error)
                )
            } else {
                chatLedgerLogger.error("\(detail)")
            }
        }
    }

    private func markRunAwaitingPermission(runId: String, toolCallId: String) async -> Bool {
        let updated = await withCheckedContinuation { (continuation: CheckedContinuation<Int, Never>) in
            db.agentRuntimeDao().markAwaitingPermission(
                runId: runId,
                inputSnapshotRef: "tool_call:\(toolCallId)"
            ) { count, _ in
                continuation.resume(returning: Int(count?.intValue ?? 0))
            }
        }
        return updated == 1
    }

    /// Resolve the provider for the current chat model, Android-style:
    ///   current chat model  ->  model.findProvider(settings.providers)
    /// The resolved ProviderSetting carries its own apiKey/baseUrl (the key now
    /// lives inside the provider, not in an iOS per-provider Keychain slot).
    /// Returns nil when the current model can't be resolved to a provider
    /// (e.g. fresh install with no configured provider/model) — the caller
    /// surfaces an honest configuration issue rather than faking an OpenAI one.
    private func makeProviderSetting() -> ProviderSetting? {
        guard let currentModel else { return nil }
        guard let provider = ChatProviderConfiguration.provider(
            for: currentModel,
            providers: sharedSettings.snapshot.providers
        ), ChatProviderConfiguration.issue(for: currentModel, provider: provider) == nil else { return nil }
        return provider
    }

    private func makeTextGenerationParams() -> TextGenerationParams {
        let modelId = currentModelId
        let modelAbilities = currentModelAbilities
        let searchEnabled = sharedSettings.snapshot.enableWebSearch
        // 图片生成现在挂载在「指定的生图模型」上(辅助任务 → 生图模型 / imageGenerationModelId),
        // 不再依赖独立的图片生成配置页。模型能解析到且其 provider 有有效 apiKey/baseURL 才挂工具。
        let imageGenerationConfigured: Bool = {
            let snap = sharedSettings.snapshot
            guard let model = snap.findModelById(uuid: snap.imageGenerationModelId),
                  let provider = ChatProviderConfiguration.provider(for: model, providers: snap.providers) else {
                return false
            }
            // Codex image generation uses the OAuth bearer (no apiKey); gate on
            // sign-in instead. Other providers require a real apiKey + baseURL.
            if IOSCodexProviderResolver.isCodexProvider(provider) {
                return IOSCodexProviderResolver.isSignedIn(provider)
            }
            let key = ChatProviderConfiguration.apiKey(of: provider).trimmingCharacters(in: .whitespacesAndNewlines)
            let base = ChatProviderConfiguration.baseURL(of: provider).trimmingCharacters(in: .whitespacesAndNewlines)
            return !key.isEmpty && !base.isEmpty
        }()
        var builtInTools: [BuiltInTools] = []
        if searchEnabled { builtInTools.append(BuiltInTools.Search.shared) }

        // Real Android parity: read generation params from the current Assistant
        // + Model instead of hardcoding temperature=0.7/topP=nil/maxTokens=nil.
        // Mirrors GenerationHandler.kt:453-468 + resolveSessionDefaults.
        let snapshot = sharedSettings.snapshot
        let assistant = snapshot.getCurrentAssistant()
        let currentModel = snapshot.getCurrentChatModel()
        let contextWindow = currentModel?.contextWindowTokens
        // resolveSessionDefaults handles: reasoningLevel AUTO→model default,
        // contextMessageSize 0→group default, maxTokens→group default fallback.
        // It is a Settings extension fun; the fallback model avoids a nil model.
        // Model requires its full initializer (Kotlin default args don't bridge).
        let resolvedModel = currentModel ?? Model(
            modelId: modelId,
            displayName: modelId,
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        let resolved = snapshot.resolveSessionDefaults(assistant: assistant, model: resolvedModel)

        // Merge custom headers/bodies: Assistant's + Model's (Android merges both).
        let mergedHeaders: [CustomHeader] = assistant.customHeaders + (currentModel?.customHeaders ?? [])
        let mergedBodies: [CustomBody] = assistant.customBodies + (currentModel?.customBodies ?? [])

        let model = Model(
            modelId: modelId,
            displayName: currentModel?.displayName ?? modelId,
            id: currentModel?.id ?? KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: mergedHeaders,
            customBodies: mergedBodies,
            inputModalities: [],
            outputModalities: [],
            abilities: modelAbilities,
            tools: Set(builtInTools),
            contextWindowTokens: contextWindow,
            providerOverwrite: currentModel?.providerOverwrite
        )
        // Tool declarations: iOS search/scrape, memory, WebMount, MCP,
        // sub-agent dispatch, and model-council run. The model decides which to
        // call; the iOS onComplete dispatch routes each to its executor.
        var toolDeclarations: [Tool] = []
        if searchEnabled {
            toolDeclarations.append(ToolKt.createSearchWebToolDeclaration())
            toolDeclarations.append(ToolKt.createScrapeWebToolDeclaration())
        }
        if IOSMemoryToolExecutor.isEnabled(runtime: sharedSettings.agentRuntime) {
            toolDeclarations.append(ToolKt.createMemoryToolDeclaration())
        }
        if imageGenerationConfigured {
            toolDeclarations.append(ToolKt.createImageGenToolDeclaration())
        }
        if localToolExecutor != nil {
            toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(
                names: workspaceToolNamesForCurrentTurn()
            ))
        }
        if localToolExecutor != nil {
            toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(
                names: ishToolNamesForCurrentTurn()
            ))
        }
        if localToolExecutor != nil, isWebMountRuntimeEnabled {
            toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(
                names: Array(IOSWebMountToolCatalog.supportedToolNames).sorted()
            ))
        }
        toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(
            names: Array(IOSSkillToolCatalog.toolNames).sorted()
        ))
        // Capability gates are always-on on iOS; declare management + call together.
        toolDeclarations.append(ToolKt.createMcpCallToolDeclaration())
        toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(
            names: Array(IOSMcpManagementToolCatalog.toolNames).sorted()
        ))
        // P0-b: flatten discovered MCP tools into `mcp__{server}__{tool}`
        // declarations. They are appended to the BRIDGE's input (never to the
        // final params — the bridge defers them behind tool_search; mcp_call
        // stays as the always-on passthrough). MCP off / no discovered tools
        // contributes nothing, so the P0-a baseline is unchanged.
        toolDeclarations.append(contentsOf: expandedMcpToolDeclarations(mcpManager: mcpManager))
        // P1-c/P1-d: 线程编排六工具声明。非常驻——照 mcp__* 展开先例只进 bridge
        // 全目录（tool_search 命中后才进 params.tools），不加新设置项。
        toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(
            names: ["spawn_agent", "list_agents", "interrupt_agent", "send_message", "followup_task", "wait_agent"]
        ))
        // 跨会话读取工具声明（session_search/session_read）。非常驻——照线程编排
        // 先例只进 bridge 全目录（tool_search 命中后才进 params.tools），无设置项。
        toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(
            names: ["session_search", "session_read"]
        ))
        // M5: discovery 引导（toolSearchDiscoveryGuidance）教模型用 tools_list
        // 识别精确工具名——iOS 主目录声明它（常驻；KMP IOS_RESIDENT_TOOL_NAMES
        // 与 DISCOVERY_UTILITY_TOOLS 已把它列为 resident），执行在桥本地。
        toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(names: ["tools_list"]))
        // P3-a: exec 纯求值工具（JavaScriptCore 沙箱，无 tools 桥）。默认关——
        // 仅在开关开时进桥输入全目录（非常驻 deferred 池，tool_search 命中后才
        // 可见可调）；关时零痕迹（声明与执行路径都不存在，模型调用走未知名
        // 硬失败语义）。
        // P3-c: wait 与 exec 同开关（cell 生命周期续取，无独立设置项）。
        if settingsStore.execJavaScriptEnabled {
            toolDeclarations.append(ToolKt.createExecToolDeclaration())
            toolDeclarations.append(ToolKt.createWaitToolDeclaration())
        }
        if isSlice3ToolEnabled("subagent_dispatch") {
            toolDeclarations.append(ToolKt.createSubAgentDispatchToolDeclaration())
        }
        if isSlice3ToolEnabled("model_council_run") {
            toolDeclarations.append(ToolKt.createModelCouncilRunToolDeclaration())
        }
        // Standard chat can pause for a focused user decision, same tool surface as novel discussion.
        toolDeclarations.append(ToolKt.createAskUserToolDeclaration())
        // Wave B1 (§13.2.4 run-start seam, §16.1): include recipe declarations
        // from the dynamic registry's CURRENT snapshot. Every recipe is
        // default-deferred — it enters the bridge's full catalog
        // (tool_search-searchable) but never the visible set. Descriptors and
        // search info come from ONE snapshot object, so declaration and
        // execution availability cannot diverge by revision. No snapshot
        // (store unreadable) → no recipe surface at all.
        var recipeSearchInfo: [String: String] = [:]
        if let dynamicCatalog = IOSDynamicToolRegistry.shared.currentSnapshot,
           !dynamicCatalog.recipeTools.isEmpty {
            toolDeclarations.append(contentsOf: dynamicCatalog.recipeDeclarations())
            recipeSearchInfo = dynamicCatalog.searchInfoByName
        }
        // P0-a tool discovery: wrap the full static declarations in the shared
        // KMP exposure bridge and surface only the first-round visible subset
        // (iOS resident tools + tool_search; everything deferred — wm_*, iSH,
        // skill management, … — is exposed on demand via tool_search). The
        // bridge instance is handed to the run coordinator, which owns it for
        // the whole run so hits become callable on the NEXT round.
        let exposureBridge = IosToolExposureBridge(tools: toolDeclarations, recipeSearchInfo: recipeSearchInfo)
        lastAssembledToolExposureBridge = exposureBridge
        // Real params: temperature/topP from Assistant, maxTokens from
        // resolveSessionDefaults (Assistant → group default), reasoningLevel
        // resolved, custom headers/bodies merged. Mirrors GenerationHandler.
        let resolvedReasoningLevel = modelAbilities.contains(.reasoning) ? resolved.reasoningLevel : ReasoningLevel.off
        return TextGenerationParams(
            model: model,
            temperature: assistant.temperature.map { KotlinFloat(value: Float(truncating: $0)) },
            topP: assistant.topP.map { KotlinFloat(value: Float(truncating: $0)) },
            maxTokens: resolved.maxTokens.map { KotlinInt(value: Int32(truncating: $0)) },
            tools: exposureBridge.visibleTools(),
            reasoningLevel: resolvedReasoningLevel,
            customHeaders: mergedHeaders,
            customBody: mergedBodies
        )
    }

    func currentToolDeclarationNames() -> [String] {
        makeTextGenerationParams().tools.map(\.name)
    }

    private func workspaceToolNamesForCurrentTurn() -> [String] {
        Array(IOSWorkspaceToolCatalog.supportedToolNames).sorted()
    }

    private func ishToolNamesForCurrentTurn() -> [String] {
        enabledModelToolNames(
            IOSEmbeddedIshToolCatalog.supportedToolNames.union(IOSIshToolCatalog.supportedToolNames)
        )
    }

    #if DEBUG
    static func auxiliaryTextGenerationParamsForTesting(
        model: Model,
        assistantHeaders: [CustomHeader] = [],
        assistantBodies: [CustomBody] = []
    ) -> TextGenerationParams {
        makeAuxiliaryTextGenerationParams(
            model: model,
            assistantHeaders: assistantHeaders,
            assistantBodies: assistantBodies
        )
    }

    func beginSuggestionRequestForTesting() -> UUID {
        beginSuggestionRequest()
    }

    func applySuggestionsForTesting(
        _ suggestions: [String],
        requestToken: UUID,
        conversationId: KotlinUuid?
    ) {
        applySuggestions(
            suggestions,
            requestToken: requestToken,
            conversationId: conversationId
        )
    }

    /// Test accessor for the resolved generation params (reads real
    /// Assistant/Model values + resolveSessionDefaults).
    func textGenerationParamsForTesting() -> TextGenerationParams {
        makeTextGenerationParams()
    }

    /// P0-a: the run bridge built by the latest assembly (nil until
    /// makeTextGenerationParams ran). Test accessor for the exposure contract.
    func toolExposureBridgeForTesting() -> IosToolExposureBridge? {
        lastAssembledToolExposureBridge
    }

    func persistPendingAssistantRegenerationForTesting(
        conversationId: KotlinUuid,
        targetMessageIndex: Int,
        generatedMessageIndex: Int,
        snapshot: [UIMessage]
    ) async -> Bool {
        guard let store = conversationStore else { return false }
        let pending = PendingAssistantRegeneration(
            conversationId: conversationId,
            targetMessageIndex: targetMessageIndex,
            generatedMessageIndex: generatedMessageIndex
        )
        pendingAssistantRegeneration = pending
        messages = snapshot
        return await persistMessagesSnapshot(
            snapshot,
            targetConversationId: conversationId,
            pendingRegeneration: pending,
            store: store,
            writeBaseline: store.writeBaseline(for: conversationId)
        )
    }
    #endif

    static func userFacingGenerationError(_ rawMessage: String, modelId: String? = nil) -> String {
        let raw = rawMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowercased = raw.lowercased()
        let prefix: String
        if lowercased.contains("invalid api key") ||
            lowercased.contains("incorrect api key") ||
            lowercased.contains("unauthorized") ||
            lowercased.contains("401") {
            prefix = "API Key 无效或没有权限。请回到「服务商」确认 Key 是否填写正确，并确认它有访问当前服务商的权限。"
        } else if lowercased.contains("model_not_found") ||
                    lowercased.contains("model not found") ||
                    lowercased.contains("does not exist") ||
                    lowercased.contains("not found") ||
                    lowercased.contains("404") {
            let trimmedModelId = modelId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let suffix = trimmedModelId.isEmpty ? "" : "当前 Model ID：\(trimmedModelId)。"
            prefix = "模型不可用、模型不存在，或当前 Base URL 不支持这个聊天路径。请在「默认模型」选择当前服务商支持的模型。\(suffix)"
        } else if lowercased.contains("network") ||
                    lowercased.contains("internet") ||
                    lowercased.contains("offline") ||
                    lowercased.contains("timed out") ||
                    lowercased.contains("timeout") ||
                    lowercased.contains("cannot connect") ||
                    lowercased.contains("could not connect") ||
                    lowercased.contains("dns") ||
                    lowercased.contains("connection refused") ||
                    lowercased.contains("nsurlerror") {
            prefix = "网络连接失败。请检查网络、Base URL 和服务商状态后重试。"
        } else {
            prefix = "请求失败。请检查服务商配置后重试。"
        }

        guard !raw.isEmpty else { return prefix }
        return "\(prefix)\n\n原始错误：\(truncatedError(raw))"
    }

    private static func truncatedError(_ value: String, maxLength: Int = 700) -> String {
        guard value.count > maxLength else { return value }
        return String(value.prefix(maxLength)) + "..."
    }

    private var isMiniAppRuntimeEnabled: Bool {
        sharedSettings.agentRuntime.miniApp.enabled
    }

    private var isWebMountRuntimeEnabled: Bool {
        true
    }

    private func isSlice3ToolEnabled(_ toolName: String) -> Bool {
        switch toolName {
        case "mcp_call", "mcp_list", "mcp_test", "mcp_import_from_skill",
             "skills_list", "use_skill", "skill_validate", "skill_import", "skill_enable", "skill_disable":
            true
        case "subagent_dispatch":
            isCapabilityPolicyEnabled("ios.agent.subagent_dispatch")
        case "model_council_run":
            isCapabilityPolicyEnabled("ios.agent.model_council_run")
        default:
            false
        }
    }

    private func isCapabilityPolicyEnabled(_ capabilityId: String) -> Bool {
        guard let localToolExecutor else { return true }
        let snapshot = localToolExecutor.permissionsStatus()
        return snapshot.capabilities.first { $0.id == capabilityId }?.policy != IOSAgentPermissionPolicy.disabled.title
    }

    private func enabledModelToolNames(_ names: Set<String>) -> [String] {
        guard let localToolExecutor else { return Array(names).sorted() }
        let snapshot = localToolExecutor.permissionsStatus()
        return names.filter { toolName in
            guard let capability = snapshot.capabilities.first(where: { $0.modelToolNames.contains(toolName) }) else {
                return false
            }
            return capability.policy != IOSAgentPermissionPolicy.disabled.title
        }
        .sorted()
    }

}
