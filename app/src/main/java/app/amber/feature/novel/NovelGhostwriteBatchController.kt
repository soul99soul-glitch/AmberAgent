package app.amber.feature.novel

import android.content.Context
import app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.amber.core.utils.NotificationUtil
import app.amber.feature.novel.background.NovelGhostwriteBatchScheduler
import app.amber.feature.novel.domain.NovelGhostwriteJobError
import app.amber.feature.novel.domain.NovelGhostwriteJobReducer
import app.amber.feature.novel.domain.NovelGhostwriteReadiness
import app.amber.feature.novel.domain.NovelGhostwriteReadinessIssue
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelGhostwriteChapterCursorV1
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelGhostwriteJobV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.persistence.NovelGhostwriteJobLoadAccess
import app.amber.feature.novel.persistence.NovelGhostwriteJobQuarantineRecord
import app.amber.feature.novel.persistence.NovelGhostwriteJobScanFailure
import app.amber.feature.novel.persistence.NovelGhostwriteJobStore
import app.amber.feature.novel.persistence.NovelLoadedGhostwriteJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.time.Instant

data class NovelGhostwriteBatchProjectSnapshot(
    val projectId: NovelProjectId,
    val jobs: List<NovelLoadedGhostwriteJob> = emptyList(),
    val scanFailures: List<NovelGhostwriteJobScanFailure> = emptyList(),
    val loadFailure: String? = null,
) {
    val isLedgerBlocked: Boolean
        get() = scanFailures.isNotEmpty() || loadFailure != null

    val activeJobs: List<NovelLoadedGhostwriteJob>
        get() = jobs.filterNot { it.job.isTerminal }

    fun latestForBinding(branchId: NovelBranchId): NovelLoadedGhostwriteJob? {
        val matching = jobs.filter { it.job.branchID == branchId }
        return matching.firstOrNull { !it.job.isTerminal }
            ?: matching.maxWithOrNull(
                compareBy<NovelLoadedGhostwriteJob> { it.job.updatedAt }
                    .thenBy { it.job.id.rawValue },
            )
    }
}

enum class NovelGhostwriteBatchCommandFailure {
    TargetOutOfRange,
    LedgerBlocked,
    LedgerReadOnly,
    ProjectUnavailable,
    ProjectReadOnly,
    CollaborationModeRequired,
    ReadinessBlocked,
    NotificationRequired,
    InvalidState,
    Conflict,
    SchedulingFailed,
    StorageFailed,
}

sealed interface NovelGhostwriteBatchCommandResult {
    data class Started(val job: NovelGhostwriteJobV1) : NovelGhostwriteBatchCommandResult
    data class Attached(val job: NovelGhostwriteJobV1) : NovelGhostwriteBatchCommandResult
    data class Updated(
        val job: NovelGhostwriteJobV1,
        val warning: String? = null,
    ) : NovelGhostwriteBatchCommandResult

    data class Quarantined(
        val record: NovelGhostwriteJobQuarantineRecord,
    ) : NovelGhostwriteBatchCommandResult

    data class Rejected(
        val reason: NovelGhostwriteBatchCommandFailure,
        val detail: String? = null,
        val readinessIssues: List<NovelGhostwriteReadinessIssue> = emptyList(),
    ) : NovelGhostwriteBatchCommandResult
}

internal fun interface NovelGhostwriteBatchProjectSnapshotting {
    suspend fun snapshot(projectId: NovelProjectId): NovelSnapshot.Project?
}

internal interface NovelGhostwriteBatchScheduling {
    suspend fun start(job: NovelGhostwriteJobV1)
    suspend fun resume(job: NovelGhostwriteJobV1)
    suspend fun cancel(jobId: NovelGhostwriteJobId)
}

/**
 * UI-facing facade for durable 1..50 chapter ghostwrite jobs.
 *
 * The observation flow only polls the durable ledger. WorkManager and its executor
 * remain the sole owners of generation; collecting this flow never starts prose work.
 */
