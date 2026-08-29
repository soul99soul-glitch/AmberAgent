package app.amber.feature.ui.pages.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.ai.core.MessageRole
import app.amber.core.ai.RunKernel
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.novelworkspace.NovelWorkspaceProjectSettingsStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import app.amber.feature.novel.workspace.NovelTurnLauncher
import app.amber.feature.novel.workspace.NovelWorkspaceCollectTarget
import app.amber.feature.novel.workspace.NovelWorkspacePrompts
import app.amber.feature.novel.workspace.NovelWorkspaceRuntime
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteController
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifest
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceProjectTitle
import app.amber.feature.novelworkspace.NovelWorkspaceSessionMessage
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Experimental workspace-native novel screen: the book is a markdown tree and generation
 * runs through [NovelWorkspaceRuntime] (five workspace tools + the canon write gate).
 */
data class NovelMarkdownMessageUi(
    val id: String,
    val role: MessageRole,
    val content: String,
)

data class NovelMarkdownChapterUi(
    val path: String,
    val title: String,
    val ordinal: Int,
    val charCount: Int,
)

data class NovelMarkdownDraftUi(
    val path: String,
    val title: String,
    val excerpt: String,
)

data class NovelMarkdownWorkspaceUiState(
    val loading: Boolean = true,
    val exists: Boolean = false,
    val title: String = "",
    val messages: List<NovelMarkdownMessageUi> = emptyList(),
    val chapters: List<NovelMarkdownChapterUi> = emptyList(),
    val drafts: List<NovelMarkdownDraftUi> = emptyList(),
    val streamingText: String = "",
    val reasoningText: String = "",
    val toolActivity: String? = null,
    val busy: Boolean = false,
    val proposals: List<NovelWorkspaceWriteProposal> = emptyList(),
    val plotStale: Boolean = false,
    /** D-D: editing a middle chapter invalidates this ordinal and after, until resolved. */
    val unresolvedFromOrdinal: Int? = null,
    /** Running/paused ghostwrite batch for this project, if any. */
    val ghostwriteJob: NovelMarkdownGhostwriteUi? = null,
    /** Per-project writing model override (null = follow global chat model). */
    val writingModelId: String? = null,
    /** Composer intent: 讨论 plans/world; 写正文 produces a collectable draft. */
    val composerMode: NovelMarkdownComposerMode = NovelMarkdownComposerMode.Discuss,
    /** Consistency review running / last report. */
    val consistencyChecking: Boolean = false,
    val consistencyReport: String? = null,
    /** One-level undo available (the last canon commit can be rolled back). */
    val canUndo: Boolean = false,
    /** Which constraint sections the per-turn brief injects (ghostwrite panel). */
    val injection: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags =
        app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
    /** Bumped when the chapter plan changes outside the editor (auto-draft) so the sheet reloads. */
    val planAutoTick: Int = 0,
    val errorMessage: String? = null,
)

enum class NovelMarkdownComposerMode { Discuss, WriteProse }

data class NovelMarkdownGhostwriteUi(
    val jobId: String,
    val executionId: String,
    val target: Int,
    val written: Int,
    val status: String,
    /** Terminal-failure reason surfaced when status == failed. */
    val reason: String? = null,
)

private data class NovelGhostwriteRefresh(
    val job: NovelMarkdownGhostwriteUi?,
    val chapters: List<NovelMarkdownChapterUi>,
    val drafts: List<NovelMarkdownDraftUi>,
    val plotStale: Boolean,
    val unresolvedFromOrdinal: Int?,
    val canUndo: Boolean,
)

