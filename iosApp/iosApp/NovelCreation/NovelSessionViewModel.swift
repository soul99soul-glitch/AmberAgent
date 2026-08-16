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

    let binding: NovelSessionBinding
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

struct NovelSessionRunDraft: Equatable, Sendable {
    let kind: NovelRunKind
    let mode: NovelSessionMode
    let granularity: NovelGenerationGranularity?
    let userText: String
    let sourceChapterVersionID: NovelChapterVersionID?
    let askUserResponse: NovelAskUserResponse?
    let injectionOverrides: NovelInjectionOverrides
    let inputBudgetTokens: Int
    var contextualCharacterMention: String? = nil
    var ghostwritePlanID: NovelChapterPlanID? = nil
    /// 代笔自愈/润修：不注入近期会话全文，避免失败稿污染下一 attempt。
    var suppressRecentSessionMessages: Bool = false
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
/// session's single-String shape. Terminal drain uses the same continuous
/// whoosh curve as `ChatStreamPresentationPacer` (fixed advance anchor +
/// dynamic tick interval), not the live streaming 36-char cap.
enum NovelSessionPresentationPacer {
    enum Mode {
        case streaming
        case terminalDrain
    }

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

    static func terminalDrainAdvance(backlogCount: Int) -> Int {
        StreamPresentationPacingPolicy.terminalDrainAdvance(backlogCount: backlogCount)
    }

    static func terminalDrainDelayNanos(advance: Int) -> UInt64 {
        StreamPresentationPacingPolicy.terminalDrainDelayNanos(advance: advance)
    }

    /// Manuscript kinds strip accidental ``` fences in the presentation buffer so
    /// pacer + bubble share one string (no dual strip / height snap).
    static func presentationContent(_ text: String, runKind: NovelRunKind?) -> String {
        switch runKind {
        case .prose, .regenerate, .polish:
            return NovelPromptCatalog.normalizedStreamingCandidateProse(text)
        case .quickStart, .characterProposal, .discussion, nil:
            return text
        }
    }

    static func step(
        displayedContent: String,
        targetContent: String,
        mode: Mode = .streaming,
        fixedTerminalAdvance: Int? = nil
    ) -> Step {
        if displayedContent == targetContent {
            return Step(content: targetContent, isCaughtUp: true)
        }
        let advanceBudget: (Int) -> Int = { backlog in
            switch mode {
            case .streaming:
                return textAdvance(backlogCount: backlog)
            case .terminalDrain:
                return StreamPresentationPacingPolicy.terminalTextAdvance(
                    backlogCount: backlog,
                    fixedAdvance: fixedTerminalAdvance
                )
            }
        }
        if targetContent.hasPrefix(displayedContent) {
            let backlog = targetContent.count - displayedContent.count
            let advance = advanceBudget(backlog)
            let next = String(targetContent.prefix(displayedContent.count + advance))
            return Step(content: next, isCaughtUp: next == targetContent)
        }
        // Replacement / divergence: re-anchor on the longest common prefix and
        // pace toward the new target — never dump multi-k chars in one frame.
        let sharedCount = displayedContent.commonPrefix(with: targetContent).count
        let backlog = max(0, targetContent.count - sharedCount)
        let advance = max(advanceBudget(backlog), minimumTextAdvance)
        let nextCount = min(targetContent.count, sharedCount + advance)
        let next = String(targetContent.prefix(nextCount))
        return Step(content: next, isCaughtUp: next == targetContent)
    }

    /// Visible pacing base when terminal manuscript normalization may have
    /// stripped a streaming fence that the bubble already hid.
    static func terminalPacingBase(
        displayedContent: String,
        targetContent: String,
        runKind: NovelRunKind?
    ) -> String {
        let display = presentationContent(displayedContent, runKind: runKind)
        if targetContent.hasPrefix(display) { return display }
        return displayedContent
    }

