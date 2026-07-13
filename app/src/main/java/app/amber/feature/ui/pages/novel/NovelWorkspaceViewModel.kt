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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NovelWorkspaceTab { Create, Materials }
enum class NovelMaterialsCategory { Manuscript, Characters, World, Plot, More }

data class NovelWorkspaceUiState(
    val loading: Boolean = true,
    val document: NovelProjectDocumentV1? = null,
    val access: NovelProjectLoadAccess = NovelProjectLoadAccess.ReadWrite,
    val primaryFailure: String? = null,
    val selectedBranchId: NovelBranchId? = null,
    val tab: NovelWorkspaceTab = NovelWorkspaceTab.Create,
    val materialsCategory: NovelMaterialsCategory = NovelMaterialsCategory.Manuscript,
    val composerMode: NovelSessionModeRequest = NovelSessionModeRequest.WriteProse,
    val granularity: NovelGenerationGranularityRequest = NovelGenerationGranularityRequest.WholeChapter,
    val errorMessage: String? = null,
    val busy: Boolean = false,
    val draft: String = "",
    val streamingText: String = "",
    val generating: Boolean = false,
)

class NovelWorkspaceViewModel(
    projectId: String,
    private val novelCreation: NovelCreation,
) : ViewModel() {
    val projectId: NovelProjectId = NovelProjectId.parse(projectId)
    private val _state = MutableStateFlow(NovelWorkspaceUiState())
    val state: StateFlow<NovelWorkspaceUiState> = _state.asStateFlow()
    private var generateJob: Job? = null

    init { refresh() }

    private var refreshSeq = 0L

    fun refresh() {
        viewModelScope.launch { refreshSuspend() }
    }

    private suspend fun refreshSuspend() {
        val seq = ++refreshSeq
        _state.value = _state.value.copy(loading = true, errorMessage = null)
        runCatching {
            when (val snap = novelCreation.snapshot(NovelQuery.Project(projectId))) {
                is NovelSnapshot.Project -> {
                    if (seq != refreshSeq) return // superseded by a newer refresh
                    val doc = snap.document
                    val branchId = _state.value.selectedBranchId
                        ?.takeIf { id -> doc.branches.any { it.id == id && it.lifecycle == NovelBranchLifecycle.Active } }
                        ?: doc.project.mainBranchID
                    _state.value = _state.value.copy(
                        loading = false,
                        document = doc,
                        access = snap.access,
                        primaryFailure = snap.primaryFailure,
                        selectedBranchId = branchId,
                    )
                }
                else -> error("Unexpected snapshot")
            }
        }.onFailure { e ->
            if (seq != refreshSeq) return
            _state.value = _state.value.copy(loading = false, errorMessage = e.message)
        }
    }

    fun selectTab(tab: NovelWorkspaceTab) { _state.value = _state.value.copy(tab = tab) }
    fun selectMaterialsCategory(category: NovelMaterialsCategory) {
        _state.value = _state.value.copy(tab = NovelWorkspaceTab.Materials, materialsCategory = category)
    }
    fun selectBranch(branchId: NovelBranchId) {
        val doc = _state.value.document ?: return
        if (doc.branches.none { it.id == branchId && it.lifecycle == NovelBranchLifecycle.Active }) {
            _state.value = _state.value.copy(errorMessage = "Branch not found")
            return
        }
        _state.value = _state.value.copy(selectedBranchId = branchId, errorMessage = null)
    }
    fun setComposerMode(mode: NovelSessionModeRequest) {
        _state.value = _state.value.copy(composerMode = mode)
    }
    fun setGranularity(g: NovelGenerationGranularityRequest) {
        _state.value = _state.value.copy(composerMode = NovelSessionModeRequest.WriteProse, granularity = g)
    }
    fun updateDraft(text: String) { _state.value = _state.value.copy(draft = text) }

    fun send() {
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.generating) return
        if (_state.value.access != NovelProjectLoadAccess.ReadWrite) {
            _state.value = _state.value.copy(errorMessage = "Project is read-only until restored")
            return
        }
        val branchId = _state.value.selectedBranchId ?: return
        val doc = _state.value.document
        val kind = when {
            doc?.project?.creationMode == NovelProjectCreationMode.QuickStart &&
                doc.sessions.firstOrNull { it.branchID == branchId }?.messages.isNullOrEmpty() ->
                NovelRunKindRequest.QuickStart
            _state.value.composerMode == NovelSessionModeRequest.DiscussPlan ->
                NovelRunKindRequest.Discussion
            else -> NovelRunKindRequest.Prose
        }
        // Always interrupt domain run before dropping the UI collector.
        novelCreation.interrupt(
            NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.User),
        )
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                generating = true, busy = true, errorMessage = null, streamingText = "",
            )
            try {
                val run = novelCreation.start(
                    NovelRunRequest(
                        projectId = projectId,
                        branchId = branchId,
                        userText = text,
                        mode = _state.value.composerMode,
                        granularity = if (kind == NovelRunKindRequest.Prose) _state.value.granularity else null,
                        kind = kind,
                    ),
                )
                var full = ""
                run.events.collect { event ->
                    when (event) {
                        NovelRunEvent.Started -> Unit
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
                                generating = false, busy = false, draft = "", streamingText = "",
                            )
                            refreshSuspend()
                        }
                        is NovelRunEvent.Interrupted -> {
                            _state.value = _state.value.copy(
                                generating = false, busy = false,
                                errorMessage = "Generation interrupted",
                                streamingText = "",
                            )
                            refreshSuspend()
                        }
                        is NovelRunEvent.Failed -> {
                            _state.value = _state.value.copy(
                                generating = false,
                                busy = false,
                                errorMessage = event.message,
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
                    errorMessage = e.message ?: "Generation failed",
                    streamingText = "",
                )
            } finally {
                // Ensure UI never stays stuck if the event stream ends without a terminal event.
                if (_state.value.generating || _state.value.busy) {
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

    fun collectCandidate(candidateId: NovelCandidateId) {
        val doc = _state.value.document ?: return
        val branchId = _state.value.selectedBranchId ?: return
        val candidate = doc.candidates.firstOrNull { it.id == candidateId } ?: return
        if (candidate.status != NovelCandidateStatus.Available) return
        val paragraphs = NovelParagraphSelection.splitParagraphs(candidate.content)
        val text = NovelParagraphSelection.joinSelected(
            paragraphs,
            NovelParagraphSelection.defaultSelectedIds(paragraphs),
        )
        val chapterCount = currentBranch()?.workingChapterSelections?.size ?: 0
        val whole = doc.project.lastGenerationGranularity.name == "WholeChapter"
        val target = when (NovelParagraphSelection.suggestTarget(chapterCount, whole)) {
            NovelParagraphSelection.SuggestedTarget.CreateFirstChapter,
            NovelParagraphSelection.SuggestedTarget.CreateNextChapter,
            -> NovelCollectionTarget.CreateNextChapter(
                chapterID = NovelChapterId.generate(),
                title = "第${chapterCount + 1}章",
            )
            NovelParagraphSelection.SuggestedTarget.AppendCurrentChapter -> {
                val chapterId = currentBranch()!!.workingChapterSelections.last().chapterID
                NovelCollectionTarget.AppendToChapter(chapterId)
            }
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(
                    NovelIntent.CollectCandidate(
                        projectId = projectId,
                        branchId = branchId,
                        candidateId = candidateId,
                        selectedText = text,
                        target = target,
                    ),
                )
                refreshSuspend()
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = e.message)
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
                refreshSuspend()
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
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
                    _state.value = _state.value.copy(selectedBranchId = outcome.branchID)
                }
                refreshSuspend()
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
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
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun setModelPolicy(policy: NovelProjectModelPolicy) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.SetModelPolicy(projectId, policy))
                refreshSuspend()
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun restorePrevious() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.RestorePrevious(projectId))
                refreshSuspend()
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
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
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
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
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun polishChapter(versionId: app.amber.feature.novel.model.NovelChapterVersionId) {
        val branchId = _state.value.selectedBranchId ?: return
        val doc = _state.value.document ?: return
        val version = doc.chapterVersions.firstOrNull { it.id == versionId } ?: return
        novelCreation.interrupt(
            NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.User),
        )
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            _state.value = _state.value.copy(generating = true, busy = true, errorMessage = null, streamingText = "")
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
            try {
                run.events.collect { event ->
                    when (event) {
                        NovelRunEvent.Started -> Unit
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
                                errorMessage = "Polish interrupted", streamingText = "",
                            )
                            refreshSuspend()
                        }
                        is NovelRunEvent.Failed -> {
                            _state.value = _state.value.copy(
                                generating = false, busy = false,
                                errorMessage = event.message, streamingText = "",
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
                    errorMessage = e.message ?: "Polish failed", streamingText = "",
                )
            } finally {
                if (_state.value.generating || _state.value.busy) {
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
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun setPolishPreference(text: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.SetPolishPreference(projectId, text))
                refreshSuspend()
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun renameBranch(branchId: NovelBranchId, name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.RenameBranch(projectId, branchId, name))
                refreshSuspend()
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun setMainBranch(branchId: NovelBranchId) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching {
                novelCreation.perform(NovelIntent.SetMainBranch(projectId, branchId))
                refreshSuspend()
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
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
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
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
            }.onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
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
