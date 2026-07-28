import Foundation
import Observation

struct NovelSessionBinding: Equatable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

struct NovelCharacterIdentityMention: Identifiable, Equatable, Sendable {
    let name: String
    var id: String { NovelCharacterIdentityResolver.normalize(name) }
}

/// 批量整章润色中单章的结果。
struct NovelBatchPolishChapterResult: Equatable, Sendable {
    enum Outcome: Equatable, Sendable {
        /// 通过漂移校验,已采用为该章新版本。
        case adopted
        /// 漂移校验判定润色改动了剧情事实,自动跳过(原文不变)。
        case skippedDrift
        /// 生成或采用失败。
        case failed
        /// 批量被停止时尚未轮到。
        case cancelled
    }

    let chapterID: NovelChapterID
    let title: String
    var outcome: Outcome
    var message: String? = nil
}

/// 批量整章润色的实时进度与最终报告。`phase == .running` 期间随观察更新,
/// 完成/停止后保留为报告,直到 `clearBatchPolish()` 清空回到选择态。
struct NovelBatchPolishProgress: Equatable, Sendable {
    enum Phase: Equatable, Sendable {
        case running
        case done
        case cancelled
    }

    let total: Int
    var completed: Int
    var currentTitle: String?
    var phase: Phase
    var results: [NovelBatchPolishChapterResult]
    let startedAt: Date

    var adoptedCount: Int { results.filter { $0.outcome == .adopted }.count }
    var skippedCount: Int { results.filter { $0.outcome == .skippedDrift }.count }
    var failedCount: Int { results.filter { $0.outcome == .failed }.count }
    var cancelledCount: Int { results.filter { $0.outcome == .cancelled }.count }
}

private struct NovelSessionRunDraft: Equatable, Sendable {
    let kind: NovelRunKind
    let mode: NovelSessionMode
    let granularity: NovelGenerationGranularity?
    let userText: String
    let sourceChapterVersionID: NovelChapterVersionID?
    let askUserResponse: NovelAskUserResponse?
    let injectionOverrides: NovelInjectionOverrides
    let inputBudgetTokens: Int
}

/// Absolute presentation target for one streaming run identity.
///
/// Deltas and replacements update `targetContent`; the UI only advances through
/// `NovelSessionPresentationPacer` so provider bursts do not dump multi-line
/// height steps into the sizeChanges-anchored list in a single flush.
struct NovelSessionPresentationBuffer {
    let runID: NovelRunID
    let messageID: NovelMessageID
    let bindingToken: UUID
    private(set) var targetContent: String

    init(
        runID: NovelRunID,
        messageID: NovelMessageID,
        bindingToken: UUID,
        baseContent: String = ""
    ) {
        self.runID = runID
        self.messageID = messageID
        self.bindingToken = bindingToken
        self.targetContent = baseContent
    }

    mutating func append(_ text: String) {
        targetContent += text
    }

    mutating func replace(with text: String) {
        targetContent = text
    }

    func matches(
        runID: NovelRunID,
        messageID: NovelMessageID,
        bindingToken: UUID
    ) -> Bool {
        self.runID == runID &&
            self.messageID == messageID &&
            self.bindingToken == bindingToken
    }
}

/// Pure presentation drain for novel streaming.
///
/// The per-tick advance policy is shared with Chat via
/// `StreamPresentationPacingPolicy` — this type only adapts it to the novel
/// session's single-String shape. Terminal paths still snap to the
/// authoritative full text.
enum NovelSessionPresentationPacer {
    static var minimumTextAdvance: Int { StreamPresentationPacingPolicy.minimumTextAdvance }
    static var maximumTextAdvance: Int { StreamPresentationPacingPolicy.maximumTextAdvance }
    static var preferredDrainTicks: Int { StreamPresentationPacingPolicy.preferredDrainTicks }

    struct Step: Equatable {
        let content: String
        let isCaughtUp: Bool
    }

    static func textAdvance(backlogCount: Int) -> Int {
        StreamPresentationPacingPolicy.textAdvance(backlogCount: backlogCount)
    }

    static func step(displayedContent: String, targetContent: String) -> Step {
        if displayedContent == targetContent {
            return Step(content: targetContent, isCaughtUp: true)
        }
        // Replacement / divergence: publish the authoritative target in one frame.
        guard targetContent.hasPrefix(displayedContent) else {
            return Step(content: targetContent, isCaughtUp: true)
        }
        let backlog = targetContent.count - displayedContent.count
        let advance = textAdvance(backlogCount: backlog)
        let next = String(targetContent.prefix(displayedContent.count + advance))
        return Step(content: next, isCaughtUp: next == targetContent)
    }
}

private struct NovelSessionProjectionTailKey: Equatable {
    let branchID: NovelBranchID
    let sessionID: NovelSessionID
    let runID: NovelRunID
    let startingUserContent: String?
    let messageID: NovelMessageID
    let phase: NovelSessionTransientTailPhase
}

private struct NovelSessionProjectionCacheKey: Equatable {
    let projectID: NovelProjectID
    let projectRevision: Int64
    let configRevision: Int64
    let projectAccess: NovelProjectLoadAccess
    let branchID: NovelBranchID
    let branchHeadRevision: Int64
    let branchWorkingRevision: Int64
    let branchSyncStatus: NovelBranchSyncStatus
    let branchLifecycle: NovelBranchLifecycle
    let activeRunID: NovelRunID?
    let sessionID: NovelSessionID
    let sessionRevision: Int64
    let expandedArchiveIDs: Set<NovelMessageID>
    let transientTail: NovelSessionProjectionTailKey?

    init(
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot,
        expandedArchiveIDs: Set<NovelMessageID>,
        transientTail: NovelSessionTransientTail?
    ) {
        projectID = project.project.id
        projectRevision = project.project.revision
        configRevision = project.project.configRevision
        projectAccess = project.access
        branchID = branch.branch.id
        branchHeadRevision = branch.branch.headRevision
        branchWorkingRevision = branch.branch.workingRevision
        branchSyncStatus = branch.branch.syncStatus
        branchLifecycle = branch.branch.lifecycle
        activeRunID = branch.branch.activeRunID
        sessionID = branch.session.id
        sessionRevision = branch.session.revision
        self.expandedArchiveIDs = expandedArchiveIDs
        self.transientTail = transientTail.map {
            NovelSessionProjectionTailKey(
                branchID: $0.branchID,
                sessionID: $0.sessionID,
                runID: $0.runID,
                startingUserContent: $0.startingUserContent,
                messageID: $0.messageID,
                phase: $0.phase
            )
        }
    }
}

private struct NovelSessionProjectionCacheEntry {
    let key: NovelSessionProjectionCacheKey
    let model: NovelSessionListModel
}

@MainActor
@Observable
final class NovelSessionViewModel {
    var mode: NovelSessionMode = .writeProse
    var granularity: NovelGenerationGranularity = .wholeChapter
    private(set) var binding: NovelSessionBinding?
    private(set) var transientTail: NovelSessionTransientTail?
    private(set) var sessionStartingRunID: NovelRunID?
    private(set) var isPerformingAction = false
    private(set) var operationErrorMessage: String?
    private(set) var refreshErrorMessage: String?
    private(set) var lastFailure: NovelFailure?
    private(set) var batchPolishProgress: NovelBatchPolishProgress?

    @ObservationIgnored private let workspace: NovelCreationViewModel
    @ObservationIgnored private var consumerTask: Task<Void, Never>?
    @ObservationIgnored private var consumerID: UUID?
    @ObservationIgnored private var attachAttemptID: UUID?
    @ObservationIgnored private var attachingRunID: NovelRunID?
    @ObservationIgnored private var attachingBindingToken: UUID?
    @ObservationIgnored private var bindingToken = UUID()
    @ObservationIgnored private var currentRunDraft: NovelSessionRunDraft?
    @ObservationIgnored private var lastRetryDraft: NovelSessionRunDraft?
    @ObservationIgnored private var lastRetryRunID: NovelRunID?
    @ObservationIgnored private var transientRunRecord: NovelActiveRunRecord?
    @ObservationIgnored private var terminalAwaitingRefresh = false
    @ObservationIgnored private var cancelledStartRunIDs: Set<NovelRunID> = []
    @ObservationIgnored private var sessionActionOwnerID: UUID?
    @ObservationIgnored private var batchPolishTask: Task<Void, Never>?
    /// 批量循环自己发起单章润色时短暂置真,让 `canStart` 放行——否则折进 `isBusy` 的
    /// `isBatchPolishing` 会把批量自己的 `start` 也挡掉。只在 start 握手期间为真。
    @ObservationIgnored private var isBatchStartingRun = false
    @ObservationIgnored private var projectionCache: NovelSessionProjectionCacheEntry?
#if DEBUG
    @ObservationIgnored private(set) var fullProjectionBuildCountForTesting = 0
#endif
    @ObservationIgnored private var presentationBuffer: NovelSessionPresentationBuffer?
    @ObservationIgnored private var presentationFlushTask: Task<Void, Never>?
    /// 终态 tail 的延迟退役任务:完成后保留 tail 一个静窗再清空,避免完成瞬间整屏一跳。
    @ObservationIgnored private var terminalTailRetirementTask: Task<Void, Never>?
    /// 终态 tail 退役前的静窗时长,默认与底部跟随的 terminalQuietDelay 对齐(滚动落定
    /// 与 tail 退役同步)。测试可注入 0 走立即退役的快路径,保持旧的「完成即清空」契约。
    @ObservationIgnored private let terminalQuietDelay: TimeInterval
    /// 批量润色等待候选时的「run 落定」宽限窗:activeRunID 在 terminalAwaitingRefresh
    /// 窗口会短暂为 nil,落定判失败前必须先过这个窗。测试可注入小值加快失败用例。
    @ObservationIgnored private let batchPolishSettleGrace: TimeInterval
    @ObservationIgnored private let batchPolishCandidateTimeout: TimeInterval

