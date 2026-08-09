package app.amber.feature.novel

import android.content.Context
import app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.amber.core.service.AgentGenerationForegroundService
import app.amber.core.utils.NotificationUtil
import app.amber.feature.novel.domain.NovelContinuityIssueSeverityV1
import app.amber.feature.novel.domain.NovelGhostwriteReadiness
import app.amber.feature.novel.domain.NovelGhostwriteReadinessIssue
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelCandidateKind
import app.amber.feature.novel.model.NovelCandidateRecord
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelChapterPlanRecord
import app.amber.feature.novel.model.NovelCollectionSource
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelRunId
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

data class NovelGhostwriteBinding(
    val projectId: NovelProjectId,
    val branchId: NovelBranchId,
)

enum class NovelGhostwritePhase {
    Idle,
    Starting,
    Writing,
    Accepting,
    Collecting,
    Syncing,
    Paused,
    WaitingUser,
    Failed,
}

enum class NovelGhostwritePauseReason {
    UserPaused,
    AcceptanceFailed,
    ObviousRepetition,
    BlockingContinuity,
    CanonicalContinuityConflict,
    ContinuityAuditIncomplete,
    CollectFailed,
    SyncFailed,
    IncompleteCandidate,
    PlanMismatch,
    NotificationUnavailable,
    ChapterCompleted,
}

enum class NovelGhostwriteFailureReason {
    AlreadyRunning,
    ProjectUnavailable,
    ProjectReadOnly,
    CollaborationModeRequired,
    ReadinessBlocked,
    NotificationPermissionRequired,
    ForegroundServiceUnavailable,
    GenerationFailed,
}

sealed interface NovelGhostwriteStartResult {
    data object Started : NovelGhostwriteStartResult

    data class Rejected(
        val reason: NovelGhostwriteFailureReason,
        val readinessIssues: List<NovelGhostwriteReadinessIssue> = emptyList(),
        val detailMessage: String? = null,
    ) : NovelGhostwriteStartResult
}

data class NovelGhostwriteProgress(
    val binding: NovelGhostwriteBinding? = null,
    val phase: NovelGhostwritePhase = NovelGhostwritePhase.Idle,
    val pauseReason: NovelGhostwritePauseReason? = null,
    val failureReason: NovelGhostwriteFailureReason? = null,
    val detailMessage: String? = null,
    val readinessIssues: List<NovelGhostwriteReadinessIssue> = emptyList(),
    val runId: NovelRunId? = null,
    val candidateId: NovelCandidateId? = null,
    val chapterPlanId: NovelChapterPlanId? = null,
    val chapterPlanDigest: String? = null,
    val partialCharacterCount: Int = 0,
    val startedAt: Instant? = null,
)

/**
 * Application-owned, single-chapter ghostwrite pipeline.
 *
 * The foreground service lowers the chance of process eviction; it does not make provider streams resumable after
 * process death. Durable partial recovery remains owned by [NovelCreation].
 */
