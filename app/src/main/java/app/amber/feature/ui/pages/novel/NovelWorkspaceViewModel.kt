package app.amber.feature.ui.pages.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.feature.novel.NovelArchiveDecisionInput
import app.amber.feature.novel.NovelBackgroundRunRegistry
import app.amber.feature.novel.NovelCreation
import app.amber.feature.novel.NovelDiscussionArchiveDraft
import app.amber.feature.novel.NovelGenerationGranularityRequest
import app.amber.feature.novel.NovelGhostwriteBatchCommandFailure
import app.amber.feature.novel.NovelGhostwriteBatchCommandResult
import app.amber.feature.novel.NovelGhostwriteBatchController
import app.amber.feature.novel.NovelGhostwriteBatchProjectSnapshot
import app.amber.feature.novel.NovelGhostwriteCoordinator
import app.amber.feature.novel.NovelGhostwriteFailureReason
import app.amber.feature.novel.NovelGhostwritePhase
import app.amber.feature.novel.NovelGhostwriteProgress
import app.amber.feature.novel.NovelGhostwriteStartResult
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.NovelInterruptReason
import app.amber.feature.novel.NovelInterruptRequest
import app.amber.feature.novel.NovelQuery
import app.amber.feature.novel.NovelRunEvent
import app.amber.feature.novel.NovelRunKindRequest
import app.amber.feature.novel.NovelRunRequest
import app.amber.feature.novel.NovelSessionModeRequest
import app.amber.feature.novel.NovelSnapshot
import app.amber.feature.novel.domain.NovelGhostwriteReadiness
import app.amber.feature.novel.domain.NovelParagraphSelection
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelCandidateKind
import app.amber.feature.novel.model.NovelCandidateRecord
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelChapterPlanStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelGhostwriteJobV1
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelProposalId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelSessionMessageRecord
import app.amber.feature.novel.model.NovelSessionRole
import app.amber.feature.novel.persistence.NovelGhostwriteJobLoadAccess
import app.amber.feature.novel.persistence.NovelGhostwriteJobScanFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/** Top-level workspace surfaces — content-first, one job per tab. */
enum class NovelWorkspaceTab {
    /** Session chat + composer. */
    Chat,
    /** Canonical chapter manuscript. */
    Manuscript,
    /** Characters, world, plot, requirements. */
    Living,
}

/**
 * Draft for the collect bottom sheet — target + paragraph multi-select.
 * Built when the user taps collect; cleared on dismiss / success.
 */
data class NovelCollectSheetState(
    val candidateId: NovelCandidateId,
    val content: String,
    val isInterrupted: Boolean,
    val isWholeChapterDefault: Boolean,
    val chapterCount: Int,
    val currentChapterId: NovelChapterId?,
    val currentChapterTitle: String?,
    /** When set, collect sheet defaults to replaceChapter for regenerate candidates. */
    val replaceChapterId: NovelChapterId? = null,
    val replaceChapterTitle: String? = null,
)

data class NovelBatchPolishResult(
    val chapterId: NovelChapterId,
    val title: String,
    val outcome: String,
)

data class NovelContinuityUiIssue(
    val id: String,
    val severity: String,
    val category: String,
    val summary: String,
    val references: List<String>,
)

/**
 * Discussion-archive sheet UI: LLM distill prefill, then user confirm.
 * Distill is non-durable; confirm still runs [NovelIntent.ArchiveDiscussion].
 */
data class NovelArchiveSheetUi(
    val distilling: Boolean = false,
    val distillError: String? = null,
    val draft: NovelDiscussionArchiveDraft? = null,
    /** Editable summary / decisions after distill (or empty for manual entry on failure). */
    val summary: String = "",
    val decisions: List<Pair<String, String>> = listOf("" to ""),
)

data class NovelGhostwriteBatchUiState(
    val loading: Boolean = true,
    val job: NovelGhostwriteJobV1? = null,
    val access: NovelGhostwriteJobLoadAccess? = null,
    val projectHasActiveJob: Boolean = false,
    val scanFailures: List<NovelGhostwriteJobScanFailure> = emptyList(),
    val loadFailure: String? = null,
    val commandPending: Boolean = false,
    val notificationRequired: Boolean = false,
) {
    val ledgerBlocked: Boolean
        get() = scanFailures.isNotEmpty() || loadFailure != null

    val bindingHasActiveJob: Boolean
        get() = job?.isTerminal == false
}

data class NovelWorkspaceUiState(
    val loading: Boolean = true,
    val document: NovelProjectDocumentV1? = null,
    val access: NovelProjectLoadAccess = NovelProjectLoadAccess.ReadWrite,
    val primaryFailure: String? = null,
    val selectedBranchId: NovelBranchId? = null,
    val tab: NovelWorkspaceTab = NovelWorkspaceTab.Chat,
    val composerMode: NovelSessionModeRequest = NovelSessionModeRequest.DiscussPlan,
    val granularity: NovelGenerationGranularityRequest = NovelGenerationGranularityRequest.WholeChapter,
    val errorMessage: String? = null,
    /** Non-error feedback (e.g. collect success). */
    val statusMessage: String? = null,
    val busy: Boolean = false,
    /** Optional phase label while busy (e.g. collect / state-delta). */
    val busyPhase: String? = null,
    val draft: String = "",
    val streamingText: String = "",
    val generating: Boolean = false,
    val collectSheet: NovelCollectSheetState? = null,
    val archiveSheet: NovelArchiveSheetUi? = null,
    val batchPolishRunning: Boolean = false,
    val batchPolishResults: List<NovelBatchPolishResult> = emptyList(),
    val continuityIssues: List<NovelContinuityUiIssue>? = null,
    val continuityConsistent: Boolean? = null,
    /** App-owned single-chapter ghostwrite progress; survives this ViewModel instance. */
    val ghostwriteProgress: NovelGhostwriteProgress? = null,
    /** Durable multi-chapter ledger projection; it never owns the WorkManager execution. */
    val ghostwriteBatch: NovelGhostwriteBatchUiState = NovelGhostwriteBatchUiState(),
)

