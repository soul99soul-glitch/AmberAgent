import CryptoKit
import Foundation
@preconcurrency import Shared
import UIKit

func chatInputDigest(for text: String) -> String {
    let hash = SHA256.hash(data: Data(text.utf8))
    return hash.map { String(format: "%02x", $0) }.joined()
}

func chatNowLocalDateTime() -> Kotlinx_datetimeLocalDateTime {
    let now = Date()
    let cal = Calendar.current
    return Kotlinx_datetimeLocalDateTime(
        year: Int32(cal.component(.year, from: now)),
        month: Int32(cal.component(.month, from: now)),
        day: Int32(cal.component(.day, from: now)),
        hour: Int32(cal.component(.hour, from: now)),
        minute: Int32(cal.component(.minute, from: now)),
        second: Int32(cal.component(.second, from: now)),
        nanosecond: Int32(cal.component(.nanosecond, from: now))
    )
}

private final class StreamJobBox {
    var job: Kotlinx_coroutines_coreJob?

    deinit {
        job?.cancel(cause: nil)
    }
}

final class ChatStreamEvent: @unchecked Sendable {
    enum Payload {
        case chunk(MessageChunk)
        case complete
        case error(KotlinThrowable)
    }

    let payload: Payload

    private init(_ payload: Payload) {
        self.payload = payload
    }

    static func chunk(_ chunk: MessageChunk) -> ChatStreamEvent {
        ChatStreamEvent(.chunk(chunk))
    }

    static func complete() -> ChatStreamEvent {
        ChatStreamEvent(.complete)
    }

    static func error(_ error: KotlinThrowable) -> ChatStreamEvent {
        ChatStreamEvent(.error(error))
    }
}

final class ChatStreamEventSink: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: AsyncStream<ChatStreamEvent>.Continuation?
    private var pendingEvents: [ChatStreamEvent] = []
    private var pendingEventHead = 0
    private var isFinished = false

    func bind(_ continuation: AsyncStream<ChatStreamEvent>.Continuation) {
        lock.lock()
        guard !isFinished else {
            lock.unlock()
            continuation.finish()
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    func yield(_ event: ChatStreamEvent) {
        lock.lock()
        guard !isFinished, let continuation else {
            lock.unlock()
            return
        }
        pendingEvents.append(event)
        // `yield` 也放在锁内，确保不同 provider 回调线程看到同一 FIFO 次序。
        continuation.yield(event)
        lock.unlock()
    }

    func claim(_ event: ChatStreamEvent) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard pendingEventHead < pendingEvents.count,
              pendingEvents[pendingEventHead] === event else {
            return false
        }
        pendingEventHead += 1
        compactClaimedPrefixIfNeeded()
        return true
    }

    func takePendingChunks() -> [MessageChunk] {
        lock.lock()
        defer { lock.unlock() }
        var chunks: [MessageChunk] = []
        var retained: [ChatStreamEvent] = []
        if pendingEventHead < pendingEvents.count {
            retained.reserveCapacity(pendingEvents.count - pendingEventHead)
            for event in pendingEvents[pendingEventHead...] {
                if case .chunk(let chunk) = event.payload {
                    chunks.append(chunk)
                } else {
                    retained.append(event)
                }
            }
        }
        pendingEvents = retained
        pendingEventHead = 0
        return chunks
    }

    func finish() {
        lock.lock()
        let continuation = continuation
        self.continuation = nil
        isFinished = true
        lock.unlock()
        continuation?.finish()
    }

    /// Atomically chooses background ownership against a racing provider terminal callback.
    /// Accepted chunks remain drainable; a queued complete/error keeps foreground ownership.
    @MainActor
    func transitionToBackgroundIfNoTerminal(_ startBackground: () -> Bool) -> Bool {
        lock.lock()
        guard !isFinished,
              !pendingEvents[pendingEventHead...].contains(where: { event in
                switch event.payload {
                case .complete, .error:
                    return true
                case .chunk:
                    return false
                }
              }) else {
            lock.unlock()
            return false
        }
        let didStart = startBackground()
        let continuation = didStart ? continuation : nil
        if didStart {
            self.continuation = nil
            isFinished = true
        }
        lock.unlock()
        continuation?.finish()
        return didStart
    }

    private func compactClaimedPrefixIfNeeded() {
        guard pendingEventHead >= 64,
              pendingEventHead * 2 >= pendingEvents.count else { return }
        pendingEvents.removeFirst(pendingEventHead)
        pendingEventHead = 0
    }

#if DEBUG
    var pendingEventCountForTesting: Int {
        lock.lock()
        defer { lock.unlock() }
        return pendingEvents.count - pendingEventHead
    }
#endif
}

@MainActor
private final class ChatStreamAccumulatorSession {
    let accumulator: MessageStreamAccumulator
    let eventSink: ChatStreamEventSink
    var detectedToolCallIds = Set<String>()
    /// 本轮是否有 chunk 报告了输出上限 finish_reason。累加器只保留 delta/message/usage,
    /// 不透传 finishReason,所以必须在消费 chunk 的当下记录下来。
    var hitOutputLimit = false

    init(accumulator: MessageStreamAccumulator, eventSink: ChatStreamEventSink) {
        self.accumulator = accumulator
        self.eventSink = eventSink
    }
}

struct ChatStreamPresentationStep {
    let snapshot: [UIMessage]
    let isCaughtUp: Bool
}

/// 流式「呈现节奏」策略:每拍该推进多少字符。Chat 与小说创作共用同一份口径。
///
/// 此前 `ChatStreamPresentationPacer` 与 `NovelSessionPresentationPacer` 各持一份
/// 逐字相同的常量与公式,注释里互相声明"同构"却没有机制保证——调一边不会让另一边
/// 变红。真正共享的只有这段策略:两边的 `step` 吃的是不同的数据形状
/// (Chat 是 `[UIMessage]` 的 part 列表,小说是单条 String),强行抽象反而更糟。
enum StreamPresentationPacingPolicy {
    /// 轻积压时的下限:一拍推进不到一行手机宽度的中文,保留既有 48ms 发布时钟。
    static let minimumTextAdvance = 12
    /// 每拍硬上限:约一到两行中文。64 字会在手机宽度下一次放出约三行，
    /// TextKit 高度与底部跟随只能在下一帧追上，表现为偶发的大幅跳变。
    static let maximumTextAdvance = 36
    /// 尽量在这么多拍内清空*当前*积压。
    static let preferredDrainTicks = 16

    /// 按积压自适应的每拍推进量。
    ///
    /// 固定 12 字符/拍意味着显示速率恒为 250 字符/秒。模型快于这个速率时
    /// 积压会持续累积,且终态排空仍按同一节奏逐拍追平——4000 字的回复要 334 拍
    /// (≈16s)才显示完,期间 `isLoading` 保持 true,用户看着"停止"按钮等一段
    /// 早已生成完的文本。
    static func textAdvance(backlogCount: Int) -> Int {
        guard backlogCount > 0 else { return 0 }
        let adaptive = (backlogCount + preferredDrainTicks - 1) / preferredDrainTicks
        return min(maximumTextAdvance, max(minimumTextAdvance, adaptive))
    }
}

enum ChatStreamPresentationPacer {
    static var minimumTextAdvance: Int { StreamPresentationPacingPolicy.minimumTextAdvance }
    static var maximumTextAdvance: Int { StreamPresentationPacingPolicy.maximumTextAdvance }
    static var preferredDrainTicks: Int { StreamPresentationPacingPolicy.preferredDrainTicks }

    static func textAdvance(backlogCount: Int) -> Int {
        StreamPresentationPacingPolicy.textAdvance(backlogCount: backlogCount)
    }

    /// 本轮所有 text part 的未显示字符总量,用来定这一拍的推进预算。
    /// 只比长度、不做前缀校验:前缀不匹配的情况由 `step` 的主循环兜底
    /// (直接发布权威全文),此处至多把预算算大一拍,不影响正确性。
    private static func pendingTextBacklog(
        currentParts: [UIMessagePart],
        targetParts: [UIMessagePart]
    ) -> Int {
        var backlog = 0
        for (index, targetPart) in targetParts.enumerated() {
            guard let targetText = targetPart as? UIMessagePart.Text else { continue }
            let currentCount: Int
            if index < currentParts.count,
               let currentText = currentParts[index] as? UIMessagePart.Text {
                currentCount = currentText.text.count
            } else {
                currentCount = 0
            }
            backlog += max(0, targetText.text.count - currentCount)
        }
        return backlog
    }

    static func step(current: [UIMessage], target: [UIMessage]) -> ChatStreamPresentationStep {
        guard let targetAssistant = target.last,
              targetAssistant.role == MessageRole.assistant else {
            return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
        }

        let currentAssistant: UIMessage?
        let currentPrefix: ArraySlice<UIMessage>
        if current.count == target.count,
           let last = current.last,
           last.role == MessageRole.assistant,
           last.id == targetAssistant.id {
            currentAssistant = last
            currentPrefix = current.dropLast()
        } else if current.count + 1 == target.count {
            currentAssistant = nil
            currentPrefix = current[...]
        } else {
            return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
        }

        let targetPrefix = target.dropLast()
        guard currentPrefix.count == targetPrefix.count,
              zip(currentPrefix, targetPrefix).allSatisfy({ $0.id == $1.id }) else {
            return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
        }

        let currentParts = currentAssistant?.parts ?? []
        guard currentParts.count <= targetAssistant.parts.count else {
            return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
        }

        var remainingBudget = textAdvance(
            backlogCount: pendingTextBacklog(
                currentParts: currentParts,
                targetParts: targetAssistant.parts
            )
        )
        var caughtUp = true
        var pacedParts: [UIMessagePart] = []
        pacedParts.reserveCapacity(targetAssistant.parts.count)

        for (index, targetPart) in targetAssistant.parts.enumerated() {
            guard let targetText = targetPart as? UIMessagePart.Text else {
                if index < currentParts.count,
                   currentParts[index] is UIMessagePart.Text {
                    return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
                }
                pacedParts.append(targetPart)
                continue
            }

            let currentText: String
            if index < currentParts.count {
                guard let text = currentParts[index] as? UIMessagePart.Text else {
                    return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
                }
                currentText = text.text
            } else {
                currentText = ""
            }
            guard targetText.text.hasPrefix(currentText) else {
                return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
            }

            let suffix = targetText.text.dropFirst(currentText.count)
            let advanceCount = min(remainingBudget, suffix.count)
            let pacedText = currentText + suffix.prefix(advanceCount)
            remainingBudget -= advanceCount
            if advanceCount < suffix.count {
                caughtUp = false
            }
            pacedParts.append(UIMessagePart.Text(text: String(pacedText), metadata: targetText.metadata))
        }

        let pacedAssistant = UIMessage(
            id: targetAssistant.id,
            role: targetAssistant.role,
            parts: pacedParts,
            annotations: targetAssistant.annotations,
            createdAt: targetAssistant.createdAt,
            finishedAt: caughtUp ? targetAssistant.finishedAt : currentAssistant?.finishedAt,
            modelId: targetAssistant.modelId,
            usage: caughtUp ? targetAssistant.usage : currentAssistant?.usage,
            translation: targetAssistant.translation
        )
        return ChatStreamPresentationStep(
            snapshot: Array(targetPrefix) + [pacedAssistant],
            isCaughtUp: caughtUp
        )
    }
}

struct ChatPendingToolApproval {
    let toolCall: UIMessagePart.Tool
    let providerSetting: ProviderSetting
    let params: TextGenerationParams
    let runId: String
    let startedAt: Int64
    let inputDigest: String
    let conversationId: KotlinUuid?
    let baseMessages: [UIMessage]
}

struct ChatGenerationDependencies {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    let localToolExecutor: IOSLocalToolExecutor?
    let searchTransport: any IOSSearchHTTPTransport
    let liveActivityController: AgentLiveActivityController
    let autoGenerateResponses: Bool
    let mcpManager: IOSMcpManager
}

struct ChatGenerationBindings {
    let getMessages: () -> [UIMessage]
    let setMessages: ([UIMessage]) -> Void
    let bumpMessageRevision: (ChatMessageUpdateReason) -> Void
    let shouldPaceStreamPresentation: () -> Bool
    let setIsLoading: (Bool) -> Void
    let setPendingMemoryApproval: (MemoryToolApprovalRequest?) -> Void
    let setPendingSearchApproval: (SearchToolApprovalRequest?) -> Void
    let setPendingWebMountApproval: (WebMountToolApprovalRequest?) -> Void
    let setPendingWorkspaceApproval: (WorkspaceToolApprovalRequest?) -> Void
    let setPendingIshHandoffApproval: (IshHandoffToolApprovalRequest?) -> Void
    let setPendingMcpApproval: (McpToolApprovalRequest?) -> Void
    let setPendingCouncilApproval: (CouncilToolApprovalRequest?) -> Void
    let setPendingAskUser: (ChatAskUserRequest?) -> Void
    let setContextCompactState: (ChatContextCompactState) -> Void
    let persistMessages: @MainActor (KotlinUuid?) async -> Bool
    let capturePersistMessagesBaseline: (KotlinUuid?) -> IOSConversationWriteBaseline?
    let persistMessagesSnapshot: @MainActor ([UIMessage], KotlinUuid?, IOSConversationWriteBaseline?) async -> Bool
    let recordRun: (String, Int64, String, String, String?) async -> Void
    var markRunAwaitingPermission: @MainActor (String, String) async -> Bool = { _, _ in true }
    let startLiveActivity: (String, KotlinUuid?, AgentActivityPresentation) -> Void
    let saveMiniAppIfPresent: ([UIMessage], KotlinUuid?) -> [UIMessage]?
    let messagesByInjectingRuntimeContext: ([UIMessage]) -> [UIMessage]
    let userFacingGenerationError: (String, String?) -> String
    var generationSucceeded: @MainActor () -> Void = {}
}

struct IOSGenerativeUiRequirement: Equatable {
    let required: Bool
    let expectSlides: Bool
    let expectFullHtmlDeck: Bool

    static let none = IOSGenerativeUiRequirement(
        required: false,
        expectSlides: false,
        expectFullHtmlDeck: false
    )

    init(required: Bool, expectSlides: Bool, expectFullHtmlDeck: Bool) {
        self.required = required
        self.expectSlides = expectSlides
        self.expectFullHtmlDeck = expectFullHtmlDeck
    }

    init(_ shared: GenerativeUiWidgetRequirement) {
        self.init(
            required: shared.required,
            expectSlides: shared.expectSlides,
            expectFullHtmlDeck: shared.expectFullHtmlDeck
        )
    }

    var sharedValue: GenerativeUiWidgetRequirement {
        GenerativeUiWidgetRequirement(
            required: required,
            expectSlides: expectSlides,
            expectFullHtmlDeck: expectFullHtmlDeck
        )
    }
}

struct IOSGenerativeUiRequestPlan {
    let params: TextGenerationParams
    let uploadMessages: [UIMessage]
    let requirement: IOSGenerativeUiRequirement
}

enum IOSGenerativeUiRequestPolicy {
    static func plan(
        setting: GenerativeUiSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) -> IOSGenerativeUiRequestPlan {
        let hasImageGenTool = params.tools.contains(where: { $0.name == "generate_image" })
        let sharedRequirement = GenerativeUiPlanner.shared.widgetRequirement(
            setting: setting,
            messages: messages
        )
        let shouldSuppressTools = GenerativeUiPlanner.shared.shouldGenerateDirectWidgetWithoutTools(
            setting: setting,
            messages: messages
        )
        let plannedParams = shouldSuppressTools ? copying(params, tools: [], reasoningLevel: params.reasoningLevel) : params
        let basePrompt = GenerativeUiPromptCatalog.shared.build(setting: setting, model: params.model)
        let routePrompt = GenerativeUiPlanner.shared.buildPrompt(
            setting: setting,
            messages: messages,
            hasImageGenTool: hasImageGenTool
        )
        let prompt = [basePrompt, routePrompt]
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .joined(separator: "\n")
        return IOSGenerativeUiRequestPlan(
            params: plannedParams,
            uploadMessages: prompt.isEmpty ? messages : [systemMessage(prompt)] + messages,
            requirement: IOSGenerativeUiRequirement(sharedRequirement)
        )
    }