class NovelGhostwriteBatchController internal constructor(
    private val projectSnapshotting: NovelGhostwriteBatchProjectSnapshotting,
    private val store: NovelGhostwriteJobStore,
    private val scheduling: NovelGhostwriteBatchScheduling,
    private val canScheduleInForeground: () -> Boolean,
) {
    constructor(
        context: Context,
        novelCreation: NovelCreation,
        store: NovelGhostwriteJobStore,
        scheduler: NovelGhostwriteBatchScheduler,
    ) : this(
        projectSnapshotting = NovelGhostwriteBatchProjectSnapshotting { projectId ->
            novelCreation.snapshot(NovelQuery.Project(projectId)) as? NovelSnapshot.Project
        },
        store = store,
        scheduling = object : NovelGhostwriteBatchScheduling {
            override suspend fun start(job: NovelGhostwriteJobV1) {
                scheduler.start(job)
            }

            override suspend fun resume(job: NovelGhostwriteJobV1) {
                scheduler.resume(job)
            }

            override suspend fun cancel(jobId: NovelGhostwriteJobId) {
                scheduler.cancel(jobId)
            }
        },
        canScheduleInForeground = {
            NotificationUtil.canShowNotification(
                context,
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            )
        },
    )

    fun observeProject(
        projectId: NovelProjectId,
        intervalMillis: Long = OBSERVATION_INTERVAL_MILLIS,
    ): Flow<NovelGhostwriteBatchProjectSnapshot> {
        require(intervalMillis > 0) { "Observation interval must be positive." }
        return flow {
            while (currentCoroutineContext().isActive) {
                emit(projectSnapshot(projectId))
                delay(intervalMillis)
            }
        }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.IO)
    }

    suspend fun projectSnapshot(projectId: NovelProjectId): NovelGhostwriteBatchProjectSnapshot =
        try {
            val scan = store.listJobs()
            NovelGhostwriteBatchProjectSnapshot(
                projectId = projectId,
                jobs = scan.jobs.filter { it.job.projectID == projectId },
                scanFailures = scan.failures,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NovelGhostwriteBatchProjectSnapshot(
                projectId = projectId,
                loadFailure = safeDetail(error),
            )
        }

    suspend fun activeForProject(projectId: NovelProjectId): List<NovelLoadedGhostwriteJob> {
        val snapshot = projectSnapshot(projectId)
        requireReadableLedger(snapshot)
        return snapshot.activeJobs
    }

    suspend fun activeForBinding(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
    ): NovelLoadedGhostwriteJob? {
        val snapshot = projectSnapshot(projectId)
        requireReadableLedger(snapshot)
        val active = snapshot.activeJobs.filter { it.job.branchID == branchId }
        if (active.size > 1) {
            throw NovelGhostwriteJobError.DuplicateActiveBinding(
                projectID = projectId,
                branchID = branchId,
                jobIDs = active.map { it.job.id },
            )
        }
        return active.singleOrNull()
    }

    suspend fun start(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        targetChapterCount: Int,
    ): NovelGhostwriteBatchCommandResult {
        if (targetChapterCount !in
            NovelGhostwriteJobV1.MIN_TARGET_CHAPTER_COUNT..NovelGhostwriteJobV1.MAX_TARGET_CHAPTER_COUNT
        ) {
            return rejected(
                NovelGhostwriteBatchCommandFailure.TargetOutOfRange,
                "批次章数必须在 1 到 50 之间",
            )
        }

        val existing = try {
            activeForBinding(projectId, branchId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return ledgerRejected(error)
        }
        if (existing != null) {
            if (existing.access != NovelGhostwriteJobLoadAccess.ReadWrite) {
                return rejected(
                    NovelGhostwriteBatchCommandFailure.LedgerReadOnly,
                    "现有批次处于只读恢复状态",
                )
            }
            if (existing.job.status == NovelGhostwriteJobStatus.Pending) {
                if (!canScheduleInForeground()) return notificationRejected()
                return runCatching {
                    scheduling.start(existing.job)
                    NovelGhostwriteBatchCommandResult.Attached(existing.job)
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    schedulingRejected(error)
                }
            }
            return NovelGhostwriteBatchCommandResult.Attached(existing.job)
        }

        if (!canScheduleInForeground()) return notificationRejected()
        val snapshot = try {
            projectSnapshotting.snapshot(projectId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        } ?: return rejected(
            NovelGhostwriteBatchCommandFailure.ProjectUnavailable,
            "项目不可用，无法创建连续代笔批次",
        )
        if (snapshot.access != NovelProjectLoadAccess.ReadWrite) {
            return rejected(
                NovelGhostwriteBatchCommandFailure.ProjectReadOnly,
                "项目处于只读恢复状态",
            )
        }
        val document = snapshot.document
        if (document.project.collaborationMode != NovelCollaborationMode.Ghostwrite) {
            return rejected(
                NovelGhostwriteBatchCommandFailure.CollaborationModeRequired,
                "请先切换到代笔模式",
            )
        }
        val branch = document.branches.firstOrNull {
            it.id == branchId && it.lifecycle == NovelBranchLifecycle.Active
        } ?: return rejected(
            NovelGhostwriteBatchCommandFailure.ProjectUnavailable,
            "当前分支不存在或已归档",
        )
        val readinessIssues = NovelGhostwriteReadiness.issues(
            document = document,
            branchId = branchId,
            requireChapterPlan = true,
        )
        if (readinessIssues.isNotEmpty()) {
            return NovelGhostwriteBatchCommandResult.Rejected(
                reason = NovelGhostwriteBatchCommandFailure.ReadinessBlocked,
                readinessIssues = readinessIssues,
            )
        }

        val now = Instant.now()
        val job = NovelGhostwriteJobReducer.create(
            id = NovelGhostwriteJobId.generate(),
            projectID = projectId,
            branchID = branchId,
            targetChapterCount = targetChapterCount,
            initialCursor = NovelGhostwriteChapterCursorV1(
                chapterIndex = 1,
                baseProjectRevision = document.project.revision,
                baseCheckpointID = branch.headCheckpointID,
                baseHeadRevision = branch.headRevision,
                baseStateSnapshotID = branch.currentStateSnapshotID,
                baseConfigRevision = document.project.configRevision,
            ),
            now = now,
        )
        val created = try {
            store.createJob(job)
        } catch (error: NovelGhostwriteJobError.ActiveJobAlreadyExists) {
            val attached = try {
                activeForBinding(projectId, branchId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            return if (attached != null) {
                NovelGhostwriteBatchCommandResult.Attached(attached.job)
            } else {
                conflictRejected(error)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return ledgerRejected(error)
        }
        return runCatching {
            scheduling.start(created.job)
            NovelGhostwriteBatchCommandResult.Started(created.job)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            schedulingRejected(error)
        }
    }

    suspend fun pause(jobId: NovelGhostwriteJobId): NovelGhostwriteBatchCommandResult =
        transitionWithDurableFence(
            jobId = jobId,
            alreadyReason = "user_paused",
            allowPausedTransition = false,
        ) { current ->
            NovelGhostwriteJobReducer.pause(
                job = current,
                expectedLedgerRevision = current.ledgerRevision,
                expectedExecutionEpoch = current.executionEpoch,
                reasonCode = "user_paused",
            )
        }

    suspend fun cancel(jobId: NovelGhostwriteJobId): NovelGhostwriteBatchCommandResult =
        transitionWithDurableFence(
            jobId = jobId,
            alreadyReason = "user_cancelled",
            allowPausedTransition = true,
        ) { current ->
            NovelGhostwriteJobReducer.cancel(
                job = current,
                expectedLedgerRevision = current.ledgerRevision,
                expectedExecutionEpoch = current.executionEpoch,
                reasonCode = "user_cancelled",
            )
        }

    suspend fun resume(jobId: NovelGhostwriteJobId): NovelGhostwriteBatchCommandResult {
        val loaded = try {
            store.loadJob(jobId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return ledgerRejected(error)
        }
        if (loaded.access != NovelGhostwriteJobLoadAccess.ReadWrite) {
            return rejected(
                NovelGhostwriteBatchCommandFailure.LedgerReadOnly,
                "批次处于只读恢复状态",
            )
        }
        val job = loaded.job
        if (job.status != NovelGhostwriteJobStatus.Paused) {
            return rejected(
                NovelGhostwriteBatchCommandFailure.InvalidState,
                "只有已暂停的批次可以继续",
            )
        }
        if (!isResumablePauseReason(job.statusReasonCode)) {
            return rejected(
                NovelGhostwriteBatchCommandFailure.InvalidState,
                humanizeNonResumableReason(job.statusReasonCode),
            )
        }
        if (!canScheduleInForeground()) return notificationRejected()
        return runCatching {
            scheduling.resume(job)
            NovelGhostwriteBatchCommandResult.Updated(job)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            schedulingRejected(error)
        }
    }

    suspend fun deleteTerminalJobsForProject(projectId: NovelProjectId): Int {
        return store.deleteTerminalForProjectIfNoActive(projectId)
    }

    suspend fun quarantineScanFailure(token: String): NovelGhostwriteBatchCommandResult = try {
        NovelGhostwriteBatchCommandResult.Quarantined(
            store.quarantineScanFailure(token),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ledgerRejected(error)
    }

    private suspend fun transitionWithDurableFence(
        jobId: NovelGhostwriteJobId,
        alreadyReason: String,
        allowPausedTransition: Boolean,
        transition: (NovelGhostwriteJobV1) -> NovelGhostwriteJobV1,
    ): NovelGhostwriteBatchCommandResult {
        repeat(CAS_ATTEMPTS) { attempt ->
            val loaded = try {
                store.loadJob(jobId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return ledgerRejected(error)
            }
            if (loaded.access != NovelGhostwriteJobLoadAccess.ReadWrite) {
                return rejected(
                    NovelGhostwriteBatchCommandFailure.LedgerReadOnly,
                    "批次处于只读恢复状态",
                )
            }
            val current = loaded.job
            if (current.isTerminal) {
                return if (current.statusReasonCode == alreadyReason) {
                    NovelGhostwriteBatchCommandResult.Updated(current)
                } else {
                    rejected(
                        NovelGhostwriteBatchCommandFailure.InvalidState,
                        "批次已经结束，不能再修改",
                    )
                }
            }
            if (current.status == NovelGhostwriteJobStatus.Paused &&
                current.statusReasonCode == alreadyReason
            ) {
                return updatedAfterCancellation(current)
            }
            if (current.status == NovelGhostwriteJobStatus.Paused && !allowPausedTransition) {
                return rejected(
                    NovelGhostwriteBatchCommandFailure.InvalidState,
                    humanizeNonResumableReason(current.statusReasonCode),
                )
            }
            val next = try {
                transition(current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return conflictRejected(error)
            }
            try {
                val committed = store.commitJob(
                    job = next,
                    expectedLedgerRevision = current.ledgerRevision,
                    expectedExecutionEpoch = current.executionEpoch,
                )
                // The ledger state is the durable fence. Work cancellation must be second.
                return updatedAfterCancellation(committed.job)
            } catch (error: NovelGhostwriteJobError.StaleLedgerRevision) {
                if (attempt + 1 == CAS_ATTEMPTS) return conflictRejected(error)
            } catch (error: NovelGhostwriteJobError.StaleExecutionEpoch) {
                if (attempt + 1 == CAS_ATTEMPTS) return conflictRejected(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return ledgerRejected(error)
            }
        }
        return rejected(NovelGhostwriteBatchCommandFailure.Conflict, "批次状态已变化，请重试")
    }

    private suspend fun updatedAfterCancellation(
        job: NovelGhostwriteJobV1,
    ): NovelGhostwriteBatchCommandResult.Updated = try {
        scheduling.cancel(job.id)
        NovelGhostwriteBatchCommandResult.Updated(job)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NovelGhostwriteBatchCommandResult.Updated(
            job = job,
            warning = "批次状态已保存，但后台调度清理失败，" +
                "可重试暂停或取消：${safeDetail(error)}",
        )
    }

    private fun requireReadableLedger(snapshot: NovelGhostwriteBatchProjectSnapshot) {
        if (snapshot.scanFailures.isNotEmpty()) {
            throw NovelGhostwriteJobError.ScanFailed(
                snapshot.scanFailures.map { "${it.fileName}: ${it.detail}" },
            )
        }
        snapshot.loadFailure?.let { throw NovelGhostwriteJobError.StorageFailure(it) }
    }

    private fun ledgerRejected(error: Exception): NovelGhostwriteBatchCommandResult.Rejected = when (error) {
        is NovelGhostwriteJobError.ScanFailed,
        is NovelGhostwriteJobError.CorruptedJob,
        is NovelGhostwriteJobError.UnsupportedSchema,
        -> rejected(
            NovelGhostwriteBatchCommandFailure.LedgerBlocked,
            "批次账本损坏或版本不兼容，请先隔离损坏任务后重试",
        )

        is NovelGhostwriteJobError.DegradedReadOnly -> rejected(
            NovelGhostwriteBatchCommandFailure.LedgerReadOnly,
            "批次账本处于只读恢复状态",
        )

        is NovelGhostwriteJobError.StaleLedgerRevision,
        is NovelGhostwriteJobError.StaleExecutionEpoch,
        is NovelGhostwriteJobError.ActiveJobAlreadyExists,
        is NovelGhostwriteJobError.DuplicateActiveBinding,
        -> conflictRejected(error)

        else -> rejected(
            NovelGhostwriteBatchCommandFailure.StorageFailed,
            safeDetail(error),
        )
    }

    private fun notificationRejected(): NovelGhostwriteBatchCommandResult.Rejected = rejected(
        NovelGhostwriteBatchCommandFailure.NotificationRequired,
        "请到系统设置中为 Amber 开启通知，再开始后台连续代笔",
    )

    private fun schedulingRejected(error: Throwable): NovelGhostwriteBatchCommandResult.Rejected = rejected(
        NovelGhostwriteBatchCommandFailure.SchedulingFailed,
        safeDetail(error).ifBlank { "后台任务提交失败，请重试" },
    )

    private fun conflictRejected(error: Throwable): NovelGhostwriteBatchCommandResult.Rejected = rejected(
        NovelGhostwriteBatchCommandFailure.Conflict,
        safeDetail(error).ifBlank { "批次状态已变化，请重试" },
    )

    private fun rejected(
        reason: NovelGhostwriteBatchCommandFailure,
        detail: String,
    ): NovelGhostwriteBatchCommandResult.Rejected =
        NovelGhostwriteBatchCommandResult.Rejected(reason = reason, detail = detail)

    private fun safeDetail(error: Throwable): String =
        error.message.orEmpty().trim().take(MAX_ERROR_DETAIL_CHARACTERS)

    private fun humanizeNonResumableReason(reasonCode: String?): String = when (reasonCode) {
        "quality_circuit_breaker" ->
            "本章连续校验失败，已停止自动循环；请取消批次后人工检查计划"
        "canonical_continuity_conflict" -> "正史连续性存在阻塞冲突，请取消批次后人工处理"
        "orphan_candidate_recovery" -> "发现进程中断前遗留的候选正文，请取消批次后人工处理"
        "external_project_drift" -> "项目内容已在批次外变化，请取消批次后检查正文与计划"
        "plan_mismatch" -> "章节计划已变化，请取消批次后重新确认"
        null -> "批次没有可恢复原因，不能自动继续"
        else -> "批次因 ${safeReasonCode(reasonCode)} 暂停，" +
            "当前原因不允许自动继续；请取消后人工处理"
    }

    private fun safeReasonCode(raw: String): String = raw.trim()
        .map { character -> if (character.isISOControl()) ' ' else character }
        .joinToString("")
        .take(NovelGhostwriteJobV1.MAX_STATUS_REASON_CODE_CHARACTERS)

    companion object {
        const val DEFAULT_TARGET_CHAPTER_COUNT = 10
        private const val OBSERVATION_INTERVAL_MILLIS = 3_000L
        private const val CAS_ATTEMPTS = 2
        private const val MAX_ERROR_DETAIL_CHARACTERS = 300

        internal fun isResumablePauseReason(reasonCode: String?): Boolean = reasonCode in setOf(
            "user_paused",
            "infra_retry_exhausted",
            "foreground_notification_unavailable",
            "foreground_start_failed",
            "foreground_update_failed",
            "worker_shell_failed",
            "batch_executor_failed",
            "scheduling_failed",
        )
    }
}