    private static let presentationFlushDelayNanos: UInt64 = 48_000_000

    init(
        workspace: NovelCreationViewModel,
        terminalQuietDelay: TimeInterval = NovelSessionBottomFollowPolicy.terminalQuietDelay,
        batchPolishSettleGrace: TimeInterval = 5,
        batchPolishCandidateTimeout: TimeInterval = 900
    ) {
        self.workspace = workspace
        self.terminalQuietDelay = terminalQuietDelay
        self.batchPolishSettleGrace = batchPolishSettleGrace
        self.batchPolishCandidateTimeout = batchPolishCandidateTimeout
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              workspace.selectedProjectID == project.project.id,
              workspace.selectedBranchID == branch.branch.id else {
            return
        }
        binding = NovelSessionBinding(
            projectID: project.project.id,
            branchID: branch.branch.id
        )
        granularity = project.project.lastGenerationGranularity
        hydrateTerminalState()
    }

    var durableMessages: [NovelSessionMessageRecord] {
        guard snapshotMatchesBinding else { return [] }
        return workspace.branchSnapshot?.session.messages ?? []
    }

    var hasArchivableDiscussion: Bool {
        guard snapshotMatchesBinding,
              let session = workspace.branchSnapshot?.session else { return false }
        let previousSequence: Int64 = switch session.archiveCursor {
        case .through(let sequence): sequence
        case .empty, nil: -1
        }
        return session.messages.contains {
            $0.sequence > previousSequence &&
                $0.mode == .discussPlan &&
                ($0.kind == .userInput || $0.kind == .discussion)
        }
    }

    var pendingCharacterIdentityMentions: [NovelCharacterIdentityMention] {
        guard snapshotMatchesBinding,
              let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else { return [] }
        let state = branch.currentState
        let identities = workspace.activeMaterials.compactMap { material -> NovelCharacterIdentity? in
            guard material.kind == .character,
                  let revision = NovelPresentation.effectiveRevision(
                      for: material,
                      project: project,
                      branch: branch
                  ) else { return nil }
            return NovelCharacterIdentity(
                materialID: material.id,
                canonicalName: revision.title,
                aliases: material.aliases
            )
        }
        let resolver = NovelCharacterIdentityResolver(identities: identities)
        let unresolvedByKey = Dictionary(
            state.unresolvedEntityNames.map {
                (NovelCharacterIdentityResolver.normalize($0), $0)
            },
            uniquingKeysWith: { first, _ in first }
        )
        let eventIDs = Set(state.eventIDs)
        var namesByKey: [String: String] = [:]
        for event in project.events where eventIDs.contains(event.id) && event.kind.hasPrefix("character.") {
            for reference in event.entityReferences {
                let key = NovelCharacterIdentityResolver.normalize(reference)
                guard let unresolved = unresolvedByKey[key], !resolver.isKnown(reference) else { continue }
                namesByKey[key] = unresolved
            }
        }
        return namesByKey.values.sorted().map(NovelCharacterIdentityMention.init(name:))
    }

