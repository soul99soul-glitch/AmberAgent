package app.amber.feature.ui.pages.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.feature.novel.NovelCreation
import app.amber.feature.novel.NovelGenerationGranularityRequest
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.NovelInterruptReason
import app.amber.feature.novel.NovelInterruptRequest
import app.amber.feature.novel.NovelQuery
import app.amber.feature.novel.NovelRunEvent
import app.amber.feature.novel.NovelRunKindRequest
import app.amber.feature.novel.NovelRunRequest
import app.amber.feature.novel.NovelSessionModeRequest
import app.amber.feature.novel.NovelSnapshot
import app.amber.feature.novel.domain.NovelParagraphSelection
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelProposalId
import app.amber.feature.novel.model.NovelSessionMessageRecord
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
    val draft: String = "",
    val streamingText: String = "",
    val generating: Boolean = false,
)

class NovelWorkspaceViewModel(
    projectId: String,
    private val novelCreation: NovelCreation,
    private val uiSession: NovelProjectUiSession,
) : ViewModel() {
    val projectId: NovelProjectId = NovelProjectId.parse(projectId)
    private val _state = MutableStateFlow(NovelWorkspaceUiState())
    val state: StateFlow<NovelWorkspaceUiState> = _state.asStateFlow()
    private var generateJob: Job? = null
    /** Bumps on each new send/polish so a cancelled job's finally cannot clear the newer run. */
    private var generationEpoch = 0L
    /**
     * QuickStart only creates the project shell; iOS auto-runs generation after create.
     * Auto-kick once per successful attempt window. Failures clear the flag so retry works.
     */
    private var quickStartKickoffDone = false

    init { refresh() }

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
                    val branchId = listOfNotNull(
                        uiSession.selectedBranch(projectId.rawValue),
                        _state.value.selectedBranchId,
                        doc.project.mainBranchID,
                    ).firstOrNull(active) ?: doc.project.mainBranchID
                    branchId?.let { uiSession.setSelectedBranch(projectId.rawValue, it) }
                    _state.value = _state.value.copy(
                        loading = false,
                        document = doc,
                        access = snap.access,
                        primaryFailure = snap.primaryFailure,
                        selectedBranchId = branchId,
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
        uiSession.setSelectedBranch(projectId.rawValue, branchId)
        _state.value = _state.value.copy(selectedBranchId = branchId, errorMessage = null)
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

    /** Short copy for the collect CTA: where this candidate will land. */
    fun collectTargetHint(isWholeChapter: Boolean = isWholeChapterCollectDefault()): String {
        val chapterCount = currentBranch()?.workingChapterSelections?.size ?: 0
        return when (NovelParagraphSelection.suggestTarget(chapterCount, isWholeChapter)) {
            NovelParagraphSelection.SuggestedTarget.CreateFirstChapter -> "将创建第 1 章"
            NovelParagraphSelection.SuggestedTarget.CreateNextChapter ->
                "将创建第 ${chapterCount + 1} 章"
            NovelParagraphSelection.SuggestedTarget.AppendCurrentChapter -> "将追加到当前章"
        }
    }

    fun isWholeChapterCollectDefault(): Boolean {
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
                                streamingText = "",
                                statusMessage = if (isQuick) {
                                    "快速开始完成：请确认下方设定建议，或到「设定」查看"
                                } else {
                                    null
                                },
                            )
                            refreshSuspend()
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
                            // Keep quickStartKickoffDone=true so refresh does not auto-loop;
                            // user can tap「重新生成初始设定」.
                            _state.value = _state.value.copy(
                                generating = false, busy = false,
                                draft = restore,
                                errorMessage = "已停止生成" +
                                    if (forceKind == NovelRunKindRequest.QuickStart) {
                                        "（可点「重新生成初始设定」再试）"
                                    } else {
                                        "，输入已恢复"
                                    },
                                streamingText = "",
                                statusMessage = null,
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
                // Only the latest generation may clear flags (cancelled job must not clobber a newer run).
                if (epoch == generationEpoch && (_state.value.generating || _state.value.busy)) {
                    _state.value = _state.value.copy(generating = false, busy = false)
                }
            }
        }
    }

    fun stop() {
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

    fun collectCandidate(candidateId: NovelCandidateId) {
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
        val candidate = doc.candidates.firstOrNull { it.id == candidateId }
        if (candidate == null) {
            _state.value = _state.value.copy(errorMessage = "找不到该候选，请刷新后重试")
            return
        }
        if (candidate.status != NovelCandidateStatus.Available) {
            _state.value = _state.value.copy(errorMessage = "该候选已收录或不可用")
            return
        }
        val paragraphs = NovelParagraphSelection.splitParagraphs(candidate.content)
        val text = NovelParagraphSelection.joinSelected(
            paragraphs,
            NovelParagraphSelection.defaultSelectedIds(paragraphs),
        )
        if (text.isBlank()) {
            _state.value = _state.value.copy(errorMessage = "候选正文为空，无法收录")
            return
        }
        val chapterCount = branch?.workingChapterSelections?.size ?: 0
        val whole = isWholeChapterCollectDefault()
        val target = when (NovelParagraphSelection.suggestTarget(chapterCount, whole)) {
            NovelParagraphSelection.SuggestedTarget.CreateFirstChapter,
            NovelParagraphSelection.SuggestedTarget.CreateNextChapter,
            -> NovelCollectionTarget.CreateNextChapter(
                chapterID = NovelChapterId.generate(),
                title = "第${chapterCount + 1}章",
            )
            NovelParagraphSelection.SuggestedTarget.AppendCurrentChapter -> {
                val chapterId = branch!!.workingChapterSelections.last().chapterID
                NovelCollectionTarget.AppendToChapter(chapterId)
            }
        }
        val successHint = collectTargetHint(whole)
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            runCatching {
                // Skip state-delta LLM on collect — that call was the main latency.
                // Living materials can catch up later; prose lands in 正文 immediately.
                novelCreation.perform(
                    NovelIntent.CollectCandidate(
                        projectId = projectId,
                        branchId = branchId,
                        candidateId = candidateId,
                        selectedText = text,
                        target = target,
                        runStateDelta = false,
                    ),
                )
                refreshSuspend()
                _state.value = _state.value.copy(
                    statusMessage = "已收录 · $successHint。可在「正文」查看",
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
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

    fun undoHead() {
        val branchId = _state.value.selectedBranchId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.UndoHead(projectId, branchId))
                refreshSuspend()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun setModelPolicy(policy: NovelProjectModelPolicy) {
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

    fun restorePrevious() {
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

    fun saveManualEdit(chapterId: NovelChapterId, title: String, content: String) {
        val branchId = _state.value.selectedBranchId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(
                    NovelIntent.SaveManualEdit(projectId, branchId, chapterId, title, content),
                )
                refreshSuspend()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun syncManualEdits() {
        val branchId = _state.value.selectedBranchId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.SyncManualEdits(projectId, branchId))
                refreshSuspend()
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
                            _state.value = _state.value.copy(generating = false, busy = false, streamingText = "")
                            refreshSuspend()
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
                if (epoch == generationEpoch && (_state.value.generating || _state.value.busy)) {
                    _state.value = _state.value.copy(generating = false, busy = false)
                }
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
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(
                    NovelIntent.ReviseMaterial(projectId, materialId, kind, title, content),
                )
                refreshSuspend()
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

    fun currentSessionMessages(): List<NovelSessionMessageRecord> {
        val doc = _state.value.document ?: return emptyList()
        val branch = currentBranch() ?: return emptyList()
        return doc.sessions.firstOrNull { it.id == branch.sessionID }?.messages.orEmpty()
    }

    fun availableCandidates() =
        _state.value.document?.candidates
            ?.filter {
                it.branchID == _state.value.selectedBranchId &&
                    it.status == NovelCandidateStatus.Available
            }
            .orEmpty()

    override fun onCleared() {
        novelCreation.interrupt(
            NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.RouteExit),
        )
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
        msg.isBlank() -> "操作失败"
        else -> msg
    }
}