    static func retryParams(_ params: TextGenerationParams) -> TextGenerationParams {
        copying(params, tools: [], reasoningLevel: .off)
    }

    static func retryMessages(
        _ messages: [UIMessage],
        requirement: IOSGenerativeUiRequirement,
        issue: String
    ) -> [UIMessage] {
        let prompt = GenerativeUiPromptCatalog.shared.buildRetry(
            requirement: requirement.sharedValue,
            previousIssue: issue
        )
        let repair = systemMessage(prompt)
        if messages.first?.role == MessageRole.system {
            return [messages[0], repair] + Array(messages.dropFirst())
        }
        return [repair] + messages
    }

    static func widgetIssue(
        in messages: [UIMessage],
        afterDisplayMessageCount baselineCount: Int,
        requirement: IOSGenerativeUiRequirement
    ) -> String? {
        guard requirement.required else { return nil }
        let start = min(max(baselineCount, 0), messages.count)
        let text = messages[start...]
            .reversed()
            .first(where: { $0.role == MessageRole.assistant })?
            .parts
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "\n") ?? ""
        let widgets = IOSGenerativeWidgetParser.parse(text, streaming: false).compactMap { segment -> IOSGenerativeWidget? in
            guard case .widget(let widget) = segment, widget.complete else { return nil }
            return widget
        }
        guard !widgets.isEmpty else { return "missing required complete show-widget" }
        if requirement.expectFullHtmlDeck,
           !widgets.contains(where: { $0.renderer == IOSGuizangHtmlDeckValidator.renderer }) {
            return "expected renderer \"\(IOSGuizangHtmlDeckValidator.renderer)\""
        }
        if requirement.expectSlides,
           !widgets.contains(where: {
               $0.renderer == "slides" || $0.renderer == IOSGuizangHtmlDeckValidator.renderer
           }) {
            return "expected a slides or full_html deck widget"
        }
        return nil
    }

    private static func copying(
        _ params: TextGenerationParams,
        tools: [Tool],
        reasoningLevel: ReasoningLevel
    ) -> TextGenerationParams {
        TextGenerationParams(
            model: params.model,
            temperature: params.temperature,
            topP: params.topP,
            maxTokens: params.maxTokens,
            tools: tools,
            reasoningLevel: reasoningLevel,
            customHeaders: params.customHeaders,
            customBody: params.customBody
        )
    }

    private static func systemMessage(_ prompt: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.system,
            parts: [UIMessagePart.Text(text: prompt, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }
}

@MainActor
final class ChatGenerationCoordinator {
    private let dependencies: ChatGenerationDependencies
    private let bindings: ChatGenerationBindings
    private lazy var provider = OpenAIKmpProvider()
    private lazy var claudeProvider = ClaudeKmpProvider()
    private var grokWebStreamTask: Task<Void, Never>?
    private let streamJobBox = StreamJobBox()
    private lazy var toolRuntime = ChatToolRuntime(
        settingsStore: dependencies.settingsStore,
        sharedSettings: dependencies.sharedSettings,
        localToolExecutor: dependencies.localToolExecutor,
        searchTransport: dependencies.searchTransport,
        mcpManager: dependencies.mcpManager
    )
    // W1 durable ledger (I-1): Started/Finished bookkeeping for every tool
    // execution on the foreground path, in the shared `agent_event` table.
    private lazy var toolLedger: IOSAgentRunLedgering = IOSAgentRunLedger()

    private var streamJob: Kotlinx_coroutines_coreJob? {
        get { streamJobBox.job }
        set { streamJobBox.job = newValue }
    }

    private var currentRunId: String?
    private var currentStartedAt: Int64?
    private var currentInputDigest: String?
    private var currentConversationIdForRun: KotlinUuid?
    private var currentToolResumeCount = 0
    private var currentGenerativeUiRequirement: IOSGenerativeUiRequirement = .none
    private var currentGenerativeUiFallbackAttempted = false
    /// I-4 冻结快照(`ChatRunSnapshot`):与 `currentRunId` 一起设置、一起清空,
    /// run 内的压缩配置只从这里读,不再每轮 live 读 `dependencies.sharedSettings.snapshot`。
    private var currentRunSnapshot: ChatRunSnapshot?
    /// I-5 打转守护(`IOSToolLoopGuard`):与 `currentRunSnapshot` 同处赋值/清理,
    /// 生命周期等于一个 run——审批续跑属于同一 run,不重置;run 结束/取消/交接
    /// 后台时清空,避免下一个 run 继承上一个 run 的重复计数。
    private var currentToolLoopGuard = IOSToolLoopGuard()
    private var currentLiveActivityStage: AgentActivityStage?
    private let maxToolResumeCount = 4
    private var pendingStreamSnapshot: [UIMessage]?
    private var pendingStreamSnapshotProvider: (() -> [UIMessage])?
    private var streamSnapshotFlushTask: Task<Void, Never>?
    private var streamEventTask: Task<Void, Never>?
    private var streamEventSink: ChatStreamEventSink?
    private var activeStreamSession: ChatStreamAccumulatorSession?
    /// 流式 UI 快照的发布间隔。每次发布都会让整个消息列表子树跑一轮 SwiftUI
    /// 事务(采样实测:子树全部短路后,事务图遍历本身仍是长内容流式的主线程
    /// 地板)。因此 48ms(≈20Hz)把事务频率从 60Hz 降到 20Hz；它不是屏幕或
    /// 动画帧率。普通小 delta 原样发布，超过一行量级的 provider burst 则在
    /// 同一时钟上分帧追上。cancel/error/handoff 始终从 accumulator 取权威快照；
    /// complete 只延后可见终态，直到分帧追上，数据完整性不受影响。
    private let streamSnapshotFlushDelayNanos: UInt64 = 48_000_000
    private var pendingMemoryToolApproval: ChatPendingToolApproval?
    private var pendingSearchToolApproval: ChatPendingToolApproval?
    private var pendingWebMountToolApproval: ChatPendingToolApproval?
    private var pendingWorkspaceToolApproval: ChatPendingToolApproval?
    private var pendingIshHandoffToolApproval: ChatPendingToolApproval?
    private var pendingMcpToolApproval: ChatPendingToolApproval?
    private var pendingCouncilToolApproval: ChatPendingToolApproval?
    private var pendingAskUserToolApproval: ChatPendingToolApproval?
    /// F8 fix: I-5's "proceed and remind" verdict is computed in
    /// `executeToolCall` at the moment a tool call is ABOUT to be dispatched
    /// for approval — before the user has answered. If the reminder is
    /// dropped right there (as it used to be: the `.waitingForApproval`
    /// branch discarded `loopGuardVerdict` entirely), all 8 approval-gated
    /// tools degrade from "warn on the 2nd repeat, hard-stop on the 3rd" to
    /// "hard-stop on the 3rd with no warning ever shown" — the model never
    /// sees the reminder that would have let it self-correct before hitting
    /// the stop. Keyed by toolCallId so it survives the wait-for-user gap;
    /// consumed (and always removed, allow or deny) at each of the 8
    /// approval-finishing call sites via `consumingPendingLoopReminder`.
    private var pendingLoopReminders: [String: String] = [:]
    private var backgroundHandoff: IOSChatBackgroundHandoff?
    private weak var pendingBackgroundConversationStore: IOSConversationStore?
    private var foregroundToolExecutionTask: Task<ChatToolRuntimeResult, Never>?
    private var foregroundToolExecutionToken: UUID?
    private var foregroundApprovedToolContinuation: CheckedContinuation<ChatToolRuntimeResult?, Never>?
    private var foregroundImageToolExecutionTask: Task<[UIMessage], Never>?
    private var foregroundImageToolExecutionToken: UUID?

    var isRunning: Bool {
        currentRunId != nil
    }

    var activeConversationId: KotlinUuid? {
        currentConversationIdForRun
    }

    func hasPendingApproval(runId: String) -> Bool {
        currentRunId == runId && hasPendingToolApproval
    }

    /// KeepAlive 短腿到期后的唯一收口：交接失败时立即走取消的持久化终态，
    /// 不能留下仍在运行但已没有任何后台 owner 的 currentRunId。
    @discardableResult
    private func handleKeepAliveExpiration(
        runId: String,
        handoff: () -> Bool
    ) -> Bool {
        guard currentRunId == runId,
              UIApplication.shared.applicationState != .active else {
            return false
        }
        let didHandoff = handoff()
        return finishKeepAliveExpiration(runId: runId, didHandoff: didHandoff)
    }

    @discardableResult
    private func finishKeepAliveExpiration(runId: String, didHandoff: Bool) -> Bool {
        // 成功交接会在返回前清空前台 currentRunId；此时不能再用前台 owner
        // 判定交接结果，否则会把真实成功误报为 false。
        guard !didHandoff else { return true }
        guard currentRunId == runId else { return false }
        // KeepAlive 在回调前已经摘掉短腿租约；失败时取消是这一轮唯一剩下的
        // owner，沿用已有 cancel() 持久化 partial/终态，不引入重试状态机。
        _ = cancel(runId: runId)
        return false
    }

    private var hasPendingToolApproval: Bool {
        pendingMemoryToolApproval != nil ||
            pendingSearchToolApproval != nil ||
            pendingWebMountToolApproval != nil ||
            pendingWorkspaceToolApproval != nil ||
            pendingIshHandoffToolApproval != nil ||
            pendingMcpToolApproval != nil ||
            pendingCouncilToolApproval != nil ||
            pendingAskUserToolApproval != nil
    }

    init(
        dependencies: ChatGenerationDependencies,
        bindings: ChatGenerationBindings
    ) {
        self.dependencies = dependencies
        self.bindings = bindings
    }

    func start(
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        inputDigest: String,
        conversationId: KotlinUuid?,
        uploadMessages: [UIMessage]
    ) {
        if isRunning {
            cancel()
        }
        bindings.setIsLoading(true)
        bindings.setContextCompactState(.idle)

        let runId = UUID().uuidString
        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)
        currentRunId = runId
        currentRunSnapshot = ChatRunSnapshot(runId: runId, settings: dependencies.sharedSettings.snapshot)
        currentToolLoopGuard = IOSToolLoopGuard()
        currentStartedAt = startedAt
        currentInputDigest = inputDigest
        currentConversationIdForRun = conversationId
        currentToolResumeCount = 0
        currentGenerativeUiRequirement = .none
        currentGenerativeUiFallbackAttempted = false
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        let initialPresentation = AgentActivityPresentation.response(
            stage: AgentActivityResponseStagePolicy.initialStage
        )
        currentLiveActivityStage = initialPresentation.stage
        bindings.startLiveActivity(
            runId,
            conversationId,
            initialPresentation
        )
        // 生成一开始就拿后台执行权，而不是等切后台再抢——那时进程已经在被挂起了。
        // 执行权在手期间流自己跑；短窗口在系统接管前到期才走后台交接。
        // 系统进度卡被取消或终止则收口当前 run，不允许借交接反向重启。
        BackgroundGenerationKeepAlive.shared.begin(
            runId,
            title: "Amber 正在生成",
            subtitle: params.model.displayName,
            onExpire: { [weak self] in
                guard let self, self.currentRunId == runId else { return }
                // 只有短窗口在系统任务接管前到期才交接；系统进度卡的取消由
                // 专用回调收口为当前 run 的取消，不能反向重启生成。
                _ = self.handleKeepAliveExpiration(
                    runId: runId,
                    handoff: {
                        self.handoffCurrentGenerationToBackground(
                            conversationStore: self.pendingBackgroundConversationStore
                        )
                    }
                )
            },
            onSystemTaskExpiration: { [weak self] in
                self?.cancelRunAfterSystemKeepAliveExpiration(runId)
            }
        )
        BackgroundGenerationKeepAlive.shared.updateProgress(
            runId,
            completed: 0,
            total: 4,
            subtitle: "准备上下文"
        )

        Task { @MainActor [weak self] in
            guard let self else { return }
            // Persist a "running" run record up-front so an interrupted mid-stream
            // run is detectable (status=running) by IOSRunRecovery's startup sweep,
            // which marks it interrupted. The terminal recordRun (completion / cancel
            // / error) REPLACEs this row with the final status + finishedAt
            // (insertRun is OnConflictStrategy.REPLACE).
            await self.bindings.recordRun(runId, startedAt, "running", inputDigest, conversationId?.toHexDashString())
            if self.dependencies.sharedSettings.isCapabilityGateEnabled(.mcp) {
                await self.dependencies.mcpManager.syncAll()
            }
            guard self.currentRunId == runId else { return }
            // Codex OAuth providers carry no apiKey: resolve a valid OAuth access
            // token (refreshing if needed) and swap in a request-ready provider
            // (bearer + Responses API + codex backend). Non-codex providers pass
            // through unchanged. A resolution failure (not signed in / refresh
            // failed) surfaces as a normal generation error.
            let effectiveProvider: ProviderSetting
            do {
                effectiveProvider = try await IOSCodexProviderResolver.resolved(providerSetting)
            } catch {
                await self.presentStreamError(
                    rawMessage: (error as NSError).localizedDescription,
                    modelId: params.model.modelId,
                    runId: runId,
                    startedAt: startedAt,
                    inputDigest: inputDigest,
                    conversationId: conversationId
                )
                return
            }
            guard self.currentRunId == runId else { return }
            if let openAI = providerSetting as? ProviderSetting.OpenAI,
               openAI.authMode != OpenAIAuthMode.codexOauth,
               IOSCodexProviderResolver.isCodexProvider(providerSetting) {
                _ = self.dependencies.sharedSettings.setOpenAIAuthMode(
                    providerId: openAI.id.description(),
                    authMode: OpenAIAuthMode.codexOauth
                )
                self.dependencies.sharedSettings.syncLegacySettingsStoreForCurrentChat(self.dependencies.settingsStore)
            }
            await self.prepareAndStartStreaming(
                providerSetting: effectiveProvider,
                params: params,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                uploadMessages: uploadMessages,
                diagnosticOriginalProvider: providerSetting
            )
        }
    }

    func runImageTool(
        input: String,
        conversationId: KotlinUuid?,
        providerSetting: ProviderSetting?,
        params: TextGenerationParams?
    ) {
        if isRunning {
            cancel()
        }

        let runId = UUID().uuidString
        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)
        let inputDigest = chatInputDigest(for: input)
        currentRunId = runId
        // runImageTool 这条路不经过 prepareAndStartStreaming(没有压缩/多轮),但仍是
        // 一个独立的 run 入口,同样按 I-4 定格一份快照,保持“currentRunId 有值时
        // currentRunSnapshot 必然一致存在”这条不变量,不给未来接入压缩/续流的调用
        // 留裂缝。
        currentRunSnapshot = ChatRunSnapshot(runId: runId, settings: dependencies.sharedSettings.snapshot)
        currentToolLoopGuard = IOSToolLoopGuard()
        currentStartedAt = startedAt
        currentInputDigest = inputDigest
        currentConversationIdForRun = conversationId
        currentToolResumeCount = 0
        currentGenerativeUiRequirement = .none
        currentGenerativeUiFallbackAttempted = false
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        bindings.setIsLoading(true)
        let imagePresentation = AgentActivityPresentation.runningTool(toolName: "generate_image")
        currentLiveActivityStage = imagePresentation.stage
        bindings.startLiveActivity(
            runId,
            conversationId,
            imagePresentation
        )
        // 生图也是流式生成的一部分，退后台同样要保住执行权。
        // 图是一次性 HTTP，执行中无法安全搬家；短窗口未被系统接管或系统长窗口
        // 被收走时都只取消当前 owner，不能另起一条请求造成重复扣费。
        BackgroundGenerationKeepAlive.shared.begin(
            runId,
            title: "Amber 正在生成图片",
            subtitle: params?.model.displayName ?? "图片生成",
            onExpire: { [weak self] in
                self?.cancelRunAfterSystemKeepAliveExpiration(runId)
            },
            onSystemTaskExpiration: { [weak self] in
                self?.cancelRunAfterSystemKeepAliveExpiration(runId)
            }
        )
        BackgroundGenerationKeepAlive.shared.updateProgress(
            runId,
            completed: 0,
            total: 3,
            subtitle: "准备图片请求"
        )

        let toolCall = toolRuntime.userInitiatedImageToolCall(input: input)
        var snapshot = bindings.getMessages()
        snapshot.append(UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [toolCall],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        ))
        bindings.setMessages(snapshot)
        bindings.bumpMessageRevision(.toolCallStarted)

        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.bindings.recordRun(runId, startedAt, "running", inputDigest, conversationId?.toHexDashString())
            guard self.currentRunId == runId else { return }

            // F9 fix (I-1 durable boundary, "先记账，后动手"): generate_image is
            // a paid sideEffect tool — before this fix, the pre-execution
            // persist was a fire-and-forget `Task {}` fired earlier in
            // `runImageTool` (no `await`, no ledger trace at all), so a crash
            // between issuing the call and the image HTTP request completing
            // left nothing durable behind. On next launch, `pendingImageToolCall`
            // would find this same tool call still pending (empty output) and
            // re-fire it — a real, user-charged re-execution. Persist the
            // baseline synchronously and record a ledger Started BEFORE the
            // execution task below; either failing means the tool must NOT
            // run, mirroring `executeToolCall`'s exact discipline.
            let writeBaseline = self.bindings.capturePersistMessagesBaseline(conversationId)
            let didPersistBeforeExecution = await self.bindings.persistMessagesSnapshot(
                snapshot,
                conversationId,
                writeBaseline
            )
            guard self.currentRunId == runId else { return }
            guard didPersistBeforeExecution else {
                await self.failImageToolCallBeforeExecution(
                    toolCall,
                    in: snapshot,
                    runId: runId,
                    startedAt: startedAt,
                    inputDigest: inputDigest,
                    conversationId: conversationId,
                    reason: "无法保存工具执行前状态，请检查存储空间后重试。"
                )
                return
            }
            let didRecordToolCallStarted = await self.toolLedger.recordToolCallStarted(
                runId: runId,
                toolCallId: toolCall.toolCallId,
                toolName: toolCall.toolName,
                argsDigest: chatInputDigest(for: toolCall.input),
                effectClass: .sideEffect
            )
            guard self.currentRunId == runId else { return }
            guard didRecordToolCallStarted else {
                await self.failImageToolCallBeforeExecution(
                    toolCall,
                    in: snapshot,
                    runId: runId,
                    startedAt: startedAt,
                    inputDigest: inputDigest,
                    conversationId: conversationId,
                    reason: "无法保存工具执行前状态，请检查存储空间后重试。"
                )
                return
            }

            let executionToken = UUID()
            let executionTask = Task { @MainActor [toolRuntime] in
                await toolRuntime.messagesByExecutingImageToolCall(
                    toolCall,
                    in: snapshot
                )
            }
            self.foregroundImageToolExecutionToken = executionToken
            self.foregroundImageToolExecutionTask = executionTask
            let resumed = await executionTask.value
            self.clearForegroundImageToolExecution(matching: executionToken)
            // The side effect already happened by now regardless of whether
            // the run is still current — record Finished unconditionally,
            // same discipline as F10's automatic/approval paths.
            await self.toolLedger.recordToolCallFinished(
                runId: runId,
                toolCallId: toolCall.toolCallId,
                outcome: "completed"
            )
            guard self.currentRunId == runId else { return }
            self.bindings.setMessages(resumed)
            self.bindings.bumpMessageRevision(.toolResultAppended)
            let failureReason = ChatToolOutputFormatter.imageFailureReason(in: resumed, matching: toolCall)
            let conversationHex = conversationId?.toHexDashString()
            let didPersist = await self.bindings.persistMessages(conversationId)
            let succeeded = failureReason == nil && didPersist
            let runStatus = didPersist
                ? (failureReason == nil ? "completed" : "failed")
                : "recovery_pending"
            await self.bindings.recordRun(
                runId,
                startedAt,
                runStatus,
                inputDigest,
                conversationHex
            )
            if succeeded {
                WatchTaskCoordinator.shared.publishCompleted(
                    runId: runId,
                    conversationId: conversationHex,
                    summary: Self.watchSummary(from: resumed),
                    kind: .imageGeneration
                )
            } else {
                WatchTaskCoordinator.shared.publish(
                    runId: runId,
                    conversationId: conversationHex,
                    presentation: .failed(),
                    summary: WatchTaskText.clipped(
                        failureReason ?? "图片已生成，但结果保存失败。",
                        maxLength: 200
                    )
                )
            }
            await self.dependencies.liveActivityController.end(
                runId: runId,
                presentation: succeeded ? .completed(toolTitle: "图片生成") : .failed()
            )
            let didFinish = self.finishStreaming(
                runId: runId,
                terminalEvent: succeeded ? .generationCompleted : .generationFailed
            )
            if succeeded && didFinish {
                self.bindings.generationSucceeded()
            }
        }
    }

    /// F9 fix helper: writes a failure output into `toolCall`'s tool part and
    /// tears the run down as "failed", for the two pre-execution failure
    /// points in `runImageTool` (persist-before-execution / ledger Started
    /// both fail-closed — the tool must never actually run in that case).
    /// Unlike `executeToolCall`'s equivalent guards (which surface a generic
    /// stream-error card via `presentStreamError` and leave the tool call's
    /// own output empty), this writes directly into the tool part so the
    /// timeline shows the failure on that specific step — mirroring how a
    /// real image-generation failure is already reported via
    /// `ChatToolOutputFormatter.imageFailureReason`.
    private func failImageToolCallBeforeExecution(
        _ toolCall: UIMessagePart.Tool,
        in snapshot: [UIMessage],
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        reason: String
    ) async {
        let failure = ChatToolOutputFormatter.toolFailureJSON(
            toolName: toolCall.toolName,
            reason: reason,
            cancelled: false
        )
        let failedMessages = toolRuntime.messagesByFinishingToolCall(
            toolCall,
            outputText: failure,
            in: snapshot
        )
        bindings.setMessages(failedMessages)
        bindings.bumpMessageRevision(.toolResultAppended)
        let conversationHex = conversationId?.toHexDashString()
        let didPersist = await bindings.persistMessages(conversationId)
        await bindings.recordRun(
            runId,
            startedAt,
            didPersist ? "failed" : "recovery_pending",
            inputDigest,
            conversationHex
        )
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: conversationHex,
            presentation: .failed(),
            summary: WatchTaskText.clipped(reason, maxLength: 200)
        )
        await dependencies.liveActivityController.end(runId: runId, presentation: .failed())
        finishStreaming(runId: runId, terminalEvent: .generationFailed)
    }

    func cancel() {
        _ = cancel(runId: nil)
    }

    @discardableResult
    func cancel(runId expectedRunId: String) -> Bool {
        cancel(runId: Optional(expectedRunId))
    }

    private func cancelRunAfterSystemKeepAliveExpiration(_ runId: String) {
        _ = cancel(runId: runId)
    }

    @discardableResult
    private func cancel(runId expectedRunId: String?) -> Bool {
        guard let activeRunId = currentRunId,
              expectedRunId == nil || expectedRunId == activeRunId else {
            return false
        }
        let runId = currentRunId
        let startedAt = currentStartedAt
        let digest = currentInputDigest
        let conversationId = currentConversationIdForRun

        streamJob?.cancel(cause: nil)
        streamJob = nil
        grokWebStreamTask?.cancel()
        grokWebStreamTask = nil
        streamEventSink?.finish()
        drainPendingStreamChunksIntoAccumulator()
        let pendingStreamSnapshotAtCancellation = latestPendingStreamSnapshot()
        var messagesAtCancellation = pendingStreamSnapshotAtCancellation ?? bindings.getMessages()
        // Cancel must close empty tool outputs (including ask_user). Otherwise a later
        // complete/resume path can re-pick the same unresolved tool as a fresh pending node.
        if toolRuntime.hasUnresolvedToolCall(in: messagesAtCancellation) {
            messagesAtCancellation = toolRuntime.messagesByFailingPendingToolCalls(
                in: messagesAtCancellation,
                failureReason: "User cancelled.",
                denied: true
            )
        }
        let writeBaselineAtCancellation = bindings.capturePersistMessagesBaseline(conversationId)
        bindings.setMessages(messagesAtCancellation)
        cancelStreamEventConsumer()
        cancelForegroundToolExecutions()
        cancelPendingStreamSnapshotPublish()
        // 取消也是 run 的终结:与 finishStreaming/后台交接保持一致,关闭录制器,
        // 否则取消路径会泄漏文件句柄与 per-run 字典条目。
        if let runId {
            ChatStreamRecorder.shared.record(runId: runId, snapshot: messagesAtCancellation)
            ChatStreamRecorder.shared.finish(runId: runId)
        }
        currentRunId = nil
        currentRunSnapshot = nil
        currentToolLoopGuard = IOSToolLoopGuard()
        currentLiveActivityStage = nil
        currentStartedAt = nil
        currentInputDigest = nil
        currentConversationIdForRun = nil
        currentToolResumeCount = 0
        currentGenerativeUiRequirement = .none
        currentGenerativeUiFallbackAttempted = false
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        clearPendingApprovals()
        bindings.setIsLoading(false)
        bindings.setContextCompactState(.idle)
        if runId != nil {
            bindings.bumpMessageRevision(.generationCancelled)
        }

        guard let runId else { return true }
        Task { @MainActor [dependencies, bindings] in
            let didPersist = await bindings.persistMessagesSnapshot(
                messagesAtCancellation,
                conversationId,
                writeBaselineAtCancellation
            )
            if let startedAt, let digest {
                await bindings.recordRun(
                    runId,
                    startedAt,
                    didPersist ? "interrupted" : "recovery_pending",
                    digest,
                    conversationId?.toHexDashString()
                )
            }
            WatchTaskCoordinator.shared.publish(
                runId: runId,
                conversationId: conversationId?.toHexDashString(),
                presentation: didPersist ? .cancelled() : .failed(),
                summary: didPersist ? nil : "已停止生成，但最终状态保存失败。"
            )
            await dependencies.liveActivityController.end(
                runId: runId,
                presentation: didPersist ? .cancelled() : .failed()
            )
            BackgroundGenerationKeepAlive.shared.end(runId)
        }
        return true
    }

    /// (Re)snapshots the background handoff payload. Called at every point a run
    /// can be interrupted by a background transition:
    ///  - `startStreaming` (model streaming), so a lock screen mid-stream hands
    ///    off the current round's input/output snapshot.
    ///  - `executeToolCall` (tool HTTP in flight), so a lock screen DURING tool
    ///    execution hands off a snapshot that still carries the model's just-made
    ///    tool call. The background engine then pre-executes that pending tool
    ///    (see IOSAgentToolEngine.executePreExistingPendingTools) instead of
    ///    re-prompting the model to re-issue it — e.g. avoiding a wasted
    ///    duplicate image generation.
    private func refreshBackgroundHandoff(
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        providerSetting: ProviderSetting,
        backgroundProviderSetting: ProviderSetting?,
        params: TextGenerationParams,
        uploadMessages: [UIMessage],
        displayMessages: [UIMessage],
        generativeUiRequirement: IOSGenerativeUiRequirement,
        generativeUiFallbackAttempted: Bool
    ) {
        if let conversationId {
            backgroundHandoff = IOSChatBackgroundHandoff(
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                providerId: (backgroundProviderSetting ?? providerSetting).id.toHexDashString(),
                providerSetting: backgroundProviderSetting ?? providerSetting,
                params: params,
                uploadMessages: uploadMessages,
                displayMessages: displayMessages,
                mode: .continueModel,
                generativeUiRequirement: generativeUiRequirement,
                generativeUiFallbackAttempted: generativeUiFallbackAttempted
            )
        } else {
            backgroundHandoff = nil
        }
    }

    private func runtimeContextUploadMessages(from baseMessages: [UIMessage]) -> [UIMessage] {
        let runtimeMessages = bindings.messagesByInjectingRuntimeContext(baseMessages)
        return ChatRuntimeContextBuilder.coalescingSystemMessages(runtimeMessages)
    }

    private func backgroundToolHandoffUploadMessages(
        from baseMessages: [UIMessage],
        params: TextGenerationParams,
        runId: String
    ) -> [UIMessage] {
        let plan = IOSGenerativeUiRequestPolicy.plan(
            setting: settingsSnapshot(forRun: runId).agentRuntime.generativeUi,
            messages: baseMessages,
            params: params
        )
        return runtimeContextUploadMessages(from: plan.uploadMessages)
    }

    /// - Parameter honorKeepAliveLease: 只有「App 退到后台」这一种理由能认这道短路。
    ///   换会话、执行权到期这些必须把前台流的归属让出去，认了就等于把生成掐死。
    @discardableResult
    func handoffCurrentGenerationToBackground(
        conversationStore: IOSConversationStore?,
        honorKeepAliveLease: Bool = false
    ) -> Bool {
        // 后台执行权还在手上：流会自己跑完，砍掉它重跑是纯粹的浪费——
        // 既烧一遍 token，又丢掉已经流出来的正文。只有 UIKit 短窗口的
        // `onExpire` 在系统接管前到期才需要真交接。
        if honorKeepAliveLease,
           let runId = currentRunId,
           BackgroundGenerationKeepAlive.shared.holdsLease(runId) {
            // 真交接推迟到执行权到期那一刻，那时调用方已经不在场了——
            // 趁现在把 store 记下来，否则 onExpire 拿不到它，等于没兜底。
            if let conversationStore {
                pendingBackgroundConversationStore = conversationStore
            }
            return false
        }
        guard !hasPendingToolApproval else { return false }
        // Once a foreground tool request is already in flight, keep that request
        // as the owner. BG handoff is not guaranteed to start immediately; if we
        // cancel/clear the foreground run here, the in-flight result can be
        // discarded and the user returns to an empty pending tool bubble.
        guard foregroundToolExecutionTask == nil,
              foregroundImageToolExecutionTask == nil else {
            return false
        }
        guard let handoff = backgroundHandoff,
              let conversationStore else {
            if isRunning {
                pendingBackgroundConversationStore = conversationStore
            }
            return false
        }
        guard !IOSGrokWebProviderResolver.isGrokWebProvider(handoff.providerSetting) else {
            return false
        }
        let startBackground = { [self] in
            IOSChatBackgroundGenerationCoordinator.shared.start(
                handoff: handoff,
                conversationStore: conversationStore,
                toolRuntime: self.toolRuntime,
                liveActivityController: self.dependencies.liveActivityController,
                saveMiniAppIfPresent: { [bindings = self.bindings] messages, conversationId in
                    bindings.saveMiniAppIfPresent(messages, conversationId)
                }
            )
        }
        let didStart = streamEventSink?.transitionToBackgroundIfNoTerminal {
            BackgroundGenerationKeepAlive.shared.transfer(handoff.runId, to: startBackground)
        } ?? BackgroundGenerationKeepAlive.shared.transfer(handoff.runId, to: startBackground)
        guard didStart else { return false }

        streamJob?.cancel(cause: nil)
        streamJob = nil
        grokWebStreamTask?.cancel()
        grokWebStreamTask = nil
        drainPendingStreamChunksIntoAccumulator()
        cancelForegroundToolExecutions()
        if let pendingStreamSnapshot = latestPendingStreamSnapshot() {
            // Background handoff bypasses the normal scheduleStreamSnapshotPublish
            // path, so without this the recorder would miss the last in-flight
            // snapshot before ownership moves to the background coordinator.
            if let runId = currentRunId {
                ChatStreamRecorder.shared.record(runId: runId, snapshot: pendingStreamSnapshot)
            }
            bindings.setMessages(pendingStreamSnapshot)
            bindings.bumpMessageRevision(.streamDelta)
        }
        cancelStreamEventConsumer()
        cancelPendingStreamSnapshotPublish()
        if let runId = currentRunId {
            ChatStreamRecorder.shared.finish(runId: runId)
        }
        currentRunId = nil
        currentRunSnapshot = nil
        currentToolLoopGuard = IOSToolLoopGuard()
        currentLiveActivityStage = nil
        currentStartedAt = nil
        currentInputDigest = nil
        currentConversationIdForRun = nil
        currentToolResumeCount = 0
        currentGenerativeUiRequirement = .none
        currentGenerativeUiFallbackAttempted = false
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        clearPendingApprovals()
        bindings.setContextCompactState(.idle)
        bindings.setIsLoading(false)
        bindings.bumpMessageRevision(.generationHandedOffToBackground)
        return true
    }

    func approvePendingMemoryTool() {
        finishPendingMemoryToolApproval(writePolicy: .allow)
    }

    func denyPendingMemoryTool() {
        finishPendingMemoryToolApproval(writePolicy: .deniedByUser("User denied memory write."))
    }

    func approvePendingSearchTool() async {
        await finishPendingSearchToolApproval(allow: true)
    }

    func denyPendingSearchTool() async {
        await finishPendingSearchToolApproval(allow: false)
    }

    func approvePendingWebMountTool() async {
        await finishPendingWebMountToolApproval(allow: true)
    }

    func denyPendingWebMountTool() async {
        await finishPendingWebMountToolApproval(allow: false)
    }

    func approvePendingWorkspaceTool() async {
        await finishPendingWorkspaceToolApproval(allow: true)
    }

    func denyPendingWorkspaceTool() async {
        await finishPendingWorkspaceToolApproval(allow: false)
    }

    func approvePendingIshHandoffTool() async {
        await finishPendingIshHandoffToolApproval(allow: true)
    }

    func denyPendingIshHandoffTool() async {
        await finishPendingIshHandoffToolApproval(allow: false)
    }

    func approvePendingMcpTool() async {
        await finishPendingMcpToolApproval(allow: true)
    }

    func denyPendingMcpTool() async {
        await finishPendingMcpToolApproval(allow: false)
    }

    func approvePendingCouncilTool() async {
        await finishPendingCouncilToolApproval(allow: true)
    }

    func denyPendingCouncilTool() async {
        await finishPendingCouncilToolApproval(allow: false)
    }

    /// I-4:压缩配置(`IOSContextCompactionCoordinator` 的 prepare/finalize 两处)
    /// 只从这里取——run 开始时定格的 `ChatRunSnapshot.settings`,不再每轮 live 读
    /// `dependencies.sharedSettings.snapshot`。同一个 run 内调用两次也拿到同一份,
    /// 这也顺带堵上了“同一轮内两次读取之间设置也可能变化”的轮内自不一致。
    ///
    /// F4 fix: only write back to `currentRunSnapshot` when `runId` still IS the
    /// active run. A `runId` that no longer matches `currentRunId` means the run
    /// this call was made on behalf of has already been superseded (replaced by
    /// `cancel()`/a new `start()`/`runImageTool()`) by the time this async call
    /// finally resumes here — committing a snapshot for a dead runId would
    /// overwrite whatever the ACTUAL active run's `settingsSnapshot(forRun:)`
    /// call already froze, silently breaking that run's I-4 guarantee too. In
    /// that case still return a freshly-read snapshot so the (already-doomed)
    /// caller can finish its error path with *some* settings, but never let it
    /// leak into `currentRunSnapshot`.
    private func settingsSnapshot(forRun runId: String) -> Settings {
        if let currentRunSnapshot, currentRunSnapshot.runId == runId {
            return currentRunSnapshot.settings
        }
        let freshSnapshot = ChatRunSnapshot(runId: runId, settings: dependencies.sharedSettings.snapshot)
        if currentRunId == runId {
            currentRunSnapshot = freshSnapshot
        }
        return freshSnapshot.settings
    }

    private func prepareAndStartStreaming(
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        uploadMessages: [UIMessage],
        diagnosticOriginalProvider: ProviderSetting? = nil
    ) async {
        guard currentRunId == runId else { return }
        let effectiveProvider: ProviderSetting
        do {
            effectiveProvider = try await IOSCodexProviderResolver.resolved(providerSetting)
        } catch {
            await presentStreamError(
                rawMessage: (error as NSError).localizedDescription,
                modelId: params.model.modelId,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }
        // F4 fix: this is the entry point's first `await`; the run may have
        // been replaced (cancel()/new start()/runImageTool()) while
        // `IOSCodexProviderResolver.resolved` was suspended. The existing
        // guard at the top of this function only covers the synchronous
        // window before that await — re-check here before touching any
        // run-scoped state (currentRunSnapshot via settingsSnapshot(forRun:)
        // below, applyCompactEvent, etc).
        guard currentRunId == runId else { return }
        let codexParams = IOSCodexProviderResolver.augmentParamsForCodex(params, provider: effectiveProvider)
        let runSettings = settingsSnapshot(forRun: runId)
        let generativeUiPlan = IOSGenerativeUiRequestPolicy.plan(
            setting: runSettings.agentRuntime.generativeUi,
            messages: uploadMessages,
            params: codexParams
        )
        let effectiveParams = generativeUiPlan.params
        let generativeUiPreparedMessages = generativeUiPlan.uploadMessages
        IOSCodexProviderResolver.writeRequestDiagnostic(
            originalProvider: diagnosticOriginalProvider ?? providerSetting,
            resolvedProvider: effectiveProvider,
            params: effectiveParams
        )

        let runtimeBaseline = bindings.messagesByInjectingRuntimeContext(generativeUiPreparedMessages)
        let runtimeOverheadTokens = max(
            IOSContextCompactionCoordinator.estimatedTokensForRequest(runtimeBaseline) -
                IOSContextCompactionCoordinator.estimatedTokensForRequest(generativeUiPreparedMessages),
            0
        )

        let preparedUploadMessages: [UIMessage]
        do {
            preparedUploadMessages = try await IOSContextCompactionCoordinator.shared.prepareMessagesForRequest(
                uploadMessages: generativeUiPreparedMessages,
                conversationId: conversationId,
                settings: runSettings,
                params: effectiveParams,
                fallbackProvider: effectiveProvider,
                promptOverheadTokens: runtimeOverheadTokens,
                onEvent: { [weak self] event in
                    guard let self,
                          ChatContextCompactEventRouter.shouldApply(
                            event: event,
                            eventRunId: runId,
                            currentRunId: self.currentRunId
                          ) else { return }
                    self.applyCompactEvent(event)
                }
            )
        } catch {
            if currentRunId == runId {
                applyCompactEvent(.failed(message: (error as NSError).localizedDescription))
            }
            await presentStreamError(
                rawMessage: "上下文压缩失败：\((error as NSError).localizedDescription)",
                modelId: effectiveParams.model.modelId,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }
        guard currentRunId == runId else { return }
        let promptedUploadMessages = messagesByInjectingImageGenerationPromptIfNeeded(
            preparedUploadMessages,
            params: effectiveParams
        )
        let runtimePreparedMessages = bindings.messagesByInjectingRuntimeContext(promptedUploadMessages)
        let finalizedUploadMessages: [UIMessage]
        do {
            finalizedUploadMessages = try IOSContextCompactionCoordinator.shared.finalizedMessagesForRequest(
                runtimePreparedMessages,
                settings: runSettings,
                params: effectiveParams
            )
        } catch {
            if currentRunId == runId {
                applyCompactEvent(.failed(message: (error as NSError).localizedDescription))
            }
            await presentStreamError(
                rawMessage: "上下文压缩失败：\((error as NSError).localizedDescription)",
                modelId: effectiveParams.model.modelId,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }
        let finalUploadMessages = ChatRuntimeContextBuilder.coalescingSystemMessages(finalizedUploadMessages)
        startStreaming(
            providerSetting: effectiveProvider,
            params: effectiveParams,
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            uploadMessages: finalUploadMessages,
            backgroundProviderSetting: providerSetting,
            generativeUiRequirement: generativeUiPlan.requirement
        )
    }

    private func applyCompactEvent(_ event: IOSContextCompactionEvent) {
        switch event {
        case .planning:
            bindings.setContextCompactState(ChatContextCompactState(
                status: .planning,
                summary: "",
                updatedAt: Date()
            ))
        case .compacting:
            bindings.setContextCompactState(ChatContextCompactState(
                status: .compacting,
                summary: "",
                updatedAt: Date()
            ))
        case .completed(let summary):
            bindings.setContextCompactState(ChatContextCompactState(
                status: .completed,
                summary: summary,
                updatedAt: Date()
            ))
        case .failed(let message):
            bindings.setContextCompactState(ChatContextCompactState(
                status: .failed,
                summary: message,
                updatedAt: Date()
            ))
        case .idle:
            bindings.setContextCompactState(.idle)
        }
    }

    private func messagesByInjectingImageGenerationPromptIfNeeded(
        _ messages: [UIMessage],
        params: TextGenerationParams
    ) -> [UIMessage] {
        guard params.tools.contains(where: { $0.name == "generate_image" }) else {
            return messages
        }
        let prompt = """
        Image-generation routing guidance for AmberAgent iOS:
        - When the user asks for a photographic, painted, illustrated, poster, wallpaper, concept-art, or character-art image, call `generate_image` exactly once instead of answering with SVG/HTML.
        - Preserve the user's subject, style, aspect-ratio cues, and language. Prefer a detailed prompt with subject, composition, lighting, mood, and visual style.
        - If the request references named fiction/IP, avoid brittle prompt wording like "fan art of <character> from <franchise>". Use an original inspired depiction that keeps the user's requested vibe and recognizable high-level visual cues without asking for an exact copyrighted character, logo, actor, or celebrity likeness.
        - If `generate_image` fails, report the failure honestly and ask whether to retry or adjust the prompt. Do not substitute an SVG/code sketch as if image generation succeeded unless the user explicitly asks for a fallback sketch.
        """
        let systemMessage = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.system,
            parts: [UIMessagePart.Text(text: prompt, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        return [systemMessage] + messages
    }

    private func startStreaming(
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        uploadMessages: [UIMessage],
        backgroundProviderSetting: ProviderSetting? = nil,
        generativeUiRequirement: IOSGenerativeUiRequirement = .none,
        generativeUiFallbackAttempted: Bool = false,
        displayMessagesOverride: [UIMessage]? = nil
    ) {
        let displayMessages = displayMessagesOverride ?? bindings.getMessages()
        currentGenerativeUiRequirement = generativeUiRequirement
        currentGenerativeUiFallbackAttempted = generativeUiFallbackAttempted
        BackgroundGenerationKeepAlive.shared.updateProgress(
            runId,
            completed: 1,
            total: 4,
            subtitle: "正在生成回复"
        )
        refreshBackgroundHandoff(
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            providerSetting: providerSetting,
            backgroundProviderSetting: backgroundProviderSetting,
            params: params,
            uploadMessages: uploadMessages,
            displayMessages: displayMessages,
            generativeUiRequirement: generativeUiRequirement,
            generativeUiFallbackAttempted: generativeUiFallbackAttempted
        )
        if let pendingStore = pendingBackgroundConversationStore {
            pendingBackgroundConversationStore = nil
            // 已经在后台了才会有 pendingStore。执行权还在就继续前台跑完。
            if handoffCurrentGenerationToBackground(
                conversationStore: pendingStore,
                honorKeepAliveLease: true
            ) {
                return
            }
        }
        let accumulator = MessageStreamAccumulator(
            initialMessages: displayMessages,
            model: params.model
        )
        let eventSink = ChatStreamEventSink()
        cancelStreamEventConsumer()
        let streamSession = ChatStreamAccumulatorSession(
            accumulator: accumulator,
            eventSink: eventSink
        )
        streamEventSink = eventSink
        activeStreamSession = streamSession
        // 从 session 建立起就保留快照入口；即使 MainActor 尚未消费首个 chunk，
        // cancel / background handoff 也能在 drain 后拿到累加器的最新状态。
        pendingStreamSnapshotProvider = { accumulator.snapshot() }
        let eventStream = AsyncStream<ChatStreamEvent>(bufferingPolicy: .unbounded) { continuation in
            eventSink.bind(continuation)
        }
        streamEventTask = Task { @MainActor [weak self] in
            for await event in eventStream {
                guard eventSink.claim(event) else { continue }
                guard let self, self.currentRunId == runId else { return }
                switch event.payload {
                case .chunk(let chunk):
                    accumulator.append(chunk: chunk)
                    if Self.reachedOutputLimit(chunk) {
                        streamSession.hitOutputLimit = true
                    }
                    let toolCalls = Self.toolCalls(in: chunk)
                        .filter { toolCall in
                            let key = chatToolCallKey(toolCall)
                            guard !streamSession.detectedToolCallIds.contains(key) else { return false }
                            streamSession.detectedToolCallIds.insert(key)
                            return true
                        }
                    if !toolCalls.isEmpty {
                        self.handleDetectedToolCalls(toolCalls, runId: runId)
                    } else if let stage = Self.responseStage(in: chunk) {
                        await self.updateResponseLiveActivityStageIfNeeded(
                            stage,
                            runId: runId
                        )
                    }
                    self.scheduleStreamSnapshotPublish(
                        snapshotProvider: { accumulator.snapshot() },
                        runId: runId
                    )
                case .complete:
                    self.cancelPendingStreamSnapshotPublish()
                    let snapshot = accumulator.snapshot()
                    // Terminal hands authority to bindings/tool execution. Keeping this
                    // accumulator reachable would let a later cancel restore a stale pre-tool snapshot.
                    self.activeStreamSession = nil
                    guard await self.drainStreamPresentation(to: snapshot, runId: runId) else {
                        return
                    }
                    await self.handleCompletedStream(
                        snapshot: snapshot,
                        providerSetting: providerSetting,
                        params: params,
                        runId: runId,
                        startedAt: startedAt,
                        inputDigest: inputDigest,
                        conversationId: conversationId,
                        hitOutputLimit: streamSession.hitOutputLimit,
                        uploadMessages: uploadMessages,
                        backgroundProviderSetting: backgroundProviderSetting,
                        displayMessages: displayMessages,
                        generativeUiRequirement: generativeUiRequirement,
                        generativeUiFallbackAttempted: generativeUiFallbackAttempted
                    )
                    return
                case .error(let error):
                    self.cancelPendingStreamSnapshotPublish()
                    let snapshot = accumulator.snapshot()
                    // The terminal snapshot is published below; it is no longer a cancel fallback.
                    self.activeStreamSession = nil
                    ChatStreamRecorder.shared.record(runId: runId, snapshot: snapshot)
                    // 与 .complete 对称:先按 pacer 逐拍追平积压文本,再发布终态与错误气泡。
                    // drain 失败(run 被取消/接管)时终态所有权已在别处,直接退出。
                    guard await self.drainStreamPresentation(to: snapshot, runId: runId) else {
                        return
                    }
                    self.bindings.setMessages(snapshot)
                    self.bindings.bumpMessageRevision(.assistantStreamClosed)
                    await self.presentStreamError(
                        rawMessage: error.message ?? String(describing: error),
                        modelId: params.model.modelId,
                        runId: runId,
                        startedAt: startedAt,
                        inputDigest: inputDigest,
                        conversationId: conversationId
                    )
                    return
                }
            }
        }

        streamJob = dispatchStream(
            providerSetting: providerSetting,
            messages: uploadMessages,
            params: params,
            onChunk: { chunk in
                eventSink.yield(.chunk(chunk))
            },
            onComplete: {
                eventSink.yield(.complete())
                eventSink.finish()
            },
            onError: { error in
                eventSink.yield(.error(error))
                eventSink.finish()
            }
        )
    }

    private func scheduleStreamSnapshotPublish(
        snapshotProvider: @escaping () -> [UIMessage],
        runId: String
    ) {
        pendingStreamSnapshotProvider = snapshotProvider
        guard streamSnapshotFlushTask == nil else { return }
        streamSnapshotFlushTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: self?.streamSnapshotFlushDelayNanos ?? 48_000_000)
            } catch {
                return
            }
            self?.flushPendingStreamSnapshot(runId: runId)
        }
    }

    private func flushPendingStreamSnapshot(runId: String) {
        streamSnapshotFlushTask = nil
        guard currentRunId == runId else {
            pendingStreamSnapshot = nil
            pendingStreamSnapshotProvider = nil
            return
        }
        ChatPerfTrace.measure("StreamFlush") {
            guard let targetSnapshot = latestPendingStreamSnapshot() else { return }
            pendingStreamSnapshot = nil
            pendingStreamSnapshotProvider = nil
            let caughtUp = publishPacedStreamSnapshot(targetSnapshot, runId: runId)
            if !caughtUp {
                pendingStreamSnapshot = targetSnapshot
                scheduleStreamSnapshotPublish(
                    snapshotProvider: { targetSnapshot },
                    runId: runId
                )
            }
        }
    }

    @discardableResult
    private func publishPacedStreamSnapshot(_ target: [UIMessage], runId: String) -> Bool {
        let step = bindings.shouldPaceStreamPresentation()
            ? ChatStreamPresentationPacer.step(current: bindings.getMessages(), target: target)
            : ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
        ChatStreamRecorder.shared.record(runId: runId, snapshot: step.snapshot)
        bindings.setMessages(step.snapshot)
        bindings.bumpMessageRevision(.streamDelta)
        return step.isCaughtUp
    }

    private func drainStreamPresentation(to target: [UIMessage], runId: String) async -> Bool {
        // Keep the authoritative terminal snapshot reachable while presentation
        // catches up. A cancellation during this short window must persist the
        // full provider result rather than the currently visible prefix.
        pendingStreamSnapshot = target
        while currentRunId == runId, !Task.isCancelled {
            if publishPacedStreamSnapshot(target, runId: runId) {
                pendingStreamSnapshot = nil
                return true
            }
            do {
                try await Task.sleep(nanoseconds: streamSnapshotFlushDelayNanos)
            } catch {
                return false
            }
        }
        return false
    }

    private func latestPendingStreamSnapshot() -> [UIMessage]? {
        if let provider = pendingStreamSnapshotProvider {
            let snapshot = provider()
            pendingStreamSnapshot = snapshot
            return snapshot
        }
        if let pendingStreamSnapshot {
            return pendingStreamSnapshot
        }
        return activeStreamSession?.accumulator.snapshot()
    }

    private func drainPendingStreamChunksIntoAccumulator() {
        guard let activeStreamSession else { return }
        for chunk in activeStreamSession.eventSink.takePendingChunks() {
            activeStreamSession.accumulator.append(chunk: chunk)
        }
    }

    private func cancelPendingStreamSnapshotPublish() {
        streamSnapshotFlushTask?.cancel()
        streamSnapshotFlushTask = nil
        pendingStreamSnapshot = nil
        pendingStreamSnapshotProvider = nil
    }

    /// Surfaces a generation failure as an assistant error bubble and finalizes
    /// the run (records "failed", ends the live activity, persists). Shared by the
    /// stream's onError and the codex token-resolution failure path.
    @MainActor
    private func presentStreamError(
        rawMessage: String,
        modelId: String,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?
    ) async {
        guard currentRunId == runId else { return }
        let userFacingMessage = bindings.userFacingGenerationError(rawMessage, modelId)
        let errMsg = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [MessageKt.localGenerationErrorTextPart(text: userFacingMessage)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        var updated = bindings.getMessages()
        if toolRuntime.hasUnresolvedToolCall(in: updated) {
            updated = toolRuntime.messagesByFailingPendingToolCalls(
                in: updated,
                failureReason: "Generation failed before the tool call completed."
            )
        }
        updated.append(errMsg)
        bindings.setMessages(updated)
        let conversationHex = conversationId?.toHexDashString()
        let didPersist = await bindings.persistMessages(conversationId)
        await bindings.recordRun(
            runId,
            startedAt,
            didPersist ? "failed" : "recovery_pending",
            inputDigest,
            conversationHex
        )
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: conversationHex,
            presentation: .failed(),
            summary: WatchTaskText.clipped(userFacingMessage, maxLength: 200)
        )
        await dependencies.liveActivityController.end(runId: runId, presentation: .failed())
        if !didPersist {
            print("[AmberChat] Failed to persist foreground error terminal run=\(runId)")
        }
        finishStreaming(runId: runId, terminalEvent: .generationFailed)
    }

    private func handleCompletedStream(
        snapshot: [UIMessage],
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        hitOutputLimit: Bool = false,
        uploadMessages: [UIMessage],
        backgroundProviderSetting: ProviderSetting?,
        displayMessages: [UIMessage],
        generativeUiRequirement: IOSGenerativeUiRequirement,
        generativeUiFallbackAttempted: Bool
    ) async {
        guard currentRunId == runId else { return }
        bindings.setMessages(snapshot)
        bindings.bumpMessageRevision(.assistantStreamClosed)

        if !generativeUiFallbackAttempted,
           !toolRuntime.hasUnresolvedToolCall(in: snapshot),
           let widgetIssue = IOSGenerativeUiRequestPolicy.widgetIssue(
               in: snapshot,
               afterDisplayMessageCount: displayMessages.count,
               requirement: generativeUiRequirement
           ) {
            let retryMessages = IOSGenerativeUiRequestPolicy.retryMessages(
                uploadMessages,
                requirement: generativeUiRequirement,
                issue: widgetIssue
            )
            let retryParams = IOSGenerativeUiRequestPolicy.retryParams(params)
            bindings.setMessages(displayMessages)
            bindings.bumpMessageRevision(.assistantStreamClosed)
            streamJob = nil
            startStreaming(
                providerSetting: providerSetting,
                params: retryParams,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                uploadMessages: ChatRuntimeContextBuilder.coalescingSystemMessages(retryMessages),
                backgroundProviderSetting: backgroundProviderSetting,
                generativeUiRequirement: generativeUiRequirement,
                generativeUiFallbackAttempted: true,
                displayMessagesOverride: displayMessages
            )
            return
        }

        // 输出上限截断:正文有效但不完整。必须先于工具分支返回——截断点可能
        // 落在 tool_calls 参数中途,那串残缺 JSON 不能当成可执行的工具调用。
        // 与 IOSAgentToolEngine.run 的判定顺序一致(reachedOutputLimit 先于
        // pendingToolCalls)。静默记 completed 会让用户把半截答案当完整答案。
        if hitOutputLimit {
            await completeTruncatedStream(
                snapshot: snapshot,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }

        let availableToolNames = Set(params.tools.map(\.name))
        if let pendingToolCall = toolRuntime.nextPendingToolCall(
            in: snapshot,
            availableToolNames: availableToolNames
        ) {
            guard currentToolResumeCount < maxToolResumeCount else {
                await failPendingToolCalls(
                    snapshot: snapshot,
                    failureText: "工具调用未执行：已达到本轮工具循环上限（\(maxToolResumeCount) 次）。请继续对话或拆分任务后重试。",
                    runId: runId,
                    startedAt: startedAt,
                    inputDigest: inputDigest,
                    conversationId: conversationId
                )
                return
            }

            currentToolResumeCount += 1
            bindings.bumpMessageRevision(.toolCallStarted)
            streamJob = nil
            await executeToolCall(
                pendingToolCall,
                providerSetting: providerSetting,
                params: params,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                baseMessages: snapshot
            )
            return
        }

        if toolRuntime.hasUnresolvedToolCall(in: snapshot) {
            await failPendingToolCalls(
                snapshot: snapshot,
                failureText: "工具调用未执行：该工具当前未启用或不可执行。请在设置中启用对应能力后重试。",
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }

        // 空回复检测：模型没有产出任何文本也没有工具调用时，给用户一个明确提示。
        if Self.isEmptyAssistantResponse(snapshot) {
            var emptySnapshot = snapshot
            emptySnapshot.append(Self.emptyResponseNotice())
            bindings.setMessages(emptySnapshot)
            bindings.bumpMessageRevision(.toolResultAppended)
            let conversationHex = conversationId?.toHexDashString()
            let didPersist = await bindings.persistMessages(conversationId)
            await bindings.recordRun(
                runId,
                startedAt,
                didPersist ? "completed" : "recovery_pending",
                inputDigest,
                conversationHex
            )
            if didPersist {
                WatchTaskCoordinator.shared.publishCompleted(
                    runId: runId,
                    conversationId: conversationHex,
                    summary: nil
                )
            } else {
                WatchTaskCoordinator.shared.publish(
                    runId: runId,
                    conversationId: conversationHex,
                    presentation: .failed(),
                    summary: "回复已生成，但最终结果保存失败。"
                )
            }
            await dependencies.liveActivityController.end(
                runId: runId,
                presentation: didPersist ? .completed() : .failed()
            )
            let didFinish = finishStreaming(
                runId: runId,
                terminalEvent: didPersist ? .generationCompleted : .generationFailed
            )
            if didPersist && didFinish {
                bindings.generationSucceeded()
            }
            return
        }

        var finalSnapshot = snapshot
        if let updatedMessages = bindings.saveMiniAppIfPresent(snapshot, conversationId) {
            finalSnapshot = updatedMessages
            bindings.setMessages(finalSnapshot)
            bindings.bumpMessageRevision(.toolResultAppended)
        }

        let conversationHex = conversationId?.toHexDashString()
        let summary = Self.watchSummary(from: finalSnapshot)
        let didPersist = await bindings.persistMessages(conversationId)
        await bindings.recordRun(
            runId,
            startedAt,
            didPersist ? "completed" : "recovery_pending",
            inputDigest,
            conversationHex
        )
        if didPersist {
            WatchTaskCoordinator.shared.publishCompleted(
                runId: runId,
                conversationId: conversationHex,
                summary: summary
            )
        } else {
            WatchTaskCoordinator.shared.publish(
                runId: runId,
                conversationId: conversationHex,
                presentation: .failed(),
                summary: "回复已生成，但最终结果保存失败。"
            )
        }
        await dependencies.liveActivityController.end(
            runId: runId,
            presentation: didPersist ? .completed() : .failed()
        )
        let didFinish = finishStreaming(
            runId: runId,
            terminalEvent: didPersist ? .generationCompleted : .generationFailed
        )
        if didPersist && didFinish {
            bindings.generationSucceeded()
        }
    }

    /// 达到 max_tokens 的收尾:保留已生成正文,追加一条可见提示,并把 run 记为
    /// `truncated` 而不是 `completed`。
    private func completeTruncatedStream(
        snapshot: [UIMessage],
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?
    ) async {
        var finalSnapshot = snapshot
        if toolRuntime.hasUnresolvedToolCall(in: finalSnapshot) {
            finalSnapshot = toolRuntime.messagesByFailingPendingToolCalls(
                in: finalSnapshot,
                failureReason: "The model output ended before the tool call completed."
            )
        }
        finalSnapshot.append(Self.outputLimitNotice())
        bindings.setMessages(finalSnapshot)
        bindings.bumpMessageRevision(.toolResultAppended)

        let conversationHex = conversationId?.toHexDashString()
        let didPersist = await bindings.persistMessages(conversationId)
        await bindings.recordRun(
            runId,
            startedAt,
            didPersist ? "truncated" : "recovery_pending",
            inputDigest,
            conversationHex
        )
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: conversationHex,
            presentation: .failed(),
            summary: Self.watchSummary(from: finalSnapshot)
        )
        await dependencies.liveActivityController.end(
            runId: runId,
            presentation: .failed()
        )
        finishStreaming(
            runId: runId,
            terminalEvent: didPersist ? .generationCompleted : .generationFailed
        )
    }

    static func outputLimitNotice() -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [MessageKt.localOutputLimitNoticeTextPart(
                text: "⚠️ 回复已达到模型输出上限，上面的内容并不完整。可以让我「继续」，或在助手设置里调高最大输出长度后重试。"
            )],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    /// 模型没有产出任何文本也没有工具调用。
    static func isEmptyAssistantResponse(_ messages: [UIMessage]) -> Bool {
        guard let last = messages.last, last.role == MessageRole.assistant else { return false }
        return !last.parts.contains { part in
            if let text = part as? UIMessagePart.Text {
                return !text.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
            return part is UIMessagePart.Tool || part is UIMessagePart.Image
        }
    }

    static func emptyResponseNotice() -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(
                text: "模型没有返回任何内容。这可能是服务商的临时问题——请重新发送，或换一个模型试试。",
                metadata: nil
            )],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    /// finish_reason 表示模型被输出预算截断(而非自然停止)。
    /// 判据与 `IOSAgentToolEngine.reachedOutputLimit` 保持同一份语义。
    static func reachedOutputLimit(_ chunk: MessageChunk) -> Bool {
        let reasons = Set(chunk.choices.compactMap { $0.finishReason?.lowercased() })
        return !reasons.isDisjoint(with: ["length", "max_tokens", "max_output_tokens"])
    }

    private func failPendingToolCalls(
        snapshot: [UIMessage],
        failureText: String,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?
    ) async {
        await terminatePendingToolCalls(
            snapshot: snapshot,
            failureText: failureText,
            runStatus: "failed",
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId
        )
    }

    /// I-5 打转守护的 stop 分支复用同一套收尾:未解决的工具调用被写成一条
    /// 结构化失败结果、run 持久化终态记录、Watch/灵动岛收尾、结束这条流。
    /// 与 `failPendingToolCalls` 唯一的差别是 `runStatus`——`guard_stopped` 与
    /// `failed` 在 `agent_run.status` 里都是终态字符串；只有持久化失败时
    /// 才保留为 `recovery_pending`，交给下次启动继续对账。
    private func terminatePendingToolCalls(
        snapshot: [UIMessage],
        failureText: String,
        runStatus: String,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?
    ) async {
        let writeBaseline = bindings.capturePersistMessagesBaseline(conversationId)
        let finalSnapshot = toolRuntime.messagesByFailingPendingToolCalls(
            in: snapshot,
            failureReason: failureText
        )
        bindings.setMessages(finalSnapshot)
        bindings.bumpMessageRevision(.toolResultAppended)
        let conversationHex = conversationId?.toHexDashString()
        let didPersist = await bindings.persistMessagesSnapshot(finalSnapshot, conversationId, writeBaseline)
        await bindings.recordRun(
            runId,
            startedAt,
            didPersist ? runStatus : "recovery_pending",
            inputDigest,
            conversationHex
        )
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: conversationHex,
            presentation: .failed(),
            summary: WatchTaskText.clipped(failureText, maxLength: 200)
        )
        await dependencies.liveActivityController.end(runId: runId, presentation: .failed())
        if !didPersist {
            print("[AmberChat] Failed to persist unresolved-tool terminal run=\(runId)")
        }
        finishStreaming(runId: runId, terminalEvent: .generationFailed)
    }

    private func executeToolCall(
        _ pendingToolCall: ChatPendingToolCall,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        baseMessages: [UIMessage]
    ) async {
        BackgroundGenerationKeepAlive.shared.updateProgress(
            runId,
            completed: 2,
            total: 4,
            subtitle: "正在执行工具"
        )
        // F11 fix (I-2 must gate ahead of I-5/I-1, not just inside
        // `ChatToolRuntime.execute`'s own per-kind dispatch): a tool call
        // whose `input` fails `parseInputStrict()` never actually executes —
        // `toolRuntime.execute` fail-closes it in place with a structured
        // error before it reaches any dispatch*/network path. Such a call
        // must not:
        //   - count toward the I-5 loop-guard's repeated-signature counter
        //     below (a call that never ran isn't "the model repeating
        //     itself" — it would falsely accelerate genuinely-distinct calls
        //     toward the guard's stop threshold);
        //   - be persisted, nor get an I-1 ledger Started record (there is
        //     nothing to retry-recover for a call rejected before it ran; a
        //     stray Started would only leave W3's classifier a phantom
        //     "outcome unknown" for a tool that in fact never fired).
        // Skip straight to `toolRuntime.execute` (it fail-closes this exact
        // case again on its own, as defense in depth) and resume the stream
        // from its result exactly like the ordinary `.completed` branch
        // further down handles a real execution.
        if pendingToolCall.toolCall.parseInputStrict() is ToolInputParse.Invalid {
            let invalidInputPending = ChatPendingToolApproval(
                toolCall: pendingToolCall.toolCall,
                providerSetting: providerSetting,
                params: params,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                baseMessages: baseMessages
            )
            let result = await toolRuntime.execute(pendingToolCall, context: invalidInputPending)
            guard currentRunId == runId else { return }
            guard case .completed(let resolvedMessages) = result else { return }
            bindings.setMessages(resolvedMessages)
            bindings.bumpMessageRevision(.toolResultAppended)
            await continueAfterToolResult(
                resolvedMessages,
                providerSetting: providerSetting,
                params: params,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }

        // I-5 打转守护:必须在 I-1 记账段之前判断——stop 分支什么都不执行,
        // 不该在账本里留下一条 Started 记录。maxToolResumeCount 是数量兜底,
        // 这里是浪费方式的第一道,两者独立共存。
        let loopGuardVerdict = currentToolLoopGuard.check(
            toolName: pendingToolCall.toolCall.toolName,
            input: pendingToolCall.toolCall.input
        )
        if case .stop(let reason) = loopGuardVerdict {
            await terminatePendingToolCalls(
                snapshot: baseMessages,
                failureText: reason,
                runStatus: "guard_stopped",
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }

        let pending = ChatPendingToolApproval(
            toolCall: pendingToolCall.toolCall,
            providerSetting: providerSetting,
            params: params,
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            baseMessages: baseMessages
        )
        let toolPresentation = AgentActivityPresentation.runningTool(
            toolName: pendingToolCall.toolCall.toolName
        )
        currentLiveActivityStage = toolPresentation.stage
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: conversationId?.toHexDashString(),
            presentation: toolPresentation
        )
        await dependencies.liveActivityController.update(
            runId: runId,
            presentation: toolPresentation,
            force: true
        )
        // Refresh the handoff snapshot right before the (potentially long) tool
        // HTTP call, so a background transition during tool execution hands off
        // a snapshot carrying this pending tool call. The background engine then
        // pre-executes it instead of re-prompting the model to re-issue it.
        refreshBackgroundHandoff(
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            providerSetting: providerSetting,
            backgroundProviderSetting: nil,
            params: params,
            uploadMessages: backgroundToolHandoffUploadMessages(
                from: baseMessages,
                params: params,
                runId: runId
            ),
            displayMessages: bindings.getMessages(),
            generativeUiRequirement: currentGenerativeUiRequirement,
            generativeUiFallbackAttempted: currentGenerativeUiFallbackAttempted
        )

        // I-1 durable boundary ("先记账，后动手"): persist the assistant message
        // carrying this tool call, THEN record the ledger's Started entry —
        // BOTH before the Task below is even created, since a Swift `Task {}`
        // starts running immediately, not lazily on first await. Mirrors
        // `pauseForApproval`'s persist-before-hold-state discipline (same
        // baseline/persistMessagesSnapshot pair); this is the automatic-tool
        // sibling of that path, closing the asymmetry W1 exists to fix. Either
        // step failing means the tool must NOT run — a slow durable "did we
        // call it" record beats a fast one that isn't there after a crash.
        let writeBaseline = bindings.capturePersistMessagesBaseline(conversationId)
        let didPersistBeforeExecution = await bindings.persistMessagesSnapshot(
            baseMessages,
            conversationId,
            writeBaseline
        )
        guard currentRunId == runId else { return }
        guard didPersistBeforeExecution else {
            await presentStreamError(
                rawMessage: "无法保存工具执行前状态，请检查存储空间后重试。",
                modelId: params.model.modelId,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }
        let toolCallEffectClass = IOSToolEffectClassMapping.forChatKind(
            pendingToolCall.kind,
            input: pendingToolCall.toolCall.input
        )
        let didRecordToolCallStarted = await toolLedger.recordToolCallStarted(
            runId: runId,
            toolCallId: pendingToolCall.toolCall.toolCallId,
            toolName: pendingToolCall.toolCall.toolName,
            argsDigest: chatInputDigest(for: pendingToolCall.toolCall.input),
            effectClass: toolCallEffectClass
        )
        guard currentRunId == runId else { return }
        guard didRecordToolCallStarted else {
            await presentStreamError(
                rawMessage: "无法保存工具执行前状态，请检查存储空间后重试。",
                modelId: params.model.modelId,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }

        let executionToken = UUID()
        let executionTask = Task { @MainActor [toolRuntime] in
            await toolRuntime.execute(pendingToolCall, context: pending)
        }
        foregroundToolExecutionToken = executionToken
        foregroundToolExecutionTask = executionTask
        let result = await executionTask.value
        clearForegroundToolExecution(matching: executionToken)

        // F10 fix (① automatic path): the tool genuinely ran — or genuinely
        // discovered mid-dispatch that it needs approval — by this point,
        // regardless of whether `currentRunId` still matches `runId`. Record
        // the honest Finished outcome for THIS attempt first, unconditionally,
        // BEFORE the run-liveness guard below. The old order (guard first)
        // meant a run replaced while the tool was executing left a dangling
        // Started with no Finished even though the tool call fully completed
        // — W3's classifier reads that as a phantom "outcome unknown" crash
        // for a tool that in fact finished cleanly. Only the UI/stream
        // continuation after this switch is gated on run liveness.
        switch result {
        case .completed:
            await toolLedger.recordToolCallFinished(
                runId: runId,
                toolCallId: pendingToolCall.toolCall.toolCallId,
                outcome: "completed"
            )
        case .waitingForApproval:
            // Honest narrative (I-1/I-3): this attempt did not execute the
            // tool — it discovered mid-dispatch that the tool needs user
            // approval and stopped. Finished(paused_for_approval) closes THIS
            // Started; the real execution happens later when the user
            // approves, which writes its own fresh Started/Finished pair (see
            // `executeApprovedAsyncTool`/`finishPendingMemoryToolApproval`)
            // under the same toolCallId. S3's pairing rule ("take the last
            // Started for a toolCallId, check for a Finished after it") relies
            // on this Finished existing so the first pair reads as `.clean`
            // rather than a phantom crash.
            await toolLedger.recordToolCallFinished(
                runId: runId,
                toolCallId: pendingToolCall.toolCall.toolCallId,
                outcome: "paused_for_approval"
            )
        }

        guard currentRunId == runId else { return }

        switch result {
        case .completed(let executedMessages):
            // I-5 第 2 次相同签名:工具照常执行,但把提醒追加进这次调用的
            // 输出(append,不替换原结果),让模型下一轮看到自己在重复。
            let resumedMessages: [UIMessage]
            if case .proceedAndRemind(let reminder) = loopGuardVerdict {
                resumedMessages = appendingToolLoopReminder(
                    reminder,
                    toToolCallId: pendingToolCall.toolCall.toolCallId,
                    in: executedMessages
                )
            } else {
                resumedMessages = executedMessages
            }
            bindings.setMessages(resumedMessages)
            bindings.bumpMessageRevision(.toolResultAppended)
            await continueAfterToolResult(
                resumedMessages,
                providerSetting: providerSetting,
                params: params,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
        case .waitingForApproval(let prompt):
            // Finished(paused_for_approval) was already recorded above (F10
            // fix), unconditionally, before this run-liveness guard.
            //
            // F8 fix: this is the ONE place `loopGuardVerdict` is known for an
            // approval-gated tool call — capture the reminder now, keyed by
            // toolCallId, so it survives however long the user takes to
            // answer. `resumeAfterApproval` cannot read `loopGuardVerdict`
            // itself (it doesn't carry `allow`/deny, and isn't in scope of
            // this local), so each of the 8 approval-finishing call sites
            // consumes this dictionary directly once they know the outcome.
            if case .proceedAndRemind(let reminder) = loopGuardVerdict {
                pendingLoopReminders[pendingToolCall.toolCall.toolCallId] = reminder
            }
            await pauseForApproval(prompt, pending: pending)
        }
    }

    private func continueAfterToolResult(
        _ resumedMessages: [UIMessage],
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?
    ) async {
        guard currentRunId == runId else { return }

        let availableToolNames = Set(params.tools.map(\.name))
        if let pendingToolCall = toolRuntime.nextPendingToolCall(
            in: resumedMessages,
            availableToolNames: availableToolNames
        ) {
            guard currentToolResumeCount < maxToolResumeCount else {
                await failPendingToolCalls(
                    snapshot: resumedMessages,
                    failureText: "工具调用未执行：已达到本轮工具循环上限（\(maxToolResumeCount) 次）。请继续对话或拆分任务后重试。",
                    runId: runId,
                    startedAt: startedAt,
                    inputDigest: inputDigest,
                    conversationId: conversationId
                )
                return
            }

            currentToolResumeCount += 1
            bindings.bumpMessageRevision(.toolCallStarted)
            streamJob = nil
            await executeToolCall(
                pendingToolCall,
                providerSetting: providerSetting,
                params: params,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                baseMessages: resumedMessages
            )
            return
        }

        if toolRuntime.hasUnresolvedToolCall(in: resumedMessages) {
            await failPendingToolCalls(
                snapshot: resumedMessages,
                failureText: "工具调用未执行：该工具当前未启用或不可执行。请在设置中启用对应能力后重试。",
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }

        let continuationParams = Self.continuationParamsAfterToolExecution(
            params,
            resumeCount: currentToolResumeCount,
            maxResumeCount: maxToolResumeCount
        )
        refreshBackgroundHandoff(
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            providerSetting: providerSetting,
            backgroundProviderSetting: nil,
            params: continuationParams,
            uploadMessages: backgroundToolHandoffUploadMessages(
                from: resumedMessages,
                params: continuationParams,
                runId: runId
            ),
            displayMessages: resumedMessages,
            generativeUiRequirement: currentGenerativeUiRequirement,
            generativeUiFallbackAttempted: currentGenerativeUiFallbackAttempted
        )
        let generating = AgentActivityPresentation.response(stage: .generating)
        currentLiveActivityStage = generating.stage
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: conversationId?.toHexDashString(),
            presentation: generating
        )
        await dependencies.liveActivityController.update(
            runId: runId,
            presentation: generating,
            force: true
        )
        await prepareAndStartStreaming(
            providerSetting: providerSetting,
            params: continuationParams,
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            uploadMessages: resumedMessages
        )
    }

    static func continuationParamsAfterToolExecution(
        _ params: TextGenerationParams,
        resumeCount: Int,
        maxResumeCount: Int
    ) -> TextGenerationParams {
        guard resumeCount >= maxResumeCount, !params.tools.isEmpty else { return params }
        return TextGenerationParams(
            model: params.model,
            temperature: params.temperature,
            topP: params.topP,
            maxTokens: params.maxTokens,
            tools: [],
            reasoningLevel: params.reasoningLevel,
            customHeaders: params.customHeaders,
            customBody: params.customBody
        )
    }

    private func pauseForApproval(
        _ prompt: ChatToolApprovalPrompt,
        pending: ChatPendingToolApproval
    ) async {
        guard currentRunId == pending.runId else { return }
        let writeBaseline = bindings.capturePersistMessagesBaseline(pending.conversationId)
        switch prompt {
        case .memory(let request):
            pendingMemoryToolApproval = pending
            bindings.setPendingMemoryApproval(request)
        case .search(let request):
            pendingSearchToolApproval = pending
            bindings.setPendingSearchApproval(request)
        case .webMount(let request):
            pendingWebMountToolApproval = pending
            bindings.setPendingWebMountApproval(request)
        case .workspace(let request):
            pendingWorkspaceToolApproval = pending
            bindings.setPendingWorkspaceApproval(request)
        case .ish(let request):
            pendingIshHandoffToolApproval = pending
            bindings.setPendingIshHandoffApproval(request)
        case .mcp(let request):
            pendingMcpToolApproval = pending
            bindings.setPendingMcpApproval(request)
        case .council(let request):
            pendingCouncilToolApproval = pending
            bindings.setPendingCouncilApproval(request)
        case .askUser(let request):
            pendingAskUserToolApproval = pending
            bindings.setPendingAskUser(request)
        }
        bindings.setMessages(pending.baseMessages)
        bindings.bumpMessageRevision(.awaitingToolApproval)
        let didPersistApprovalOwner = await bindings.markRunAwaitingPermission(
            pending.runId,
            pending.toolCall.toolCallId
        )
        guard currentRunId == pending.runId else { return }
        guard didPersistApprovalOwner else {
            clearPendingApprovals()
            await presentStreamError(
                rawMessage: "无法保存待确认恢复信息，请重试。",
                modelId: pending.params.model.modelId,
                runId: pending.runId,
                startedAt: pending.startedAt,
                inputDigest: pending.inputDigest,
                conversationId: pending.conversationId
            )
            return
        }
        let didPersist = await bindings.persistMessagesSnapshot(
            pending.baseMessages,
            pending.conversationId,
            writeBaseline
        )
        guard currentRunId == pending.runId else { return }
        guard didPersist else {
            clearPendingApprovals()
            await presentStreamError(
                rawMessage: "无法保存待确认状态，请检查存储空间后重试。",
                modelId: pending.params.model.modelId,
                runId: pending.runId,
                startedAt: pending.startedAt,
                inputDigest: pending.inputDigest,
                conversationId: pending.conversationId
            )
            return
        }

        // 等人点按钮不需要后台执行权——这一轮此刻不在算，在等人。必须等可见
        // baseMessages 已经耐久保存后再还租约，避免挂起/杀进程后连待确认节点都丢失。
        BackgroundGenerationKeepAlive.shared.end(pending.runId)
        bindings.setIsLoading(false)
        currentLiveActivityStage = .waitingForConfirmation
        if case .askUser(let request) = prompt {
            WatchTaskCoordinator.shared.publishAskUser(
                runId: pending.runId,
                conversationId: pending.conversationId?.toHexDashString(),
                request: WatchAskUserRequest(
                    id: request.id,
                    question: request.question,
                    options: request.options
                )
            )
        } else {
            let conversationHex = pending.conversationId?.toHexDashString()
            WatchTaskCoordinator.shared.publishWaitingApproval(
                runId: pending.runId,
                conversationId: conversationHex,
                prompt: prompt
            )
        }
        await dependencies.liveActivityController.update(
            runId: pending.runId,
            presentation: .waitingForUser(kind: prompt.activityKind),
            force: true
        )
    }

    // finishMemoryApproval is a synchronous, in-process, sub-millisecond write
    // (no network round trip like the other approval kinds), so this entry
    // point stays sync-signatured to avoid rippling `await` up through
    // ChatViewModel/ChatView's button actions (out of W1's scope). The I-1
    // Started/Finished pair is still recorded, sequenced inside a Task so
    // Started durably lands before `finishMemoryApproval` runs.
    private func finishPendingMemoryToolApproval(writePolicy: IOSMemoryToolWritePolicy) {
        guard let pending = pendingMemoryToolApproval else { return }
        clearPendingMemoryApproval()
        guard currentRunId == pending.runId else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            let didRecordStart = await self.toolLedger.recordToolCallStarted(
                runId: pending.runId,
                toolCallId: pending.toolCall.toolCallId,
                toolName: pending.toolCall.toolName,
                argsDigest: chatInputDigest(for: pending.toolCall.input),
                effectClass: IOSToolEffectClassMapping.forChatKind(
                    .memory,
                    input: pending.toolCall.input
                )
            )
            guard didRecordStart else {
                await self.presentStreamError(
                    rawMessage: "无法保存工具执行前状态，请检查存储空间后重试。",
                    modelId: pending.params.model.modelId,
                    runId: pending.runId,
                    startedAt: pending.startedAt,
                    inputDigest: pending.inputDigest,
                    conversationId: pending.conversationId
                )
                return
            }
            // F3 fix: same window as F2 — `recordToolCallStarted` above is a
            // suspension point; the run may have been replaced by the time it
            // resumes here. Close out Started before running the side effect.
            guard self.currentRunId == pending.runId else {
                await self.toolLedger.recordToolCallFinished(
                    runId: pending.runId,
                    toolCallId: pending.toolCall.toolCallId,
                    outcome: "not_executed_run_replaced"
                )
                return
            }
            let executedMessages = self.toolRuntime.finishMemoryApproval(
                pending: pending,
                writePolicy: writePolicy
            )
            await self.toolLedger.recordToolCallFinished(
                runId: pending.runId,
                toolCallId: pending.toolCall.toolCallId,
                outcome: "completed"
            )
            guard self.currentRunId == pending.runId else { return }
            let resumedMessages = self.consumingPendingLoopReminder(
                for: pending.toolCall.toolCallId,
                in: executedMessages
            )
            self.bindings.setMessages(resumedMessages)
            self.bindings.bumpMessageRevision(.toolResultAppended)
            self.resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
        }
    }

    /// F8 fix: removes (and, if present, applies) a pending I-5 loop-guard
    /// reminder for `toolCallId`. Always removes the entry regardless of
    /// whether it was applied, so `pendingLoopReminders` never leaks a stale
    /// reminder for a toolCallId that's already been resolved — approved,
    /// denied, or answered.
    private func consumingPendingLoopReminder(
        for toolCallId: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        guard let reminder = pendingLoopReminders.removeValue(forKey: toolCallId) else { return messages }
        return appendingToolLoopReminder(reminder, toToolCallId: toolCallId, in: messages)
    }

    private func finishPendingSearchToolApproval(allow: Bool) async {
        guard let pending = pendingSearchToolApproval else { return }
        guard currentRunId == pending.runId else { return }
        clearPendingSearchApproval()
        guard let executedMessages = await executeApprovedAsyncTool(pending: pending, allow: allow, effectClass: .pure, operation: {
            await self.toolRuntime.finishSearchApproval(pending: pending, allow: allow)
        }) else { return }
        let resumedMessages = consumingPendingLoopReminder(for: pending.toolCall.toolCallId, in: executedMessages)
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision(.toolResultAppended)
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingWebMountToolApproval(allow: Bool) async {
        guard let pending = pendingWebMountToolApproval else { return }
        guard currentRunId == pending.runId else { return }
        clearPendingWebMountApproval()
        guard let executedMessages = await executeApprovedAsyncTool(pending: pending, allow: allow, effectClass: .sideEffect, operation: {
            await self.toolRuntime.finishWebMountApproval(pending: pending, allow: allow)
        }) else { return }
        let resumedMessages = consumingPendingLoopReminder(for: pending.toolCall.toolCallId, in: executedMessages)
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision(.toolResultAppended)
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingWorkspaceToolApproval(allow: Bool) async {
        guard let pending = pendingWorkspaceToolApproval else { return }
        guard currentRunId == pending.runId else { return }
        clearPendingWorkspaceApproval()
        guard let executedMessages = await executeApprovedAsyncTool(pending: pending, allow: allow, effectClass: .sideEffect, operation: {
            await self.toolRuntime.finishWorkspaceApproval(pending: pending, allow: allow)
        }) else { return }
        let resumedMessages = consumingPendingLoopReminder(for: pending.toolCall.toolCallId, in: executedMessages)
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision(.toolResultAppended)
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingIshHandoffToolApproval(allow: Bool) async {
        guard let pending = pendingIshHandoffToolApproval else { return }
        guard currentRunId == pending.runId else { return }
        clearPendingIshHandoffApproval()
        guard let executedMessages = await executeApprovedAsyncTool(pending: pending, allow: allow, effectClass: .sideEffect, operation: {
            await self.toolRuntime.finishIshHandoffApproval(pending: pending, allow: allow)
        }) else { return }
        let resumedMessages = consumingPendingLoopReminder(for: pending.toolCall.toolCallId, in: executedMessages)
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision(.toolResultAppended)
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingMcpToolApproval(allow: Bool) async {
        guard let pending = pendingMcpToolApproval else { return }
        guard currentRunId == pending.runId else { return }
        clearPendingMcpApproval()
        guard let executedMessages = await executeApprovedAsyncTool(pending: pending, allow: allow, effectClass: .sideEffect, operation: {
            await self.toolRuntime.finishMcpApproval(pending: pending, allow: allow)
        }) else { return }
        let resumedMessages = consumingPendingLoopReminder(for: pending.toolCall.toolCallId, in: executedMessages)
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision(.toolResultAppended)
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingCouncilToolApproval(allow: Bool) async {
        guard let pending = pendingCouncilToolApproval else { return }
        guard currentRunId == pending.runId else { return }
        clearPendingCouncilApproval()
        guard let executedMessages = await executeApprovedAsyncTool(pending: pending, allow: allow, effectClass: .sideEffect, operation: {
            await self.toolRuntime.finishCouncilApproval(pending: pending, allow: allow)
        }) else { return }
        let resumedMessages = consumingPendingLoopReminder(for: pending.toolCall.toolCallId, in: executedMessages)
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision(.toolResultAppended)
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    @discardableResult
    func answerPendingAskUser(_ answer: String) -> Bool {
        finishPendingAskUserAnswer(answer: answer)
    }

    // ask_user has no side effect at all (effectClass .pure) and
    // `finishAskUserAnswer` is a synchronous local computation, so — like
    // memory above — the public signature stays sync (its Bool return is
    // consulted synchronously by Watch call sites) while the ledger pair is
    // recorded inside a Task, ordered ahead of the local write.
    @discardableResult
    private func finishPendingAskUserAnswer(answer: String) -> Bool {
        guard let pending = pendingAskUserToolApproval else { return false }
        // Validate ownership before clearing UI/pending. Otherwise a racing cancel or
        // run replacement can drop the card without writing tool output.
        guard currentRunId == pending.runId else { return false }
        clearPendingAskUser()
        Task { @MainActor [weak self] in
            guard let self else { return }
            let didRecordStart = await self.toolLedger.recordToolCallStarted(
                runId: pending.runId,
                toolCallId: pending.toolCall.toolCallId,
                toolName: pending.toolCall.toolName,
                argsDigest: chatInputDigest(for: pending.toolCall.input),
                effectClass: .pure
            )
            guard didRecordStart else {
                await self.presentStreamError(
                    rawMessage: "无法保存工具执行前状态，请检查存储空间后重试。",
                    modelId: pending.params.model.modelId,
                    runId: pending.runId,
                    startedAt: pending.startedAt,
                    inputDigest: pending.inputDigest,
                    conversationId: pending.conversationId
                )
                return
            }
            // F3 fix: same window as F2/memory above — the run may have been
            // replaced while `recordToolCallStarted` was suspended.
            guard self.currentRunId == pending.runId else {
                await self.toolLedger.recordToolCallFinished(
                    runId: pending.runId,
                    toolCallId: pending.toolCall.toolCallId,
                    outcome: "not_executed_run_replaced"
                )
                return
            }
            let executedMessages = self.toolRuntime.finishAskUserAnswer(
                pending: pending,
                answer: answer
            )
            await self.toolLedger.recordToolCallFinished(
                runId: pending.runId,
                toolCallId: pending.toolCall.toolCallId,
                outcome: "completed"
            )
            guard self.currentRunId == pending.runId else { return }
            let resumedMessages = self.consumingPendingLoopReminder(
                for: pending.toolCall.toolCallId,
                in: executedMessages
            )
            self.bindings.setMessages(resumedMessages)
            self.bindings.bumpMessageRevision(.toolResultAppended)
            self.resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
        }
        return true
    }

    private func executeApprovedAsyncTool(
        pending: ChatPendingToolApproval,
        allow: Bool,
        effectClass: IOSToolEffectClass,
        operation: @escaping @MainActor () async -> [UIMessage]
    ) async -> [UIMessage]? {
        // A denial never reaches the tool — `operation()` here only produces a
        // "user denied" JSON result, no side effect occurs, so there is nothing
        // for the ledger to durably record.
        guard allow else { return await operation() }

        // I-1, resumption half: this is the real (post-approval) execution of a
        // tool call the automatic path already Started→Finished(paused_for_approval)
        // once. `pauseForApproval` persisted the awaiting-approval snapshot before
        // we ever got here, so — unlike the automatic path — there is no separate
        // "persist baseMessages" step; the durable state this resumption builds on
        // is already on disk. What's missing without this is a Started record for
        // THIS attempt, under the same toolCallId, before the side effect below.
        let didRecordStart = await toolLedger.recordToolCallStarted(
            runId: pending.runId,
            toolCallId: pending.toolCall.toolCallId,
            toolName: pending.toolCall.toolName,
            argsDigest: chatInputDigest(for: pending.toolCall.input),
            effectClass: effectClass
        )
        guard didRecordStart else {
            await presentStreamError(
                rawMessage: "无法保存工具执行前状态，请检查存储空间后重试。",
                modelId: pending.params.model.modelId,
                runId: pending.runId,
                startedAt: pending.startedAt,
                inputDigest: pending.inputDigest,
                conversationId: pending.conversationId
            )
            return nil
        }
        // F2 fix: `recordToolCallStarted` above is this function's first
        // `await` — the run this approval belonged to may have been replaced
        // (cancel()/new start()) while it was suspended. Without this check,
        // `setIsLoading(true)`/`beginKeepAlive` below would run for a dead
        // run (loading spinner stuck forever, a KeepAlive lease leaked under
        // the new run's runId), AND the side effect below would execute on
        // behalf of a run nobody is listening to anymore. Close out the
        // Started we just wrote — Finished(outcome: "not_executed_run_replaced")
        // — so W3's classifier reads this pair as `.clean`, never a phantom
        // "outcome unknown" crash.
        guard currentRunId == pending.runId else {
            await toolLedger.recordToolCallFinished(
                runId: pending.runId,
                toolCallId: pending.toolCall.toolCallId,
                outcome: "not_executed_run_replaced"
            )
            return nil
        }

        bindings.setIsLoading(true)
        beginKeepAlive(for: pending)

        let executionToken = UUID()
        let result = await withCheckedContinuation { continuation in
            foregroundApprovedToolContinuation = continuation
            let executionTask: Task<ChatToolRuntimeResult, Never> = Task { @MainActor in
                let result = ChatToolRuntimeResult.completed(await operation())
                self.completeApprovedToolExecution(result, matching: executionToken)
                return result
            }
            foregroundToolExecutionToken = executionToken
            foregroundToolExecutionTask = executionTask
        }
        defer { clearForegroundToolExecution(matching: executionToken) }
        // F10 fix (② approval path): `result` is `nil` exactly when
        // `cancelForegroundToolExecutions` resumed this continuation early —
        // the run was cancelled while `operation()` may still genuinely be
        // running in the background (its real completion, if any, arrives
        // later via `completeApprovedToolExecution`, whose token guard will
        // by then no longer match and silently drop it). At that point the
        // side effect's outcome is NOT known to be "completed" — writing
        // Finished("completed") unconditionally here was a false record. A
        // dangling Started (no Finished) is the honest "outcome unknown"
        // state W3's crash-recovery classifier already knows how to read;
        // only write Finished when this attempt actually produced a result.
        if result != nil {
            await toolLedger.recordToolCallFinished(
                runId: pending.runId,
                toolCallId: pending.toolCall.toolCallId,
                outcome: "completed"
            )
        }
        guard currentRunId == pending.runId, let result else { return nil }
        guard case .completed(let resumedMessages) = result else { return nil }
        refreshBackgroundHandoff(
            runId: pending.runId,
            startedAt: pending.startedAt,
            inputDigest: pending.inputDigest,
            conversationId: pending.conversationId,
            providerSetting: pending.providerSetting,
            backgroundProviderSetting: nil,
            params: pending.params,
            uploadMessages: backgroundToolHandoffUploadMessages(
                from: resumedMessages,
                params: pending.params,
                runId: pending.runId
            ),
            displayMessages: resumedMessages,
            generativeUiRequirement: currentGenerativeUiRequirement,
            generativeUiFallbackAttempted: currentGenerativeUiFallbackAttempted
        )
        return resumedMessages
    }

    private func beginKeepAlive(for pending: ChatPendingToolApproval) {
        BackgroundGenerationKeepAlive.shared.begin(
            pending.runId,
            title: "Amber 正在生成",
            subtitle: pending.params.model.displayName,
            onExpire: { [weak self] in
                guard let self, self.currentRunId == pending.runId else { return }
                _ = self.handleKeepAliveExpiration(
                    runId: pending.runId,
                    handoff: {
                        self.handoffCurrentGenerationToBackground(
                            conversationStore: self.pendingBackgroundConversationStore
                        )
                    }
                )
            },
            onSystemTaskExpiration: { [weak self] in
                self?.cancelRunAfterSystemKeepAliveExpiration(pending.runId)
            }
        )
    }

    private func resumeAfterApproval(
        pending: ChatPendingToolApproval,
        resumedMessages: [UIMessage]
    ) {
        guard currentRunId == pending.runId else { return }
        guard dependencies.autoGenerateResponses else {
            Task { @MainActor [weak self] in
                guard let self, self.currentRunId == pending.runId else { return }
                let didPersist = await self.bindings.persistMessages(pending.conversationId)
                guard self.currentRunId == pending.runId else { return }
                let conversationHex = pending.conversationId?.toHexDashString()
                await self.bindings.recordRun(
                    pending.runId,
                    pending.startedAt,
                    didPersist ? "completed" : "recovery_pending",
                    pending.inputDigest,
                    conversationHex
                )
                if didPersist {
                    WatchTaskCoordinator.shared.publishCompleted(
                        runId: pending.runId,
                        conversationId: conversationHex,
                        summary: nil
                    )
                } else {
                    WatchTaskCoordinator.shared.publish(
                        runId: pending.runId,
                        conversationId: conversationHex,
                        presentation: .failed(),
                        summary: "工具结果已生成，但最终状态保存失败。"
                    )
                }
                await self.dependencies.liveActivityController.end(
                    runId: pending.runId,
                    presentation: didPersist ? .completed() : .failed()
                )
                let didFinish = self.finishStreaming(
                    runId: pending.runId,
                    terminalEvent: didPersist ? .generationCompleted : .generationFailed
                )
                if didPersist && didFinish {
                    self.bindings.generationSucceeded()
                }
            }
            return
        }

        bindings.setIsLoading(true)
        // 重新拿执行权：审批往往横跨退后台，pauseForApproval 已经把租约还了。
        // 手表端批准更是典型——批准时 App 就在后台，不拿回来这一轮就是裸奔的。
        beginKeepAlive(for: pending)
        let generating = AgentActivityPresentation.response(
            stage: .generating
        )
        currentLiveActivityStage = generating.stage
        bindings.startLiveActivity(
            pending.runId,
            pending.conversationId,
            generating
        )
        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.continueAfterToolResult(
                resumedMessages,
                providerSetting: pending.providerSetting,
                params: pending.params,
                runId: pending.runId,
                startedAt: pending.startedAt,
                inputDigest: pending.inputDigest,
                conversationId: pending.conversationId
            )
        }
    }

    private func dispatchStream(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onChunk: @escaping (MessageChunk) -> Void,
        onComplete: @escaping () -> Void,
        onError: @escaping (KotlinThrowable) -> Void
    ) -> Kotlinx_coroutines_coreJob? {
        if let openAI = providerSetting as? ProviderSetting.OpenAI {
            if IOSGrokWebProviderResolver.isGrokWebConfiguration(openAI) {
                grokWebStreamTask?.cancel()
                let providerId = IOSGrokWebProviderResolver.providerKey(openAI)
                grokWebStreamTask = Task {
                    do {
                        try await IOSGrokWebClient(providerId: providerId).streamText(
                            messages: messages,
                            params: params,
                            onChunk: onChunk
                        )
                        guard !Task.isCancelled else { return }
                        onComplete()
                    } catch is CancellationError {
                        return
                    } catch {
                        guard !Task.isCancelled else { return }
                        onError(KotlinThrowable(message: (error as NSError).localizedDescription))
                    }
                }
                return nil
            }
            return provider.streamTextCancellable(
                providerSetting: openAI,
                messages: messages,
                params: params,
                onChunk: onChunk,
                onComplete: onComplete,
                onError: onError
            )
        }
        if let claude = providerSetting as? ProviderSetting.Claude {
            return claudeProvider.streamTextCancellable(
                providerSetting: claude,
                messages: messages,
                params: params,
                onChunk: onChunk,
                onComplete: onComplete,
                onError: onError
            )
        }
        onError(KotlinThrowable(message: "当前服务商类型暂不支持聊天"))
        return nil
    }

    @discardableResult
    private func finishStreaming(
        runId: String,
        terminalEvent: ChatMessageUpdateReason? = nil
    ) -> Bool {
        guard currentRunId == runId else { return false }
        cancelPendingStreamSnapshotPublish()
        cancelStreamEventConsumer()
        // 前台把这一轮跑完了，执行权到此为止。
        BackgroundGenerationKeepAlive.shared.end(runId)
        ChatStreamRecorder.shared.finish(runId: runId)
        grokWebStreamTask?.cancel()
        grokWebStreamTask = nil
        currentRunId = nil
        currentRunSnapshot = nil
        currentToolLoopGuard = IOSToolLoopGuard()
        currentLiveActivityStage = nil
        currentStartedAt = nil
        currentInputDigest = nil
        currentConversationIdForRun = nil
        currentGenerativeUiRequirement = .none
        currentGenerativeUiFallbackAttempted = false
        streamJob = nil
        clearForegroundToolExecutions()
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        bindings.setIsLoading(false)
        clearPendingApprovals()
        if let terminalEvent {
            bindings.bumpMessageRevision(terminalEvent)
        }
        return true
    }

    private func cancelStreamEventConsumer() {
        streamEventSink?.finish()
        streamEventSink = nil
        streamEventTask?.cancel()
        streamEventTask = nil
        activeStreamSession = nil
    }

    private func cancelForegroundToolExecutions() {
        foregroundToolExecutionTask?.cancel()
        foregroundToolExecutionTask = nil
        foregroundToolExecutionToken = nil
        let approvedToolContinuation = foregroundApprovedToolContinuation
        foregroundApprovedToolContinuation = nil
        approvedToolContinuation?.resume(returning: nil)
        foregroundImageToolExecutionTask?.cancel()
        foregroundImageToolExecutionTask = nil
        foregroundImageToolExecutionToken = nil
    }

    private func clearForegroundToolExecutions() {
        foregroundToolExecutionTask = nil
        foregroundToolExecutionToken = nil
        foregroundImageToolExecutionTask = nil
        foregroundImageToolExecutionToken = nil
    }

    private func clearForegroundToolExecution(matching token: UUID) {
        guard foregroundToolExecutionToken == token else { return }
        foregroundToolExecutionTask = nil
        foregroundToolExecutionToken = nil
    }

    private func completeApprovedToolExecution(
        _ result: ChatToolRuntimeResult,
        matching token: UUID
    ) {
        guard foregroundToolExecutionToken == token else { return }
        let continuation = foregroundApprovedToolContinuation
        foregroundApprovedToolContinuation = nil
        continuation?.resume(returning: result)
    }

    private func clearForegroundImageToolExecution(matching token: UUID) {
        guard foregroundImageToolExecutionToken == token else { return }
        foregroundImageToolExecutionTask = nil
        foregroundImageToolExecutionToken = nil
    }

    private func clearPendingApprovals() {
        clearPendingMemoryApproval()
        clearPendingSearchApproval()
        clearPendingWebMountApproval()
        clearPendingWorkspaceApproval()
        clearPendingIshHandoffApproval()
        clearPendingMcpApproval()
        clearPendingCouncilApproval()
        clearPendingAskUser()
        // F8 fix: called from all 3 `currentRunId = nil` teardown points
        // (cancel/finishStreaming/handoff-to-background) — a reminder keyed
        // to a toolCallId whose approval card just got wiped without ever
        // being answered must not survive into some future, unrelated run.
        pendingLoopReminders.removeAll()
    }

    private func clearPendingMemoryApproval() {
        pendingMemoryToolApproval = nil
        bindings.setPendingMemoryApproval(nil)
    }

    private func clearPendingSearchApproval() {
        pendingSearchToolApproval = nil
        bindings.setPendingSearchApproval(nil)
    }

    private func clearPendingWebMountApproval() {
        pendingWebMountToolApproval = nil
        bindings.setPendingWebMountApproval(nil)
    }

    private func clearPendingWorkspaceApproval() {
        pendingWorkspaceToolApproval = nil
        bindings.setPendingWorkspaceApproval(nil)
    }

    private func clearPendingIshHandoffApproval() {
        pendingIshHandoffToolApproval = nil
        bindings.setPendingIshHandoffApproval(nil)
    }

    private func clearPendingMcpApproval() {
        pendingMcpToolApproval = nil
        bindings.setPendingMcpApproval(nil)
    }

    private func clearPendingCouncilApproval() {
        pendingCouncilToolApproval = nil
        bindings.setPendingCouncilApproval(nil)
    }

    private func clearPendingAskUser() {
        pendingAskUserToolApproval = nil
        bindings.setPendingAskUser(nil)
    }

    private static func toolCalls(in chunk: MessageChunk) -> [UIMessagePart.Tool] {
        chunk.choices.flatMap { choice in
            (choice.delta ?? choice.message)?.parts.compactMap { $0 as? UIMessagePart.Tool } ?? []
        }
    }

    private static func responseStage(in chunk: MessageChunk) -> AgentActivityStage? {
        let parts = chunk.choices.flatMap { choice in
            (choice.delta ?? choice.message)?.parts ?? []
        }
        let hasTextDelta = parts.contains { part in
            guard let text = part as? UIMessagePart.Text else { return false }
            return text.text.contains { !$0.isWhitespace }
        }
        let hasReasoningDelta = parts.contains { part in
            guard let reasoning = part as? UIMessagePart.Reasoning else { return false }
            return !reasoning.reasoning.isEmpty
        }
        return AgentActivityResponseStagePolicy.updatedStage(
            hasReasoningDelta: hasReasoningDelta,
            hasTextDelta: hasTextDelta
        )
    }

    private func updateResponseLiveActivityStageIfNeeded(
        _ stage: AgentActivityStage,
        runId: String
    ) async {
        guard currentRunId == runId,
              let publishedStage = AgentActivityResponseStagePolicy.nextPublishedStage(
                  current: currentLiveActivityStage,
                  candidate: stage
              ) else { return }
        currentLiveActivityStage = publishedStage
        let presentation = AgentActivityPresentation.response(stage: publishedStage)
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: currentConversationIdForRun?.toHexDashString(),
            presentation: presentation
        )
        await dependencies.liveActivityController.update(
            runId: runId,
            presentation: presentation,
            force: true
        )
    }

    private func handleDetectedToolCalls(_ toolCalls: [UIMessagePart.Tool], runId: String) {
#if DEBUG
        for toolCall in toolCalls where IOSSearchExecutor.supportedToolNames.contains(toolCall.toolName) {
            print("[ChatViewModel] Detected search tool call runId=\(runId) tool=\(toolCall.toolName) toolCallId=\(toolCall.toolCallId) inputDigest=\(chatInputDigest(for: toolCall.input))")
        }
        for toolCall in toolCalls where IOSWebMountToolCatalog.supportedToolNames.contains(toolCall.toolName) {
            print("[ChatViewModel] Detected WebMount tool call runId=\(runId) toolCallId=\(toolCall.toolCallId) tool=\(toolCall.toolName)")
        }
        for toolCall in toolCalls where IOSWorkspaceToolCatalog.supportedToolNames.contains(toolCall.toolName) {
            print("[ChatViewModel] Detected Workspace tool call runId=\(runId) toolCallId=\(toolCall.toolCallId) tool=\(toolCall.toolName)")
        }
        for toolCall in toolCalls where IOSIshToolCatalog.supportedToolNames.contains(toolCall.toolName) {
            print("[ChatViewModel] Detected iSH handoff tool call runId=\(runId) toolCallId=\(toolCall.toolCallId)")
        }
        for toolCall in toolCalls where IOSEmbeddedIshToolCatalog.supportedToolNames.contains(toolCall.toolName) {
            print("[ChatViewModel] Detected embedded iSH tool call runId=\(runId) toolCallId=\(toolCall.toolCallId)")
        }
        for toolCall in toolCalls where toolCall.toolName == "memory_tool" {
            print("[ChatViewModel] Detected memory_tool call runId=\(runId) toolCallId=\(toolCall.toolCallId)")
        }
#endif
    }

#if DEBUG
    func installPendingSearchApprovalForTesting(
        pending: ChatPendingToolApproval,
        request: SearchToolApprovalRequest
    ) async {
        streamJob = nil
        currentRunId = pending.runId
        currentStartedAt = pending.startedAt
        currentInputDigest = pending.inputDigest
        currentConversationIdForRun = pending.conversationId
        await pauseForApproval(.search(request), pending: pending)
    }

    /// 模拟系统已通过 application-state gate 后收到的短腿到期结果；测试用它
    /// 注入交接失败，直接验证真实 cancel() 收口，而不是检查 source 字符串。
    @discardableResult
    func keepAliveExpirationForTesting(runId: String, didHandoff: Bool) -> Bool {
        finishKeepAliveExpiration(runId: runId, didHandoff: didHandoff)
    }

    func backgroundToolHandoffUploadMessagesForTesting(_ baseMessages: [UIMessage]) -> [UIMessage] {
        runtimeContextUploadMessages(from: baseMessages)
    }

    func backgroundToolHandoffUploadMessagesForTesting(
        _ baseMessages: [UIMessage],
        params: TextGenerationParams,
        runId: String
    ) -> [UIMessage] {
        backgroundToolHandoffUploadMessages(from: baseMessages, params: params, runId: runId)
    }

    @discardableResult
    func finishStreamingForTesting(runId: String) -> Bool {
        finishStreaming(runId: runId)
    }

    /// I-4 测试缝：`settingsSnapshot(forRun:)` 是 private,`IOSRunSnapshotTests`
    /// 通过这个薄包装验证冻结/新 turn 边界语义,不需要跑一整条真实生成流水线。
    func settingsSnapshotForTesting(runId: String) -> Settings {
        settingsSnapshot(forRun: runId)
    }

    /// I-4 测试缝：直接安装 `currentRunId`/`currentRunSnapshot`,模拟
    /// `start()`/`runImageTool()` 真实入口已经完成的赋值,不需要驱动完整的
    /// provider 解析 + 压缩 + 流式管线。
    func installRunSnapshotForTesting(runId: String, snapshot: ChatRunSnapshot?) {
        currentRunId = runId
        currentRunSnapshot = snapshot
    }

    func installRunMetadataForTesting(
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid? = nil
    ) {
        currentRunId = runId
        currentStartedAt = startedAt
        currentInputDigest = inputDigest
        currentConversationIdForRun = conversationId
    }

    func currentRunSnapshotForTesting() -> ChatRunSnapshot? {
        currentRunSnapshot
    }

    func finishedToolCallMessagesForTesting(
        _ targetToolCall: UIMessagePart.Tool,
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        toolRuntime.finishedToolCallMessagesForTesting(
            targetToolCall,
            outputText: outputText,
            in: messages
        )
    }

    func failingPendingToolCallMessagesForTesting(
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        toolRuntime.messagesByFailingPendingToolCalls(
            in: messages,
            outputText: outputText
        )
    }

    func memoryToolOutputForTesting(input: String) -> String {
        toolRuntime.memoryToolOutputForTesting(input: input)
    }

    func memoryApprovalRequestForTesting(input: String) -> MemoryToolApprovalRequest? {
        toolRuntime.memoryApprovalRequestForTesting(input: input)
    }

    func memoryToolApprovalOutputForTesting(input: String, allow: Bool) -> String {
        toolRuntime.memoryToolApprovalOutputForTesting(input: input, allow: allow)
    }

    func webMountToolOutputForTesting(
        toolName: String,
        input: String,
        isUserInitiated: Bool = false
    ) async -> String {
        await toolRuntime.webMountToolOutputForTesting(
            toolName: toolName,
            input: input,
            isUserInitiated: isUserInitiated
        )
    }

    func webMountApprovalRequestForTesting(
        toolName: String,
        input: String
    ) async -> WebMountToolApprovalRequest? {
        await toolRuntime.webMountApprovalRequestForTesting(
            toolName: toolName,
            input: input
        )
    }

    func webMountToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        await toolRuntime.webMountToolApprovalOutputForTesting(
            toolName: toolName,
            input: input,
            allow: allow
        )
    }

    func searchApprovalRequestForTesting(
        toolName: String,
        input: String
    ) -> SearchToolApprovalRequest? {
        toolRuntime.searchApprovalRequestForTesting(toolName: toolName, input: input)
    }

    func searchToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        await toolRuntime.searchToolApprovalOutputForTesting(
            toolName: toolName,
            input: input,
            allow: allow
        )
    }
#endif
}


extension ChatGenerationCoordinator {
    fileprivate static func watchSummary(from messages: [UIMessage]) -> String? {
        guard let lastAssistant = messages.last(where: { $0.role == MessageRole.assistant }) else {
            return nil
        }
        let text = lastAssistant.parts
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return WatchTaskText.clipped(text, maxLength: 280)
    }
}
