package app.amber.feature.novel

import app.amber.feature.novel.domain.NovelBranchReducer
import app.amber.feature.novel.domain.NovelCollectCommand
import app.amber.feature.novel.domain.NovelCollectionReducer
import app.amber.feature.novel.domain.NovelCreateProjectCommand
import app.amber.feature.novel.domain.NovelDeleteMaterialCommand
import app.amber.feature.novel.domain.NovelError
import app.amber.feature.novel.domain.NovelGenerationReducer
import app.amber.feature.novel.domain.NovelDocumentValidator
import app.amber.feature.novel.domain.NovelGenerationStartArtifacts
import app.amber.feature.novel.domain.NovelInternalRunRequest
import app.amber.feature.novel.domain.NovelManualEditReducer
import app.amber.feature.novel.domain.NovelManualSyncReducer
import app.amber.feature.novel.domain.NovelMutationContext
import app.amber.feature.novel.domain.NovelPolishReducer
import app.amber.feature.novel.domain.NovelReduceResult
import app.amber.feature.novel.domain.NovelReducer
import app.amber.feature.novel.domain.NovelRenameProjectCommand
import app.amber.feature.novel.domain.NovelReviseMaterialCommand
import app.amber.feature.novel.domain.NovelSetModelPolicyCommand
import app.amber.feature.novel.domain.NovelSetPolishPreferenceCommand
import app.amber.feature.novel.domain.NovelStructuredOutputDecoder
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelFailure
import app.amber.feature.novel.model.NovelGenerationGranularity
import app.amber.feature.novel.model.NovelGenerationReceiptRecord
import app.amber.feature.novel.model.NovelLoadedProject
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelMaterialRevisionId
import app.amber.feature.novel.model.NovelMessageId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelProjectSummary
import app.amber.feature.novel.model.NovelReceiptId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelRunInterruptionReason
import app.amber.feature.novel.model.NovelRunKind
import app.amber.feature.novel.model.NovelRunStatus
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelSessionMessageKind
import app.amber.feature.novel.model.NovelSessionMode
import app.amber.feature.novel.model.NovelSessionRole
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.persistence.NovelFileProjectRepository
import app.amber.feature.novel.persistence.NovelProjectPersisting
import app.amber.feature.novel.persistence.NovelRecoveryStore
import app.amber.feature.novel.runtime.NovelInjectionPlanner
import app.amber.feature.novel.runtime.NovelModelEvent
import app.amber.feature.novel.runtime.NovelModelMessage
import app.amber.feature.novel.runtime.NovelModelParameters
import app.amber.feature.novel.runtime.NovelModelPurpose
import app.amber.feature.novel.runtime.NovelModelRequest
import app.amber.feature.novel.runtime.NovelModelRunning
import app.amber.feature.novel.runtime.NovelPromptCatalog
import app.amber.feature.novel.runtime.NovelPromptKind
import app.amber.feature.novel.serialization.NovelPackageCodec
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class DefaultNovelCreation(
    private val repository: NovelProjectPersisting,
    private val modelRunning: NovelModelRunning,
    private val appScope: CoroutineScope,
    private val recoveryStore: NovelRecoveryStore? = null,
) : NovelCreation {
    private val writeMutex = Mutex()
    private val _projectList = MutableStateFlow<List<NovelProjectSummary>>(emptyList())
    override val projectList: StateFlow<List<NovelProjectSummary>> = _projectList.asStateFlow()

    private class LiveRun(
        val projectId: NovelProjectId,
        val runId: NovelRunId,
        val events: MutableSharedFlow<NovelRunEvent>,
        @Volatile var job: Job,
        @Volatile var partial: String = "",
        @Volatile var terminal: Boolean = false,
        @Volatile var branchId: NovelBranchId? = null,
        @Volatile var sessionId: NovelSessionId? = null,
        @Volatile var messageId: NovelMessageId? = null,
        @Volatile var baseRevision: Long = 0,
        val sequence: AtomicLong = AtomicLong(0),
        @Volatile var lastRecoveryBytes: Int = 0,
        @Volatile var lastRecoveryAtMs: Long = 0,
    )

    private val liveRuns = ConcurrentHashMap<String, LiveRun>()
    private val interruptTombstones = ConcurrentHashMap.newKeySet<String>()

    override suspend fun refreshProjects() {
        _projectList.value = repository.listProjects()
    }

    override suspend fun snapshot(query: NovelQuery): NovelSnapshot = writeMutex.withLock {
        when (query) {
            NovelQuery.Projects -> {
                val items = repository.listProjects()
                _projectList.value = items
                NovelSnapshot.Projects(items)
            }
            is NovelQuery.Project -> {
                val loaded = loadProjectRecoveringOrphanedRuns(query.projectId)
                NovelSnapshot.Project(loaded.document, loaded.access, loaded.primaryFailure)
            }
            is NovelQuery.BranchMarkdown -> {
                val loaded = loadProjectRecoveringOrphanedRuns(query.projectId)
                assertNotBusy(loaded)
                val md = NovelPackageCodec.exportMarkdown(loaded.document, query.branchId)
                NovelSnapshot.Markdown("${loaded.document.project.name}.md", md)
            }
            is NovelQuery.ProjectPackage -> {
                val loaded = loadProjectRecoveringOrphanedRuns(query.projectId)
                assertNotBusy(loaded)
                val envelope = NovelPackageCodec.encode(loaded.document)
                val bytes = NovelPackageCodec.let {
                    app.amber.feature.novel.serialization.NovelSwiftCompatibleJson.encodePackageEnvelope(envelope)
                }
                NovelSnapshot.PackageBytes("${loaded.document.project.name}.ambernovel", bytes)
            }
        }
    }

    override suspend fun perform(intent: NovelIntent): NovelOutcome {
        // Collect may await the provider — must not hold writeMutex across that await.
        val outcome = when (intent) {
            is NovelIntent.CollectCandidate -> collectCandidate(intent)
            is NovelIntent.SyncManualEdits -> syncManualEdits(intent)
            is NovelIntent.AdoptPolishCandidate -> adoptPolish(intent)
            else -> writeMutex.withLock {
                when (intent) {
                    is NovelIntent.CreateProject -> createProject(intent)
                    is NovelIntent.RenameProject -> renameProject(intent)
                    is NovelIntent.DeleteProject -> deleteProject(intent)
                    is NovelIntent.RestorePrevious -> restorePrevious(intent)
                    is NovelIntent.SetModelPolicy -> setModelPolicy(intent)
                    is NovelIntent.ClearStateSyncModelPolicy -> clearStateSyncModelPolicy(intent)
                    is NovelIntent.SetPolishPreference -> setPolishPreference(intent)
                    is NovelIntent.ResolveProposal -> resolveProposal(intent)
                    is NovelIntent.ForkBranch -> forkBranch(intent)
                    is NovelIntent.UndoHead -> undoHead(intent)
                    is NovelIntent.RestoreChapterVersion -> restoreChapterVersion(intent)
                    is NovelIntent.ArchiveDiscussion -> archiveDiscussion(intent)
                    is NovelIntent.SaveManualEdit -> saveManualEdit(intent)
                    is NovelIntent.RenameBranch -> renameBranch(intent)
                    is NovelIntent.SetMainBranch -> setMainBranch(intent)
                    is NovelIntent.ReviseMaterial -> reviseMaterial(intent)
                    is NovelIntent.DeleteMaterial -> deleteMaterial(intent)
                    is NovelIntent.ImportPackage -> importPackage(intent)
                    is NovelIntent.SetChapterDiscarded -> setChapterDiscarded(intent)
                    is NovelIntent.RetryTerminal ->
                        throw NovelError.InvalidInput("RetryTerminal not needed for completed runs")
                    is NovelIntent.CollectCandidate,
                    is NovelIntent.SyncManualEdits,
                    is NovelIntent.AdoptPolishCandidate,
                    -> error("unreachable")
                }
            }
        }
        writeMutex.withLock {
            _projectList.value = repository.listProjects()
        }
        return outcome
    }

    override fun start(request: NovelRunRequest): NovelRun {
        val runId = NovelRunId.generate()
        val events = MutableSharedFlow<NovelRunEvent>(
            replay = 16,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        // Register before launch so interrupt can always find the run.
        val live = LiveRun(request.projectId, runId, events, Job())
        liveRuns[runId.rawValue] = live
        val job = appScope.launch {
            try {
                runGeneration(runId, request, events)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                events.tryEmit(
                    NovelRunEvent.Failed(
                        code = (error as? NovelError)?.code() ?: "generation_error",
                        message = error.message ?: "Generation failed",
                    ),
                )
            } finally {
                liveRuns.remove(runId.rawValue)
            }
        }
        live.job = job
        return NovelRun(runId, events)
    }

    override fun interrupt(request: NovelInterruptRequest) {
        val reason = when (request.reason) {
            NovelInterruptReason.User -> NovelRunInterruptionReason.User
            NovelInterruptReason.Background -> NovelRunInterruptionReason.Background
            NovelInterruptReason.RouteExit -> NovelRunInterruptionReason.RouteExit
        }
        val targets = if (request.runId != null) {
            listOfNotNull(liveRuns[request.runId.rawValue])
        } else {
            liveRuns.values.filter { it.projectId == request.projectId }
        }
        for (live in targets) {
            interruptTombstones.add(live.runId.rawValue)
            modelRunning.cancel(live.runId)
            live.job.cancel()
            appScope.launch {
                finalizeInterrupt(live, reason)
            }
        }
    }

    private suspend fun runGeneration(
        runId: NovelRunId,
        request: NovelRunRequest,
        events: MutableSharedFlow<NovelRunEvent>,
    ) {
        if (runId.rawValue in interruptTombstones) {
            events.tryEmit(NovelRunEvent.Interrupted(""))
            return
        }
        val reserved = writeMutex.withLock {
            reserveRun(runId, request)
        }
        events.tryEmit(NovelRunEvent.Started)
        val live = liveRuns[runId.rawValue]
        val accumulated = StringBuilder()
        try {
            modelRunning.start(reserved.modelRequest).collect { event ->
                if (runId.rawValue in interruptTombstones) return@collect
                when (event) {
                    is NovelModelEvent.TextDelta -> {
                        accumulated.append(event.text)
                        live?.partial = accumulated.toString()
                        maybeFlushRecovery(live)
                        events.tryEmit(NovelRunEvent.Delta(event.text))
                    }
                    is NovelModelEvent.TextReplacement -> {
                        accumulated.clear()
                        accumulated.append(event.text)
                        live?.partial = accumulated.toString()
                        maybeFlushRecovery(live, force = true)
                        // Full replace (not append) — surface cumulative text so UI can reset.
                        events.tryEmit(NovelRunEvent.Replace(event.text))
                    }
                    NovelModelEvent.Completed -> Unit
                    is NovelModelEvent.Failed -> throw NovelError.ProviderError(event.message)
                }
            }
            if (runId.rawValue in interruptTombstones) {
                // finalizeInterrupt already emits Interrupted — do not double-emit.
                finalizeInterrupt(liveRuns[runId.rawValue] ?: return, NovelRunInterruptionReason.User)
                return
            }
            var didComplete = false
            writeMutex.withLock {
                if (live?.terminal == true) return@withLock
                if (runId.rawValue in interruptTombstones) return@withLock
                val loaded = repository.loadProject(request.projectId)
                val before = loaded.document.project.revision
                val (next, _) = NovelGenerationReducer.complete(
                    runId, accumulated.toString(), loaded.document,
                )
                if (next.project.revision != before) {
                    repository.commitProject(next, expectedRevision = before)
                    live?.terminal = true
                    didComplete = true
                    recoveryStore?.delete(request.projectId, runId)
                }
            }
            if (didComplete) {
                events.tryEmit(NovelRunEvent.Completed(accumulated.toString()))
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (runId.rawValue in interruptTombstones) {
                return
            }
            var didFail = false
            writeMutex.withLock {
                if (live?.terminal == true) return@withLock
                if (runId.rawValue in interruptTombstones) return@withLock
                val loaded = repository.loadProject(request.projectId)
                val before = loaded.document.project.revision
                val (next, _) = NovelGenerationReducer.fail(
                    runId,
                    NovelFailure(
                        code = (error as? NovelError)?.code() ?: "generation_error",
                        message = error.message ?: "failed",
                        isRetryable = true,
                    ),
                    accumulated.toString(),
                    loaded.document,
                )
                if (next.project.revision != before) {
                    repository.commitProject(next, expectedRevision = before)
                    live?.terminal = true
                    didFail = true
                }
            }
            if (didFail) {
                events.tryEmit(
                    NovelRunEvent.Failed(
                        code = (error as? NovelError)?.code() ?: "generation_error",
                        message = error.message ?: "Generation failed",
                    ),
                )
            }
        }
    }

    private data class ReservedRun(
        val modelRequest: NovelModelRequest,
    )

    private suspend fun reserveRun(runId: NovelRunId, request: NovelRunRequest): ReservedRun {
        val loaded = repository.loadProject(request.projectId)
        if (loaded.access != app.amber.feature.novel.model.NovelProjectLoadAccess.ReadWrite) {
            throw NovelError.DegradedReadOnly(request.projectId)
        }
        val doc = loaded.document
        val branchId = request.branchId ?: doc.project.mainBranchID
        val branch = doc.branches.firstOrNull { it.id == branchId }
            ?: throw NovelError.BranchNotFound(branchId)

        val kind = when {
            request.kind == NovelRunKindRequest.Polish -> NovelRunKind.Polish
            request.kind == NovelRunKindRequest.Regenerate -> NovelRunKind.Regenerate
            request.kind == NovelRunKindRequest.QuickStart ||
                (doc.project.creationMode == app.amber.feature.novel.model.NovelProjectCreationMode.QuickStart &&
                    doc.settingProposals.none { !it.isResolved } &&
                    request.mode == NovelSessionModeRequest.DiscussPlan &&
                    doc.sessions.firstOrNull { it.id == branch.sessionID }?.messages.isNullOrEmpty()) ->
                NovelRunKind.QuickStart
            request.mode == NovelSessionModeRequest.DiscussPlan -> NovelRunKind.Discussion
            else -> NovelRunKind.Prose
        }
        if (kind == NovelRunKind.Polish && request.sourceChapterVersionId == null) {
            throw NovelError.InvalidInput("Polish requires sourceChapterVersionId")
        }
        if (kind == NovelRunKind.Regenerate && request.sourceChapterVersionId == null) {
            throw NovelError.InvalidInput("Regenerate requires sourceChapterVersionId")
        }
        // Prose must not carry a rewrite source — that would falsely enable replaceChapter.
        if (kind == NovelRunKind.Prose && request.sourceChapterVersionId != null) {
            throw NovelError.InvalidInput("Prose runs must not set sourceChapterVersionId")
        }
        if (kind == NovelRunKind.Regenerate) {
            val sourceId = request.sourceChapterVersionId!!
            val source = doc.chapterVersions.firstOrNull { it.id == sourceId }
                ?: throw NovelError.InvalidInput("Source chapter version not found for regenerate")
            val onBranch = branch.workingChapterSelections.any {
                it.chapterID == source.chapterID && it.versionID == sourceId
            }
            if (!onBranch) {
                throw NovelError.InvalidInput("Regenerate source is not the current head of that chapter")
            }
        }
        val granularity = when {
            kind == NovelRunKind.Regenerate -> NovelGenerationGranularity.WholeChapter
            kind != NovelRunKind.Prose -> null
            request.granularity != null -> request.granularity.toModel()
            else -> doc.project.lastGenerationGranularity
        }
        val promptKind = when (kind) {
            NovelRunKind.QuickStart -> NovelPromptKind.QuickStart
            NovelRunKind.Discussion -> NovelPromptKind.Discussion
            NovelRunKind.Prose -> when (granularity) {
                NovelGenerationGranularity.WholeChapter -> NovelPromptKind.ProseWholeChapter
                else -> NovelPromptKind.ProseContinuation
            }
            NovelRunKind.Polish -> NovelPromptKind.WholeChapterPolish
            NovelRunKind.Regenerate -> NovelPromptKind.WholeChapterRegeneration
        }
        val purpose = when (kind) {
            NovelRunKind.QuickStart -> NovelModelPurpose.QuickStart
            NovelRunKind.Discussion -> NovelModelPurpose.Discussion
            NovelRunKind.Prose, NovelRunKind.Regenerate -> NovelModelPurpose.Prose
            NovelRunKind.Polish -> NovelModelPurpose.Polish
        }

        val model = modelRunning.resolveModel(doc.project.modelPolicy)
        val plan = NovelInjectionPlanner.plan(
            document = doc,
            branchId = branchId,
            promptKind = promptKind,
            userText = request.userText,
            sourceChapterVersionId = request.sourceChapterVersionId,
        )
        val injectionReceiptId = NovelReceiptId.generate()
        val generationReceiptId = NovelReceiptId.generate()
        val operationId = NovelOperationId.generate()
        val userMessageId = NovelMessageId.generate()
        val assistantMessageId = NovelMessageId.generate()
        val candidateId = if (
            kind == NovelRunKind.Prose ||
            kind == NovelRunKind.Polish ||
            kind == NovelRunKind.Regenerate
        ) {
            NovelCandidateId.generate()
        } else null
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val injectionReceipt = NovelInjectionPlanner.toReceipt(
            plan = plan,
            receiptId = injectionReceiptId,
            runId = runId,
            projectId = request.projectId,
            branchId = branchId,
            model = model,
            createdAt = now,
        )
        val regenerateUserText = if (kind == NovelRunKind.Regenerate) {
            val source = doc.chapterVersions.first { it.id == request.sourceChapterVersionId }
            buildString {
                appendLine("请完整重写本章（可改剧情事实，消除矛盾；不要续写后续章）。")
                if (request.userText.isNotBlank()) {
                    appendLine()
                    appendLine("作者补充：")
                    appendLine(request.userText.trim())
                }
                appendLine()
                appendLine("—— 原章标题：${source.title} ——")
                append(source.content)
            }
        } else {
            request.userText
        }
        val modelRequest = NovelModelRequest(
            runID = runId,
            model = model,
            purpose = purpose,
            messages = listOf(
                NovelModelMessage(NovelModelMessage.Role.System, plan.prompt.systemText + "\n\n" + plan.contextText),
                NovelModelMessage(NovelModelMessage.Role.User, regenerateUserText),
            ),
            parameters = NovelModelParameters(),
        )
        val requestPayload = sha256HexOfUtf8(
            listOf(runId.rawValue, branchId.rawValue, kind.name, regenerateUserText).joinToString("|"),
        )
        val generationReceipt = NovelGenerationReceiptRecord(
            id = generationReceiptId,
            runID = runId,
            providerID = model.providerID,
            ownerProviderID = model.ownerProviderID,
            modelID = model.modelID,
            wireModelID = model.wireModelID,
            promptVersion = plan.prompt.version,
            injectionReceiptID = injectionReceiptId,
            parameters = modelRequest.parameters.evidenceDictionary(),
            requestSHA256 = requestPayload,
            createdAt = now,
        )
        val internal = NovelInternalRunRequest(
            id = runId,
            operationID = operationId,
            projectID = request.projectId,
            branchID = branchId,
            kind = kind,
            mode = when (kind) {
                NovelRunKind.Prose, NovelRunKind.Polish, NovelRunKind.Regenerate -> NovelSessionMode.WriteProse
                else -> NovelSessionMode.DiscussPlan
            },
            granularity = granularity,
            userText = regenerateUserText,
            userMessageID = userMessageId,
            assistantMessageID = assistantMessageId,
            candidateID = candidateId,
            generationReceiptID = generationReceiptId,
            injectionReceiptID = injectionReceiptId,
            sourceChapterVersionID = request.sourceChapterVersionId,
            expectedProjectRevision = doc.project.revision,
            expectedConfigRevision = doc.project.configRevision,
            expectedBranchHeadRevision = branch.headRevision,
            requestPayloadSHA256 = requestPayload,
        )
        if (runId.rawValue in interruptTombstones) {
            throw NovelError.InvalidInput("Run was cancelled before start")
        }
        val reduced = NovelGenerationReducer.begin(
            internal,
            NovelGenerationStartArtifacts(injectionReceipt, generationReceipt),
            doc,
            now,
        )
        repository.commitProject(reduced.document, expectedRevision = doc.project.revision)
        liveRuns[runId.rawValue]?.apply {
            this.branchId = branchId
            this.sessionId = branch.sessionID
            this.messageId = assistantMessageId
            this.baseRevision = doc.project.revision
        }
        return ReservedRun(modelRequest)
    }

    private suspend fun finalizeInterrupt(live: LiveRun?, reason: NovelRunInterruptionReason) {
        if (live == null || live.terminal) return
        var shouldEmit = false
        writeMutex.withLock {
            if (live.terminal) return
            val loaded = runCatching { repository.loadProject(live.projectId) }.getOrNull()
            if (loaded == null) {
                // Still unblock UI collectors waiting on the SharedFlow.
                live.terminal = true
                shouldEmit = true
                return@withLock
            }
            val running = loaded.document.activeRuns.any {
                it.id == live.runId && it.status == NovelRunStatus.Running
            }
            if (running) {
                val (next, _) = NovelGenerationReducer.interrupt(
                    live.runId, reason, live.partial, loaded.document,
                )
                if (next.project.revision != loaded.document.project.revision) {
                    repository.commitProject(next, expectedRevision = loaded.document.project.revision)
                }
            }
            // Always mark terminal + emit, even if reserve never landed a Running row
            // (stop during reserve window) so UI collectors do not hang forever.
            live.terminal = true
            shouldEmit = true
        }
        if (shouldEmit) {
            live.events.tryEmit(NovelRunEvent.Interrupted(live.partial))
        }
    }

    private fun assertNotBusy(loaded: NovelLoadedProject) {
        val doc = loaded.document
        if (doc.activeRuns.any { it.status == NovelRunStatus.Running } || doc.pendingOperations.isNotEmpty()) {
            throw NovelError.ProjectBusy(doc.project.id)
        }
    }

    private suspend fun loadProjectRecoveringOrphanedRuns(projectId: NovelProjectId): NovelLoadedProject {
        val loaded = repository.loadProject(projectId)
        val orphanedRuns = loaded.document.activeRuns.filter { run ->
            run.status == NovelRunStatus.Running && !liveRuns.containsKey(run.id.rawValue)
        }
        if (orphanedRuns.isEmpty()) return loaded

        val sidecars = recoveryStore?.listForProject(projectId).orEmpty()
        var recovered = loaded.document
        orphanedRuns.forEach { run ->
            val sidecar = sidecars.asSequence()
                .filter { candidate ->
                    candidate.runID == run.id &&
                        candidate.branchID == run.branchID &&
                        candidate.sessionID == run.sessionID &&
                        candidate.messageID == run.messageID &&
                        candidate.baseProjectRevision <= recovered.project.revision
                }
                .filter { candidate ->
                    runCatching { NovelDocumentValidator.validateRecovery(candidate) }.isSuccess
                }
                .maxByOrNull { it.sequence }
            val partial = sidecar?.partialContent ?: run.partialContent
            recovered = NovelGenerationReducer.interrupt(
                run.id,
                NovelRunInterruptionReason.Recovery,
                partial,
                recovered,
            ).first
        }
        val committed = repository.commitProject(
            recovered,
            expectedRevision = loaded.document.project.revision,
        )
        orphanedRuns.forEach { recoveryStore?.delete(projectId, it.id) }
        return committed
    }

    private suspend fun createProject(intent: NovelIntent.CreateProject): NovelOutcome {
        val command = NovelCreateProjectCommand(
            context = NovelMutationContext(operationID = NovelOperationId.generate()),
            projectID = NovelProjectId.generate(),
            branchID = NovelBranchId.generate(),
            sessionID = NovelSessionId.generate(),
            initialStateSnapshotID = NovelStateSnapshotId.generate(),
            initialCheckpointID = NovelCheckpointId.generate(),
            name = intent.name,
            branchName = intent.branchName,
            creationMode = intent.mode,
            quickStartSeed = intent.quickStartSeed,
        )
        val reduced = NovelReducer.createProject(command)
        repository.createProject(reduced.document)
        return reduced.outcome
    }

    private suspend fun renameProject(intent: NovelIntent.RenameProject): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        val reduced = NovelReducer.renameProject(
            NovelRenameProjectCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = loaded.document.project.revision,
                ),
                projectID = intent.projectId,
                name = intent.name,
            ),
            loaded.document,
        )
        return commitIfChanged(loaded, reduced)
    }

    private suspend fun deleteProject(intent: NovelIntent.DeleteProject): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        if (loaded.document.activeRuns.any { it.status == NovelRunStatus.Running }) {
            throw NovelError.ProjectBusy(intent.projectId)
        }
        repository.deleteProject(intent.projectId, expectedRevision = loaded.document.project.revision)
        return NovelOutcome.ProjectDeleted(intent.projectId)
    }

    private suspend fun restorePrevious(intent: NovelIntent.RestorePrevious): NovelOutcome {
        val loaded = repository.restorePrevious(intent.projectId)
        return NovelOutcome.PreviousProjectRestored(
            projectID = intent.projectId,
            revision = loaded.document.project.revision,
        )
    }

    private suspend fun setModelPolicy(intent: NovelIntent.SetModelPolicy): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        val reduced = NovelReducer.setModelPolicy(
            NovelSetModelPolicyCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = loaded.document.project.revision,
                    expectedConfigRevision = loaded.document.project.configRevision,
                ),
                projectID = intent.projectId,
                policy = intent.policy,
                purpose = intent.purpose,
            ),
            loaded.document,
        )
        return commitIfChanged(loaded, reduced)
    }

    private suspend fun clearStateSyncModelPolicy(
        intent: NovelIntent.ClearStateSyncModelPolicy,
    ): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        val reduced = NovelReducer.clearStateSyncModelPolicy(
            NovelSetModelPolicyCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = loaded.document.project.revision,
                    expectedConfigRevision = loaded.document.project.configRevision,
                ),
                projectID = intent.projectId,
                // Placeholder — clear path ignores policy value and writes null.
                policy = NovelProjectModelPolicy.Global,
                purpose = app.amber.feature.novel.domain.NovelModelPolicyPurpose.StateSync,
            ),
            loaded.document,
        )
        return commitIfChanged(loaded, reduced)
    }

    private suspend fun setPolishPreference(intent: NovelIntent.SetPolishPreference): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        val reduced = NovelReducer.setPolishPreference(
            NovelSetPolishPreferenceCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = loaded.document.project.revision,
                    expectedConfigRevision = loaded.document.project.configRevision,
                ),
                projectID = intent.projectId,
                polishPreference = intent.polishPreference,
            ),
            loaded.document,
        )
        return commitIfChanged(loaded, reduced)
    }

    private suspend fun collectCandidate(intent: NovelIntent.CollectCandidate): NovelOutcome {
        // Snapshot under lock, then release before any provider await.
        val (policy, planSeed) = writeMutex.withLock {
            val loaded = repository.loadProject(intent.projectId)
            assertNotBusy(loaded)
            val plan = if (intent.runStateDelta) {
                NovelInjectionPlanner.plan(
                    loaded.document,
                    intent.branchId,
                    NovelPromptKind.StateDeltaV1,
                    intent.selectedText,
                )
            } else null
            loaded.document.project.effectiveStateSyncModelPolicy() to plan
        }

        // Provider await is outside the write lock. Manuscript collect still proceeds if
        // state-delta fails — prose must not be lost (P0-A soft-fail policy).
        var stateDelta: app.amber.feature.novel.domain.NovelStateDeltaV1? = null
        val deltaRequested = intent.runStateDelta
        if (deltaRequested && planSeed != null) {
            try {
                val model = modelRunning.resolveModel(policy)
                val runId = NovelRunId.generate()
                val deltaUserText = when (val t = intent.target) {
                    is NovelCollectionTarget.ReplaceChapter -> buildString {
                        appendLine("【替换收录】以下正文将整章替换目标章（chapterID=${t.chapterID.rawValue}），不是追加。")
                        appendLine("请提取因替换产生的状态变化；旧章事实若被推翻，用 summary/events 体现新状态，不要假装旧事实仍成立。")
                        appendLine()
                        append(intent.selectedText)
                    }
                    else -> intent.selectedText
                }
                val req = NovelModelRequest(
                    runID = runId,
                    model = model,
                    purpose = NovelModelPurpose.StateExtraction,
                    messages = listOf(
                        NovelModelMessage(
                            NovelModelMessage.Role.System,
                            planSeed.prompt.systemText + "\n\n" + planSeed.contextText,
                        ),
                        NovelModelMessage(NovelModelMessage.Role.User, deltaUserText),
                    ),
                )
                val text = buildString {
                    modelRunning.start(req).collect { ev ->
                        when (ev) {
                            is NovelModelEvent.TextDelta -> append(ev.text)
                            is NovelModelEvent.TextReplacement -> {
                                clear()
                                append(ev.text)
                            }
                            else -> Unit
                        }
                    }
                }
                if (text.isNotBlank()) {
                    stateDelta = runCatching {
                        NovelStructuredOutputDecoder.decodeStateDelta(text)
                    }.getOrNull()
                }
            } catch (_: Exception) {
                stateDelta = null
            }
        }
        // Soft-fail: keep manuscript, mark needsSync so「同步状态」can rebuild living state.
        val markNeedsSync = deltaRequested && stateDelta == null

        return writeMutex.withLock {
            val current = repository.loadProject(intent.projectId)
            assertNotBusy(current)
            val reduced = NovelCollectionReducer.collect(
                NovelCollectCommand(
                    projectId = intent.projectId,
                    branchId = intent.branchId,
                    candidateId = intent.candidateId,
                    selectedText = intent.selectedText,
                    target = intent.target,
                    expectedProjectRevision = current.document.project.revision,
                    expectedBranchHeadRevision = current.document.branches
                        .first { it.id == intent.branchId }.headRevision,
                    stateDelta = stateDelta,
                    markNeedsSync = markNeedsSync,
                ),
                current.document,
            )
            repository.commitProject(reduced.document, expectedRevision = current.document.project.revision)
            reduced.outcome
        }
    }

    private suspend fun resolveProposal(intent: NovelIntent.ResolveProposal): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        val reduced = NovelBranchReducer.resolveProposal(
            projectId = intent.projectId,
            proposalId = intent.proposalId,
            accept = intent.accept,
            expectedProjectRevision = loaded.document.project.revision,
            expectedConfigRevision = loaded.document.project.configRevision,
            document = loaded.document,
        )
        repository.commitProject(reduced.document, expectedRevision = loaded.document.project.revision)
        return reduced.outcome
    }

    private suspend fun forkBranch(intent: NovelIntent.ForkBranch): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        assertNotBusy(loaded)
        val reduced = NovelBranchReducer.fork(
            projectId = intent.projectId,
            sourceBranchId = intent.sourceBranchId,
            checkpointId = intent.checkpointId,
            name = intent.name,
            expectedProjectRevision = loaded.document.project.revision,
            document = loaded.document,
        )
        repository.commitProject(reduced.document, expectedRevision = loaded.document.project.revision)
        return reduced.outcome
    }

    private suspend fun undoHead(intent: NovelIntent.UndoHead): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        assertNotBusy(loaded)
        val branch = loaded.document.branches.first { it.id == intent.branchId }
        val reduced = NovelBranchReducer.undoHead(
            projectId = intent.projectId,
            branchId = intent.branchId,
            expectedProjectRevision = loaded.document.project.revision,
            expectedBranchHeadRevision = branch.headRevision,
            document = loaded.document,
        )
        repository.commitProject(reduced.document, expectedRevision = loaded.document.project.revision)
        return reduced.outcome
    }

    private suspend fun restoreChapterVersion(intent: NovelIntent.RestoreChapterVersion): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        assertNotBusy(loaded)
        val branch = loaded.document.branches.first { it.id == intent.branchId }
        val reduced = NovelPolishReducer.restoreChapterVersion(
            projectId = intent.projectId,
            branchId = intent.branchId,
            targetChapterVersionId = intent.targetChapterVersionId,
            expectedProjectRevision = loaded.document.project.revision,
            expectedBranchHeadRevision = branch.headRevision,
            document = loaded.document,
        )
        return commitIfChanged(loaded, reduced)
    }

    private suspend fun archiveDiscussion(intent: NovelIntent.ArchiveDiscussion): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        assertNotBusy(loaded)
        val branch = loaded.document.branches.first { it.id == intent.branchId }
        val reduced = NovelBranchReducer.archiveDiscussion(
            projectId = intent.projectId,
            branchId = intent.branchId,
            summary = intent.summary,
            decisions = intent.decisions.map { row ->
                NovelBranchReducer.ArchiveDecision(
                    topic = row.topic,
                    decision = row.decision,
                    relatedMaterialId = row.relatedMaterialId,
                )
            },
            throughSequence = intent.throughSequence,
            chapterId = intent.chapterId,
            expectedProjectRevision = loaded.document.project.revision,
            expectedBranchHeadRevision = branch.headRevision,
            document = loaded.document,
        )
        return commitIfChanged(loaded, reduced)
    }

    private suspend fun syncManualEdits(intent: NovelIntent.SyncManualEdits): NovelOutcome {
        val (policy, chunks, planSeed) = writeMutex.withLock {
            val loaded = repository.loadProject(intent.projectId)
            assertNotBusy(loaded)
            val branch = loaded.document.branches.first { it.id == intent.branchId }
            val chunks = NovelManualSyncReducer.workingManuscriptChunks(
                loaded.document,
                intent.branchId,
            )
            val fullManuscript = chunks.joinToString("\n\n")
            val plan = if (intent.runStateDelta) {
                NovelInjectionPlanner.plan(
                    loaded.document,
                    intent.branchId,
                    NovelPromptKind.ManualSyncV1,
                    // Plan context uses a short seed; per-chunk model input carries the body.
                    fullManuscript.take(4_000),
                )
            } else null
            Triple(
                loaded.document.project.effectiveStateSyncModelPolicy(),
                chunks,
                plan,
            )
        }
        var stateDelta: app.amber.feature.novel.domain.NovelStateDeltaV1? = null
        var modelRebuildAttempted = false
        var successfulChunkCount = 0
        if (intent.runStateDelta && planSeed != null && chunks.isNotEmpty()) {
            modelRebuildAttempted = true
            try {
                val model = modelRunning.resolveModel(policy)
                val chunkDeltas = mutableListOf<app.amber.feature.novel.domain.NovelStateDeltaV1>()
                chunks.forEachIndexed { index, chunk ->
                    val text = buildString {
                        modelRunning.start(
                            NovelModelRequest(
                                runID = NovelRunId.generate(),
                                model = model,
                                purpose = NovelModelPurpose.StateRebuild,
                                messages = listOf(
                                    NovelModelMessage(
                                        NovelModelMessage.Role.System,
                                        planSeed.prompt.systemText + "\n\n" + planSeed.contextText,
                                    ),
                                    NovelModelMessage(
                                        NovelModelMessage.Role.User,
                                        NovelManualSyncReducer.modelInputForChunk(
                                            chunk = chunk,
                                            index = index,
                                            total = chunks.size,
                                        ),
                                    ),
                                ),
                            ),
                        ).collect { ev ->
                            when (ev) {
                                is NovelModelEvent.TextDelta -> append(ev.text)
                                is NovelModelEvent.TextReplacement -> {
                                    clear(); append(ev.text)
                                }
                                else -> Unit
                            }
                        }
                    }
                    if (text.isNotBlank()) {
                        runCatching {
                            NovelStructuredOutputDecoder.decodeStateDelta(text)
                        }.getOrNull()?.let {
                            chunkDeltas += it
                            successfulChunkCount++
                        }
                    }
                }
                stateDelta = NovelManualSyncReducer.mergeChunkDeltas(chunkDeltas)
            } catch (_: Exception) {
                stateDelta = null
            }
        }
        return writeMutex.withLock {
            val current = repository.loadProject(intent.projectId)
            assertNotBusy(current)
            val branch = current.document.branches.first { it.id == intent.branchId }
            // Formalize manuscript always (needsSync must clear), but if a model rebuild was
            // requested and produced nothing, leave a visible incomplete summary rather than
            // silently reusing stale living state as "success".
            val commitDelta = when {
                stateDelta != null -> stateDelta
                modelRebuildAttempted -> app.amber.feature.novel.domain.NovelStateDeltaV1(
                    schemaVersion = 1,
                    stateSummary = "Synced after manual edit (model rebuild incomplete).",
                    events = emptyList(),
                    unresolvedEntityNames = emptyList(),
                    settingProposals = emptyList(),
                )
                else -> null
            }
            val reduced = NovelManualSyncReducer.sync(
                projectId = intent.projectId,
                branchId = intent.branchId,
                expectedProjectRevision = current.document.project.revision,
                expectedBranchHeadRevision = branch.headRevision,
                stateDelta = commitDelta,
                document = current.document,
            )
            repository.commitProject(reduced.document, expectedRevision = current.document.project.revision)
            // Partial rebuild is visible via state summary "model rebuild incomplete";
            // manuscript formalization still commits so NeedsSync clears.
            reduced.outcome
        }
    }

    private suspend fun adoptPolish(intent: NovelIntent.AdoptPolishCandidate): NovelOutcome {
        // Drift check outside lock when not rewrite
        var compatible = true
        if (!intent.asRewrite) {
            val (source, polished) = writeMutex.withLock {
                val loaded = repository.loadProject(intent.projectId)
                val candidate = loaded.document.candidates.first { it.id == intent.candidateId }
                val sourceId = candidate.sourceChapterVersionID
                    ?: throw NovelError.InvalidInput("missing source")
                val source = loaded.document.chapterVersions.first { it.id == sourceId }.content
                val polishedText = app.amber.feature.novel.runtime.NovelPromptCatalog
                    .completedPolishContent(candidate.content) ?: candidate.content
                source to polishedText
            }
            try {
                val model = modelRunning.resolveModel(
                    writeMutex.withLock {
                        repository.loadProject(intent.projectId).document.project.modelPolicy
                    },
                )
                val text = buildString {
                    modelRunning.start(
                        NovelModelRequest(
                            runID = NovelRunId.generate(),
                            model = model,
                            purpose = NovelModelPurpose.DriftCheck,
                            messages = listOf(
                                NovelModelMessage(
                                    NovelModelMessage.Role.System,
                                    app.amber.feature.novel.runtime.NovelPromptCatalog
                                        .template(NovelPromptKind.PolishDriftV1).systemText,
                                ),
                                NovelModelMessage(
                                    NovelModelMessage.Role.User,
                                    "SOURCE:\n$source\n\nCANDIDATE:\n$polished",
                                ),
                            ),
                        ),
                    ).collect { ev ->
                        when (ev) {
                            is NovelModelEvent.TextDelta -> append(ev.text)
                            is NovelModelEvent.TextReplacement -> {
                                clear(); append(ev.text)
                            }
                            else -> Unit
                        }
                    }
                }
                if (text.isBlank()) {
                    // Fail closed: no drift verdict → not safe to adopt as polish.
                    compatible = false
                } else {
                    compatible = NovelStructuredOutputDecoder.decodePolishDrift(text).compatible
                }
            } catch (_: Exception) {
                compatible = false // fail closed
            }
        }
        return writeMutex.withLock {
            val loaded = repository.loadProject(intent.projectId)
            assertNotBusy(loaded)
            val branch = loaded.document.branches.first { it.id == intent.branchId }
            val reduced = if (intent.asRewrite || !compatible) {
                NovelPolishReducer.saveAsRewrite(
                    projectId = intent.projectId,
                    branchId = intent.branchId,
                    candidateId = intent.candidateId,
                    expectedProjectRevision = loaded.document.project.revision,
                    expectedBranchHeadRevision = branch.headRevision,
                    document = loaded.document,
                )
            } else {
                NovelPolishReducer.adoptSafe(
                    projectId = intent.projectId,
                    branchId = intent.branchId,
                    candidateId = intent.candidateId,
                    expectedProjectRevision = loaded.document.project.revision,
                    expectedBranchHeadRevision = branch.headRevision,
                    document = loaded.document,
                )
            }
            repository.commitProject(reduced.document, expectedRevision = loaded.document.project.revision)
            reduced.outcome
        }
    }

    private suspend fun renameBranch(intent: NovelIntent.RenameBranch): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        val reduced = NovelBranchReducer.renameBranch(
            projectId = intent.projectId,
            branchId = intent.branchId,
            name = intent.name,
            expectedProjectRevision = loaded.document.project.revision,
            document = loaded.document,
        )
        repository.commitProject(reduced.document, expectedRevision = loaded.document.project.revision)
        return reduced.outcome
    }

    private suspend fun setMainBranch(intent: NovelIntent.SetMainBranch): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        val reduced = NovelBranchReducer.setMainBranch(
            projectId = intent.projectId,
            branchId = intent.branchId,
            expectedProjectRevision = loaded.document.project.revision,
            document = loaded.document,
        )
        repository.commitProject(reduced.document, expectedRevision = loaded.document.project.revision)
        return reduced.outcome
    }

    private suspend fun reviseMaterial(intent: NovelIntent.ReviseMaterial): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        val reduced = NovelReducer.reviseMaterial(
            NovelReviseMaterialCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = loaded.document.project.revision,
                    expectedConfigRevision = loaded.document.project.configRevision,
                ),
                projectID = intent.projectId,
                materialID = intent.materialId ?: NovelMaterialId.generate(),
                revisionID = NovelMaterialRevisionId.generate(),
                kind = intent.kind,
                title = intent.title,
                content = intent.content,
            ),
            loaded.document,
        )
        return commitIfChanged(loaded, reduced)
    }

    private suspend fun deleteMaterial(intent: NovelIntent.DeleteMaterial): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        val reduced = NovelReducer.deleteMaterial(
            NovelDeleteMaterialCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = loaded.document.project.revision,
                    expectedConfigRevision = loaded.document.project.configRevision,
                ),
                projectID = intent.projectId,
                materialID = intent.materialId,
            ),
            loaded.document,
        )
        return commitIfChanged(loaded, reduced)
    }

    private fun maybeFlushRecovery(live: LiveRun?, force: Boolean = false) {
        if (live == null || recoveryStore == null) return
        val partial = live.partial
        val now = System.currentTimeMillis()
        val grew = partial.length - live.lastRecoveryBytes
        if (!force && grew < 8 * 1024 && now - live.lastRecoveryAtMs < 2000) return
        val branchId = live.branchId ?: return
        val sessionId = live.sessionId ?: return
        val messageId = live.messageId ?: return
        val store = recoveryStore ?: return
        runCatching {
            store.write(
                projectId = live.projectId,
                runId = live.runId,
                branchId = branchId,
                sessionId = sessionId,
                messageId = messageId,
                baseProjectRevision = live.baseRevision,
                sequence = live.sequence.incrementAndGet(),
                partialContent = partial,
            )
            live.lastRecoveryBytes = partial.length
            live.lastRecoveryAtMs = now
        }
    }

    private suspend fun saveManualEdit(intent: NovelIntent.SaveManualEdit): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        val reduced = NovelManualEditReducer.saveEdit(
            projectId = intent.projectId,
            branchId = intent.branchId,
            chapterId = intent.chapterId,
            title = intent.title,
            content = intent.content,
            expectedProjectRevision = loaded.document.project.revision,
            document = loaded.document,
        )
        repository.commitProject(reduced.document, expectedRevision = loaded.document.project.revision)
        return reduced.outcome
    }

    private suspend fun importPackage(intent: NovelIntent.ImportPackage): NovelOutcome {
        val decoded = NovelPackageCodec.decode(intent.bytes)
        val replaceId = intent.replaceProjectId
        val existingList = repository.listProjects()
        val sameIdExists = existingList.any { it.id == decoded.project.id }
        // keep-both: remap project id only (matches iOS NovelProjectIdentityRemapper simplification).
        val document = if (intent.keepBoth && sameIdExists && replaceId == null) {
            val newId = NovelProjectId.generate()
            decoded.copy(
                project = decoded.project.copy(id = newId),
                injectionReceipts = decoded.injectionReceipts.map { r ->
                    r.copy(projectID = newId)
                },
            )
        } else {
            decoded
        }
        val existingToReplace = if (replaceId != null) {
            val existing = repository.loadProject(replaceId)
            assertNotBusy(existing)
            interrupt(
                NovelInterruptRequest(
                    projectId = replaceId,
                    reason = NovelInterruptReason.User,
                ),
            )
            existing
        } else {
            null
        }
        if (existingList.any { it.id == document.project.id } && replaceId == null && !intent.keepBoth) {
            throw NovelError.ProjectAlreadyExists(document.project.id)
        }
        if (existingToReplace != null && replaceId == document.project.id) {
            val oldRevision = existingToReplace.document.project.revision
            repository.deleteProject(replaceId, oldRevision)
            try {
                repository.createProject(document)
            } catch (error: Exception) {
                runCatching { repository.createProject(existingToReplace.document) }
                throw error
            }
        } else {
            repository.createProject(document)
            if (existingToReplace != null && replaceId != null) {
                runCatching {
                    repository.deleteProject(replaceId, existingToReplace.document.project.revision)
                }
            }
        }
        val disposition = when {
            replaceId != null -> app.amber.feature.novel.model.NovelProjectImportDisposition.Replaced
            intent.keepBoth && sameIdExists ->
                app.amber.feature.novel.model.NovelProjectImportDisposition.KeptBoth
            else -> app.amber.feature.novel.model.NovelProjectImportDisposition.Created
        }
        return NovelOutcome.ProjectImported(
            sourceProjectID = decoded.project.id,
            projectID = document.project.id,
            disposition = disposition,
            interruptedRunCount = document.activeRuns.count {
                it.status == NovelRunStatus.Interrupted
            },
            revision = document.project.revision,
        )
    }

    private suspend fun setChapterDiscarded(intent: NovelIntent.SetChapterDiscarded): NovelOutcome {
        val loaded = repository.loadProject(intent.projectId)
        assertNotBusy(loaded)
        val branch = loaded.document.branches.first { it.id == intent.branchId }
        val reduced = NovelBranchReducer.setChapterDiscarded(
            projectId = intent.projectId,
            branchId = intent.branchId,
            chapterId = intent.chapterId,
            discarded = intent.discarded,
            expectedProjectRevision = loaded.document.project.revision,
            expectedBranchHeadRevision = branch.headRevision,
            document = loaded.document,
        )
        return commitIfChanged(loaded, reduced)
    }

    override suspend fun continuityAudit(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
    ): app.amber.feature.novel.domain.NovelContinuityAuditV1 {
        val loaded = repository.loadProject(projectId)
        if (loaded.access != app.amber.feature.novel.model.NovelProjectLoadAccess.ReadWrite) {
            throw NovelError.DegradedReadOnly(projectId)
        }
        assertNotBusy(loaded)
        val doc = loaded.document
        val branch = doc.branches.firstOrNull { it.id == branchId }
            ?: throw NovelError.BranchNotFound(branchId)
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            throw NovelError.InvalidInput("Synchronize the working manuscript before continuity audit.")
        }
        val manuscript = branch.workingChapterSelections.mapIndexed { index, sel ->
            val version = doc.chapterVersions.firstOrNull { it.id == sel.versionID }
            val title = version?.title.orEmpty().ifBlank { "第${index + 1}章" }
            val body = version?.content.orEmpty()
            "# Chapter ${index + 1}: $title\n\n$body"
        }.joinToString("\n\n")
        if (manuscript.isBlank()) {
            throw NovelError.InvalidInput("No manuscript chapters to audit.")
        }
        val model = modelRunning.resolveModel(doc.project.modelPolicy)
        val prompt = NovelPromptCatalog.template(NovelPromptKind.ContinuityAuditV1)
        val text = buildString {
            modelRunning.start(
                NovelModelRequest(
                    runID = NovelRunId.generate(),
                    model = model,
                    purpose = NovelModelPurpose.ContinuityAudit,
                    messages = listOf(
                        NovelModelMessage(NovelModelMessage.Role.System, prompt.systemText),
                        NovelModelMessage(NovelModelMessage.Role.User, manuscript),
                    ),
                ),
            ).collect { ev ->
                when (ev) {
                    is NovelModelEvent.TextDelta -> append(ev.text)
                    is NovelModelEvent.TextReplacement -> {
                        clear()
                        append(ev.text)
                    }
                    else -> Unit
                }
            }
        }
        return NovelStructuredOutputDecoder.decodeContinuityAudit(text)
    }

    override suspend fun distillDiscussionArchive(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        chapterId: NovelChapterId?,
    ): NovelDiscussionArchiveDraft {
        val loaded = repository.loadProject(projectId)
        if (loaded.access != app.amber.feature.novel.model.NovelProjectLoadAccess.ReadWrite) {
            throw NovelError.DegradedReadOnly(projectId)
        }
        assertNotBusy(loaded)
        val doc = loaded.document
        val branch = doc.branches.firstOrNull {
            it.id == branchId && it.lifecycle == NovelBranchLifecycle.Active
        } ?: throw NovelError.BranchNotFound(branchId)
        if (branch.activeRunID != null) throw NovelError.ProjectBusy(projectId)
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            throw NovelError.InvalidInput("Synchronize the working manuscript before archiving discussion.")
        }
        val session = doc.sessions.firstOrNull {
            it.id == branch.sessionID && it.branchID == branch.id
        } ?: throw NovelError.SessionNotFound(branch.sessionID)
        if (chapterId != null && doc.chapters.none { it.id == chapterId }) {
            throw NovelError.InvalidInput("Discussion archive references a missing chapter.")
        }
        val previousSequence = when (val c = session.archiveCursor) {
            is app.amber.feature.novel.model.NovelSessionCursor.Through -> c.sequence
            else -> -1L
        }
        val discussionMessages = session.messages.filter {
            it.sequence > previousSequence &&
                it.mode == NovelSessionMode.DiscussPlan &&
                (
                    it.kind == NovelSessionMessageKind.UserInput ||
                        it.kind == NovelSessionMessageKind.Discussion
                    )
        }
        val throughSequence = discussionMessages.maxOfOrNull { it.sequence }
            ?: throw NovelError.InvalidInput("当前没有可归档的新讨论。")

        val effectiveMaterials = doc.materials.filter { !it.isDeleted }.mapNotNull { material ->
            val revision = doc.materialRevisions.firstOrNull { it.id == material.currentRevisionID }
                ?: return@mapNotNull null
            material to revision
        }
        val materialLines = effectiveMaterials.map { (material, revision) ->
            "${material.id} | ${revision.title}"
        }
        val messageLines = discussionMessages.map { message ->
            val role = when (message.role) {
                NovelSessionRole.User -> "USER"
                NovelSessionRole.Assistant -> "ASSISTANT"
                NovelSessionRole.System -> "SYSTEM"
            }
            "[${message.sequence}] $role\n${message.content}"
        }
        val discussionInput = buildString {
            append("AVAILABLE MATERIALS\n")
            append(if (materialLines.isEmpty()) "(none)" else materialLines.joinToString("\n"))
            append("\n\nDISCUSSION\n")
            append(messageLines.joinToString("\n\n"))
        }

        val model = modelRunning.resolveModel(doc.project.modelPolicy)
        val prompt = NovelPromptCatalog.template(NovelPromptKind.DiscussionArchiveV1)
        val text = buildString {
            modelRunning.start(
                NovelModelRequest(
                    runID = NovelRunId.generate(),
                    model = model,
                    purpose = NovelModelPurpose.DiscussionArchive,
                    messages = listOf(
                        NovelModelMessage(NovelModelMessage.Role.System, prompt.systemText),
                        NovelModelMessage(NovelModelMessage.Role.User, discussionInput),
                    ),
                ),
            ).collect { ev ->
                when (ev) {
                    is NovelModelEvent.TextDelta -> append(ev.text)
                    is NovelModelEvent.TextReplacement -> {
                        clear()
                        append(ev.text)
                    }
                    else -> Unit
                }
            }
        }
        val archive = NovelStructuredOutputDecoder.decodeDiscussionArchive(text)
        val availableMaterialIds = effectiveMaterials.map { it.first.id }.toSet()
        val decisions = archive.decisions.map { item ->
            val related = item.relatedMaterialID?.let { raw ->
                val materialId = runCatching { NovelMaterialId.parse(raw) }.getOrElse {
                    throw NovelError.InvalidInput("讨论归档引用了无效的资料 ID。")
                }
                if (materialId !in availableMaterialIds) {
                    throw NovelError.InvalidInput("讨论归档引用了当前项目中不存在的资料。")
                }
                materialId
            }
            NovelDiscussionArchiveDraftDecision(
                topic = item.topic,
                decision = item.decision,
                relatedMaterialId = related,
            )
        }
        return NovelDiscussionArchiveDraft(
            projectId = projectId,
            branchId = branchId,
            sessionId = session.id,
            throughSequence = throughSequence,
            chapterId = chapterId,
            summary = archive.summary,
            decisions = decisions,
        )
    }

    private suspend fun commitIfChanged(loaded: NovelLoadedProject, reduced: NovelReduceResult): NovelOutcome {
        if (reduced.document.project.revision == loaded.document.project.revision) {
            return reduced.outcome
        }
        repository.commitProject(reduced.document, expectedRevision = loaded.document.project.revision)
        return reduced.outcome
    }
}
