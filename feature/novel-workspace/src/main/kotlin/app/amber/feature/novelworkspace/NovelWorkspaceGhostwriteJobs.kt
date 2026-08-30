package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 批次类型。Write = 批量代笔新章（目标序号 = 最新已提交章 + 1 起）；Polish = 批量润色
 * 既有章节（范围 [startOrdinal, endOrdinal]，不新增章节）。
 *
 * 带默认值的序列化字段：旧 job JSON 没有 mode/endOrdinal，解码时落到默认值
 * （Write / 0），旧记录可继续被 [NovelWorkspaceGhostwriteJobs] 枚举与续跑。
 * job 文件是宿主私有状态（.amber/jobs/），不跨端传输，扩展不涉及 wire 契约。
 */
@Serializable
enum class NovelWorkspaceGhostwriteMode {
    @SerialName("write")
    Write,

    @SerialName("polish")
    Polish,
    ;

    /** Stable lowercase token (matches [SerialName]); also used in unique work names. */
    val value: String
        get() = when (this) {
            Write -> "write"
            Polish -> "polish"
        }
}

/** Durable product stage projected by the UI. Later phases advance it per model/commit step. */
@Serializable
enum class NovelWorkspaceGhostwriteStage {
    @SerialName("idle")
    Idle,

    @SerialName("writing")
    Writing,

    @SerialName("reviewing")
    Reviewing,

    @SerialName("rewriting")
    Rewriting,

    @SerialName("committing")
    Committing,

    @SerialName("planning")
    Planning,
}

/** One chapter commit owned by this job. Progress counts these receipts, not file ordinals. */
@Serializable
data class NovelWorkspaceGhostwriteReceipt(
    val commitId: String,
    val chapterOrdinal: Int,
    val planId: String,
    val planDigest: String,
    /** Present for reviewed ghostwrite commits; null keeps Phase-0/legacy JSON compatible. */
    val candidateId: String? = null,
)

/** Job-private prose candidate. It is never exposed as a draft/proposal or written to canon. */
@Serializable
data class NovelWorkspaceGhostwriteCandidate(
    val id: String,
    val chapterOrdinal: Int,
    val planId: String,
    val planDigest: String,
    /** 0 = initial draft; 1/2 = targeted rewrite. */
    val attempt: Int,
    val title: String,
    val body: String,
    val repairInstructions: List<String> = emptyList(),
    @Serializable(with = NovelWorkspaceInstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = NovelWorkspaceInstantSerializer::class)
    val updatedAt: Instant,
) {
    companion object {
        const val MAX_REWRITE_ATTEMPTS = 2
    }
}

@Serializable
enum class NovelWorkspaceJointReviewFindingKind {
    @SerialName("missing_required")
    MissingRequired,

    @SerialName("forbidden_violation")
    ForbiddenViolation,

    @SerialName("hard_continuity")
    HardContinuity,

    @SerialName("non_blocking")
    NonBlocking,
}

@Serializable
data class NovelWorkspaceJointReviewFinding(
    val kind: NovelWorkspaceJointReviewFindingKind,
    val message: String,
    val candidateEvidence: String,
    val planEvidence: String? = null,
)

/** Strict, candidate-bound output of the one read-only joint-review model turn. */
@Serializable
data class NovelWorkspaceJointReviewResult(
    val candidateId: String,
    val chapterOrdinal: Int,
    val planId: String,
    val planDigest: String,
    val blocking: Boolean,
    val rewriteRequired: Boolean,
    val repairInstructions: List<String>,
    val findings: List<NovelWorkspaceJointReviewFinding>,
    /** Complete post-chapter current plot/character state for plot/current.md. */
    val plotState: String,
    /** One concise event line appended to plot/current.md's recent highlights. */
    val chapterHighlight: String,
    /** Parsed in Phase 2; consumed atomically by Phase 3. */
    val nextPlan: String? = null,
)

/**
 * Ghostwrite batch job record. Progress is NOT stored — it is derived from the ledger
 * (chapters committed since the job started), so a crashed worker never reports stale
 * progress and resume is recomputed from the actual manuscript, not a counter.
 */
