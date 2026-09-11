package app.amber.feature.novel.workspace

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceContextAssembler
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteCandidate
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceJointReviewResult
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifest
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceProjectTitle
import app.amber.feature.novelworkspace.NovelWorkspaceSlug
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUndoRecord
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import app.amber.feature.novelworkspace.sha256Hex
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Workspace-native generation runtime: one turn = one agent loop over the five
 * workspace primitives. Free writes land during the turn; canon writes are buffered
 * and surface as a single author-gated proposal when the turn ends.
 */
class NovelWorkspaceRuntime(private val kernel: RunKernel) {

    sealed interface TurnEvent {
        data class Delta(val text: String) : TurnEvent
        data class ReasoningDelta(val text: String) : TurnEvent
        data class ToolActivity(val toolName: String) : TurnEvent
        data class Completed(
            val finalText: String,
            val proposal: NovelWorkspaceWriteProposal?,
        ) : TurnEvent
        data class Failed(val message: String) : TurnEvent
    }

    data class TurnRequest(
        val projectDirectory: File,
        val branchId: String,
        val branchSlug: String,
        val userText: String,
        val systemPrompt: String,
        val settings: Settings,
        val model: Model,
        val maxSteps: Int = 16,
        /** Candidate/review/planning turns may inspect tools but must not write any path. */
        val readOnlyTools: Boolean = false,
        /** Ghostwrite/unattended turns commit canon writes automatically (no approval card). */
        val autoApproveCanon: Boolean = false,
        /** Commit message used when autoApproveCanon commits canon writes. */
        val autoCommitMessage: String = NovelWorkspaceLedger.Message.COLLECTION,
        /** Which sections the constraint brief carries; null = defaults (all on). */
        val injection: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags? = null,
        /** Durable batch owner. Null means an interactive author turn. */
        val ownerJobId: String? = null,
        /** Identifies this specific Worker execution across pause/resume. */
        val ownerExecutionId: String? = null,
        /** Phase-0 binding carried through the unattended commit path. */
        val ghostwriteChapterOrdinal: Int? = null,
        val ghostwritePlanId: String? = null,
        val ghostwritePlanDigest: String? = null,
        /**
         * Polish turn marker + host-locked target: the ONE existing chapter file this
         * turn may rewrite. Non-null makes the commit a 「润色」 commit that is exempt
         * from the D-D unresolved gate (polish changes prose, never story facts, so a
         * middle-chapter polish must not invalidate the chapters after it) and locks
         * the write tool to this path.
        */
        val polishChapterPath: String? = null,
        /** Localized fallback used only when an exception has no message. */
        val fallbackErrorMessage: String = "Generation failed",
        /** Locale for host-generated state/error copy and the injected constraint brief. */
        val locale: Locale = Locale.CHINESE,
    )

    private val _pendingProposals = MutableStateFlow<List<NovelWorkspaceWriteProposal>>(emptyList())
    val pendingProposals: StateFlow<List<NovelWorkspaceWriteProposal>> = _pendingProposals.asStateFlow()