class NovelWorkspaceViewModel(
    projectId: String,
    private val novelCreation: NovelCreation,
    private val uiSession: NovelProjectUiSession,
    private val ghostwriteCoordinator: NovelGhostwriteCoordinator,
    private val backgroundRunRegistry: NovelBackgroundRunRegistry,
    private val ghostwriteBatchController: NovelGhostwriteBatchController,
) : ViewModel() {
    val projectId: NovelProjectId = NovelProjectId.parse(projectId)
    private val _state = MutableStateFlow(NovelWorkspaceUiState())
    val state: StateFlow<NovelWorkspaceUiState> = _state.asStateFlow()
    private var generateJob: Job? = null
    private var batchPolishJob: Job? = null
    private var batchPolishEpoch = 0L
    private var continuityJob: Job? = null
    private var archiveDistillJob: Job? = null
    /** Bumps on each new send/polish so a cancelled job's finally cannot clear the newer run. */
    private var generationEpoch = 0L
    /**
     * QuickStart only creates the project shell; iOS auto-runs generation after create.
     * Auto-kick once per successful attempt window. Failures clear the flag so retry works.
     */
    private var quickStartKickoffDone = false
    private var ghostwriteOwnedBusy = false
    private var ghostwriteStartPending = false
    private var latestGhostwriteBatchSnapshot: NovelGhostwriteBatchProjectSnapshot? = null

    init {
        refresh()
        viewModelScope.launch {
            ghostwriteCoordinator.progress.collect { latest ->
                val visible = ghostwriteProgressForBranch(
                    progress = latest,
                    projectId = this@NovelWorkspaceViewModel.projectId,
                    branchId = _state.value.selectedBranchId,
                )
                val running = visible.isRunning()
                val wasRunning = ghostwriteOwnedBusy
                val wasStartPending = ghostwriteStartPending
                val clearOwnedBusy = shouldClearGhostwriteBusy(
                    progress = visible,
                    wasRunning = wasRunning,
                    startPending = wasStartPending,
                )
                when {
                    running -> {
                        ghostwriteOwnedBusy = true
                        ghostwriteStartPending = false
                    }
                    clearOwnedBusy -> {
                        ghostwriteOwnedBusy = false
                        ghostwriteStartPending = false
                    }
                }
                _state.value = _state.value.copy(
                    ghostwriteProgress = visible,
                    busy = when {
                        running -> true
                        clearOwnedBusy -> false
                        else -> _state.value.busy
                    },
                    busyPhase = when {
                        running -> "后台代笔…"
                        clearOwnedBusy -> null
                        else -> _state.value.busyPhase
                    },
                )
                if (visible?.phase in setOf(
                        NovelGhostwritePhase.Paused,
                        NovelGhostwritePhase.WaitingUser,
                        NovelGhostwritePhase.Failed,
                    )
                ) {
                    refreshSuspend(quiet = true)
                }
            }
        }
        viewModelScope.launch {
            ghostwriteBatchController.observeProject(
                this@NovelWorkspaceViewModel.projectId,
            ).collect { snapshot ->
                val previous = _state.value.ghostwriteBatch.job
                latestGhostwriteBatchSnapshot = snapshot
                val currentBranchId = _state.value.selectedBranchId
                val visibleBranchId = preferredBranchForActiveBatch(
                    snapshot = snapshot,
                    selectedBranchId = currentBranchId,
                    isActiveBranch = { branchId ->
                        _state.value.document?.branches?.any {
                            it.id == branchId && it.lifecycle == NovelBranchLifecycle.Active
                        } == true
                    },
                ) ?: currentBranchId
                if (visibleBranchId != null && visibleBranchId != currentBranchId) {
                    archiveDistillJob?.cancel()
                    archiveDistillJob = null
                    uiSession.setSelectedBranch(this@NovelWorkspaceViewModel.projectId.rawValue, visibleBranchId)
                }
                val projected = projectGhostwriteBatchForBranch(
                    snapshot = snapshot,
                    branchId = visibleBranchId,
                    previous = _state.value.ghostwriteBatch,
                )
                _state.value = _state.value.copy(
                    selectedBranchId = visibleBranchId,
                    ghostwriteProgress = ghostwriteProgressForBranch(
                        progress = ghostwriteCoordinator.progress.value,
                        projectId = this@NovelWorkspaceViewModel.projectId,
                        branchId = visibleBranchId,
                    ),
                    ghostwriteBatch = projected,
                    archiveSheet = if (visibleBranchId != currentBranchId) null else _state.value.archiveSheet,
                )
                val latest = projected.job
                if (latest != null &&
                    (latest.completedChapterCount != previous?.completedChapterCount ||
                        latest.status != previous?.status)
                ) {
                    refreshSuspend(quiet = true)
                } else {
                    _state.value.document?.let { document ->
                        maybeKickQuickStart(document, _state.value.selectedBranchId)
                    }
                }
            }
        }
    }

    private var refreshSeq = 0L

    fun refresh(fromResume: Boolean = false) {
        viewModelScope.launch {
            // Resume with an already-loaded doc: quiet refresh so we don't flash loading
            // or wipe status/error mid-stream. Still re-evaluate quick-start kick.
            val quiet = fromResume && _state.value.document != null
            refreshSuspend(quiet = quiet)
        }
    }

    private suspend fun refreshSuspend(quiet: Boolean = false) {
        val seq = ++refreshSeq
        if (!quiet) {
            // Preserve in-flight generation feedback; only clear errors on a full reload.
            _state.value = _state.value.copy(
                loading = true,
                errorMessage = if (_state.value.generating) _state.value.errorMessage else null,
            )
        }
        runCatching {
            when (val snap = novelCreation.snapshot(NovelQuery.Project(projectId))) {
                is NovelSnapshot.Project -> {
                    if (seq != refreshSeq) return // superseded by a newer refresh
                    val doc = snap.document
                    val active = { id: NovelBranchId ->
                        doc.branches.any { it.id == id && it.lifecycle == NovelBranchLifecycle.Active }
                    }
                    val activeBatchBranch = latestGhostwriteBatchSnapshot?.let { snapshot ->
                        preferredBranchForActiveBatch(
                            snapshot = snapshot,
                            selectedBranchId = _state.value.selectedBranchId,
                            isActiveBranch = active,
                        )
                    }
                    val branchId = listOfNotNull(
                        activeBatchBranch,
                        uiSession.selectedBranch(projectId.rawValue),
                        _state.value.selectedBranchId,
                        doc.project.mainBranchID,
                    ).firstOrNull(active) ?: doc.project.mainBranchID
                    branchId?.let { uiSession.setSelectedBranch(projectId.rawValue, it) }
                    val branchProgress = ghostwriteProgressForBranch(
                        progress = ghostwriteCoordinator.progress.value,
                        projectId = projectId,
                        branchId = branchId,
                    )
                    val ghostwriteRunning = branchProgress.isRunning()
                    if (ghostwriteRunning) {
                        ghostwriteOwnedBusy = true
                        ghostwriteStartPending = false
                    }
                    val batchProjection = latestGhostwriteBatchSnapshot?.let { batchSnapshot ->
                        projectGhostwriteBatchForBranch(
                            snapshot = batchSnapshot,
                            branchId = branchId,
                            previous = _state.value.ghostwriteBatch,
                        )
                    } ?: _state.value.ghostwriteBatch
                    _state.value = _state.value.copy(
                        loading = false,
                        document = doc,
                        access = snap.access,
                        primaryFailure = snap.primaryFailure,
                        selectedBranchId = branchId,
                        ghostwriteProgress = branchProgress,
                        ghostwriteBatch = batchProjection,
                        busy = if (ghostwriteRunning) true else _state.value.busy,
                        busyPhase = if (ghostwriteRunning) "后台代笔…" else _state.value.busyPhase,
                    )
                    // Always re-check after load (quiet or not) — create opens workspace empty.
                    maybeKickQuickStart(doc, branchId)
                }
                else -> error("Unexpected snapshot")
            }
        }.onFailure { e ->
            if (seq != refreshSeq) return
            _state.value = _state.value.copy(
                loading = false,
                errorMessage = if (quiet) _state.value.errorMessage else e.message,
            )
        }
    }

    /** True when this project still needs the initial setting-proposal generation. */
    fun needsQuickStartGeneration(doc: NovelProjectDocumentV1? = _state.value.document): Boolean {
        doc ?: return false
        if (doc.project.creationMode != NovelProjectCreationMode.QuickStart) return false
        val seed = doc.project.quickStartSeed ?: return false
        if (seed.genre.isBlank() || seed.coreIdea.isBlank()) return false
        // Already produced proposals (resolved or not) → quick start did its job.
        if (doc.settingProposals.isNotEmpty()) return false
        val branchId = _state.value.selectedBranchId ?: doc.project.mainBranchID ?: return false
        val branch = doc.branches.firstOrNull { it.id == branchId } ?: return false
        // Domain already has a live run — UI should attach via normal stream, don't double-start.
        if (branch.activeRunID != null) return false
        return true
    }

    private fun maybeKickQuickStart(doc: NovelProjectDocumentV1, branchId: NovelBranchId?) {
        if (quickStartKickoffDone) return
        if (_state.value.generating || _state.value.busy) return
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) return
        if (branchId == null) return
        if (!needsQuickStartGeneration(doc)) return
        startQuickStartSuggestions(branchId = branchId, fromAuto = true)
    }

    private fun batchMutationBlockMessage(): String? = when {
        _state.value.ghostwriteBatch.loading -> "正在检查连续代笔账本，请稍后再试"
        _state.value.ghostwriteBatch.ledgerBlocked ->
            "连续代笔账本异常；请先在创作控制中隔离损坏任务"
        _state.value.ghostwriteBatch.projectHasActiveJob ->
            "连续代笔批次尚未结束，请先取消批次再修改项目"
        else -> null
    }

    private fun rejectIfBatchMutationBlocked(): Boolean {
        val message = batchMutationBlockMessage() ?: return false
        _state.value = _state.value.copy(errorMessage = message)
        return true
    }

    /**
     * iOS-aligned: generate world / characters / outline / writing-requirements proposals
     * from the stored quick-start seed (injected into the prompt, not only user text).
     */
    fun startQuickStartSuggestions(
        branchId: NovelBranchId? = _state.value.selectedBranchId,
        fromAuto: Boolean = false,
    ) {
        val doc = _state.value.document
        if (doc == null) {
            _state.value = _state.value.copy(errorMessage = "项目尚未加载完成，请稍后再试")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        if (_state.value.generating) {
            _state.value = _state.value.copy(errorMessage = "正在生成中，请稍候或先停止")
            return
        }
        val seed = doc.project.quickStartSeed
        if (doc.project.creationMode != NovelProjectCreationMode.QuickStart ||
            seed == null ||
            seed.genre.isBlank() ||
            seed.coreIdea.isBlank()
        ) {
            _state.value = _state.value.copy(errorMessage = "缺少快速开始的题材或核心想法")
            return
        }
        val resolvedBranch = branchId
            ?: doc.project.mainBranchID
            ?: run {
                _state.value = _state.value.copy(errorMessage = "未找到可用分支，无法生成初始设定")
                return
            }
        if (doc.branches.none { it.id == resolvedBranch && it.lifecycle == NovelBranchLifecycle.Active }) {
            _state.value = _state.value.copy(errorMessage = "分支不可用，无法生成初始设定")
            return
        }
        // Match iOS userText; seed genre/idea is injected via NovelInjectionPlanner.
        val userText = buildString {
            appendLine("请生成一组可确认的世界观、人物、总剧情大纲和写作要求建议。")
            appendLine()
            appendLine("题材：${seed.genre.trim()}")
            append("核心想法：${seed.coreIdea.trim()}")
        }
        quickStartKickoffDone = true
        _state.value = _state.value.copy(
            selectedBranchId = resolvedBranch,
            statusMessage = "正在根据快速开始生成角色、世界观与剧情大纲…",
            composerMode = NovelSessionModeRequest.DiscussPlan,
            tab = NovelWorkspaceTab.Chat,
            errorMessage = null,
        )
        send(
            overrideText = userText,
            forceKind = NovelRunKindRequest.QuickStart,
            branchIdOverride = resolvedBranch,
            // Auto-kick must not cancel a not-yet-started run; only interrupt when UI is mid-stream.
            interruptExisting = !fromAuto || _state.value.generating,
        )
    }

    fun selectTab(tab: NovelWorkspaceTab) { _state.value = _state.value.copy(tab = tab) }

    /** Jump to living materials (e.g. after reviewing a setting proposal). */
    fun openLivingMaterials() {
        _state.value = _state.value.copy(tab = NovelWorkspaceTab.Living)
    }
    fun selectBranch(branchId: NovelBranchId) {
        val doc = _state.value.document ?: return
        val switchingToActiveBatch = latestGhostwriteBatchSnapshot?.activeJobs.orEmpty().any {
            it.job.branchID == branchId
        }
        if (branchId != _state.value.selectedBranchId && !switchingToActiveBatch) {
            batchMutationBlockMessage()?.let { message ->
                _state.value = _state.value.copy(errorMessage = message)
                return
            }
        }
        if (branchId != _state.value.selectedBranchId && ghostwriteCoordinator.owns(projectId)) {
            _state.value = _state.value.copy(errorMessage = "代笔正在运行，请先暂停再切换分支")
            return
        }
        if (doc.branches.none { it.id == branchId && it.lifecycle == NovelBranchLifecycle.Active }) {
            _state.value = _state.value.copy(errorMessage = "Branch not found")
            return
        }
        // Switching branch mid-run would show B's history with A's stream — stop first.
        if (branchId != _state.value.selectedBranchId && _state.value.generating) {
            novelCreation.interrupt(
                NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.User),
            )
        }
        // Archive sheet is bound to the distill draft's branch; drop it on switch.
        if (branchId != _state.value.selectedBranchId && _state.value.archiveSheet != null) {
            archiveDistillJob?.cancel()
            archiveDistillJob = null
        }
        uiSession.setSelectedBranch(projectId.rawValue, branchId)
        val clearArchive = branchId != _state.value.selectedBranchId
        val branchProgress = ghostwriteProgressForBranch(
            progress = ghostwriteCoordinator.progress.value,
            projectId = projectId,
            branchId = branchId,
        )
        val batchProjection = latestGhostwriteBatchSnapshot?.let { snapshot ->
            projectGhostwriteBatchForBranch(
                snapshot = snapshot,
                branchId = branchId,
                previous = _state.value.ghostwriteBatch,
            )
        } ?: _state.value.ghostwriteBatch
        _state.value = _state.value.copy(
            selectedBranchId = branchId,
            errorMessage = null,
            ghostwriteProgress = branchProgress,
            ghostwriteBatch = batchProjection,
            archiveSheet = if (clearArchive) null else _state.value.archiveSheet,
            busy = if (clearArchive && _state.value.archiveSheet?.distilling == true) {
                false
            } else {
                _state.value.busy
            },
            busyPhase = if (clearArchive && _state.value.archiveSheet?.distilling == true) {
                null
            } else {
                _state.value.busyPhase
            },
        )
    }
    fun setComposerMode(mode: NovelSessionModeRequest) {
        if (mode == NovelSessionModeRequest.WriteProse) {
            // Smart default: no chapters yet → whole chapter; otherwise continue a segment.
            val chapterCount = currentBranch()?.workingChapterSelections?.size ?: 0
            val g = if (chapterCount > 0) {
                NovelGenerationGranularityRequest.Continuation
            } else {
                NovelGenerationGranularityRequest.WholeChapter
            }
            _state.value = _state.value.copy(
                composerMode = NovelSessionModeRequest.WriteProse,
                granularity = g,
            )
        } else {
            _state.value = _state.value.copy(composerMode = mode)
        }
    }

    fun setGranularity(g: NovelGenerationGranularityRequest) {
        _state.value = _state.value.copy(
            composerMode = NovelSessionModeRequest.WriteProse,
            granularity = g,
        )
    }

    /** Short copy for the collect CTA: where this candidate will land by default. */
    fun collectTargetHint(
        candidate: NovelCandidateRecord? = null,
        isWholeChapter: Boolean = isWholeChapterCollectDefault(candidate),
        isInterrupted: Boolean = false,
    ): String {
        val prefix = if (isInterrupted) "中断稿 · " else ""
        val doc = _state.value.document
        val branch = currentBranch()
        val replaceSource = candidate?.sourceChapterVersionID?.let { sid ->
            doc?.chapterVersions?.firstOrNull { it.id == sid }
        }
        if (replaceSource != null &&
            branch?.workingChapterSelections?.any { it.chapterID == replaceSource.chapterID } == true
        ) {
            val title = replaceSource.title.takeIf { it.isNotBlank() }
            val body = if (title != null) {
                "重写 · 收录后替换「$title」"
            } else {
                "重写本章 · 收录后替换原文"
            }
            return prefix + body
        }
        val chapterCount = branch?.workingChapterSelections?.size ?: 0
        val body = when (NovelParagraphSelection.suggestTarget(chapterCount, isWholeChapter)) {
            NovelParagraphSelection.SuggestedTarget.CreateFirstChapter -> "将创建第 1 章"
            NovelParagraphSelection.SuggestedTarget.CreateNextChapter ->
                "将创建第 ${chapterCount + 1} 章"
            NovelParagraphSelection.SuggestedTarget.AppendCurrentChapter -> "将追加到当前章"
        }
        return prefix + body
    }

    fun isWholeChapterCollectDefault(candidate: NovelCandidateRecord? = null): Boolean {
        // Prefer the run that produced this candidate; fall back to project last + composer.
        if (candidate != null) {
            val doc = _state.value.document
            val run = doc?.activeRuns?.firstOrNull { it.candidateID == candidate.id }
            val g = run?.granularity ?: doc?.project?.lastGenerationGranularity
            if (g != null) return g.name == "WholeChapter"
        }
        val g = _state.value.document?.project?.lastGenerationGranularity
            ?: return _state.value.granularity == NovelGenerationGranularityRequest.WholeChapter
        return g.name == "WholeChapter"
    }
    fun updateDraft(text: String) { _state.value = _state.value.copy(draft = text) }

    fun send(
        overrideText: String? = null,
        forceKind: NovelRunKindRequest? = null,
        branchIdOverride: NovelBranchId? = null,
        interruptExisting: Boolean = true,
    ) {
        val text = (overrideText ?: _state.value.draft).trim()
        if (text.isEmpty()) {
            if (forceKind == NovelRunKindRequest.QuickStart) quickStartKickoffDone = false
            return
        }
        if (_state.value.generating) {
            _state.value = _state.value.copy(errorMessage = "请先停止当前生成，再发送")
            return
        }
        if (ghostwriteCoordinator.owns(projectId)) {
            _state.value = _state.value.copy(errorMessage = "代笔正在后台运行，请先暂停")
            return
        }
        batchMutationBlockMessage()?.let { message ->
            if (forceKind == NovelRunKindRequest.QuickStart) quickStartKickoffDone = false
            _state.value = _state.value.copy(errorMessage = message)
            return
        }
        if (_state.value.busy) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中（例如收录），请稍后再发送")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            if (forceKind == NovelRunKindRequest.QuickStart) quickStartKickoffDone = false
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        val branchId = branchIdOverride ?: _state.value.selectedBranchId
        if (branchId == null) {
            if (forceKind == NovelRunKindRequest.QuickStart) quickStartKickoffDone = false
            _state.value = _state.value.copy(errorMessage = "未选择分支，无法发送")
            return
        }
        val doc = _state.value.document
        val kind = forceKind ?: when {
            // First empty-session send on a QuickStart project still maps to QuickStart.
            doc?.project?.creationMode == NovelProjectCreationMode.QuickStart &&
                doc.settingProposals.isEmpty() &&
                doc.sessions.firstOrNull { it.branchID == branchId }?.messages.isNullOrEmpty() ->
                NovelRunKindRequest.QuickStart
            _state.value.composerMode == NovelSessionModeRequest.DiscussPlan ->
                NovelRunKindRequest.Discussion
            else -> NovelRunKindRequest.Prose
        }
        // QuickStart is a planning run even if UI was on 写正文.
        val mode = if (kind == NovelRunKindRequest.QuickStart) {
            NovelSessionModeRequest.DiscussPlan
        } else {
            _state.value.composerMode
        }
        val granularity = if (kind == NovelRunKindRequest.Prose) _state.value.granularity else null
        val keepStatus = forceKind == NovelRunKindRequest.QuickStart &&
            !_state.value.statusMessage.isNullOrBlank()
        // Clear draft immediately so the composer does not stay stuck with old text.
        _state.value = _state.value.copy(
            draft = "",
            generating = true,
            busy = true,
            errorMessage = null,
            statusMessage = if (keepStatus) {
                _state.value.statusMessage
            } else if (kind == NovelRunKindRequest.QuickStart) {
                "正在根据快速开始生成角色、世界观与剧情大纲…"
            } else {
                null
            },
            streamingText = "",
            selectedBranchId = branchId,
            tab = if (kind == NovelRunKindRequest.QuickStart) {
                NovelWorkspaceTab.Chat
            } else {
                _state.value.tab
            },
        )
        // Only interrupt when replacing an in-flight UI collection — auto kick must not
        // race-cancel a run that has not been registered yet.
        if (interruptExisting) {
            novelCreation.interrupt(
                NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.User),
            )
            generateJob?.cancel()
        }
        val epoch = ++generationEpoch
        generateJob = viewModelScope.launch {
            try {
                val run = novelCreation.start(
                    NovelRunRequest(
                        projectId = projectId,
                        branchId = branchId,
                        userText = text,
                        mode = mode,
                        granularity = granularity,
                        kind = kind,
                    ),
                )
                var full = ""
                collectRunUntilTerminal(run.events) { event ->
                    when (event) {
                        NovelRunEvent.Started -> {
                            // User message is already committed; pull session without full-page loading flash.
                            refreshSuspend(quiet = true)
                        }
                        is NovelRunEvent.Delta -> {
                            full += event.text
                            _state.value = _state.value.copy(streamingText = full)
                        }
                        is NovelRunEvent.Replace -> {
                            full = event.text
                            _state.value = _state.value.copy(streamingText = full)
                        }
                        is NovelRunEvent.Completed -> {
                            val isQuick = kind == NovelRunKindRequest.QuickStart
                            _state.value = _state.value.copy(
                                generating = false,
                                busy = false,
                                statusMessage = if (isQuick) {
                                    "快速开始完成：请确认下方设定建议，或到「设定」查看"
                                } else {
                                    null
                                },
                            )
                            refreshSuspend(quiet = true)
                            quietClearStreaming(epoch)
                        }
                        is NovelRunEvent.Interrupted -> {
                            // Restore unsent draft only when stop cancelled mid-flight and box is empty.
                            // Don't put auto quick-start seed back into the composer.
                            val restore = if (forceKind == NovelRunKindRequest.QuickStart) {
                                _state.value.draft
                            } else if (_state.value.draft.isBlank()) {
                                text
                            } else {
                                _state.value.draft
                            }
                            val hasPartial = event.partial.isNotBlank() &&
                                kind == NovelRunKindRequest.Prose
                            // Keep quickStartKickoffDone=true so refresh does not auto-loop;
                            // user can tap「重新生成初始设定」.
                            _state.value = _state.value.copy(
                                generating = false, busy = false,
                                draft = restore,
                                errorMessage = when {
                                    forceKind == NovelRunKindRequest.QuickStart ->
                                        "已停止生成（可点「重新生成初始设定」再试）"
                                    hasPartial -> null
                                    else -> "已停止生成，输入已恢复"
                                },
                                streamingText = "",
                                statusMessage = if (hasPartial) {
                                    "生成已中断 · 可收录已生成部分"
                                } else {
                                    null
                                },
                            )
                            refreshSuspend(quiet = true)
                        }
                        is NovelRunEvent.Failed -> {
                            val restore = if (forceKind == NovelRunKindRequest.QuickStart) {
                                _state.value.draft
                            } else if (_state.value.draft.isBlank()) {
                                text
                            } else {
                                _state.value.draft
                            }
                            // Do not clear kickoff flag — avoids fail→refresh→auto-kick loops.
                            _state.value = _state.value.copy(
                                generating = false,
                                busy = false,
                                draft = restore,
                                errorMessage = humanizeNovelError(event.code, event.message) +
                                    if (forceKind == NovelRunKindRequest.QuickStart) {
                                        "（可点「重新生成初始设定」再试）"
                                    } else {
                                        ""
                                    },
                                streamingText = "",
                                statusMessage = null,
                            )
                            refreshSuspend(quiet = true)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep kickoffDone for QuickStart so a hard failure does not auto-retry forever.
                val restore = if (forceKind == NovelRunKindRequest.QuickStart) {
                    _state.value.draft
                } else if (_state.value.draft.isBlank()) {
                    text
                } else {
                    _state.value.draft
                }
                _state.value = _state.value.copy(
                    generating = false,
                    busy = false,
                    draft = restore,
                    errorMessage = humanizeNovelError(null, e.message) +
                        if (forceKind == NovelRunKindRequest.QuickStart) {
                            "（可点「重新生成初始设定」再试）"
                        } else {
                            ""
                        },
                    streamingText = "",
                    statusMessage = null,
                )
            } finally {
                finishLegacyGeneration(epoch)
            }
        }
    }

    fun stop() {
        val ghostwriteRunId = _state.value.ghostwriteProgress?.runId
        if (ghostwriteRunId != null && ghostwriteCoordinator.owns(projectId, ghostwriteRunId)) {
            pauseGhostwrite()
            return
        }
        novelCreation.interrupt(
            NovelInterruptRequest(projectId, reason = NovelInterruptReason.User),
        )
    }

    fun resendUserText(text: String) {
        if (_state.value.generating) {
            _state.value = _state.value.copy(errorMessage = "请先停止当前生成，再重新发送")
            return
        }
        send(overrideText = text)
    }

    /**
     * SharedFlow never completes; stop the collector after the first terminal event
     * so generation jobs do not idle forever.
     */
    private suspend fun collectRunUntilTerminal(
        events: Flow<NovelRunEvent>,
        onEvent: suspend (NovelRunEvent) -> Unit,
    ) {
        events
            .transformWhile { event ->
                emit(event)
                event !is NovelRunEvent.Completed &&
                    event !is NovelRunEvent.Interrupted &&
                    event !is NovelRunEvent.Failed
            }
            .collect { onEvent(it) }
    }

    /**
     * Keep the last streaming frame briefly so terminal handoff does not flash empty.
     * Only clears if [epoch] still owns the generation slot.
     */
    private suspend fun quietClearStreaming(epoch: Long, delayMs: Long = STREAM_QUIET_MS) {
        kotlinx.coroutines.delay(delayMs)
        if (epoch == generationEpoch) {
            _state.value = _state.value.copy(streamingText = "")
        }
    }

    private fun ghostwriteBusyNow(): Boolean = ghostwriteStartPending ||
        ghostwriteOwnedBusy ||
        ghostwriteCoordinator.owns(projectId)

    /** A legacy generation may release only the busy state it still owns. */
    private fun finishLegacyGeneration(epoch: Long) {
        val current = _state.value
        val busyAfterFinally = legacyOperationBusyAfterFinally(
            operationTokenMatches = epoch == generationEpoch,
            operationStillActive = current.generating,
            ghostwriteOwnedOrPending = ghostwriteBusyNow(),
        ) ?: return
        _state.value = current.copy(
            generating = false,
            busy = busyAfterFinally,
            busyPhase = if (busyAfterFinally) "后台代笔…" else null,
        )
    }

    companion object {
        private const val STREAM_QUIET_MS = 120L
    }

    /**
     * Open the collect sheet for a prose candidate (Available or Interrupted).
     * Does not commit; user confirms target + paragraph selection in the sheet.
     */
    fun openCollectSheet(candidateId: NovelCandidateId) {
        val doc = _state.value.document
        if (doc == null) {
            _state.value = _state.value.copy(errorMessage = "项目未加载，无法收录")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        if (_state.value.generating || _state.value.busy) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中，请稍后再收录")
            return
        }
        val branch = currentBranch()
        if (branch == null) {
            _state.value = _state.value.copy(errorMessage = "未选择分支，无法收录")
            return
        }
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            _state.value = _state.value.copy(
                errorMessage = "正文已改写，请先在「正文」点「同步状态」，再收录",
            )
            return
        }
        val candidate = collectableProseCandidates().firstOrNull { it.id == candidateId }
        if (candidate == null) {
            _state.value = _state.value.copy(errorMessage = "找不到可收录的候选，请刷新后重试")
            return
        }
        if (candidate.content.isBlank()) {
            _state.value = _state.value.copy(errorMessage = "候选正文为空，无法收录")
            return
        }
        if (candidate.baseHeadRevision != branch.headRevision) {
            _state.value = _state.value.copy(errorMessage = "该候选已过期，请基于最新正文重新生成")
            return
        }
        val chapterCount = branch.workingChapterSelections.size
        val lastSel = branch.workingChapterSelections.lastOrNull()
        val currentTitle = lastSel?.let { sel ->
            doc.chapterVersions.firstOrNull { it.id == sel.versionID }?.title
        }
        val whole = isWholeChapterCollectDefault(candidate)
        val replaceSource = candidate.sourceChapterVersionID?.let { sid ->
            doc.chapterVersions.firstOrNull { it.id == sid }
        }
        val replaceOnBranch = replaceSource != null && branch.workingChapterSelections.any {
            it.chapterID == replaceSource.chapterID
        }
        _state.value = _state.value.copy(
            errorMessage = null,
            collectSheet = NovelCollectSheetState(
                candidateId = candidate.id,
                content = candidate.content,
                isInterrupted = candidate.status == NovelCandidateStatus.Interrupted,
                isWholeChapterDefault = whole,
                chapterCount = chapterCount,
                currentChapterId = lastSel?.chapterID,
                currentChapterTitle = currentTitle,
                replaceChapterId = if (replaceOnBranch) replaceSource?.chapterID else null,
                replaceChapterTitle = if (replaceOnBranch) replaceSource?.title else null,
            ),
        )
    }

    fun dismissCollectSheet() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(collectSheet = null)
    }

    /**
     * Commit collection with user-chosen target + selected text.
     * Default [runStateDelta]=true so living materials grow with the manuscript (P0-A).
     * Manuscript always commits; if state-delta fails silently inside the coordinator,
     * UI surfaces a soft warning instead of pretending settings updated.
     */
    /**
     * @param targetMode 0 = create next chapter, 1 = append current, 2 = replace regenerate target
     */
    fun confirmCollect(
        selectedText: String,
        appendToCurrent: Boolean,
        runStateDelta: Boolean = true,
        replaceTarget: Boolean = false,
    ) {
        val sheet = _state.value.collectSheet
        if (sheet == null) {
            _state.value = _state.value.copy(errorMessage = "收录面板已关闭，请重试")
            return
        }
        val text = selectedText.trim()
        if (text.isBlank()) {
            _state.value = _state.value.copy(errorMessage = "请至少选择一段正文再收录")
            return
        }
        val doc = _state.value.document
        if (doc == null) {
            _state.value = _state.value.copy(errorMessage = "项目未加载，无法收录")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        // Sync busy before launch so double-tap cannot start two collects.
        if (_state.value.generating || _state.value.busy) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中，请稍后再收录")
            return
        }
        val branchId = _state.value.selectedBranchId
        if (branchId == null) {
            _state.value = _state.value.copy(errorMessage = "未选择分支，无法收录")
            return
        }
        val branch = currentBranch()
        if (branch?.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            _state.value = _state.value.copy(
                errorMessage = "正文已改写，请先在「正文」点「同步状态」，再收录",
            )
            return
        }
        val chapterCount = branch?.workingChapterSelections?.size ?: 0
        val canReplace = replaceTarget && sheet.replaceChapterId != null
        val target = when {
            canReplace -> NovelCollectionTarget.ReplaceChapter(sheet.replaceChapterId!!)
            appendToCurrent && sheet.currentChapterId != null && chapterCount > 0 ->
                NovelCollectionTarget.AppendToChapter(sheet.currentChapterId)
            else -> NovelCollectionTarget.CreateNextChapter(
                chapterID = NovelChapterId.generate(),
                title = "第${chapterCount + 1}章",
            )
        }
        val successHint = when {
            canReplace -> {
                val title = sheet.replaceChapterTitle?.takeIf { it.isNotBlank() }
                if (title != null) "已替换「$title」" else "已替换目标章"
            }
            appendToCurrent && sheet.currentChapterId != null && chapterCount > 0 -> {
                val title = sheet.currentChapterTitle?.takeIf { it.isNotBlank() }
                if (title != null) "已追加到「$title」" else "已追加到当前章"
            }
            else -> "已创建第 ${chapterCount + 1} 章"
        }
        val candidateId = sheet.candidateId
        val eventsBefore = doc.events.size
        val proposalsBefore = doc.settingProposals.size
        val summaryBefore = branch?.let { b ->
            doc.stateSnapshots.firstOrNull { it.id == b.currentStateSnapshotID }?.summary
        }
        // Hold the sheet until success so failures do not wipe paragraph selection.
        _state.value = _state.value.copy(
            busy = true,
            busyPhase = if (runStateDelta) "收录并更新剧情…" else "收录中…",
            errorMessage = null,
            statusMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                novelCreation.perform(
                    NovelIntent.CollectCandidate(
                        projectId = projectId,
                        branchId = branchId,
                        candidateId = candidateId,
                        selectedText = text,
                        target = target,
                        runStateDelta = runStateDelta,
                    ),
                )
                refreshSuspend()
                val after = _state.value.document
                val afterBranch = after?.branches?.firstOrNull { it.id == branchId }
                val summaryAfter = afterBranch?.let { b ->
                    after.stateSnapshots.firstOrNull { it.id == b.currentStateSnapshotID }?.summary
                }
                val outlineBefore = branch?.let { b ->
                    doc.stateSnapshots.firstOrNull { it.id == b.currentStateSnapshotID }?.branchOutline
                }
                val outlineAfter = afterBranch?.let { b ->
                    after.stateSnapshots.firstOrNull { it.id == b.currentStateSnapshotID }?.branchOutline
                }
                val needsSync = afterBranch?.syncStatus == NovelBranchSyncStatus.NeedsSync
                val stateGrew = after != null && (
                    after.events.size > eventsBefore ||
                        after.settingProposals.size > proposalsBefore ||
                        (summaryAfter != null && summaryAfter != summaryBefore) ||
                        (outlineAfter != null && outlineAfter != outlineBefore)
                    )
                val status = when {
                    !runStateDelta ->
                        "已收录 · $successHint。可在「正文」查看"
                    needsSync ->
                        "已收录 · $successHint。剧情状态未能自动更新 — 请到「正文」点「同步状态」后继续生成"
                    stateGrew ->
                        "已收录 · $successHint。剧情状态已更新，可在「正文 / 设定」查看"
                    else ->
                        "已收录 · $successHint。可在「正文」查看"
                }
                _state.value = _state.value.copy(
                    statusMessage = status,
                    collectSheet = null,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false, busyPhase = null)
        }
    }

    /** @deprecated Prefer [openCollectSheet]; kept for any residual direct calls. */
    fun collectCandidate(candidateId: NovelCandidateId) {
        openCollectSheet(candidateId)
    }

    fun resolveProposal(proposalId: NovelProposalId, accept: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(
                    NovelIntent.ResolveProposal(projectId, proposalId, accept),
                )
                refreshSuspend(quiet = true)
                _state.value = _state.value.copy(
                    statusMessage = if (accept) {
                        "已确认设定，可在「设定」查看"
                    } else {
                        "已忽略该建议"
                    },
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun forkFromHead(name: String) {
        if (rejectIfBatchMutationBlocked()) return
        val branch = currentBranch() ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                val outcome = novelCreation.perform(
                    NovelIntent.ForkBranch(
                        projectId = projectId,
                        sourceBranchId = branch.id,
                        checkpointId = branch.headCheckpointID,
                        name = name,
                    ),
                )
                if (outcome is app.amber.feature.novel.model.NovelOutcome.BranchForked) {
                    uiSession.setSelectedBranch(projectId.rawValue, outcome.branchID)
                    _state.value = _state.value.copy(selectedBranchId = outcome.branchID)
                }
                refreshSuspend()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun undoHead(onSuccess: (() -> Unit)? = null) {
        val branchId = _state.value.selectedBranchId ?: return
        if (_state.value.busy || _state.value.generating) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中，请稍后再撤销")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        if (currentBranch()?.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            _state.value = _state.value.copy(
                errorMessage = "正文有未同步改写，请先「同步状态」或放弃手改后再撤销收录",
            )
            return
        }
        val undoingArchive = isDiscussionArchiveHead()
        _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
        viewModelScope.launch {
            runCatching {
                novelCreation.perform(NovelIntent.UndoHead(projectId, branchId))
                refreshSuspend()
                _state.value = _state.value.copy(
                    statusMessage = when {
                        undoingArchive -> "已撤销讨论归档 · 讨论可重新进入注入窗口"
                        else -> "已撤销分支头 · 相关候选可重新收录/采用"
                    },
                )
                onSuccess?.invoke()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    /** True when this collected candidate is still the branch head checkpoint. */
    fun isCollectHeadCandidate(candidate: NovelCandidateRecord): Boolean {
        if (candidate.status != NovelCandidateStatus.Collected) return false
        val branch = currentBranch() ?: return false
        // Don't offer undo while unsynced manual edits sit on the same head.
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) return false
        val head = branch.headCheckpointID
        return candidate.collectedCheckpointID == head
    }

    /** True when branch head is a discussion-archive checkpoint (undo rewinds archiveCursor). */
    fun isDiscussionArchiveHead(): Boolean {
        val doc = _state.value.document ?: return false
        val branch = currentBranch() ?: return false
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) return false
        val head = doc.checkpoints.firstOrNull { it.id == branch.headCheckpointID } ?: return false
        return head.kind == app.amber.feature.novel.model.NovelCheckpointKind.DiscussionArchive
    }



    fun chapterVersionsFor(chapterId: NovelChapterId): List<app.amber.feature.novel.model.NovelChapterVersionRecord> {
        val doc = _state.value.document ?: return emptyList()
        return doc.chapterVersions
            .filter { it.chapterID == chapterId }
            .sortedByDescending { it.createdAt }
    }

    fun restoreChapterVersion(versionId: app.amber.feature.novel.model.NovelChapterVersionId) {
        val branchId = _state.value.selectedBranchId ?: return
        if (_state.value.busy || _state.value.generating) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中，请稍后再恢复")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        if (currentBranch()?.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            _state.value = _state.value.copy(
                errorMessage = "正文已改写，请先同步状态再恢复版本",
            )
            return
        }
        _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
        viewModelScope.launch {
            runCatching {
                novelCreation.perform(
                    NovelIntent.RestoreChapterVersion(projectId, branchId, versionId),
                )
                refreshSuspend()
                _state.value = _state.value.copy(statusMessage = "已恢复到所选版本")
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun setModelPolicy(policy: NovelProjectModelPolicy) {
        if (rejectIfBatchMutationBlocked()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.SetModelPolicy(projectId, policy))
                refreshSuspend()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun setCollaborationMode(mode: NovelCollaborationMode) {
        val doc = _state.value.document ?: return
        val branchId = _state.value.selectedBranchId ?: return
        if (doc.project.collaborationMode == mode) return
        if (ghostwriteCoordinator.owns(projectId)) {
            _state.value = _state.value.copy(errorMessage = "代笔正在运行，请先暂停再切换模式")
            return
        }
        if (mode == NovelCollaborationMode.Ghostwrite) {
            val issues = NovelGhostwriteReadiness.issues(
                document = doc,
                branchId = branchId,
                requireChapterPlan = false,
            )
            if (issues.isNotEmpty()) {
                _state.value = _state.value.copy(
                    errorMessage = "无法切入代笔：" + issues.joinToString("；") { it.displayName },
                )
                return
            }
        }
        mutateGhostwriteSetting(
            successMessage = if (mode == NovelCollaborationMode.Ghostwrite) {
                "已切换到代笔模式"
            } else {
                "已切换到共创模式"
            },
        ) {
            novelCreation.perform(NovelIntent.SetCollaborationMode(projectId, branchId, mode))
        }
    }

    fun setPauseGhostwriteOnBlockingContinuity(enabled: Boolean) {
        mutateGhostwriteSetting(successMessage = "已更新连续性暂停设置") {
            novelCreation.perform(
                NovelIntent.SetPauseGhostwriteOnBlockingContinuity(projectId, enabled),
            )
        }
    }

    fun saveChapterPlan(draft: NovelChapterPlanDraft, status: NovelChapterPlanStatus) {
        val doc = _state.value.document ?: return
        val branchId = _state.value.selectedBranchId ?: return
        val mustHappen = novelPlanLines(draft.mustHappen)
        val mustNotHappen = novelPlanLines(draft.mustNotHappen)
        val visibleFacts = novelPlanLines(draft.visibleFacts)
        if (draft.goalAndConflict.isBlank()) {
            _state.value = _state.value.copy(errorMessage = "请填写本章的目标与冲突")
            return
        }
        if (status == NovelChapterPlanStatus.Confirmed && mustHappen.isEmpty()) {
            _state.value = _state.value.copy(errorMessage = "确认计划时至少需要一条「必发生」")
            return
        }
        if (listOf(mustHappen, mustNotHappen, visibleFacts).any { it.size > 32 }) {
            _state.value = _state.value.copy(errorMessage = "计划中每组清单最多 32 条")
            return
        }
        val planId = doc.chapterPlan(branchId)?.id ?: NovelChapterPlanId.generate()
        mutateGhostwriteSetting(
            successMessage = if (status == NovelChapterPlanStatus.Confirmed) {
                "本章计划已确认，可以开始代笔"
            } else {
                "本章计划草稿已保存"
            },
        ) {
            novelCreation.perform(
                NovelIntent.UpsertChapterPlan(
                    projectId = projectId,
                    branchId = branchId,
                    planId = planId,
                    status = status,
                    outlinePlacement = draft.outlinePlacement,
                    goalAndConflict = draft.goalAndConflict,
                    mustHappen = mustHappen,
                    mustNotHappen = mustNotHappen,
                    endingHook = draft.endingHook,
                    visibleFacts = visibleFacts,
                ),
            )
        }
    }

    fun clearChapterPlan() {
        val branchId = _state.value.selectedBranchId ?: return
        mutateGhostwriteSetting(successMessage = "本章计划已清除") {
            novelCreation.perform(NovelIntent.ClearChapterPlan(projectId, branchId))
        }
    }

    fun saveUpcomingArc(beats: List<String>) {
        val branchId = _state.value.selectedBranchId ?: return
        val normalized = beats.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (normalized.isEmpty()) {
            _state.value = _state.value.copy(errorMessage = "请至少填写一条往后几章的方向")
            return
        }
        if (normalized.size > 8 || normalized.any { it.length > 160 }) {
            _state.value = _state.value.copy(errorMessage = "往后几章最多 8 条，每条最多 160 字")
            return
        }
        mutateGhostwriteSetting(successMessage = "往后几章的方向已保存") {
            novelCreation.perform(NovelIntent.UpsertUpcomingArc(projectId, branchId, normalized))
        }
    }

    fun clearUpcomingArc() {
        val branchId = _state.value.selectedBranchId ?: return
        mutateGhostwriteSetting(successMessage = "往后几章的备注已清除") {
            novelCreation.perform(NovelIntent.ClearUpcomingArc(projectId, branchId))
        }
    }

    fun startGhostwrite() {
        val branchId = _state.value.selectedBranchId
        if (branchId == null) {
            _state.value = _state.value.copy(errorMessage = "未选择分支，无法开始代笔")
            return
        }
        batchMutationBlockMessage()?.let { message ->
            _state.value = _state.value.copy(errorMessage = message)
            return
        }
        if (_state.value.busy || _state.value.generating) {
            _state.value = _state.value.copy(errorMessage = "当前有操作正在进行")
            return
        }
        ghostwriteStartPending = true
        _state.value = _state.value.copy(
            busy = true,
            busyPhase = "准备后台代笔…",
            errorMessage = null,
            statusMessage = null,
        )
        viewModelScope.launch {
            runCatching { ghostwriteCoordinator.start(projectId, branchId) }
                .onSuccess { result ->
                    when (result) {
                        NovelGhostwriteStartResult.Started -> {
                            _state.value = _state.value.copy(
                                statusMessage = "代笔已在后台开始，离开页面也会继续",
                            )
                        }
                        is NovelGhostwriteStartResult.Rejected -> {
                            ghostwriteStartPending = false
                            ghostwriteOwnedBusy = false
                            _state.value = _state.value.copy(
                                busy = false,
                                busyPhase = null,
                                errorMessage = humanizeGhostwriteRejection(result),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    ghostwriteStartPending = false
                    ghostwriteOwnedBusy = false
                    _state.value = _state.value.copy(
                        busy = false,
                        busyPhase = null,
                        errorMessage = humanizeNovelError(null, error.message),
                    )
                }
        }
    }

    fun pauseGhostwrite() {
        val runId = _state.value.ghostwriteProgress?.runId
        if (runId == null) {
            _state.value = _state.value.copy(errorMessage = "找不到当前代笔任务，请刷新后重试")
            return
        }
        if (ghostwriteCoordinator.pause(runId)) {
            _state.value = _state.value.copy(statusMessage = "正在暂停代笔…", errorMessage = null)
        } else {
            _state.value = _state.value.copy(errorMessage = "代笔任务已变更，未中断新任务")
        }
    }

    fun startGhostwriteBatch(targetChapterCount: Int) {
        val branchId = _state.value.selectedBranchId
        if (branchId == null) {
            _state.value = _state.value.copy(errorMessage = "未选择分支，无法开始连续代笔")
            return
        }
        if (_state.value.busy || _state.value.generating || ghostwriteCoordinator.owns(projectId)) {
            _state.value = _state.value.copy(errorMessage = "当前有操作正在进行")
            return
        }
        submitGhostwriteBatchCommand("正在创建连续代笔批次…") {
            ghostwriteBatchController.start(projectId, branchId, targetChapterCount)
        }
    }

    fun pauseGhostwriteBatch() {
        val jobId = currentGhostwriteBatchJobId() ?: return
        submitGhostwriteBatchCommand(
            pendingMessage = "正在安全暂停批次…",
            updatedMessage = "连续代笔批次已暂停",
        ) {
            ghostwriteBatchController.pause(jobId)
        }
    }

    fun resumeGhostwriteBatch() {
        val jobId = currentGhostwriteBatchJobId() ?: return
        submitGhostwriteBatchCommand(
            pendingMessage = "正在恢复后台批次…",
            updatedMessage = "已提交恢复后台调度",
        ) {
            ghostwriteBatchController.resume(jobId)
        }
    }

    fun cancelGhostwriteBatch() {
        val jobId = currentGhostwriteBatchJobId() ?: return
        submitGhostwriteBatchCommand(
            pendingMessage = "正在取消批次…",
            updatedMessage = "连续代笔批次已取消",
        ) {
            ghostwriteBatchController.cancel(jobId)
        }
    }

    fun quarantineGhostwriteBatchFailure(token: String) {
        submitGhostwriteBatchCommand("正在隔离损坏账本…") {
            ghostwriteBatchController.quarantineScanFailure(token)
        }
    }

    private fun currentGhostwriteBatchJobId(): NovelGhostwriteJobId? =
        _state.value.ghostwriteBatch.job?.id ?: run {
            _state.value = _state.value.copy(errorMessage = "找不到当前连续代笔批次，请稍后重试")
            null
        }

    private fun submitGhostwriteBatchCommand(
        pendingMessage: String,
        updatedMessage: String? = null,
        command: suspend () -> NovelGhostwriteBatchCommandResult,
    ) {
        val batch = _state.value.ghostwriteBatch
        if (batch.commandPending) return
        _state.value = _state.value.copy(
            ghostwriteBatch = batch.copy(
                commandPending = true,
                notificationRequired = false,
            ),
            errorMessage = null,
            statusMessage = pendingMessage,
        )
        viewModelScope.launch {
            val result = try {
                command()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    ghostwriteBatch = _state.value.ghostwriteBatch.copy(commandPending = false),
                    errorMessage = humanizeNovelError(null, error.message),
                    statusMessage = null,
                )
                return@launch
            }
            applyGhostwriteBatchCommandResult(result, updatedMessage)
        }
    }

    private fun applyGhostwriteBatchCommandResult(
        result: NovelGhostwriteBatchCommandResult,
        updatedMessage: String?,
    ) {
        when (result) {
            is NovelGhostwriteBatchCommandResult.Started -> {
                publishGhostwriteBatchJob(result.job)
                _state.value = _state.value.copy(
                    statusMessage = "连续代笔批次已提交后台，可离开页面",
                    errorMessage = null,
                )
            }
            is NovelGhostwriteBatchCommandResult.Attached -> {
                publishGhostwriteBatchJob(result.job)
                _state.value = _state.value.copy(
                    statusMessage = "已连接到现有连续代笔批次",
                    errorMessage = null,
                )
            }
            is NovelGhostwriteBatchCommandResult.Updated -> {
                publishGhostwriteBatchJob(result.job)
                _state.value = _state.value.copy(
                    statusMessage = updatedMessage ?: when (result.job.status) {
                        NovelGhostwriteJobStatus.Paused -> "连续代笔批次已暂停"
                        NovelGhostwriteJobStatus.Cancelled -> "连续代笔批次已取消"
                        else -> "连续代笔批次已恢复后台调度"
                    },
                    errorMessage = result.warning,
                )
            }
            is NovelGhostwriteBatchCommandResult.Quarantined -> {
                _state.value = _state.value.copy(
                    ghostwriteBatch = _state.value.ghostwriteBatch.copy(commandPending = false),
                    statusMessage = "损坏账本已隔离保留，可重新检查并启动",
                    errorMessage = null,
                )
            }
            is NovelGhostwriteBatchCommandResult.Rejected -> {
                _state.value = _state.value.copy(
                    ghostwriteBatch = _state.value.ghostwriteBatch.copy(
                        commandPending = false,
                        notificationRequired = result.reason ==
                            NovelGhostwriteBatchCommandFailure.NotificationRequired,
                    ),
                    statusMessage = null,
                    errorMessage = humanizeGhostwriteBatchRejection(result),
                )
            }
        }
    }

    private fun publishGhostwriteBatchJob(job: NovelGhostwriteJobV1) {
        val hasOtherActive = latestGhostwriteBatchSnapshot?.activeJobs.orEmpty().any {
            it.job.id != job.id
        }
        _state.value = _state.value.copy(
            ghostwriteBatch = _state.value.ghostwriteBatch.copy(
                loading = false,
                job = job,
                access = NovelGhostwriteJobLoadAccess.ReadWrite,
                projectHasActiveJob = !job.isTerminal || hasOtherActive,
                commandPending = false,
                notificationRequired = false,
            ),
        )
    }

    private fun mutateGhostwriteSetting(
        successMessage: String,
        block: suspend () -> Unit,
    ) {
        batchMutationBlockMessage()?.let { message ->
            _state.value = _state.value.copy(errorMessage = message)
            return
        }
        if (_state.value.busy || _state.value.generating || ghostwriteCoordinator.owns(projectId)) {
            _state.value = _state.value.copy(errorMessage = "当前有生成或收录操作正在进行")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    refreshSuspend(quiet = true)
                    _state.value = _state.value.copy(statusMessage = successMessage)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        errorMessage = humanizeNovelError(null, error.message),
                    )
                }
            _state.value = _state.value.copy(busy = false, busyPhase = null)
        }
    }

    fun restorePrevious() {
        if (rejectIfBatchMutationBlocked()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.RestorePrevious(projectId))
                refreshSuspend()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    /**
     * @return true when save committed; false when gated or failed (caller should keep editor open).
     */
    fun saveManualEdit(
        chapterId: NovelChapterId,
        title: String,
        content: String,
        onSuccess: (() -> Unit)? = null,
    ) {
        val branchId = _state.value.selectedBranchId ?: return
        if (_state.value.busy || _state.value.generating) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中，请稍后再保存")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
        viewModelScope.launch {
            runCatching {
                novelCreation.perform(
                    NovelIntent.SaveManualEdit(projectId, branchId, chapterId, title, content),
                )
                refreshSuspend()
                _state.value = _state.value.copy(statusMessage = "已保存手改 · 请同步状态")
                onSuccess?.invoke()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun syncManualEdits() {
        val branchId = _state.value.selectedBranchId ?: return
        if (_state.value.busy || _state.value.generating) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中，请稍后再同步")
            return
        }
        val chapterCount = currentBranch()?.workingChapterSelections?.size ?: 0
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                busyPhase = if (chapterCount > 1) {
                    "同步状态（分 $chapterCount 章重建）…"
                } else {
                    "同步状态…"
                },
                errorMessage = null,
                statusMessage = null,
            )
            runCatching {
                novelCreation.perform(NovelIntent.SyncManualEdits(projectId, branchId))
                refreshSuspend()
                val branch = currentBranch()
                val snap = _state.value.document?.stateSnapshots
                    ?.firstOrNull { it.id == branch?.currentStateSnapshotID }
                val incomplete = snap?.summary?.contains("model rebuild incomplete") == true
                _state.value = _state.value.copy(
                    statusMessage = when {
                        incomplete -> "已同步正文，但剧情提炼未成功 · 可重试同步"
                        chapterCount > 1 -> "已同步状态 · 分 $chapterCount 章重建剧情"
                        else -> "已同步状态"
                    },
                    errorMessage = if (incomplete) {
                        "模型未返回有效状态增量，Living 摘要可能不完整"
                    } else {
                        null
                    },
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false, busyPhase = null)
        }
    }

    fun cancelBatchPolish() {
        batchPolishEpoch += 1
        batchPolishJob?.cancel()
        batchPolishJob = null
        val keepGhostwriteBusy = ghostwriteBusyNow()
        _state.value = _state.value.copy(
            batchPolishRunning = false,
            busy = keepGhostwriteBusy,
            generating = false,
            busyPhase = if (keepGhostwriteBusy) "后台代笔…" else null,
            statusMessage = "已取消批量润色",
            streamingText = "",
        )
    }

    fun clearBatchPolishResults() {
        _state.value = _state.value.copy(batchPolishResults = emptyList())
    }

    fun clearContinuityAudit() {
        _state.value = _state.value.copy(continuityIssues = null, continuityConsistent = null)
    }

    /**
     * Serial batch polish: for each chapter version, polish → adopt safe; drift → skip.
     */
    fun startBatchPolish(versionIds: List<app.amber.feature.novel.model.NovelChapterVersionId>) {
        if (versionIds.isEmpty()) {
            _state.value = _state.value.copy(errorMessage = "请至少选择一章")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        if (_state.value.generating || _state.value.busy || _state.value.batchPolishRunning) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中")
            return
        }
        if (currentBranch()?.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            _state.value = _state.value.copy(errorMessage = "正文已改写，请先同步状态再批量润色")
            return
        }
        val branchId = _state.value.selectedBranchId ?: return
        novelCreation.interrupt(
            NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.User),
        )
        _state.value = _state.value.copy(
            batchPolishRunning = true,
            busy = true,
            generating = true,
            batchPolishResults = emptyList(),
            tab = NovelWorkspaceTab.Chat,
            errorMessage = null,
            statusMessage = "批量润色 0/${versionIds.size}…",
            busyPhase = "批量润色",
            streamingText = "",
        )
        val epoch = ++batchPolishEpoch
        batchPolishJob?.cancel()
        batchPolishJob = viewModelScope.launch {
            val results = mutableListOf<NovelBatchPolishResult>()
            try {
                versionIds.forEachIndexed { index, versionId ->
                    val doc = _state.value.document
                    val version = doc?.chapterVersions?.firstOrNull { it.id == versionId }
                    val title = version?.title?.ifBlank { "第${index + 1}章" } ?: "第${index + 1}章"
                    if (version == null) {
                        results += NovelBatchPolishResult(
                            chapterId = NovelChapterId.generate(),
                            title = title,
                            outcome = "跳过：版本不存在",
                        )
                        return@forEachIndexed
                    }
                    _state.value = _state.value.copy(
                        statusMessage = "批量润色 ${index + 1}/${versionIds.size} · $title",
                        streamingText = "",
                    )
                    val run = novelCreation.start(
                        NovelRunRequest(
                            projectId = projectId,
                            branchId = branchId,
                            userText = "Polish this chapter:\n\n${version.content}",
                            mode = NovelSessionModeRequest.WriteProse,
                            kind = NovelRunKindRequest.Polish,
                            sourceChapterVersionId = versionId,
                        ),
                    )
                    var full = ""
                    var failed: String? = null
                    collectRunUntilTerminal(run.events) { event ->
                        when (event) {
                            is NovelRunEvent.Delta -> {
                                full += event.text
                                _state.value = _state.value.copy(streamingText = full)
                            }
                            is NovelRunEvent.Replace -> {
                                full = event.text
                                _state.value = _state.value.copy(streamingText = full)
                            }
                            is NovelRunEvent.Failed -> failed = event.message
                            is NovelRunEvent.Interrupted -> failed = "已中断"
                            else -> Unit
                        }
                    }
                    refreshSuspend()
                    if (failed != null) {
                        results += NovelBatchPolishResult(version.chapterID, title, "失败：$failed")
                        return@forEachIndexed
                    }
                    val candidate = _state.value.document?.candidates
                        ?.filter {
                            it.kind == NovelCandidateKind.Polish &&
                                it.status == NovelCandidateStatus.Available &&
                                it.sourceChapterVersionID == versionId
                        }
                        ?.maxByOrNull { it.createdAt }
                    if (candidate == null) {
                        results += NovelBatchPolishResult(version.chapterID, title, "失败：无润色候选")
                        return@forEachIndexed
                    }
                    val beforeSync = currentBranch()?.syncStatus
                    runCatching {
                        novelCreation.perform(
                            NovelIntent.AdoptPolishCandidate(
                                projectId,
                                branchId,
                                candidate.id,
                                asRewrite = false,
                            ),
                        )
                        refreshSuspend()
                    }.onFailure { e ->
                        results += NovelBatchPolishResult(
                            version.chapterID,
                            title,
                            "失败：${e.message ?: "adopt"}",
                        )
                        return@forEachIndexed
                    }
                    val afterSync = currentBranch()?.syncStatus
                    val label = if (
                        beforeSync != NovelBranchSyncStatus.NeedsSync &&
                        afterSync == NovelBranchSyncStatus.NeedsSync
                    ) {
                        "跳过：事实漂移"
                    } else {
                        "已采用"
                    }
                    results += NovelBatchPolishResult(version.chapterID, title, label)
                    _state.value = _state.value.copy(batchPolishResults = results.toList())
                }
                val adopted = results.count { it.outcome == "已采用" }
                val skipped = results.count { it.outcome.startsWith("跳过") }
                val failedN = results.size - adopted - skipped
                _state.value = _state.value.copy(
                    statusMessage = "批量润色完成 · 采用 $adopted · 跳过 $skipped · 失败 $failedN",
                    batchPolishResults = results.toList(),
                    streamingText = "",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (epoch == batchPolishEpoch) {
                    _state.value = _state.value.copy(statusMessage = "已取消批量润色")
                }
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = humanizeNovelError(null, e.message),
                )
            } finally {
                val current = _state.value
                val busyAfterFinally = legacyOperationBusyAfterFinally(
                    operationTokenMatches = epoch == batchPolishEpoch,
                    operationStillActive = current.batchPolishRunning,
                    ghostwriteOwnedOrPending = ghostwriteBusyNow(),
                )
                if (busyAfterFinally != null) {
                    _state.value = current.copy(
                        batchPolishRunning = false,
                        busy = busyAfterFinally,
                        generating = false,
                        busyPhase = if (busyAfterFinally) "后台代笔…" else null,
                        streamingText = "",
                    )
                }
            }
        }
    }

    fun runContinuityAudit() {
        val branchId = _state.value.selectedBranchId ?: return
        if (_state.value.busy || _state.value.generating) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中")
            return
        }
        if (currentBranch()?.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            _state.value = _state.value.copy(errorMessage = "请先同步状态再检查一致性")
            return
        }
        _state.value = _state.value.copy(
            busy = true,
            busyPhase = "一致性审计…",
            errorMessage = null,
            statusMessage = "正在检查剧情一致性…",
            continuityIssues = null,
            continuityConsistent = null,
        )
        continuityJob?.cancel()
        continuityJob = viewModelScope.launch {
            runCatching {
                val audit = novelCreation.continuityAudit(projectId, branchId)
                _state.value = _state.value.copy(
                    continuityConsistent = audit.consistent,
                    continuityIssues = audit.issues.map { issue ->
                        NovelContinuityUiIssue(
                            id = issue.id,
                            severity = issue.severity.name,
                            category = issue.category.name,
                            summary = issue.summary,
                            references = issue.references.map {
                                "第${it.chapterOrdinal}章 ${it.chapterTitle}：${it.evidence}"
                            },
                        )
                    },
                    statusMessage = if (audit.consistent) {
                        "一致性审计：未发现矛盾"
                    } else {
                        "一致性审计：发现 ${audit.issues.size} 个问题"
                    },
                    tab = NovelWorkspaceTab.Manuscript,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false, busyPhase = null)
        }
    }

    fun setChapterDiscarded(chapterId: NovelChapterId, discarded: Boolean) {
        val branchId = _state.value.selectedBranchId ?: return
        if (_state.value.busy || _state.value.generating) return
        _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
        viewModelScope.launch {
            runCatching {
                novelCreation.perform(
                    NovelIntent.SetChapterDiscarded(projectId, branchId, chapterId, discarded),
                )
                refreshSuspend()
                _state.value = _state.value.copy(
                    statusMessage = if (discarded) "已废弃章节（需同步状态）" else "已恢复章节（需同步状态）",
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun polishChapter(versionId: app.amber.feature.novel.model.NovelChapterVersionId) {
        val branchId = _state.value.selectedBranchId ?: return
        val doc = _state.value.document ?: return
        val version = doc.chapterVersions.firstOrNull { it.id == versionId } ?: return
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        // Avoid ProjectBusy race: stop current run first; user taps polish again when idle.
        if (_state.value.generating) {
            novelCreation.interrupt(
                NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.User),
            )
            _state.value = _state.value.copy(
                tab = NovelWorkspaceTab.Chat,
                errorMessage = "已停止当前生成，请再点一次润色",
            )
            return
        }
        // Polish stream + adopt actions live on the Chat tab; switch so the user can see them.
        _state.value = _state.value.copy(
            tab = NovelWorkspaceTab.Chat,
            generating = true,
            busy = true,
            statusMessage = "正在润色「${version.title.ifBlank { "本章" }}」…",
            errorMessage = null,
            streamingText = "",
        )
        novelCreation.interrupt(
            NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.User),
        )
        val epoch = ++generationEpoch
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            try {
                val run = novelCreation.start(
                    NovelRunRequest(
                        projectId = projectId,
                        branchId = branchId,
                        userText = "Polish this chapter:\n\n${version.content}",
                        mode = NovelSessionModeRequest.WriteProse,
                        kind = NovelRunKindRequest.Polish,
                        sourceChapterVersionId = versionId,
                    ),
                )
                var full = ""
                collectRunUntilTerminal(run.events) { event ->
                    when (event) {
                        NovelRunEvent.Started -> refreshSuspend(quiet = true)
                        is NovelRunEvent.Delta -> {
                            full += event.text
                            _state.value = _state.value.copy(streamingText = full)
                        }
                        is NovelRunEvent.Replace -> {
                            full = event.text
                            _state.value = _state.value.copy(streamingText = full)
                        }
                        is NovelRunEvent.Completed -> {
                            refreshSuspend(quiet = true)
                            _state.value = _state.value.copy(generating = false, busy = false)
                            quietClearStreaming(epoch)
                        }
                        is NovelRunEvent.Interrupted -> {
                            _state.value = _state.value.copy(
                                generating = false, busy = false,
                                errorMessage = "已停止生成", streamingText = "",
                            )
                            refreshSuspend()
                        }
                        is NovelRunEvent.Failed -> {
                            _state.value = _state.value.copy(
                                generating = false, busy = false,
                                errorMessage = humanizeNovelError(event.code, event.message),
                                streamingText = "",
                            )
                            refreshSuspend()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    generating = false, busy = false,
                    errorMessage = humanizeNovelError(null, e.message),
                    streamingText = "",
                )
            } finally {
                finishLegacyGeneration(epoch)
            }
        }
    }

    /**
     * Rewrite a chapter (may change story facts). Produces a prose candidate whose collect
     * default is [NovelCollectionTarget.ReplaceChapter].
     */
    fun regenerateChapter(versionId: app.amber.feature.novel.model.NovelChapterVersionId) {
        val branchId = _state.value.selectedBranchId ?: return
        val doc = _state.value.document ?: return
        val version = doc.chapterVersions.firstOrNull { it.id == versionId } ?: return
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        if (currentBranch()?.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            _state.value = _state.value.copy(
                errorMessage = "正文已改写，请先在「正文」点「同步状态」，再重写",
            )
            return
        }
        if (_state.value.generating) {
            novelCreation.interrupt(
                NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.User),
            )
            _state.value = _state.value.copy(
                tab = NovelWorkspaceTab.Chat,
                errorMessage = "已停止当前生成，请再点一次「重写本章」",
            )
            return
        }
        _state.value = _state.value.copy(
            tab = NovelWorkspaceTab.Chat,
            generating = true,
            busy = true,
            statusMessage = "正在重写「${version.title.ifBlank { "本章" }}」…",
            errorMessage = null,
            streamingText = "",
        )
        novelCreation.interrupt(
            NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.User),
        )
        val epoch = ++generationEpoch
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            try {
                val run = novelCreation.start(
                    NovelRunRequest(
                        projectId = projectId,
                        branchId = branchId,
                        userText = "",
                        mode = NovelSessionModeRequest.WriteProse,
                        kind = NovelRunKindRequest.Regenerate,
                        sourceChapterVersionId = versionId,
                    ),
                )
                var full = ""
                collectRunUntilTerminal(run.events) { event ->
                    when (event) {
                        NovelRunEvent.Started -> refreshSuspend(quiet = true)
                        is NovelRunEvent.Delta -> {
                            full += event.text
                            _state.value = _state.value.copy(streamingText = full)
                        }
                        is NovelRunEvent.Replace -> {
                            full = event.text
                            _state.value = _state.value.copy(streamingText = full)
                        }
                        is NovelRunEvent.Completed -> {
                            _state.value = _state.value.copy(
                                generating = false,
                                busy = false,
                                statusMessage = "重写完成 · 收录后将替换原文",
                            )
                            refreshSuspend(quiet = true)
                            quietClearStreaming(epoch)
                        }
                        is NovelRunEvent.Interrupted -> {
                            _state.value = _state.value.copy(
                                generating = false,
                                busy = false,
                                streamingText = "",
                                statusMessage = if (full.isNotBlank()) {
                                    "重写已中断 · 可收录已生成部分"
                                } else {
                                    null
                                },
                                errorMessage = if (full.isBlank()) "已停止生成" else null,
                            )
                            refreshSuspend()
                        }
                        is NovelRunEvent.Failed -> {
                            _state.value = _state.value.copy(
                                generating = false,
                                busy = false,
                                errorMessage = humanizeNovelError(event.code, event.message),
                                streamingText = "",
                            )
                            refreshSuspend()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    generating = false,
                    busy = false,
                    errorMessage = humanizeNovelError(null, e.message),
                    streamingText = "",
                )
            } finally {
                finishLegacyGeneration(epoch)
            }
        }
    }

    fun adoptPolish(candidateId: NovelCandidateId, asRewrite: Boolean) {
        val branchId = _state.value.selectedBranchId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(
                    NovelIntent.AdoptPolishCandidate(projectId, branchId, candidateId, asRewrite),
                )
                refreshSuspend()
                // Drift fail-closed may save as rewrite + NeedsSync even when user tapped 采用润色.
                if (!asRewrite &&
                    currentBranch()?.syncStatus == NovelBranchSyncStatus.NeedsSync
                ) {
                    _state.value = _state.value.copy(
                        errorMessage = "润色与正文事实不一致，已按改写保存；请到「正文」点「同步状态」后再生成",
                    )
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun setPolishPreference(text: String) {
        if (rejectIfBatchMutationBlocked()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.SetPolishPreference(projectId, text))
                refreshSuspend()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun renameBranch(branchId: NovelBranchId, name: String) {
        if (rejectIfBatchMutationBlocked()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.RenameBranch(projectId, branchId, name))
                refreshSuspend()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun setMainBranch(branchId: NovelBranchId) {
        if (rejectIfBatchMutationBlocked()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.SetMainBranch(projectId, branchId))
                refreshSuspend()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun reviseMaterial(
        kind: app.amber.feature.novel.model.NovelMaterialKind,
        title: String,
        content: String,
        materialId: app.amber.feature.novel.model.NovelMaterialId? = null,
        onSuccess: (() -> Unit)? = null,
    ) {
        if (rejectIfBatchMutationBlocked()) return
        if (_state.value.busy) return
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        val creating = materialId == null
        // Set busy before launch so a double-tap cannot enqueue two mutations.
        _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
        viewModelScope.launch {
            runCatching {
                novelCreation.perform(
                    NovelIntent.ReviseMaterial(projectId, materialId, kind, title, content),
                )
                refreshSuspend()
                _state.value = _state.value.copy(
                    statusMessage = if (creating) "已新建资料" else "已保存资料",
                )
                onSuccess?.invoke()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun deleteMaterial(
        materialId: app.amber.feature.novel.model.NovelMaterialId,
        onSuccess: (() -> Unit)? = null,
    ) {
        if (rejectIfBatchMutationBlocked()) return
        if (_state.value.busy) return
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
        viewModelScope.launch {
            runCatching {
                novelCreation.perform(NovelIntent.DeleteMaterial(projectId, materialId))
                refreshSuspend()
                _state.value = _state.value.copy(statusMessage = "已删除资料")
                onSuccess?.invoke()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun exportMarkdown(onResult: (String, String) -> Unit) {
        val branchId = _state.value.selectedBranchId ?: return
        viewModelScope.launch {
            runCatching {
                when (val snap = novelCreation.snapshot(NovelQuery.BranchMarkdown(projectId, branchId))) {
                    is NovelSnapshot.Markdown -> onResult(snap.fileName, snap.content)
                    else -> Unit
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
        }
    }

    fun currentBranch(): NovelBranchRecord? {
        val doc = _state.value.document ?: return null
        val id = _state.value.selectedBranchId ?: return null
        return doc.branches.firstOrNull { it.id == id }
    }

    /**
     * Latest generation injection receipt for the selected branch (excludes fact/state receipts).
     * Used by the "本次上下文" read-only panel.
     */
    fun latestInjectionReceipt(): app.amber.feature.novel.model.NovelInjectionReceiptRecord? {
        val doc = _state.value.document ?: return null
        val branchId = _state.value.selectedBranchId ?: return null
        return doc.injectionReceipts
            .asSequence()
            .filter { it.branchID == branchId && it.factTransaction == null }
            .maxByOrNull { it.createdAt }
    }

    fun discussionArchivableMessages(): List<app.amber.feature.novel.model.NovelSessionMessageRecord> {
        val doc = _state.value.document ?: return emptyList()
        val branch = currentBranch() ?: return emptyList()
        val session = doc.sessions.firstOrNull { it.id == branch.sessionID } ?: return emptyList()
        val previous = when (val c = session.archiveCursor) {
            is app.amber.feature.novel.model.NovelSessionCursor.Through -> c.sequence
            else -> -1L
        }
        return session.messages.filter {
            it.sequence > previous &&
                it.mode == app.amber.feature.novel.model.NovelSessionMode.DiscussPlan &&
                (
                    it.kind == app.amber.feature.novel.model.NovelSessionMessageKind.UserInput ||
                        it.kind == app.amber.feature.novel.model.NovelSessionMessageKind.Discussion
                    )
        }
    }

    /** Open archive sheet and start LLM distill (non-persistent). */
    fun openArchiveSheet() {
        if (_state.value.archiveSheet != null) return
        val messages = discussionArchivableMessages()
        if (messages.isEmpty()) {
            _state.value = _state.value.copy(errorMessage = "当前没有可归档的新讨论")
            return
        }
        if (_state.value.busy || _state.value.generating) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中，请稍后再归档")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        if (currentBranch()?.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            _state.value = _state.value.copy(
                errorMessage = "正文已改写，请先同步状态再归档讨论",
            )
            return
        }
        _state.value = _state.value.copy(
            archiveSheet = NovelArchiveSheetUi(distilling = true),
            busy = true,
            busyPhase = "提炼讨论…",
            errorMessage = null,
        )
        distillArchiveDraft()
    }

    fun dismissArchiveSheet() {
        archiveDistillJob?.cancel()
        archiveDistillJob = null
        val wasDistilling = _state.value.archiveSheet?.distilling == true
        _state.value = _state.value.copy(
            archiveSheet = null,
            busy = if (wasDistilling) false else _state.value.busy,
            busyPhase = if (wasDistilling) null else _state.value.busyPhase,
        )
    }

    fun updateArchiveSummary(summary: String) {
        val sheet = _state.value.archiveSheet ?: return
        if (sheet.distilling) return
        _state.value = _state.value.copy(
            archiveSheet = sheet.copy(summary = summary.take(300)),
        )
    }

    fun updateArchiveDecisions(decisions: List<Pair<String, String>>) {
        val sheet = _state.value.archiveSheet ?: return
        if (sheet.distilling) return
        _state.value = _state.value.copy(archiveSheet = sheet.copy(decisions = decisions))
    }

    fun retryArchiveDistill() {
        val sheet = _state.value.archiveSheet ?: return
        if (sheet.distilling || _state.value.generating) return
        // Block while confirm-save is in flight (busy without distilling).
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            archiveSheet = sheet.copy(distilling = true, distillError = null),
            busy = true,
            busyPhase = "提炼讨论…",
        )
        distillArchiveDraft()
    }

    private fun distillArchiveDraft() {
        val branchId = _state.value.selectedBranchId ?: return
        archiveDistillJob?.cancel()
        archiveDistillJob = viewModelScope.launch {
            try {
                val draft = novelCreation.distillDiscussionArchive(
                    projectId = projectId,
                    branchId = branchId,
                )
                val pairs = draft.decisions.map { it.topic to it.decision }
                    .ifEmpty { listOf("" to "") }
                _state.value = _state.value.copy(
                    archiveSheet = NovelArchiveSheetUi(
                        distilling = false,
                        draft = draft,
                        summary = draft.summary.take(300),
                        decisions = pairs,
                    ),
                    statusMessage = "已提炼 ${draft.decisions.size} 条决定，请确认后归档",
                    busy = false,
                    busyPhase = null,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Sheet dismissed mid-distill.
                throw e
            } catch (e: Exception) {
                val prev = _state.value.archiveSheet
                _state.value = _state.value.copy(
                    archiveSheet = NovelArchiveSheetUi(
                        distilling = false,
                        distillError = humanizeNovelError(null, e.message),
                        draft = prev?.draft,
                        summary = prev?.summary.orEmpty(),
                        decisions = prev?.decisions?.ifEmpty { listOf("" to "") }
                            ?: listOf("" to ""),
                    ),
                    busy = false,
                    busyPhase = null,
                )
            }
        }
    }

    fun archiveDiscussion(
        summary: String,
        decisions: List<Pair<String, String>>,
        onSuccess: (() -> Unit)? = null,
    ) {
        val sheet = _state.value.archiveSheet
        val draft = sheet?.draft
        // Prefer draft identity over current UI selection (branch switch safety).
        val branchId = draft?.branchId ?: _state.value.selectedBranchId ?: return
        if (draft != null) {
            if (draft.projectId != projectId) {
                _state.value = _state.value.copy(errorMessage = "归档草稿与当前项目不匹配，请重新打开")
                return
            }
            if (draft.branchId != _state.value.selectedBranchId) {
                _state.value = _state.value.copy(
                    errorMessage = "分支已切换，请重新归档讨论",
                    archiveSheet = null,
                )
                return
            }
        }
        val messages = discussionArchivableMessages()
        if (messages.isEmpty() && draft == null) {
            _state.value = _state.value.copy(errorMessage = "当前没有可归档的新讨论")
            return
        }
        if (summary.isBlank() || decisions.none { it.first.isNotBlank() && it.second.isNotBlank() }) {
            _state.value = _state.value.copy(
                archiveSheet = sheet?.copy(distillError = "请填写摘要，并至少确认一条决定"),
                errorMessage = "请填写摘要，并至少确认一条决定",
            )
            return
        }
        if (_state.value.busy || _state.value.generating || sheet?.distilling == true) {
            _state.value = _state.value.copy(errorMessage = "当前有操作进行中，请稍后再归档")
            return
        }
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "项目处于只读恢复状态，请先恢复为可写")
            return
        }
        if (currentBranch()?.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            _state.value = _state.value.copy(
                errorMessage = "正文已改写，请先同步状态再归档讨论",
            )
            return
        }
        // Prefer distill draft cursor so confirm matches the distilled window.
        val through = draft?.throughSequence
            ?: messages.maxOfOrNull { it.sequence }
            ?: return
        val relatedByTopic = draft?.decisions
            ?.associate { it.topic.trim() to it.relatedMaterialId }
            .orEmpty()
        val cleaned = decisions
            .map { it.first.trim() to it.second.trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .map { (topic, decision) ->
                NovelArchiveDecisionInput(
                    topic = topic,
                    decision = decision,
                    // Preserve related link when topic still matches distilled row.
                    relatedMaterialId = relatedByTopic[topic],
                )
            }
        _state.value = _state.value.copy(
            busy = true,
            errorMessage = null,
            statusMessage = null,
            archiveSheet = sheet?.copy(distillError = null),
        )
        viewModelScope.launch {
            runCatching {
                novelCreation.perform(
                    NovelIntent.ArchiveDiscussion(
                        projectId = projectId,
                        branchId = branchId,
                        summary = summary.trim(),
                        decisions = cleaned,
                        throughSequence = through,
                        chapterId = draft?.chapterId,
                    ),
                )
                refreshSuspend()
                _state.value = _state.value.copy(
                    statusMessage = "已归档讨论 · ${cleaned.size} 条决定写入资料",
                    archiveSheet = null,
                )
                onSuccess?.invoke()
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    errorMessage = humanizeNovelError(null, e.message),
                    archiveSheet = _state.value.archiveSheet?.copy(
                        distillError = humanizeNovelError(null, e.message),
                    ),
                )
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun currentSessionMessages(): List<NovelSessionMessageRecord> {
        val doc = _state.value.document ?: return emptyList()
        val branch = currentBranch() ?: return emptyList()
        return doc.sessions.firstOrNull { it.id == branch.sessionID }?.messages.orEmpty()
    }

    /** Polish adopt CTA — only fully Available polish candidates. */
    fun availableCandidates() =
        _state.value.document?.candidates
            ?.filter {
                it.branchID == _state.value.selectedBranchId &&
                    it.status == NovelCandidateStatus.Available
            }
            .orEmpty()

    /** Prose collect CTA — Available/Interrupted and still aligned with branch head. */
    fun collectableProseCandidates(): List<NovelCandidateRecord> {
        val branch = currentBranch() ?: return emptyList()
        // needsSync blocks formal collection; hide CTA rather than show a fake entry.
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) return emptyList()
        return _state.value.document?.candidates
            ?.filter {
                it.branchID == branch.id &&
                    it.kind == NovelCandidateKind.Prose &&
                    (it.status == NovelCandidateStatus.Available ||
                        it.status == NovelCandidateStatus.Interrupted) &&
                    it.baseHeadRevision == branch.headRevision
            }
            .orEmpty()
    }

    /**
     * Resolve the candidate shown under a session message.
     * Stale / needsSync prose still returns the record so the bubble can explain why
     * collect is unavailable, but [collectableProseCandidates] drives the enabled CTA.
     */
    fun proseCandidateForMessage(candidateId: NovelCandidateId?): NovelCandidateRecord? {
        candidateId ?: return null
        val branchId = _state.value.selectedBranchId
        val all = _state.value.document?.candidates.orEmpty()
        val prose = all.firstOrNull {
            it.id == candidateId &&
                it.branchID == branchId &&
                it.kind == NovelCandidateKind.Prose &&
                (it.status == NovelCandidateStatus.Available ||
                    it.status == NovelCandidateStatus.Interrupted)
        }
        if (prose != null) return prose
        return availableCandidates().firstOrNull {
            it.id == candidateId && it.kind == NovelCandidateKind.Polish
        }
    }

    /** Why collect is blocked for this prose candidate, or null if openable. */
    fun collectBlockReason(candidate: NovelCandidateRecord): String? {
        if (candidate.kind != NovelCandidateKind.Prose) return null
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            return "只读恢复状态，请先恢复为可写"
        }
        if (_state.value.generating || _state.value.busy) {
            return "当前有操作进行中"
        }
        val branch = currentBranch() ?: return "未选择分支"
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            return "正文已改写，请先同步状态"
        }
        if (candidate.baseHeadRevision != branch.headRevision) {
            return "候选已过期，请重新生成"
        }
        if (candidate.content.isBlank()) return "候选正文为空"
        return null
    }

    fun resendFromCandidate(candidateId: NovelCandidateId) {
        val doc = _state.value.document ?: return
        val candidate = doc.candidates.firstOrNull { it.id == candidateId } ?: return
        val session = doc.sessions.firstOrNull { it.id == candidate.sessionID } ?: return
        val assistIdx = session.messages.indexOfFirst {
            it.candidateID == candidateId || it.id == candidate.sourceMessageID
        }
        val text = if (assistIdx > 0) {
            session.messages.subList(0, assistIdx)
                .lastOrNull { it.role == NovelSessionRole.User }
                ?.content
        } else {
            null
        }
        if (text.isNullOrBlank()) {
            _state.value = _state.value.copy(errorMessage = "找不到原输入，请手动重新发送")
            return
        }
        resendUserText(text)
    }

    override fun onCleared() {
        backgroundRunRegistry.withOwnedRunIds(projectId) { ownedRunIds ->
            novelCreation.interrupt(novelRouteExitRequest(projectId, ownedRunIds))
        }
        super.onCleared()
    }
}

/** Map known novel domain failures to short Chinese copy; leave unknown messages intact. */
internal fun humanizeNovelError(code: String?, message: String?): String {
    val msg = message.orEmpty()
    return when {
        code == "model_unavailable" ||
            msg.contains("Global chat model is not configured", ignoreCase = true) ||
            msg.contains("ModelUnavailable", ignoreCase = true) ||
            msg.contains("model is unavailable", ignoreCase = true) ||
            msg.contains("Provider missing", ignoreCase = true) ||
            msg.contains("Model not found", ignoreCase = true) ->
            "请先在设置中配置可用的聊天模型（小说创作使用全局聊天模型）"
        msg.contains("needs sync", ignoreCase = true) ->
            "正文已改写，请先在「正文」点「同步状态」，再继续生成或收录"
        msg.contains("Project is busy", ignoreCase = true) || code == "project_busy" ->
            "当前项目正在生成中，请稍后再试"
        msg.contains("read-only", ignoreCase = true) || code == "degraded_read_only" ->
            "项目处于只读恢复状态，请先恢复为可写"
        msg.contains("not available for collection", ignoreCase = true) ->
            "该候选已收录或不可用"
        msg.contains("stale relative to branch head", ignoreCase = true) ->
            "该候选已过期，请基于最新正文重新生成"
        msg.contains("Selected text is empty", ignoreCase = true) ->
            "请至少选择一段正文再收录"
        msg.isBlank() -> "操作失败"
        else -> msg
    }
}

internal fun humanizeGhostwriteRejection(result: NovelGhostwriteStartResult.Rejected): String {
    if (result.readinessIssues.isNotEmpty()) {
        return "代笔条件尚未满足：" + result.readinessIssues.joinToString("；") { it.displayName }
    }
    if (result.reason == NovelGhostwriteFailureReason.NotificationPermissionRequired) {
        return "请到系统设置中为 Amber 开启通知，再开始后台代笔"
    }
    result.detailMessage?.takeIf { it.isNotBlank() }?.let { return it }
    return when (result.reason) {
        NovelGhostwriteFailureReason.AlreadyRunning -> "已有代笔任务在运行"
        NovelGhostwriteFailureReason.ProjectUnavailable -> "项目不可用，无法开始代笔"
        NovelGhostwriteFailureReason.ProjectReadOnly -> "项目处于只读恢复状态"
        NovelGhostwriteFailureReason.CollaborationModeRequired -> "请先切换到代笔模式"
        NovelGhostwriteFailureReason.ReadinessBlocked -> "代笔条件尚未满足"
        NovelGhostwriteFailureReason.NotificationPermissionRequired ->
            "请到系统设置中为 Amber 开启通知，再开始后台代笔"
        NovelGhostwriteFailureReason.ForegroundServiceUnavailable -> "无法启动后台生成服务"
        NovelGhostwriteFailureReason.GenerationFailed -> "代笔生成失败"
    }
}

internal fun humanizeGhostwriteBatchRejection(
    result: NovelGhostwriteBatchCommandResult.Rejected,
): String {
    if (result.readinessIssues.isNotEmpty()) {
        return "连续代笔条件尚未满足：" +
            result.readinessIssues.joinToString("；") { it.displayName }
    }
    result.detail?.takeIf { it.isNotBlank() }?.let { return it }
    return when (result.reason) {
        NovelGhostwriteBatchCommandFailure.TargetOutOfRange -> "批次章数必须在 1 到 50 之间"
        NovelGhostwriteBatchCommandFailure.LedgerBlocked ->
            "批次账本损坏，请先隔离损坏任务后重试"
        NovelGhostwriteBatchCommandFailure.LedgerReadOnly -> "批次账本处于只读恢复状态"
        NovelGhostwriteBatchCommandFailure.ProjectUnavailable -> "项目或分支不可用"
        NovelGhostwriteBatchCommandFailure.ProjectReadOnly -> "项目处于只读恢复状态"
        NovelGhostwriteBatchCommandFailure.CollaborationModeRequired -> "请先切换到代笔模式"
        NovelGhostwriteBatchCommandFailure.ReadinessBlocked -> "连续代笔条件尚未满足"
        NovelGhostwriteBatchCommandFailure.NotificationRequired ->
            "请到系统设置中为 Amber 开启通知，再开始后台连续代笔"
        NovelGhostwriteBatchCommandFailure.InvalidState -> "当前批次状态不允许此操作"
        NovelGhostwriteBatchCommandFailure.Conflict -> "批次状态已变化，请重试"
        NovelGhostwriteBatchCommandFailure.SchedulingFailed -> "后台任务提交失败，请重试"
        NovelGhostwriteBatchCommandFailure.StorageFailed -> "批次账本保存失败"
    }
}

/**
 * Returns the busy value an old operation may publish from `finally`, or null when it no longer owns the slot.
 */
internal fun legacyOperationBusyAfterFinally(
    operationTokenMatches: Boolean,
    operationStillActive: Boolean,
    ghostwriteOwnedOrPending: Boolean,
): Boolean? = if (operationTokenMatches && operationStillActive) {
    ghostwriteOwnedOrPending
} else {
    null
}

internal fun novelRouteExitRequest(
    projectId: NovelProjectId,
    ownedRunIds: Set<NovelRunId>,
): NovelInterruptRequest = NovelInterruptRequest(
    projectId = projectId,
    reason = NovelInterruptReason.RouteExit,
    excludedRunIds = ownedRunIds,
)

/** Progress is branch-local after ownership ends; never offer recovery on a sibling branch. */
internal fun ghostwriteProgressForBranch(
    progress: NovelGhostwriteProgress?,
    projectId: NovelProjectId,
    branchId: NovelBranchId?,
): NovelGhostwriteProgress? = progress?.takeIf {
    branchId != null &&
        it.binding?.projectId == projectId &&
        it.binding?.branchId == branchId
}

internal fun projectGhostwriteBatchForBranch(
    snapshot: NovelGhostwriteBatchProjectSnapshot,
    branchId: NovelBranchId?,
    previous: NovelGhostwriteBatchUiState,
): NovelGhostwriteBatchUiState {
    val loaded = branchId?.let(snapshot::latestForBinding)
    val reasonCode = loaded?.job?.statusReasonCode
    val notificationRequired = reasonCode in setOf(
        "foreground_notification_unavailable",
        "foreground_start_failed",
        "foreground_update_failed",
    )
    return NovelGhostwriteBatchUiState(
        loading = false,
        job = loaded?.job,
        access = loaded?.access,
        projectHasActiveJob = snapshot.activeJobs.isNotEmpty(),
        scanFailures = snapshot.scanFailures,
        loadFailure = snapshot.loadFailure,
        commandPending = previous.commandPending,
        notificationRequired = previous.notificationRequired || notificationRequired,
    )
}

/** Prefer the durable batch binding after process recreation; never strand its controls on another branch. */
internal fun preferredBranchForActiveBatch(
    snapshot: NovelGhostwriteBatchProjectSnapshot,
    selectedBranchId: NovelBranchId?,
    isActiveBranch: (NovelBranchId) -> Boolean,
): NovelBranchId? {
    val active = snapshot.activeJobs.filter { isActiveBranch(it.job.branchID) }
    if (selectedBranchId != null && active.any { it.job.branchID == selectedBranchId }) {
        return selectedBranchId
    }
    return active.maxWithOrNull(
        compareBy<app.amber.feature.novel.persistence.NovelLoadedGhostwriteJob> { it.job.updatedAt }
            .thenBy { it.job.id.rawValue },
    )?.job?.branchID
}