@Serializable
data class NovelWorkspaceGhostwriteJob(
    val id: String,
    /** Changes on every resume/retry so an older Worker cannot regain write ownership. */
    val executionId: String = "",
    val branchSlug: String,
    val targetChapterCount: Int,
    /** Write: manuscript ordinal just before the job's first chapter was committed.
     *  Polish: first ordinal of the polish range (inclusive). */
    val startOrdinal: Int,
    /** Polish only: last ordinal of the polish range (inclusive); 0 = unused (Write). */
    val endOrdinal: Int = 0,
    val mode: NovelWorkspaceGhostwriteMode = NovelWorkspaceGhostwriteMode.Write,
    /** Immutable branch identity captured when the author confirms the batch. */
    val branchId: String = "",
    /** Initial and current CAS boundary for this job's branch. */
    val baseHeadId: String? = null,
    val expectedHeadId: String? = null,
    val baseTreeDigest: String = "",
    val expectedTreeDigest: String = "",
    /** Confirmed chapter-plan snapshot. The UI start click is the confirmation gate. */
    val planId: String = "",
    val planDigest: String = "",
    val confirmedPlan: String = "",
    /** Durable execution projection; no progress counter is stored. */
    val currentChapterOrdinal: Int = 0,
    val stage: NovelWorkspaceGhostwriteStage = NovelWorkspaceGhostwriteStage.Idle,
    val rewriteAttempt: Int = 0,
    val pendingCandidate: NovelWorkspaceGhostwriteCandidate? = null,
    val pendingReview: NovelWorkspaceJointReviewResult? = null,
    val receipts: List<NovelWorkspaceGhostwriteReceipt> = emptyList(),
    val status: String = STATUS_RUNNING,
    val reason: String? = null,
    @Serializable(with = NovelWorkspaceInstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = NovelWorkspaceInstantSerializer::class)
    val updatedAt: Instant,
) {
    val executionKey: String get() = executionId.ifBlank { id }

    val isTerminal: Boolean get() =
        status == STATUS_COMPLETED || status == STATUS_FAILED || status == STATUS_CANCELLED

    /**
     * Old on-disk jobs have none of the Phase-0 binding fields and keep legacy progress.
     * A completed final chapter clears the current plan, but its candidate receipt keeps
     * this job on the version-bound path for durable progress/recovery.
     */
    val isVersionBound: Boolean get() =
        mode == NovelWorkspaceGhostwriteMode.Write &&
            branchId.isNotBlank() &&
            (
                planId.isNotBlank() && planDigest.isNotBlank() ||
                    receipts.any { !it.candidateId.isNullOrBlank() }
            )

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}

