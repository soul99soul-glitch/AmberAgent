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
/// Mirrors Chat's bounded-per-tick reveal, but advances adaptively with backlog
/// so long chapters do not accumulate multi-second UI lag under a fixed 12-char
/// ceiling. Terminal paths still snap to the authoritative full text.
enum NovelSessionPresentationPacer {
    /// Soft floor: roughly one CJK phone-width line per tick when backlog is light.
    static let minimumTextAdvance = 12
    /// Hard ceiling per 48ms tick: drains large backlog in a few seconds, not tens.
    static let maximumTextAdvance = 64
    /// Prefer clearing the *current* backlog within this many ticks when possible.
    static let preferredDrainTicks = 16

    struct Step: Equatable {
        let content: String
        let isCaughtUp: Bool
    }

    static func textAdvance(backlogCount: Int) -> Int {
        guard backlogCount > 0 else { return 0 }
        let adaptive = (backlogCount + preferredDrainTicks - 1) / preferredDrainTicks
        return min(maximumTextAdvance, max(minimumTextAdvance, adaptive))
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

    private static let presentationFlushDelayNanos: UInt64 = 48_000_000

    init(
        workspace: NovelCreationViewModel,
        terminalQuietDelay: TimeInterval = NovelSessionBottomFollowPolicy.terminalQuietDelay
    ) {
        self.workspace = workspace
        self.terminalQuietDelay = terminalQuietDelay
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
        isStarting || isPerformingAction || workspace.isPerforming
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
              !isBusy,
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
        case .prose, .polish: NovelCandidateID()
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