    var characterIdentityChoices: [(material: NovelMaterialRecord, title: String)] {
        guard let project = workspace.projectSnapshot else { return [] }
        return workspace.activeMaterials.compactMap { material in
            guard material.kind == .character,
                  let revision = NovelPresentation.effectiveRevision(
                      for: material,
                      project: project,
                      branch: workspace.branchSnapshot
                  ) else { return nil }
            return (material, revision.title)
        }.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    func projectedListModel(
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot,
        expandedArchiveIDs: Set<NovelMessageID> = []
    ) -> NovelSessionListModel? {
        guard binding?.projectID == project.project.id,
              binding?.branchID == branch.branch.id else { return nil }
        let key = NovelSessionProjectionCacheKey(
            project: project,
            branch: branch,
            expandedArchiveIDs: expandedArchiveIDs,
            transientTail: transientTail
        )
        if let cached = projectionCache, cached.key == key {
            guard let tail = transientTail else { return cached.model }
            if let updated = NovelSessionPresentation.updatingTransientTail(
                in: cached.model,
                with: tail
            ) {
                projectionCache = NovelSessionProjectionCacheEntry(key: key, model: updated)
                return updated
            }
        }
        #if DEBUG
        fullProjectionBuildCountForTesting += 1
        #endif
        let model = NovelSessionPresentation.project(NovelSessionProjectionInput(
            project: project,
            branch: branch,
            expandedArchiveIDs: expandedArchiveIDs,
            transientTail: transientTail
        ))
        projectionCache = NovelSessionProjectionCacheEntry(key: key, model: model)
        return model
    }

    var currentChapterVersions: [NovelChapterVersionRecord] {
        guard snapshotMatchesBinding,
              let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else { return [] }
        let versionsByID = Dictionary(uniqueKeysWithValues: project.chapterVersions.map { ($0.id, $0) })
        return branch.chapterSelections.compactMap { versionsByID[$0.versionID] }
    }

    var availableProseCandidates: [NovelCandidateRecord] {
        candidates(kind: .prose).filter { $0.status == .available }
    }

    var availablePolishCandidates: [NovelCandidateRecord] {
        candidates(kind: .polish).filter { $0.status == .available }
    }

    var branchPendingOperations: [NovelPendingOperationRecord] {
        guard let branchID = binding?.branchID else { return [] }
        return workspace.projectSnapshot?.pendingOperations.filter { $0.branchID == branchID } ?? []
    }

    var retryableBranchPendingOperations: [NovelPendingOperationRecord] {
        branchPendingOperations.filter { $0.status == .retryable }
    }

    var branchPolishTransactions: [NovelPendingPolishTransactionRecord] {
        guard let branchID = binding?.branchID else { return [] }
        return workspace.projectSnapshot?.polishTransactions.filter { $0.branchID == branchID } ?? []
    }

    var access: NovelProjectLoadAccess? {
        guard snapshotMatchesBinding else { return nil }
        return workspace.projectSnapshot?.access
    }

    var needsSync: Bool {
        workspace.branchSnapshot?.branch.syncStatus == .needsSync
    }

    var activeRunID: NovelRunID? {
        if terminalAwaitingRefresh { return nil }
        if let tail = transientTail {
            switch tail.phase {
            case .waitingForFirstToken, .streaming:
                return tail.runID
            case .persistenceBlocked, .terminalAwaitingRefresh, .interrupted, .failed:
                return nil
            }
        }
        return activeRun?.id ?? boundQuickStartStartingRun?.id
    }

    var isStarting: Bool {
        sessionStartingRunID != nil || boundQuickStartStartingRun != nil
    }

    var isRunning: Bool {
        activeRunID != nil
    }

    var isStreaming: Bool {
        guard !terminalAwaitingRefresh else { return false }
        return switch transientTail?.phase {
        case .waitingForFirstToken, .streaming: true
        case .persistenceBlocked, .terminalAwaitingRefresh, .interrupted, .failed, nil: false
        }
    }

    var isBusy: Bool {
        isStarting || isPerformingAction || workspace.isPerforming || isBatchPolishing
    }

    /// 批量整章润色是否正在进行。派生自进度阶段,随 `batchPolishProgress` 一起被观察;
    /// 折进 `isBusy` 后,现有 `canStart`、阅读器门禁与输入框禁用都自动挡住并发起跑。
    var isBatchPolishing: Bool {
        batchPolishProgress?.phase == .running
    }

    /// 发起批量润色的前置门禁,与阅读器单章 `polishBlockReason` 同义。
    var canStartBatchPolish: Bool {
        workspace.canMutate &&
            !isRunning &&
            !needsSync &&
            branchPendingOperations.isEmpty &&
            !isBusy
    }

    var canSend: Bool {
        canStart(kind: mode == .discussPlan ? .discussion : .prose)
    }

    var canStop: Bool {
        activeRunID != nil && access == .readWrite && !isPerformingAction
    }

    var canRetryLastTerminal: Bool {
        guard lastRetryDraft != nil,
              let runID = lastRetryRunID,
              !isRunning,
              !isBusy,
              access == .readWrite,
              let run = workspace.projectSnapshot?.activeRuns.first(where: { $0.id == runID }) else {
            return false
        }
        return run.kind != .quickStart &&
            canStart(kind: run.kind) &&
            isEligibleForExactRetry(run)
    }

    var canRetryPendingTerminal: Bool {
        guard case .persistenceBlocked = transientTail?.phase else { return false }
        return access == .readWrite && !workspace.requiresReload && !isPerformingAction
    }

    var errorMessage: String? {
        operationErrorMessage ?? refreshErrorMessage
    }

    var hasRefreshError: Bool {
        refreshErrorMessage != nil
    }

    func bindToCurrentSelection() async {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              workspace.selectedProjectID == project.project.id,
              workspace.selectedBranchID == branch.branch.id else {
            resetBinding()
            return
        }
        let next = NovelSessionBinding(
            projectID: project.project.id,
            branchID: branch.branch.id
        )
        let didChange = binding != next
        if didChange {
            detachConsumer()
            // 切 binding 即作废旧 token:顺带取消可能仍在静窗里等待的 tail 退役任务。
            terminalTailRetirementTask?.cancel()
            terminalTailRetirementTask = nil
            bindingToken = UUID()
            binding = next
            transientTail = nil
            transientRunRecord = nil
            terminalAwaitingRefresh = false
            currentRunDraft = nil
            lastRetryDraft = nil
            lastRetryRunID = nil
            operationErrorMessage = nil
            refreshErrorMessage = nil
            lastFailure = nil
            granularity = project.project.lastGenerationGranularity
        }
        hydrateTerminalState()
        let currentActiveRun = activeRun
        if let run = currentActiveRun,
           consumerTask == nil || transientTail?.runID != run.id {
            await attach(to: run)
        } else if let startingRun = boundQuickStartStartingRun {
            if transientTail?.runID != startingRun.id {
                installTail(
                    run: startingRun,
                    content: "",
                    phase: .waitingForFirstToken
                )
            }
        } else if currentActiveRun == nil,
                  !terminalAwaitingRefresh,
                  isActiveTailPhase(transientTail?.phase) {
            transientTail = nil
            transientRunRecord = nil
        }
    }

    @discardableResult
    func refresh() async -> Bool {
        guard let binding else {
            await bindToCurrentSelection()
            return self.binding != nil
        }
        return await refreshDurable(binding: binding, token: bindingToken)
    }

    func detachConsumer() {
        consumerID = nil
        consumerTask?.cancel()
        consumerTask = nil
        attachAttemptID = nil
        attachingRunID = nil
        attachingBindingToken = nil
        cancelPendingPresentation()
    }

    func clearError() {
        operationErrorMessage = nil
        refreshErrorMessage = nil
        lastFailure = nil
    }

    @discardableResult
    func send(
        text: String,
        injectionOverrides: NovelInjectionOverrides = .none,
        inputBudgetTokens: Int = 16_000
    ) async -> Bool {
        let kind: NovelRunKind = mode == .discussPlan ? .discussion : .prose
        let draft = NovelSessionRunDraft(
            kind: kind,
            mode: mode,
            granularity: kind == .prose ? granularity : nil,
            userText: text,
            sourceChapterVersionID: nil,
            askUserResponse: nil,
            injectionOverrides: injectionOverrides,
            inputBudgetTokens: inputBudgetTokens
        )
        return await start(draft)
    }

    @discardableResult
    func answerAskUser(
        promptMessageID: NovelMessageID,
        answer: String
    ) async -> Bool {
        guard let promptMessage = durableMessages.first(where: {
            $0.id == promptMessageID && $0.role == .assistant
        }), case .some(.askUser) = promptMessage.interaction else {
            operationErrorMessage = "这个问题已经失效，请重新发起讨论。"
            return false
        }
        let response = NovelAskUserResponse(
            promptMessageID: promptMessageID,
            answer: answer
        )
        let draft = NovelSessionRunDraft(
            kind: .discussion,
            mode: .discussPlan,
            granularity: nil,
            userText: answer,
            sourceChapterVersionID: nil,
            askUserResponse: response,
            injectionOverrides: .none,
            inputBudgetTokens: 16_000
        )
        return await start(draft)
    }

    @discardableResult
    func associateCharacterAlias(
        _ alias: String,
        with materialID: NovelMaterialID
    ) async -> Bool {
        guard let project = workspace.projectSnapshot,
              let material = workspace.activeMaterials.first(where: {
                  $0.id == materialID && $0.kind == .character
              }),
              let revision = NovelPresentation.currentRevision(for: material, in: project) else {
            operationErrorMessage = "目标角色已经不存在。"
            return false
        }
        await workspace.saveMaterial(
            materialID: material.id,
            kind: .character,
            title: revision.title,
            content: revision.content,
            tags: revision.tags,
            injectionMode: revision.injectionMode,
            aliases: material.aliases + [alias]
        )
        operationErrorMessage = workspace.errorMessage
        _ = await refreshDurable(binding: binding, token: bindingToken)
        return workspace.errorMessage == nil
    }

    func stop(reason: NovelRunInterruptionReason = .user) async {
        _ = await interruptBoundRun(reason: reason)
    }

    @discardableResult
    func interruptForRouteExit() async -> Bool {
        if binding == nil {
            await bindToCurrentSelection()
        }
        guard !isPerformingAction,
              !workspace.isPerforming || isStarting else { return false }
        if activeRunID != nil {
            guard await interruptBoundRun(reason: .routeExit) else { return false }
        }
        detachConsumer()
        return true
    }

    func interruptForBackground(deadline: Date = Date().addingTimeInterval(5)) async {
        if binding == nil {
            await bindToCurrentSelection()
        }
        guard let projectID = binding?.projectID else { return }
        let ownsSessionAction = !isPerformingAction
        let backgroundOwnerID = UUID()
        let ownsWorkspaceBusy = workspace.acquireSessionOperation(ownerID: backgroundOwnerID)
        if ownsSessionAction { isPerformingAction = true }
        defer {
            if ownsWorkspaceBusy {
                workspace.releaseSessionOperation(ownerID: backgroundOwnerID)
            }
            if ownsSessionAction { isPerformingAction = false }
        }
        let startingRunID = isStarting ? activeRunID : nil
        if let startingRunID, sessionStartingRunID == startingRunID {
            cancelledStartRunIDs.insert(startingRunID)
        }
        await workspace.interruptSessionForBackground(
            projectID: projectID,
            runID: activeRunID,
            deadline: deadline
        )
        let refreshed = await refreshDurable(binding: binding, token: bindingToken)
        if refreshed,
           let startingRunID,
           workspace.projectSnapshot?.activeRuns.contains(where: {
               $0.id == startingRunID && $0.status == .running
           }) != true {
            releaseSessionStartOwnership(runID: startingRunID)
            if transientTail?.runID == startingRunID {
                clearTransientTail()
            }
            operationErrorMessage = nil
        }
    }

    @discardableResult
    func retryLastTerminal() async -> Bool {
        guard let runID = lastRetryRunID else { return false }
        return await retryGeneration(runID: runID)
    }

    @discardableResult
    func retryGeneration(runID: NovelRunID) async -> Bool {
        if terminalAwaitingRefresh, !(await refresh()) { return false }
        guard let run = workspace.projectSnapshot?.activeRuns.first(where: {
            $0.id == runID && $0.branchID == binding?.branchID &&
                ($0.status == .failed || $0.status == .interrupted)
        }), isEligibleForExactRetry(run) else { return false }
        if run.kind == .quickStart {
            // 精确重试:从持久化的 user 消息取回本次请求原文(含用户填写的调整方向)
            // 原样重发。此前这里调的是无参版本,会退回默认文案、把调整方向静默丢掉,
            // 与 isEligibleForExactRetry 的「精确」契约相悖。取不到就退回默认行为。
            let originalUserText = workspace.branchSnapshot?.session.messages.first(where: {
                $0.runID == run.id && $0.id == run.userMessageID && $0.role == .user
            })?.content
            guard let retryRunID = await workspace.startQuickStartSuggestions(
                exactUserText: originalUserText
            ), retryRunID != runID else { return false }
            operationErrorMessage = nil
            refreshErrorMessage = nil
            lastFailure = nil
            lastRetryDraft = nil
            lastRetryRunID = nil
            await bindToCurrentSelection()
            return activeRunID == retryRunID
        }
        guard let draft = draft(for: run) else { return false }
        return await start(draft)
    }

    func retryPendingTerminal() async {
        guard let runID = transientTail?.runID,
              canRetryPendingTerminal,
              beginAction() else { return }
        defer { endAction() }
        do {
            try await workspace.retrySessionTerminal(runID: runID)
            operationErrorMessage = nil
            if transientTail?.runID == runID {
                updateTail(phase: .terminalAwaitingRefresh)
                terminalAwaitingRefresh = true
            }
        } catch {
            operationErrorMessage = describe(error)
        }
        _ = await refreshDurable(binding: binding, token: bindingToken)
    }

    func candidate(id: NovelCandidateID) -> NovelCandidateRecord? {
        guard let branchID = binding?.branchID else { return nil }
        return workspace.projectSnapshot?.candidates.first {
            $0.id == id && $0.branchID == branchID
        }
    }

    func collectionGranularity(for candidateID: NovelCandidateID) -> NovelGenerationGranularity {
        guard let project = workspace.projectSnapshot else { return granularity }
        if let direct = project.activeRuns.first(where: { $0.candidateID == candidateID })?.granularity {
            return direct
        }
        guard let candidate = project.candidates.first(where: { $0.id == candidateID }) else {
            return project.project.lastGenerationGranularity
        }
        let sourceRunID = project.sessions
            .first(where: { $0.id == candidate.sessionID })?
            .messages
            .first(where: { $0.id == candidate.sourceMessageID })?
            .runID
        if let sourceRunID,
           let sourceGranularity = project.activeRuns.first(where: {
               $0.id == sourceRunID
           })?.granularity {
            return sourceGranularity
        }
        if let sourceCandidateID = candidate.clonedFromCandidateID,
           let sourceGranularity = project.activeRuns.first(where: {
               $0.candidateID == sourceCandidateID
           })?.granularity {
            return sourceGranularity
        }
        return project.project.lastGenerationGranularity
    }

    func paragraphs(candidateID: NovelCandidateID) -> [NovelParagraphRecord] {
        guard let candidate = candidate(id: candidateID) else { return [] }
        return NovelParagraphParser.paragraphs(in: candidate.content)
    }

    @discardableResult
    func collectCandidate(
        _ candidateID: NovelCandidateID,
        selection: NovelParagraphSelection,
        target: NovelCollectionTarget
    ) async -> Bool {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let candidate = candidate(id: candidateID),
              candidate.kind == .prose,
              candidate.status == .available || candidate.status == .interrupted,
              branch.branch.syncStatus == .synchronized,
              branchPendingOperations.isEmpty,
              snapshotMatchesBinding else { return false }
        do {
            _ = try NovelParagraphParser.selectedText(for: selection, in: candidate.content)
        } catch {
            operationErrorMessage = describe(error)
            return false
        }
        let action = NovelAction.collectCandidate(NovelCollectCandidateCommand(
            context: mutationContext(project: project, branch: branch),
            projectID: project.project.id,
            branchID: branch.branch.id,
            pendingID: NovelPendingOperationID(),
            candidateID: candidate.id,
            selection: selection,
            target: target,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        ))
        guard await perform(action) != nil else { return false }
        workspace.scheduleAutomaticStateSync(
            projectID: project.project.id,
            branchID: branch.branch.id
        )
        return true
    }

    func distillDiscussionArchive(
        chapterID: NovelChapterID?
    ) async -> NovelDiscussionArchiveDraft? {
        guard let binding,
              snapshotMatchesBinding,
              beginAction() else { return nil }
        defer { endAction() }
        do {
            let draft = try await workspace.distillDiscussionArchive(
                projectID: binding.projectID,
                branchID: binding.branchID,
                chapterID: chapterID
            )
            operationErrorMessage = nil
            return draft
        } catch {
            operationErrorMessage = describe(error)
            return nil
        }
    }

    func confirmDiscussionArchive(
        _ draft: NovelDiscussionArchiveDraft,
        decisions: [NovelDiscussionArchiveDraftDecision],
        summary: String
    ) async -> Bool {
        guard !decisions.isEmpty,
              let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let binding,
              draft.projectID == binding.projectID,
              draft.branchID == binding.branchID,
              draft.sessionID == branch.session.id,
              snapshotMatchesBinding else { return false }
        let confirmed = decisions.map {
            NovelConfirmedDiscussionDecision(
                materialID: NovelMaterialID(),
                revisionID: NovelMaterialRevisionID(),
                topic: $0.topic,
                decision: $0.decision,
                relatedMaterialID: $0.relatedMaterialID
            )
        }
        let action = NovelAction.archiveDiscussion(NovelArchiveDiscussionCommand(
            context: mutationContext(project: project, branch: branch),
            projectID: binding.projectID,
            branchID: binding.branchID,
            archiveID: NovelMessageID(),
            checkpointID: NovelCheckpointID(),
            throughSequence: draft.throughSequence,
            chapterID: draft.chapterID,
            summary: summary,
            decisions: confirmed
        ))
        return await perform(action) != nil
    }

    func cloneCollectedProse(_ candidateID: NovelCandidateID) async -> NovelCandidateID? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let source = candidate(id: candidateID),
              source.kind == .prose,
              source.status == .collected,
              snapshotMatchesBinding else { return nil }
        let clonedID = NovelCandidateID()
        let outcome = await perform(.cloneCandidate(NovelCloneCandidateCommand(
            context: mutationContext(project: project, branch: branch),
            projectID: project.project.id,
            branchID: branch.branch.id,
            sourceCandidateID: candidateID,
            candidateID: clonedID
        )))
        guard case .candidateCloned(_, _, candidateID, let actualID, _) = outcome,
              candidateID == source.id,
              actualID == clonedID else { return nil }
        return clonedID
    }