class NovelMarkdownWorkspaceViewModel(
    projectId: String,
    private val repository: NovelWorkspaceProjectRepository,
    private val settingsAggregator: SettingsAggregator,
    private val ghostwriteController: NovelWorkspaceGhostwriteController,
    private val turnLauncher: NovelTurnLauncher,
    kernel: RunKernel,
) : ViewModel() {

    val projectId: String = projectId
    private val runtime = NovelWorkspaceRuntime(kernel)
    private val _state = MutableStateFlow(NovelMarkdownWorkspaceUiState())
    val state: StateFlow<NovelMarkdownWorkspaceUiState> = _state.asStateFlow()

    private var projectDirectory: File? = null
    private var branchId: String? = null
    private var branchSlug: String? = null

    /** In-flight chat turn; cancellable so the composer's stop button can end it. */
    private var turnJob: kotlinx.coroutines.Job? = null
    private var ghostwriteRefreshJob: kotlinx.coroutines.Job? = null

    /** Stop the in-flight turn (composer stop). Partial output is discarded; the
     *  workspace runtime rolls back any uncommitted canon writes on cancellation. */
    fun stopTurn() {
        turnJob?.cancel()
    }

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            runCatching {
                if (!repository.exists(projectId)) {
                    _state.value = _state.value.copy(loading = false, exists = false)
                    return@launch
                }
                val directory = repository.projectDirectory(projectId)
                val store = NovelWorkspaceStore(directory)
                val manifest = NovelWorkspaceManifest.parse(
                    store.read(NovelWorkspacePaths.MANIFEST) ?: "",
                )
                val ledger = NovelWorkspaceLedger.load(directory)
                projectDirectory = directory
                branchId = NovelWorkspaceLedger.branchId(store, ledger, manifest.mainBranch)
                branchSlug = manifest.mainBranch
                _state.value = _state.value.copy(
                    loading = false,
                    exists = true,
                    title = NovelWorkspaceProjectTitle.read(store),
                    messages = loadMessages(directory),
                    chapters = loadChapters(store),
                    proposals = proposalsForThisProject(),
                    drafts = loadDrafts(store),
                    plotStale = NovelWorkspaceLedger.isPlotStale(ledger, manifest.mainBranch),
                    unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore
                        .entryFor(directory, manifest.mainBranch)?.fromOrdinal,
                    writingModelId = NovelWorkspaceProjectSettingsStore.load(directory).writingModelId,
                    injection = NovelWorkspaceProjectSettingsStore.load(directory).injection
                        ?: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
                    canUndo = runtime.canUndo(directory),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = error.message ?: "无法打开工作区项目",
                )
            }
            refreshGhostwrite()
        }
    }

    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return false
        val directory = projectDirectory
        val branch = branchId
        val slug = branchSlug
        if (directory == null || branch == null || slug == null) {
            _state.value = _state.value.copy(errorMessage = "工作区尚未加载完成")
            return false
        }
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = "当前分支仍被代笔批次占用，请先让批次完成或取消后再继续")
            return false
        }
        if (_state.value.composerMode == NovelMarkdownComposerMode.WriteProse) {
            if (_state.value.plotStale) {
                _state.value = _state.value.copy(errorMessage = "剧情落后于正文。请切到“讨论”，发送“根据最新正文同步 plot/current.md”，并批准剧情修改")
                return false
            }
            if (_state.value.unresolvedFromOrdinal != null) {
                _state.value = _state.value.copy(errorMessage = "存在未解决的中间章修改，请先处理后再写新正文")
                return false
            }
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings)
        if (model == null) {
            _state.value = _state.value.copy(errorMessage = "尚未配置聊天模型")
            return false
        }
        // A blank book (no chapters, no setting cards) treats the message as the quickstart
        // seed and generates the initial settings. Chapter/setting emptiness — not message
        // history — is the criterion, so a failed first turn re-triggers quickstart instead
        // of permanently falling back to plain discussion.
        val isBlankBook = _state.value.chapters.isEmpty() &&
            NovelWorkspaceStore(directory).list(NovelWorkspacePaths.SETTING_DIR).isEmpty()
        // Quickstart must actually produce files; a read-only turn is a silent failure
        // for a first-time user, so measure what exists and check it after the turn.
        val filesBeforeQuickstart = if (isBlankBook) NovelWorkspaceStore(directory).list().size else -1
        appendSessionMessage(directory, branch, NovelWorkspaceSessionMessage(
            id = UUID.randomUUID().toString().uppercase(),
            role = "user",
            kind = "userInput",
            content = trimmed,
            createdAt = Instant.now(),
        ))
        _state.value = _state.value.copy(
            busy = true,
            errorMessage = null,
            streamingText = "",
            reasoningText = "",
            toolActivity = null,
            messages = loadMessages(directory),
        )
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            android.util.Log.i("NovelWorkspace", "send: turn starting (blank=$isBlankBook)")
            var finalText = ""
            try {
                turnLauncher.launch(
                NovelWorkspaceRuntime.TurnRequest(
                    projectDirectory = directory,
                    branchId = branch,
                    branchSlug = slug,
                    userText = trimmed,
                    systemPrompt = when {
                        isBlankBook -> NovelWorkspacePrompts.quickStart(genre = "", coreIdea = trimmed)
                        _state.value.composerMode == NovelMarkdownComposerMode.WriteProse ->
                            NovelWorkspacePrompts.proseDraft(NovelWorkspacePrompts.ProseGranularity.CONTINUATION)
                        else -> NovelWorkspacePrompts.discussion()
                    },
                    settings = settings,
                    model = model,
                    // Quickstart writes several setting files in one turn; 16 steps
                    // starved it into a read-only loop on device.
                    maxSteps = if (isBlankBook) 32 else 16,
                    injection = _state.value.injection,
                ),
                runtime,
            ).events.collect { event ->
                when (event) {
                    is NovelWorkspaceRuntime.TurnEvent.Delta -> {
                        finalText += event.text
                        _state.value = _state.value.copy(streamingText = finalText)
                    }
                    is NovelWorkspaceRuntime.TurnEvent.ReasoningDelta -> {
                        _state.value = _state.value.copy(
                            reasoningText = _state.value.reasoningText + event.text,
                        )
                    }
                    is NovelWorkspaceRuntime.TurnEvent.ToolActivity -> {
                        _state.value = _state.value.copy(toolActivity = toolLabel(event.toolName))
                    }
                    is NovelWorkspaceRuntime.TurnEvent.Completed -> {
                        android.util.Log.i("NovelWorkspace", "send: Completed finalLen=${event.finalText.length}")
                        if (event.finalText.isNotBlank()) {
                            appendSessionMessage(directory, branch, NovelWorkspaceSessionMessage(
                                id = UUID.randomUUID().toString().uppercase(),
                                role = "assistant",
                                kind = "discussion",
                                content = event.finalText,
                                createdAt = Instant.now(),
                            ))
                        }
                        val store = NovelWorkspaceStore(directory)
                        // Device-observed failure: the quickstart turn can spend its whole
                        // budget reading an empty book and produce nothing — surface that
                        // instead of ending as a silent empty turn.
                        val producedNothing = isBlankBook &&
                            filesBeforeQuickstart >= 0 &&
                            store.list().size == filesBeforeQuickstart
                        _state.value = _state.value.copy(
                            busy = false,
                            streamingText = "",
                            reasoningText = "",
                            toolActivity = null,
                            errorMessage = if (producedNothing) {
                                "快速开始这轮没有写出任何设定文件。请更明确地下指令重试，例如：主角叫什么、题材背景，并要求它写入设定文件。"
                            } else {
                                null
                            },
                            messages = loadMessages(directory),
                            chapters = loadChapters(store),
                            drafts = loadDrafts(store),
                            proposals = proposalsForThisProject(),
                            plotStale = NovelWorkspaceLedger.isPlotStale(
                                NovelWorkspaceLedger.load(directory),
                                slug,
                            ),
                            unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore
                                .entryFor(directory, slug)?.fromOrdinal,
                            canUndo = runtime.canUndo(directory),
                        )
                    }
                    is NovelWorkspaceRuntime.TurnEvent.Failed -> {
                        android.util.Log.i("NovelWorkspace", "send: Failed msg=${event.message}")
                        _state.value = _state.value.copy(
                            busy = false,
                            streamingText = "",
                            toolActivity = null,
                            errorMessage = event.message,
                            proposals = proposalsForThisProject(),
                        )
                    }
                }
            }
            } finally {
                // Stop button / VM clear cancels the collect: reset the busy chrome.
                if (_state.value.busy) {
                    _state.value = _state.value.copy(
                        busy = false,
                        streamingText = "",
                        reasoningText = "",
                        toolActivity = null,
                    )
                }
            }
        }
        return true
    }

    /** D-D resolution: let the assistant rewrite the chapters a middle edit invalidated.
     *  Rewrites go through the canon gate as proposals; the author approves them, then
     *  confirms 无碍 to clear the gate. */
    fun rewriteLaterChapters() {
        val fromOrdinal = _state.value.unresolvedFromOrdinal ?: return
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = "当前分支仍被代笔批次占用，请先让批次完成或取消后再重写正文")
            return
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings) ?: run {
            _state.value = _state.value.copy(errorMessage = "尚未配置聊天模型")
            return
        }
        _state.value = _state.value.copy(
            busy = true,
            errorMessage = null,
            streamingText = "",
            reasoningText = "",
            toolActivity = null,
        )
        viewModelScope.launch {
            var finalText = ""
            turnLauncher.launch(
                NovelWorkspaceRuntime.TurnRequest(
                    projectDirectory = directory,
                    branchId = branch,
                    branchSlug = slug,
                    userText = "请重写第 $fromOrdinal 章起的受影响章节，使其与前文一致。",
                    systemPrompt = NovelWorkspacePrompts.rewriteLaterChapters(fromOrdinal),
                    settings = settings,
                    model = model,
                ),
                runtime,
            ).events.collect { event ->
                when (event) {
                    is NovelWorkspaceRuntime.TurnEvent.Delta -> {
                        finalText += event.text
                        _state.value = _state.value.copy(streamingText = finalText)
                    }
                    is NovelWorkspaceRuntime.TurnEvent.ReasoningDelta -> {
                        _state.value = _state.value.copy(
                            reasoningText = _state.value.reasoningText + event.text,
                        )
                    }
                    is NovelWorkspaceRuntime.TurnEvent.ToolActivity -> {
                        _state.value = _state.value.copy(toolActivity = toolLabel(event.toolName))
                    }
                    is NovelWorkspaceRuntime.TurnEvent.Completed -> {
                        val store = NovelWorkspaceStore(directory)
                        _state.value = _state.value.copy(
                            busy = false,
                            streamingText = "",
                            reasoningText = "",
                            toolActivity = null,
                            chapters = loadChapters(store),
                            drafts = loadDrafts(store),
                            proposals = proposalsForThisProject(),
                            plotStale = NovelWorkspaceLedger.isPlotStale(
                                NovelWorkspaceLedger.load(directory),
                                slug,
                            ),
                        )
                    }
                    is NovelWorkspaceRuntime.TurnEvent.Failed -> {
                        _state.value = _state.value.copy(
                            busy = false,
                            streamingText = "",
                            toolActivity = null,
                            errorMessage = event.message,
                            proposals = proposalsForThisProject(),
                        )
                    }
                }
            }
        }
    }

    fun approve(proposalId: String) {
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = "当前分支仍被代笔批次占用，请先让批次完成或取消后再批准正文修改")
            return
        }
        viewModelScope.launch {
            runCatching {
                runtime.approve(proposalId)
                val directory = projectDirectory ?: return@launch
                val slug = branchSlug
                val store = NovelWorkspaceStore(directory)
                _state.value = _state.value.copy(
                    chapters = loadChapters(store),
                    drafts = loadDrafts(store),
                    proposals = proposalsForThisProject(),
                    plotStale = if (slug != null) {
                        NovelWorkspaceLedger.isPlotStale(NovelWorkspaceLedger.load(directory), slug)
                    } else {
                        false
                    },
                    unresolvedFromOrdinal = slug?.let {
                        NovelWorkspaceUnresolvedStore.entryFor(directory, it)?.fromOrdinal
                    },
                    canUndo = runtime.canUndo(directory),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(errorMessage = error.message ?: "批准失败")
            }
        }
    }

    fun reject(proposalId: String) {
        runtime.reject(proposalId)
        _state.value = _state.value.copy(proposals = proposalsForThisProject())
    }

    /** D-D resolve (确认无碍): clear the unresolved gate for the current branch. */
    fun resolveUnresolved() {
        val directory = projectDirectory ?: return
        val slug = branchSlug ?: return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = "代笔批次进行中，不能确认中间章状态")
            return
        }
        runCatching {
            NovelWorkspaceUnresolvedStore.clear(directory, slug)
            _state.value = _state.value.copy(unresolvedFromOrdinal = null)
        }.onFailure { error ->
            _state.value = _state.value.copy(errorMessage = error.message ?: "无法解除未决状态")
        }
    }

    /** Author manual chapter edit; saving commits it (middle edits raise the unresolved gate). */
    fun saveChapterEdit(path: String, title: String, body: String, onSaved: () -> Unit) {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = "当前分支仍被代笔批次占用，请先让批次完成或取消后再编辑正文")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                runtime.saveChapterEdit(
                    projectDirectory = directory,
                    branchId = branch,
                    branchSlug = slug,
                    chapterPath = path,
                    title = title,
                    body = body,
                )
                val store = NovelWorkspaceStore(directory)
                _state.value = _state.value.copy(
                    chapters = loadChapters(store),
                    plotStale = NovelWorkspaceLedger.isPlotStale(NovelWorkspaceLedger.load(directory), slug),
                    unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
                    canUndo = runtime.canUndo(directory),
                )
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message ?: "保存失败")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** 撤销最近一笔 canon commit (single level). */
    fun undoLast() {
        val directory = projectDirectory ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = "当前分支仍被代笔批次占用，请先让批次完成或取消后再撤销")
            return
        }
        viewModelScope.launch {
            val undone = withContext(Dispatchers.IO) { runtime.undoLast(directory) }
            if (!undone) {
                _state.value = _state.value.copy(errorMessage = "没有可撤销的最近操作")
                return@launch
            }
            val slug = branchSlug
            val store = NovelWorkspaceStore(directory)
            _state.value = _state.value.copy(
                chapters = loadChapters(store),
                drafts = loadDrafts(store),
                proposals = proposalsForThisProject(),
                plotStale = if (slug != null) {
                    NovelWorkspaceLedger.isPlotStale(NovelWorkspaceLedger.load(directory), slug)
                } else {
                    false
                },
                unresolvedFromOrdinal = slug?.let {
                    NovelWorkspaceUnresolvedStore.entryFor(directory, it)?.fromOrdinal
                },
                canUndo = runtime.canUndo(directory),
            )
        }
    }

    fun readChapter(path: String): String? {
        val directory = projectDirectory ?: return null
        return runCatching {
            val store = NovelWorkspaceStore(directory)
            val content = store.read(path) ?: return@runCatching null
            NovelWorkspaceMarkdown.parseFile(content).body
        }.getOrNull()
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun setComposerMode(mode: NovelMarkdownComposerMode) {
        _state.value = _state.value.copy(composerMode = mode)
    }

    private fun proposalsForThisProject(): List<NovelWorkspaceWriteProposal> {
        val directory = projectDirectory ?: return emptyList()
        return runtime.pendingProposals.value.filter { it.projectDirectory == directory }
    }

    private fun hasActiveGhostwrite(): Boolean {
        if (_state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED
        ) return true
        val directory = projectDirectory ?: return false
        val slug = branchSlug ?: return false
        return NovelWorkspaceGhostwriteJobs.activeFor(directory, slug) != null
    }

    private fun loadMessages(directory: File): List<NovelMarkdownMessageUi> {
        val branch = branchId ?: return emptyList()
        return NovelWorkspaceSessions.load(directory).sessions[branch].orEmpty().map { message ->
            NovelMarkdownMessageUi(
                id = message.id,
                role = when (message.role) {
                    "assistant" -> MessageRole.ASSISTANT
                    "system" -> MessageRole.SYSTEM
                    else -> MessageRole.USER
                },
                content = message.content,
            )
        }
    }

    private fun loadChapters(store: NovelWorkspaceStore): List<NovelMarkdownChapterUi> {
        val slug = branchSlug ?: return emptyList()
        val prefix = NovelWorkspacePaths.branchPrefix(slug) + "/chapters"
        return store.list(prefix).mapNotNull { path ->
            val content = store.read(path) ?: return@mapNotNull null
            val parsed = NovelWorkspaceMarkdown.parseFile(content)
            NovelMarkdownChapterUi(
                path = path,
                title = parsed.fields["title"] ?: NovelWorkspacePaths.fileNameTitle(path),
                ordinal = parsed.fields["ordinal"]?.toIntOrNull()
                    ?: NovelWorkspacePaths.chapterOrdinalFromPath(path)
                    ?: 0,
                charCount = parsed.body.length,
            )
        }.sortedBy { it.ordinal }
    }

    private fun loadDrafts(store: NovelWorkspaceStore): List<NovelMarkdownDraftUi> {
        return store.list(NovelWorkspacePaths.DRAFTS_DIR).mapNotNull { path ->
            val content = store.read(path) ?: return@mapNotNull null
            val parsed = NovelWorkspaceMarkdown.parseFile(content)
            NovelMarkdownDraftUi(
                path = path,
                title = parsed.fields["title"]?.takeIf { it.isNotBlank() }
                    ?: NovelWorkspacePaths.fileNameTitle(path),
                excerpt = parsed.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(80),
            )
        }
    }

    /** Author collects a draft into the manuscript and commits (the click is the approval). */
    fun collectDraft(draftPath: String, target: NovelWorkspaceCollectTarget, title: String? = null) {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = "当前分支仍被代笔批次占用，请先让批次完成或取消后再收录正文")
            return
        }
        if (target !is NovelWorkspaceCollectTarget.ReplaceChapter) {
            val currentLedger = NovelWorkspaceLedger.load(directory)
            if (NovelWorkspaceLedger.isPlotStale(currentLedger, slug)) {
                _state.value = _state.value.copy(errorMessage = "剧情落后于正文。请切到“讨论”，发送“根据最新正文同步 plot/current.md”，并批准剧情修改后再收录")
                return
            }
            if (NovelWorkspaceUnresolvedStore.entryFor(directory, slug) != null) {
                _state.value = _state.value.copy(errorMessage = "存在未解决的中间章修改，请先处理后再收录新正文")
                return
            }
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                runtime.collectDraft(
                    projectDirectory = directory,
                    branchId = branch,
                    branchSlug = slug,
                    draftPath = draftPath,
                    target = target,
                    chapterTitle = title,
                )
                val store = NovelWorkspaceStore(directory)
                _state.value = _state.value.copy(
                    chapters = loadChapters(store),
                    drafts = loadDrafts(store),
                    plotStale = NovelWorkspaceLedger.isPlotStale(NovelWorkspaceLedger.load(directory), slug),
                    unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
                    canUndo = runtime.canUndo(directory),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message ?: "收录失败")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** Load the active ghostwrite job + ledger-derived progress into the UI state.
     *  When nothing is active, the latest failed job is shown instead so a dead
     *  batch leaves a visible reason instead of silently disappearing. */
    fun refreshGhostwrite() {
        val directory = projectDirectory ?: return
        val slug = branchSlug ?: return
        ghostwriteRefreshJob?.cancel()
        ghostwriteRefreshJob = viewModelScope.launch {
            val refresh = withContext(Dispatchers.IO) {
                val store = NovelWorkspaceStore(directory)
                val ledger = NovelWorkspaceLedger.load(directory)
                val job = (NovelWorkspaceGhostwriteJobs.listActive(directory)
                    .firstOrNull { it.branchSlug == slug }
                    // Keep the newest failed batch visible so the author can retry it.
                    ?: NovelWorkspaceGhostwriteJobs.latestFailed(directory, slug))
                    ?.let { job ->
                        NovelMarkdownGhostwriteUi(
                            jobId = job.id,
                            executionId = job.executionKey,
                            target = job.targetChapterCount,
                            written = NovelWorkspaceGhostwriteJobs.progress(job, store),
                            status = job.status,
                            reason = job.reason,
                        )
                    }
                NovelGhostwriteRefresh(
                    job = job,
                    chapters = loadChapters(store),
                    drafts = loadDrafts(store),
                    plotStale = NovelWorkspaceLedger.isPlotStale(ledger, slug),
                    unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
                    canUndo = runtime.canUndo(directory),
                )
            }
            _state.value = _state.value.copy(
                ghostwriteJob = refresh.job,
                chapters = refresh.chapters,
                drafts = refresh.drafts,
                plotStale = refresh.plotStale,
                unresolvedFromOrdinal = refresh.unresolvedFromOrdinal,
                canUndo = refresh.canUndo,
            )
        }
    }

    fun startGhostwriteBatch(targetChapterCount: Int) {
        val directory = projectDirectory ?: return
        val slug = branchSlug ?: return
        if (targetChapterCount <= 0) {
            _state.value = _state.value.copy(errorMessage = "章数需大于 0")
            return
        }
        val settings = settingsAggregator.settingsFlow.value
        if (settings.init) {
            _state.value = _state.value.copy(errorMessage = "模型设置仍在加载，请稍后重试")
            return
        }
        if (resolveWritingModel(settings) == null) {
            _state.value = _state.value.copy(errorMessage = "尚未配置聊天模型，请关闭代笔面板并在顶部选择模型")
            return
        }
        // Only an active (running/paused) batch blocks a new one; a visible failed
        // job is a read-only leftover and is dismissed by starting fresh.
        if (_state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED
        ) {
            _state.value = _state.value.copy(errorMessage = "已有代笔批次，请先继续完成或取消后再开新批次")
            return
        }
        // D-D: never start a batch while a middle-chapter edit is unresolved.
        if (NovelWorkspaceUnresolvedStore.entryFor(directory, slug) != null) {
            _state.value = _state.value.copy(
                errorMessage = "存在未解决的中间章修改，请先处理（确认无碍/重写后章）再代笔",
            )
            return
        }
        val ledger = NovelWorkspaceLedger.load(directory)
        if (NovelWorkspaceLedger.isPlotStale(ledger, slug)) {
            _state.value = _state.value.copy(
                plotStale = true,
                errorMessage = "剧情落后于正文。请切到“讨论”，发送“根据最新正文同步 plot/current.md”，并批准剧情修改后再代笔",
            )
            return
        }
        runCatching { ghostwriteController.startBatch(directory, projectId, slug, targetChapterCount) }
            .onSuccess { job ->
                _state.value = _state.value.copy(
                    errorMessage = null,
                    ghostwriteJob = NovelMarkdownGhostwriteUi(
                        jobId = job.id,
                        executionId = job.executionKey,
                        target = job.targetChapterCount,
                        written = 0,
                        status = job.status,
                    ),
                )
            }
            .onFailure {
                _state.value = _state.value.copy(errorMessage = it.message ?: "代笔启动失败")
                refreshGhostwrite()
            }
    }

    fun pauseGhostwriteBatch() {
        val directory = projectDirectory ?: return
        val current = _state.value.ghostwriteJob ?: return
        runCatching { ghostwriteController.pause(directory, current.jobId, current.executionId) }
            .onFailure { _state.value = _state.value.copy(errorMessage = it.message) }
        refreshGhostwrite()
    }

    fun resumeGhostwriteBatch() {
        val directory = projectDirectory ?: return
        val current = _state.value.ghostwriteJob ?: return
        runCatching {
            ghostwriteController.resume(directory, projectId, current.jobId, current.executionId)
        }.onSuccess { resumed ->
            if (resumed != null) {
                _state.value = _state.value.copy(
                    errorMessage = null,
                    ghostwriteJob = current.copy(
                        executionId = resumed.executionKey,
                        status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
                        reason = null,
                    ),
                )
            }
        }.onFailure { _state.value = _state.value.copy(errorMessage = it.message) }
        refreshGhostwrite()
    }

    fun retryFailedGhostwriteBatch() {
        val directory = projectDirectory ?: return
        val current = _state.value.ghostwriteJob ?: return
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_FAILED) return
        runCatching {
            ghostwriteController.retryFailed(directory, projectId, current.jobId, current.executionId)
        }.onSuccess { resumed ->
            _state.value = _state.value.copy(
                errorMessage = null,
                ghostwriteJob = current.copy(
                    executionId = resumed.executionKey,
                    status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
                    reason = null,
                ),
            )
        }.onFailure {
            _state.value = _state.value.copy(errorMessage = it.message ?: "代笔继续失败")
            refreshGhostwrite()
        }
    }

    fun cancelGhostwriteBatch() {
        val directory = projectDirectory ?: return
        val current = _state.value.ghostwriteJob ?: return
        runCatching { ghostwriteController.cancel(directory, current.jobId, current.executionId) }
            .onFailure { _state.value = _state.value.copy(errorMessage = it.message) }
        refreshGhostwrite()
    }

    /** Drop the read-only failed-batch card so the start form comes back. */
    fun dismissGhostwriteFailure() {
        val directory = projectDirectory ?: return
        val jobId = _state.value.ghostwriteJob?.jobId ?: return
        if (_state.value.ghostwriteJob?.status != NovelWorkspaceGhostwriteJob.STATUS_FAILED) return
        viewModelScope.launch(Dispatchers.IO) {
            NovelWorkspaceGhostwriteJobs.delete(directory, jobId)
            _state.value = _state.value.copy(ghostwriteJob = null)
        }
    }

    /** Per-project writing model wins over the global chat model; invalid ids fall through. */
    @OptIn(ExperimentalUuidApi::class)
    private fun resolveWritingModel(settings: app.amber.core.settings.Settings): app.amber.ai.provider.Model? {
        val override = projectDirectory?.let { NovelWorkspaceProjectSettingsStore.load(it).writingModelId }
        if (override != null) {
            runCatching { Uuid.parse(override) }.getOrNull()?.let { uuid ->
                settings.findModelById(uuid)?.let { return it }
            }
        }
        return settings.getCurrentChatModel()
    }

    /** Review model: project review override → writing override → global chat model. */
    @OptIn(ExperimentalUuidApi::class)
    private fun resolveReviewModel(settings: app.amber.core.settings.Settings): app.amber.ai.provider.Model? {
        val reviewOverride = projectDirectory?.let { NovelWorkspaceProjectSettingsStore.load(it).reviewModelId }
        if (reviewOverride != null) {
            runCatching { Uuid.parse(reviewOverride) }.getOrNull()?.let { uuid ->
                settings.findModelById(uuid)?.let { return it }
            }
        }
        return resolveWritingModel(settings)
    }

    /** Layer-3 consistency review: read the newest chapter against the constraint brief.
     *  Manual, on-demand (no auto-run) — keeps token cost in the author's hands. */
    fun runConsistencyCheck() {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.consistencyChecking) return
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveReviewModel(settings)
        if (model == null) {
            _state.value = _state.value.copy(errorMessage = "尚未配置审稿模型")
            return
        }
        _state.value = _state.value.copy(
            consistencyChecking = true,
            consistencyReport = null,
            errorMessage = null,
            // Consistency check is a read-only turn, but it still drives the agent loop;
            // hold busy so a second turn can't interleave and cross-talk _state.
            busy = true,
        )
        viewModelScope.launch {
            var report = ""
            turnLauncher.launch(
                NovelWorkspaceRuntime.TurnRequest(
                    projectDirectory = directory,
                    branchId = branch,
                    branchSlug = slug,
                    userText = "请对最新一章做一致性检查。",
                    systemPrompt = NovelWorkspacePrompts.consistencyReview(),
                    settings = settings,
                    model = model,
                    injection = _state.value.injection,
                ),
                runtime,
            ).events.collect { event ->
                when (event) {
                    is NovelWorkspaceRuntime.TurnEvent.Delta -> report += event.text
                    is NovelWorkspaceRuntime.TurnEvent.Failed ->
                        _state.value = _state.value.copy(errorMessage = event.message)
                    else -> Unit
                }
            }
            _state.value = _state.value.copy(
                consistencyChecking = false,
                consistencyReport = report.ifBlank { null },
                busy = false,
                canUndo = runtime.canUndo(directory),
            )
        }
    }

    fun dismissConsistencyReport() {
        _state.value = _state.value.copy(consistencyReport = null)
    }

    /** Set or clear the per-project writing model override. */
    fun setWritingModel(modelId: String?) {
        val directory = projectDirectory ?: return
        runCatching {
            val current = NovelWorkspaceProjectSettingsStore.load(directory)
            NovelWorkspaceProjectSettingsStore.save(
                current.copy(writingModelId = modelId),
                directory,
            )
            _state.value = _state.value.copy(writingModelId = modelId)
        }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "保存模型设置失败") }
    }

    fun currentWritingModelId(): String? =
        projectDirectory?.let { NovelWorkspaceProjectSettingsStore.load(it).writingModelId }

    // ── Ghostwrite panel backing: author-editable control files + injected-brief preview.

    private fun planPath(): String? =
        branchSlug?.let { NovelWorkspacePaths.branchPrefix(it) + "/plan/this-chapter.md" }

    private fun upcomingPath(): String? =
        branchSlug?.let { NovelWorkspacePaths.branchPrefix(it) + "/plan/upcoming.md" }

    fun readChapterPlan(): String =
        pathRead(planPath()) ?: ""

    fun saveChapterPlan(body: String) = pathWrite(planPath(), body)

    fun readUpcomingArc(): String = pathRead(upcomingPath()) ?: ""

    fun saveUpcomingArc(body: String) = pathWrite(upcomingPath(), body)

    /** Writing preference = the setting/writing card (first file; created on save). */
    fun readWritingPreference(): String {
        val directory = projectDirectory ?: return ""
        val store = NovelWorkspaceStore(directory)
        val first = store.list(NovelWorkspacePaths.SETTING_DIR + "/writing").firstOrNull()
        return pathRead(first) ?: ""
    }

    fun saveWritingPreference(body: String) {
        val directory = projectDirectory ?: return
        val store = NovelWorkspaceStore(directory)
        val existing = store.list(NovelWorkspacePaths.SETTING_DIR + "/writing").firstOrNull()
        pathWrite(existing ?: NovelWorkspacePaths.SETTING_DIR + "/writing/写作要求.md", body)
    }

    /** What the host will inject as constraints next turn (ghostwrite panel preview). */
    fun briefPreview(): String {
        val directory = projectDirectory ?: return ""
        val slug = branchSlug ?: return ""
        return runCatching {
            app.amber.feature.novelworkspace.NovelWorkspaceContextAssembler.assemble(
                NovelWorkspaceStore(directory), slug,
                flags = _state.value.injection,
            )
        }.getOrDefault("")
    }

    private fun pathRead(path: String?): String? {
        val directory = projectDirectory ?: return null
        if (path == null) return null
        return runCatching {
            NovelWorkspaceMarkdown.parseFile(NovelWorkspaceStore(directory).read(path) ?: "").body
        }.getOrNull()
    }

    private fun pathWrite(path: String?, body: String) {
        val directory = projectDirectory ?: return
        if (path == null) return
        runCatching {
            NovelWorkspaceStore(directory).write(path, body)
        }.onFailure {
            _state.value = _state.value.copy(errorMessage = it.message ?: "保存失败")
        }
    }

    /** Toggle one injected-brief section; persisted per project and applied to every turn. */
    fun setInjectionFlags(flags: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags) {
        val directory = projectDirectory ?: return
        runCatching {
            val current = NovelWorkspaceProjectSettingsStore.load(directory)
            NovelWorkspaceProjectSettingsStore.save(
                current.copy(injection = flags),
                directory,
            )
            _state.value = _state.value.copy(injection = flags)
        }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "保存注入设置失败") }
    }

    /** Ghostwrite panel: model drafts the next chapter's plan into plan/this-chapter.md. */
    fun generateChapterPlan() {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = "当前分支仍被代笔批次占用，请先让批次完成或取消后再生成计划")
            return
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings) ?: run {
            _state.value = _state.value.copy(errorMessage = "尚未配置聊天模型")
            return
        }
        val expectedPlanPath = NovelWorkspacePaths.branchPrefix(slug) + "/plan/this-chapter.md"
        val planBefore = NovelWorkspaceStore(directory).read(expectedPlanPath)
        val headBefore = NovelWorkspaceLedger.load(directory).heads[branch]
        _state.value = _state.value.copy(busy = true, errorMessage = null, streamingText = "", toolActivity = null)
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            try {
                turnLauncher.launch(
                    NovelWorkspaceRuntime.TurnRequest(
                        projectDirectory = directory,
                        branchId = branch,
                        branchSlug = slug,
                        userText = "请根据前文拟定下一章计划并写入计划文件。",
                        systemPrompt = NovelWorkspacePrompts.planDraft(),
                        settings = settings,
                        model = model,
                        injection = _state.value.injection,
                    ),
                    runtime,
                ).events.collect { event ->
                    when (event) {
                        is NovelWorkspaceRuntime.TurnEvent.Completed -> {
                            val producedPlan = withContext(Dispatchers.IO) {
                                val headAfter = NovelWorkspaceLedger.load(directory).heads[branch]
                                val planAfter = NovelWorkspaceStore(directory).read(expectedPlanPath)
                                val planBody = planAfter
                                    ?.let(NovelWorkspaceMarkdown::parseFile)
                                    ?.body
                                    .orEmpty()
                                headAfter != headBefore && planAfter != planBefore && planBody.isNotBlank()
                            }
                            _state.value = _state.value.copy(
                                busy = false,
                                streamingText = "",
                                toolActivity = null,
                                errorMessage = if (producedPlan) {
                                    null
                                } else {
                                    "本轮没有写入下一章计划。请重试；若持续失败，请换用支持工具调用的模型。"
                                },
                                // Reload only when the expected branch file was durably committed.
                                planAutoTick = if (producedPlan) {
                                    _state.value.planAutoTick + 1
                                } else {
                                    _state.value.planAutoTick
                                },
                            )
                        }
                        is NovelWorkspaceRuntime.TurnEvent.Failed -> {
                            _state.value = _state.value.copy(
                                busy = false,
                                streamingText = "",
                                toolActivity = null,
                                errorMessage = event.message,
                            )
                        }
                        else -> Unit
                    }
                }
            } finally {
                if (_state.value.busy) {
                    _state.value = _state.value.copy(busy = false, streamingText = "", toolActivity = null)
                }
            }
        }
    }

    private fun appendSessionMessage(directory: File, branch: String, message: NovelWorkspaceSessionMessage) {
        val sessions = NovelWorkspaceSessions.load(directory)
        val existing = sessions.sessions[branch].orEmpty()
        NovelWorkspaceSessions.save(
            sessions.copy(sessions = sessions.sessions + (branch to existing + message)),
            directory,
        )
    }

    private fun toolLabel(toolName: String): String = when (toolName) {
        "novel_workspace_list" -> "正在查看目录…"
        "novel_workspace_read" -> "正在读取文件…"
        "novel_workspace_grep" -> "正在搜索前文…"
        "novel_workspace_status" -> "正在检查工作区状态…"
        "novel_workspace_write" -> "正在写入…"
        else -> "正在调用工具…"
    }
}
