package app.amber.feature.novel.workspace

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.Generator
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceContextAssembler
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceProjectTitle
import app.amber.feature.novelworkspace.NovelWorkspaceSlug
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUndoRecord
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
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
class NovelWorkspaceRuntime(private val generator: Generator) {

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
    )

    private val _pendingProposals = MutableStateFlow<List<NovelWorkspaceWriteProposal>>(emptyList())
    val pendingProposals: StateFlow<List<NovelWorkspaceWriteProposal>> = _pendingProposals.asStateFlow()

    fun runTurn(request: TurnRequest): Flow<TurnEvent> = flow {
        val store = NovelWorkspaceStore(request.projectDirectory)
        val ownerAtStart = NovelWorkspaceGhostwriteJobs.activeFor(
            request.projectDirectory,
            request.branchSlug,
        )
        if (request.ownerJobId == null && ownerAtStart != null) {
            emit(TurnEvent.Failed("当前分支仍被代笔批次占用，请先让批次完成或取消后再修改正文"))
            return@flow
        }
        if (request.ownerJobId != null &&
            (ownerAtStart?.id != request.ownerJobId ||
                ownerAtStart.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
                ownerAtStart.executionKey != request.ownerExecutionId)
        ) {
            emit(TurnEvent.Failed("代笔已暂停或取消"))
            return@flow
        }
        val batch = NovelWorkspaceWriteBatch()
        val session = NovelWorkspaceToolSession(
            store = store,
            branchSlug = request.branchSlug,
            projectTitle = readProjectTitle(store),
            batch = batch,
            autoApproveCanon = request.autoApproveCanon,
        )
        // Context-engineering core: inject the constraint brief (plot state, open
        // foreshadowing, decisions, and the plan's entity subgraph) every turn.
        val brief = runCatching {
            NovelWorkspaceContextAssembler.assemble(
                store,
                request.branchSlug,
                flags = request.injection ?: app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags(),
            )
        }.getOrDefault("")
        val systemPrompt = if (brief.isBlank()) {
            request.systemPrompt
        } else {
            request.systemPrompt +
                "\n\n## 工作区状态简报（host 注入，以下为当前正史约束，不得与之矛盾）\n" + brief
        }
        var emittedText = ""
        var emittedReasoning = ""
        val seenTools = mutableSetOf<String>()
        var canonLedgerPersisted = false
        var canonRollbackHandled = false
        try {
            val messages = buildList {
                if (systemPrompt.isNotBlank()) {
                    add(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(systemPrompt))))
                }
                add(UIMessage.user(request.userText))
            }
            generator.generateText(
                settings = request.settings,
                model = request.model,
                messages = messages,
                tools = session.tools(),
                maxSteps = request.maxSteps,
                // The workspace write tool has its own strict path and author gates.
                // Trust only this named tool; do not enable blanket auto-approval.
                autoApprovedToolNames = setOf("novel_workspace_write"),
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
                rollbackUncommittedCanon(request, batch)
                emit(TurnEvent.Failed("代笔已暂停或取消"))
                return@flow
            }
            val proposal = if (batch.isEmpty()) {
                // Free writes (setting/inbox/drafts) already hit disk; commit them so the
                // ledger and worktree never drift apart.
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
                        emit(TurnEvent.Failed("代笔已暂停或取消"))
                        return@flow
                    }
                }
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
                        commitTree(
                            projectDirectory = request.projectDirectory,
                            branchId = request.branchId,
                            branchSlug = request.branchSlug,
                            message = request.autoCommitMessage,
                            onLedgerPersisted = { canonLedgerPersisted = true },
                        )
                    } catch (error: Exception) {
                        if (!canonLedgerPersisted) {
                            rollbackUncommittedCanon(request, batch)
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
                    rollbackUncommittedCanon(request, batch)
                    emit(TurnEvent.Failed("代笔已暂停或取消"))
                    return@flow
                }
                NovelWorkspaceUndo.save(
                    NovelWorkspaceUndoRecord(
                        commitId = committed.id,
                        parentCommitId = committed.parentId,
                        files = batch.previousSnapshot(),
                        unresolvedBefore = unresolvedBefore,
                    ),
                    request.projectDirectory,
                )
                null
            } else {
                registerProposal(request, batch.snapshot())
            }
            emit(TurnEvent.Completed(emittedText, proposal))
        } catch (error: CancellationException) {
            // Same orphan risk as a failed turn: auto-approved canon writes may
            // already be on disk with no commit. Restore, then propagate.
            if (!canonLedgerPersisted && !canonRollbackHandled) rollbackUncommittedCanon(request, batch)
            throw error
        } catch (error: Exception) {
            if (canonLedgerPersisted) {
                // The chapter is already durable. Reporting a failed turn would make
                // the batch retry the same ordinal after a checkout/undo side-effect error.
                emit(TurnEvent.Completed(emittedText, proposal = null))
            } else {
                if (!canonRollbackHandled) rollbackUncommittedCanon(request, batch)
                emit(TurnEvent.Failed(error.message ?: "工作区生成失败"))
            }
        }
    }

    /**
     * A failed/cancelled unattended turn must not leave files on disk that no commit
     * owns. Interactive free writes still land immediately; unattended ones are buffered.
     */
    private fun rollbackUncommittedCanon(request: TurnRequest, batch: NovelWorkspaceWriteBatch) {
        if (!request.autoApproveCanon) return
        val store = NovelWorkspaceStore(request.projectDirectory)
        for ((path, previous) in batch.previousSnapshot()) {
            if (previous == null) store.delete(path) else store.write(path, previous)
        }
    }

    /** Author approved the gate: apply every entry, one commit, checkout refreshed. */
    fun approve(proposalId: String) {
        val proposal = _pendingProposals.value.firstOrNull { it.id == proposalId } ?: return
        assertNoGhostwriteOwner(proposal.projectDirectory, proposal.branchSlug)
        val store = NovelWorkspaceStore(proposal.projectDirectory)
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
            ),
            proposal.projectDirectory,
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
                NovelWorkspaceUndo.save(
                    NovelWorkspaceUndoRecord(
                        commitId = commit.id,
                        parentCommitId = commit.parentId,
                        files = previous,
                        unresolvedBefore = unresolvedBefore,
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
        ) ?: throw NovelWorkspaceIoError("代笔已暂停或取消")
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
            ),
            projectDirectory,
        )
        return commit
    }

    /**
     * Commit the current tree for a branch: append commit, advance the branch head,
     * keep the global head mirroring only the previously-mirrored branch, run the
     * unresolved (middle-edit) hook, refresh the checkout, and persist the ledger.
     */
    private fun commitTree(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        message: String,
        onLedgerPersisted: () -> Unit = {},
    ): NovelWorkspaceCommit {
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val parentId = ledger.heads[branchId] ?: ledger.head
        val commitId = UUID.randomUUID().toString().uppercase()
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
        NovelWorkspaceLedger.firstUnresolvedOrdinalAfterEdit(
            store,
            updated,
            branchSlug,
            commit,
        )?.let { fromOrdinal ->
            NovelWorkspaceUnresolvedStore.set(projectDirectory, branchSlug, fromOrdinal, commitId)
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
        val proposal = NovelWorkspaceWriteProposal(
            id = UUID.randomUUID().toString().uppercase(),
            projectDirectory = request.projectDirectory,
            branchId = request.branchId,
            branchSlug = request.branchSlug,
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

    /** True when there is a one-level undo available for this project. */
    fun canUndo(projectDirectory: File): Boolean {
        val undo = NovelWorkspaceUndo.load(projectDirectory) ?: return false
        return NovelWorkspaceLedger.load(projectDirectory).head == undo.commitId
    }

    /**
     * 撤销最近一笔: restore the previous contents captured at the last commit, move the
     * branch head back, drop the undone commit, clear the undo record, refresh checkout.
     * Only the ledger's current head commit can be undone (single level).
     */
    fun undoLast(projectDirectory: File): Boolean {
        assertNoGhostwriteOwner(projectDirectory)
        val undo = NovelWorkspaceUndo.load(projectDirectory) ?: return false
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val head = ledger.headCommit ?: return false
        if (head.id != undo.commitId) return false
        for ((path, previous) in undo.files) {
            if (previous == null) store.delete(path) else store.write(path, previous)
        }
        val updated = ledger.copy(
            head = undo.parentCommitId,
            commits = ledger.commits.filterNot { it.id == undo.commitId },
            // Branch heads pointing at the undone commit move to its parent; if the undone
            // commit was a branch root (no parent), that head entry is removed.
            heads = ledger.heads.mapNotNull { (branch, headId) ->
                when {
                    headId != undo.commitId -> branch to headId
                    undo.parentCommitId != null -> branch to undo.parentCommitId!!
                    else -> null
                }
            }.toMap(),
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

    companion object {
        fun readProjectTitle(store: NovelWorkspaceStore): String =
            NovelWorkspaceProjectTitle.read(store)

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