class NovelGhostwriteCoordinator internal constructor(
    private val context: Context,
    private val novelCreation: NovelCreation,
    private val appScope: CoroutineScope,
    private val backgroundRunRegistry: NovelBackgroundRunRegistry,
    private val hasNotificationPermission: (Context) -> Boolean = { appContext ->
        NotificationUtil.canShowNotification(
            appContext,
            CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
        )
    },
    private val startForeground: (Context, NovelProjectId, NovelRunId, String, String, String) -> Boolean =
        { serviceContext, projectId, runId, leaseToken, title, content ->
            AgentGenerationForegroundService.startNovel(
                context = serviceContext,
                projectId = projectId.rawValue,
                runId = runId.rawValue,
                leaseToken = leaseToken,
                title = title,
                content = content,
            )
        },
    private val stopForeground: (Context, NovelRunId, String) -> Unit = { serviceContext, runId, leaseToken ->
        AgentGenerationForegroundService.stopNovel(serviceContext, runId.rawValue, leaseToken)
    },
    private val newRunId: () -> NovelRunId = { NovelRunId.generate() },
    private val now: () -> Instant = Instant::now,
) {
    private data class Owner(
        val token: Long,
        val backgroundOwnerToken: String,
        val binding: NovelGhostwriteBinding,
        val runId: NovelRunId? = null,
        val previousPauseReason: NovelGhostwritePauseReason? = null,
        val previousCandidateId: NovelCandidateId? = null,
        val previousPlanId: NovelChapterPlanId? = null,
        val previousPlanDigest: String? = null,
    )

    private data class ForegroundLease(
        val token: Long,
        val runId: NovelRunId,
        val serviceLeaseToken: String,
    )

    private enum class ForegroundAcquireResult {
        Acquired,
        LostOwnership,
        Failed,
    }

    private val lock = Any()
    private val _progress = MutableStateFlow(NovelGhostwriteProgress())
    val progress: StateFlow<NovelGhostwriteProgress> = _progress.asStateFlow()

    private var nextToken = 0L
    private val ownerNamespace = UUID.randomUUID().toString()
    private var owner: Owner? = null
    private var pipelineJob: Job? = null
    private var foregroundLease: ForegroundLease? = null

    /**
     * Starts readiness checks and the pipeline in [appScope]. The caller only awaits the start decision; cancelling the
     * caller does not cancel an already-owned pipeline.
     */
    suspend fun start(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
    ): NovelGhostwriteStartResult {
        val binding = NovelGhostwriteBinding(projectId, branchId)
        val startResult = CompletableDeferred<NovelGhostwriteStartResult>()
        val newOwner: Owner
        val job: Job
        synchronized(lock) {
            if (owner != null) {
                return NovelGhostwriteStartResult.Rejected(
                    reason = NovelGhostwriteFailureReason.AlreadyRunning,
                    detailMessage = "已有代笔任务正在运行。",
                )
            }
            nextToken += 1
            val previous = _progress.value.takeIf { it.binding == binding }
            newOwner = Owner(
                token = nextToken,
                backgroundOwnerToken = "single:$ownerNamespace:$nextToken",
                binding = binding,
                previousPauseReason = previous?.pauseReason,
                previousCandidateId = previous?.candidateId,
                previousPlanId = previous?.chapterPlanId,
                previousPlanDigest = previous?.chapterPlanDigest,
            )
            owner = newOwner
            _progress.value = NovelGhostwriteProgress(
                binding = binding,
                phase = NovelGhostwritePhase.Starting,
                pauseReason = previous?.pauseReason,
                candidateId = previous?.candidateId,
                chapterPlanId = previous?.chapterPlanId,
                chapterPlanDigest = previous?.chapterPlanDigest,
                partialCharacterCount = previous?.partialCharacterCount ?: 0,
                startedAt = now(),
            )
            job = appScope.launch(start = CoroutineStart.LAZY) {
                runPipeline(newOwner, startResult)
            }
            pipelineJob = job
        }
        job.start()
        return startResult.await()
    }

    /** Cancels only the currently owned, exact novel run. */
    fun pause(runId: NovelRunId): Boolean = stopOwnedRun(
        token = null,
        runId = runId,
        reason = NovelGhostwritePauseReason.UserPaused,
        detail = "已暂停代笔。",
    )

    private fun stopOwnedRun(
        token: Long?,
        runId: NovelRunId,
        reason: NovelGhostwritePauseReason,
        detail: String,
    ): Boolean {
        val current: Owner
        val job: Job
        synchronized(lock) {
            current = owner?.takeIf {
                it.runId == runId && (token == null || it.token == token)
            } ?: return false
            job = pipelineJob ?: return false
            nextToken += 1
            if (foregroundLease?.let { it.token == current.token && it.runId == runId } == true) {
                foregroundLease = null
            }
            // Enqueue the exact stop before another owner may reacquire the same run ID.
            stopForeground(context, runId, current.backgroundOwnerToken)
            backgroundRunRegistry.release(
                ownerToken = current.backgroundOwnerToken,
                projectId = current.binding.projectId,
                runId = runId,
            )
            owner = null
            pipelineJob = null
            _progress.value = _progress.value.copy(
                phase = NovelGhostwritePhase.Paused,
                pauseReason = reason,
                failureReason = null,
                detailMessage = detail,
                readinessIssues = emptyList(),
                runId = null,
            )
        }
        novelCreation.interrupt(
            NovelInterruptRequest(
                projectId = current.binding.projectId,
                runId = runId,
                reason = NovelInterruptReason.User,
            ),
        )
        job.cancel()
        return true
    }

    /**
     * Returns whether the application coordinator currently owns this project/run. A null [runId] is the project-level
     * query used by route-exit handling while the run is still being allocated.
     */
    fun owns(projectId: NovelProjectId, runId: NovelRunId? = null): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        current.binding.projectId == projectId && (runId == null || current.runId == runId)
    }

    private suspend fun runPipeline(
        initialOwner: Owner,
        startResult: CompletableDeferred<NovelGhostwriteStartResult>,
    ) {
        var foregroundRunId: NovelRunId? = null
        var terminalWaiter: Deferred<NovelRunEvent?>? = null
        try {
            val initialSnapshot = loadProject(initialOwner.binding)
                ?: return rejectStart(
                    initialOwner.token,
                    startResult,
                    NovelGhostwriteFailureReason.ProjectUnavailable,
                    "无法读取小说项目。",
                )
            if (initialSnapshot.access != NovelProjectLoadAccess.ReadWrite) {
                return rejectStart(
                    initialOwner.token,
                    startResult,
                    NovelGhostwriteFailureReason.ProjectReadOnly,
                    "当前项目为只读状态。",
                )
            }
            val document = initialSnapshot.document
            if (document.project.collaborationMode != NovelCollaborationMode.Ghostwrite) {
                return rejectStart(
                    initialOwner.token,
                    startResult,
                    NovelGhostwriteFailureReason.CollaborationModeRequired,
                    "请先切换到代笔模式。",
                )
            }

            val collectedRecovery = collectedCandidateForRecovery(document, initialOwner)
            if (collectedRecovery != null) {
                if (!hasNotificationPermission(context)) {
                    return rejectStart(
                        initialOwner.token,
                        startResult,
                        NovelGhostwriteFailureReason.NotificationPermissionRequired,
                        "需要通知权限才能在后台继续代笔。",
                    )
                }
                val recoveryRunId = candidateRunId(document, collectedRecovery.id)
                    ?: return rejectStart(
                        initialOwner.token,
                        startResult,
                        NovelGhostwriteFailureReason.GenerationFailed,
                        "找不到已收录章节对应的代笔任务，无法安全续同步。",
                    )
                if (!claimRun(initialOwner.token, recoveryRunId)) return
                when (
                    acquireForegroundLease(
                        token = initialOwner.token,
                        projectId = initialOwner.binding.projectId,
                        runId = recoveryRunId,
                        title = document.project.name,
                        content = "正在完成代笔章节同步",
                    )
                ) {
                    ForegroundAcquireResult.Acquired -> foregroundRunId = recoveryRunId
                    ForegroundAcquireResult.LostOwnership -> return
                    ForegroundAcquireResult.Failed -> return rejectStart(
                        initialOwner.token,
                        startResult,
                        NovelGhostwriteFailureReason.ForegroundServiceUnavailable,
                        "无法启动后台代笔服务。",
                    )
                }
                val currentPlan = document.chapterPlan(initialOwner.binding.branchId)
                val clearCurrentPlan = currentPlan?.let {
                    it.isConfirmed && belongsToPlan(collectedRecovery, it)
                } == true
                updateOwned(initialOwner.token) {
                    it.copy(
                        phase = if (clearCurrentPlan) {
                            NovelGhostwritePhase.Collecting
                        } else {
                            NovelGhostwritePhase.Syncing
                        },
                        candidateId = collectedRecovery.id,
                        chapterPlanId = collectedRecovery.ghostwritePlanID,
                        chapterPlanDigest = collectedRecovery.chapterPlanDigest,
                        partialCharacterCount = collectedRecovery.content.length,
                        pauseReason = null,
                        failureReason = null,
                        detailMessage = null,
                        readinessIssues = emptyList(),
                    )
                }
                startResult.complete(NovelGhostwriteStartResult.Started)
                completeCollectedChapter(
                    token = initialOwner.token,
                    binding = initialOwner.binding,
                    candidate = collectedRecovery,
                    clearCurrentPlan = clearCurrentPlan,
                )
                return
            }

            val readinessIssues = NovelGhostwriteReadiness.issues(
                document = document,
                branchId = initialOwner.binding.branchId,
                requireChapterPlan = true,
            )
            if (readinessIssues.isNotEmpty()) {
                return rejectStart(
                    initialOwner.token,
                    startResult,
                    NovelGhostwriteFailureReason.ReadinessBlocked,
                    readinessIssues.first().displayName,
                    readinessIssues,
                )
            }
            val plan = document.confirmedChapterPlan(initialOwner.binding.branchId)
                ?: return rejectStart(
                    initialOwner.token,
                    startResult,
                    NovelGhostwriteFailureReason.ReadinessBlocked,
                    "代笔需要已确认的本章计划。",
                    listOf(NovelGhostwriteReadinessIssue.MissingChapterPlan),
                )
            updateOwned(initialOwner.token) {
                it.copy(
                    chapterPlanId = plan.id,
                    chapterPlanDigest = plan.contentDigest,
                    readinessIssues = emptyList(),
                )
            }

            if (!hasNotificationPermission(context)) {
                return rejectStart(
                    initialOwner.token,
                    startResult,
                    NovelGhostwriteFailureReason.NotificationPermissionRequired,
                    "需要通知权限才能在后台继续代笔。",
                )
            }

            var candidate = reusableCandidate(document, initialOwner, plan)
            val recoveredRunId = candidate?.let { candidateRunId(document, it.id) }
            if (candidate != null && recoveredRunId == null) {
                // Without the durable run identity this candidate cannot provide exact pause/foreground ownership.
                candidate = null
            }
            if (candidate == null) {
                val preexistingCandidateIds = document.candidates.mapTo(mutableSetOf()) { it.id }
                val preparedRunId = newRunId()
                if (!claimRun(initialOwner.token, preparedRunId)) return
                updateOwned(initialOwner.token) {
                    it.copy(
                        phase = NovelGhostwritePhase.Writing,
                        failureReason = null,
                        detailMessage = null,
                        partialCharacterCount = 0,
                        candidateId = null,
                    )
                }
                val run = startOwnedRun(
                    token = initialOwner.token,
                    request = NovelRunRequest(
                        runId = preparedRunId,
                        projectId = initialOwner.binding.projectId,
                        branchId = initialOwner.binding.branchId,
                        userText = GHOSTWRITE_USER_TEXT,
                        mode = NovelSessionModeRequest.WriteProse,
                        granularity = NovelGenerationGranularityRequest.WholeChapter,
                        kind = NovelRunKindRequest.Prose,
                        ghostwritePlanId = plan.id,
                    ),
                ) ?: return
                if (run.id != preparedRunId) {
                    novelCreation.interrupt(
                        NovelInterruptRequest(
                            projectId = initialOwner.binding.projectId,
                            runId = run.id,
                            reason = NovelInterruptReason.User,
                        ),
                    )
                    error("NovelCreation did not honor the caller-owned run ID.")
                }
                if (!isOwned(initialOwner.token)) return
                val waiter = appScope.async(start = CoroutineStart.UNDISPATCHED) {
                    awaitTerminal(initialOwner.token, run)
                }
                terminalWaiter = waiter
                when (
                    acquireForegroundLease(
                        token = initialOwner.token,
                        projectId = initialOwner.binding.projectId,
                        runId = run.id,
                        title = document.project.name,
                        content = "正在代笔完整章节",
                    )
                ) {
                    ForegroundAcquireResult.Acquired -> foregroundRunId = run.id
                    ForegroundAcquireResult.LostOwnership -> return
                    ForegroundAcquireResult.Failed -> {
                        novelCreation.interrupt(
                            NovelInterruptRequest(
                                projectId = initialOwner.binding.projectId,
                                runId = run.id,
                                reason = NovelInterruptReason.User,
                            ),
                        )
                        return rejectStart(
                            initialOwner.token,
                            startResult,
                            NovelGhostwriteFailureReason.ForegroundServiceUnavailable,
                            "无法启动后台代笔服务。",
                        )
                    }
                }
                updateOwned(initialOwner.token) {
                    it.copy(
                        pauseReason = null,
                        failureReason = null,
                        detailMessage = null,
                    )
                }
                startResult.complete(NovelGhostwriteStartResult.Started)
                when (val terminal = waiter.await()) {
                    is NovelRunEvent.Completed -> Unit
                    is NovelRunEvent.Interrupted -> {
                        stopPipeline(
                            token = initialOwner.token,
                            phase = NovelGhostwritePhase.Failed,
                            reason = NovelGhostwritePauseReason.IncompleteCandidate,
                            detail = "本章正文不完整，已停止自动收录。",
                        )
                        return
                    }
                    is NovelRunEvent.Failed -> {
                        failPipeline(
                            token = initialOwner.token,
                            reason = NovelGhostwriteFailureReason.GenerationFailed,
                            detail = terminal.message,
                        )
                        return
                    }
                    null -> return
                    else -> error("Unexpected non-terminal novel event")
                }
                val completedDocument = loadProject(initialOwner.binding)?.document
                val resolution = completedDocument?.let {
                    resolveGeneratedCandidate(it, run.id, preexistingCandidateIds, plan)
                }
                candidate = resolution?.candidate
                if (candidate == null) {
                    val reason = resolution?.failure ?: NovelGhostwritePauseReason.IncompleteCandidate
                    stopPipeline(
                        token = initialOwner.token,
                        phase = NovelGhostwritePhase.Failed,
                        reason = reason,
                        detail = if (reason == NovelGhostwritePauseReason.PlanMismatch) {
                            "生成的正文与当前本章计划不匹配。"
                        } else {
                            "没有找到完整的代笔正文候选。"
                        },
                    )
                    return
                }
            } else {
                val runId = checkNotNull(recoveredRunId)
                if (!claimRun(initialOwner.token, runId)) return
                when (
                    acquireForegroundLease(
                        token = initialOwner.token,
                        projectId = initialOwner.binding.projectId,
                        runId = runId,
                        title = document.project.name,
                        content = "正在验收并收录代笔章节",
                    )
                ) {
                    ForegroundAcquireResult.Acquired -> foregroundRunId = runId
                    ForegroundAcquireResult.LostOwnership -> return
                    ForegroundAcquireResult.Failed -> return rejectStart(
                        initialOwner.token,
                        startResult,
                        NovelGhostwriteFailureReason.ForegroundServiceUnavailable,
                        "无法启动后台代笔服务。",
                    )
                }
                startResult.complete(NovelGhostwriteStartResult.Started)
            }

            if (!isOwned(initialOwner.token)) return
            val ownedCandidate = candidate ?: return
            updateOwned(initialOwner.token) {
                it.copy(
                    phase = NovelGhostwritePhase.Accepting,
                    candidateId = ownedCandidate.id,
                    partialCharacterCount = ownedCandidate.content.length,
                    pauseReason = null,
                    failureReason = null,
                    detailMessage = null,
                )
            }
            val acceptance = try {
                novelCreation.acceptChapterPlan(
                    projectId = initialOwner.binding.projectId,
                    branchId = initialOwner.binding.branchId,
                    candidateId = ownedCandidate.id,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                stopPipeline(
                    initialOwner.token,
                    NovelGhostwritePhase.Paused,
                    NovelGhostwritePauseReason.AcceptanceFailed,
                    error.message ?: "本章计划验收失败。",
                    ownedCandidate.id,
                )
                return
            }
            if (!isOwned(initialOwner.token)) return
            if (!acceptance.accepted) {
                stopPipeline(
                    initialOwner.token,
                    NovelGhostwritePhase.Paused,
                    NovelGhostwritePauseReason.AcceptanceFailed,
                    acceptance.summary.ifBlank {
                        acceptance.missingMustHappen.joinToString("；").ifBlank { "本章计划验收未通过。" }
                    },
                    ownedCandidate.id,
                )
                return
            }
            if (acceptance.obviousRepetition.isNotEmpty()) {
                stopPipeline(
                    initialOwner.token,
                    NovelGhostwritePhase.Paused,
                    NovelGhostwritePauseReason.ObviousRepetition,
                    acceptance.obviousRepetition.joinToString("；"),
                    ownedCandidate.id,
                )
                return
            }

            val currentDocument = loadProject(initialOwner.binding)?.document ?: document
            if (!isOwned(initialOwner.token)) return
            if (currentDocument.project.pauseGhostwriteOnBlockingContinuity) {
                val report = try {
                    novelCreation.continuityAuditIncludingCandidate(
                        projectId = initialOwner.binding.projectId,
                        branchId = initialOwner.binding.branchId,
                        candidateId = ownedCandidate.id,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    stopPipeline(
                        initialOwner.token,
                        NovelGhostwritePhase.Paused,
                        NovelGhostwritePauseReason.ContinuityAuditIncomplete,
                        error.message ?: "连续性检查未完整完成。",
                        ownedCandidate.id,
                    )
                    return
                }
                if (!isOwned(initialOwner.token)) return
                if (report.failedChunkCount > 0) {
                    stopPipeline(
                        initialOwner.token,
                        NovelGhostwritePhase.Paused,
                        NovelGhostwritePauseReason.ContinuityAuditIncomplete,
                        "连续性检查未完整完成，已停止自动收录。",
                        ownedCandidate.id,
                    )
                    return
                }
                if (report.canonicalOnlyBlockingIssues.isNotEmpty()) {
                    stopPipeline(
                        initialOwner.token,
                        NovelGhostwritePhase.Paused,
                        NovelGhostwritePauseReason.CanonicalContinuityConflict,
                        report.canonicalOnlyBlockingIssues.joinToString("；") {
                            it.summary.trim()
                        }.ifBlank {
                            "既有正文中存在阻塞级连续性问题，不能通过重写候选章自动修复。"
                        },
                        ownedCandidate.id,
                    )
                    return
                }
                val blockingIssues = report.issues.filter {
                    it.severity == NovelContinuityIssueSeverityV1.Blocking
                }
                if (blockingIssues.isNotEmpty()) {
                    stopPipeline(
                        initialOwner.token,
                        NovelGhostwritePhase.Paused,
                        NovelGhostwritePauseReason.BlockingContinuity,
                        blockingIssues.joinToString("；") { it.summary.trim() }.ifBlank {
                            "前后情节存在严重连续性问题。"
                        },
                        ownedCandidate.id,
                    )
                    return
                }
            }

            if (!isOwned(initialOwner.token)) return
            updateOwned(initialOwner.token) {
                it.copy(
                    phase = NovelGhostwritePhase.Collecting,
                    candidateId = ownedCandidate.id,
                    pauseReason = null,
                    failureReason = null,
                    detailMessage = null,
                )
            }
            val collectOutcome = try {
                novelCreation.perform(
                    NovelIntent.CollectCandidate(
                        projectId = initialOwner.binding.projectId,
                        branchId = initialOwner.binding.branchId,
                        candidateId = ownedCandidate.id,
                        selectedText = ownedCandidate.content,
                        target = NovelCollectionTarget.CreateNextChapter(
                            chapterID = NovelChapterId.generate(),
                            title = plan.outlinePlacement.trim().ifBlank { "未命名章节" },
                        ),
                        source = NovelCollectionSource.SystemAutoCollect,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                stopPipeline(
                    initialOwner.token,
                    NovelGhostwritePhase.Failed,
                    NovelGhostwritePauseReason.CollectFailed,
                    error.message ?: "自动收录失败。",
                    ownedCandidate.id,
                )
                return
            }
            if (!isOwned(initialOwner.token)) return
            if (collectOutcome !is NovelOutcome.CandidateCollected ||
                collectOutcome.candidateID != ownedCandidate.id
            ) {
                stopPipeline(
                    initialOwner.token,
                    NovelGhostwritePhase.Failed,
                    NovelGhostwritePauseReason.CollectFailed,
                    "自动收录没有返回预期结果。",
                    ownedCandidate.id,
                )
                return
            }

            completeCollectedChapter(
                token = initialOwner.token,
                binding = initialOwner.binding,
                candidate = ownedCandidate,
                clearCurrentPlan = true,
            )
        } catch (error: CancellationException) {
            if (!startResult.isCompleted) {
                startResult.complete(
                    NovelGhostwriteStartResult.Rejected(
                        reason = NovelGhostwriteFailureReason.GenerationFailed,
                        detailMessage = "代笔启动已取消。",
                    ),
                )
            }
        } catch (error: Exception) {
            if (!startResult.isCompleted) {
                rejectStart(
                    initialOwner.token,
                    startResult,
                    NovelGhostwriteFailureReason.GenerationFailed,
                    error.message ?: "代笔启动失败。",
                )
            } else {
                failPipeline(
                    initialOwner.token,
                    NovelGhostwriteFailureReason.GenerationFailed,
                    error.message ?: "代笔失败。",
                )
            }
        } finally {
            terminalWaiter?.cancel()
            foregroundRunId?.let { releaseForegroundLease(initialOwner.token, it) }
            if (!startResult.isCompleted) {
                startResult.complete(
                    NovelGhostwriteStartResult.Rejected(
                        reason = NovelGhostwriteFailureReason.GenerationFailed,
                        detailMessage = "代笔任务未能启动。",
                    ),
                )
            }
            releaseOwnership(initialOwner.token)
        }
    }

    private suspend fun completeCollectedChapter(
        token: Long,
        binding: NovelGhostwriteBinding,
        candidate: NovelCandidateRecord,
        clearCurrentPlan: Boolean,
    ) {
        if (!isOwned(token)) return
        if (clearCurrentPlan) {
            val clearOutcome = try {
                novelCreation.perform(
                    NovelIntent.ClearChapterPlan(
                        projectId = binding.projectId,
                        branchId = binding.branchId,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                stopPipeline(
                    token,
                    NovelGhostwritePhase.Failed,
                    NovelGhostwritePauseReason.CollectFailed,
                    error.message ?: "正文已收录，但清除本章计划失败。",
                    candidate.id,
                )
                return
            }
            if (!isOwned(token)) return
            if (clearOutcome !is NovelOutcome.ChapterPlanCleared) {
                stopPipeline(
                    token,
                    NovelGhostwritePhase.Failed,
                    NovelGhostwritePauseReason.CollectFailed,
                    "正文已收录，但清除本章计划失败。",
                    candidate.id,
                )
                return
            }
        }

        val afterClear = try {
            loadProject(binding)?.document
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (!isOwned(token)) return
        if (isSynchronized(afterClear, binding.branchId)) {
            stopPipeline(
                token = token,
                phase = NovelGhostwritePhase.WaitingUser,
                reason = NovelGhostwritePauseReason.ChapterCompleted,
                detail = "本章已收录并同步。请确认下一章计划。",
                candidateId = candidate.id,
            )
            return
        }

        updateOwned(token) {
            it.copy(
                phase = NovelGhostwritePhase.Syncing,
                candidateId = candidate.id,
                pauseReason = null,
                failureReason = null,
                detailMessage = null,
            )
        }
        val syncOutcome = try {
            novelCreation.perform(
                NovelIntent.SyncManualEdits(
                    projectId = binding.projectId,
                    branchId = binding.branchId,
                    failClosed = true,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            stopPipeline(
                token,
                NovelGhostwritePhase.Failed,
                NovelGhostwritePauseReason.SyncFailed,
                error.message ?: "剧情同步失败。",
                candidate.id,
            )
            return
        }
        if (!isOwned(token)) return
        if (syncOutcome !is NovelOutcome.ManualSyncCommitted ||
            !isSynchronized(loadProject(binding)?.document, binding.branchId)
        ) {
            stopPipeline(
                token,
                NovelGhostwritePhase.Failed,
                NovelGhostwritePauseReason.SyncFailed,
                "剧情同步尚未完成。",
                candidate.id,
            )
            return
        }
        stopPipeline(
            token = token,
            phase = NovelGhostwritePhase.WaitingUser,
            reason = NovelGhostwritePauseReason.ChapterCompleted,
            detail = "本章已收录并同步。请确认下一章计划。",
            candidateId = candidate.id,
        )
    }

    private suspend fun awaitTerminal(token: Long, run: NovelRun): NovelRunEvent? =
        run.events.first { event ->
            if (!isOwned(token)) return@first true
            when (event) {
                is NovelRunEvent.Delta -> updateOwned(token) { progress ->
                    progress.copy(partialCharacterCount = progress.partialCharacterCount + event.text.length)
                }
                is NovelRunEvent.Replace -> updateOwned(token) { progress ->
                    progress.copy(partialCharacterCount = event.text.length)
                }
                else -> Unit
            }
            event is NovelRunEvent.Completed ||
                event is NovelRunEvent.Interrupted ||
                event is NovelRunEvent.Failed
        }.takeIf { isOwned(token) }

    private fun newestMatchingCandidate(
        document: NovelProjectDocumentV1,
        branchId: NovelBranchId,
        plan: NovelChapterPlanRecord,
    ): NovelCandidateRecord? = document.candidates
        .asSequence()
        .filter { candidate ->
            candidate.branchID == branchId &&
                candidate.kind == NovelCandidateKind.Prose &&
                candidate.status == NovelCandidateStatus.Available &&
                candidate.content.isNotBlank() &&
                belongsToPlan(candidate, plan)
        }
        .maxByOrNull { it.createdAt }

    private fun reusableCandidate(
        document: NovelProjectDocumentV1,
        currentOwner: Owner,
        plan: NovelChapterPlanRecord,
    ): NovelCandidateRecord? {
        if (currentOwner.previousPauseReason.requiresRewriteOnContinue()) {
            return null
        }
        val previouslyOwned = currentOwner.previousCandidateId?.let { candidateId ->
            document.candidates.firstOrNull { candidate ->
                candidate.id == candidateId &&
                    candidate.branchID == currentOwner.binding.branchId &&
                    candidate.kind == NovelCandidateKind.Prose &&
                    candidate.status == NovelCandidateStatus.Available &&
                    candidate.content.isNotBlank() &&
                    belongsToPlan(candidate, plan)
            }
        }
        return previouslyOwned ?: newestMatchingCandidate(document, currentOwner.binding.branchId, plan)
    }

    private fun collectedCandidateForRecovery(
        document: NovelProjectDocumentV1,
        currentOwner: Owner,
    ): NovelCandidateRecord? {
        val previousPlanId = currentOwner.previousPlanId
        val previousPlanDigest = currentOwner.previousPlanDigest
        val previouslyOwned = if (previousPlanId != null && previousPlanDigest != null) {
            currentOwner.previousCandidateId?.let { candidateId ->
                document.candidates.firstOrNull { candidate ->
                    candidate.id == candidateId &&
                        candidate.branchID == currentOwner.binding.branchId &&
                        candidate.kind == NovelCandidateKind.Prose &&
                        candidate.status == NovelCandidateStatus.Collected &&
                        candidate.ghostwritePlanID == previousPlanId &&
                        candidate.chapterPlanDigest == previousPlanDigest
                }
            }
        } else {
            null
        }
        if (previouslyOwned != null) return previouslyOwned
        val currentPlan = document.confirmedChapterPlan(currentOwner.binding.branchId) ?: return null
        return document.candidates
            .asSequence()
            .filter { candidate ->
                candidate.branchID == currentOwner.binding.branchId &&
                    candidate.kind == NovelCandidateKind.Prose &&
                    candidate.status == NovelCandidateStatus.Collected &&
                    belongsToPlan(candidate, currentPlan)
            }
            .maxByOrNull { it.createdAt }
    }

    private fun candidateRunId(document: NovelProjectDocumentV1, candidateId: NovelCandidateId): NovelRunId? =
        document.activeRuns.firstOrNull { it.candidateID == candidateId }?.id
            ?: document.sessions.asSequence()
                .flatMap { it.messages.asSequence() }
                .firstOrNull { it.candidateID == candidateId }
                ?.runID

    private data class CandidateResolution(
        val candidate: NovelCandidateRecord? = null,
        val failure: NovelGhostwritePauseReason? = null,
    )

    private fun resolveGeneratedCandidate(
        document: NovelProjectDocumentV1,
        runId: NovelRunId,
        preexistingCandidateIds: Set<NovelCandidateId>,
        plan: NovelChapterPlanRecord,
    ): CandidateResolution {
        val runCandidateId = document.activeRuns.firstOrNull { it.id == runId }?.candidateID
        val candidate = runCandidateId?.let { id -> document.candidates.firstOrNull { it.id == id } }
            ?: document.candidates
                .asSequence()
                .filter { it.id !in preexistingCandidateIds && it.kind == NovelCandidateKind.Prose }
                .maxByOrNull { it.createdAt }
            ?: return CandidateResolution(failure = NovelGhostwritePauseReason.IncompleteCandidate)
        if (candidate.status != NovelCandidateStatus.Available || candidate.content.isBlank()) {
            return CandidateResolution(failure = NovelGhostwritePauseReason.IncompleteCandidate)
        }
        if (!belongsToPlan(candidate, plan)) {
            return CandidateResolution(failure = NovelGhostwritePauseReason.PlanMismatch)
        }
        return CandidateResolution(candidate = candidate)
    }

    private fun belongsToPlan(candidate: NovelCandidateRecord, plan: NovelChapterPlanRecord): Boolean =
        candidate.ghostwritePlanID == plan.id && candidate.chapterPlanDigest == plan.contentDigest

    private fun NovelGhostwritePauseReason?.requiresRewriteOnContinue(): Boolean = when (this) {
        NovelGhostwritePauseReason.AcceptanceFailed,
        NovelGhostwritePauseReason.ObviousRepetition,
        NovelGhostwritePauseReason.BlockingContinuity,
        NovelGhostwritePauseReason.ContinuityAuditIncomplete,
        NovelGhostwritePauseReason.IncompleteCandidate,
        NovelGhostwritePauseReason.PlanMismatch,
        NovelGhostwritePauseReason.NotificationUnavailable -> true
        NovelGhostwritePauseReason.UserPaused,
        NovelGhostwritePauseReason.CanonicalContinuityConflict,
        NovelGhostwritePauseReason.CollectFailed,
        NovelGhostwritePauseReason.SyncFailed,
        NovelGhostwritePauseReason.ChapterCompleted,
        null -> false
    }

    private fun isSynchronized(document: NovelProjectDocumentV1?, branchId: NovelBranchId): Boolean {
        if (document == null) return false
        val branch = document.branches.firstOrNull { it.id == branchId } ?: return false
        return branch.syncStatus == NovelBranchSyncStatus.Synchronized &&
            document.pendingOperations.none { it.branchID == branchId }
    }

    private suspend fun loadProject(binding: NovelGhostwriteBinding): NovelSnapshot.Project? =
        novelCreation.snapshot(NovelQuery.Project(binding.projectId)) as? NovelSnapshot.Project

    /** Prevents pause/background observers from interleaving between exact run ownership and domain start. */
    private fun startOwnedRun(token: Long, request: NovelRunRequest): NovelRun? = synchronized(lock) {
        val runId = request.runId ?: return@synchronized null
        val current = owner
        if (current?.token != token || current.runId != runId) return@synchronized null
        novelCreation.start(request)
    }

    /**
     * Serializes service start with ownership changes. The service key is the run ID, so an old token must never
     * enqueue a start after a newer token has reacquired that same run ID.
     */
    private fun acquireForegroundLease(
        token: Long,
        projectId: NovelProjectId,
        runId: NovelRunId,
        title: String,
        content: String,
    ): ForegroundAcquireResult = synchronized(lock) {
        val current = owner
        if (current?.token != token || current.runId != runId) {
            return@synchronized ForegroundAcquireResult.LostOwnership
        }
        if (!startForeground(context, projectId, runId, current.backgroundOwnerToken, title, content)) {
            return@synchronized ForegroundAcquireResult.Failed
        }
        if (owner?.token != token || owner?.runId != runId) {
            return@synchronized ForegroundAcquireResult.LostOwnership
        }
        foregroundLease = ForegroundLease(
            token = token,
            runId = runId,
            serviceLeaseToken = current.backgroundOwnerToken,
        )
        monitorForegroundLease(token, runId)
        ForegroundAcquireResult.Acquired
    }

    private fun monitorForegroundLease(token: Long, runId: NovelRunId) {
        appScope.launch {
            while (true) {
                delay(FOREGROUND_VISIBILITY_POLL_MILLIS)
                val active = synchronized(lock) {
                    foregroundLease?.let { it.token == token && it.runId == runId } == true
                }
                if (!active) return@launch
                if (!hasNotificationPermission(context)) {
                    stopOwnedRun(
                        token = token,
                        runId = runId,
                        reason = NovelGhostwritePauseReason.NotificationUnavailable,
                        detail = "后台通知不可用，代笔已安全暂停。开启通知后可继续。",
                    )
                    return@launch
                }
            }
        }
    }

    /** Enqueues stop while holding the ownership lock, before a newer token may start the same run ID. */
    private fun releaseForegroundLease(token: Long, runId: NovelRunId) {
        synchronized(lock) {
            val lease = foregroundLease
            if (lease == null || lease.token != token || lease.runId != runId) return
            foregroundLease = null
            stopForeground(context, runId, lease.serviceLeaseToken)
        }
    }

    private fun claimRun(token: Long, runId: NovelRunId): Boolean = synchronized(lock) {
        val current = owner?.takeIf { it.token == token } ?: return@synchronized false
        if (!backgroundRunRegistry.claim(current.backgroundOwnerToken, current.binding.projectId, runId)) {
            return@synchronized false
        }
        owner = current.copy(runId = runId)
        _progress.value = _progress.value.copy(runId = runId)
        true
    }

    private fun updateOwned(token: Long, transform: (NovelGhostwriteProgress) -> NovelGhostwriteProgress) {
        synchronized(lock) {
            if (owner?.token != token) return
            _progress.value = transform(_progress.value)
        }
    }

    private fun isOwned(token: Long): Boolean = synchronized(lock) { owner?.token == token }

    private fun stopPipeline(
        token: Long,
        phase: NovelGhostwritePhase,
        reason: NovelGhostwritePauseReason,
        detail: String,
        candidateId: NovelCandidateId? = null,
    ) {
        updateOwned(token) {
            it.copy(
                phase = phase,
                pauseReason = reason,
                failureReason = null,
                detailMessage = detail,
                readinessIssues = emptyList(),
                runId = null,
                candidateId = candidateId ?: it.candidateId,
                chapterPlanId = if (phase == NovelGhostwritePhase.WaitingUser) null else it.chapterPlanId,
                chapterPlanDigest = if (phase == NovelGhostwritePhase.WaitingUser) null else it.chapterPlanDigest,
            )
        }
    }

    private fun failPipeline(
        token: Long,
        reason: NovelGhostwriteFailureReason,
        detail: String,
    ) {
        updateOwned(token) {
            it.copy(
                phase = NovelGhostwritePhase.Failed,
                failureReason = reason,
                detailMessage = detail,
                runId = null,
            )
        }
    }

    private fun rejectStart(
        token: Long,
        startResult: CompletableDeferred<NovelGhostwriteStartResult>,
        reason: NovelGhostwriteFailureReason,
        detail: String,
        readinessIssues: List<NovelGhostwriteReadinessIssue> = emptyList(),
    ) {
        updateOwned(token) {
            it.copy(
                phase = NovelGhostwritePhase.Failed,
                failureReason = reason,
                detailMessage = detail,
                readinessIssues = readinessIssues,
                runId = null,
            )
        }
        startResult.complete(
            NovelGhostwriteStartResult.Rejected(
                reason = reason,
                readinessIssues = readinessIssues,
                detailMessage = detail,
            ),
        )
    }

    private fun releaseOwnership(token: Long) {
        synchronized(lock) {
            val current = owner?.takeIf { it.token == token } ?: return
            current.runId?.let { runId ->
                backgroundRunRegistry.release(
                    ownerToken = current.backgroundOwnerToken,
                    projectId = current.binding.projectId,
                    runId = runId,
                )
            }
            owner = null
            pipelineJob = null
        }
    }

    private companion object {
        const val GHOSTWRITE_USER_TEXT = "请按本章计划写完整一章正文。"
        const val FOREGROUND_VISIBILITY_POLL_MILLIS = 1_500L
    }
}