    @discardableResult
    func forkFromCheckpoint(
        _ checkpointID: NovelCheckpointID,
        name: String
    ) async -> NovelBranchID? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              project.checkpoints.contains(where: {
                  $0.id == checkpointID && $0.kind != .initial
              }), snapshotMatchesBinding,
              !isRunning else {
            operationErrorMessage = "当前状态不能从这个存档点创建分支。"
            return nil
        }
        let branchID = await workspace.forkBranch(
            from: branch.branch.id,
            checkpointID: checkpointID,
            name: name
        )
        operationErrorMessage = branchID == nil ? workspace.presentedMessage : nil
        await bindToCurrentSelection()
        return branchID
    }

    func retryPending(_ pendingID: NovelPendingOperationID) async {
        guard let project = workspace.projectSnapshot,
              project.pendingOperations.contains(where: { $0.id == pendingID }),
              snapshotMatchesBinding else { return }
        _ = await perform(.retryPending(NovelRetryPendingCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: project.project.revision,
                expectedConfigRevision: project.project.configRevision,
                expectedBranchHeadRevision: workspace.branchSnapshot?.branch.headRevision
            ),
            projectID: project.project.id,
            pendingID: pendingID
        )))
    }

    @discardableResult
    func startWholeChapterPolish(chapterID: NovelChapterID? = nil) async -> Bool {
        let source: NovelChapterVersionRecord?
        if let chapterID {
            source = currentChapterVersions.first { $0.chapterID == chapterID }
        } else {
            source = currentChapterVersions.last
        }
        guard let source else {
            operationErrorMessage = "当前分支还没有可润色的正式章节。"
            return false
        }
        let draft = NovelSessionRunDraft(
            kind: .polish,
            mode: .writeProse,
            granularity: nil,
            userText: "请在不改变任何剧情事实的前提下润色《\(source.title)》。",
            sourceChapterVersionID: source.id,
            askUserResponse: nil,
            injectionOverrides: .none,
            inputBudgetTokens: 16_000
        )
        return await start(draft)
    }

    /// 整章重新生成:与润色的区别是**允许改变剧情事实**,因此不走润色事务的
    /// 漂移闸,而是产出普通 prose 候选,由用户确认后以 `.replaceChapter` 收录为
    /// 该章新版本(版本类型 `.collected`,另起事实兼容链)。
    func startWholeChapterRegeneration(chapterID: NovelChapterID) async -> Bool {
        guard let source = currentChapterVersions.first(where: { $0.chapterID == chapterID }) else {
            operationErrorMessage = "这一章还没有正式版本，无法重新生成。"
            return false
        }
        let draft = NovelSessionRunDraft(
            kind: .regenerate,
            mode: .writeProse,
            granularity: .wholeChapter,
            userText: "请重写《\(source.title)》这一整章。允许调整剧情以消除与前后章节的矛盾或重复，"
                + "但必须与其余章节保持一致。",
            sourceChapterVersionID: source.id,
            askUserResponse: nil,
            injectionOverrides: .none,
            inputBudgetTokens: 16_000
        )
        return await start(draft)
    }

    /// 候选若由「整章重新生成」产生,它会带着被重写章节的版本 id;收录面板据此
    /// 提供「替换该章」。返回 nil 表示这是普通候选,只能追加或新建章节。
    func regenerationTargetChapterID(for candidateID: NovelCandidateID) -> NovelChapterID? {
        guard let candidate = candidate(id: candidateID),
              candidate.kind == .prose,
              let versionID = candidate.sourceChapterVersionID else { return nil }
        return currentChapterVersions.first { $0.id == versionID }?.chapterID
    }

    func adoptPolishCandidate(_ candidateID: NovelCandidateID) async {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let candidate = candidate(id: candidateID),
              candidate.kind == .polish,
              candidate.status == .available,
              snapshotMatchesBinding else { return }
        let command = NovelAdoptPolishCandidateCommand(
            context: mutationContext(project: project, branch: branch),
            projectID: project.project.id,
            branchID: branch.branch.id,
            transactionID: NovelPendingOperationID(),
            candidateID: candidate.id,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            expectedWorkingRevision: branch.branch.workingRevision
        )
        await applyPolishAdoption(command)
    }

    func retryPolishTransaction(_ transactionID: NovelPendingOperationID) async {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let transaction = project.polishTransactions.first(where: {
                  $0.id == transactionID && $0.branchID == branch.branch.id &&
                      ($0.status == .pending || $0.status == .retryable)
              }), snapshotMatchesBinding else { return }
        let command = NovelAdoptPolishCandidateCommand(
            context: NovelMutationContext(
                operationID: transaction.operationID,
                expectedProjectRevision: project.project.revision,
                expectedConfigRevision: project.project.configRevision,
                expectedBranchHeadRevision: branch.branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branch.branch.id,
            transactionID: transaction.id,
            candidateID: transaction.candidateID,
            proposedChapterVersionID: transaction.proposedChapterVersionID,
            checkpointID: transaction.checkpointID,
            expectedWorkingRevision: branch.branch.workingRevision
        )
        await applyPolishAdoption(command)
    }

    func abandonPolishTransaction(_ transactionID: NovelPendingOperationID) async {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              project.polishTransactions.contains(where: {
                  $0.id == transactionID && $0.branchID == branch.branch.id
              }), snapshotMatchesBinding else { return }
        _ = await perform(.abandonPolishTransaction(
            NovelAbandonPolishTransactionCommand(
                context: mutationContext(project: project, branch: branch),
                projectID: project.project.id,
                branchID: branch.branch.id,
                transactionID: transactionID
            )
        ))
    }

    // MARK: - 批量整章润色

    /// 发起批量整章润色:按 `chapterIDs` 顺序逐章「生成候选 → 采用(含漂移校验)」。
    /// 必须串行——一个项目同一时刻只能跑一个 run,且采用会推进分支 head 使其他已生成
    /// 候选作废,所以只能生成一章、采用一章、再下一章。通过漂移校验的章自动采用为新
    /// 版本;改了剧情的章自动跳过;失败章记录在案。任务句柄留在 ViewModel 上,关闭
    /// sheet 不中断,只有 `cancelBatchPolish()` 会停止。
    func startBatchPolish(chapterIDs: [NovelChapterID]) {
        guard batchPolishTask == nil, !chapterIDs.isEmpty, canStartBatchPolish else { return }
        batchPolishProgress = NovelBatchPolishProgress(
            total: chapterIDs.count,
            completed: 0,
            currentTitle: nil,
            phase: .running,
            results: [],
            startedAt: Date()
        )
        batchPolishTask = Task { @MainActor [weak self] in
            await self?.runBatchPolish(chapterIDs: chapterIDs)
            self?.batchPolishTask = nil
        }
    }

    func cancelBatchPolish() {
        batchPolishTask?.cancel()
    }

    /// 报告态下「再润色一批」用:清空进度回到选择态。运行中不允许清。
    func clearBatchPolish() {
        guard batchPolishTask == nil else { return }
        batchPolishProgress = nil
    }

    private func runBatchPolish(chapterIDs: [NovelChapterID]) async {
        var results: [NovelBatchPolishChapterResult] = []
        do {
            for (index, chapterID) in chapterIDs.enumerated() {
                try Task.checkCancellation()
                // 章间主动让快照追上 durable:清掉上一章可能残留的 terminalAwaitingRefresh,
                // 保证本章 start 的 canStart 门禁不被卡。refreshDurable 幂等且不 rebind/detach。
                _ = await refreshDurable(binding: binding, token: bindingToken)
                let title = batchPolishTitle(for: chapterID, ordinal: index + 1)
                mutateBatchProgress {
                    $0.completed = index
                    $0.currentTitle = title
                }
                let result = try await polishOneChapter(chapterID: chapterID, title: title)
                results.append(result)
                mutateBatchProgress {
                    $0.completed = index + 1
                    $0.results = results
                }
            }
            mutateBatchProgress {
                $0.completed = chapterIDs.count
                $0.currentTitle = nil
                $0.phase = .done
                $0.results = results
            }
        } catch is CancellationError {
            // 用户按了停止:已采用的保留,未轮到的标 cancelled,不弹错误。
            results.append(contentsOf: unhandledBatchResults(
                chapterIDs: chapterIDs,
                handled: results,
                outcome: .cancelled,
                message: nil
            ))
            mutateBatchProgress {
                $0.currentTitle = nil
                $0.phase = .cancelled
                $0.results = results
            }
        } catch {
            // 意料外的错误:剩余章记 failed 收口,保留已完成的结果。
            results.append(contentsOf: unhandledBatchResults(
                chapterIDs: chapterIDs,
                handled: results,
                outcome: .failed,
                message: describe(error)
            ))
            mutateBatchProgress {
                $0.currentTitle = nil
                $0.phase = .done
                $0.results = results
            }
        }
    }

    /// 单章「生成候选 → 采用」。只在被取消时抛 `CancellationError`,其余失败都作为结果
    /// 返回让批量继续(同连续性审计「一块失败不作废其余块」)。
    private func polishOneChapter(
        chapterID: NovelChapterID,
        title: String
    ) async throws -> NovelBatchPolishChapterResult {
        guard let source = currentChapterVersions.first(where: { $0.chapterID == chapterID }) else {
            return NovelBatchPolishChapterResult(
                chapterID: chapterID, title: title, outcome: .failed,
                message: "当前分支没有这一章。"
            )
        }
        let preexisting = Set(
            availablePolishCandidates
                .filter { $0.sourceChapterVersionID == source.id }
                .map(\.id)
        )
        isBatchStartingRun = true
        let started = await startWholeChapterPolish(chapterID: chapterID)
        isBatchStartingRun = false
        guard started else {
            return NovelBatchPolishChapterResult(
                chapterID: chapterID, title: title, outcome: .failed,
                message: "\(errorMessage ?? "润色没有开始,请稍后重试。") [\(batchPolishDiagnostic())]"
            )
        }
        let candidateID: NovelCandidateID?
        do {
            candidateID = try await awaitPolishCandidate(
                sourceVersionID: source.id,
                preexistingIDs: preexisting
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return NovelBatchPolishChapterResult(
                chapterID: chapterID, title: title, outcome: .failed,
                message: describe(error)
            )
        }
        guard let candidateID else {
            return NovelBatchPolishChapterResult(
                chapterID: chapterID, title: title, outcome: .failed,
                message: "\(errorMessage ?? "润色没有产出候选。") [\(batchPolishDiagnostic())]"
            )
        }
        await adoptPolishCandidate(candidateID)
        // 不在此处 checkCancellation:采用(含漂移校验)是非抛出的完整往返,即便批量在漂移
        // 期间被取消,也应先读出真实事务状态(可能已 .completed 采用)再返回,避免把实际已
        // 采用的章误报为 cancelled。取消会在下一章的循环顶部 checkCancellation 收口。
        if let transaction = branchPolishTransactions.first(where: {
            $0.candidateID == candidateID
        }) {
            switch transaction.status {
            case .completed:
                return NovelBatchPolishChapterResult(
                    chapterID: chapterID, title: title, outcome: .adopted
                )
            case .incompatible:
                return NovelBatchPolishChapterResult(
                    chapterID: chapterID, title: title, outcome: .skippedDrift,
                    message: "润色改动了剧情事实,已跳过本章。"
                )
            case .blocked, .retryable, .pending, .abandoned:
                return NovelBatchPolishChapterResult(
                    chapterID: chapterID, title: title, outcome: .failed,
                    message: transaction.lastFailure?.message ?? "润色没有完成。"
                )
            }
        }
        return NovelBatchPolishChapterResult(
            chapterID: chapterID, title: title, outcome: .failed,
            message: errorMessage ?? "采用没有完成,请检查项目状态。"
        )
    }

    /// 等待某章的润色候选生成完成。以「出现新的可用候选」为成功主信号;run 落定后
    /// 宽限窗内仍无候选、或整体超时则判为失败(返回 nil)。
    private func awaitPolishCandidate(
        sourceVersionID: NovelChapterVersionID,
        preexistingIDs: Set<NovelCandidateID>
    ) async throws -> NovelCandidateID? {
        let startedAt = Date()
        while true {
            try Task.checkCancellation()
            // 主动让快照追上 durable 提交。真机上 .completed 事件可能先于候选落盘广播,
            // consume 的单次 refresh 读到的是旧快照(无候选、run 仍 running);若轮询只读不
            // refresh,就会一直读旧快照——既看不到候选,也无法在 run 真正结束后经
            // refreshDurable 的 retire 分支清掉 terminalAwaitingRefresh,进而卡住后续章节的
            // 启动门禁。refreshDurable 幂等、不 rebind、不 detach 正在跑的 consumer。
            _ = await refreshDurable(binding: binding, token: bindingToken)
            if let candidate = availablePolishCandidates.first(where: {
                $0.sourceChapterVersionID == sourceVersionID &&
                    !preexistingIDs.contains($0.id)
            }) {
                return candidate.id
            }
            let elapsed = Date().timeIntervalSince(startedAt)
            // activeRunID 在 terminalAwaitingRefresh 窗口本就为 nil,故不再把
            // `!terminalAwaitingRefresh` 当落定条件——否则一旦终态 refreshDurable 失败
            // (terminalAwaitingRefresh 卡住、refreshErrorMessage 置位),settled 永远为
            // false,要白等到 900s 超时。改由宽限窗兜底:候选正常会在宽限窗内落盘并被上面的
            // 候选检查捕获,只有宽限窗内仍未出现才判失败。
            let settled = activeRunID == nil && !isStarting
            if (settled && elapsed > batchPolishSettleGrace) ||
                elapsed > batchPolishCandidateTimeout {
                return nil
            }
            try await Task.sleep(for: .milliseconds(300))
        }
    }

    private func unhandledBatchResults(
        chapterIDs: [NovelChapterID],
        handled: [NovelBatchPolishChapterResult],
        outcome: NovelBatchPolishChapterResult.Outcome,
        message: String?
    ) -> [NovelBatchPolishChapterResult] {
        let handledIDs = Set(handled.map(\.chapterID))
        return chapterIDs.enumerated().compactMap { index, chapterID in
            guard !handledIDs.contains(chapterID) else { return nil }
            return NovelBatchPolishChapterResult(
                chapterID: chapterID,
                title: batchPolishTitle(for: chapterID, ordinal: index + 1),
                outcome: outcome,
                message: message
            )
        }
    }

    private func mutateBatchProgress(_ mutate: (inout NovelBatchPolishProgress) -> Void) {
        guard var progress = batchPolishProgress else { return }
        mutate(&progress)
        batchPolishProgress = progress
    }

    private func batchPolishTitle(for chapterID: NovelChapterID, ordinal: Int) -> String {
        guard let version = currentChapterVersions.first(where: {
            $0.chapterID == chapterID
        }) else {
            return "第 \(ordinal) 章"
        }
        return NovelPresentation.chapterDisplayTitle(
            storedTitle: version.title,
            content: version.content,
            ordinal: ordinal
        )
    }

    /// 批量失败时附带的内部状态快照:真机上回写/门禁卡住时,兜底文案看不出真因,把当时的
    /// binding / 快照匹配 / refresh 错误 / activeRun 等带出来,便于据截图定位。
    private func batchPolishDiagnostic() -> String {
        [
            "binding=\(binding == nil ? "nil" : "ok")",
            "match=\(snapshotMatchesBinding)",
            "selProj=\(String(describing: workspace.selectedProjectID))",
            "bindProj=\(binding?.projectID.description ?? "-")",
            "tailAwait=\(terminalAwaitingRefresh)",
            "activeRun=\(activeRun.map { String(describing: $0.status) } ?? "-")",
            "refreshErr=\(refreshErrorMessage ?? "-")",
            "opErr=\(operationErrorMessage ?? "-")",
            "polishCands=\(availablePolishCandidates.count)",
            "wsPerform=\(workspace.isPerforming)",
        ].joined(separator: " ")
    }

    @discardableResult
    func convertPolishCandidateToManualRewrite(_ candidateID: NovelCandidateID) async -> Bool {
        guard !workspace.requiresReload,
              let candidate = candidate(id: candidateID),
              candidate.kind == .polish,
              let sourceID = candidate.sourceChapterVersionID,
              let source = workspace.projectSnapshot?.chapterVersions.first(where: { $0.id == sourceID }),
              currentChapterVersions.contains(where: { $0.id == source.id }) else {
            return false
        }
        isPerformingAction = true
        workspace.clearError()
        let saved = await workspace.saveManualRewrite(
            chapterID: source.chapterID,
            title: source.title,
            content: candidate.content
        )
        isPerformingAction = false
        operationErrorMessage = workspace.errorMessage
        _ = await refreshDurable(binding: binding, token: bindingToken)
        return saved
    }

    func syncManualEdits() async {
        guard needsSync, !isPerformingAction, !workspace.requiresReload else { return }
        isPerformingAction = true
        workspace.clearError()
        await workspace.syncManualEdits()
        isPerformingAction = false
        operationErrorMessage = workspace.errorMessage
        _ = await refreshDurable(binding: binding, token: bindingToken)
    }
}

extension NovelSessionViewModel {
    /// 生成中状态条按**真实 run** 显示文案,而不是 composer 的当前设置:
    /// `start(_:)` 不回写 mode/granularity,重试失败 run 时两者会分叉。
    var activeRunKind: NovelRunKind? { activeRun?.kind }
    var activeRunGranularity: NovelGenerationGranularity? { activeRun?.granularity }
}

private extension NovelSessionViewModel {
    var snapshotMatchesBinding: Bool {
        guard let binding,
              workspace.selectedProjectID == binding.projectID,
              workspace.selectedBranchID == binding.branchID,
              workspace.projectSnapshot?.project.id == binding.projectID,
              workspace.branchSnapshot?.branch.id == binding.branchID else { return false }
        return true
    }

    var activeRun: NovelActiveRunRecord? {
        guard let branchID = binding?.branchID else { return nil }
        return workspace.projectSnapshot?.activeRuns.first {
            $0.branchID == branchID && $0.status == .running
        }
    }

    var boundQuickStartStartingRun: NovelActiveRunRecord? {
        guard let binding,
              workspace.quickStartStartingProjectID == binding.projectID,
              let run = workspace.quickStartStartingRun,
              run.branchID == binding.branchID else { return nil }
        return run
    }

    func isEligibleForExactRetry(_ run: NovelActiveRunRecord) -> Bool {
        guard run.branchID == binding?.branchID,
              run.status == .failed || run.status == .interrupted,
              run.status != .failed || run.terminalFailure?.isRetryable == true,
              let branch = workspace.branchSnapshot?.branch else { return false }
        if run.kind == .prose || run.kind == .polish {
            guard run.baseCheckpointID == branch.headCheckpointID,
                  run.baseHeadRevision == branch.headRevision else { return false }
        }
        if run.kind == .polish {
            guard let sourceID = run.sourceChapterVersionID,
                  branch.workingChapterSelections.contains(where: { $0.versionID == sourceID }) else {
                return false
            }
        }
        return true
    }

    func candidates(kind: NovelCandidateKind) -> [NovelCandidateRecord] {
        guard let branchID = binding?.branchID else { return [] }
        return workspace.projectSnapshot?.candidates.filter {
            $0.branchID == branchID && $0.kind == kind
        } ?? []
    }

    func canStart(kind: NovelRunKind) -> Bool {
        guard snapshotMatchesBinding,
              access == .readWrite,
              !workspace.requiresReload,
              refreshErrorMessage == nil,
              !terminalAwaitingRefresh,
              (!isBusy || isBatchStartingRun),
              !isRunning,
              let branch = workspace.branchSnapshot?.branch,
              branch.lifecycle == .active,
              branch.activeRunID == nil,
              activeRun == nil else { return false }
        switch kind {
        case .discussion:
            return true
        case .prose:
            return !branchPendingOperations.contains(where: \.blocksProseGeneration)
        case .regenerate:
            // 要把被重写章的正文原样注入,所以必须已同步且没有未落地的正文操作。
            return branch.syncStatus == .synchronized && branchPendingOperations.isEmpty
        case .polish:
            return branch.syncStatus == .synchronized && branchPendingOperations.isEmpty
        case .quickStart:
            return false
        }
    }

    @discardableResult
    func start(_ draft: NovelSessionRunDraft) async -> Bool {
        guard !draft.userText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              draft.inputBudgetTokens > 0,
              canStart(kind: draft.kind),
              let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else { return false }
        let previousTail = transientTail
        let previousRunRecord = transientRunRecord
        let previousTerminalAwaitingRefresh = terminalAwaitingRefresh

        let candidateID: NovelCandidateID? = switch draft.kind {
        case .prose, .polish, .regenerate: NovelCandidateID()
        case .quickStart, .discussion: nil
        }
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: project.project.id,
            branchID: branch.branch.id,
            kind: draft.kind,
            mode: draft.mode,
            granularity: draft.granularity,
            userText: draft.userText,
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: candidateID,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: draft.sourceChapterVersionID,
            askUserResponse: draft.askUserResponse,
            injectionOverrides: draft.injectionOverrides,
            inputBudgetTokens: draft.inputBudgetTokens,
            expectedProjectRevision: project.project.revision,
            expectedConfigRevision: project.project.configRevision,
            expectedBranchHeadRevision: branch.branch.headRevision
        )
        guard workspace.acquireSessionOperation(ownerID: request.id.rawValue) else {
            return false
        }
        let placeholderRun = NovelActiveRunRecord(
            id: request.id,
            operationID: request.operationID,
            requestPayloadSHA256: (try? request.canonicalPayloadSHA256()) ?? "",
            branchID: request.branchID,
            sessionID: branch.session.id,
            kind: request.kind,
            mode: request.mode,
            granularity: request.granularity,
            userMessageID: request.userMessageID,
            messageID: request.assistantMessageID,
            candidateID: request.candidateID,
            sourceChapterVersionID: request.sourceChapterVersionID,
            baseCheckpointID: branch.branch.headCheckpointID,
            baseHeadRevision: branch.branch.headRevision,
            status: .running,
            partialContent: "",
            receiptID: request.generationReceiptID,
            startedAt: Date(),
            terminalAt: nil,
            interruptionReason: nil,
            terminalFailure: nil
        )
        installTail(
            run: placeholderRun,
            content: "",
            startingUserContent: draft.userText,
            phase: .waitingForFirstToken
        )
        sessionStartingRunID = request.id
        let requestID = request.id
        defer {
            releaseSessionStartOwnership(runID: requestID)
            cancelledStartRunIDs.remove(requestID)
        }
        do {
            let run = try await workspace.startSessionRun(request)
            guard !cancelledStartRunIDs.contains(request.id) else { return false }
            currentRunDraft = draft
            operationErrorMessage = nil
            refreshErrorMessage = nil
            lastFailure = nil
            lastRetryDraft = nil
            lastRetryRunID = nil
            consume(run, draft: draft, token: bindingToken)
            return true
        } catch {
            transientTail = previousTail
            transientRunRecord = previousRunRecord
            terminalAwaitingRefresh = previousTerminalAwaitingRefresh
            if let previousTail,
               !previousTerminalAwaitingRefresh,
               !isActiveTailPhase(previousTail.phase) {
                // installTail cancels the old quiet-window task. If the new run
                // fails before it starts, restore that terminal tail's retirement
                // as well as its visible state so it can still hand off to durable.
                retireTerminalTransientTail(runID: previousTail.runID, token: bindingToken)
            }
            if cancelledStartRunIDs.contains(request.id) {
                operationErrorMessage = nil
            } else {
                operationErrorMessage = describe(error)
            }
            return false
        }
    }

    func consume(_ run: NovelRun, draft: NovelSessionRunDraft, token: UUID) {
        detachConsumer()
        let nextConsumerID = UUID()
        consumerID = nextConsumerID
        consumerTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for await event in run.events {
                guard !Task.isCancelled, self.bindingToken == token else { return }
                await self.consume(event, runID: run.id, draft: draft, token: token)
            }
            guard self.consumerID == nextConsumerID else { return }
            self.consumerID = nil
            self.consumerTask = nil
        }
    }

    func consume(
        _ event: NovelRunEvent,
        runID: NovelRunID,
        draft: NovelSessionRunDraft,
        token: UUID
    ) async {
        guard transientTail?.runID == runID else { return }
        switch event {
        case .started:
            _ = await refreshDurable(binding: binding, token: token)
            adoptDurableRunRecord(runID: runID)
        case .delta(let text):
            if draft.kind == .quickStart {
                publishQuickStartStreamingPhaseIfNeeded(runID: runID, token: token)
            } else {
                enqueuePresentationDelta(text, runID: runID, token: token)
            }
        case .replaced(let text):
            if draft.kind == .quickStart {
                publishQuickStartStreamingPhaseIfNeeded(runID: runID, token: token)
            } else {
                enqueuePresentationReplacement(text, runID: runID, token: token)
            }
        case .completed(let snapshot):
            publishTerminalPresentation(
                runID: runID,
                token: token,
                authoritativeContent: snapshot.message.content,
                phase: .terminalAwaitingRefresh
            )
            terminalAwaitingRefresh = true
            lastRetryDraft = nil
            lastRetryRunID = nil
            currentRunDraft = nil
            let refreshed = await refreshDurable(binding: binding, token: token)
            if refreshed { retireTerminalTransientTail(runID: runID, token: token) }
        case .interrupted(let snapshot):
            publishTerminalPresentation(
                runID: runID,
                token: token,
                authoritativeContent: draft.kind == .quickStart ? "" : snapshot?.message.content,
                phase: .interrupted
            )
            terminalAwaitingRefresh = true
            lastRetryDraft = draft
            lastRetryRunID = runID
            currentRunDraft = nil
            let refreshed = await refreshDurable(binding: binding, token: token)
            if refreshed { retireTerminalTransientTail(runID: runID, token: token) }
        case .failed(let failure):
            publishTerminalPresentation(
                runID: runID,
                token: token,
                authoritativeContent: draft.kind == .quickStart ? "" : nil,
                phase: .failed(failure)
            )
            terminalAwaitingRefresh = true
            lastFailure = failure
            operationErrorMessage = NovelPresentation.failureMessage(failure)
            lastRetryDraft = failure.isRetryable ? draft : nil
            lastRetryRunID = failure.isRetryable ? runID : nil
            currentRunDraft = nil
            let refreshed = await refreshDurable(binding: binding, token: token)
            if refreshed { retireTerminalTransientTail(runID: runID, token: token) }
        case .persistenceBlocked(let failure):
            publishTerminalPresentation(
                runID: runID,
                token: token,
                authoritativeContent: draft.kind == .quickStart ? "" : nil,
                phase: .persistenceBlocked(failure)
            )
            lastFailure = failure
            operationErrorMessage = NovelPresentation.failureMessage(failure)
        }
    }

    func attach(to run: NovelActiveRunRecord) async {
        let expectedToken = bindingToken
        if attachingRunID == run.id,
           attachingBindingToken == expectedToken {
            return
        }
        let attemptID = UUID()
        attachAttemptID = attemptID
        attachingRunID = run.id
        attachingBindingToken = expectedToken
        defer {
            if attachAttemptID == attemptID {
                attachAttemptID = nil
                attachingRunID = nil
                attachingBindingToken = nil
            }
        }
        guard let draft = draft(for: run), let request = request(for: run, draft: draft) else {
            refreshErrorMessage = "无法恢复正在生成的消息订阅。"
            return
        }
        let expectedBinding = binding
        let initialContent = run.kind == .quickStart ? "" : run.partialContent
        installTail(
            run: run,
            content: initialContent,
            phase: run.partialContent.isEmpty ? .waitingForFirstToken : .streaming
        )
        do {
            let observed = try await workspace.startSessionRun(request)
            guard attachAttemptID == attemptID,
                  binding == expectedBinding,
                  bindingToken == expectedToken,
                  transientTail?.runID == run.id else { return }
            currentRunDraft = draft
            consume(observed, draft: draft, token: expectedToken)
        } catch {
            guard attachAttemptID == attemptID,
                  binding == expectedBinding,
                  bindingToken == expectedToken,
                  transientTail?.runID == run.id else { return }
            clearTransientTail()
            refreshErrorMessage = describe(error)
        }
    }

    func draft(for run: NovelActiveRunRecord) -> NovelSessionRunDraft? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let user = branch.session.messages.first(where: {
                  $0.runID == run.id && $0.id == run.userMessageID && $0.role == .user
              }), let injection = project.injectionReceipts.first(where: {
                  $0.runID == run.id
              }) else { return nil }
        return NovelSessionRunDraft(
            kind: run.kind,
            mode: run.mode,
            granularity: run.granularity,
            userText: user.content,
            sourceChapterVersionID: run.sourceChapterVersionID,
            askUserResponse: {
                guard case .some(.askUserAnswer(let response)) = user.interaction else {
                    return nil
                }
                return response
            }(),
            injectionOverrides: NovelInjectionOverrides(
                forceIncludeMaterialIDs: injection.forceIncludeMaterialIDs,
                forceExcludeMaterialIDs: injection.forceExcludeMaterialIDs
            ),
            inputBudgetTokens: injection.requestedInputBudgetTokens
        )
    }

    func request(
        for run: NovelActiveRunRecord,
        draft: NovelSessionRunDraft
    ) -> NovelRunRequest? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let injection = project.injectionReceipts.first(where: { $0.runID == run.id }) else {
            return nil
        }
        return NovelRunRequest(
            id: run.id,
            operationID: run.operationID,
            projectID: project.project.id,
            branchID: branch.branch.id,
            kind: run.kind,
            mode: run.mode,
            granularity: run.granularity,
            userText: draft.userText,
            userMessageID: run.userMessageID,
            assistantMessageID: run.messageID,
            candidateID: run.candidateID,
            generationReceiptID: run.receiptID,
            injectionReceiptID: injection.id,
            sourceChapterVersionID: run.sourceChapterVersionID,
            askUserResponse: draft.askUserResponse,
            injectionOverrides: draft.injectionOverrides,
            inputBudgetTokens: draft.inputBudgetTokens,
            expectedProjectRevision: project.project.revision,
            expectedConfigRevision: project.project.configRevision,
            expectedBranchHeadRevision: branch.branch.headRevision
        )
    }

    @discardableResult
    func refreshDurable(binding expected: NovelSessionBinding?, token: UUID) async -> Bool {
        guard let expected,
              binding == expected,
              bindingToken == token,
              workspace.selectedProjectID == expected.projectID,
              workspace.selectedBranchID == expected.branchID else { return false }
        do {
            try await workspace.refreshCurrentSelection(projectID: expected.projectID)
            guard binding == expected,
                  bindingToken == token,
                  snapshotMatchesBinding else { return false }
            refreshErrorMessage = nil
            hydrateTerminalState()
            if terminalAwaitingRefresh,
               let tail = transientTail,
               workspace.projectSnapshot?.activeRuns.first(where: { $0.id == tail.runID })?.status != .running {
                retireTerminalTransientTail(runID: tail.runID, token: token)
            } else if terminalAwaitingRefresh,
                      transientTail == nil,
                      workspace.branchSnapshot?.branch.activeRunID == nil {
                terminalAwaitingRefresh = false
            }
            return true
        } catch {
            guard bindingToken == token else { return false }
            refreshErrorMessage = describe(error)
            return false
        }
    }

    func hydrateTerminalState() {
        guard let branchID = binding?.branchID,
              let runs = workspace.projectSnapshot?.activeRuns.filter({ $0.branchID == branchID }) else {
            return
        }
        guard let latest = runs.max(by: { $0.startedAt < $1.startedAt }) else {
            updateTerminalState(draft: nil, runID: nil, failure: nil)
            return
        }
        switch latest.status {
        case .failed:
            let retryDraft = latest.terminalFailure?.isRetryable == true ? draft(for: latest) : nil
            updateTerminalState(
                draft: retryDraft,
                runID: retryDraft == nil ? nil : latest.id,
                failure: latest.terminalFailure
            )
        case .interrupted:
            let retryDraft = draft(for: latest)
            updateTerminalState(
                draft: retryDraft,
                runID: retryDraft == nil ? nil : latest.id,
                failure: latest.terminalFailure
            )
        case .running, .completed:
            updateTerminalState(draft: nil, runID: nil, failure: latest.terminalFailure)
        }
    }

    private func updateTerminalState(
        draft: NovelSessionRunDraft?,
        runID: NovelRunID?,
        failure: NovelFailure?
    ) {
        if lastRetryDraft != draft { lastRetryDraft = draft }
        if lastRetryRunID != runID { lastRetryRunID = runID }
        if lastFailure != failure { lastFailure = failure }
    }

    func applyPolishAdoption(_ command: NovelAdoptPolishCandidateCommand) async {
        _ = await perform(.adoptPolishCandidate(command))
    }

    @discardableResult
    func perform(_ action: NovelAction) async -> NovelOutcome? {
        guard beginAction() else { return nil }
        defer { endAction() }
        let outcome: NovelOutcome?
        do {
            outcome = try await workspace.performSessionAction(action)
            operationErrorMessage = nil
        } catch {
            outcome = nil
            operationErrorMessage = describe(error)
        }
        _ = await refreshDurable(binding: binding, token: bindingToken)
        return outcome
    }

    func beginAction() -> Bool {
        guard !isPerformingAction,
              !workspace.requiresReload else { return false }
        let ownerID = UUID()
        guard workspace.acquireSessionOperation(ownerID: ownerID) else { return false }
        sessionActionOwnerID = ownerID
        isPerformingAction = true
        return true
    }

    func endAction() {
        if let ownerID = sessionActionOwnerID {
            workspace.releaseSessionOperation(ownerID: ownerID)
            sessionActionOwnerID = nil
        }
        isPerformingAction = false
    }

    func mutationContext(
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot
    ) -> NovelMutationContext {
        NovelMutationContext(
            operationID: NovelOperationID(),
            expectedProjectRevision: project.project.revision,
            expectedConfigRevision: project.project.configRevision,
            expectedBranchHeadRevision: branch.branch.headRevision
        )
    }

    func enqueuePresentationDelta(
        _ text: String,
        runID: NovelRunID,
        token: UUID
    ) {
        guard !text.isEmpty,
              let current = transientTail,
              current.runID == runID,
              bindingToken == token else { return }
        preparePresentationBuffer(for: current, token: token)
        presentationBuffer?.append(text)
        schedulePresentationFlush(
            runID: runID,
            messageID: current.messageID,
            token: token
        )
    }

    func enqueuePresentationReplacement(
        _ text: String,
        runID: NovelRunID,
        token: UUID
    ) {
        guard let current = transientTail,
              current.runID == runID,
              bindingToken == token else { return }
        preparePresentationBuffer(for: current, token: token)
        presentationBuffer?.replace(with: text)
        schedulePresentationFlush(
            runID: runID,
            messageID: current.messageID,
            token: token
        )
    }

    func preparePresentationBuffer(
        for tail: NovelSessionTransientTail,
        token: UUID
    ) {
        guard presentationBuffer?.matches(
            runID: tail.runID,
            messageID: tail.messageID,
            bindingToken: token
        ) != true else { return }
        cancelPendingPresentation()
        presentationBuffer = NovelSessionPresentationBuffer(
            runID: tail.runID,
            messageID: tail.messageID,
            bindingToken: token,
            baseContent: tail.content
        )
    }

    func schedulePresentationFlush(
        runID: NovelRunID,
        messageID: NovelMessageID,
        token: UUID
    ) {
        guard presentationFlushTask == nil else { return }
        presentationFlushTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: Self.presentationFlushDelayNanos)
            } catch {
                return
            }
            guard let self, !Task.isCancelled else { return }
            self.presentationFlushTask = nil
            self.flushPendingPresentation(
                runID: runID,
                messageID: messageID,
                token: token
            )
        }
    }

    func flushPendingPresentation(
        runID: NovelRunID,
        messageID: NovelMessageID,
        token: UUID
    ) {
        guard bindingToken == token,
              let current = transientTail,
              current.runID == runID,
              current.messageID == messageID,
              let buffer = presentationBuffer,
              buffer.matches(
                  runID: runID,
                  messageID: messageID,
                  bindingToken: token
              ) else {
            cancelPendingPresentation()
            return
        }
        let step = NovelSessionPresentationPacer.step(
            displayedContent: current.content,
            targetContent: buffer.targetContent
        )
        // [TEMP-DIAG] 取证「显示内容回退」:目标被整体替换后若不再以已显示内容
        // 为前缀,pacer 会当场跳到新目标,内容可能变短 → 高度塌陷 → 列表位移。
        ChatStreamAnomalyRecorder.noteNovelPresentation(
            displayed: current.content.count,
            target: buffer.targetContent.count,
            next: step.content.count,
            prefixHeld: buffer.targetContent.hasPrefix(current.content)
        )
        if step.content != current.content || current.phase != .streaming {
            updateTail(content: step.content, phase: .streaming)
        }
        if step.isCaughtUp {
            // Buffer target fully revealed; drop so the next delta starts a fresh base.
            presentationBuffer = nil
        } else {
            // Keep absolute target and keep draining on the 48ms clock.
            schedulePresentationFlush(
                runID: runID,
                messageID: messageID,
                token: token
            )
        }
    }

    func publishQuickStartStreamingPhaseIfNeeded(
        runID: NovelRunID,
        token: UUID
    ) {
        guard bindingToken == token,
              let current = transientTail,
              current.runID == runID,
              current.phase != .streaming || !current.content.isEmpty else { return }
        updateTail(content: "", phase: .streaming)
    }

    func publishTerminalPresentation(
        runID: NovelRunID,
        token: UUID,
        authoritativeContent: String?,
        phase: NovelSessionTransientTailPhase
    ) {
        presentationFlushTask?.cancel()
        presentationFlushTask = nil
        guard bindingToken == token,
              let current = transientTail,
              current.runID == runID else {
            presentationBuffer = nil
            return
        }
        let bufferedContent: String?
        if let buffer = presentationBuffer,
           buffer.matches(
               runID: runID,
               messageID: current.messageID,
               bindingToken: token
           ) {
            // Terminal snaps to the full target — no paced lag on complete/error/cancel.
            bufferedContent = buffer.targetContent
        } else {
            bufferedContent = nil
        }
        presentationBuffer = nil
        let content = authoritativeContent ?? bufferedContent ?? current.content
        guard content != current.content || current.phase != phase else { return }
        updateTail(content: content, phase: phase)
    }

    func cancelPendingPresentation() {
        presentationFlushTask?.cancel()
        presentationFlushTask = nil
        presentationBuffer = nil
    }

    func installTail(
        run: NovelActiveRunRecord,
        content: String,
        renderRevision: UInt64 = 0,
        startingUserContent: String? = nil,
        phase: NovelSessionTransientTailPhase
    ) {
        // 新 run 开始:取消上一场可能仍在静窗里等待的 tail 退役任务。
        terminalTailRetirementTask?.cancel()
        terminalTailRetirementTask = nil
        cancelPendingPresentation()
        transientRunRecord = run
        transientTail = NovelSessionTransientTail(
            run: run,
            content: content,
            renderRevision: renderRevision,
            startingUserContent: startingUserContent,
            phase: phase
        )
        terminalAwaitingRefresh = false
    }

    func updateTail(
        content: String? = nil,
        phase: NovelSessionTransientTailPhase? = nil
    ) {
        guard let current = transientTail else { return }
        transientTail = current.updating(
            content: content ?? current.content,
            phase: phase ?? current.phase
        )
    }

    func adoptDurableRunRecord(runID: NovelRunID) {
        guard let durable = workspace.projectSnapshot?.activeRuns.first(where: { $0.id == runID }),
              let current = transientTail else { return }
        transientRunRecord = durable
        transientTail = NovelSessionTransientTail(
            run: durable,
            content: current.content,
            renderRevision: current.renderRevision,
            startingUserContent: current.startingUserContent,
            phase: current.phase
        )
    }

    func clearTransientTail() {
        terminalTailRetirementTask?.cancel()
        terminalTailRetirementTask = nil
        cancelPendingPresentation()
        transientTail = nil
        transientRunRecord = nil
        terminalAwaitingRefresh = false
    }

    /// 终态(完成/中断/失败)且 durable 刷新成功后的 tail 退役:不立即清空,而是保留到
    /// 静窗(与底部跟随 terminalQuietDelay 对齐)过去再退役,避免生成完成、durable 正文
    /// 接管的一瞬间 tail 突然消失导致整屏「跳一下」的闪烁。输入区立即解锁
    /// (terminalAwaitingRefresh=false)——tail 的终态 phase 本身仍把 isRunning 置假,
    /// 不会误判为还在生成。静窗内若开新 run(installTail)或切 binding,退役任务会被取消。
    private func retireTerminalTransientTail(runID: NovelRunID, token: UUID) {
        terminalAwaitingRefresh = false
        terminalTailRetirementTask?.cancel()
        guard terminalQuietDelay > 0 else {
            // 零静窗(测试快路径):立即退役,保持旧的「完成即清空」契约。
            if bindingToken == token, transientTail?.runID == runID {
                clearTransientTail()
            }
            return
        }
        let delayNanos = UInt64(terminalQuietDelay * 1_000_000_000)
        terminalTailRetirementTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: delayNanos)
            guard !Task.isCancelled, let self,
                  self.bindingToken == token,
                  self.transientTail?.runID == runID else { return }
            self.clearTransientTail()
        }
    }

    func isActiveTailPhase(_ phase: NovelSessionTransientTailPhase?) -> Bool {
        return switch phase {
        case .waitingForFirstToken, .streaming: true
        case .persistenceBlocked, .terminalAwaitingRefresh, .interrupted, .failed, nil: false
        }
    }

    func interruptBoundRun(reason: NovelRunInterruptionReason) async -> Bool {
        guard let bound = binding,
              let runID = activeRunID else { return true }
        guard !isPerformingAction,
              !workspace.isPerforming || isStarting else { return false }
        let isCancellingSessionStart = sessionStartingRunID == runID
        let isCancellingQuickStart = boundQuickStartStartingRun?.id == runID
        let actionOwnerID: UUID?
        if isCancellingSessionStart || isCancellingQuickStart {
            actionOwnerID = nil
        } else {
            let ownerID = UUID()
            guard workspace.acquireSessionOperation(ownerID: ownerID) else { return false }
            actionOwnerID = ownerID
        }
        if isCancellingSessionStart {
            cancelledStartRunIDs.insert(runID)
        }
        isPerformingAction = true
        defer {
            if let actionOwnerID {
                workspace.releaseSessionOperation(ownerID: actionOwnerID)
            }
            isPerformingAction = false
        }
        let command = NovelCancelRunCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: nil,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: bound.projectID,
            runID: runID,
            reason: reason
        )
        do {
            try await workspace.interruptSessionRun(command)
            operationErrorMessage = nil
            if isCancellingSessionStart {
                releaseSessionStartOwnership(runID: runID)
            }
        } catch {
            operationErrorMessage = describe(error)
            if isCancellingSessionStart {
                cancelledStartRunIDs.remove(runID)
                _ = await refreshDurable(binding: bound, token: bindingToken)
                await bindToCurrentSelection()
            }
            return false
        }
        if transientTail?.runID == runID {
            clearTransientTail()
        }
        currentRunDraft = nil
        guard snapshotMatchesBinding else { return true }
        let refreshed = await refreshDurable(binding: bound, token: bindingToken)
        return refreshed &&
            workspace.branchSnapshot?.branch.activeRunID == nil &&
            workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) != true
    }

    func releaseSessionStartOwnership(runID: NovelRunID) {
        if sessionStartingRunID == runID {
            sessionStartingRunID = nil
        }
        workspace.releaseSessionOperation(ownerID: runID.rawValue)
    }

    func resetBinding() {
        detachConsumer()
        bindingToken = UUID()
        binding = nil
        clearTransientTail()
        currentRunDraft = nil
        lastRetryDraft = nil
        lastRetryRunID = nil
    }

    func describe(_ error: Error) -> String {
        NovelPresentation.operationErrorMessage(error)
    }
}