object NovelWorkspaceGhostwriteJobs {
    private const val DIR = "jobs"

    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(projectDirectory: File): File =
        File(File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME), DIR)

    @Synchronized
    fun save(job: NovelWorkspaceGhostwriteJob, projectDirectory: File) {
        val directory = dir(projectDirectory)
        if (!directory.exists() && !directory.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create jobs directory: $directory")
        }
        val destination = File(directory, "${job.id}.json")
        val temp = File.createTempFile("novel-job-", ".tmp", directory)
        try {
            temp.writeText(
                json.encodeToString(NovelWorkspaceGhostwriteJob.serializer(), job),
                Charsets.UTF_8,
            )
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            NovelWorkspaceLedger.atomicMove(temp, destination)
        } finally {
            temp.delete()
        }
    }

    fun load(projectDirectory: File, jobId: String): NovelWorkspaceGhostwriteJob? {
        val f = File(dir(projectDirectory), "$jobId.json")
        if (!f.exists()) return null
        return try {
            json.decodeFromString(NovelWorkspaceGhostwriteJob.serializer(), f.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            null
        }
    }

    fun listActive(projectDirectory: File): List<NovelWorkspaceGhostwriteJob> =
        decodeAll(projectDirectory).filter { !it.isTerminal }

    @Synchronized
    fun activeFor(projectDirectory: File, branchSlug: String): NovelWorkspaceGhostwriteJob? =
        listActive(projectDirectory).firstOrNull { it.branchSlug == branchSlug }

    /** Atomically claim the branch for a new batch inside this app process. */
    @Synchronized
    fun saveIfNoActive(job: NovelWorkspaceGhostwriteJob, projectDirectory: File): Boolean {
        if (activeFor(projectDirectory, job.branchSlug) != null) return false
        save(job, projectDirectory)
        return true
    }

    /** Keep an author commit and a new batch claim on the same branch mutually exclusive. */
    @Synchronized
    fun <T> withNoActiveBranch(
        projectDirectory: File,
        branchSlug: String,
        block: () -> T,
    ): T? {
        if (activeFor(projectDirectory, branchSlug) != null) return null
        return block()
    }

    /** Reactivate the same failed batch so its durable cursor and target stay intact. */
    @Synchronized
    fun restartFailed(
        projectDirectory: File,
        jobId: String,
        expectedExecutionId: String? = null,
        expectedBranchSlug: String? = null,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_FAILED) return null
        if (expectedExecutionId != null && current.executionKey != expectedExecutionId) return null
        if (expectedBranchSlug != null && current.branchSlug != expectedBranchSlug) return null
        if (activeFor(projectDirectory, current.branchSlug) != null) return null
        return current.copy(
            executionId = java.util.UUID.randomUUID().toString().uppercase(),
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            reason = null,
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /**
     * Resume only the paused durable state and invalidate the previous Worker execution.
     * [expectedBranchSlug] 非空时校验批次仍属于该分支：job 绑定创建时的 activeBranch，
     * 作者切到别的分支后 继续/重试 必须被拒（先切回原分支），否则批次会把另一条分支的
     * 工作区视角当作上下文继续写章。
     */
    @Synchronized
    fun restartPaused(
        projectDirectory: File,
        jobId: String,
        expectedExecutionId: String? = null,
        expectedBranchSlug: String? = null,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_PAUSED) return null
        if (expectedExecutionId != null && current.executionKey != expectedExecutionId) return null
        if (expectedBranchSlug != null && current.branchSlug != expectedBranchSlug) return null
        return current.copy(
            executionId = java.util.UUID.randomUUID().toString().uppercase(),
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            reason = null,
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /** Persist a state change only when the latest durable state still matches. */
    @Synchronized
    fun transition(
        projectDirectory: File,
        jobId: String,
        expectedStatuses: Set<String>,
        newStatus: String,
        reason: String? = null,
        expectedExecutionId: String? = null,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status !in expectedStatuses) return null
        if (expectedExecutionId != null && current.executionKey != expectedExecutionId) return null
        return current.copy(
            status = newStatus,
            reason = reason,
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /** Serialize the final owner check with pause/cancel transitions. */
    @Synchronized
    fun <T> withRunningOwner(
        projectDirectory: File,
        jobId: String,
        executionId: String,
        block: () -> T,
    ): T? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            current.executionKey != executionId
        ) return null
        if (current.isVersionBound && !bindingMatches(current, projectDirectory)) return null
        return block()
    }

    /**
     * Advance the bound branch CAS and append exactly one owned receipt. This is called
     * inside [withRunningOwner]'s synchronized commit block, before pause/cancel can win.
     */
    @Synchronized
    fun recordWriteCommit(
        projectDirectory: File,
        jobId: String,
        executionId: String,
        commitId: String,
        chapterOrdinal: Int,
        planId: String,
        planDigest: String,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            current.executionKey != executionId ||
            !current.isVersionBound ||
            current.planId != planId ||
            current.planDigest != planDigest
        ) return null
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        if (ledger.heads[current.branchId] != commitId) return null
        val commit = ledger.commit(commitId) ?: return null
        val chapterPrefix = NovelWorkspacePaths.branchPrefix(current.branchSlug) + "/chapters/"
        val ownsTargetChapter = NovelWorkspaceLedger.changedPaths(commit, ledger.commits)
            .asSequence()
            .filter { it.startsWith(chapterPrefix) }
            .mapNotNull(NovelWorkspacePaths::chapterOrdinalFromPath)
            .any { it == chapterOrdinal }
        val receipt = NovelWorkspaceGhostwriteReceipt(
            commitId = commitId,
            chapterOrdinal = chapterOrdinal,
            planId = planId,
            planDigest = planDigest,
        )
        return current.copy(
            expectedHeadId = commitId,
            expectedTreeDigest = NovelWorkspaceLedger.treeSHA256(
                NovelWorkspaceStore(projectDirectory).fileTree(),
            ),
            currentChapterOrdinal = if (ownsTargetChapter) chapterOrdinal + 1 else chapterOrdinal,
            receipts = if (ownsTargetChapter) {
                current.receipts.filterNot { it.chapterOrdinal == chapterOrdinal } + receipt
            } else {
                current.receipts
            },
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /** Mark the durable phase before a read-only initial/rewrite model turn starts. */
    @Synchronized
    fun beginCandidateTurn(
        projectDirectory: File,
        jobId: String,
        executionId: String,
        chapterOrdinal: Int,
        rewrite: Boolean,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            current.executionKey != executionId ||
            !current.isVersionBound ||
            !bindingMatches(current, projectDirectory) ||
            current.currentChapterOrdinal != chapterOrdinal
        ) return null
        if (rewrite) {
            val candidate = current.pendingCandidate ?: return null
            if (candidate.attempt >= NovelWorkspaceGhostwriteCandidate.MAX_REWRITE_ATTEMPTS) return null
        } else if (current.pendingCandidate != null) {
            return current
        }
        return current.copy(
            stage = if (rewrite) {
                NovelWorkspaceGhostwriteStage.Rewriting
            } else {
                NovelWorkspaceGhostwriteStage.Writing
            },
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /** Persist one generated candidate only while its owner, plan and chapter still match. */
    @Synchronized
    fun recordCandidate(
        projectDirectory: File,
        jobId: String,
        executionId: String,
        candidate: NovelWorkspaceGhostwriteCandidate,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            current.executionKey != executionId ||
            !current.isVersionBound ||
            !bindingMatches(current, projectDirectory) ||
            current.currentChapterOrdinal != candidate.chapterOrdinal ||
            current.planId != candidate.planId ||
            current.planDigest != candidate.planDigest ||
            candidate.attempt !in 0..NovelWorkspaceGhostwriteCandidate.MAX_REWRITE_ATTEMPTS ||
            candidate.body.isBlank()
        ) return null
        val previous = current.pendingCandidate
        val validRevision = if (previous == null) {
            candidate.attempt == 0
        } else {
            candidate.id == previous.id &&
                candidate.attempt == previous.attempt + 1 &&
                candidate.repairInstructions.isNotEmpty()
        }
        if (!validRevision) return null
        return current.copy(
            pendingCandidate = candidate,
            pendingReview = null,
            rewriteAttempt = candidate.attempt,
            stage = NovelWorkspaceGhostwriteStage.Reviewing,
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /** Persist a strictly validated review while the same candidate and tree are owned. */
    @Synchronized
    fun recordReview(
        projectDirectory: File,
        jobId: String,
        executionId: String,
        review: NovelWorkspaceJointReviewResult,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        val candidate = current.pendingCandidate ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            current.executionKey != executionId ||
            !current.isVersionBound ||
            !bindingMatches(current, projectDirectory) ||
            review.candidateId != candidate.id ||
            review.chapterOrdinal != candidate.chapterOrdinal ||
            review.planId != candidate.planId ||
            review.planDigest != candidate.planDigest ||
            !validReviewDecision(review)
        ) return null
        return current.copy(
            pendingReview = review,
            stage = when {
                review.rewriteRequired -> NovelWorkspaceGhostwriteStage.Rewriting
                !review.blocking &&
                    (review.chapterOrdinal == current.startOrdinal + current.targetChapterCount ||
                        !review.nextPlan.isNullOrBlank()) -> NovelWorkspaceGhostwriteStage.Committing
                !review.blocking -> NovelWorkspaceGhostwriteStage.Planning
                else -> NovelWorkspaceGhostwriteStage.Reviewing
            },
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /**
     * Persist the one host-generated next plan after a review passed without returning it.
     * Planning is deliberately a separate owner/CAS gate: a stale planner must not be able
     * to attach a plan to a newer candidate or tree.
     */
    @Synchronized
    fun recordPlannedNextPlan(
        projectDirectory: File,
        jobId: String,
        executionId: String,
        candidateId: String,
        nextPlan: String,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        val candidate = current.pendingCandidate ?: return null
        val review = current.pendingReview ?: return null
        val trimmedPlan = nextPlan.trim()
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            current.executionKey != executionId ||
            current.stage != NovelWorkspaceGhostwriteStage.Planning ||
            !current.isVersionBound ||
            !bindingMatches(current, projectDirectory) ||
            candidate.id != candidateId ||
            review.candidateId != candidateId ||
            review.chapterOrdinal != candidate.chapterOrdinal ||
            review.planId != candidate.planId ||
            review.planDigest != candidate.planDigest ||
            review.blocking ||
            review.rewriteRequired ||
            !validReviewDecision(review) ||
            !review.nextPlan.isNullOrBlank() ||
            candidate.chapterOrdinal !in
                (current.startOrdinal + 1) until (current.startOrdinal + current.targetChapterCount) ||
            trimmedPlan.isBlank()
        ) return null
        return current.copy(
            pendingReview = review.copy(nextPlan = trimmedPlan),
            stage = NovelWorkspaceGhostwriteStage.Committing,
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /**
     * Complete accounting after the reviewed chapter+plot commit is already canonical.
     * This deliberately validates commit provenance instead of the old tree binding so
     * the same method can repair the narrow Ledger-before-job crash window.
     */
    @Synchronized
    fun recordReviewedCommit(
        projectDirectory: File,
        jobId: String,
        executionId: String,
        commitId: String,
        candidateId: String,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        current.receipts.firstOrNull { it.candidateId == candidateId }?.let { receipt ->
            return current.takeIf { receipt.commitId == commitId }
        }
        val candidate = current.pendingCandidate ?: return null
        val review = current.pendingReview ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            current.executionKey != executionId ||
            !current.isVersionBound ||
            candidate.id != candidateId ||
            review.candidateId != candidateId ||
            review.chapterOrdinal != candidate.chapterOrdinal ||
            review.planId != candidate.planId ||
            review.planDigest != candidate.planDigest ||
            review.blocking ||
            review.rewriteRequired ||
            !validReviewDecision(review)
        ) return null
        val finalChapterOrdinal = current.startOrdinal + current.targetChapterCount
        if (candidate.chapterOrdinal !in (current.startOrdinal + 1)..finalChapterOrdinal) return null
        val finalChapter = candidate.chapterOrdinal == finalChapterOrdinal
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        if (ledger.heads[current.branchId] != commitId) return null
        val commit = ledger.commit(commitId) ?: return null
        if (commit.parentId != current.expectedHeadId ||
            commit.message != NovelWorkspaceLedger.Message.GHOSTWRITE_REVIEWED
        ) return null
        val chapterPrefix = NovelWorkspacePaths.branchPrefix(current.branchSlug) + "/chapters/"
        val changed = NovelWorkspaceLedger.changedPaths(commit, ledger.commits)
        val chapterPath = changed.singleOrNull { path ->
            path.startsWith(chapterPrefix) &&
                NovelWorkspacePaths.chapterOrdinalFromPath(path) == candidate.chapterOrdinal
        } ?: return null
        val plotPath = NovelWorkspacePaths.branchPrefix(current.branchSlug) + "/plot/current.md"
        if (plotPath !in changed) return null
        val chapter = NovelWorkspaceStore(projectDirectory).read(chapterPath)
            ?.let(NovelWorkspaceMarkdown::parseFile)
            ?: return null
        if (chapter.fields["sourceCandidateId"] != candidate.id ||
            chapter.fields["sourcePlanId"] != candidate.planId ||
            chapter.fields["sourcePlanDigest"] != candidate.planDigest ||
            chapter.body != candidate.body.trim()
        ) return null
        val store = NovelWorkspaceStore(projectDirectory)
        val planPath = NovelWorkspacePaths.branchPrefix(current.branchSlug) + "/plan/this-chapter.md"
        val parent = commit.parentId?.let(ledger::commit)
        if (parent == null && commit.parentId != null) return null
        val parentPlanHash = parent?.files?.get(planPath)
        val childPlanHash = commit.files[planPath]
        val nextPlan = review.nextPlan?.trim()
        val rotatedPlan = if (finalChapter) {
            if (childPlanHash != null || store.exists(planPath)) return null
            null
        } else {
            if (childPlanHash == null ||
                (parentPlanHash != null && parentPlanHash == childPlanHash)
            ) return null
            if (nextPlan.isNullOrBlank()) return null
            val canonicalPlan = store.read(planPath) ?: return null
            if (sha256Hex(canonicalPlan) != childPlanHash) return null
            val parsedPlan = NovelWorkspaceMarkdown.parseFile(canonicalPlan)
            val body = parsedPlan.body.trim()
            val id = parsedPlan.fields["id"]?.trim().orEmpty()
            val expectedId = reviewedNextPlanId(candidate.id)
            val expectedDigest = reviewedNextPlanDigest(nextPlan)
            if (parsedPlan.fields["kind"]?.trim() != "plan" ||
                parsedPlan.fields["status"]?.trim() != "confirmed" ||
                parsedPlan.fields["title"]?.trim() != "本章计划" ||
                body != nextPlan ||
                id != expectedId ||
                reviewedNextPlanDigest(body) != expectedDigest ||
                parsedPlan.fields["digest"]?.trim()?.let { it != expectedDigest } == true ||
                parsedPlan.fields["planDigest"]?.trim()?.let { it != expectedDigest } == true
            ) return null
            Triple(id, expectedDigest, body)
        }
        val receipt = NovelWorkspaceGhostwriteReceipt(
            commitId = commitId,
            chapterOrdinal = candidate.chapterOrdinal,
            planId = candidate.planId,
            planDigest = candidate.planDigest,
            candidateId = candidate.id,
        )
        return current.copy(
            expectedHeadId = commitId,
            expectedTreeDigest = NovelWorkspaceLedger.treeSHA256(
                store.fileTree(),
            ),
            currentChapterOrdinal = candidate.chapterOrdinal + 1,
            stage = if (finalChapter) {
                NovelWorkspaceGhostwriteStage.Idle
            } else {
                NovelWorkspaceGhostwriteStage.Writing
            },
            rewriteAttempt = 0,
            pendingCandidate = null,
            pendingReview = null,
            planId = rotatedPlan?.first.orEmpty(),
            planDigest = rotatedPlan?.second.orEmpty(),
            confirmedPlan = rotatedPlan?.third.orEmpty(),
            receipts = current.receipts.filterNot { it.chapterOrdinal == candidate.chapterOrdinal } + receipt,
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /** Stable host identity for a plan produced from one reviewed candidate. */
    fun reviewedNextPlanId(candidateId: String): String = UUID.nameUUIDFromBytes(
        ("reviewed-plan:" + candidateId).toByteArray(Charsets.UTF_8),
    ).toString().uppercase()

    /** Stable plan digest over the canonical, trimmed body. */
    fun reviewedNextPlanDigest(body: String): String = sha256Hex(body.trim())

    private fun validReviewDecision(review: NovelWorkspaceJointReviewResult): Boolean {
        if (review.blocking && review.rewriteRequired) return false
        if (review.rewriteRequired && review.repairInstructions.none { it.isNotBlank() }) return false
        val hardFindings = review.findings.filter {
            it.kind != NovelWorkspaceJointReviewFindingKind.NonBlocking
        }
        if (hardFindings.any { finding ->
                finding.message.isBlank() ||
                    finding.candidateEvidence.isBlank() ||
                    ((finding.kind == NovelWorkspaceJointReviewFindingKind.MissingRequired ||
                        finding.kind == NovelWorkspaceJointReviewFindingKind.ForbiddenViolation) &&
                        finding.planEvidence.isNullOrBlank())
            }
        ) return false
        if (review.blocking && hardFindings.isEmpty()) return false
        if (!review.blocking && !review.rewriteRequired && hardFindings.isNotEmpty()) return false
        if (!review.blocking && !review.rewriteRequired &&
            (review.plotState.isBlank() || review.chapterHighlight.isBlank())
        ) return false
        return true
    }

    private fun bindingMatches(job: NovelWorkspaceGhostwriteJob, projectDirectory: File): Boolean {
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        if (NovelWorkspaceLedger.branchId(store, ledger, job.branchSlug) != job.branchId) return false
        if (ledger.heads[job.branchId] != job.expectedHeadId) return false
        return NovelWorkspaceLedger.treeSHA256(store.fileTree()) == job.expectedTreeDigest
    }

    /**
     * Latest failed job, if any — surfaced by the UI so a dead batch's reason is
     * not silent (the worker posts no failure notification of its own).
     */
    fun latestFailed(
        projectDirectory: File,
        branchSlug: String? = null,
    ): NovelWorkspaceGhostwriteJob? =
        decodeAll(projectDirectory)
            .filter {
                it.status == NovelWorkspaceGhostwriteJob.STATUS_FAILED &&
                    (branchSlug == null || it.branchSlug == branchSlug)
            }
            .maxByOrNull { it.updatedAt }

    private fun decodeAll(projectDirectory: File): List<NovelWorkspaceGhostwriteJob> {
        val directory = dir(projectDirectory)
        if (!directory.exists()) return emptyList()
        return directory.listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull { f ->
                runCatching {
                    json.decodeFromString(NovelWorkspaceGhostwriteJob.serializer(), f.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            .sortedBy { it.createdAt }
    }

    fun delete(projectDirectory: File, jobId: String) {
        File(dir(projectDirectory), "$jobId.json").delete()
    }

    /**
     * Progress = durable branch-head state since the job started. Write mode counts
     * newly committed chapters; Polish mode counts 「润色」 commits at/after the job's
     * creation that changed a chapter INSIDE the job's [startOrdinal, endOrdinal]
     * range (one polished chapter = exactly one such commit — rolled-back turns leave
     * none; paths whose ordinal cannot be parsed are ignored), so a crashed polish
     * batch also resumes from the ledger instead of a counter.
     *
     * 取舍：边界用精确 [NovelWorkspaceGhostwriteJob.createdAt] 比较，不再向下取整到秒。
     * commit 的 createdAt 持久化本就秒截断，job 再取整会把「job 创建前同一秒内」的旧
     * 润色 commit 计入新 job（取消旧批次 1 秒内新建时静默跳过范围首章）。残留窗口：
     * 同秒重启时 job 从磁盘读回也是秒截断，恰好落在同一秒的旧 commit 仍可能被计入
     * 一次 —— 后果只是该章被多润色一次，安全方向。
     */
    fun progress(job: NovelWorkspaceGhostwriteJob, store: NovelWorkspaceStore): Int {
        val ledger = NovelWorkspaceLedger.load(store.rootDirectory)
        if (job.mode == NovelWorkspaceGhostwriteMode.Polish) {
            val chaptersPrefix = NovelWorkspacePaths.branchPrefix(job.branchSlug) + "/chapters/"
            val ordinalRange = job.startOrdinal..job.endOrdinal
            val polished = ledger.commits.count { commit ->
                commit.message == NovelWorkspaceLedger.Message.POLISH &&
                    // Exact compare: commit timestamps are persisted second-truncated, so
                    // flooring the job boundary widened the same-second window (see KDoc).
                    !commit.createdAt.isBefore(job.createdAt) &&
                    NovelWorkspaceLedger.changedPaths(commit, ledger.commits)
                        .asSequence()
                        .mapNotNull { path ->
                            path.takeIf { it.startsWith(chaptersPrefix) }
                                ?.let(NovelWorkspacePaths::chapterOrdinalFromPath)
                        }
                        .any { it in ordinalRange }
            }
            return polished.coerceIn(0, job.targetChapterCount)
        }
        val boundJob = load(store.rootDirectory, job.id)
            ?.takeIf { it.executionKey == job.executionKey && it.isVersionBound }
            ?: job
        if (boundJob.isVersionBound) {
            val ancestryIds = ledger.ancestry(ledger.heads[boundJob.branchId]).mapTo(mutableSetOf()) { it.id }
            return boundJob.receipts
                .asSequence()
                .filter { it.commitId in ancestryIds }
                .filter {
                    it.chapterOrdinal in
                        (boundJob.startOrdinal + 1)..(boundJob.startOrdinal + boundJob.targetChapterCount)
                }
                .distinctBy { it.chapterOrdinal }
                .count()
                .coerceIn(0, boundJob.targetChapterCount)
        }
        val ordinals = NovelWorkspaceLedger.committedChapterOrdinals(store, ledger, job.branchSlug)
        val currentMax = ordinals.maxOrNull() ?: job.startOrdinal
        return (currentMax - job.startOrdinal).coerceIn(0, job.targetChapterCount)
    }
}
