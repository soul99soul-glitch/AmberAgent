package app.amber.feature.ui.pages.novel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.agent.R
import app.amber.ai.core.MessageRole
import app.amber.core.ai.RunKernel
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.appLocale
import app.amber.feature.novelworkspace.NovelWorkspaceProjectSettingsStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import app.amber.feature.novel.workspace.NovelTurnLauncher
import app.amber.feature.novel.workspace.NovelWorkspaceCollectTarget
import app.amber.feature.novel.workspace.NovelWorkspacePrompts
import app.amber.feature.novel.workspace.NovelWorkspaceRuntime
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteController
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteCoordinator
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceCatalog
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceLedgerStore
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceProjectTitle
import app.amber.feature.novelworkspace.NovelWorkspaceSessionMessage
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import app.amber.feature.novelworkspace.NovelWorkspaceSlug
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
    /** 活跃分支 slug（.amber/branch.json 标记，缺失回退 manifest.mainBranch）。 */
    val branchSlug: String? = null,
    /** 分支列表（ledger heads + branches/ 目录，标当前），分支 sheet 数据源。 */
    val branches: List<NovelWorkspaceBranches.NovelWorkspaceBranchInfo> = emptyList(),
    val messages: List<NovelMarkdownMessageUi> = emptyList(),
    val chapters: List<NovelMarkdownChapterUi> = emptyList(),
    val drafts: List<NovelMarkdownDraftUi> = emptyList(),
    /** 设定 tab：设定文件分组 + 伏笔 + 决定（每次 commit 后与切分支后刷新）。 */
    val catalog: NovelWorkspaceCatalog.NovelWorkspaceCatalogData? = null,
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
    /** Per-project review model override (null = follow the writing model). */
    val reviewModelId: String? = null,
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
    val branchSlug: String,
    val target: Int,
    val written: Int,
    /** First chapter ordinal owned by this batch (used by polish ranges). */
    val startOrdinal: Int,
    val status: String,
    /** Durable stage for the current chapter (writing/reviewing/rewriting/committing/planning). */
    val stage: NovelWorkspaceGhostwriteStage = NovelWorkspaceGhostwriteStage.Idle,
    /** 1-based chapter ordinal currently owned by the batch. */
    val currentChapterOrdinal: Int = 0,
    /** Targeted rewrite attempt for the current candidate (0..2). */
    val rewriteAttempt: Int = 0,
    /** Terminal-failure reason surfaced when status == failed. */
    val reason: String? = null,
    /** Batch kind: 代笔 writes new chapters, 润色 re-proses an existing range. */
    val mode: NovelWorkspaceGhostwriteMode = NovelWorkspaceGhostwriteMode.Write,
)