    /**
     * Run one workspace turn. [runId] / [events] thread the run scope's
     * identity and protocol event writer (Step 3) so the kernel can emit the
     * durable audit trail — tool lifecycle events and, on the durable path,
     * request snapshots. Emission stays gated by the kernel's durable-path
     * check; null (bare callers) keeps the loop silent.
     */
    fun runTurn(
        request: TurnRequest,
        runId: String? = null,
        events: app.amber.core.agent.runtime.AgentEventWriter? = null,
    ): Flow<TurnEvent> = flow {
        val store = NovelWorkspaceStore(request.projectDirectory)
        val ownerAtStart = NovelWorkspaceGhostwriteJobs.activeFor(
            request.projectDirectory,
            request.branchSlug,
        )
        if (request.ownerJobId == null && ownerAtStart != null) {
            emit(TurnEvent.Failed(request.localized(
                chinese = "当前分支仍被代笔批次占用，请先让批次完成或取消后再修改正文",
                english = "This branch is occupied by a ghostwrite batch. Let it finish or cancel it before editing the manuscript.",
            )))
            return@flow
        }
        val runningOwnerMatches = request.ownerJobId?.let { ownerJobId ->
            request.ownerExecutionId?.let { executionId ->
                NovelWorkspaceGhostwriteJobs.withRunningOwner(
                    request.projectDirectory,
                    ownerJobId,
                    executionId,
                ) { true }
            }
        }
        if (request.ownerJobId != null && runningOwnerMatches != true) {
            emit(TurnEvent.Failed(request.localized(
                chinese = "代笔已暂停、取消，或正文与确认计划的版本绑定已变化",
                english = "The ghostwrite batch was paused or cancelled, or its manuscript/plan binding changed.",
            )))
            return@flow
        }
        val batch = NovelWorkspaceWriteBatch()
        val session = NovelWorkspaceToolSession(
            store = store,
            branchSlug = request.branchSlug,
            projectTitle = readProjectTitle(store),
            batch = batch,
            readOnly = request.readOnlyTools,
            autoApproveCanon = request.autoApproveCanon,
            polishTargetPath = request.polishChapterPath,
            confirmedPlanLocked = request.ghostwritePlanDigest != null,
        )
        // Context-engineering core: inject the constraint brief (plot state, open
        // foreshadowing, decisions, and the plan's entity subgraph) every turn.
        val brief = runCatching {
            NovelWorkspaceContextAssembler.assemble(
                store,
                request.branchSlug,
                flags = request.injection ?: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
                locale = request.locale,
            )
        }.getOrDefault("")
        val systemPrompt = if (brief.isBlank()) {
            request.systemPrompt
        } else {
            request.systemPrompt +
                if (request.locale.language.equals("zh", ignoreCase = true)) {
                    "\n\n## 工作区状态简报（host 注入，以下为当前正史约束，不得与之矛盾）\n"
                } else {
                    "\n\n## Workspace state brief (host-injected; the following constraints are current canon)\n"
                } + brief
        }
        var emittedText = ""
        var emittedReasoning = ""
        val seenTools = mutableSetOf<String>()
        var canonLedgerPersisted = false
        var canonAccountingPersisted = false
        var canonRollbackHandled = false
        var freeWritesPersisted = false
        try {
            val messages = buildList {
                if (systemPrompt.isNotBlank()) {
                    add(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(systemPrompt))))
                }
                add(UIMessage.user(request.userText))
            }
            kernel.run(
                GenerationRunSession(
                    settings = request.settings,
                model = request.model,
                messages = messages,
                tools = session.tools(),
                maxSteps = request.maxSteps,
                // The workspace write tool has its own strict path and author gates.
                // Trust only this named tool; do not enable blanket auto-approval.
                autoApprovedToolNames = setOf("novel_workspace_write"),
                // Step 5: thread the run scope's identity and event writer the
                // same way SubAgentRunner does; the kernel's durable-path gate
                // decides whether anything is emitted.
                runId = runId,
                toolLifecycleEvents = events,
                // Step 6: v1 default codifies no new restriction; narrowing
                // arrives via sub-agent payloads.
                executionPolicy = app.amber.feature.runtime.ExecutionPolicy.permissive(),
                ),
            ).collect { chunk ->
                val messages = (chunk as? GenerationChunk.Messages)?.messages ?: return@collect
                val assistantText = assistantTextOf(messages)
                if (assistantText != emittedText) {
                    val delta = if (assistantText.startsWith(emittedText)) {
                        assistantText.substring(emittedText.length)
                    } else {
                        assistantText
                    }
                    emittedText = assistantText
                    if (delta.isNotEmpty()) emit(TurnEvent.Delta(delta))
                }
                val reasoning = reasoningOf(messages)
                if (reasoning.length > emittedReasoning.length && reasoning.startsWith(emittedReasoning)) {
                    emit(TurnEvent.ReasoningDelta(reasoning.substring(emittedReasoning.length)))
                    emittedReasoning = reasoning
                }
                for (tool in messages.lastOrNull()?.getTools().orEmpty()) {
                    if (seenTools.add(tool.toolCallId)) {
                        emit(TurnEvent.ToolActivity(tool.toolName))
                    }
                }
            }
            if (request.ownerJobId != null &&
                NovelWorkspaceGhostwriteJobs.load(request.projectDirectory, request.ownerJobId)?.let {
                    it.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
                        it.executionKey != request.ownerExecutionId
                } != false
            ) {
                rollbackUncommittedWrites(request, batch)
                emit(TurnEvent.Failed(request.localized(
                    chinese = "代笔已暂停或取消",
                    english = "The ghostwrite batch is paused or cancelled.",
                )))
                return@flow
            }
            // Interactive free writes already hit disk. Commit them before any separate
            // canon proposal so rejecting that proposal cannot leave the ledger dirty.
            if (batch.hasFreeWrites()) {
                val committed = if (request.ownerJobId != null) {
                    NovelWorkspaceGhostwriteJobs.withRunningOwner(
                        request.projectDirectory,
                        request.ownerJobId,
                        checkNotNull(request.ownerExecutionId),
                    ) {
                        commitTree(
                            projectDirectory = request.projectDirectory,
                            branchId = request.branchId,
                            branchSlug = request.branchSlug,
                            message = NovelWorkspaceLedger.Message.GENERIC,
                        )
                    }
                } else {
                    commitTree(
                        projectDirectory = request.projectDirectory,
                        branchId = request.branchId,
                        branchSlug = request.branchSlug,
                        message = NovelWorkspaceLedger.Message.GENERIC,
                    )
                }
                if (committed == null) {
                    rollbackUncommittedWrites(request, batch)
                    emit(TurnEvent.Failed(request.localized(
                        chinese = "代笔已暂停或取消",
                        english = "The ghostwrite batch is paused or cancelled.",
                    )))
                    return@flow
                }
                freeWritesPersisted = true
            }
            val proposal = if (batch.isEmpty()) {
                null
            } else if (request.autoApproveCanon) {
                // Apply buffered unattended writes only while this Worker still owns its
                // execution token, then persist the write and ledger under one lock.
                val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(request.projectDirectory)
                val commitBufferedCanon = {
                    val canonStore = NovelWorkspaceStore(request.projectDirectory)
                    try {
                        batch.snapshot().forEach { entry ->
                            batch.rememberPrevious(entry.path, canonStore.read(entry.path))
                            canonStore.write(entry.path, entry.content)
                        }
                        val commit = commitTree(
                            projectDirectory = request.projectDirectory,
                            branchId = request.branchId,
                            branchSlug = request.branchSlug,
                            message = request.autoCommitMessage,
                            // A polish commit only re-proses one existing chapter — it
                            // must not arm the D-D unresolved gate (facts unchanged).
                            armsUnresolvedGate = request.polishChapterPath == null,
                            onLedgerPersisted = { canonLedgerPersisted = true },
                        )
                        val ownerJob = request.ownerJobId?.let {
                            NovelWorkspaceGhostwriteJobs.load(request.projectDirectory, it)
                        }
                        if (ownerJob?.isVersionBound == true) {
                            checkNotNull(
                                NovelWorkspaceGhostwriteJobs.recordWriteCommit(
                                    projectDirectory = request.projectDirectory,
                                    jobId = ownerJob.id,
                                    executionId = checkNotNull(request.ownerExecutionId),
                                    commitId = commit.id,
                                    chapterOrdinal = checkNotNull(request.ghostwriteChapterOrdinal),
                                    planId = checkNotNull(request.ghostwritePlanId),
                                    planDigest = checkNotNull(request.ghostwritePlanDigest),
                                ),
                            ) { "代笔版本绑定已变化" }
                        }
                        canonAccountingPersisted = true
                        commit
                    } catch (error: Exception) {
                        if (!canonLedgerPersisted) {
                            rollbackUncommittedWrites(request, batch)
                            canonRollbackHandled = true
                        }
                        throw error
                    }
                }
                val committed = if (request.ownerJobId != null) {
                    NovelWorkspaceGhostwriteJobs.withRunningOwner(
                        request.projectDirectory,
                        request.ownerJobId,
                        checkNotNull(request.ownerExecutionId),
                        commitBufferedCanon,
                    )
                } else {
                    commitBufferedCanon()
                }
                if (committed == null) {
                    rollbackUncommittedWrites(request, batch)
                    emit(TurnEvent.Failed(request.localized(
                        chinese = "代笔已暂停或取消",
                        english = "The ghostwrite batch is paused or cancelled.",
                    )))
                    return@flow
                }
                NovelWorkspaceUndo.save(
                    NovelWorkspaceUndoRecord(
                        commitId = committed.id,
                        parentCommitId = committed.parentId,
                        files = batch.previousSnapshot(),
                        unresolvedBefore = unresolvedBefore,
                        branchSlug = request.branchSlug,
                    ),
                    request.projectDirectory,
                )
                null
            } else {
                registerProposal(request, batch.snapshot())
            }
            emit(TurnEvent.Completed(emittedText, proposal))
        } catch (error: CancellationException) {
            // Same orphan risk as a failed turn: writes may already be on disk with no
            // commit. Restore the turn preimages, then propagate cancellation.
            if (!canonLedgerPersisted && !freeWritesPersisted && !canonRollbackHandled) {
                rollbackUncommittedWrites(request, batch)
            }
            throw error
        } catch (error: Exception) {
            if (canonLedgerPersisted && !canonAccountingPersisted && request.ownerJobId != null) {
                emit(TurnEvent.Failed(request.localized(
                    chinese = "本章已提交，但批次记账失败；已停止以避免重复写章",
                    english = "The chapter was committed, but batch accounting failed; the batch stopped to avoid writing a duplicate chapter.",
                )))
            } else if (canonLedgerPersisted) {
                // The chapter is already durable. Reporting a failed turn would make
                // the batch retry the same ordinal after a checkout/undo side-effect error.
                emit(TurnEvent.Completed(emittedText, proposal = null))
            } else {
                if (!freeWritesPersisted && !canonRollbackHandled) {
                    rollbackUncommittedWrites(request, batch)
                }
                emit(TurnEvent.Failed(error.message ?: request.fallbackErrorMessage))
            }
        }
    }

    /**
     * A failed/cancelled turn must not leave files on disk that no commit owns.
     * Interactive free writes land immediately but remember their pre-turn content;
     * unattended canon writes remember it when their buffered batch is applied.
     */
    private fun rollbackUncommittedWrites(request: TurnRequest, batch: NovelWorkspaceWriteBatch) {
        val store = NovelWorkspaceStore(request.projectDirectory)
        for ((path, previous) in batch.previousSnapshot()) {
            if (previous == null) store.delete(path) else store.write(path, previous)
        }
    }

    private fun TurnRequest.localized(chinese: String, english: String): String =
        if (locale.language.equals("zh", ignoreCase = true)) chinese else english

    /** Author approved the gate: apply every entry, one commit, checkout refreshed. */
    fun approve(proposalId: String) {
        val proposal = _pendingProposals.value.firstOrNull { it.id == proposalId } ?: return
        NovelWorkspaceGhostwriteJobs.withNoActiveBranch(
            proposal.projectDirectory,
            proposal.branchSlug,
        ) {
            val store = NovelWorkspaceStore(proposal.projectDirectory)
            val ledgerBefore = NovelWorkspaceLedger.load(proposal.projectDirectory)
            val currentHead = ledgerBefore.headOf(proposal.branchId)?.id
            val currentTreeDigest = NovelWorkspaceLedger.treeSHA256(store.fileTree())
            if (currentHead != proposal.baseHeadId || currentTreeDigest != proposal.baseTreeDigest) {
                throw NovelWorkspaceIoError("提案已过期：正文或计划在确认前发生了变化，请重新生成")
            }
            val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(proposal.projectDirectory)
            // Capture pre-write contents so 撤销最近一笔 can restore them.
            val previous = proposal.entries.associate { it.path to store.read(it.path) }
            for (entry in proposal.entries) {
                store.write(entry.path, mergedContent(store, entry))
            }
            val commit = commitTree(
                projectDirectory = proposal.projectDirectory,
                branchId = proposal.branchId,
                branchSlug = proposal.branchSlug,
                message = commitMessageFor(proposal.entries.map { it.path }),
            )
            NovelWorkspaceUndo.save(
                NovelWorkspaceUndoRecord(
                    commitId = commit.id,
                    parentCommitId = commit.parentId,
                    files = previous,
                    unresolvedBefore = unresolvedBefore,
                    branchSlug = proposal.branchSlug,
                ),
                proposal.projectDirectory,
            )
            commit
        } ?: throw NovelWorkspaceIoError(
            "当前分支仍被代笔批次占用，请先让批次完成或取消后再批准提案",
        )
        _pendingProposals.value = _pendingProposals.value.filterNot { it.id == proposalId }
    }

    /**
     * Author-initiated collect: promote a draft into the manuscript and commit.
     * The author's click IS the approval, so this commits directly (no proposal card).
     * The draft is consumed. ReplaceChapter on a middle chapter records the unresolved gate.
     */
    fun collectDraft(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        draftPath: String,
        target: NovelWorkspaceCollectTarget,
        chapterTitle: String? = null,
    ): NovelWorkspaceCommit {
        assertNoGhostwriteOwner(projectDirectory, branchSlug)
        val store = NovelWorkspaceStore(projectDirectory)
        val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
        val draftRaw = store.read(draftPath)
            ?: throw NovelWorkspaceIoError("草稿不存在：$draftPath")
        val draftBody = NovelWorkspaceMarkdown.parseFile(draftRaw).body
        val chaptersPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters"

        // Compute the new chapter path up front so undo captures it pre-write.
        var newChapterPath: String? = null
        val targetChapterPath = when (target) {
            is NovelWorkspaceCollectTarget.NewChapter -> {
                val nextOrdinal =
                    (NovelWorkspaceLedger.workingChapterOrdinals(store, branchSlug).maxOrNull() ?: 0) + 1
                val title = chapterTitle?.takeIf { it.isNotBlank() }
                    ?: NovelWorkspacePaths.fileNameTitle(draftPath).ifBlank { "第 $nextOrdinal 章" }
                val leafSlug = NovelWorkspaceSlug.slug(title).ifEmpty { "chapter" }
                "$chaptersPrefix/" + NovelWorkspacePaths.chapterFileName(nextOrdinal, leafSlug)
            }
            is NovelWorkspaceCollectTarget.AppendToChapter -> target.chapterPath
            is NovelWorkspaceCollectTarget.ReplaceChapter -> target.chapterPath
        }
        // Capture pre-write contents for 撤销最近一笔 (draft is deleted, chapter written).
        val previous = mapOf(
            draftPath to store.read(draftPath),
            targetChapterPath to store.read(targetChapterPath),
        )

        when (target) {
            is NovelWorkspaceCollectTarget.NewChapter -> {
                val path = targetChapterPath
                val nextOrdinal =
                    NovelWorkspacePaths.chapterOrdinalFromPath(path) ?: 1
                val title = chapterTitle?.takeIf { it.isNotBlank() }
                    ?: NovelWorkspacePaths.fileNameTitle(draftPath).ifBlank { "第 $nextOrdinal 章" }
                newChapterPath = path
                store.write(
                    path,
                    NovelWorkspaceMarkdown.render(
                        fields = listOf(
                            "id" to UUID.randomUUID().toString().uppercase(),
                            "kind" to "chapter",
                            "title" to title,
                            "ordinal" to nextOrdinal.toString(),
                        ),
                        body = draftBody,
                    ),
                )
            }
            is NovelWorkspaceCollectTarget.AppendToChapter -> {
                val existing = store.read(target.chapterPath)
                    ?: throw NovelWorkspaceIoError("目标章节不存在：${target.chapterPath}")
                val parsed = NovelWorkspaceMarkdown.parseFile(existing)
                store.write(
                    target.chapterPath,
                    NovelWorkspaceMarkdown.render(
                        fields = parsed.fields.toList(),
                        aliases = parsed.lists["aliases"].orEmpty(),
                        body = (parsed.body + "\n\n" + draftBody).trim(),
                    ),
                )
            }
            is NovelWorkspaceCollectTarget.ReplaceChapter -> {
                val existing = store.read(target.chapterPath)
                    ?: throw NovelWorkspaceIoError("目标章节不存在：${target.chapterPath}")
                val parsed = NovelWorkspaceMarkdown.parseFile(existing)
                store.write(
                    target.chapterPath,
                    NovelWorkspaceMarkdown.render(
                        fields = parsed.fields.toList(),
                        aliases = parsed.lists["aliases"].orEmpty(),
                        body = draftBody,
                    ),
                )
            }
        }

        // Collecting consumes the draft.
        store.delete(draftPath)
        val commit = commitTree(
            projectDirectory = projectDirectory,
            branchId = branchId,
            branchSlug = branchSlug,
            message = NovelWorkspaceLedger.Message.COLLECTION,
        )
        NovelWorkspaceUndo.save(
            NovelWorkspaceUndoRecord(
                commitId = commit.id,
                parentCommitId = commit.parentId,
                files = previous,
                unresolvedBefore = unresolvedBefore,
                branchSlug = branchSlug,
            ),
            projectDirectory,
        )
        return commit
    }

    /**
     * Ghostwrite host-write path: the model returned the chapter as plain text
     * (device-proven: several providers narrate instead of calling the write
     * tool), so the host files it into the manuscript and commits — same shape
     * as a collected draft. Undo captures pre-write state like collectDraft.
     */
    fun commitGhostwrittenChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterOrdinal: Int,
        title: String,
        body: String,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
    ): NovelWorkspaceCommit {
        val commitBlock = commitBlock@{
            val store = NovelWorkspaceStore(projectDirectory)
            val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
            val chaptersPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters"
            val leafSlug = NovelWorkspaceSlug.slug(title).ifEmpty { "chapter" }
            val path = "$chaptersPrefix/" + NovelWorkspacePaths.chapterFileName(chapterOrdinal, leafSlug)
            val previous = mapOf(path to store.read(path))
            var ledgerPersisted = false
            var accountingPersisted = false
            try {
                store.write(
                    path,
                    NovelWorkspaceMarkdown.render(
                        fields = listOf(
                            "id" to UUID.randomUUID().toString().uppercase(),
                            "kind" to "chapter",
                            "title" to title,
                            "ordinal" to chapterOrdinal.toString(),
                        ),
                        body = body,
                    ),
                )
                val commit = commitTree(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = branchSlug,
                    message = NovelWorkspaceLedger.Message.COLLECTION,
                    onLedgerPersisted = { ledgerPersisted = true },
                )
                if (ownerJobId != null) {
                    val currentJob = checkNotNull(NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId))
                    if (currentJob.isVersionBound) {
                        checkNotNull(
                            NovelWorkspaceGhostwriteJobs.recordWriteCommit(
                                projectDirectory = projectDirectory,
                                jobId = ownerJobId,
                                executionId = checkNotNull(ownerExecutionId),
                                commitId = commit.id,
                                chapterOrdinal = chapterOrdinal,
                                planId = currentJob.planId,
                                planDigest = currentJob.planDigest,
                            ),
                        ) { "代笔版本绑定已变化" }
                    }
                }
                accountingPersisted = true
                NovelWorkspaceUndo.save(
                    NovelWorkspaceUndoRecord(
                        commitId = commit.id,
                        parentCommitId = commit.parentId,
                        files = previous,
                        unresolvedBefore = unresolvedBefore,
                        branchSlug = branchSlug,
                    ),
                    projectDirectory,
                )
                commit
            } catch (error: Exception) {
                if (ledgerPersisted && accountingPersisted) {
                    NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.let {
                        return@commitBlock it
                    }
                }
                if (ledgerPersisted) throw error
                for ((changedPath, oldContent) in previous) {
                    if (oldContent == null) store.delete(changedPath) else store.write(changedPath, oldContent)
                }
                throw error
            }
        }
        if (ownerJobId == null) return commitBlock()
        return NovelWorkspaceGhostwriteJobs.withRunningOwner(
            projectDirectory,
            ownerJobId,
            checkNotNull(ownerExecutionId),
            commitBlock,
        ) ?: throw NovelWorkspaceIoError("代笔已暂停或取消")
    }

    /**
     * The only Phase-2 path from a private candidate into canon. Chapter prose and the
     * reviewed post-chapter plot state share one Ledger commit and one undo record.
     */
    fun commitReviewedChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        ownerJobId: String,
        ownerExecutionId: String,
    ): NovelWorkspaceCommit {
        val committed = NovelWorkspaceGhostwriteJobs.withRunningOwner(
            projectDirectory,
            ownerJobId,
            ownerExecutionId,
        ) {
            val job = checkNotNull(NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId))
            val candidate = checkNotNull(job.pendingCandidate)
            val review = checkNotNull(job.pendingReview)
            check(job.stage == NovelWorkspaceGhostwriteStage.Committing &&
                !review.blocking && !review.rewriteRequired
            ) { "联合审核尚未通过" }
            check(job.branchId == branchId && job.branchSlug == branchSlug) { "代笔分支绑定已变化" }

            val store = NovelWorkspaceStore(projectDirectory)
            val chaptersPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters"
            check(store.list(chaptersPrefix).none {
                NovelWorkspacePaths.chapterOrdinalFromPath(it) == candidate.chapterOrdinal
            }) { "目标章节已经存在" }
            val chapterPath = reviewedChapterPath(branchSlug, candidate)
            val plotPath = NovelWorkspacePaths.branchPrefix(branchSlug) + "/plot/current.md"
            val planPath = NovelWorkspacePaths.branchPrefix(branchSlug) + "/plan/this-chapter.md"
            val finalChapter = NovelWorkspaceGhostwriteJobs.progress(job, store) + 1 >=
                job.targetChapterCount
            val nextPlanContent = if (finalChapter) null else reviewedNextPlanContent(candidate, review)
            val previous = linkedMapOf(
                chapterPath to store.read(chapterPath),
                plotPath to store.read(plotPath),
                planPath to store.read(planPath),
            )
            val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
            val ledgerBefore = NovelWorkspaceLedger.load(projectDirectory)
            check(ledgerBefore.heads[branchId] == job.expectedHeadId) { "代笔分支版本已变化" }
            val commitId = UUID.randomUUID().toString().uppercase()
            val undo = NovelWorkspaceUndoRecord(
                commitId = commitId,
                parentCommitId = job.expectedHeadId,
                files = previous,
                unresolvedBefore = unresolvedBefore,
                branchSlug = branchSlug,
            )
            val previousUndo = NovelWorkspaceUndo.load(projectDirectory)
            var ledgerPersisted = false
            NovelWorkspaceUndo.save(undo, projectDirectory)
            try {
                store.write(chapterPath, reviewedChapterContent(candidate))
                store.write(plotPath, reviewedPlotContent(store, plotPath, review))
                if (nextPlanContent == null) {
                    store.delete(planPath)
                } else {
                    store.write(planPath, nextPlanContent)
                }
                val commit = commitTree(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = branchSlug,
                    message = NovelWorkspaceLedger.Message.GHOSTWRITE_REVIEWED,
                    commitId = commitId,
                    onLedgerPersisted = { ledgerPersisted = true },
                )
                checkNotNull(
                    NovelWorkspaceGhostwriteJobs.recordReviewedCommit(
                        projectDirectory = projectDirectory,
                        jobId = ownerJobId,
                        executionId = ownerExecutionId,
                        commitId = commit.id,
                        candidateId = candidate.id,
                    ),
                ) { "章节已收录，但代笔进度记账失败" }
                commit
            } catch (error: Exception) {
                if (!ledgerPersisted) {
                    previous.forEach { (path, content) ->
                        if (content == null) store.delete(path) else store.write(path, content)
                    }
                    store.materializeCheckout()
                    if (previousUndo == null) {
                        NovelWorkspaceUndo.clear(projectDirectory)
                    } else {
                        NovelWorkspaceUndo.save(previousUndo, projectDirectory)
                    }
                }
                throw error
            }
        }
        return committed ?: throw NovelWorkspaceIoError("代笔已暂停、取消，或正文版本已变化")
    }

    /**
     * Repair only the reviewed-commit crash window. A canonical matching Ledger commit
     * gets its job receipt back; pre-Ledger candidate/plot writes are restored from the
     * already-durable undo preimage. No model call is repeated here.
     */
    fun reconcileReviewedChapter(
        projectDirectory: File,
        ownerJobId: String,
        ownerExecutionId: String,
    ): Boolean {
        val job = NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId) ?: return false
        val candidate = job.pendingCandidate ?: return false
        val review = job.pendingReview ?: return false
        if (job.executionKey != ownerExecutionId ||
            job.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            job.stage != NovelWorkspaceGhostwriteStage.Committing ||
            review.blocking || review.rewriteRequired
        ) return false
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val headId = ledger.heads[job.branchId]
        if (headId != job.expectedHeadId) {
            val canonicalCommit = headId?.let(ledger::commit)
                ?: throw NovelWorkspaceIoError("代笔提交后的账本来源无法确认")
            if (store.fileTree() != canonicalCommit.files) {
                throw NovelWorkspaceIoError("代笔提交后的工作树已被其他修改改变")
            }
            return NovelWorkspaceGhostwriteJobs.recordReviewedCommit(
                projectDirectory = projectDirectory,
                jobId = ownerJobId,
                executionId = ownerExecutionId,
                commitId = canonicalCommit.id,
                candidateId = candidate.id,
            ) != null || throw NovelWorkspaceIoError("代笔提交后的账本来源无法确认")
        }

        val undo = NovelWorkspaceUndo.load(projectDirectory)
        val orphanUndo = undo?.takeIf {
            it.parentCommitId == job.expectedHeadId &&
                it.branchSlug == job.branchSlug &&
                ledger.commit(it.commitId) == null
        }
        val treeDigest = NovelWorkspaceLedger.treeSHA256(store.fileTree())
        if (treeDigest == job.expectedTreeDigest) {
            if (orphanUndo != null) NovelWorkspaceUndo.clear(projectDirectory)
            return false
        }
        val chapterPath = reviewedChapterPath(job.branchSlug, candidate)
        val plotPath = NovelWorkspacePaths.branchPrefix(job.branchSlug) + "/plot/current.md"
        val planPath = NovelWorkspacePaths.branchPrefix(job.branchSlug) + "/plan/this-chapter.md"
        val finalChapter = NovelWorkspaceGhostwriteJobs.progress(job, store) + 1 >=
            job.targetChapterCount
        val nextPlanContent = if (finalChapter) null else reviewedNextPlanContent(candidate, review)
        if (orphanUndo == null || orphanUndo.files.keys != setOf(chapterPath, plotPath, planPath)) {
            throw NovelWorkspaceIoError("代笔提交前的工作树已被其他修改改变")
        }
        val expectedWrites = mapOf(
            chapterPath to reviewedChapterContent(candidate),
            plotPath to reviewedPlotContentFromPrevious(orphanUndo.files[plotPath], review),
            planPath to nextPlanContent,
        )
        val targetFilesAreRecoverable = expectedWrites.all { (path, expectedContent) ->
            val currentContent = store.read(path)
            currentContent == orphanUndo.files[path] || currentContent == expectedContent
        }
        val restoredTree = store.fileTree().toMutableMap().apply {
            orphanUndo.files.forEach { (path, content) ->
                if (content == null) remove(path) else put(path, sha256Hex(content))
            }
        }
        if (!targetFilesAreRecoverable ||
            NovelWorkspaceLedger.treeSHA256(restoredTree) != job.expectedTreeDigest
        ) {
            throw NovelWorkspaceIoError("代笔提交前的工作树已被其他修改改变")
        }
        orphanUndo.files.forEach { (path, content) ->
            if (content == null) store.delete(path) else store.write(path, content)
        }
        store.materializeCheckout()
        NovelWorkspaceUndo.clear(projectDirectory)
        check(NovelWorkspaceLedger.treeSHA256(store.fileTree()) == job.expectedTreeDigest) {
            "代笔提交前状态恢复失败"
        }
        return false
    }

    private fun reviewedChapterPath(
        branchSlug: String,
        candidate: NovelWorkspaceGhostwriteCandidate,
    ): String {
        val slug = NovelWorkspaceSlug.slug(candidate.title).ifEmpty { "chapter" }
        return NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters/" +
            NovelWorkspacePaths.chapterFileName(candidate.chapterOrdinal, slug)
    }

    private fun reviewedChapterContent(candidate: NovelWorkspaceGhostwriteCandidate): String =
        NovelWorkspaceMarkdown.render(
            fields = listOf(
                "id" to UUID.nameUUIDFromBytes(
                    ("reviewed-chapter:" + candidate.id).toByteArray(Charsets.UTF_8),
                ).toString().uppercase(),
                "kind" to "chapter",
                "title" to candidate.title,
                "ordinal" to candidate.chapterOrdinal.toString(),
                "sourceCandidateId" to candidate.id,
                "sourcePlanId" to candidate.planId,
                "sourcePlanDigest" to candidate.planDigest,
            ),
            body = candidate.body,
        )

    private fun reviewedNextPlanContent(
        candidate: NovelWorkspaceGhostwriteCandidate,
        review: NovelWorkspaceJointReviewResult,
    ): String {
        val body = review.nextPlan?.trim().orEmpty()
        check(body.isNotEmpty()) { "联合审核没有提供下一章计划" }
        return NovelWorkspaceMarkdown.render(
            fields = listOf(
                "id" to NovelWorkspaceGhostwriteJobs.reviewedNextPlanId(candidate.id),
                "kind" to "plan",
                "title" to "本章计划",
                "status" to "confirmed",
            ),
            body = body,
        )
    }

    private fun reviewedPlotContent(
        store: NovelWorkspaceStore,
        plotPath: String,
        review: NovelWorkspaceJointReviewResult,
    ): String = reviewedPlotContentFromPrevious(store.read(plotPath), review)

    private fun reviewedPlotContentFromPrevious(
        previous: String?,
        review: NovelWorkspaceJointReviewResult,
    ): String {
        val parsed = previous?.let(NovelWorkspaceMarkdown::parseFile)
        val highlights = parsed?.body
            ?.let(NovelWorkspaceMarkdown::splitHighlights)
            ?.second
            .orEmpty() + review.chapterHighlight.trim()
        val body = buildString {
            append(review.plotState.trim())
            append("\n\n## 近期已写\n")
            highlights.filter { it.isNotBlank() }.forEach { append("- ").append(it).append('\n') }
        }.trim()
        return NovelWorkspaceMarkdown.render(
            fields = parsed?.fields?.toList() ?: listOf(
                "id" to UUID.nameUUIDFromBytes(
                    ("reviewed-plot:" + review.candidateId).toByteArray(Charsets.UTF_8),
                ).toString().uppercase(),
                "kind" to "plot",
                "title" to "当前剧情",
            ),
            aliases = parsed?.lists?.get("aliases").orEmpty(),
            body = body,
        )
    }

    /**
     * Polish host-write path: the model returned the polished chapter as plain text
     * instead of calling the write tool, so the host replaces the chapter body and
     * commits. Chapter identity (id/ordinal/title front matter) stays host-owned —
     * polish must not rename or renumber a chapter — unlike [commitGhostwrittenChapter],
     * which files a NEW chapter. Message 「润色」; exempt from the D-D unresolved gate
     * (facts unchanged). Owner-gated exactly like the ghostwrite host-write path.
     */
    fun commitPolishedChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterPath: String,
        polishedBody: String,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
    ): NovelWorkspaceCommit {
        val commitBlock = commitBlock@{
            val store = NovelWorkspaceStore(projectDirectory)
            val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
            val existing = store.read(chapterPath)
                ?: throw NovelWorkspaceIoError("章节不存在：$chapterPath")
            val parsed = NovelWorkspaceMarkdown.parseFile(existing)
            val previous = mapOf(chapterPath to existing)
            var ledgerPersisted = false
            try {
                store.write(
                    chapterPath,
                    NovelWorkspaceMarkdown.render(
                        fields = parsed.fields.toList(),
                        aliases = parsed.lists["aliases"].orEmpty(),
                        body = polishedBody.trim(),
                    ),
                )
                val commit = commitTree(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = branchSlug,
                    message = NovelWorkspaceLedger.Message.POLISH,
                    armsUnresolvedGate = false,
                    onLedgerPersisted = { ledgerPersisted = true },
                )
                NovelWorkspaceUndo.save(
                    NovelWorkspaceUndoRecord(
                        commitId = commit.id,
                        parentCommitId = commit.parentId,
                        files = previous,
                        unresolvedBefore = unresolvedBefore,
                        branchSlug = branchSlug,
                    ),
                    projectDirectory,
                )
                commit
            } catch (error: Exception) {
                if (ledgerPersisted) {
                    NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.let {
                        return@commitBlock it
                    }
                    throw error
                }
                for ((changedPath, oldContent) in previous) {
                    if (oldContent == null) store.delete(changedPath) else store.write(changedPath, oldContent)
                }
                throw error
            }
        }
        if (ownerJobId == null) return commitBlock()
        return NovelWorkspaceGhostwriteJobs.withRunningOwner(
            projectDirectory,
            ownerJobId,
            checkNotNull(ownerExecutionId),
            commitBlock,
        ) ?: throw NovelWorkspaceIoError("润色已暂停或取消")
    }

    /**
     * 剧情指针 commit after a polished chapter — the D-C freshness pairing that keeps a
     * polish batch from flipping `isPlotStale`.
     *
     * Why the host does this instead of the model: `isPlotStale` compares the newest
     * commit that CHANGED chapters/ against the newest commit that CHANGED plot/. A
     * 润色 commit changes only chapters/, so without a pairing commit every polish batch
     * would end plot-stale even though no story fact moved; relying on the model to
     * touch plot/current.md in the same turn (the write-mode D-C pattern) is not
     * something polish may ask for — the plot text itself must stay untouched. The host
     * therefore appends a single, self-replacing pointer line to plot/current.md and
     * commits it with the existing 「剧情指针」 convention (the same 收录→剧情指针 sequence
     * the legacy engine used for batch runs): after each chapter the commit chain reads
     * 润色 → 剧情指针, so plot freshness always trails or matches chapters/ and the batch
     * ends NOT stale. The line is plain prose (no front matter), documents that prose
     * moved without plot movement, and never accumulates.
     */
    fun commitPolishPointer(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterOrdinal: Int,
    ): NovelWorkspaceCommit {
        val store = NovelWorkspaceStore(projectDirectory)
        val plotPath = NovelWorkspacePaths.branchPrefix(branchSlug) + "/plot/current.md"
        val pointerLine = "（润色指针：第 $chapterOrdinal 章文字已润色，剧情与事实未变。）"
        val existing = store.read(plotPath)
        val rendered = if (existing == null) {
            // Plotless book: create the pointer file so freshness has a plot side at all.
            NovelWorkspaceMarkdown.render(
                fields = listOf(
                    "id" to UUID.randomUUID().toString().uppercase(),
                    "kind" to "plot",
                    "title" to "当前剧情",
                ),
                body = pointerLine,
            )
        } else {
            val parsed = NovelWorkspaceMarkdown.parseFile(existing)
            val kept = parsed.body
                .lines()
                .filterNot { it.trimStart().startsWith(POLISH_POINTER_LINE_PREFIX) }
                .joinToString("\n")
                .trimEnd()
            NovelWorkspaceMarkdown.render(
                fields = parsed.fields.toList(),
                aliases = parsed.lists["aliases"].orEmpty(),
                body = (if (kept.isEmpty()) "" else "$kept\n\n") + pointerLine,
            )
        }
        store.write(plotPath, rendered)
        return commitTree(
            projectDirectory = projectDirectory,
            branchId = branchId,
            branchSlug = branchSlug,
            message = NovelWorkspaceLedger.Message.PLOT_POINTER,
        )
    }

    /**
     * Author manual edit: save a chapter's title/body and commit. Host identity
     * (id/ordinal) is preserved; only title and body change. Editing a middle chapter
     * records the unresolved gate via the shared commitTree hook.
     */
    fun saveChapterEdit(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterPath: String,
        title: String,
        body: String,
    ): NovelWorkspaceCommit {
        assertNoGhostwriteOwner(projectDirectory, branchSlug)
        requirePathUnderActiveBranch(projectDirectory, chapterPath)
        val store = NovelWorkspaceStore(projectDirectory)
        val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
        val previous = mapOf(chapterPath to store.read(chapterPath))
        val existing = store.read(chapterPath)
            ?: throw NovelWorkspaceIoError("章节不存在：$chapterPath")
        val parsed = NovelWorkspaceMarkdown.parseFile(existing)
        val fields = parsed.fields.toMutableMap()
        fields["title"] = title
        store.write(
            chapterPath,
            NovelWorkspaceMarkdown.render(
                fields = fields.toList(),
                aliases = parsed.lists["aliases"].orEmpty(),
                body = body,
            ),
        )
        val commit = commitTree(
            projectDirectory = projectDirectory,
            branchId = branchId,
            branchSlug = branchSlug,
            message = NovelWorkspaceLedger.Message.MANUAL_EDIT,
        )
        NovelWorkspaceUndo.save(
            NovelWorkspaceUndoRecord(
                commitId = commit.id,
                parentCommitId = commit.parentId,
                files = previous,
                unresolvedBefore = unresolvedBefore,
                branchSlug = branchSlug,
            ),
            projectDirectory,
        )
        return commit
    }

    /**
     * Author manual edit of a NON-chapter file from the 设定 tab: setting cards (free
     * paths) and foreshadowing nodes (protected plot/ paths). Only the body changes —
     * front matter (identity, status, relations) is host-owned and re-rendered verbatim,
     * same rule as [saveChapterEdit]. Saving commits 「手改」 (the click is the author's
     * approval) and captures undo, so the tab keeps the 保存/撤销 conventions of the
     * chapter editor. manifest/project/branch.md and other host-owned paths are refused.
     */
    fun saveFileEdit(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        path: String,
        body: String,
    ): NovelWorkspaceCommit {
        assertNoGhostwriteOwner(projectDirectory, branchSlug)
        requirePathUnderActiveBranch(projectDirectory, path)
        val isProtectedNonChapter = NovelWorkspacePaths.isProtectedPath(path) &&
            path.split('/').getOrNull(2) == "plot"
        if (!NovelWorkspacePaths.isFreeWritePath(path) && !isProtectedNonChapter) {
            throw NovelWorkspaceIoError("该文件由宿主管理，不能编辑：$path")
        }
        val store = NovelWorkspaceStore(projectDirectory)
        val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(projectDirectory)
        val previous = mapOf(path to store.read(path))
        val existing = store.read(path)
        val rendered = if (existing != null && existing.trim().startsWith("---")) {
            val parsed = NovelWorkspaceMarkdown.parseFile(existing)
            NovelWorkspaceMarkdown.render(
                fields = parsed.fields.toList(),
                aliases = parsed.lists["aliases"].orEmpty(),
                body = body,
            )
        } else {
            body
        }
        store.write(path, rendered)
        val commit = commitTree(
            projectDirectory = projectDirectory,
            branchId = branchId,
            branchSlug = branchSlug,
            message = NovelWorkspaceLedger.Message.MANUAL_EDIT,
        )
        NovelWorkspaceUndo.save(
            NovelWorkspaceUndoRecord(
                commitId = commit.id,
                parentCommitId = commit.parentId,
                files = previous,
                unresolvedBefore = unresolvedBefore,
                branchSlug = branchSlug,
            ),
            projectDirectory,
        )
        return commit
    }

    /**
     * Commit the current tree for a branch: append commit, advance the branch head,
     * keep the global head mirroring only the previously-mirrored branch, run the
     * unresolved (middle-edit) hook, refresh the checkout, and persist the ledger.
     *
     * [armsUnresolvedGate]=false is reserved for polish commits: D-D invalidates the
     * chapters after an edited middle chapter because their content may no longer
     * follow from it — a polish commit keeps every fact, so nothing after it becomes
     * invalid and the gate must stay silent.
     */
    private fun commitTree(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        message: String,
        armsUnresolvedGate: Boolean = true,
        commitId: String = UUID.randomUUID().toString().uppercase(),
        onLedgerPersisted: () -> Unit = {},
    ): NovelWorkspaceCommit {
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val parentId = ledger.heads[branchId] ?: ledger.head
        val commit = NovelWorkspaceLedger.makeCommit(
            id = commitId,
            parentId = parentId,
            files = store.fileTree(),
            message = message,
            createdAt = Instant.now(),
        )
        val withCommit = NovelWorkspaceLedger.appending(commit, ledger).copy(
            heads = ledger.heads + (branchId to commitId),
        )
        // head mirrors the branch that was mirrored before this commit; approving or
        // collecting on a side branch must not drag the global pointer away from it.
        val mirrorsBranch = ledger.head == null || ledger.heads[branchId] == ledger.head
        val updated = if (mirrorsBranch) withCommit else withCommit.copy(head = ledger.head)
        NovelWorkspaceLedger.save(updated, projectDirectory)
        onLedgerPersisted()
        // D-D: a middle-chapter edit invalidates everything after it — record the range.
        // Polish commits (armsUnresolvedGate=false) keep all facts and are exempt.
        if (armsUnresolvedGate) {
            NovelWorkspaceLedger.firstUnresolvedOrdinalAfterEdit(
                store,
                updated,
                branchSlug,
                commit,
            )?.let { fromOrdinal ->
                NovelWorkspaceUnresolvedStore.set(projectDirectory, branchSlug, fromOrdinal, commitId)
            }
        }
        store.materializeCheckout()
        return commit
    }

    /**
     * Identity stays host-owned (matches iOS): the model's front matter is discarded,
     * only its body is kept; an existing file's front matter is re-rendered verbatim.
     * New protected files get host-synthesized identity from the path.
     */
    private fun mergedContent(store: NovelWorkspaceStore, entry: NovelWorkspaceWriteEntry): String {
        val parsedNew = NovelWorkspaceMarkdown.parseFile(entry.content)
        // fields empty → bare body (a leading `---` thematic break is not front matter).
        val body = parsedNew.body
        val existing = store.read(entry.path)
        if (existing != null && existing.trim().startsWith("---")) {
            val parsedOld = NovelWorkspaceMarkdown.parseFile(existing)
            return NovelWorkspaceMarkdown.render(
                fields = parsedOld.fields.toList(),
                aliases = parsedOld.lists["aliases"].orEmpty(),
                body = body,
            )
        }
        if (existing != null) return body
        return NovelWorkspaceMarkdown.render(
            fields = synthesizedFields(entry.path, parsedNew.fields["title"]),
            body = body,
        )
    }

    private fun synthesizedFields(path: String, modelTitle: String?): List<Pair<String, String>> {
        val segments = path.split('/')
        val section = segments.getOrNull(2)
        return when (section) {
            "chapters" -> listOf(
                "id" to UUID.randomUUID().toString().uppercase(),
                "kind" to "chapter",
                "title" to (modelTitle ?: NovelWorkspacePaths.fileNameTitle(path)),
                "ordinal" to (NovelWorkspacePaths.chapterOrdinalFromPath(path)?.toString() ?: "1"),
            )
            "plot" -> listOf(
                "id" to UUID.randomUUID().toString().uppercase(),
                "kind" to "plot",
                "title" to (modelTitle ?: NovelWorkspacePaths.fileNameTitle(path)),
            )
            else -> listOf(
                "id" to UUID.randomUUID().toString().uppercase(),
                "kind" to "chapter",
                "title" to (modelTitle ?: NovelWorkspacePaths.fileNameTitle(path)),
            )
        }
    }

    fun reject(proposalId: String) {
        _pendingProposals.value = _pendingProposals.value.filterNot { it.id == proposalId }
    }

    private fun registerProposal(
        request: TurnRequest,
        entries: List<NovelWorkspaceWriteEntry>,
    ): NovelWorkspaceWriteProposal {
        val store = NovelWorkspaceStore(request.projectDirectory)
        val ledger = NovelWorkspaceLedger.load(request.projectDirectory)
        val plan = store.read(NovelWorkspacePaths.branchPrefix(request.branchSlug) + "/plan/this-chapter.md")
            ?.let(NovelWorkspaceMarkdown::parseFile)
        val planBody = plan?.body?.trim().orEmpty()
        val proposal = NovelWorkspaceWriteProposal(
            id = UUID.randomUUID().toString().uppercase(),
            projectDirectory = request.projectDirectory,
            branchId = request.branchId,
            branchSlug = request.branchSlug,
            baseHeadId = ledger.headOf(request.branchId)?.id,
            baseTreeDigest = NovelWorkspaceLedger.treeSHA256(store.fileTree()),
            planId = plan?.fields?.get("id")?.takeIf { it.isNotBlank() },
            planDigest = planBody.takeIf { it.isNotBlank() }?.let(::sha256Hex),
            entries = entries,
            createdAt = Instant.now(),
        )
        _pendingProposals.value = _pendingProposals.value + proposal
        return proposal
    }

    // Join only Text parts: tool parts would otherwise leave separator artifacts.
    private fun assistantTextOf(messages: List<UIMessage>): String = messages
        .lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        .orEmpty()

    private fun reasoningOf(messages: List<UIMessage>): String = messages
        .lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Reasoning>()
        ?.lastOrNull()
        ?.reasoning
        .orEmpty()

    /**
     * 兼容旧单参调用：按当前活跃分支（.amber/branch.json 标记，回退主线）判定。
     * 多分支代码路径应使用 [canUndo] 的双参形式显式传入分支。
     */
    fun canUndo(projectDirectory: File): Boolean =
        canUndo(projectDirectory, app.amber.feature.novelworkspace.NovelWorkspaceBranches.activeSlug(projectDirectory))

    /**
     * True when a one-level undo is available for THIS branch: the undo record must
     * belong to [branchSlug]（书级 undo.json 的分支绑定——分支不匹配一律视为无 undo，
     * 防 cross-branch 恢复写坏数据；历史 null 记录按主线解释，见 NovelWorkspaceUndoRecord）
     * and match the branch's own head commit.
     */
    fun canUndo(projectDirectory: File, branchSlug: String): Boolean {
        val undo = NovelWorkspaceUndo.load(projectDirectory) ?: return false
        if (!undoBelongsToBranch(undo, projectDirectory, branchSlug)) return false
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val store = NovelWorkspaceStore(projectDirectory)
        val branchId = NovelWorkspaceLedger.branchId(store, ledger, branchSlug) ?: return false
        return ledger.heads[branchId] == undo.commitId
    }

    /** Legacy (null-slug) records can only have come from the main branch. */
    private fun undoBelongsToBranch(
        undo: NovelWorkspaceUndoRecord,
        projectDirectory: File,
        branchSlug: String,
    ): Boolean {
        val recordBranch = undo.branchSlug ?: NovelWorkspaceManifest.parse(
            NovelWorkspaceStore(projectDirectory).read(NovelWorkspacePaths.MANIFEST) ?: "",
        ).mainBranch
        return recordBranch == branchSlug
    }

    /** 兼容旧单参调用：对当前活跃分支执行撤销（见 [undoLast] 双参形式）。 */
    fun undoLast(projectDirectory: File): Boolean =
        undoLast(projectDirectory, app.amber.feature.novelworkspace.NovelWorkspaceBranches.activeSlug(projectDirectory))

    /**
     * 撤销最近一笔: restore the previous contents captured at the last commit, move the
     * BRANCH head back, drop the undone commit, clear the undo record, refresh checkout.
     * Only the undo record's branch head commit can be undone (single level) and the
     * record must belong to [branchSlug] — an undo saved on another branch is invisible
     * here (mismatch = no undo; see [canUndo]).
     *
     * 多分支 fork 安全：被撤销 commit 若仍是其他分支的 head（或更后 commit 的 parent，
     * 即分叉点），则只把本分支 head 退回、commit 本体保留在链上；仅当无任何引用时才
     * 从 commits 中删除。全局 head 只在恰好镜像本分支时随动。
     */
    fun undoLast(projectDirectory: File, branchSlug: String): Boolean {
        assertNoGhostwriteOwner(projectDirectory)
        val undo = NovelWorkspaceUndo.load(projectDirectory) ?: return false
        if (!undoBelongsToBranch(undo, projectDirectory, branchSlug)) return false
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val branchId = NovelWorkspaceLedger.branchId(store, ledger, branchSlug) ?: return false
        if (ledger.heads[branchId] != undo.commitId) return false
        for ((path, previous) in undo.files) {
            if (previous == null) store.delete(path) else store.write(path, previous)
        }
        val stillReferenced = ledger.commits.any { it.parentId == undo.commitId } ||
            ledger.heads.any { (branch, headId) -> branch != branchId && headId == undo.commitId }
        // The undone branch head moves to the commit's parent; a branch-root undo
        // removes that head entry entirely.
        val updatedHeads = if (undo.parentCommitId != null) {
            ledger.heads + (branchId to undo.parentCommitId!!)
        } else {
            ledger.heads - branchId
        }
        val updated = ledger.copy(
            head = if (ledger.head == undo.commitId) undo.parentCommitId else ledger.head,
            heads = updatedHeads,
            commits = if (stillReferenced) {
                ledger.commits
            } else {
                ledger.commits.filterNot { it.id == undo.commitId }
            },
        )
        NovelWorkspaceLedger.save(updated, projectDirectory)
        store.materializeCheckout()
        val unresolvedBefore = undo.unresolvedBefore
        if (unresolvedBefore != null) {
            NovelWorkspaceUnresolvedStore.save(unresolvedBefore, projectDirectory)
        } else {
            // Legacy undo records predate unresolvedBefore. Only remove the gate
            // created by the commit being undone; preserve unrelated older gates.
            val current = NovelWorkspaceUnresolvedStore.load(projectDirectory)
            val restored = current.copy(
                branches = current.branches.filterValues { it.sinceCommitId != undo.commitId },
            )
            if (restored != current) NovelWorkspaceUnresolvedStore.save(restored, projectDirectory)
        }
        NovelWorkspaceUndo.clear(projectDirectory)
        return true
    }

    private fun assertNoGhostwriteOwner(projectDirectory: File, branchSlug: String? = null) {
        val owner = if (branchSlug == null) {
            NovelWorkspaceGhostwriteJobs.listActive(projectDirectory).firstOrNull()
        } else {
            NovelWorkspaceGhostwriteJobs.activeFor(projectDirectory, branchSlug)
        }
        if (owner != null) {
            throw NovelWorkspaceIoError("当前分支仍被代笔批次占用，请先让批次完成或取消后再修改正文")
        }
    }

    /**
     * 防御层：作者手改保存（saveChapterEdit/saveFileEdit）的目标若带分支前缀，该前缀
     * 必须等于磁盘上的当前活跃分支（.amber/branch.json 标记，同 [NovelWorkspaceBranches.activeSlug]
     * 的解析口径）。切分支后残留的明细视图（上一分支打开的章节/设定编辑器）会把旧分支
     * 路径递进保存入口；不校验时旧分支文件被写盘、却以新分支的 branchId 推进 head 并记
     * undo——账本污染。UI 层在切分支时已重置明细视图，这里是 runtime 的第二道。
     * 不带分支前缀的书级路径（setting/、drafts/、inbox/）跨分支共享，不在此约束内。
     */
    private fun requirePathUnderActiveBranch(projectDirectory: File, path: String) {
        val segments = path.split('/')
        val pathBranch = segments
            .takeIf { it.size >= 3 && it[0] == NovelWorkspacePaths.BRANCHES_DIR }
            ?.get(1)
            ?: return
        val active = NovelWorkspaceBranches.activeSlug(projectDirectory)
        if (pathBranch != active) {
            throw NovelWorkspaceIoError(
                "该文件属于分支「$pathBranch」，当前活跃分支是「$active」；请切回该分支后再保存",
            )
        }
    }

    companion object {
        fun readProjectTitle(store: NovelWorkspaceStore): String =
            NovelWorkspaceProjectTitle.read(store)

        /** Polish pointer line marker in plot/current.md (replaced, never accumulated). */
        private const val POLISH_POINTER_LINE_PREFIX = "（润色指针："

        private fun commitMessageFor(paths: List<String>): String = when {
            paths.any { "/chapters/" in it } -> NovelWorkspaceLedger.Message.COLLECTION
            paths.any { "/plot/" in it } -> NovelWorkspaceLedger.Message.PLOT_POINTER
            else -> NovelWorkspaceLedger.Message.GENERIC
        }
    }
}

data class NovelWorkspaceWriteProposal(
    val id: String,
    val projectDirectory: File,
    val branchId: String,
    val branchSlug: String,
    val baseHeadId: String?,
    val baseTreeDigest: String,
    val planId: String? = null,
    val planDigest: String? = null,
    val entries: List<NovelWorkspaceWriteEntry>,
    val createdAt: Instant,
)

/** Where a collected draft lands in the manuscript. */
sealed interface NovelWorkspaceCollectTarget {
    /** Whole draft becomes a brand-new chapter appended at the end. */
    data object NewChapter : NovelWorkspaceCollectTarget

    /** Draft appended to the end of an existing chapter. */
    data class AppendToChapter(val chapterPath: String) : NovelWorkspaceCollectTarget

    /** Existing chapter body replaced by the draft. */
    data class ReplaceChapter(val chapterPath: String) : NovelWorkspaceCollectTarget
}