    static func terminalStep(
        displayedContent: String,
        targetContent: String,
        runKind: NovelRunKind?,
        fixedTerminalAdvance: Int? = nil
    ) -> Step {
        let pacingBase = terminalPacingBase(
            displayedContent: displayedContent,
            targetContent: targetContent,
            runKind: runKind
        )
        return step(
            displayedContent: pacingBase,
            targetContent: targetContent,
            mode: .terminalDrain,
            fixedTerminalAdvance: fixedTerminalAdvance
        )
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

private struct NovelCharacterIdentityMentionsCacheKey: Equatable {
    let projectRevision: Int64
    let configRevision: Int64
    let branchID: NovelBranchID
    let stateSnapshotID: NovelStateSnapshotID
    let unresolvedNames: [String]
}

private struct NovelCharacterIdentityMentionsCacheEntry {
    let key: NovelCharacterIdentityMentionsCacheKey
    let value: [NovelCharacterIdentityMention]
}

@MainActor
@Observable
final class NovelSessionViewModel {
    var mode: NovelSessionMode = .discussPlan
    var granularity: NovelGenerationGranularity = .wholeChapter
    private(set) var binding: NovelSessionBinding?
    private(set) var transientTail: NovelSessionTransientTail?
    private(set) var sessionStartingRunID: NovelRunID?
    private(set) var isPerformingAction = false
    /// 正在采用润色版的候选 ID：气泡据此显示加载指示器。
    private(set) var adoptingPolishCandidateID: NovelCandidateID?
    private(set) var operationErrorMessage: String?
    private(set) var refreshErrorMessage: String?
    private(set) var lastFailure: NovelFailure?
    private(set) var polishRetryTransactionID: NovelPendingOperationID?
    private var batchPolishProgressStorage: NovelBatchPolishProgress?

    var batchPolishProgress: NovelBatchPolishProgress? {
        guard let progress = batchPolishProgressStorage,
              progress.binding == binding,
              snapshotMatchesBinding else { return nil }
        return progress
    }

    @ObservationIgnored private let workspace: NovelCreationViewModel
    @ObservationIgnored private var consumerTask: Task<Void, Never>?
    @ObservationIgnored private var consumerID: UUID?
    @ObservationIgnored private var attachAttemptID: UUID?
    @ObservationIgnored private var attachingRunID: NovelRunID?
    @ObservationIgnored private var attachingBindingToken: UUID?
    @ObservationIgnored private var consumerAttachmentDesired = true
    @ObservationIgnored private var bindingToken = UUID()
    @ObservationIgnored private var currentRunDraft: NovelSessionRunDraft?
    @ObservationIgnored private var lastRetryDraft: NovelSessionRunDraft?
    @ObservationIgnored private var lastRetryRunID: NovelRunID?
    @ObservationIgnored private var transientRunRecord: NovelActiveRunRecord?
    @ObservationIgnored private var terminalAwaitingRefresh = false
    @ObservationIgnored private var cancelledStartRunIDs: Set<NovelRunID> = []
    @ObservationIgnored private var answeringAskUserMessageID: NovelMessageID?
    /// Session-local card close after 写入正文. Not a durable message; leaving
    /// the project drops it. Avoids starting a follow-up model turn that locks the UI.
    private var locallyResolvedAskUser: [NovelMessageID: NovelAskUserResponse] = [:]
    @ObservationIgnored private var sessionActionOwnerID: UUID?
    @ObservationIgnored private var polishRetryTask: Task<Void, Never>?
    @ObservationIgnored private var polishRetryTaskBinding: NovelSessionBinding?
    @ObservationIgnored private var batchPolishTask: Task<Void, Never>?
    @ObservationIgnored private var batchPolishTaskBinding: NovelSessionBinding?
    @ObservationIgnored private var batchPolishOwnedRunID: NovelRunID?
    @ObservationIgnored private var batchPolishCancellationReason: NovelRunInterruptionReason = .user
    /// 批量循环自己发起单章润色时短暂置真,让 `canStart` 放行——否则折进 `isBusy` 的
    /// `isBatchPolishing` 会把批量自己的 `start` 也挡掉。只在 start 握手期间为真。
    @ObservationIgnored private var isBatchStartingRun = false
    /// 与 `batchPolishProgressStorage` 一样可观察，面板才能跟相位刷新。
    private var ghostwriteProgressStorage: NovelGhostwriteProgress?
    /// 面板选择的目标章数（1...10）；开跑时写入 progress。
    var ghostwriteTargetChapterCount: Int = NovelGhostwriteBatch.minChapterCount
    @ObservationIgnored var ghostwriteTask: Task<Void, Never>?
    @ObservationIgnored var ghostwriteTaskBinding: NovelSessionBinding?
    @ObservationIgnored private var ghostwriteBackgroundLeaseOwnerID: UUID?
    @ObservationIgnored var ghostwriteOwnedRunID: NovelRunID?
    /// pause / 离页取消时置 true：catch 不得把本批标成 `.cancelled`（会丢续跑与 sidecar）。
    @ObservationIgnored var ghostwriteCancelAsUserPause = false
    /// 代笔 pipeline 自己发起整章时短暂置真，避免 `isGhostwriting` 折进 `isBusy` 挡掉自己的 start。
    @ObservationIgnored var isGhostwriteStartingRun = false
    @ObservationIgnored private var projectionCache: NovelSessionProjectionCacheEntry?
    @ObservationIgnored private var pendingCharacterIdentityMentionsCache:
        NovelCharacterIdentityMentionsCacheEntry?
    @ObservationIgnored private var characterIdentityChoicesCache:
        (projectRevision: Int64, configRevision: Int64, value: [(material: NovelMaterialRecord, title: String)])?
    /// Session-open staging: body must not pull secondary chrome until this advances.
    private(set) var loadStage: NovelSessionLoadStage = .idle
#if DEBUG
    @ObservationIgnored private(set) var fullProjectionBuildCountForTesting = 0
#endif
    @ObservationIgnored private var presentationBuffer: NovelSessionPresentationBuffer?
    /// Quick Start keeps its structured transport text separate from the user-facing tail.
    /// Only the strict terminal decoder is allowed to commit proposal records.
    @ObservationIgnored private var quickStartStructuredContent: String?
    @ObservationIgnored private var characterProposalStructuredContent: String?
    @ObservationIgnored private var presentationFlushTask: Task<Void, Never>?
    /// 思考流 48ms 拍合并（与正文 presentationFlush 同源时钟）：网络 chunk 先并入
    /// pendingReasoningText，每拍一次合并进 row.reasoningContent。卡片内优化
    /// （尾段整体淡入、sizeThatFades 去 layoutIfNeeded、内部滚动 540pt/s 限速、
    /// cadence 门禁 p95≤40ms）都以「每拍一次 append」为前提——逐 chunk 直上 UI 时
    /// 每个网络 chunk 都触发整行重建+测量（真机思考框整体卡顿，探针实测 p95 88ms/
    /// max 1.38s）。只影响呈现节奏，不动 lifecycle broadcast 语义与 storage。
    @ObservationIgnored private var reasoningFlushTask: Task<Void, Never>?
    @ObservationIgnored private var reasoningFlushToken = UUID()
    @ObservationIgnored private var pendingReasoningText: String?
    /// 终态 tail 的延迟退役任务:完成后保留 tail 一个静窗再清空,避免完成瞬间整屏一跳。
    @ObservationIgnored private var terminalTailRetirementTask: Task<Void, Never>?
    /// 终态 tail 退役前的静窗时长,默认与底部跟随的 terminalQuietDelay 对齐(滚动落定
    /// 与 tail 退役同步)。测试可注入 0 走立即退役的快路径,保持旧的「完成即清空」契约。
    @ObservationIgnored private let terminalQuietDelay: TimeInterval
    @ObservationIgnored private let composerDefaults: UserDefaults
    /// 批量润色等待候选时的「run 落定」宽限窗:activeRunID 在 terminalAwaitingRefresh
    /// 窗口会短暂为 nil,落定判失败前必须先过这个窗。测试可注入小值加快失败用例。
    @ObservationIgnored private let batchPolishSettleGrace: TimeInterval
    @ObservationIgnored private let batchPolishCandidateTimeout: TimeInterval

    private static let presentationFlushDelayNanos: UInt64 = 48_000_000

    init(
        workspace: NovelCreationViewModel,
        terminalQuietDelay: TimeInterval = NovelSessionBottomFollowPolicy.terminalQuietDelay,
        batchPolishSettleGrace: TimeInterval = 5,
        batchPolishCandidateTimeout: TimeInterval = 900,
        composerDefaults: UserDefaults = .standard
    ) {
        self.workspace = workspace
        self.terminalQuietDelay = terminalQuietDelay
        self.batchPolishSettleGrace = batchPolishSettleGrace
        self.batchPolishCandidateTimeout = batchPolishCandidateTimeout
        self.composerDefaults = composerDefaults
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
        applyComposerPreference(project: project, branch: branch, persistFallback: true)
        hydrateTerminalState()
    }

    func setComposerIntent(_ intent: NovelComposerIntent) {
        applyComposerIntent(intent)
        if let projectID = binding?.projectID ?? workspace.selectedProjectID {
            NovelComposerIntentPreference.store(intent, for: projectID, defaults: composerDefaults)
        }
    }

    func reconcileComposerIntent() {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else {
            mode = .discussPlan
            return
        }
        applyComposerPreference(project: project, branch: branch, persistFallback: true)
    }

    private func applyComposerPreference(
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot,
        persistFallback: Bool
    ) {
        let stored = NovelComposerIntentPreference.stored(
            for: project.project.id,
            defaults: composerDefaults
        )
        let resolved = NovelComposerIntentPreference.resolve(
            stored: stored,
            collaborationMode: project.project.collaborationMode,
            hasConfirmedChapterPlan: project.confirmedChapterPlan(for: branch.branch.id) != nil
        )
        applyComposerIntent(resolved)
        if persistFallback, resolved != stored {
            NovelComposerIntentPreference.store(
                resolved,
                for: project.project.id,
                defaults: composerDefaults
            )
        }
    }

    private func applyComposerIntent(_ intent: NovelComposerIntent) {
        let values = intent.requestValues
        mode = values.mode
        if intent != .discuss {
            granularity = values.granularity
        }
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

    /// Presentation-facing list: empty until secondary chrome stage so open layout
    /// does not pay identity resolution in the same frames as core transcript.
    var pendingCharacterIdentityMentions: [NovelCharacterIdentityMention] {
        guard loadStage >= .secondaryChrome else { return [] }
        return resolvedPendingCharacterIdentityMentions
    }

    /// Domain-facing list: independent of load stage. Actions/tests must use this
    /// so closed-loop is not coupled to presentation staging.
    var resolvedPendingCharacterIdentityMentions: [NovelCharacterIdentityMention] {
        guard snapshotMatchesBinding,
              let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else { return [] }
        let state = branch.currentState
        // Cache: body re-evaluates this getter continuously; large projects hit
        // 0x8BADF00D when this path (or the cards it drives) runs every frame.
        let cacheKey = NovelCharacterIdentityMentionsCacheKey(
            projectRevision: project.project.revision,
            configRevision: project.project.configRevision,
            branchID: branch.branch.id,
            stateSnapshotID: state.id,
            unresolvedNames: state.unresolvedEntityNames
        )
        if let cached = pendingCharacterIdentityMentionsCache, cached.key == cacheKey {
            return cached.value
        }

        // Fast known-set only: current titles + stored aliases.
        // Do NOT walk appliedOperations / settingProposals / revision history here —
        // that O(ops × materials) work used to freeze session open on 赵大来了-scale
        // projects. Missing a historical alias only risks an extra card, not data loss.
        var knownKeys: Set<String> = []
        knownKeys.reserveCapacity(max(16, workspace.activeMaterials.count * 2))
        for material in workspace.activeMaterials where material.kind == .character {
            if let revision = NovelPresentation.effectiveRevision(
                for: material,
                project: project,
                branch: branch
            ) {
                let titleKey = NovelCharacterIdentityResolver.normalize(revision.title)
                if !titleKey.isEmpty { knownKeys.insert(titleKey) }
            }
            for alias in material.aliases {
                let aliasKey = NovelCharacterIdentityResolver.normalize(alias)
                if !aliasKey.isEmpty { knownKeys.insert(aliasKey) }
            }
        }
        let clarifiedKeys = Set(
            state.characterIdentityClarifications.map {
                NovelCharacterIdentityResolver.normalize($0.mention)
            }
        )
        var namesByKey: [String: String] = [:]
        for name in state.unresolvedEntityNames {
            let key = NovelCharacterIdentityResolver.normalize(name)
            // Immediate presentation filter: stale place names already stored
            // in unresolvedEntityNames must not open as 「确认人物身份」 cards.
            guard !key.isEmpty,
                  NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate(name),
                  !clarifiedKeys.contains(key),
                  !knownKeys.contains(key) else { continue }
            namesByKey[key] = name
        }
        let value = namesByKey.values.sorted {
            $0.localizedStandardCompare($1) == .orderedAscending
        }.map(NovelCharacterIdentityMention.init(name:))
        pendingCharacterIdentityMentionsCache = NovelCharacterIdentityMentionsCacheEntry(
            key: cacheKey,
            value: value
        )
        return value
    }

    /// Cap session cards so a large unresolved set cannot paint dozens of heavy
    /// forms in one ScrollView body (true freeze source after open).
    static let maxVisibleCharacterIdentityCards = 3
    /// Cap inline choice buttons; longer character rosters use a compact menu.
    static let maxInlineCharacterIdentityChoices = 6

    /// Presentation-facing choices (stage-gated). Domain/actions use
    /// `resolvedCharacterIdentityChoices`.
    var characterIdentityChoices: [(material: NovelMaterialRecord, title: String)] {
        guard loadStage >= .secondaryChrome else { return [] }
        return resolvedCharacterIdentityChoices
    }

    var resolvedCharacterIdentityChoices: [(material: NovelMaterialRecord, title: String)] {
        guard snapshotMatchesBinding,
              let project = workspace.projectSnapshot else { return [] }
        let rev = project.project.revision
        let config = project.project.configRevision
        if let cached = characterIdentityChoicesCache,
           cached.projectRevision == rev,
           cached.configRevision == config {
            return cached.value
        }
        // Titles only — do not expand proposal/history aliases here.
        let value: [(material: NovelMaterialRecord, title: String)] = workspace.activeMaterials
            .compactMap { material in
                guard material.kind == .character,
                      let revision = NovelPresentation.effectiveRevision(
                          for: material,
                          project: project,
                          branch: workspace.branchSnapshot
                      ) else { return nil }
                return (material, revision.title)
            }
            .sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        characterIdentityChoicesCache = (rev, config, value)
        return value
    }

    /// Best existing character for a pending mention (deterministic, offline).
    /// Used for the one-tap 「确认为…」 primary action on identity cards.
    func recommendedCharacterIdentityChoice(
        for mention: String
    ) -> (material: NovelMaterialRecord, title: String)? {
        let choices = resolvedCharacterIdentityChoices
        guard !choices.isEmpty else { return nil }
        let candidates = choices.map { choice in
            (
                id: choice.material.id.description,
                title: choice.title,
                aliases: choice.material.aliases
            )
        }
        guard let match = NovelCharacterIdentityResolver.recommendedIdentityMatch(
            mention: mention,
            candidates: candidates
        ) else { return nil }
        return choices.first {
            $0.material.id.description == match.id
        }
    }

    /// Advance open staging. Call only from the session view's sequenced open task
    /// (or binding reset). Stages only move forward, except reset to `.idle`.
    /// Non-idle targets require an established core bind first (no idle→secondary leap).
    func advanceLoadStage(to stage: NovelSessionLoadStage) {
        if stage == .idle {
            loadStage = .idle
            pendingCharacterIdentityMentionsCache = nil
            characterIdentityChoicesCache = nil
            return
        }
        guard stage > loadStage else { return }
        if stage > .coreTranscript, loadStage < .coreTranscript {
            // Cannot skip past core without a successful bind.
            return
        }
        loadStage = stage
        // Warm identity caches once when secondary chrome opens so the first body
        // that paints cards does not pay resolution + choice sort mid-layout.
        if stage >= .secondaryChrome {
            _ = resolvedPendingCharacterIdentityMentions
            _ = resolvedCharacterIdentityChoices
        }
    }

    func activeCharacterProposal(
        for mention: String
    ) -> NovelSettingProposalRecord? {
        let key = NovelCharacterIdentityResolver.normalize(mention)
        return workspace.branchSnapshot?.activeSettingProposals.first { proposal in
            guard case .some(.contextualCharacter(
                _,
                let sourceMention,
                .character
            )) = proposal.origin else { return false }
            return NovelCharacterIdentityResolver.normalize(sourceMention) == key
        }
    }

    func relatedCharacterProposalCount(for mention: String) -> Int {
        let key = NovelCharacterIdentityResolver.normalize(mention)
        return workspace.branchSnapshot?.activeSettingProposals.filter { proposal in
            guard case .some(.contextualCharacter(
                _,
                let sourceMention,
                let kind
            )) = proposal.origin,
                  kind != .character else { return false }
            return NovelCharacterIdentityResolver.normalize(sourceMention) == key
        }.count ?? 0
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
            guard let tail = transientTail else {
                return overlayLocalAskUserAnswers(cached.model)
            }
            if let updated = NovelSessionPresentation.updatingTransientTail(
                in: cached.model,
                with: tail
            ) {
                projectionCache = NovelSessionProjectionCacheEntry(key: key, model: updated)
                return overlayLocalAskUserAnswers(updated)
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
        return overlayLocalAskUserAnswers(model)
    }

    private func overlayLocalAskUserAnswers(_ model: NovelSessionListModel) -> NovelSessionListModel {
        guard !locallyResolvedAskUser.isEmpty else { return model }
        var changed = false
        let rows = model.rows.map { row -> NovelSessionRowModel in
            guard let askUser = row.askUser,
                  askUser.response == nil,
                  let response = locallyResolvedAskUser[row.id] else {
                return row
            }
            changed = true
            let resolved = NovelAskUserPresentation(prompt: askUser.prompt, response: response)
            return NovelSessionRowModel(
                id: row.id,
                sequence: row.sequence,
                role: row.role,
                mode: row.mode,
                granularity: row.granularity,
                kind: row.kind,
                content: row.content,
                reasoningContent: row.reasoningContent,
                isReasoningLive: row.isReasoningLive,
                createdAt: row.createdAt,
                runID: row.runID,
                runStatus: row.runStatus,
                candidate: row.candidate,
                committedChange: row.committedChange,
                askUser: resolved,
                archive: row.archive,
                transientPhase: row.transientPhase,
                actions: row.actions,
                digest: NovelSessionRowDigest(
                    layout: row.digest.layout.replacingOccurrences(of: ":pending;", with: ":answered;"),
                    presentation: row.digest.presentation
                ),
                lagAllowance: row.lagAllowance
            )
        }
        guard changed else { return model }
        return NovelSessionListModel(
            sessionID: model.sessionID,
            rows: rows,
            activeTailID: model.activeTailID
        )
    }

    /// Build the session list model off the main actor, then install the cache.
    /// Call before advancing to `.coreTranscript` on cold open.
    private func warmProjectionCache(
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot,
        expandedArchiveIDs: Set<NovelMessageID>
    ) async {
        let key = NovelSessionProjectionCacheKey(
            project: project,
            branch: branch,
            expandedArchiveIDs: expandedArchiveIDs,
            transientTail: transientTail
        )
        if let cached = projectionCache, cached.key == key {
            return
        }
        let input = NovelSessionProjectionInput(
            project: project,
            branch: branch,
            expandedArchiveIDs: expandedArchiveIDs,
            transientTail: transientTail
        )
        let model = await Task.detached(priority: .userInitiated) {
            NovelSessionPresentation.project(input)
        }.value
        // Drop result if the user switched sessions while projecting.
        guard binding?.projectID == project.project.id,
              binding?.branchID == branch.branch.id else { return }
        #if DEBUG
        fullProjectionBuildCountForTesting += 1
        #endif
        projectionCache = NovelSessionProjectionCacheEntry(key: key, model: model)
    }

    var currentChapterVersions: [NovelChapterVersionRecord] {
        guard snapshotMatchesBinding,
              let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else { return [] }
        let versionsByID = Dictionary(uniqueKeysWithValues: project.chapterVersions.map { ($0.id, $0) })
        return branch.chapterSelections.compactMap { versionsByID[$0.versionID] }
    }

    var hasPolishableChapters: Bool {
        guard snapshotMatchesBinding,
              let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else { return false }
        let selectedChapterIDs = Set(branch.chapterSelections.map(\.chapterID))
        return project.chapters.contains {
            selectedChapterIDs.contains($0.id) && $0.discardedAt == nil
        }
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

    var unresolvedBranchPolishTransactions: [NovelPendingPolishTransactionRecord] {
        branchPolishTransactions.filter {
            $0.status == .pending || $0.status == .retryable || $0.status == .blocked
        }
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
        isStarting || terminalAwaitingRefresh || isPerformingAction ||
            workspace.isPerforming || isBatchPolishing ||
            (isGhostwriting && !isGhostwriteStartingRun)
    }

    /// 批量整章润色是否正在进行。派生自进度阶段,随 `batchPolishProgress` 一起被观察;
    /// 折进 `isBusy` 后,现有 `canStart`、阅读器门禁与输入框禁用都自动挡住并发起跑。
    var isBatchPolishing: Bool {
        batchPolishProgress?.phase == .running
    }

    /// 目录入口、选择页和报告重试共用同一个门禁，避免先看到可用按钮、
    /// 进入下一层才发现不能开始。
    var batchPolishBlocker: NovelSessionActionBlocker? {
        guard access == .readWrite else { return .projectReadOnly }
        if workspace.requiresReload || hasRefreshError { return .reloadRequired }
        if isRunning { return .generationRunning }
        if needsSync { return .branchNeedsSync }
        if !branchPendingOperations.isEmpty || !unresolvedBranchPolishTransactions.isEmpty {
            return .pendingOperation
        }
        if isBusy { return .transactionInProgress }
        return nil
    }

    var canStartBatchPolish: Bool {
        hasPolishableChapters && batchPolishBlocker == nil
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
            canStart(kind: run.kind, granularity: run.granularity) &&
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

    func bindToCurrentSelection(
        expandedArchiveIDs: Set<NovelMessageID> = []
    ) async {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              workspace.selectedProjectID == project.project.id,
              workspace.selectedBranchID == branch.branch.id else {
            await resetBinding()
            return
        }
        let next = NovelSessionBinding(
            projectID: project.project.id,
            branchID: branch.branch.id
        )
        let didChange = binding != next
        if didChange {
            await cancelPolishRetryForBindingChange(from: binding)
            await cancelBatchPolishForBindingChange(from: binding)
            await cancelGhostwriteForBindingChange(from: binding)
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
            applyComposerPreference(project: project, branch: branch, persistFallback: true)
            // New session: drop secondary chrome and projection until staged open advances.
            advanceLoadStage(to: .idle)
            projectionCache = nil
            pendingCharacterIdentityMentionsCache = nil
            characterIdentityChoicesCache = nil
            locallyResolvedAskUser = [:]
        }
        consumerAttachmentDesired = true
        hydrateTerminalState()
        // Project off the main actor while still at .idle so the open spinner can
        // animate; sync project() on MainActor was freezing the scene (watchdog).
        await warmProjectionCache(
            project: project,
            branch: branch,
            expandedArchiveIDs: expandedArchiveIDs
        )
        guard binding == next else { return }
        advanceLoadStage(to: .coreTranscript)
        // Ghostwrite restore / run attach after first core frame is allowed to commit.
        await Task.yield()
        guard binding == next else { return }
        await restoreGhostwriteProgressIfNeeded(for: next)
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
                  sessionStartingRunID == nil,
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
        let token = bindingToken
        guard await refreshDurable(binding: binding, token: token) else { return false }
        if consumerAttachmentDesired, consumerTask == nil, let run = activeRun {
            await attach(to: run)
        }
        return bindingToken == token && refreshErrorMessage == nil
    }

    func detachConsumer() {
        consumerAttachmentDesired = false
        clearConsumer()
    }

    private func clearConsumer() {
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
        guard answeringAskUserMessageID != promptMessageID else { return false }
        guard let promptMessage = durableMessages.first(where: {
            $0.id == promptMessageID && $0.role == .assistant
        }), case .some(.askUser(let prompt)) = promptMessage.interaction else {
            operationErrorMessage = "这个问题已经失效，请重新发起讨论。"
            return false
        }
        answeringAskUserMessageID = promptMessageID
        defer { answeringAskUserMessageID = nil }
        if let revision = prompt.chapterRevision {
            let trimmed = answer.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed == NovelChapterRevisionApproval.approveOption {
                guard await applyChapterRevision(revision) else { return false }
                locallyResolvedAskUser[promptMessageID] = NovelAskUserResponse(
                    promptMessageID: promptMessageID,
                    answer: trimmed
                )
                await syncRevisionPlotState(chapterID: revision.chapterID)
                operationErrorMessage = nil
                return true
            } else if trimmed != NovelChapterRevisionApproval.rejectOption {
                operationErrorMessage = "请选择写入正文或拒绝这次修改。"
                return false
            }
        }
        if let revert = prompt.manuscriptRevert {
            let trimmed = answer.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed == NovelManuscriptRevertApproval.approveOption {
                guard await applyManuscriptRevert(revert) else { return false }
                locallyResolvedAskUser[promptMessageID] = NovelAskUserResponse(
                    promptMessageID: promptMessageID,
                    answer: trimmed
                )
                operationErrorMessage = nil
                return true
            } else if trimmed != NovelManuscriptRevertApproval.rejectOption {
                operationErrorMessage = "请选择回退这几章或取消回退。"
                return false
            }
        }
        let response = NovelAskUserResponse(
            promptMessageID: promptMessageID,
            answer: answer
        )
        if let promptRunID = promptMessage.runID,
           workspace.projectSnapshot?.activeRuns.first(where: {
               $0.id == promptRunID
           })?.kind == .quickStart {
            guard let runID = await workspace.startQuickStartSuggestions(
                exactUserText: answer,
                askUserResponse: response
            ) else { return false }
            operationErrorMessage = nil
            refreshErrorMessage = nil
            await bindToCurrentSelection()
            return activeRunID == runID
        }
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

    private func applyChapterRevision(_ proposal: NovelChapterRevisionProposal) async -> Bool {
        if isGhostwriting {
            operationErrorMessage = "代笔正在推进本章，暂时不能改正文。"
            return false
        }
        guard let binding,
              let snapshot = workspace.projectSnapshot,
              let branch = snapshot.branches.first(where: { $0.id == binding.branchID }),
              let selection = branch.workingChapterSelections.first(where: {
                  $0.chapterID == proposal.chapterID
              }),
              let version = snapshot.chapterVersions.first(where: {
                  $0.id == selection.versionID && $0.chapterID == proposal.chapterID
              }) else {
            operationErrorMessage = "目标章节已经不在工作正文里，无法写入。"
            return false
        }
        let replaced: (oldText: String, newContent: String)
        do {
            replaced = try NovelParagraphParser.replacingParagraphs(
                in: version.content,
                start: proposal.startParagraph,
                end: proposal.endParagraph,
                with: proposal.newText
            )
        } catch {
            operationErrorMessage = "改正文失败：\(error.localizedDescription)"
            return false
        }
        guard replaced.oldText == proposal.oldText else {
            operationErrorMessage = "正文已变化，请重新读取后再改。"
            return false
        }
        let saved = await workspace.saveManualRewrite(
            chapterID: proposal.chapterID,
            title: version.title,
            content: replaced.newContent,
            schedulesAutomaticSync: false
        )
        if !saved {
            operationErrorMessage = workspace.errorMessage ?? "改正文保存失败，请重试。"
            return false
        }
        return true
    }

    private func applyManuscriptRevert(_ proposal: NovelManuscriptRevertProposal) async -> Bool {
        if isGhostwriting {
            operationErrorMessage = "代笔正在推进本章，暂时不能回退章节。"
            return false
        }
        let reverted = await workspace.revertRecentChapters(proposal)
        if !reverted {
            operationErrorMessage = workspace.errorMessage ?? "回退章节失败，请重试。"
            return false
        }
        return true
    }

    private func syncRevisionPlotState(chapterID: NovelChapterID) async {
        guard let branch = workspace.branchSnapshot else { return }
        let isLastWorkingChapter = branch.branch.workingChapterSelections.last?.chapterID
            == chapterID
        if isLastWorkingChapter {
            let synced = await workspace.syncWorkingManuscript(preferStateDelta: true)
            if !synced {
                workspace.scheduleAutomaticStateSync(
                    projectID: branch.projectID,
                    branchID: branch.branch.id
                )
            }
            return
        }
        workspace.scheduleAutomaticStateSync(
            projectID: branch.projectID,
            branchID: branch.branch.id
        )
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

    @discardableResult
    func ignoreCharacterIdentityMention(_ mention: String) async -> Bool {
        await clarifyCharacterIdentityMention(
            mention,
            clarification: "这是一次性出现的路人，不需要建立人物档案。"
        )
    }

    @discardableResult
    func clarifyCharacterIdentityMention(
        _ mention: String,
        clarification: String
    ) async -> Bool {
        let normalized = clarification.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            operationErrorMessage = "请先输入人物身份说明。"
            return false
        }
        let succeeded = await workspace.clarifyCharacterIdentity(
            mention: mention,
            clarification: normalized
        )
        operationErrorMessage = workspace.errorMessage
        _ = await refreshDurable(binding: binding, token: bindingToken)
        return succeeded && workspace.errorMessage == nil
    }

    @discardableResult
    func startCharacterProposal(
        for mention: String,
        guidance: String
    ) async -> Bool {
        let normalizedMention = mention.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedGuidance = guidance.trimmingCharacters(in: .whitespacesAndNewlines)
        guard resolvedPendingCharacterIdentityMentions.contains(where: {
            NovelCharacterIdentityResolver.normalize($0.name) ==
                NovelCharacterIdentityResolver.normalize(normalizedMention)
        }) else {
            operationErrorMessage = "这个人物身份问题已经失效。"
            return false
        }
        guard activeCharacterProposal(for: normalizedMention) == nil else {
            operationErrorMessage = "这个人物已有待确认建议。"
            return false
        }
        var userText = """
        请为正文中新出现但尚未建档的人物生成一份可确认的人物建议。

        当前称呼：\(normalizedMention)
        """
        if !normalizedGuidance.isEmpty {
            userText += "\n\n作者补充：\(normalizedGuidance)"
        }
        return await start(NovelSessionRunDraft(
            kind: .characterProposal,
            mode: .discussPlan,
            granularity: nil,
            userText: userText,
            sourceChapterVersionID: nil,
            askUserResponse: nil,
            injectionOverrides: .none,
            inputBudgetTokens: 16_000,
            contextualCharacterMention: normalizedMention
        ))
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
        guard let runID = lastRetryRunID else {
            operationErrorMessage = "没有可重试的生成，请重新发送。"
            return false
        }
        // One refresh clears stale revision/snapshot before exact retry — the
        // common "状态不匹配" after a failed stream that still left UI on an old
        // config revision.
        if !(await refresh()) {
            if operationErrorMessage == nil {
                operationErrorMessage = "无法刷新项目状态，请退出后重新进入再试。"
            }
            return false
        }
        let ok = await retryGeneration(runID: runID)
        if !ok, operationErrorMessage == nil {
            operationErrorMessage = "暂时无法按原请求重试。请重新发送，或重新载入项目后再试。"
        }
        return ok
    }

    @discardableResult
    func retryGeneration(runID: NovelRunID) async -> Bool {
        // Same as retryLastTerminal: refresh first so expected revisions match
        // disk after a failed stream left the UI on an older snapshot.
        if !(await refresh()) {
            if operationErrorMessage == nil {
                operationErrorMessage = "无法刷新项目状态，请退出后重新进入再试。"
            }
            return false
        }
        guard let run = workspace.projectSnapshot?.activeRuns.first(where: {
            $0.id == runID && $0.branchID == binding?.branchID &&
                ($0.status == .failed || $0.status == .interrupted)
        }), isEligibleForExactRetry(run) else {
            if operationErrorMessage == nil {
                operationErrorMessage = "这次生成已不可精确重试（分支或检查点已变化）。请重新发送。"
            }
            return false
        }
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
        guard let draft = draft(for: run) else {
            if operationErrorMessage == nil {
                operationErrorMessage = "找不到原请求内容，请重新发送。"
            }
            return false
        }
        // Keep composer chrome aligned with the run being retried so the dock
        // does not still show "讨论" while an exact prose/polish retry starts.
        mode = draft.mode
        if let granularity = draft.granularity {
            self.granularity = granularity
        }
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
        target: NovelCollectionTarget,
        source: NovelCollectionSource = .user
    ) async -> Bool {
        guard snapshotMatchesBinding else {
            operationErrorMessage = "会话已切换，无法收录。"
            return false
        }
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else {
            operationErrorMessage = "项目未就绪，无法收录。"
            return false
        }
        guard let candidate = candidate(id: candidateID),
              candidate.kind == .prose,
              candidate.status == .available || candidate.status == .interrupted else {
            operationErrorMessage = "没有可收录的完整正文候选。"
            return false
        }
        guard branch.branch.syncStatus == .synchronized else {
            operationErrorMessage = "分支待同步，无法收录正文。"
            return false
        }
        guard branchPendingOperations.isEmpty else {
            operationErrorMessage = "仍有未完成的同步或事务，无法收录。"
            return false
        }
        guard branch.branch.activeRunID == nil else {
            operationErrorMessage = "生成尚未完全结束，无法收录。"
            return false
        }
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
            factCompatibilityID: UUID(),
            source: source
        ))
        // beginAction 失败时 perform 会静默返回 nil，这里先占锁并给出明确原因。
        guard beginAction() else {
            operationErrorMessage = workspace.requiresReload
                ? "项目需要重新载入，无法收录。"
                : "有其他操作进行中，无法收录。"
            return false
        }
        let outcome: NovelOutcome?
        do {
            outcome = try await workspace.performSessionAction(action)
            operationErrorMessage = nil
        } catch {
            outcome = nil
            operationErrorMessage = describe(error)
        }
        endAction()
        _ = await refreshDurable(binding: binding, token: bindingToken)
        guard outcome != nil else { return false }
        if workspace.branchSnapshot?.branch.syncStatus == .needsSync {
            workspace.scheduleAutomaticStateSync(
                projectID: project.project.id,
                branchID: branch.branch.id
            )
        }
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
        } catch is CancellationError {
            return nil
        } catch let error as NovelStructuredModelExecutionFailure
            where error.failure.code == "cancelled" {
            return nil
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
              let pending = project.pendingOperations.first(where: { $0.id == pendingID }),
              snapshotMatchesBinding else { return }
        if pending.kind == .manualSync {
            await workspace.retryPending(pendingID)
            return
        }
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
        adoptingPolishCandidateID = candidateID
        defer { adoptingPolishCandidateID = nil }
        await applyPolishAdoption(command)
        // 与 collectCandidate 对齐：采用后自动触发剧情同步。
        workspace.scheduleAutomaticStateSync(
            projectID: project.project.id,
            branchID: branch.branch.id
        )
    }

    func retryPolishTransaction(_ transactionID: NovelPendingOperationID) async {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let transaction = project.polishTransactions.first(where: {
                  $0.id == transactionID && $0.branchID == branch.branch.id &&
                      ($0.status == .pending || $0.status == .retryable)
              }), snapshotMatchesBinding,
              polishTransactionSourceBlocker(transactionID) == nil else { return }
        let chapterID = project.chapterVersions.first {
            $0.id == transaction.sourceChapterVersionID
        }?.chapterID
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
        let durableStatus = workspace.projectSnapshot?.polishTransactions.first(where: {
            $0.id == transactionID
        })?.status
        if Task.isCancelled,
           durableStatus != .completed,
           durableStatus != .incompatible {
            operationErrorMessage = nil
            return
        }
        reconcileBatchPolishResult(transactionID: transactionID, chapterID: chapterID)
    }

    @discardableResult
    func startPolishRetry(_ transactionID: NovelPendingOperationID) -> Bool {
        guard polishRetryTask == nil,
              let binding,
              unresolvedBranchPolishTransactions.contains(where: {
                  $0.id == transactionID &&
                      ($0.status == .pending || $0.status == .retryable)
              }),
              polishTransactionSourceBlocker(transactionID) == nil else { return false }
        polishRetryTransactionID = transactionID
        polishRetryTaskBinding = binding
        polishRetryTask = Task { @MainActor [weak self] in
            await self?.retryPolishTransaction(transactionID)
            guard let self,
                  self.polishRetryTransactionID == transactionID else { return }
            self.polishRetryTransactionID = nil
            self.polishRetryTask = nil
            self.polishRetryTaskBinding = nil
        }
        return true
    }

    func cancelPolishRetry() {
        polishRetryTask?.cancel()
    }

    func polishTransactionSourceBlocker(
        _ transactionID: NovelPendingOperationID
    ) -> NovelSessionActionBlocker? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let transaction = project.polishTransactions.first(where: {
                  $0.id == transactionID && $0.branchID == branch.branch.id
              }),
              let source = project.chapterVersions.first(where: {
                  $0.id == transaction.sourceChapterVersionID
              }),
              branch.branch.workingChapterSelections.contains(where: {
                  $0.chapterID == source.chapterID && $0.versionID == source.id
              }),
              project.chapters.contains(where: {
                  $0.id == source.chapterID && $0.discardedAt == nil
              }) else {
            return .sourceChapterChanged
        }
        return nil
    }

    @discardableResult
    func abandonPolishTransaction(_ transactionID: NovelPendingOperationID) async -> Bool {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let transaction = project.polishTransactions.first(where: {
                  $0.id == transactionID && $0.branchID == branch.branch.id
              }), snapshotMatchesBinding else {
            operationErrorMessage = workspace.requiresReload
                ? "项目已变化，请重新载入后再试。"
                : "这项润色已经处理，或当前分支已经变化。"
            return false
        }
        let chapterID = project.chapterVersions.first {
            $0.id == transaction.sourceChapterVersionID
        }?.chapterID
        operationErrorMessage = nil
        let outcome = await perform(.abandonPolishTransaction(
            NovelAbandonPolishTransactionCommand(
                context: mutationContext(project: project, branch: branch),
                projectID: project.project.id,
                branchID: branch.branch.id,
                transactionID: transactionID
            )
        ))
        reconcileBatchPolishResult(transactionID: transactionID, chapterID: chapterID)
        guard let outcome,
              case .polishTransactionAbandoned = outcome else {
            if operationErrorMessage == nil {
                operationErrorMessage = workspace.requiresReload
                    ? "项目已变化，请重新载入后再试。"
                    : "当前有其他操作正在处理，请稍后再试。"
            }
            return false
        }
        return true
    }

    @discardableResult
    func abandonPolishTransactions(
        _ transactionIDs: [NovelPendingOperationID]
    ) async -> Int {
        let ownerBinding = binding
        var abandonedCount = 0
        for transactionID in transactionIDs {
            guard !Task.isCancelled,
                  binding == ownerBinding else { break }
            guard await abandonPolishTransaction(transactionID) else { break }
            abandonedCount += 1
        }
        return abandonedCount
    }

    // MARK: - 批量整章润色

    /// 发起批量整章润色:按 `chapterIDs` 顺序逐章「生成候选 → 采用(含漂移校验)」。
    /// 必须串行——一个项目同一时刻只能跑一个 run,且采用会推进分支 head 使其他已生成
    /// 候选作废,所以只能生成一章、采用一章、再下一章。通过漂移校验的章自动采用为新
    /// 版本;改了剧情的章自动跳过;失败章记录在案。任务句柄留在 ViewModel 上,关闭
    /// sheet 不中断,只有 `cancelBatchPolish()` 会停止。
    @discardableResult
    func startBatchPolish(chapterIDs: [NovelChapterID]) -> Bool {
        guard batchPolishTask == nil,
              let binding,
              !chapterIDs.isEmpty,
              canStartBatchPolish else {
            operationErrorMessage = batchPolishBlocker?.displayName
                ?? (chapterIDs.isEmpty ? "请至少选择一章。" : "批量润色暂时不能开始。")
            return false
        }
        operationErrorMessage = nil
        batchPolishProgressStorage = NovelBatchPolishProgress(
            binding: binding,
            total: chapterIDs.count,
            completed: 0,
            currentTitle: nil,
            phase: .running,
            results: [],
            startedAt: Date()
        )
        batchPolishTaskBinding = binding
        batchPolishCancellationReason = .user
        batchPolishTask = Task { @MainActor [weak self] in
            await self?.runBatchPolish(chapterIDs: chapterIDs, binding: binding)
            guard self?.batchPolishTaskBinding == binding else { return }
            self?.batchPolishTask = nil
            self?.batchPolishTaskBinding = nil
            self?.batchPolishOwnedRunID = nil
        }
        return true
    }

    func cancelBatchPolish() {
        guard batchPolishTask != nil else { return }
        batchPolishCancellationReason = .user
        batchPolishTask?.cancel()
    }

    private func cancelBatchPolishForBindingChange(
        from expectedBinding: NovelSessionBinding?
    ) async {
        guard let expectedBinding else {
            batchPolishProgressStorage = nil
            return
        }
        if batchPolishTaskBinding == expectedBinding, let task = batchPolishTask {
            batchPolishCancellationReason = .routeExit
            task.cancel()
            await task.value
        }
        if batchPolishProgressStorage?.binding == expectedBinding {
            batchPolishProgressStorage = nil
        }
    }

    /// 报告态下「再润色一批」用:清空进度回到选择态。运行中不允许清。
    func clearBatchPolish() {
        guard batchPolishTask == nil,
              unresolvedBranchPolishTransactions.isEmpty else { return }
        batchPolishProgressStorage = nil
    }

    /// 报告态下「重试失败章节」:只重跑失败和未处理的章,跳过已成功和漂移跳过的。
    @discardableResult
    func retryFailedBatchPolish() -> Bool {
        guard batchPolishTask == nil,
              let progress = batchPolishProgress else { return false }
        let retryIDs = progress.results
            .filter { $0.outcome == .failed || $0.outcome == .cancelled }
            .map(\.chapterID)
        guard !retryIDs.isEmpty else { return false }
        return startBatchPolish(chapterIDs: retryIDs)
    }

    private func runBatchPolish(
        chapterIDs: [NovelChapterID],
        binding expectedBinding: NovelSessionBinding
    ) async {
        var results: [NovelBatchPolishChapterResult] = []
        do {
            for (index, chapterID) in chapterIDs.enumerated() {
                try Task.checkCancellation()
                // 章间主动让快照追上 durable:清掉上一章可能残留的 terminalAwaitingRefresh,
                // 保证本章 start 的 canStart 门禁不被卡。refreshDurable 幂等且不 rebind/detach。
                _ = await refreshDurable(binding: binding, token: bindingToken)
                let title = batchPolishTitle(for: chapterID, ordinal: index + 1)
                mutateBatchProgress(binding: expectedBinding) {
                    $0.completed = index
                    $0.currentTitle = title
                }
                let result = try await polishOneChapter(chapterID: chapterID, title: title)
                batchPolishOwnedRunID = nil
                results.append(result)
                try Task.checkCancellation()
                mutateBatchProgress(binding: expectedBinding) {
                    $0.completed = index + 1
                    $0.results = results
                }
                if !unresolvedBranchPolishTransactions.isEmpty {
                    results.append(contentsOf: unhandledBatchResults(
                        chapterIDs: chapterIDs,
                        handled: results,
                        outcome: .cancelled,
                        message: "前一章的润色检查需要先处理。"
                    ))
                    mutateBatchProgress(binding: expectedBinding) {
                        $0.completed = index + 1
                        $0.currentTitle = nil
                        $0.phase = .done
                        $0.results = results
                    }
                    return
                }
            }
            mutateBatchProgress(binding: expectedBinding) {
                $0.completed = chapterIDs.count
                $0.currentTitle = nil
                $0.phase = .done
                $0.results = results
            }
        } catch is CancellationError {
            await stopOwnedBatchPolishRun(
                binding: expectedBinding,
                reason: batchPolishCancellationReason
            )
            batchPolishOwnedRunID = nil
            // 用户按了停止:已采用的保留,未轮到的标 cancelled,不弹错误。
            results.append(contentsOf: unhandledBatchResults(
                chapterIDs: chapterIDs,
                handled: results,
                outcome: .cancelled,
                message: nil
            ))
            mutateBatchProgress(binding: expectedBinding) {
                $0.currentTitle = nil
                $0.phase = .cancelled
                $0.results = results
            }
        } catch {
            batchPolishOwnedRunID = nil
            // 意料外的错误:剩余章记 failed 收口,保留已完成的结果。
            results.append(contentsOf: unhandledBatchResults(
                chapterIDs: chapterIDs,
                handled: results,
                outcome: .failed,
                message: describe(error)
            ))
            mutateBatchProgress(binding: expectedBinding) {
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
        batchPolishOwnedRunID = activeRunID
        isBatchStartingRun = false
        // 停止也可能落在 run 启动握手内；`start` 会把取消收口成 false，若这里不
        // 重新检查，当前章会被误报为普通启动失败。
        try Task.checkCancellation()
        guard started else {
#if DEBUG
            print("[BatchPolish] start failed \(chapterID): \(batchPolishDiagnostic())")
#endif
            return NovelBatchPolishChapterResult(
                chapterID: chapterID, title: title, outcome: .failed,
                message: errorMessage ?? "润色没有开始,请稍后重试。"
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
#if DEBUG
            print("[BatchPolish] no candidate \(chapterID): \(batchPolishDiagnostic())")
#endif
            return NovelBatchPolishChapterResult(
                chapterID: chapterID, title: title, outcome: .failed,
                message: errorMessage ?? "润色没有产出候选。"
            )
        }
        await adoptPolishCandidate(candidateID)
        let transaction = branchPolishTransactions.first(where: {
            $0.candidateID == candidateID
        })
        if Task.isCancelled {
            switch transaction?.status {
            case .completed, .incompatible:
                break
            case .pending, .retryable, .blocked, .abandoned, nil:
                operationErrorMessage = nil
                throw CancellationError()
            }
        }
        if let transaction {
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
            // 停止可能与上面的 durable refresh 竞速。refresh 本身不抛取消；若不在
            // await 后重新检查，刚被 stop 收口的当前章会被误记为“生成失败”，而不是
            // 用户明确停止后的“未处理”。
            try Task.checkCancellation()
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
                // 硬超时但 run 仍在跑：停掉它，否则后续章节全被 canStart 的
                // !isRunning 门禁挡住，级联失败。
                if activeRunID != nil {
                    await stop(reason: .user)
                }
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

    private func mutateBatchProgress(
        binding expectedBinding: NovelSessionBinding? = nil,
        _ mutate: (inout NovelBatchPolishProgress) -> Void
    ) {
        guard var progress = batchPolishProgressStorage,
              progress.binding == (expectedBinding ?? binding) else { return }
        mutate(&progress)
        batchPolishProgressStorage = progress
    }

    private func stopOwnedBatchPolishRun(
        binding expectedBinding: NovelSessionBinding,
        reason: NovelRunInterruptionReason
    ) async {
        let runID = batchPolishOwnedRunID ?? (isBatchStartingRun ? activeRunID : nil)
        guard let runID,
              binding == expectedBinding,
              activeRunID == runID else { return }
        let durableRun = workspace.projectSnapshot?.activeRuns.first {
            $0.id == runID && $0.branchID == expectedBinding.branchID
        }
        if let durableRun, durableRun.status != .running {
            if transientTail?.runID == runID { clearTransientTail() }
            return
        }
        await stop(reason: reason)
    }

    private func reconcileBatchPolishResult(
        transactionID: NovelPendingOperationID,
        chapterID: NovelChapterID?
    ) {
        guard let chapterID,
              let transaction = workspace.projectSnapshot?.polishTransactions.first(where: {
                  $0.id == transactionID
              }) else { return }
        mutateBatchProgress { progress in
            guard let index = progress.results.firstIndex(where: { $0.chapterID == chapterID }) else {
                return
            }
            switch transaction.status {
            case .completed:
                progress.results[index].outcome = .adopted
                progress.results[index].message = nil
            case .incompatible:
                progress.results[index].outcome = .skippedDrift
                progress.results[index].message = "润色改动了剧情事实,已跳过本章。"
            case .abandoned:
                progress.results[index].outcome = .failed
                progress.results[index].message = "已放弃未完成的润色检查，可重新润色本章。"
            case .pending, .retryable, .blocked:
                progress.results[index].outcome = .failed
                progress.results[index].message = transaction.lastFailure?.message ?? "润色检查仍未完成。"
            }
        }
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
    /// Prefer the live run record; fall back to the bound transient run so status
    /// chrome stays correct through terminal presentation drain.
    var activeRunKind: NovelRunKind? {
        activeRun?.kind ?? transientRunRecord?.kind
    }
    var activeRunGranularity: NovelGenerationGranularity? {
        activeRun?.granularity ?? transientRunRecord?.granularity
    }
    /// True while the manuscript terminal is still chrome-visible.
    ///
    /// Covers both the drain/save lock (`terminalAwaitingRefresh`) and the quiet
    /// window after unlock: retire clears the flag first so composer unlocks, but
    /// the tail stays on `.terminalAwaitingRefresh` until quiet elapses. Without
    /// this, the generation strip collapses ~28pt before the tail retires.
    var isTerminalPresenting: Bool {
        if terminalAwaitingRefresh { return true }
        if case .terminalAwaitingRefresh = transientTail?.phase { return true }
        return false
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
        if run.kind == .prose || run.kind == .polish || run.kind == .regenerate {
            guard run.baseCheckpointID == branch.headCheckpointID,
                  run.baseHeadRevision == branch.headRevision else { return false }
        }
        if run.kind == .polish || run.kind == .regenerate {
            guard let sourceID = run.sourceChapterVersionID,
                  branch.workingChapterSelections.contains(where: { $0.versionID == sourceID }),
                  let sourceVersion = workspace.projectSnapshot?.chapterVersions.first(where: {
                      $0.id == sourceID
                  }),
                  workspace.projectSnapshot?.chapters.contains(where: {
                      $0.id == sourceVersion.chapterID && $0.discardedAt == nil
                  }) == true else {
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

    func canStart(
        kind: NovelRunKind,
        granularity: NovelGenerationGranularity? = nil
    ) -> Bool {
        startBlockerMessage(kind: kind, granularity: granularity) == nil
    }

    /// Human-readable gate reason when generation cannot start. `nil` means open.
    func startBlockerMessage(
        kind: NovelRunKind,
        granularity: NovelGenerationGranularity? = nil
    ) -> String? {
        guard snapshotMatchesBinding else {
            return "项目状态尚未对齐，请先点「重新载入」。"
        }
        guard access == .readWrite else {
            return "当前项目是只读状态，无法生成。"
        }
        if workspace.requiresReload {
            return "项目需要重新载入后才能继续生成。"
        }
        if let refreshErrorMessage {
            return refreshErrorMessage
        }
        if terminalAwaitingRefresh {
            return "上一轮结果还在保存，请稍候再试或点「重新载入」。"
        }
        if isBusy && !isBatchStartingRun {
            return "当前还有操作在进行，请稍候。"
        }
        if isRunning {
            return "已有生成在进行中。"
        }
        guard let branch = workspace.branchSnapshot?.branch else {
            return "分支尚未就绪，请重新载入项目。"
        }
        guard branch.lifecycle == .active else {
            return "当前分支不可用，无法生成。"
        }
        if branch.activeRunID != nil || activeRun != nil {
            return "分支上还有未结束的生成，请稍候或重新载入。"
        }
        switch kind {
        case .discussion:
            return nil
        case .characterProposal:
            guard branch.syncStatus == .synchronized,
                  branchPendingOperations.isEmpty,
                  unresolvedBranchPolishTransactions.isEmpty else {
                return "请先完成剧情同步或处理未完成的操作，再继续。"
            }
            return nil
        case .prose:
            guard branch.syncStatus == .synchronized,
                  !branchPendingOperations.contains(where: \.blocksProseGeneration) else {
                return "正文生成前需要先同步剧情状态。"
            }
            if isGhostwriting && !isGhostwriteStartingRun {
                return "代笔进行中，请先暂停或等当前章完成。"
            }
            let proseGranularity = granularity ?? self.granularity
            if workspace.projectSnapshot?.project.collaborationMode == .ghostwrite,
               proseGranularity == .wholeChapter,
               workspace.projectSnapshot?.confirmedChapterPlan(for: branch.id) == nil {
                return "代笔写整章需要先确认本章计划。"
            }
            return nil
        case .regenerate:
            guard branch.syncStatus == .synchronized,
                  branchPendingOperations.isEmpty,
                  unresolvedBranchPolishTransactions.isEmpty else {
                return "重新生成前需要先同步剧情并完成未落地操作。"
            }
            return nil
        case .polish:
            guard branch.syncStatus == .synchronized,
                  branchPendingOperations.isEmpty,
                  unresolvedBranchPolishTransactions.isEmpty else {
                return "润色前需要先同步剧情并完成未落地操作。"
            }
            return nil
        case .quickStart:
            return "开局建议请从项目入口发起，不能从会话直接重试。"
        }
    }

    @discardableResult
    func start(_ draft: NovelSessionRunDraft) async -> Bool {
        if draft.userText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            operationErrorMessage = "请求内容为空，请重新输入后再试。"
            return false
        }
        if draft.inputBudgetTokens <= 0 {
            operationErrorMessage = "模型输入预算无效，请检查项目模型设置后重试。"
            return false
        }
        if let blocker = startBlockerMessage(kind: draft.kind, granularity: draft.granularity) {
            operationErrorMessage = blocker
            return false
        }
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else {
            operationErrorMessage = "项目尚未就绪，请重新载入后再试。"
            return false
        }
        let previousTail = transientTail
        let previousRunRecord = transientRunRecord
        let previousTerminalAwaitingRefresh = terminalAwaitingRefresh
        let expectedBindingToken = bindingToken

        let candidateID: NovelCandidateID? = switch draft.kind {
        case .prose, .polish, .regenerate: NovelCandidateID()
        case .quickStart, .characterProposal, .discussion: nil
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
            contextualCharacterMention: draft.contextualCharacterMention,
            ghostwritePlanID: draft.ghostwritePlanID,
            injectionOverrides: draft.injectionOverrides,
            inputBudgetTokens: draft.inputBudgetTokens,
            suppressRecentSessionMessages: draft.suppressRecentSessionMessages,
            expectedProjectRevision: project.project.revision,
            expectedConfigRevision: project.project.configRevision,
            expectedBranchHeadRevision: branch.branch.headRevision
        )
        guard workspace.acquireSessionOperation(ownerID: request.id.rawValue) else {
            operationErrorMessage = "当前会话正忙，请稍候再试。"
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
            contextualCharacterMention: request.contextualCharacterMention,
            baseCheckpointID: branch.branch.headCheckpointID,
            baseHeadRevision: branch.branch.headRevision,
            status: .running,
            partialContent: "",
            receiptID: request.generationReceiptID,
            startedAt: Date(),
            terminalAt: nil,
            interruptionReason: nil,
            terminalFailure: nil,
            chapterPlanDigest: nil,
            ghostwritePlanID: draft.ghostwritePlanID
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
            if consumerAttachmentDesired,
               bindingToken == expectedBindingToken,
               binding?.projectID == request.projectID,
               binding?.branchID == request.branchID {
                consume(run, draft: draft, token: expectedBindingToken)
            }
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
        clearConsumer()
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
            if draft.ghostwritePlanID != nil {
                advanceGhostwriteBackgroundProgress(by: 1)
            }
            _ = await refreshDurable(binding: binding, token: token)
            adoptDurableRunRecord(runID: runID)
        case .reasoningDelta(let text):
            if draft.ghostwritePlanID != nil, !text.isEmpty {
                advanceGhostwriteBackgroundProgress(by: Int64(text.utf8.count))
            }
            applyReasoningPresentation(text, runID: runID, token: token)
        case .delta(let text):
            if draft.ghostwritePlanID != nil, !text.isEmpty {
                advanceGhostwriteBackgroundProgress(by: Int64(text.utf8.count))
            }
            markReasoningFinishedIfNeeded(runID: runID, token: token)
            if draft.kind == .quickStart {
                appendQuickStartStructuredDelta(text, runID: runID, token: token)
            } else if draft.kind == .characterProposal {
                appendCharacterProposalStructuredDelta(text, runID: runID, token: token)
            } else {
                enqueuePresentationDelta(text, runID: runID, token: token)
            }
        case .replaced(let text):
            if draft.ghostwritePlanID != nil, !text.isEmpty {
                advanceGhostwriteBackgroundProgress(by: Int64(text.utf8.count))
            }
            markReasoningFinishedIfNeeded(runID: runID, token: token)
            if draft.kind == .quickStart {
                replaceQuickStartStructuredContent(text, runID: runID, token: token)
            } else if draft.kind == .characterProposal {
                replaceCharacterProposalStructuredContent(text, runID: runID, token: token)
            } else {
                enqueuePresentationReplacement(text, runID: runID, token: token)
            }
        case .completed(let snapshot):
            markReasoningFinishedIfNeeded(runID: runID, token: token)
            guard await publishTerminalPresentation(
                runID: runID,
                token: token,
                authoritativeContent: snapshot.message.content,
                phase: .terminalAwaitingRefresh
            ) else { return }
            lastRetryDraft = nil
            lastRetryRunID = nil
            currentRunDraft = nil
            let refreshed = await refreshDurable(binding: binding, token: token)
            if refreshed { retireTerminalTransientTail(runID: runID, token: token) }
        case .interrupted(let snapshot):
            markReasoningFinishedIfNeeded(runID: runID, token: token)
            guard await publishTerminalPresentation(
                runID: runID,
                token: token,
                authoritativeContent: draft.kind == .quickStart || draft.kind == .characterProposal
                    ? ""
                    : snapshot?.message.content,
                phase: .interrupted
            ) else { return }
            lastRetryDraft = draft
            lastRetryRunID = runID
            currentRunDraft = nil
            let refreshed = await refreshDurable(binding: binding, token: token)
            if refreshed { retireTerminalTransientTail(runID: runID, token: token) }
        case .failed(let failure):
            markReasoningFinishedIfNeeded(runID: runID, token: token)
            guard await publishTerminalPresentation(
                runID: runID,
                token: token,
                authoritativeContent: draft.kind == .quickStart || draft.kind == .characterProposal
                    ? ""
                    : nil,
                phase: .failed(failure)
            ) else { return }
            lastFailure = failure
            operationErrorMessage = NovelPresentation.failureMessage(failure)
            lastRetryDraft = failure.isRetryable ? draft : nil
            lastRetryRunID = failure.isRetryable ? runID : nil
            currentRunDraft = nil
            let refreshed = await refreshDurable(binding: binding, token: token)
            if refreshed { retireTerminalTransientTail(runID: runID, token: token) }
        case .persistenceBlocked(let failure):
            markReasoningFinishedIfNeeded(runID: runID, token: token)
            guard await publishTerminalPresentation(
                runID: runID,
                token: token,
                authoritativeContent: draft.kind == .quickStart || draft.kind == .characterProposal
                    ? ""
                    : nil,
                phase: .persistenceBlocked(failure)
            ) else { return }
            terminalAwaitingRefresh = false
            lastFailure = failure
            operationErrorMessage = NovelPresentation.failureMessage(failure)
        }
    }

    func attach(to run: NovelActiveRunRecord) async {
        guard consumerAttachmentDesired else { return }
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
        let initialContent: String = switch run.kind {
        case .quickStart:
            NovelQuickStartStreamingPresentation.markdown(from: run.partialContent)
        case .characterProposal:
            NovelQuickStartStreamingPresentation.characterProposalMarkdown(
                from: run.partialContent
            )
        case .discussion, .prose, .polish, .regenerate:
            run.partialContent
        }
        installTail(
            run: run,
            content: initialContent,
            phase: run.partialContent.isEmpty ? .waitingForFirstToken : .streaming
        )
        do {
            let observed = try await workspace.startSessionRun(
                request,
                acquiresBackgroundLease: false
            )
            guard attachAttemptID == attemptID,
                  consumerAttachmentDesired,
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
        // Exact retry must not re-apply Ask User. The failed run already durably
        // wrote the answer interaction; replaying askUserResponse hits
        // "already been answered" and surfaces as a generic state mismatch.
        // Re-send the same user text as a normal follow-up turn instead.
        return NovelSessionRunDraft(
            kind: run.kind,
            mode: run.mode,
            granularity: run.granularity,
            userText: user.content,
            sourceChapterVersionID: run.sourceChapterVersionID,
            askUserResponse: nil,
            injectionOverrides: NovelInjectionOverrides(
                forceIncludeMaterialIDs: injection.forceIncludeMaterialIDs,
                forceExcludeMaterialIDs: injection.forceExcludeMaterialIDs
            ),
            inputBudgetTokens: injection.requestedInputBudgetTokens,
            contextualCharacterMention: run.contextualCharacterMention,
            ghostwritePlanID: run.ghostwritePlanID
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
            contextualCharacterMention: run.contextualCharacterMention,
            ghostwritePlanID: run.ghostwritePlanID,
            injectionOverrides: draft.injectionOverrides,
            inputBudgetTokens: draft.inputBudgetTokens,
            suppressRecentSessionMessages: draft.suppressRecentSessionMessages,
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

    /// Presentation-only thinking stream. Never touches manuscript presentation buffer.
    ///
    /// 48ms 拍节流（与正文 presentationFlush 同源）：chunk 先并入 pendingReasoningText
    /// （按既有前缀去重语义累积），每拍一次合并进 row.reasoningContent。终态/正文切换
    /// 前由 markReasoningFinishedIfNeeded 同步收口最后一段思考——presentation 延迟
    /// ≤48ms，lifecycle broadcast 语义与 storage 均不变。
    func applyReasoningPresentation(
        _ text: String,
        runID: NovelRunID,
        token: UUID
    ) {
        guard !text.isEmpty,
              bindingToken == token,
              let current = transientTail,
              current.runID == runID else { return }
        let effective = pendingReasoningText ?? current.reasoningContent
        let accumulated: String
        if text.hasPrefix(effective) || effective.isEmpty {
            accumulated = text
        } else if effective.hasPrefix(text) {
            return
        } else {
            accumulated = effective + text
        }
        guard accumulated != current.reasoningContent || !current.isReasoningLive else { return }
        pendingReasoningText = accumulated
        scheduleReasoningFlush(runID: runID, token: token)
    }

    func markReasoningFinishedIfNeeded(runID: NovelRunID, token: UUID) {
        guard bindingToken == token,
              let current = transientTail,
              current.runID == runID,
              current.isReasoningLive else { return }
        // 同步收口：尚未发布的思考尾巴与 isReasoningLive=false 在同一拍落地，
        // 卡片软收起与正文出现同拍（与逐 chunk 直上时代理等价，无额外延迟）。
        flushPendingReasoning(runID: runID, token: token, finishing: true)
    }

    private func scheduleReasoningFlush(runID: NovelRunID, token: UUID) {
        guard reasoningFlushTask == nil else { return }
        let flushToken = UUID()
        reasoningFlushToken = flushToken
        reasoningFlushTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: Self.presentationFlushDelayNanos)
            } catch {
                return
            }
            guard let self, !Task.isCancelled, self.reasoningFlushToken == flushToken else { return }
            self.reasoningFlushTask = nil
            self.flushPendingReasoning(runID: runID, token: token, finishing: false)
        }
    }

    /// 一拍合并：pendingReasoningText 与当前 tail 合并为一次可见发布。
    /// finishing=true 时（终态/正文切换）同步收口并在同一拍翻转 isReasoningLive=false。
    private func flushPendingReasoning(
        runID: NovelRunID,
        token: UUID,
        finishing: Bool
    ) {
        reasoningFlushTask?.cancel()
        reasoningFlushTask = nil
        guard bindingToken == token,
              let current = transientTail,
              current.runID == runID else { return }
        let pending = pendingReasoningText
        pendingReasoningText = nil
        if pending == nil, !finishing { return }
        let merged: String
        if let pending {
            if pending.hasPrefix(current.reasoningContent) || current.reasoningContent.isEmpty {
                merged = pending
            } else if current.reasoningContent.hasPrefix(pending) {
                if !finishing { return }
                merged = current.reasoningContent
            } else {
                merged = current.reasoningContent + pending
            }
        } else {
            merged = current.reasoningContent
        }
        let nextLive = finishing ? false : true
        guard merged != current.reasoningContent || current.isReasoningLive != nextLive else { return }
        // Promote waiting placeholder once thinking is visible.
        let nextPhase: NovelSessionTransientTailPhase =
            current.phase == .waitingForFirstToken ? .streaming : current.phase
        transientTail = current.updating(
            content: current.content,
            renderRevision: current.renderRevision &+ 1,
            phase: nextPhase,
            reasoningContent: merged,
            isReasoningLive: nextLive
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
        normalizePresentationBufferIfNeeded(runKind: transientRunRecord?.kind)
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
        normalizePresentationBufferIfNeeded(runKind: transientRunRecord?.kind)
        schedulePresentationFlush(
            runID: runID,
            messageID: current.messageID,
            token: token
        )
    }

    /// Keep buffer target in the same form the bubble displays for manuscript kinds.
    func normalizePresentationBufferIfNeeded(runKind: NovelRunKind?) {
        guard var buffer = presentationBuffer else { return }
        let normalized = NovelSessionPresentationPacer.presentationContent(
            buffer.targetContent,
            runKind: runKind
        )
        guard normalized != buffer.targetContent else { return }
        buffer.replace(with: normalized)
        presentationBuffer = buffer
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

    func appendQuickStartStructuredDelta(
        _ text: String,
        runID: NovelRunID,
        token: UUID
    ) {
        guard !text.isEmpty,
              bindingToken == token,
              transientTail?.runID == runID else { return }
        quickStartStructuredContent = (quickStartStructuredContent ?? "") + text
        publishQuickStartPresentation(runID: runID, token: token)
    }

    func replaceQuickStartStructuredContent(
        _ text: String,
        runID: NovelRunID,
        token: UUID
    ) {
        guard bindingToken == token,
              transientTail?.runID == runID else { return }
        quickStartStructuredContent = text
        publishQuickStartPresentation(runID: runID, token: token)
    }

    func appendCharacterProposalStructuredDelta(
        _ text: String,
        runID: NovelRunID,
        token: UUID
    ) {
        guard transientTail?.runID == runID, bindingToken == token else { return }
        characterProposalStructuredContent = (characterProposalStructuredContent ?? "") + text
        publishCharacterProposalStreamingPresentation(runID: runID, token: token)
    }

    func replaceCharacterProposalStructuredContent(
        _ text: String,
        runID: NovelRunID,
        token: UUID
    ) {
        guard transientTail?.runID == runID, bindingToken == token else { return }
        characterProposalStructuredContent = text
        publishCharacterProposalStreamingPresentation(runID: runID, token: token)
    }

    func publishCharacterProposalStreamingPresentation(runID: NovelRunID, token: UUID) {
        let presentation = NovelQuickStartStreamingPresentation.characterProposalMarkdown(
            from: characterProposalStructuredContent ?? ""
        )
        enqueuePresentationReplacement(presentation, runID: runID, token: token)
    }

    func publishQuickStartPresentation(runID: NovelRunID, token: UUID) {
        let markdown = NovelQuickStartStreamingPresentation.markdown(
            from: quickStartStructuredContent ?? ""
        )
        if markdown.isEmpty {
            publishQuickStartStreamingPhaseIfNeeded(runID: runID, token: token)
        } else {
            enqueuePresentationReplacement(markdown, runID: runID, token: token)
        }
    }

    func publishTerminalPresentation(
        runID: NovelRunID,
        token: UUID,
        authoritativeContent: String?,
        phase: NovelSessionTransientTailPhase
    ) async -> Bool {
        presentationFlushTask?.cancel()
        presentationFlushTask = nil
        guard bindingToken == token,
              let current = transientTail,
              current.runID == runID else {
            presentationBuffer = nil
            return false
        }
        let bufferedContent: String?
        if let buffer = presentationBuffer,
           buffer.matches(
               runID: runID,
               messageID: current.messageID,
               bindingToken: token
        ) {
            bufferedContent = buffer.targetContent
        } else {
            bufferedContent = nil
        }
        presentationBuffer = nil
        let rawTarget = authoritativeContent ?? bufferedContent ?? current.content
        let targetContent = NovelSessionPresentationPacer.presentationContent(
            rawTarget,
            runKind: transientRunRecord?.kind
        )
        terminalAwaitingRefresh = true
        var didPublishTerminal = false
        defer {
            if !didPublishTerminal,
               bindingToken == token,
               transientTail?.runID == runID {
                terminalAwaitingRefresh = false
            }
        }

        // 与标准 Chat 的终态语义一致：complete/error/cancel 到达时，模型全文可能
        // 已领先可见文本很多拍。不能绕过 pacer 一次发布全部积压，否则正文高度会在
        // 单帧暴涨，滚动驱动只能随后追赶。先按 Chat 同源终态连续曲线逐拍追平，
        // 再切终态和刷新 durable（完成时积压一次定锚，不逐拍衰减回慢节拍）。
        // 每拍随剩余积压透出 lagAllowance（1→0 连续衰减）：driver 据此收紧跟随
        // 时间常数（τ_eff = τ × allowance），最后一拍前视口已贴回底部——完成瞬间
        // 的钉底不再需要一次性清掉跟随滞后（与 Chat 的 ChatMessageUpdateSignal.
        // lagAllowance 同源，复用 StreamPresentationPacingPolicy.lagAllowance）。
        let initialBase = NovelSessionPresentationPacer.terminalPacingBase(
            displayedContent: current.content,
            targetContent: targetContent,
            runKind: transientRunRecord?.kind
        )
        let initialBacklog: Int
        if targetContent.hasPrefix(initialBase) {
            initialBacklog = targetContent.count - initialBase.count
        } else {
            initialBacklog = targetContent.count
        }
        let drainAdvance = NovelSessionPresentationPacer.terminalDrainAdvance(
            backlogCount: initialBacklog
        )
        while !Task.isCancelled,
              bindingToken == token,
              let visibleTail = transientTail,
              visibleTail.runID == runID {
            let step = NovelSessionPresentationPacer.terminalStep(
                displayedContent: visibleTail.content,
                targetContent: targetContent,
                runKind: transientRunRecord?.kind,
                fixedTerminalAdvance: drainAdvance
            )
            if step.isCaughtUp {
                if step.content != visibleTail.content || visibleTail.phase != phase {
                    updateTail(content: step.content, phase: phase)
                }
                didPublishTerminal = true
                return true
            }
            let remainingBacklog = max(0, targetContent.count - step.content.count)
            let allowance = StreamPresentationPacingPolicy.lagAllowance(
                remainingBacklog: remainingBacklog,
                drainStartBacklog: initialBacklog
            )
            updateTail(content: step.content, phase: .streaming, lagAllowance: allowance)
            do {
                // 拍间隔跟随优雅尾的实际拍速逐拍重算（8ms 连续放宽到 48ms），
                // 不能用完成时的锚速定值——否则末段减速拍被 8ms 压缩、尾拍仍砸。
                let beatAdvance = StreamPresentationPacingPolicy.terminalTextAdvance(
                    backlogCount: remainingBacklog,
                    fixedAdvance: drainAdvance
                )
                try await Task.sleep(
                    nanoseconds: NovelSessionPresentationPacer.terminalDrainDelayNanos(
                        advance: beatAdvance
                    )
                )
            } catch {
                return false
            }
        }
        return false
    }

    /// 清空全部「尚未发布的呈现工作」：正文 presentation buffer 与思考流 pending。
    /// 思考流的 finish 收口走 markReasoningFinishedIfNeeded（同一拍合并+翻转 live），
    /// 这里只处理 run 边界（install/terminal/clear）与 buffer 失效路径。
    func cancelPendingPresentation() {
        presentationFlushTask?.cancel()
        presentationFlushTask = nil
        presentationBuffer = nil
        reasoningFlushTask?.cancel()
        reasoningFlushTask = nil
        reasoningFlushToken = UUID()
        pendingReasoningText = nil
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
        quickStartStructuredContent = run.kind == .quickStart ? run.partialContent : nil
        characterProposalStructuredContent = run.kind == .characterProposal
            ? run.partialContent
            : nil
        transientRunRecord = run
        transientTail = NovelSessionTransientTail(
            run: run,
            content: NovelSessionPresentationPacer.presentationContent(content, runKind: run.kind),
            renderRevision: renderRevision,
            startingUserContent: startingUserContent,
            phase: phase
        )
        terminalAwaitingRefresh = false
    }

    func updateTail(
        content: String? = nil,
        phase: NovelSessionTransientTailPhase? = nil,
        lagAllowance: Double? = nil
    ) {
        guard let current = transientTail else { return }
        transientTail = current.updating(
            content: content ?? current.content,
            phase: phase ?? current.phase,
            lagAllowance: lagAllowance
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
            phase: current.phase,
            reasoningContent: current.reasoningContent,
            isReasoningLive: current.isReasoningLive
        )
    }

    func clearTransientTail() {
        terminalTailRetirementTask?.cancel()
        terminalTailRetirementTask = nil
        cancelPendingPresentation()
        quickStartStructuredContent = nil
        characterProposalStructuredContent = nil
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

    func interruptBoundRun(
        reason: NovelRunInterruptionReason,
        runID explicitRunID: NovelRunID? = nil
    ) async -> Bool {
        guard let bound = binding,
              let runID = explicitRunID ?? activeRunID else { return true }
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

    func resetBinding() async {
        await cancelPolishRetryForBindingChange(from: binding)
        await cancelBatchPolishForBindingChange(from: binding)
        await cancelGhostwriteForBindingChange(from: binding)
        detachConsumer()
        bindingToken = UUID()
        binding = nil
        clearTransientTail()
        currentRunDraft = nil
        lastRetryDraft = nil
        lastRetryRunID = nil
        advanceLoadStage(to: .idle)
        projectionCache = nil
        locallyResolvedAskUser = [:]
    }

    func cancelPolishRetryForBindingChange(
        from expectedBinding: NovelSessionBinding?
    ) async {
        guard let expectedBinding,
              polishRetryTaskBinding == expectedBinding,
              let task = polishRetryTask else { return }
        task.cancel()
        await task.value
    }

    func describe(_ error: Error) -> String {
        NovelPresentation.operationErrorMessage(error)
    }
}

// MARK: - 代笔有界多章 pipeline（最多 10 章）

extension NovelSessionViewModel {
    var ghostwriteProgress: NovelGhostwriteProgress? {
        guard let progress = ghostwriteProgressStorage,
              progress.binding == binding,
              snapshotMatchesBinding else { return nil }
        return progress
    }

    var isGhostwriting: Bool {
        switch ghostwriteProgress?.phase {
        case .writing, .accepting, .collecting, .syncing, .planning, .revising: true
        case .paused, .waitingUser, .failed, nil: false
        }
    }

    /// 批中合同已消费、同步失败或拟计划失败时，允许无确认合同续跑。
    private var canResumeGhostwriteWithoutPlan: Bool {
        guard let progress = ghostwriteProgressStorage,
              progress.binding == binding,
              progress.canResumeWithoutConfirmedPlan else { return false }
        return true
    }

    var ghostwriteReadinessIssue: NovelGhostwriteReadinessIssue? {
        guard let project = workspace.projectSnapshot,
              let branchID = binding?.branchID else { return .branchNeedsSync }
        var issues = NovelGhostwriteReadiness.issues(
            materials: project.materials,
            materialRevisions: project.materialRevisions,
            branches: project.branches,
            pendingOperations: project.pendingOperations,
            polishTransactions: project.polishTransactions,
            activeRuns: project.activeRuns,
            chapterPlans: project.chapterPlans,
            mainBranchID: project.project.mainBranchID,
            branchID: branchID,
            requireChapterPlan: !canResumeGhostwriteWithoutPlan
        )
        // 批中续跑「同步失败」：由 pipeline 内 await sync，不在入口硬挡。
        if canResumeGhostwriteWithoutPlan {
            issues.removeAll { $0 == .branchNeedsSync || $0 == .missingChapterPlan }
        }
        return issues.first
    }

    var canStartGhostwriteChapter: Bool {
        guard ghostwriteBlocker == nil,
              let branchID = binding?.branchID,
              workspace.projectSnapshot?.project.collaborationMode == .ghostwrite
        else { return false }
        if canResumeGhostwriteWithoutPlan { return true }
        // 完批后开新批：允许没有确认计划（pipeline 自动拟定），与批内第 2～N 章同路径。
        if ghostwriteProgressStorage?.pauseReason == .batchCompleted
            || ghostwriteProgressStorage?.pauseReason == .chapterCompleted {
            return true
        }
        return workspace.projectSnapshot?.confirmedChapterPlan(for: branchID) != nil
    }

    var ghostwriteBlocker: NovelSessionActionBlocker? {
        guard access == .readWrite else { return .projectReadOnly }
        if workspace.requiresReload || hasRefreshError { return .reloadRequired }
        if isGhostwriting { return .transactionInProgress }
        if isBatchPolishing { return .transactionInProgress }
        if isRunning { return .generationRunning }
        // 续跑「同步失败」时允许在 needsSync 下点继续，pipeline 内再等同步。
        if needsSync, !canResumeGhostwriteWithoutPlan { return .branchNeedsSync }
        if !branchPendingOperations.isEmpty || !unresolvedBranchPolishTransactions.isEmpty {
            return .pendingOperation
        }
        if isBusy { return .transactionInProgress }
        if workspace.projectSnapshot?.project.collaborationMode != .ghostwrite {
            return .chapterPlanRequired
        }
        if ghostwriteReadinessIssue != nil { return .ghostwriteRequirementsMissing }
        return nil
    }

    @discardableResult
    func startGhostwriteChapter(targetChapterCount: Int? = nil) -> Bool {
        guard ghostwriteTask == nil,
              let binding,
              canStartGhostwriteChapter
        else {
            let blocker = ghostwriteBlocker
            if blocker == .ghostwriteRequirementsMissing,
               let readinessIssue = ghostwriteReadinessIssue {
                operationErrorMessage = readinessIssue.displayName
            } else {
                operationErrorMessage = blocker?.displayName ?? "代笔暂时不能开始。"
            }
            return false
        }
        let previous = ghostwriteProgressStorage
        let sameBinding = previous?.binding == binding
        // 与面板「继续代笔」同义，避免 cancelled 显示「开始」却仍续旧批。
        let resumingBatch = sameBinding && (previous?.shouldContinueSameBatch == true)
        let target = NovelGhostwriteBatch.clamp(
            resumingBatch
                ? (previous?.targetChapterCount ?? ghostwriteTargetChapterCount)
                : (targetChapterCount ?? ghostwriteTargetChapterCount)
        )
        let confirmedPlan = workspace.projectSnapshot?.confirmedChapterPlan(for: binding.branchID)
        // 完批后开新批：允许没有确认计划直接进入 pipeline 的 planning 阶段自动拟定
        //（与批内第 2～N 章同路径，proposeAndConfirmNextChapterPlan）。
        // 首次启动（无前批）仍需用户手动确认计划。
        let isStartingNewBatchAfterCompletion = !resumingBatch
            && (previous?.pauseReason == .batchCompleted || previous?.pauseReason == .chapterCompleted)
        if confirmedPlan == nil,
           !canResumeGhostwriteWithoutPlan,
           !isStartingNewBatchAfterCompletion {
            operationErrorMessage = "代笔需要已确认的本章计划。"
            return false
        }
        operationErrorMessage = nil
        // 续跑保留本批幂等集合；新批清空，避免「进度 0/N」却显示「本批已收录 5 章」。
        let retainedCollected = resumingBatch
            ? (previous?.autoCollectedCandidateIDs ?? [])
            : []
        // 质量失败：继续必须重写，禁止同稿再验（验收不过 / 复读 / 连续性 / 不完整等）。
        let retainedCandidate: NovelCandidateID? = {
            guard resumingBatch else { return nil }
            if previous?.mustRewriteCandidateOnResume == true { return nil }
            return previous?.candidateID
        }()
        let retainedSuperseded: Set<NovelCandidateID> = {
            guard resumingBatch else { return [] }
            var set = previous?.supersededCandidateIDs ?? []
            if previous?.mustRewriteCandidateOnResume == true,
               let old = previous?.candidateID {
                set.insert(old)
            }
            return set
        }()
        let completed = resumingBatch ? (previous?.completedChapterCount ?? 0) : 0
        let currentIndex = resumingBatch
            ? max(previous?.currentChapterIndex ?? 1, completed + 1)
            : 1
        if resumingBatch, let previousTarget = previous?.targetChapterCount {
            // 本批 N 在开跑时固定；面板 Stepper 不得在续跑时改写目标。
            ghostwriteTargetChapterCount = previousTarget
        }
        let openingPhase: NovelGhostwritePhase = {
            if resumingBatch, previous?.pendingSyncChapterCredit == true { return .syncing }
            if confirmedPlan == nil { return .planning }
            if resumingBatch, previous?.revisionBriefOverride != nil { return .revising }
            return .writing
        }()
        ghostwriteProgressStorage = NovelGhostwriteProgress(
            binding: binding,
            phase: openingPhase,
            pauseReason: resumingBatch ? previous?.pauseReason : nil,
            detailMessage: resumingBatch ? previous?.detailMessage : nil,
            candidateID: retainedCandidate,
            chapterPlanDigest: confirmedPlan?.contentDigest,
            autoCollectedCandidateIDs: retainedCollected,
            startedAt: resumingBatch ? (previous?.startedAt ?? Date()) : Date(),
            targetChapterCount: target,
            completedChapterCount: completed,
            currentChapterIndex: currentIndex,
            lastCompletedPlanSummary: resumingBatch ? previous?.lastCompletedPlanSummary : nil,
            pendingSyncChapterCredit: resumingBatch
                ? (previous?.pendingSyncChapterCredit ?? false)
                : false,
            // 人手点继续/润修后给新的自动改写预算，并清空指纹环（避免立刻熔断）。
            qualityAttemptIndex: {
                guard resumingBatch else { return 0 }
                if previous?.mustRewriteCandidateOnResume == true { return 0 }
                return previous?.qualityAttemptIndex ?? 0
            }(),
            maxQualityAttempts: resumingBatch
                ? (previous?.maxQualityAttempts ?? NovelGhostwriteHeal.defaultMaxQualityAttempts)
                : NovelGhostwriteHeal.defaultMaxQualityAttempts,
            lastFailureReceipt: resumingBatch ? previous?.lastFailureReceipt : nil,
            supersededCandidateIDs: retainedSuperseded,
            recentFailureFingerprints: {
                guard resumingBatch else { return [] }
                // 人手续跑：清 fuse，避免「两次同指纹后继续」第一次就熔断。
                if previous?.mustRewriteCandidateOnResume == true
                    || previous?.revisionBriefOverride != nil {
                    return []
                }
                return previous?.recentFailureFingerprints ?? []
            }(),
            revisionBriefOverride: resumingBatch ? previous?.revisionBriefOverride : nil,
            didThinContractAmendThisChapter: resumingBatch
                ? (previous?.didThinContractAmendThisChapter ?? false)
                : false,
            contractAmendments: resumingBatch ? (previous?.contractAmendments ?? []) : [],
            infraRetryCount: 0
        )
        ghostwriteTaskBinding = binding
        let backgroundLeaseOwnerID = UUID()
        ghostwriteBackgroundLeaseOwnerID = backgroundLeaseOwnerID
        beginGhostwriteBackgroundLease(
            binding: binding,
            ownerID: backgroundLeaseOwnerID
        )
        ghostwriteTask = Task { @MainActor [weak self] in
            await self?.runGhostwriteBatch(binding: binding)
            if self == nil || self?.ghostwriteBackgroundLeaseOwnerID == backgroundLeaseOwnerID {
                BackgroundGenerationKeepAlive.shared.end(
                    novelGhostwriteBackgroundLeaseID(
                        projectID: binding.projectID,
                        branchID: binding.branchID
                    )
                )
            }
            guard let self,
                  self.ghostwriteBackgroundLeaseOwnerID == backgroundLeaseOwnerID,
                  self.ghostwriteTaskBinding == binding else { return }
            self.ghostwriteBackgroundLeaseOwnerID = nil
            self.ghostwriteTask = nil
            self.ghostwriteTaskBinding = nil
            self.ghostwriteOwnedRunID = nil
            self.isGhostwriteStartingRun = false
        }
        if let progress = ghostwriteProgressStorage {
            persistGhostwriteProgress(progress)
        }
        return true
    }

    func pauseGhostwrite() {
        guard ghostwriteTask != nil else { return }
        ghostwriteCancelAsUserPause = true
        mutateGhostwriteProgress {
            $0.pauseReason = .userPaused
            if $0.phase == .writing || $0.phase == .accepting || $0.phase == .collecting
                || $0.phase == .planning || $0.phase == .revising {
                $0.phase = .paused
            }
        }
        ghostwriteTask?.cancel()
    }

    @discardableResult
    func continueGhostwriteChapter() -> Bool {
        guard ghostwriteTask == nil else { return false }
        return startGhostwriteChapter()
    }

    /// 人工润修：写入可编辑 brief 后按自愈写路径重开本章（隔离会话历史）。
    @discardableResult
    func startGhostwriteRevision(brief: String) -> Bool {
        guard ghostwriteTask == nil, binding != nil else {
            operationErrorMessage = "代笔暂时不能开始。"
            return false
        }
        let trimmed = brief.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            operationErrorMessage = "请先填写润修要求。"
            return false
        }
        guard var progress = ghostwriteProgressStorage,
              progress.binding == binding,
              progress.shouldOfferRevisionSheet
        else {
            operationErrorMessage = "当前没有可润修的代笔中断状态。"
            return false
        }
        // 保留 pauseReason 使 shouldContinueSameBatch 仍为 true；brief 在 start 重建 progress 时带上。
        // receipt.sourceCandidateID 供 obtain 取上一稿正文；candidateID 仍作废以免同稿再验。
        if let old = progress.candidateID {
            if progress.lastFailureReceipt?.sourceCandidateID == nil {
                if let receipt = progress.lastFailureReceipt {
                    progress.lastFailureReceipt = NovelGhostwriteFailureReceipt.make(
                        reason: receipt.reason,
                        summary: receipt.summary,
                        missingMustHappen: receipt.missingMustHappen,
                        repetitionBeats: receipt.repetitionBeats,
                        continuityNotes: receipt.continuityNotes,
                        attemptIndex: receipt.attemptIndex,
                        sourceCandidateID: old,
                        planDigest: receipt.planDigest
                    )
                } else {
                    progress.lastFailureReceipt = NovelGhostwriteFailureReceipt.make(
                        reason: progress.pauseReason ?? .healBudgetExhausted,
                        summary: progress.detailMessage ?? "",
                        attemptIndex: progress.qualityAttemptIndex,
                        sourceCandidateID: old,
                        planDigest: progress.chapterPlanDigest
                    )
                }
            }
            progress.supersededCandidateIDs.insert(old)
        }
        progress.candidateID = nil
        progress.revisionBriefOverride = trimmed
        progress.qualityAttemptIndex = 0
        progress.detailMessage = "按审稿意见润修中…"
        ghostwriteProgressStorage = progress
        return startGhostwriteChapter()
    }

    func cancelGhostwriteForBindingChange(
        from expectedBinding: NovelSessionBinding?
    ) async {
        guard let expectedBinding else {
            ghostwriteProgressStorage = nil
            return
        }
        if ghostwriteTaskBinding == expectedBinding, let task = ghostwriteTask {
            // 先落盘暂停态再取消：切分支/退页后仍可从 sidecar 续本批。
            ghostwriteCancelAsUserPause = true
            mutateGhostwriteProgress {
                if $0.pendingSyncChapterCredit {
                    $0.phase = .failed
                    $0.pauseReason = .syncFailed
                } else {
                    $0.phase = .paused
                    $0.pauseReason = .userPaused
                }
                $0.detailMessage = Self.mergeGhostwriteDetail(
                    $0.detailMessage,
                    "离开页面时已保存本批进度，回来可继续。"
                )
            }
            task.cancel()
            await task.value
        }
        // 只清内存；sidecar 按 project+branch 保留，bind 时 restore。
        if ghostwriteProgressStorage?.binding == expectedBinding {
            ghostwriteProgressStorage = nil
        }
    }

    private static func mergeGhostwriteDetail(_ existing: String?, _ note: String) -> String {
        let base = existing?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if base.isEmpty { return note }
        if base.contains(note) { return base }
        return base + "\n" + note
    }

    private func runGhostwriteBatch(binding expectedBinding: NovelSessionBinding) async {
        do {
            while true {
                try Task.checkCancellation()
                _ = await refreshDurable(binding: binding, token: bindingToken)
                guard ghostwriteProgressStorage?.binding == expectedBinding else { return }

                // 已收录待同步：先完成记账，禁止少计章后再开写下一章。
                if ghostwriteProgressStorage?.pendingSyncChapterCredit == true {
                    let credited = try await settlePendingGhostwriteSyncCredit(
                        expectedBinding: expectedBinding
                    )
                    if !credited { return }
                    if ghostwriteProgressStorage?.isBatchComplete == true {
                        finishGhostwriteBatch(
                            binding: expectedBinding,
                            candidateID: ghostwriteProgressStorage?.candidateID
                        )
                        return
                    }
                    continue
                }

                let target = ghostwriteProgressStorage?.targetChapterCount
                    ?? NovelGhostwriteBatch.minChapterCount
                let completed = ghostwriteProgressStorage?.completedChapterCount ?? 0
                if completed >= target {
                    finishGhostwriteBatch(
                        binding: expectedBinding,
                        candidateID: ghostwriteProgressStorage?.candidateID
                    )
                    return
                }

                mutateGhostwriteProgress(binding: expectedBinding) {
                    $0.currentChapterIndex = completed + 1
                }

                // 每章入口取最新确认合同；章内 Tier2 改合同后也会在 loop 内刷新。
                let plan: NovelChapterPlanRecord
                if let existing = workspace.projectSnapshot?.confirmedChapterPlan(
                    for: expectedBinding.branchID
                ) {
                    plan = existing
                } else {
                    guard let proposed = try await proposeNextGhostwritePlan(
                        expectedBinding: expectedBinding
                    ) else { return }
                    plan = proposed
                }

                let chapterOK = try await runOneGhostwriteChapter(
                    initialPlan: plan,
                    expectedBinding: expectedBinding
                )
                if !chapterOK { return }

                // runOne 在 collect+clear 后已标 pending；成功同步后在此记账。
                let candidateID = ghostwriteProgressStorage?.candidateID
                let planSummary = workspace.projectSnapshot?
                    .confirmedChapterPlan(for: expectedBinding.branchID)?
                    .ghostwriteBatchSummary()
                    ?? ghostwriteProgressStorage?.lastCompletedPlanSummary
                mutateGhostwriteProgress(binding: expectedBinding) {
                    if let planSummary {
                        $0.lastCompletedPlanSummary = planSummary
                    }
                    _ = $0.applyPendingSyncChapterCredit()
                }

                if (ghostwriteProgressStorage?.completedChapterCount ?? 0) >= target {
                    finishGhostwriteBatch(
                        binding: expectedBinding,
                        candidateID: candidateID
                    )
                    return
                }
                // 下一章：循环顶部会自动拟合同。
            }
        } catch is CancellationError {
            await stopOwnedGhostwriteRun(
                binding: expectedBinding,
                reason: ghostwriteRunInterruptionReason()
            )
            let reason = ghostwriteCancellationPauseReason()
            pauseGhostwritePipeline(
                binding: expectedBinding,
                reason: reason,
                detail: nil,
                candidateID: ghostwriteProgressStorage?.candidateID
            )
            ghostwriteCancelAsUserPause = false
        } catch let failure as NovelStructuredModelExecutionFailure where failure.failure.code == "cancelled" {
            await stopOwnedGhostwriteRun(
                binding: expectedBinding,
                reason: ghostwriteRunInterruptionReason()
            )
            let reason = ghostwriteCancellationPauseReason()
            pauseGhostwritePipeline(
                binding: expectedBinding,
                reason: reason,
                detail: nil,
                candidateID: ghostwriteProgressStorage?.candidateID
            )
            ghostwriteCancelAsUserPause = false
        } catch {
            ghostwriteCancelAsUserPause = false
            pauseGhostwritePipeline(
                binding: expectedBinding,
                reason: .failedReason(from: error),
                detail: describe(error),
                candidateID: ghostwriteProgressStorage?.candidateID
            )
        }
    }

    /// 协作取消：默认视为用户暂停（可续跑），绝不因 heal 清 pauseReason 而落到 `.cancelled`。
    private func ghostwriteCancellationPauseReason() -> NovelGhostwritePauseReason {
        if ghostwriteProgressStorage?.pendingSyncChapterCredit == true {
            return .syncFailed
        }
        if ghostwriteCancelAsUserPause {
            return .userPaused
        }
        switch ghostwriteProgressStorage?.pauseReason {
        case .userPaused, .syncFailed, .infrastructureFailed:
            return ghostwriteProgressStorage?.pauseReason ?? .userPaused
        case .healBudgetExhausted, .acceptanceFailed, .obviousRepetition,
             .blockingContinuity, .continuityAuditIncomplete:
            // 质量停机中途再被取消：保留可续跑的质量终态语义。
            return ghostwriteProgressStorage?.pauseReason ?? .userPaused
        default:
            // 批内协作取消一律可续，不把本批作废。
            return .userPaused
        }
    }

    private func ghostwriteRunInterruptionReason() -> NovelRunInterruptionReason {
        ghostwriteProgressStorage?.pauseReason == .infrastructureFailed
            ? .expiration
            : .user
    }

    /// 单章闭环：写→验→（可选）连续性→收录→清合同→同步。
    /// 验收/复读失败时在预算内自动改写；用尽或严重连续性则暂停。成功 true；已暂停 false。
    /// Tier2 改合同后会刷新 live plan，避免 digest 与 collect 错配。
    private func runOneGhostwriteChapter(
        initialPlan: NovelChapterPlanRecord,
        expectedBinding: NovelSessionBinding
    ) async throws -> Bool {
        var plan = initialPlan
        // 合同对应候选已进正史：不得再写一章。常见于收录后清合同失败 / 取消。
        if let alreadyCollected = candidates(kind: .prose).first(where: {
            NovelGhostwriteCandidateOwnership.belongs($0, to: plan) && $0.status == .collected
        }) {
            mutateGhostwriteProgress(binding: expectedBinding) {
                $0.autoCollectedCandidateIDs.insert(alreadyCollected.id)
                $0.candidateID = alreadyCollected.id
            }
            if workspace.projectSnapshot?.chapterPlan(for: expectedBinding.branchID) != nil {
                let planCleared = await workspace.clearChapterPlan(
                    branchID: expectedBinding.branchID
                )
                if !planCleared {
                    pauseGhostwritePipeline(
                        binding: expectedBinding,
                        reason: .collectFailed,
                        detail: "本章已收录，但未能清除计划。请手动清除后再继续。",
                        candidateID: alreadyCollected.id
                    )
                    return false
                }
            }
            mutateGhostwriteProgress(binding: expectedBinding) {
                $0.chapterPlanDigest = nil
                $0.phase = .syncing
                $0.pendingSyncChapterCredit = true
                $0.lastCompletedPlanSummary = plan.ghostwriteBatchSummary()
            }
            let synced = await awaitGhostwriteStateSync(expectedBinding: expectedBinding)
            try Task.checkCancellation()
            if !synced {
                pauseGhostwritePipeline(
                    binding: expectedBinding,
                    reason: .syncFailed,
                    detail: "本章已收录；同步完成后可继续本批下一章。",
                    candidateID: alreadyCollected.id
                )
                return false
            }
            return true
        }

        while true {
            try Task.checkCancellation()
            // Tier2 改合同后 digest 会变：每轮写前刷新 live 确认合同，避免 accept/collect 错配。
            if let live = workspace.projectSnapshot?
                .confirmedChapterPlan(for: expectedBinding.branchID) {
                plan = live
            } else {
                pauseGhostwritePipeline(
                    binding: expectedBinding,
                    reason: .planMismatch,
                    detail: "本章计划已不存在或未确认，无法继续代笔。",
                    candidateID: ghostwriteProgressStorage?.candidateID
                )
                return false
            }
            let candidateID = try await obtainGhostwriteCandidate(
                plan: plan,
                expectedBinding: expectedBinding
            )
            try Task.checkCancellation()

            mutateGhostwriteProgress(binding: expectedBinding) {
                $0.phase = .accepting
                $0.candidateID = candidateID
                $0.chapterPlanDigest = plan.contentDigest
            }
            let acceptance = try await withGhostwriteInfraRetry(
                binding: expectedBinding,
                stage: "验收"
            ) {
                try await workspace.acceptChapterPlan(
                    projectID: expectedBinding.projectID,
                    branchID: expectedBinding.branchID,
                    candidateID: candidateID
                )
            }
            try Task.checkCancellation()
            if !acceptance.accepted {
                let detail = acceptance.summary.isEmpty
                    ? acceptance.missingMustHappen.joined(separator: "；")
                    : acceptance.summary
                let healed = await registerGhostwriteQualityFailure(
                    binding: expectedBinding,
                    reason: .acceptanceFailed,
                    detail: detail,
                    missingMustHappen: acceptance.missingMustHappen,
                    forbiddenViolations: acceptance.forbiddenViolations,
                    repetitionBeats: acceptance.obviousRepetition,
                    continuityNotes: [],
                    candidateID: candidateID,
                    planDigest: plan.contentDigest
                )
                if healed { continue }
                return false
            }
            if !acceptance.obviousRepetition.isEmpty {
                let detail = acceptance.obviousRepetition.joined(separator: "；")
                let healed = await registerGhostwriteQualityFailure(
                    binding: expectedBinding,
                    reason: .obviousRepetition,
                    detail: detail.isEmpty
                        ? NovelGhostwritePauseReason.obviousRepetition.displayMessage
                        : detail,
                    missingMustHappen: [],
                    forbiddenViolations: [],
                    repetitionBeats: acceptance.obviousRepetition,
                    continuityNotes: [],
                    candidateID: candidateID,
                    planDigest: plan.contentDigest
                )
                if healed { continue }
                return false
            }

            let pauseOnBlockingContinuity = workspace.projectSnapshot?.project
                .pauseGhostwriteOnBlockingContinuity ?? true
            if pauseOnBlockingContinuity {
                var continuityReport = try await withGhostwriteInfraRetry(
                    binding: expectedBinding,
                    stage: "连续性检查"
                ) {
                    try await workspace.auditContinuityIncludingCandidate(
                        projectID: expectedBinding.projectID,
                        branchID: expectedBinding.branchID,
                        candidateID: candidateID,
                        maxPriorManuscriptChapters:
                            NovelGhostwriteContinuityGate.nearScopePriorChapterCount
                    )
                }
                // 规则恢复：incomplete 再静默整次近距扫描 1 次，仍失败才停人。
                // 不烧质量预算；blocking 干净报告不进此循环。
                var silentRerun = 0
                while NovelGhostwriteContinuityGate.shouldSilentRerunIncomplete(
                    failedChunkCount: continuityReport.failedChunkCount,
                    alreadyReran: silentRerun
                ) {
                    try Task.checkCancellation()
                    silentRerun += 1
                    mutateGhostwriteProgress(binding: expectedBinding) {
                        $0.detailMessage = "连续性检查未扫稳，正在再检…"
                    }
                    continuityReport = try await withGhostwriteInfraRetry(
                        binding: expectedBinding,
                        stage: "连续性检查"
                    ) {
                        try await workspace.auditContinuityIncludingCandidate(
                            projectID: expectedBinding.projectID,
                            branchID: expectedBinding.branchID,
                            candidateID: candidateID,
                            maxPriorManuscriptChapters:
                                NovelGhostwriteContinuityGate.nearScopePriorChapterCount
                        )
                    }
                }
                try Task.checkCancellation()
                if let reason = NovelGhostwriteContinuityGate.pauseReason(for: continuityReport),
                   let detail = NovelGhostwriteContinuityGate.pauseDetail(for: continuityReport) {
                    let receipt = NovelGhostwriteFailureReceipt.make(
                        reason: reason,
                        summary: detail,
                        missingMustHappen: [],
                        repetitionBeats: [],
                        continuityNotes: NovelGhostwriteContinuityGate
                            .blockingIssueSummaries(in: continuityReport),
                        attemptIndex: reason == .blockingContinuity
                            ? (ghostwriteProgressStorage?.qualityAttemptIndex ?? 0) + 1
                            : (ghostwriteProgressStorage?.qualityAttemptIndex ?? 0),
                        sourceCandidateID: candidateID,
                        planDigest: plan.contentDigest
                    )
                    if reason == .blockingContinuity {
                        // 严重连续性：质量停机，记尝试并作废候选，等人润修/处理。
                        mutateGhostwriteProgress(binding: expectedBinding) {
                            $0.qualityAttemptIndex += 1
                            $0.lastFailureReceipt = receipt
                            $0.supersededCandidateIDs.insert(candidateID)
                        }
                    } else {
                        // 审计未完整：不是质量判定。留回执供界面呈现，
                        // 但不消耗改写预算、不作废候选——继续时复验同一已验收稿。
                        mutateGhostwriteProgress(binding: expectedBinding) {
                            $0.lastFailureReceipt = receipt
                        }
                    }
                    pauseGhostwritePipeline(
                        binding: expectedBinding,
                        reason: reason,
                        detail: detail,
                        candidateID: candidateID
                    )
                    return false
                }
            }

            mutateGhostwriteProgress(binding: expectedBinding) {
                $0.phase = .collecting
                $0.candidateID = candidateID
            }
            let collected = await autoCollectGhostwriteCandidate(
                candidateID,
                plan: plan
            )
            guard collected else {
                // 用户暂停/取消时 settle 会 return false，勿标成「收录失败」。
                try Task.checkCancellation()
                let reason = ghostwriteCollectPauseReason(for: candidateID)
                pauseGhostwritePipeline(
                    binding: expectedBinding,
                    reason: reason,
                    detail: reason == .collectBaseStale
                        ? reason.displayMessage
                        : operationErrorMessage,
                    candidateID: candidateID
                )
                return false
            }
            // 收录成功立刻记入幂等集合，避免清合同前取消导致同合同再写一章。
            mutateGhostwriteProgress(binding: expectedBinding) {
                $0.autoCollectedCandidateIDs.insert(candidateID)
                $0.candidateID = candidateID
                $0.resetChapterHealState()
            }
            // 收录成功即消费合同：同步失败也不得用同一合同开下一章。
            let planCleared = await workspace.clearChapterPlan(
                branchID: expectedBinding.branchID
            )
            guard planCleared else {
                pauseGhostwritePipeline(
                    binding: expectedBinding,
                    reason: .collectFailed,
                    detail: "本章已收录，但未能清除计划。请手动清除后再继续。",
                    candidateID: candidateID
                )
                return false
            }
            mutateGhostwriteProgress(binding: expectedBinding) {
                $0.chapterPlanDigest = nil
                $0.phase = .syncing
                $0.pendingSyncChapterCredit = true
                $0.lastCompletedPlanSummary = plan.ghostwriteBatchSummary()
            }

            let synced = await awaitGhostwriteStateSync(expectedBinding: expectedBinding)
            try Task.checkCancellation()
            guard synced else {
                let syncDetail = [
                    workspace.errorMessage,
                    "本章已收录；同步完成后可继续本批下一章。",
                ]
                .compactMap { $0 }
                .joined(separator: " ")
                pauseGhostwritePipeline(
                    binding: expectedBinding,
                    reason: .syncFailed,
                    detail: syncDetail,
                    candidateID: candidateID
                )
                return false
            }
            return true
        }
    }

    /// 登记质量失败；若预算允许则准备自动改写并返回 true（调用方 `continue` 重写）。
    /// 预算/指纹用尽时：复读可 mustNot 薄升级；仅缺 1 条 must 可措辞对齐；否则 pause。
    @discardableResult
    private func registerGhostwriteQualityFailure(
        binding expectedBinding: NovelSessionBinding,
        reason: NovelGhostwritePauseReason,
        detail: String,
        missingMustHappen: [String],
        forbiddenViolations: [String],
        repetitionBeats: [String],
        continuityNotes: [String],
        candidateID: NovelCandidateID,
        planDigest: String?
    ) async -> Bool {
        let priorIndex = ghostwriteProgressStorage?.qualityAttemptIndex ?? 0
        let receipt = NovelGhostwriteFailureReceipt.make(
            reason: reason,
            summary: detail,
            missingMustHappen: missingMustHappen,
            repetitionBeats: repetitionBeats,
            continuityNotes: continuityNotes,
            attemptIndex: priorIndex + 1,
            sourceCandidateID: candidateID,
            planDigest: planDigest
        )
        var healResult = (willRewrite: false, blockedByFingerprint: false)
        mutateGhostwriteProgress(binding: expectedBinding) {
            healResult = $0.registerQualityFailureForHeal(
                reason: reason,
                receipt: receipt,
                failedCandidateID: candidateID
            )
        }
        if healResult.willRewrite {
            return true
        }

        let alreadyAmended = ghostwriteProgressStorage?.didThinContractAmendThisChapter ?? false

        // Tier2a：复读 → 追加 mustNot 后再给一轮（每章一次）。
        if NovelGhostwriteHeal.shouldAttemptMustNotAmend(
            reason: reason,
            receipt: receipt,
            alreadyAmendedThisChapter: alreadyAmended
        ) {
            let amended = await attemptThinMustNotAmend(
                expectedBinding: expectedBinding,
                receipt: receipt
            )
            if amended {
                return true
            }
        }

        // Tier2b：仅缺 1 条 must → 放宽该条措辞对齐后再给一轮（每章一次）。
        if NovelGhostwriteHeal.shouldAttemptMustAlign(
            reason: reason,
            receipt: receipt,
            forbiddenViolations: forbiddenViolations,
            alreadyAmendedThisChapter: ghostwriteProgressStorage?.didThinContractAmendThisChapter
                ?? false
        ) {
            let amended = await attemptThinMustAlign(
                expectedBinding: expectedBinding,
                receipt: receipt
            )
            if amended {
                return true
            }
        }

        let maxAttempts = ghostwriteProgressStorage?.maxQualityAttempts
            ?? NovelGhostwriteHeal.defaultMaxQualityAttempts
        let exhaustedDetail: String
        if reason.allowsAutomaticQualityHeal {
            var lines = [detail]
            if healResult.blockedByFingerprint {
                lines.append("连续两次同一类问题，已停止空转改写。")
            } else {
                lines.append(
                    "已自动改写 \(max(0, maxAttempts - 1)) 次仍未过关。"
                )
            }
            lines.append("可按审稿意见润修，或整章重写 / 改计划。")
            exhaustedDetail = lines.joined(separator: "\n")
        } else {
            exhaustedDetail = detail
        }
        pauseGhostwritePipeline(
            binding: expectedBinding,
            reason: reason.allowsAutomaticQualityHeal ? .healBudgetExhausted : reason,
            detail: exhaustedDetail,
            candidateID: candidateID
        )
        // 预算用尽时 pauseReason 用 healBudgetExhausted，但 receipt.reason 保留原始质量原因。
        mutateGhostwriteProgress(binding: expectedBinding) {
            $0.lastFailureReceipt = receipt
        }
        return false
    }

    /// 把复读 beat 写入本章合同 mustNot，digest 更新后准备再写一轮。
    private func attemptThinMustNotAmend(
        expectedBinding: NovelSessionBinding,
        receipt: NovelGhostwriteFailureReceipt
    ) async -> Bool {
        guard let plan = workspace.projectSnapshot?
            .confirmedChapterPlan(for: expectedBinding.branchID)
        else { return false }
        let existing = Set(
            plan.mustNotHappen.map {
                $0.trimmingCharacters(in: .whitespacesAndNewlines)
            }.filter { !$0.isEmpty }
        )
        var additions: [String] = []
        for beat in receipt.repetitionBeats {
            let trimmed = beat.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            let already = existing.contains { old in
                old == trimmed || old.contains(trimmed) || trimmed.contains(old)
            }
            if !already {
                additions.append(trimmed)
            }
        }
        guard !additions.isEmpty else { return false }

        let newMustNot = plan.mustNotHappen + additions
        let beforeDigest = plan.contentDigest
        // upsert 使用 workspace 当前选中分支；代笔主线要求主分支且与 session binding 一致。
        guard workspace.selectedBranchID == expectedBinding.branchID else { return false }
        let saved = await workspace.upsertChapterPlan(
            planID: plan.id,
            status: .confirmed,
            outlinePlacement: plan.outlinePlacement,
            goalAndConflict: plan.goalAndConflict,
            mustHappen: plan.mustHappen,
            mustNotHappen: newMustNot,
            endingHook: plan.endingHook,
            visibleFacts: plan.visibleFacts
        )
        guard saved else { return false }
        _ = await refreshDurable(binding: binding, token: bindingToken)
        let afterDigest = workspace.projectSnapshot?
            .confirmedChapterPlan(for: expectedBinding.branchID)?
            .contentDigest
        let chapterIndex = ghostwriteProgressStorage?.currentChapterIndex ?? 1
        let amendment = NovelGhostwriteContractAmendment(
            kind: .appendMustNot,
            detail: additions.joined(separator: "；"),
            chapterIndex: chapterIndex,
            beforeDigest: beforeDigest,
            afterDigest: afterDigest
        )
        mutateGhostwriteProgress(binding: expectedBinding) {
            $0.prepareAfterThinContractAmend(
                amendment: amendment,
                newPlanDigest: afterDigest
            )
            // 注入：禁止项写进 brief，避免只靠合同遗漏。
            $0.revisionBriefOverride = """
            合同已更新禁止项，请勿再写：
            \(additions.map { "- \($0)" }.joined(separator: "\n"))
            上一稿已写好的部分视为已确定，只改上述禁止项与未落实的必发生。
            """
        }
        return true
    }

    /// 仅缺 1 条 must：放宽该条合同措辞（允许等价表达），再写一轮；不删 must、不改 goal。
    private func attemptThinMustAlign(
        expectedBinding: NovelSessionBinding,
        receipt: NovelGhostwriteFailureReceipt
    ) async -> Bool {
        guard let plan = workspace.projectSnapshot?
            .confirmedChapterPlan(for: expectedBinding.branchID)
        else { return false }
        guard let missing = receipt.missingMustHappen.first else { return false }
        guard let rephrase = NovelGhostwriteHeal.rephraseSingleMust(
            planMustHappen: plan.mustHappen,
            missingItem: missing,
            acceptanceSummary: receipt.summary
        ) else { return false }

        var newMust = plan.mustHappen
        guard rephrase.index >= 0, rephrase.index < newMust.count else { return false }
        newMust[rephrase.index] = rephrase.rewritten

        let beforeDigest = plan.contentDigest
        guard workspace.selectedBranchID == expectedBinding.branchID else { return false }
        let saved = await workspace.upsertChapterPlan(
            planID: plan.id,
            status: .confirmed,
            outlinePlacement: plan.outlinePlacement,
            goalAndConflict: plan.goalAndConflict,
            mustHappen: newMust,
            mustNotHappen: plan.mustNotHappen,
            endingHook: plan.endingHook,
            visibleFacts: plan.visibleFacts
        )
        guard saved else { return false }
        _ = await refreshDurable(binding: binding, token: bindingToken)
        let afterDigest = workspace.projectSnapshot?
            .confirmedChapterPlan(for: expectedBinding.branchID)?
            .contentDigest
        let chapterIndex = ghostwriteProgressStorage?.currentChapterIndex ?? 1
        let amendment = NovelGhostwriteContractAmendment(
            kind: .alignSingleMust,
            detail: "「\(rephrase.original)」→「\(rephrase.rewritten)」",
            chapterIndex: chapterIndex,
            beforeDigest: beforeDigest,
            afterDigest: afterDigest
        )
        mutateGhostwriteProgress(binding: expectedBinding) {
            $0.prepareAfterThinContractAmend(
                amendment: amendment,
                newPlanDigest: afterDigest
            )
            $0.revisionBriefOverride = """
            本章合同已放宽一条必发生措辞（保留意图，允许等价表达）：
            - 原：\(rephrase.original)
            - 现：\(rephrase.rewritten)

            上一稿已写好的部分视为已确定；请明确写出可辨认的对应情绪/动作，不要只靠暗示。
            """
            $0.detailMessage = "已放宽 1 条必发生措辞，再写一轮…"
        }
        return true
    }

    /// 处理「已收录、待同步记账」：同步成功后 completed+=1，不重写、不新拟合同。
    private func settlePendingGhostwriteSyncCredit(
        expectedBinding: NovelSessionBinding
    ) async throws -> Bool {
        mutateGhostwriteProgress(binding: expectedBinding) {
            $0.phase = .syncing
            $0.pauseReason = nil
            $0.detailMessage = nil
        }
        let synced = await awaitGhostwriteStateSync(expectedBinding: expectedBinding)
        try Task.checkCancellation()
        guard synced else {
            pauseGhostwritePipeline(
                binding: expectedBinding,
                reason: .syncFailed,
                detail: "本章已收录；同步完成后可继续本批下一章。",
                candidateID: ghostwriteProgressStorage?.candidateID
            )
            return false
        }
        mutateGhostwriteProgress(binding: expectedBinding) {
            _ = $0.applyPendingSyncChapterCredit()
        }
        return true
    }

    private func proposeNextGhostwritePlan(
        expectedBinding: NovelSessionBinding
    ) async throws -> NovelChapterPlanRecord? {
        // 新批首章（completed==0）：拟完计划后暂停让用户确认。
        // 批内后续（completed>0）：拟完直接连写，不中断节奏。
        let completedBeforeProposal = ghostwriteProgressStorage?.completedChapterCount ?? 0
        let isAutoProposalForUserConfirmation = completedBeforeProposal == 0

        mutateGhostwriteProgress(binding: expectedBinding) {
            $0.phase = .planning
            $0.pauseReason = nil
            $0.detailMessage = nil
            $0.chapterPlanDigest = nil
        }
        let synced = await awaitGhostwriteStateSync(expectedBinding: expectedBinding)
        try Task.checkCancellation()
        guard synced else {
            pauseGhostwritePipeline(
                binding: expectedBinding,
                reason: .syncFailed,
                detail: "拟定下一章计划前需要完成剧情同步。",
                candidateID: ghostwriteProgressStorage?.candidateID
            )
            return nil
        }
        let ordinal = (ghostwriteProgressStorage?.completedChapterCount ?? 0) + 1
        let previousSummary = ghostwriteProgressStorage?.lastCompletedPlanSummary
        let maxProposalAttempts = NovelGhostwriteHeal.defaultMaxInfraRetries
        var lastError: Error?
        for attempt in 1...maxProposalAttempts {
            do {
                try Task.checkCancellation()
                if attempt > 1 {
                    mutateGhostwriteProgress(binding: expectedBinding) {
                        $0.detailMessage = "拟定计划重试 \(attempt)/\(maxProposalAttempts)…"
                    }
                    try? await Task.sleep(for: .milliseconds(400 * attempt))
                }
                let plan = try await workspace.proposeAndConfirmNextChapterPlan(
                    projectID: expectedBinding.projectID,
                    branchID: expectedBinding.branchID,
                    nextChapterOrdinal: ordinal,
                    previousPlanSummary: previousSummary
                )
                _ = await refreshDurable(binding: binding, token: bindingToken)
                if isAutoProposalForUserConfirmation {
                    // 新批首章：计划已拟定并落盘，暂停等用户确认后再连写。
                    pauseGhostwritePipeline(
                        binding: expectedBinding,
                        reason: .planProposedForNewBatch,
                        detail: nil,
                        candidateID: ghostwriteProgressStorage?.candidateID
                    )
                    return nil
                }
                mutateGhostwriteProgress(binding: expectedBinding) {
                    $0.chapterPlanDigest = plan.contentDigest
                    $0.phase = .writing
                    $0.detailMessage = nil
                }
                return plan
            } catch is CancellationError {
                throw CancellationError()
            } catch let failure as NovelStructuredModelExecutionFailure
                where failure.failure.code == "cancelled" {
                throw CancellationError()
            } catch let failure as NovelStructuredModelExecutionFailure
                where failure.failure.isRetryable && attempt < maxProposalAttempts {
                lastError = failure
                continue
            } catch {
                lastError = error
                // 非 retryable 或最后一次：退出循环
                if attempt >= maxProposalAttempts { break }
                // 未知错误也允许再试一次
                continue
            }
        }
        pauseGhostwritePipeline(
            binding: expectedBinding,
            reason: .planProposalFailed,
            detail: lastError.map(describe) ?? "自动拟定下一章计划失败。",
            candidateID: ghostwriteProgressStorage?.candidateID
        )
        return nil
    }

    private func finishGhostwriteBatch(
        binding expectedBinding: NovelSessionBinding,
        candidateID: NovelCandidateID?
    ) {
        let target = ghostwriteProgressStorage?.targetChapterCount
            ?? NovelGhostwriteBatch.minChapterCount
        let completed = ghostwriteProgressStorage?.completedChapterCount ?? 0
        let highlightCount = ghostwriteRecentHighlightCount(for: expectedBinding)
        let reason: NovelGhostwritePauseReason = target > 1 ? .batchCompleted : .chapterCompleted
        var detail: String
        if target > 1 {
            detail = "本批 \(completed) 章已收录并同步。"
        } else {
            detail = "本章已收录并同步。"
        }
        if highlightCount > 0 {
            detail += " 已记入 \(highlightCount) 条近期要点，方便下一章少复读。"
        }
        if target == 1 {
            detail += " 请先定好下一章计划再继续。"
        } else {
            detail += " 下一批请先确认首章计划。"
        }
        mutateGhostwriteProgress(binding: expectedBinding) {
            $0.phase = .waitingUser
            $0.pauseReason = reason
            $0.detailMessage = detail
            $0.candidateID = candidateID ?? $0.candidateID
            $0.chapterPlanDigest = nil
        }
    }

    private func stopOwnedGhostwriteRun(
        binding expectedBinding: NovelSessionBinding,
        reason: NovelRunInterruptionReason
    ) async {
        let runID = ghostwriteOwnedRunID ?? (isGhostwriteStartingRun ? activeRunID : nil)
        guard let runID,
              binding == expectedBinding else { return }
        let durableRun = workspace.projectSnapshot?.activeRuns.first {
            $0.id == runID && $0.branchID == expectedBinding.branchID
        }
        if let durableRun, durableRun.status != .running {
            if transientTail?.runID == runID { clearTransientTail() }
            return
        }
        guard durableRun?.status == .running || activeRunID == runID else { return }
        _ = await interruptBoundRun(reason: reason, runID: runID)
    }

    private func canReuseGhostwriteCandidate(
        _ candidate: NovelCandidateRecord,
        plan: NovelChapterPlanRecord
    ) -> Bool {
        guard let document = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot?.branch else {
            return false
        }
        let sourceMessage = document.sessions
            .first(where: { $0.id == candidate.sessionID })?
            .messages.first(where: { $0.id == candidate.sourceMessageID })
        return NovelGhostwriteCandidateOwnership.canReuseForAutomaticCollect(
            candidate,
            plan: plan,
            branchHeadCheckpointID: branch.headCheckpointID,
            branchHeadRevision: branch.headRevision,
            checkpoints: document.checkpoints,
            sourceMessage: sourceMessage,
            superseded: ghostwriteProgressStorage?.supersededCandidateIDs ?? [],
            alreadyCollected: ghostwriteProgressStorage?.autoCollectedCandidateIDs ?? []
        )
    }

    private func ghostwriteCollectPauseReason(
        for candidateID: NovelCandidateID
    ) -> NovelGhostwritePauseReason {
        let found = candidate(id: candidateID)
        let document = workspace.projectSnapshot
        let branch = workspace.branchSnapshot?.branch
        let sourceMessage: NovelSessionMessageRecord? = {
            guard let found, let document else { return nil }
            return document.sessions
                .first(where: { $0.id == found.sessionID })?
                .messages.first(where: { $0.id == found.sourceMessageID })
        }()
        return NovelGhostwriteCollectFailure.pauseReason(
            candidate: found,
            branchHeadCheckpointID: branch?.headCheckpointID,
            branchHeadRevision: branch?.headRevision,
            checkpoints: document?.checkpoints ?? [],
            sourceMessage: sourceMessage
        )
    }

    private func obtainGhostwriteCandidate(
        plan: NovelChapterPlanRecord,
        expectedBinding: NovelSessionBinding
    ) async throws -> NovelCandidateID {
        // 质量失败 / 自动改写中：必须产新稿，禁止复用已作废或当前失败候选。
        let mustRewrite: Bool = {
            // 第一条款仅描述「章内自愈在途」（heal 置 phase=.writing 且清 pauseReason）；
            // 已暂停状态一律以 pauseReason 为唯一权威，避免「审计未完整」这类
            // 保留候选的暂停被误判成强制重写。
            if ghostwriteProgressStorage?.pauseReason == nil,
               ghostwriteProgressStorage?.lastFailureReceipt != nil,
               (ghostwriteProgressStorage?.qualityAttemptIndex ?? 0) > 0,
               ghostwriteProgressStorage?.phase == .writing {
                return true
            }
            return ghostwriteProgressStorage?.pauseReason?.requiresRewriteOnContinue == true
        }()
        if !mustRewrite,
           let ownedID = ghostwriteProgressStorage?.candidateID,
           let owned = candidate(id: ownedID),
           canReuseGhostwriteCandidate(owned, plan: plan) {
            return ownedID
        }
        if !mustRewrite,
           let recovered = candidates(kind: .prose)
            .filter({ canReuseGhostwriteCandidate($0, plan: plan) })
            .max(by: { $0.createdAt < $1.createdAt }) {
            mutateGhostwriteProgress(binding: expectedBinding) {
                $0.candidateID = recovered.id
            }
            return recovered.id
        }
        let healReceipt = ghostwriteProgressStorage?.lastFailureReceipt
        let revisionBrief = ghostwriteProgressStorage?.revisionBriefOverride
        let isHealRewrite = healReceipt != nil
            || revisionBrief != nil
            || (ghostwriteProgressStorage?.qualityAttemptIndex ?? 0) > 0
            || ghostwriteProgressStorage?.phase == .revising
        // 润修与自动自愈都钉住上一稿：确定性部分由宿主固化。
        let sourceDraft: String? = {
            guard let sourceID = healReceipt?.sourceCandidateID,
                  let body = candidate(id: sourceID)?.content else { return nil }
            let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }()
        let userText = NovelGhostwriteProgress.writeUserText(
            receipt: healReceipt,
            revisionBrief: revisionBrief,
            sourceDraft: sourceDraft
        )
        mutateGhostwriteProgress(binding: expectedBinding) {
            $0.phase = revisionBrief == nil ? .writing : .revising
            $0.chapterPlanDigest = plan.contentDigest
            $0.candidateID = nil
            $0.pauseReason = nil
            $0.revisionBriefOverride = nil // 只消费一次
            if $0.detailMessage == nil || $0.qualityAttemptIndex == 0 {
                $0.detailMessage = revisionBrief == nil ? nil : "按审稿意见润修中…"
            }
        }
        let preexisting = Set(
            candidates(kind: .prose)
                .filter { $0.status == .available }
                .map(\.id)
        )
        isGhostwriteStartingRun = true
        let started = await start(NovelSessionRunDraft(
            kind: .prose,
            mode: .writeProse,
            granularity: .wholeChapter,
            userText: userText,
            sourceChapterVersionID: nil,
            askUserResponse: nil,
            injectionOverrides: .none,
            inputBudgetTokens: NovelGhostwriteBatch.writeInputBudgetTokens,
            ghostwritePlanID: plan.id,
            suppressRecentSessionMessages: isHealRewrite
        ))
        ghostwriteOwnedRunID = activeRunID
        isGhostwriteStartingRun = false
        try Task.checkCancellation()
        guard started else {
            throw NovelError.invalidInput(operationErrorMessage ?? "代笔写整章没有开始。")
        }
        guard let candidateID = try await awaitGhostwriteCandidate(
            plan: plan,
            preexistingIDs: preexisting,
            expectedBinding: expectedBinding
        ) else {
            throw NovelError.invalidInput(operationErrorMessage ?? "代笔没有产出完整候选。")
        }
        ghostwriteOwnedRunID = nil
        mutateGhostwriteProgress(binding: expectedBinding) {
            $0.candidateID = candidateID
        }
        return candidateID
    }

    private func awaitGhostwriteCandidate(
        plan: NovelChapterPlanRecord,
        preexistingIDs: Set<NovelCandidateID>,
        expectedBinding: NovelSessionBinding
    ) async throws -> NovelCandidateID? {
        let startedAt = Date()
        while true {
            try Task.checkCancellation()
            _ = await refreshDurable(binding: binding, token: bindingToken)
            try Task.checkCancellation()
            if let candidate = candidates(kind: .prose).first(where: {
                $0.status == .available &&
                    NovelGhostwriteCandidateOwnership.belongs($0, to: plan) &&
                    !preexistingIDs.contains($0.id)
            }) {
                return candidate.id
            }
            if let interrupted = candidates(kind: .prose).first(where: {
                $0.status == .interrupted &&
                    NovelGhostwriteCandidateOwnership.belongs($0, to: plan) &&
                    !preexistingIDs.contains($0.id)
            }), activeRunID == nil, !isStarting {
                mutateGhostwriteProgress {
                    $0.candidateID = interrupted.id
                }
                throw NovelError.invalidInput("本章正文不完整。")
            }
            let elapsed = Date().timeIntervalSince(startedAt)
            let settled = activeRunID == nil && !isStarting
            if (settled && elapsed > batchPolishSettleGrace) ||
                elapsed > batchPolishCandidateTimeout {
                await stopOwnedGhostwriteRun(binding: expectedBinding, reason: .user)
                return nil
            }
            try await Task.sleep(for: .milliseconds(300))
        }
    }

    private func autoCollectGhostwriteCandidate(
        _ candidateID: NovelCandidateID,
        plan: NovelChapterPlanRecord
    ) async -> Bool {
        // 只等生成 run 从分支上摘掉；同步/锁由 collectCandidate 门禁负责。
        _ = await refreshDurable(binding: binding, token: bindingToken)
        let settleDeadline = Date().addingTimeInterval(3)
        while Date() < settleDeadline {
            if Task.isCancelled { return false }
            if workspace.branchSnapshot?.branch.activeRunID == nil { break }
            try? await Task.sleep(for: .milliseconds(150))
            _ = await refreshDurable(binding: binding, token: bindingToken)
        }
        if Task.isCancelled { return false }

        if ghostwriteProgressStorage?.autoCollectedCandidateIDs.contains(candidateID) == true {
            return true
        }
        guard let candidate = candidate(id: candidateID),
              candidate.status == .available else {
            operationErrorMessage = "找不到可自动收录的完整正文候选。"
            return false
        }
        // 与领域 systemAutoCollect 一致：以合同 digest 绑定为准。
        // ghostwritePlanID 仅作增强校验——有则必须匹配，缺省不挡（旧候选/恢复路径）。
        guard candidate.chapterPlanDigest == plan.contentDigest else {
            operationErrorMessage = "这篇稿没有绑定当前本章计划，无法自动收录。"
            return false
        }
        if let boundPlanID = candidate.ghostwritePlanID, boundPlanID != plan.id {
            operationErrorMessage = "这篇稿和当前计划对不上，无法自动收录。"
            return false
        }
        let paragraphs = NovelParagraphParser.paragraphs(in: candidate.content)
        guard !paragraphs.isEmpty else {
            operationErrorMessage = "候选正文为空，无法自动收录。"
            return false
        }
        let title = plan.outlinePlacement.trimmingCharacters(in: .whitespacesAndNewlines)
        let chapterTitle = title.isEmpty ? "未命名章节" : title
        let selection = NovelParagraphSelection(
            paragraphIDs: paragraphs.map(\.id),
            editedText: nil
        )
        let collected = await collectCandidate(
            candidateID,
            selection: selection,
            target: .createNextChapter(
                chapterID: NovelChapterID(),
                title: chapterTitle
            ),
            source: .systemAutoCollect
        )
        if !collected,
           operationErrorMessage == nil || operationErrorMessage?.isEmpty == true {
            operationErrorMessage = "自动收录失败。"
        }
        return collected
    }

    private func awaitGhostwriteStateSync(
        expectedBinding: NovelSessionBinding
    ) async -> Bool {
        // 不用「从进入起 240s」墙钟：多 chunk rebuild 本身就可能超过 4 分钟。
        // 有进展（chunk/activity/状态变化）就刷新；仅在空闲/失败且 kick 用尽，
        // 或长时间完全无进展时才判失败。
        let idleStallTimeout: TimeInterval = 240
        let inFlightStallTimeout: TimeInterval = 900
        var lastProgressAt = Date()
        var lastProgressSignature = ""
        var infraRetries = 0
        let maxInfra = NovelGhostwriteHeal.defaultMaxInfraRetries
        var lastKickAt: Date?
        while true {
            if Task.isCancelled { return false }
            _ = await refreshDurable(binding: binding, token: bindingToken)
            guard binding == expectedBinding else { return false }
            let branch = workspace.branchSnapshot?.branch
            let pending = branchPendingOperations
            if branch?.syncStatus == .synchronized, pending.isEmpty {
                mutateGhostwriteProgress(binding: expectedBinding) {
                    $0.infraRetryCount = 0
                    $0.detailMessage = nil
                }
                return true
            }

            let syncFailedMessage = workspace.automaticStateSyncFailureMessage(
                projectID: expectedBinding.projectID,
                branchID: expectedBinding.branchID
            )
            let activity = workspace.stateSyncActivity
            let isRunning = workspace.isStateSyncOperationRunning
            let pendingStatus = pending.first?.status
            let nextChunk = pending.first?.manualSyncProgress?.nextChunkIndex
            let completedChars = activity?.completedCharacters ?? -1
            let completedChunks = activity?.completedChunks ?? -1
            let progressSignature = [
                branch?.syncStatus.rawValue ?? "?",
                "\(pending.count)",
                pendingStatus?.rawValue ?? "-",
                "\(nextChunk.map(String.init) ?? "-")",
                "\(completedChunks)",
                "\(completedChars)",
                activity.map { "\($0.phase)" } ?? "none",
                isRunning ? "run" : "idle",
                syncFailedMessage ?? ""
            ].joined(separator: "|")
            if progressSignature != lastProgressSignature {
                lastProgressSignature = progressSignature
                lastProgressAt = Date()
                // chunk 前进视为真实进展，重置 kick 预算。
                if let nextChunk, nextChunk > 0 {
                    infraRetries = 0
                }
            }

            let workInFlight = isRunning
                || activity != nil
                || pending.contains {
                    $0.kind == .manualSync && $0.status == .pending
                }
            let idleNeedsSync = branch?.syncStatus == .needsSync
                && pending.isEmpty
                && !workInFlight
            let stuckRetryable = pending.count == 1
                && pending[0].kind == .manualSync
                && pending[0].status == .retryable
                && !workInFlight
            let shouldKick = idleNeedsSync
                && infraRetries < maxInfra
                && (lastKickAt.map { Date().timeIntervalSince($0) >= 1.5 } ?? true)

            if shouldKick {
                infraRetries += 1
                lastKickAt = Date()
                mutateGhostwriteProgress(binding: expectedBinding) {
                    $0.phase = .syncing
                    $0.infraRetryCount = infraRetries
                    $0.detailMessage = "剧情同步重试 \(infraRetries)/\(maxInfra)…"
                }
                workspace.retryStateSync(
                    projectID: expectedBinding.projectID,
                    branchID: expectedBinding.branchID
                )
                try? await Task.sleep(for: .milliseconds(500))
                continue
            }

            // Automatic sync already spent its heal budget. Do not kick the same
            // retryable pending again — that would stack another 3 model calls.
            let healExhausted = !workInFlight
                && (stuckRetryable || syncFailedMessage != nil)
                && Date().timeIntervalSince(lastProgressAt) > 2
            if healExhausted {
                return false
            }

            if idleNeedsSync,
               infraRetries >= maxInfra,
               Date().timeIntervalSince(lastProgressAt) > 3 {
                return false
            }

            let stallLimit = workInFlight ? inFlightStallTimeout : idleStallTimeout
            if Date().timeIntervalSince(lastProgressAt) > stallLimit {
                return false
            }
            try? await Task.sleep(for: .milliseconds(400))
        }
    }

    private func pauseGhostwritePipeline(
        binding expectedBinding: NovelSessionBinding,
        reason: NovelGhostwritePauseReason,
        detail: String?,
        candidateID: NovelCandidateID?
    ) {
        let phase: NovelGhostwritePhase = switch reason {
        case .chapterCompleted, .batchCompleted, .planProposedForNewBatch: .waitingUser
        case .healBudgetExhausted: .waitingUser
        case .acceptanceFailed, .obviousRepetition, .blockingContinuity,
             .continuityAuditIncomplete, .userPaused, .cancelled,
             .planProposalFailed:
            .paused
        case .collectFailed, .collectBaseStale, .syncFailed, .incompleteCandidate,
             .planMismatch, .infrastructureFailed:
            .failed
        }
        mutateGhostwriteProgress(binding: expectedBinding) {
            $0.phase = phase
            $0.pauseReason = reason
            $0.detailMessage = detail ?? reason.displayMessage
            $0.candidateID = candidateID ?? $0.candidateID
            if reason.requiresRewriteOnContinue, let candidateID {
                $0.supersededCandidateIDs.insert(candidateID)
            }
        }
        // 代笔中断由 ghostwrite status bar 独占呈现与恢复动作；不要再把同一
        // 文案投到通用 error banner，后者的「重试」并不拥有整条 pipeline。
        operationErrorMessage = nil
    }

    private func ghostwriteRecentHighlightCount(
        for expectedBinding: NovelSessionBinding
    ) -> Int {
        guard let project = workspace.projectSnapshot,
              let branch = project.branches.first(where: {
                  $0.id == expectedBinding.branchID
              }),
              let state = project.stateSnapshots.first(where: {
                  $0.id == branch.currentStateSnapshotID
              }) else {
            return 0
        }
        return state.recentWrittenHighlights.count
    }

    private func mutateGhostwriteProgress(
        binding expectedBinding: NovelSessionBinding? = nil,
        _ body: (inout NovelGhostwriteProgress) -> Void
    ) {
        guard var progress = ghostwriteProgressStorage else { return }
        if let expectedBinding, progress.binding != expectedBinding { return }
        body(&progress)
        ghostwriteProgressStorage = progress
        persistGhostwriteProgress(progress)
        advanceGhostwriteBackgroundProgress(by: 1, subtitle: progress.statusLabel)
    }

    private func beginGhostwriteBackgroundLease(
        binding: NovelSessionBinding,
        ownerID: UUID
    ) {
        let leaseID = novelGhostwriteBackgroundLeaseID(
            projectID: binding.projectID,
            branchID: binding.branchID
        )
        BackgroundGenerationKeepAlive.shared.begin(
            leaseID,
            title: "Amber 代笔中",
            subtitle: ghostwriteProgressStorage?.statusLabel ?? "准备代笔",
            onExpire: { [weak self] in
                Task { @MainActor [weak self] in
                    self?.expireGhostwriteBackgroundLease(
                        binding: binding,
                        ownerID: ownerID
                    )
                }
            },
            onSystemTaskExpiration: { [weak self] in
                Task { @MainActor [weak self] in
                    self?.expireGhostwriteBackgroundLease(
                        binding: binding,
                        ownerID: ownerID
                    )
                }
            }
        )
        BackgroundGenerationKeepAlive.shared.updateProgress(
            leaseID,
            completed: 0,
            total: -1,
            subtitle: ghostwriteProgressStorage?.statusLabel ?? "准备代笔"
        )
        advanceGhostwriteBackgroundProgress(by: 1)
    }

    private func advanceGhostwriteBackgroundProgress(
        by units: Int64,
        subtitle: String? = nil
    ) {
        guard let progress = ghostwriteProgressStorage,
              progress.binding == ghostwriteTaskBinding else { return }
        BackgroundGenerationKeepAlive.shared.advanceProgress(
            novelGhostwriteBackgroundLeaseID(
                projectID: progress.binding.projectID,
                branchID: progress.binding.branchID
            ),
            by: units,
            subtitle: subtitle ?? progress.statusLabel
        )
    }

    private func expireGhostwriteBackgroundLease(
        binding expectedBinding: NovelSessionBinding,
        ownerID: UUID
    ) {
        guard ghostwriteTaskBinding == expectedBinding,
              ghostwriteBackgroundLeaseOwnerID == ownerID,
              ghostwriteTask != nil else { return }
        mutateGhostwriteProgress(binding: expectedBinding) {
            $0.phase = .failed
            $0.pauseReason = .infrastructureFailed
            $0.detailMessage = "后台执行时间已结束，当前批次进度已保存，可以继续。"
        }
        workspace.cancelAutomaticStateSync(
            projectID: expectedBinding.projectID,
            branchID: expectedBinding.branchID,
            suppressReschedule: false
        )
        ghostwriteTask?.cancel()
    }

    /// 验收/连续性审计的基建重试入口：重试时在进度面板给出阶段性提示。
    private func withGhostwriteInfraRetry<T: Sendable>(
        binding expectedBinding: NovelSessionBinding,
        stage: String,
        operation: @Sendable () async throws -> T
    ) async throws -> T {
        try await NovelGhostwriteInfraRetry.run(onRetry: { [weak self] attempt in
            await self?.mutateGhostwriteProgress(binding: expectedBinding) {
                $0.detailMessage =
                    "\(stage)调用失败，正在重试 \(attempt + 1)/\(NovelGhostwriteInfraRetry.maxAttempts)…"
            }
        }, operation: operation)
    }

    /// 冷启动 / 重绑会话：从 sidecar 恢复本批代笔进度（不抢正在跑的 task）。
    private func restoreGhostwriteProgressIfNeeded(
        for expectedBinding: NovelSessionBinding
    ) async {
        guard ghostwriteTask == nil else { return }
        if let current = ghostwriteProgressStorage, current.binding == expectedBinding {
            return
        }
        guard let restored = await workspace.loadGhostwriteBatchProgress(
            projectID: expectedBinding.projectID,
            branchID: expectedBinding.branchID
        ) else {
            if ghostwriteProgressStorage?.binding != expectedBinding {
                ghostwriteProgressStorage = nil
            }
            return
        }
        guard restored.binding == expectedBinding else { return }
        // 完批记录不应继续占面板；清掉磁盘脏文件。
        if restored.isBatchComplete, restored.pendingSyncChapterCredit != true {
            clearPersistedGhostwriteProgress(for: expectedBinding)
            if ghostwriteProgressStorage?.binding == expectedBinding {
                ghostwriteProgressStorage = nil
            }
            return
        }
        ghostwriteProgressStorage = restored
    }

    private func persistGhostwriteProgress(_ progress: NovelGhostwriteProgress) {
        workspace.persistGhostwriteBatchProgress(progress)
    }

    private func clearPersistedGhostwriteProgress(for binding: NovelSessionBinding) {
        workspace.clearGhostwriteBatchProgress(
            projectID: binding.projectID,
            branchID: binding.branchID
        )
    }
}