private data class NovelGhostwriteRefresh(
    val job: NovelMarkdownGhostwriteUi?,
    val chapters: List<NovelMarkdownChapterUi>,
    val drafts: List<NovelMarkdownDraftUi>,
    val catalog: NovelWorkspaceCatalog.NovelWorkspaceCatalogData?,
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
    private val context: Context,
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
            reloadState()
        }
    }

    private fun reloadState() {
        runCatching {
            if (!repository.exists(projectId)) {
                _state.value = _state.value.copy(loading = false, exists = false)
                return@runCatching
            }
            val directory = repository.projectDirectory(projectId)
            val store = NovelWorkspaceStore(directory)
            val ledger = NovelWorkspaceLedger.load(directory)
            projectDirectory = directory
            // 活跃分支：.amber/branch.json 标记优先，缺失回退 manifest.mainBranch。
            val slug = NovelWorkspaceBranches.activeSlug(directory)
            branchId = NovelWorkspaceLedger.branchId(store, ledger, slug)
            branchSlug = slug
            _state.value = _state.value.copy(
                loading = false,
                exists = true,
                title = NovelWorkspaceProjectTitle.read(store),
                branchSlug = slug,
                branches = NovelWorkspaceBranches.list(directory, slug),
                messages = loadMessages(directory),
                chapters = loadChapters(store),
                catalog = loadCatalog(directory, ledger, slug),
                proposals = proposalsForThisProject(),
                drafts = loadDrafts(store),
                plotStale = NovelWorkspaceLedger.isPlotStale(store, ledger, slug),
                unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore
                    .entryFor(directory, slug)?.fromOrdinal,
                writingModelId = NovelWorkspaceProjectSettingsStore.load(directory).writingModelId,
                reviewModelId = NovelWorkspaceProjectSettingsStore.load(directory).reviewModelId,
                injection = NovelWorkspaceProjectSettingsStore.load(directory).injection
                    ?: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
                canUndo = runtime.canUndo(directory, slug),
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                loading = false,
                errorMessage = error.message ?: text(R.string.error_title_operation),
            )
        }
        refreshGhostwrite()
    }

    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return false
        val directory = projectDirectory
        val branch = branchId
        val slug = branchSlug
        if (directory == null || branch == null || slug == null) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_loading))
            return false
        }
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return false
        }
        if (_state.value.composerMode == NovelMarkdownComposerMode.WriteProse) {
            if (_state.value.plotStale) {
                _state.value = _state.value.copy(
                    errorMessage = text(
                        R.string.novel_ghostwrite_error_stale_plot,
                        text(R.string.novel_ghostwrite_task_write),
                    ),
                )
                return false
            }
            if (_state.value.unresolvedFromOrdinal != null) {
                _state.value = _state.value.copy(
                    errorMessage = text(
                        R.string.novel_ghostwrite_error_unresolved_edits,
                        text(R.string.novel_ghostwrite_task_write),
                    ),
                )
                return false
            }
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings)
        if (model == null) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
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
                        isBlankBook -> NovelWorkspacePrompts.quickStart(
                            genre = "",
                            coreIdea = trimmed,
                            locale = context.appLocale(),
                        )
                        _state.value.composerMode == NovelMarkdownComposerMode.WriteProse ->
                            NovelWorkspacePrompts.proseDraft(
                                NovelWorkspacePrompts.ProseGranularity.CONTINUATION,
                                locale = context.appLocale(),
                            )
                        else -> NovelWorkspacePrompts.discussion(locale = context.appLocale())
                    },
                    settings = settings,
                    model = model,
                    fallbackErrorMessage = text(R.string.error_title_operation),
                    locale = context.appLocale(),
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
                                text(R.string.novel_no_setting_files)
                            } else {
                                null
                            },
                            messages = loadMessages(directory),
                            chapters = loadChapters(store),
                            drafts = loadDrafts(store),
                            proposals = proposalsForThisProject(),
                            plotStale = NovelWorkspaceLedger.isPlotStale(
                                store,
                                NovelWorkspaceLedger.load(directory),
                                slug,
                            ),
                            unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore
                                .entryFor(directory, slug)?.fromOrdinal,
                            catalog = loadCatalog(directory, slug),
                            canUndo = runtime.canUndo(directory, slug),
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
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings) ?: run {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
            return
        }
        _state.value = _state.value.copy(
            busy = true,
            errorMessage = null,
            streamingText = "",
            reasoningText = "",
            toolActivity = null,
        )
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            var finalText = ""
            try {
                turnLauncher.launch(
                    NovelWorkspaceRuntime.TurnRequest(
                        projectDirectory = directory,
                        branchId = branch,
                        branchSlug = slug,
                        userText = localizedPromptText(
                            chinese = "请重写第 $fromOrdinal 章起的受影响章节，使其与前文一致。",
                            english = "Rewrite the affected chapters from chapter $fromOrdinal so they remain consistent with the preceding story.",
                        ),
                        systemPrompt = NovelWorkspacePrompts.rewriteLaterChapters(
                            fromOrdinal,
                            locale = context.appLocale(),
                        ),
                        settings = settings,
                        model = model,
                        fallbackErrorMessage = text(R.string.error_title_operation),
                        locale = context.appLocale(),
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
                            // Completed 刷新集与 send() 对齐（J1）：重写轮落盘的提案批准前后，
                            // 剧情/设定/undo 状态都要回到磁盘真相。
                            _state.value = _state.value.copy(
                                busy = false,
                                streamingText = "",
                                reasoningText = "",
                                toolActivity = null,
                                chapters = loadChapters(store),
                                drafts = loadDrafts(store),
                                proposals = proposalsForThisProject(),
                                plotStale = NovelWorkspaceLedger.isPlotStale(
                                    store,
                                    NovelWorkspaceLedger.load(directory),
                                    slug,
                                ),
                                unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore
                                    .entryFor(directory, slug)?.fromOrdinal,
                                catalog = loadCatalog(directory, slug),
                                canUndo = runtime.canUndo(directory, slug),
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
            } finally {
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
    }

    /**
     * 重写本章（Regenerate）：让模型产出整章替换稿。chapters/ 是受保护路径，模型的写入
     * 经 novel_workspace_write 缓冲为提案，走现有审批卡（MarkdownProposalCard）确认/拒绝，
     * 不新建审批机制；该章若处于中间章未决（unresolved）状态照常允许重写，未决门既有
     * 语义自会处理，这里不特判。
     */
    fun rewriteChapter(ordinal: Int): Boolean {
        val directory = projectDirectory ?: return false
        val branch = branchId ?: return false
        val slug = branchSlug ?: return false
        if (_state.value.busy) return false
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return false
        }
        val chapter = _state.value.chapters.firstOrNull { it.ordinal == ordinal } ?: return false
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings) ?: run {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
            return false
        }
        val store = NovelWorkspaceStore(directory)
        val currentBody = store.read(chapter.path)
            ?.let { NovelWorkspaceMarkdown.parseFile(it).body }
            .orEmpty()
        _state.value = _state.value.copy(
            busy = true,
            errorMessage = null,
            streamingText = "",
            reasoningText = "",
            toolActivity = null,
        )
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            var finalText = ""
            try {
                turnLauncher.launch(
                    NovelWorkspaceRuntime.TurnRequest(
                        projectDirectory = directory,
                        branchId = branch,
                        branchSlug = slug,
                        userText = localizedPromptText(
                            chinese = "请重写第 $ordinal 章「${chapter.title}」，把整章替换稿写回 ${chapter.path}。",
                            english = "Rewrite chapter $ordinal (\"${chapter.title}\") as a complete replacement and write it back to ${chapter.path}.",
                        ),
                        systemPrompt = NovelWorkspacePrompts.regenerateChapter(
                            chapterOrdinal = ordinal,
                            chapterTitle = chapter.title,
                            chapterPath = chapter.path,
                            chapterBody = currentBody,
                            plan = pathRead(planPath()),
                            writingPreference = readWritingPreference(),
                            locale = context.appLocale(),
                        ),
                        settings = settings,
                        model = model,
                        fallbackErrorMessage = text(R.string.error_title_operation),
                        locale = context.appLocale(),
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
                            val refreshed = NovelWorkspaceStore(directory)
                            // Completed 刷新集与 send() 对齐（J1）。
                            _state.value = _state.value.copy(
                                busy = false,
                                streamingText = "",
                                reasoningText = "",
                                toolActivity = null,
                                chapters = loadChapters(refreshed),
                                drafts = loadDrafts(refreshed),
                                proposals = proposalsForThisProject(),
                                plotStale = NovelWorkspaceLedger.isPlotStale(
                                    refreshed,
                                    NovelWorkspaceLedger.load(directory),
                                    slug,
                                ),
                                unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore
                                    .entryFor(directory, slug)?.fromOrdinal,
                                catalog = loadCatalog(directory, slug),
                                canUndo = runtime.canUndo(directory, slug),
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
            } finally {
                // Stop/cancel mid-turn: reset the busy chrome like send() does.
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

    /**
     * 创作快捷动作：角色提案。人物卡写入 setting/characters/（自由写路径，novel_workspace_write
     * 直存、无需审批）；目标文件名由宿主按现有 slug 规则净化并对既有卡片去重。
     */
    fun proposeCharacter(name: String, sketch: String) {
        val trimmedName = name.trim()
        val trimmedSketch = sketch.trim()
        if (trimmedName.isEmpty()) return
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings) ?: run {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
            return
        }
        val store = NovelWorkspaceStore(directory)
        val charactersDir = NovelWorkspacePaths.SETTING_DIR + "/characters"
        val existing = runCatching { store.list(charactersDir) }.getOrDefault(emptyList())
        val leaf = NovelWorkspaceSlug.reservedPath(
            preferred = NovelWorkspaceSlug.slug(trimmedName).ifEmpty { "character" } + ".md",
            used = existing.map { it.substringAfterLast('/') }.toMutableSet(),
            fallback = "character",
        )
        val targetPath = "$charactersDir/$leaf"
        appendSessionMessage(directory, branch, NovelWorkspaceSessionMessage(
            id = UUID.randomUUID().toString().uppercase(),
            role = "user",
            kind = "userInput",
            content = localizedPromptText(
                chinese = "提案角色「$trimmedName」：$trimmedSketch",
                english = "Propose character \"$trimmedName\": $trimmedSketch",
            ),
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
            var finalText = ""
            try {
                turnLauncher.launch(
                    NovelWorkspaceRuntime.TurnRequest(
                        projectDirectory = directory,
                        branchId = branch,
                        branchSlug = slug,
                        userText = localizedPromptText(
                            chinese = "请提案新角色「$trimmedName」：$trimmedSketch",
                            english = "Propose a new character \"$trimmedName\": $trimmedSketch",
                        ),
                        systemPrompt = NovelWorkspacePrompts.characterProposal(
                            characterName = trimmedName,
                            sketch = trimmedSketch,
                            existingCharacters = existing,
                            targetPath = targetPath,
                            locale = context.appLocale(),
                        ),
                        settings = settings,
                        model = model,
                        fallbackErrorMessage = text(R.string.error_title_operation),
                        locale = context.appLocale(),
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
                            if (event.finalText.isNotBlank()) {
                                appendSessionMessage(directory, branch, NovelWorkspaceSessionMessage(
                                    id = UUID.randomUUID().toString().uppercase(),
                                    role = "assistant",
                                    kind = "discussion",
                                    content = event.finalText,
                                    createdAt = Instant.now(),
                                ))
                            }
                            // Completed 刷新集与 send() 对齐（J1）：角色卡是自由写路径、
                            // 本轮直存落盘，设定 tab/undo/剧情门必须立即反映，否则新角色
                            // 卡要等重进页面才可见。
                            _state.value = _state.value.copy(
                                busy = false,
                                streamingText = "",
                                reasoningText = "",
                                toolActivity = null,
                                messages = loadMessages(directory),
                                chapters = loadChapters(store),
                                drafts = loadDrafts(store),
                                proposals = proposalsForThisProject(),
                                plotStale = NovelWorkspaceLedger.isPlotStale(
                                    store,
                                    NovelWorkspaceLedger.load(directory),
                                    slug,
                                ),
                                unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore
                                    .entryFor(directory, slug)?.fromOrdinal,
                                catalog = loadCatalog(directory, slug),
                                canUndo = runtime.canUndo(directory, slug),
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
            } finally {
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
    }

    fun approve(proposalId: String) {
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
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
                        NovelWorkspaceLedger.isPlotStale(
                            store,
                            NovelWorkspaceLedger.load(directory),
                            slug,
                        )
                    } else {
                        false
                    },
                    unresolvedFromOrdinal = slug?.let {
                        NovelWorkspaceUnresolvedStore.entryFor(directory, it)?.fromOrdinal
                    },
                    catalog = slug?.let { loadCatalog(directory, it) },
                    canUndo = slug?.let { runtime.canUndo(directory, it) } ?: false,
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    errorMessage = localizedRuntimeError(error, R.string.error_title_operation),
                )
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
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        runCatching {
            NovelWorkspaceUnresolvedStore.clear(directory, slug)
            _state.value = _state.value.copy(unresolvedFromOrdinal = null)
        }.onFailure { error ->
            _state.value = _state.value.copy(errorMessage = error.message ?: text(R.string.error_title_operation))
        }
    }

    /** Author manual chapter edit; saving commits it (middle edits raise the unresolved gate). */
    fun saveChapterEdit(path: String, title: String, body: String, onSaved: () -> Unit) {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
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
                    catalog = loadCatalog(directory, slug),
                    plotStale = NovelWorkspaceLedger.isPlotStale(
                        store,
                        NovelWorkspaceLedger.load(directory),
                        slug,
                    ),
                    unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
                    canUndo = runtime.canUndo(directory, slug),
                )
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = localizedRuntimeError(error, R.string.workspace_save_failed),
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** 撤销最近一笔 canon commit (single level, branch-bound). */
    fun undoLast() {
        val directory = projectDirectory ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        viewModelScope.launch {
            val undone = withContext(Dispatchers.IO) { runtime.undoLast(directory, slug) }
            if (!undone) {
                _state.value = _state.value.copy(errorMessage = text(R.string.novel_unknown_reason))
                return@launch
            }
            val store = NovelWorkspaceStore(directory)
            _state.value = _state.value.copy(
                chapters = loadChapters(store),
                drafts = loadDrafts(store),
                catalog = loadCatalog(directory, slug),
                proposals = proposalsForThisProject(),
                plotStale = NovelWorkspaceLedger.isPlotStale(
                    store,
                    NovelWorkspaceLedger.load(directory),
                    slug,
                ),
                unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
                canUndo = runtime.canUndo(directory, slug),
            )
        }
    }

    fun readChapter(path: String): String? = readFileBody(path)

    /** Body of any book file (setting cards, foreshadowing nodes) for the 设定 tab editor. */
    fun readFileBody(path: String): String? {
        val directory = projectDirectory ?: return null
        return runCatching {
            val store = NovelWorkspaceStore(directory)
            val content = store.read(path) ?: return@runCatching null
            NovelWorkspaceMarkdown.parseFile(content).body
        }.getOrNull()
    }

    // ── 多分支：新建 / 切换（branch sheet）────────────────────────────

    /** 从当前活跃分支分叉出新分支；成功后刷新分支列表（不切换）。 */
    fun createBranch(name: String) {
        val directory = projectDirectory ?: return
        val current = branchSlug ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                withContext(Dispatchers.IO) {
                    NovelWorkspaceBranches.createBranch(
                        directory,
                        current,
                        name,
                        locale = context.appLocale(),
                    )
                }
                val slug = NovelWorkspaceBranches.activeSlug(directory)
                _state.value = _state.value.copy(
                    errorMessage = null,
                    branches = NovelWorkspaceBranches.list(directory, slug),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message ?: text(R.string.error_title_operation))
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /**
     * 切换活跃分支：批次进行中被存储层拒绝；成功后清空本项目在途提案/草稿卡（内存态
     * 属于切换前的分支视图）并整页重载（章节/剧情门/undo/会话/注入简报/设定 tab）。
     */
    fun switchBranch(slug: String) {
        val directory = projectDirectory ?: return
        if (_state.value.busy) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_in_progress))
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                withContext(Dispatchers.IO) {
                    NovelWorkspaceBranches.switchBranch(
                        directory,
                        slug,
                        locale = context.appLocale(),
                    )
                }
                // 提案/草稿卡是上一分支视图的内存态：随切换整体清空，防止跨分支批准。
                runtime.pendingProposals.value
                    .filter { it.projectDirectory == directory }
                    .forEach { runtime.reject(it.id) }
                _state.value = _state.value.copy(
                    streamingText = "",
                    reasoningText = "",
                    toolActivity = null,
                    consistencyReport = null,
                )
                reloadState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = error.message ?: text(R.string.error_title_operation),
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    // ── 设定 tab：保存（走既有直写+commit+undo 惯例）─────────────────

    /** 保存设定卡/伏笔节点的正文修改：宿主手改 + 「手改」commit + undo 记录。 */
    fun saveFileEdit(path: String, body: String, onSaved: () -> Unit) {
        commitFileEdit(path, body, onSaved)
    }

    /**
     * 写作偏好 = setting/writing 卡（首个文件；首次保存创建）。与设定 tab 的
     * saveFileEdit 走同一提交口径（手改 commit + undo 记录，J7）：面板行为不变
     * （保存即落盘），但与双入口另一侧一样可撤销、进账本。批次进行中面板本就
     * 禁用（branchOwned），runtime 的 owner gate 是第二道。
     */
    fun saveWritingPreference(body: String, onSaved: () -> Unit) {
        val directory = projectDirectory ?: return
        val store = NovelWorkspaceStore(directory)
        val target = store.list(NovelWorkspacePaths.SETTING_DIR + "/writing").firstOrNull()
            ?: NovelWorkspacePaths.SETTING_DIR + "/writing/写作要求.md"
        commitFileEdit(target, body, onSaved)
    }

    /** saveFileEdit / saveWritingPreference 共享的宿主手改提交路径（含刷新与 undo）。 */
    private fun commitFileEdit(path: String, body: String, onSaved: () -> Unit) {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                runtime.saveFileEdit(
                    projectDirectory = directory,
                    branchId = branch,
                    branchSlug = slug,
                    path = path,
                    body = body,
                )
                val store = NovelWorkspaceStore(directory)
                _state.value = _state.value.copy(
                    catalog = loadCatalog(directory, slug),
                    plotStale = NovelWorkspaceLedger.isPlotStale(
                        store,
                        NovelWorkspaceLedger.load(directory),
                        slug,
                    ),
                    unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
                    canUndo = runtime.canUndo(directory, slug),
                )
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = localizedRuntimeError(error, R.string.workspace_save_failed),
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private fun loadCatalog(directory: File, slug: String): NovelWorkspaceCatalog.NovelWorkspaceCatalogData =
        loadCatalog(directory, NovelWorkspaceLedger.load(directory), slug)

    private fun loadCatalog(
        directory: File,
        ledger: NovelWorkspaceLedgerStore,
        slug: String,
    ): NovelWorkspaceCatalog.NovelWorkspaceCatalogData = runCatching {
        NovelWorkspaceCatalog.load(NovelWorkspaceStore(directory), ledger, slug)
    }.getOrDefault(NovelWorkspaceCatalog.NovelWorkspaceCatalogData(emptyList(), emptyList(), emptyList()))

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
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_FAILED
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
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        if (target !is NovelWorkspaceCollectTarget.ReplaceChapter) {
            val currentLedger = NovelWorkspaceLedger.load(directory)
            if (NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(directory), currentLedger, slug)) {
                _state.value = _state.value.copy(
                    errorMessage = text(
                        R.string.novel_ghostwrite_error_stale_plot,
                        text(R.string.novel_ghostwrite_task_write),
                    ),
                )
                return
            }
            if (NovelWorkspaceUnresolvedStore.entryFor(directory, slug) != null) {
                _state.value = _state.value.copy(
                    errorMessage = text(
                        R.string.novel_ghostwrite_error_unresolved_edits,
                        text(R.string.novel_ghostwrite_task_write),
                    ),
                )
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
                    catalog = loadCatalog(directory, slug),
                    plotStale = NovelWorkspaceLedger.isPlotStale(
                        store,
                        NovelWorkspaceLedger.load(directory),
                        slug,
                    ),
                    unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
                    canUndo = runtime.canUndo(directory, slug),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = localizedRuntimeError(error, R.string.error_title_operation),
                )
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
                            branchSlug = job.branchSlug,
                            target = job.targetChapterCount,
                            written = NovelWorkspaceGhostwriteJobs.progress(job, store),
                            startOrdinal = job.startOrdinal,
                            status = job.status,
                            stage = job.stage,
                            currentChapterOrdinal = job.currentChapterOrdinal,
                            rewriteAttempt = job.rewriteAttempt,
                            reason = localizedJobReason(job.reason),
                            mode = job.mode,
                        )
                    }
                NovelGhostwriteRefresh(
                    job = job,
                    chapters = loadChapters(store),
                    drafts = loadDrafts(store),
                    catalog = loadCatalog(directory, ledger, slug),
                    plotStale = NovelWorkspaceLedger.isPlotStale(store, ledger, slug),
                    unresolvedFromOrdinal = NovelWorkspaceUnresolvedStore.entryFor(directory, slug)?.fromOrdinal,
                    canUndo = runtime.canUndo(directory, slug),
                )
            }
            _state.value = _state.value.copy(
                ghostwriteJob = refresh.job,
                chapters = refresh.chapters,
                drafts = refresh.drafts,
                catalog = refresh.catalog,
                plotStale = refresh.plotStale,
                unresolvedFromOrdinal = refresh.unresolvedFromOrdinal,
                canUndo = refresh.canUndo,
            )
        }
    }

    fun startGhostwriteBatch(targetChapterCount: Int) {
        val directory = projectDirectory ?: return
        val slug = branchSlug ?: return
        if (targetChapterCount !in 1..NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS) {
            _state.value = _state.value.copy(errorMessage = text(R.string.error_title_operation))
            return
        }
        checkBatchStartReady(polish = false)?.let { blocked ->
            _state.value = _state.value.copy(errorMessage = blocked)
            return
        }
        runCatching { ghostwriteController.startBatch(directory, projectId, slug, targetChapterCount) }
            .onSuccess { job ->
                _state.value = _state.value.copy(
                    errorMessage = null,
                    ghostwriteJob = NovelMarkdownGhostwriteUi(
                        jobId = job.id,
                        executionId = job.executionKey,
                        branchSlug = job.branchSlug,
                        target = job.targetChapterCount,
                        written = 0,
                        startOrdinal = job.startOrdinal,
                        status = job.status,
                        stage = job.stage,
                        currentChapterOrdinal = job.currentChapterOrdinal,
                        rewriteAttempt = job.rewriteAttempt,
                        mode = job.mode,
                    ),
                )
            }
            .onFailure {
                _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.error_title_operation))
                refreshGhostwrite()
            }
    }

    /**
     * 批量润色既有章节 [fromOrdinal, toOrdinal]（含两端）。范围按正文目录校验（每章必须
     * 存在）；与代笔互斥——分支上任一 active 批次（无论代笔还是润色）都会拒绝新批次。
     * 暂停/继续/重试/取消与代笔共用同一套操作（job 层面 mode 无关），进度呈现按
     * mode 区分文案。
     */
    fun startPolish(fromOrdinal: Int, toOrdinal: Int) {
        val directory = projectDirectory ?: return
        val slug = branchSlug ?: return
        if (fromOrdinal <= 0 || toOrdinal < fromOrdinal) {
            _state.value = _state.value.copy(errorMessage = text(R.string.error_title_operation))
            return
        }
        val ordinals = _state.value.chapters.map { it.ordinal }.toSet()
        val missing = (fromOrdinal..toOrdinal).firstOrNull { it !in ordinals }
        if (missing != null) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_no_chapters_to_polish))
            return
        }
        val blocked = checkBatchStartReady(polish = true)
        if (blocked != null) {
            _state.value = _state.value.copy(errorMessage = blocked)
            return
        }
        runCatching {
            ghostwriteController.startPolishBatch(directory, projectId, slug, fromOrdinal, toOrdinal)
        }
            .onSuccess { job ->
                _state.value = _state.value.copy(
                    errorMessage = null,
                    ghostwriteJob = NovelMarkdownGhostwriteUi(
                        jobId = job.id,
                        executionId = job.executionKey,
                        branchSlug = job.branchSlug,
                        target = job.targetChapterCount,
                        written = 0,
                        startOrdinal = job.startOrdinal,
                        status = job.status,
                        stage = job.stage,
                        currentChapterOrdinal = job.currentChapterOrdinal,
                        rewriteAttempt = job.rewriteAttempt,
                        mode = job.mode,
                    ),
                )
            }
            .onFailure {
                _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.error_title_operation))
                refreshGhostwrite()
            }
    }

    /** Shared pre-flight gates for both batch kinds; returns the blocking error or null when clear. */
    private fun checkBatchStartReady(polish: Boolean): String? {
        val directory = projectDirectory
        val slug = branchSlug
        if (directory == null || slug == null) return text(R.string.novel_loading)
        val settings = settingsAggregator.settingsFlow.value
        if (settings.init) return text(R.string.novel_loading)
        if (resolveWritingModel(settings) == null) {
            return text(R.string.novel_ghostwrite_error_model_missing)
        }
        // A failed batch remains bound to its frozen plan because retry resumes it.
        // The author must explicitly dismiss it before editing or starting fresh.
        if (_state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED ||
            _state.value.ghostwriteJob?.status == NovelWorkspaceGhostwriteJob.STATUS_FAILED
        ) {
            return text(R.string.novel_branch_locked)
        }
        if (NovelWorkspaceUnresolvedStore.entryFor(directory, slug) != null) {
            return text(
                R.string.novel_ghostwrite_error_unresolved_edits,
                text(if (polish) R.string.novel_ghostwrite_task_polish else R.string.novel_ghostwrite_task_write),
            )
        }
        if (NovelWorkspaceLedger.isPlotStale(
                NovelWorkspaceStore(directory),
                NovelWorkspaceLedger.load(directory),
                slug,
            )
        ) {
            _state.value = _state.value.copy(plotStale = true)
            return text(
                R.string.novel_ghostwrite_error_stale_plot,
                text(if (polish) R.string.novel_ghostwrite_task_polish else R.string.novel_ghostwrite_task_write),
            )
        }
        return null
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
        val slug = branchSlug ?: return
        // job 绑定创建时的分支：作者已切走时先切回原分支才能继续。
        if (current.branchSlug != slug) {
            _state.value = _state.value.copy(
                errorMessage = text(R.string.error_title_operation),
            )
            return
        }
        runCatching {
            ghostwriteController.resume(
                directory,
                projectId,
                current.jobId,
                current.executionId,
                expectedBranchSlug = slug,
            )
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
        val slug = branchSlug ?: return
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_FAILED) return
        if (current.branchSlug != slug) {
            _state.value = _state.value.copy(
                errorMessage = text(R.string.error_title_operation),
            )
            return
        }
        runCatching {
            ghostwriteController.retryFailed(
                directory,
                projectId,
                current.jobId,
                current.executionId,
                expectedBranchSlug = slug,
            )
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
            _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.error_title_operation))
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
            _state.value = _state.value.copy(ghostwriteJob = null, errorMessage = null)
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
     *  This is an author-triggered, read-only review; each batch chapter already has its
     *  own candidate-bound joint review before atomic collection. */
    fun runConsistencyCheck() {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.consistencyChecking) return
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveReviewModel(settings)
        if (model == null) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
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
                    userText = localizedPromptText(
                        chinese = "请对最新一章做一致性检查。",
                        english = "Review the latest chapter for consistency.",
                    ),
                    systemPrompt = NovelWorkspacePrompts.consistencyReview(locale = context.appLocale()),
                    settings = settings,
                    model = model,
                    fallbackErrorMessage = text(R.string.error_title_operation),
                    locale = context.appLocale(),
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
                canUndo = runtime.canUndo(directory, slug),
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
        }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.workspace_save_failed)) }
    }

    /** Set or clear the per-project review model override (null = follow the writing model). */
    fun setReviewModel(modelId: String?) {
        val directory = projectDirectory ?: return
        runCatching {
            val current = NovelWorkspaceProjectSettingsStore.load(directory)
            NovelWorkspaceProjectSettingsStore.save(
                current.copy(reviewModelId = modelId),
                directory,
            )
            _state.value = _state.value.copy(reviewModelId = modelId)
        }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.workspace_save_failed)) }
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

    fun saveChapterPlan(body: String): Boolean {
        projectDirectory ?: return false
        branchSlug ?: return false
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return false
        }
        return pathWrite(planPath(), body)
    }

    fun readUpcomingArc(): String = pathRead(upcomingPath()) ?: ""

    fun saveUpcomingArc(body: String) = pathWrite(upcomingPath(), body)

    /** Writing preference = the setting/writing card (first file; created on save). */
    fun readWritingPreference(): String {
        val directory = projectDirectory ?: return ""
        val store = NovelWorkspaceStore(directory)
        val first = store.list(NovelWorkspacePaths.SETTING_DIR + "/writing").firstOrNull()
        return pathRead(first) ?: ""
    }

    // saveWritingPreference 已上移至设定 tab 的 saveFileEdit 旁：两入口共用 commitFileEdit
    // （手改 commit + undo，J7 口径统一）。

    /** What the host will inject as constraints next turn (ghostwrite panel preview). */
    fun briefPreview(): String {
        val directory = projectDirectory ?: return ""
        val slug = branchSlug ?: return ""
        return runCatching {
            app.amber.feature.novelworkspace.NovelWorkspaceContextAssembler.assemble(
                NovelWorkspaceStore(directory), slug,
                flags = _state.value.injection,
                locale = context.appLocale(),
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

    private fun pathWrite(path: String?, body: String): Boolean {
        val directory = projectDirectory ?: return false
        if (path == null) return false
        return runCatching {
            NovelWorkspaceStore(directory).write(path, body)
            true
        }.getOrElse {
            _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.workspace_save_failed))
            false
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
        }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: text(R.string.workspace_save_failed)) }
    }

    /** Ghostwrite panel: model drafts the next chapter's plan into plan/this-chapter.md. */
    fun generateChapterPlan() {
        val directory = projectDirectory ?: return
        val branch = branchId ?: return
        val slug = branchSlug ?: return
        if (_state.value.busy) return
        if (hasActiveGhostwrite()) {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_batch_in_use))
            return
        }
        val settings = settingsAggregator.settingsFlow.value
        val model = resolveWritingModel(settings) ?: run {
            _state.value = _state.value.copy(errorMessage = text(R.string.novel_ghostwrite_error_model_missing))
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
                        userText = localizedPromptText(
                            chinese = "请根据前文拟定下一章计划并写入计划文件。",
                            english = "Draft the next chapter plan from the preceding story and write it to the plan file.",
                        ),
                        systemPrompt = NovelWorkspacePrompts.planDraft(locale = context.appLocale()),
                        settings = settings,
                        model = model,
                        fallbackErrorMessage = text(R.string.error_title_operation),
                        locale = context.appLocale(),
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
                                    text(R.string.error_title_operation)
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

    private fun toolLabel(toolName: String): String = context.getString(R.string.novel_thinking)

    private fun localizedPromptText(chinese: String, english: String): String =
        if (context.appLocale().language.equals("zh", ignoreCase = true)) chinese else english

    /** Translate only the fixed owner guard; preserve dynamic exception details verbatim. */
    private fun localizedRuntimeError(error: Throwable, fallbackResId: Int): String =
        if (error.message == OWNER_GUARD_ERROR) {
            text(R.string.novel_batch_in_use)
        } else {
            error.message ?: text(fallbackResId)
        }

    private fun localizedJobReason(reason: String?): String? {
        if (reason == null || context.appLocale().language.equals("zh", ignoreCase = true)) {
            return reason
        }
        val prefix = NovelWorkspaceGhostwriteCoordinator.REASON_POLISH_POINTER_COMMIT_FAILED
        if (!reason.startsWith(prefix)) return reason
        val detail = reason.removePrefix(prefix).removePrefix("：").trim()
        return if (detail.isEmpty()) {
            text(R.string.error_title_operation)
        } else {
            "${text(R.string.error_title_operation)}: $detail"
        }
    }

    private fun text(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        context.getString(id, *args)

    private companion object {
        private const val OWNER_GUARD_ERROR =
            "当前分支仍被代笔批次占用，请先让批次完成或取消后再修改正文"
    }
}
