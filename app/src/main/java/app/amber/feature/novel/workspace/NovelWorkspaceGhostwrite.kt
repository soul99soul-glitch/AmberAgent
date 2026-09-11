package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteCandidate
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspaceJointReviewResult
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import app.amber.feature.novelworkspace.sha256Hex
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ghostwrite on the workspace model. A version-bound write chapter is generated as a
 * private candidate, jointly reviewed, then committed with its continuity state in one
 * host-owned commit. Durable receipts, not the visible chapter count, drive resume.
 */
class NovelWorkspaceGhostwriteCoordinator(
    private val runtime: NovelWorkspaceRuntime,
    private val turnLauncher: NovelTurnLauncher,
) {

    data class GhostwriteChapterResult(
        val commitId: String?,
        val candidate: NovelWorkspaceGhostwriteCandidate? = null,
        val error: String? = null,
    )

    data class JointReviewTurnResult(
        val review: NovelWorkspaceJointReviewResult? = null,
        val error: String? = null,
        val retryable: Boolean = false,
    )

    data class NextPlanTurnResult(
        val nextPlan: String? = null,
        val error: String? = null,
    )

    @Serializable
    private data class NextPlanPayload(val nextPlan: String)

    /** One chapter-writing turn; version-bound jobs persist a private candidate only. */
    suspend fun ghostwriteOneChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        settings: Settings,
        model: Model,
        chapterOrdinal: Int,
        maxSteps: Int = 24,
        injection: NovelWorkspaceInjectionFlags? = null,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
        repairInstructions: List<String> = emptyList(),
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
    ): GhostwriteChapterResult {
        val store = NovelWorkspaceStore(projectDirectory)
        val commitIdBeforeTurn = NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.id
        var boundJob = ownerJobId?.let { NovelWorkspaceGhostwriteJobs.load(projectDirectory, it) }
        val candidateMode = boundJob?.isVersionBound == true
        val rewrite = repairInstructions.isNotEmpty()
        if (candidateMode) {
            val currentCandidate = boundJob.pendingCandidate
            if (!rewrite && currentCandidate?.chapterOrdinal == chapterOrdinal) {
                return GhostwriteChapterResult(commitId = null, candidate = currentCandidate)
            }
            if (rewrite && currentCandidate == null) {
                return GhostwriteChapterResult(
                    commitId = null,
                    error = localized(
                        locale,
                        chinese = "没有可重写的章节候选",
                        english = "There is no chapter candidate to rewrite.",
                    ),
                )
            }
            if (rewrite && currentCandidate != null &&
                currentCandidate.attempt >= NovelWorkspaceGhostwriteCandidate.MAX_REWRITE_ATTEMPTS
            ) {
                return GhostwriteChapterResult(
                    commitId = null,
                    error = localized(
                        locale,
                        chinese = "本章已达到最多两次定向重写上限",
                        english = "This chapter has reached the limit of two targeted rewrites.",
                    ),
                )
            }
            boundJob = NovelWorkspaceGhostwriteJobs.beginCandidateTurn(
                projectDirectory = projectDirectory,
                jobId = checkNotNull(ownerJobId),
                executionId = checkNotNull(ownerExecutionId),
                chapterOrdinal = chapterOrdinal,
                rewrite = rewrite,
            ) ?: return GhostwriteChapterResult(
                commitId = null,
                error = localized(
                    locale,
                    chinese = "代笔已暂停、取消，或正文与确认计划的版本绑定已变化",
                    english = "The ghostwrite batch was paused or cancelled, or its manuscript/plan binding changed.",
                ),
            )
        }
        val plan = boundJob?.takeIf { it.isVersionBound }?.confirmedPlan
            ?: store.read(NovelWorkspacePaths.branchPrefix(branchSlug) + "/plan/this-chapter.md")
                ?.let { NovelWorkspaceMarkdown.parseFile(it).body }
        val basePrompt = NovelWorkspacePrompts.ghostwriteChapter(
            chapterOrdinal,
            plan,
            locale = locale,
        )
        val writingPrompt = if (rewrite) {
            basePrompt + localized(
                locale,
                chinese = "\n\n## 本次定向修复\n只修复下列已确认问题，保持本章计划、核心事件和未被指出的正文不变：\n" +
                    repairInstructions.joinToString("\n") { "- $it" },
                english = "\n\n## Targeted repairs\nFix only the confirmed issues below. Keep the chapter plan, core events, and unaffected prose unchanged:\n" +
                    repairInstructions.joinToString("\n") { "- $it" },
            )
        } else {
            basePrompt
        }
        // A wedged/trickling provider could otherwise hang a chapter turn for the
        // OkHttp read-timeout window or longer with no feedback anywhere; bound it
        // and surface as a chapter failure (retry/stop logic then applies).
        val turnHandle = turnLauncher.launch(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = projectDirectory,
                branchId = branchId,
                branchSlug = branchSlug,
                userText = localized(
                    locale,
                    chinese = "请写第 $chapterOrdinal 章。",
                    english = "Write chapter $chapterOrdinal.",
                ),
                systemPrompt = writingPrompt,
                settings = settings,
                model = model,
                maxSteps = maxSteps,
                readOnlyTools = candidateMode,
                autoApproveCanon = !candidateMode,
                autoCommitMessage = "代笔收录",
                injection = injection,
                ownerJobId = ownerJobId,
                ownerExecutionId = ownerExecutionId,
                ghostwriteChapterOrdinal = chapterOrdinal,
                ghostwritePlanId = boundJob?.takeIf { it.isVersionBound }?.planId,
                ghostwritePlanDigest = boundJob?.takeIf { it.isVersionBound }?.planDigest,
                fallbackErrorMessage = fallbackErrorMessage,
                locale = locale,
            ),
            runtime,
        )
        // One turn emits exactly one terminal event (Completed or Failed); the
        // deltas in between are not needed for an unattended chapter.
        val terminal = try {
            kotlinx.coroutines.withTimeout(CHAPTER_TURN_TIMEOUT_MS) {
                turnHandle.events.first { event ->
                    event is NovelWorkspaceRuntime.TurnEvent.Completed ||
                        event is NovelWorkspaceRuntime.TurnEvent.Failed
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            // The launcher already cancelled the run when withTimeout cancelled
            // the collect; wait for the handler's rollback-on-cancel to settle
            // so the chapter failure never races the restore writes.
            runCatching { turnHandle.awaitTerminal() }
            return GhostwriteChapterResult(
                commitId = null,
                error = localized(
                    locale,
                    chinese = "本章生成超时（${CHAPTER_TURN_TIMEOUT_MS / 60_000} 分钟无完成），已中止本轮",
                    english = "Chapter generation timed out (${CHAPTER_TURN_TIMEOUT_MS / 60_000} minutes without completion); this turn was aborted.",
                ),
            )
        }
        val failure = terminal as? NovelWorkspaceRuntime.TurnEvent.Failed
        if (failure != null) return GhostwriteChapterResult(commitId = null, error = failure.message)
        val finalText = (terminal as? NovelWorkspaceRuntime.TurnEvent.Completed)
            ?.finalText
            ?.trim()
            .orEmpty()
        if (candidateMode) {
            if (finalText.length < MIN_HOSTWRITE_CHARS) {
                return GhostwriteChapterResult(
                    commitId = null,
                    error = localized(
                        locale,
                        chinese = "本轮未返回可用的完整章节正文",
                        english = "This turn did not return a usable complete chapter.",
                    ),
                )
            }
            val currentJob = checkNotNull(boundJob)
            val previous = currentJob.pendingCandidate
            val now = Instant.now()
            val (title, body) = splitChapterTitle(finalText, chapterOrdinal)
            val candidate = NovelWorkspaceGhostwriteCandidate(
                id = previous?.id ?: "CAND-${UUID.randomUUID().toString().uppercase()}",
                chapterOrdinal = chapterOrdinal,
                planId = currentJob.planId,
                planDigest = currentJob.planDigest,
                attempt = if (rewrite) checkNotNull(previous).attempt + 1 else 0,
                title = title,
                body = body,
                repairInstructions = repairInstructions,
                createdAt = previous?.createdAt ?: now,
                updatedAt = now,
            )
            val recorded = NovelWorkspaceGhostwriteJobs.recordCandidate(
                projectDirectory = projectDirectory,
                jobId = checkNotNull(ownerJobId),
                executionId = checkNotNull(ownerExecutionId),
                candidate = candidate,
            ) ?: return GhostwriteChapterResult(
                commitId = null,
                error = localized(
                    locale,
                    chinese = "候选生成完成，但批次版本绑定已变化，未写入正史",
                    english = "The candidate was generated, but the batch binding changed; canon was not modified.",
                ),
            )
            return GhostwriteChapterResult(commitId = null, candidate = recorded.pendingCandidate)
        }
        // A canon commit happened iff the ledger head advanced past where we started.
        val ledgerAfterTurn = NovelWorkspaceLedger.load(projectDirectory)
        val commit = ledgerAfterTurn.headOf(branchId)
        val targetCommitted = chapterOrdinal in NovelWorkspaceLedger.committedChapterOrdinals(
            store,
            ledgerAfterTurn,
            branchSlug,
        )
        if (targetCommitted && commit != null && commit.id != commitIdBeforeTurn) {
            return GhostwriteChapterResult(commitId = commit.id)
        }
        // Host-write fallback (device-proven): many providers narrate the chapter
        // as plain text instead of calling the write tool. A substantial final
        // answer IS the chapter — file it host-side and commit.
        if (finalText.length >= MIN_HOSTWRITE_CHARS) {
            val (title, body) = splitChapterTitle(finalText, chapterOrdinal)
            val filed = try {
                runtime.commitGhostwrittenChapter(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = branchSlug,
                    chapterOrdinal = chapterOrdinal,
                    title = title,
                    body = body,
                    ownerJobId = ownerJobId,
                    ownerExecutionId = ownerExecutionId,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return GhostwriteChapterResult(
                    commitId = null,
                    error = localizedOwnerError(
                        error = error,
                        locale = locale,
                        fallbackErrorMessage = fallbackErrorMessage,
                        chinese = "代笔已暂停或取消",
                        english = "The ghostwrite batch is paused or cancelled.",
                    ),
                )
            }
            return GhostwriteChapterResult(commitId = filed.id)
        }
        return GhostwriteChapterResult(commitId = null)
    }

    /** Read-only, candidate-bound joint review with a hard 10k output-token floor. */
    suspend fun reviewCandidate(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        settings: Settings,
        model: Model,
        ownerJobId: String,
        ownerExecutionId: String,
        injection: NovelWorkspaceInjectionFlags? = null,
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
    ): JointReviewTurnResult {
        val owned = NovelWorkspaceGhostwriteJobs.withRunningOwner(
            projectDirectory,
            ownerJobId,
            ownerExecutionId,
        ) { NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId) }
            ?: return JointReviewTurnResult(
                error = localized(
                    locale,
                    chinese = "代笔已暂停、取消，或正文与确认计划的版本绑定已变化",
                    english = "The ghostwrite batch was paused or cancelled, or its manuscript/plan binding changed.",
                ),
            )
        val candidate = owned.pendingCandidate ?: return JointReviewTurnResult(
            error = localized(
                locale,
                chinese = "没有可审核的章节候选",
                english = "There is no chapter candidate to review.",
            ),
        )
        owned.pendingReview?.takeIf { it.candidateId == candidate.id }?.let {
            return JointReviewTurnResult(review = it)
        }
        val reviewSettings = settings.copy(maxTokens = maxOf(settings.maxTokens ?: 0, MIN_REVIEW_OUTPUT_TOKENS))
        val handle = turnLauncher.launch(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = projectDirectory,
                branchId = branchId,
                branchSlug = branchSlug,
                userText = localized(
                    locale,
                    chinese = "请联合审核候选 ${candidate.id}，只返回严格 JSON。",
                    english = "Joint-review candidate ${candidate.id}; return strict JSON only.",
                ),
                systemPrompt = NovelWorkspacePrompts.jointGhostwriteReview(
                    candidate = candidate,
                    confirmedPlan = owned.confirmedPlan,
                    requiresNextPlan = NovelWorkspaceGhostwriteJobs.progress(
                        owned,
                        NovelWorkspaceStore(projectDirectory),
                    ) + 1 < owned.targetChapterCount,
                    locale = locale,
                ),
                settings = reviewSettings,
                model = model,
                maxSteps = 12,
                readOnlyTools = true,
                autoApproveCanon = false,
                injection = injection,
                ownerJobId = ownerJobId,
                ownerExecutionId = ownerExecutionId,
                ghostwriteChapterOrdinal = candidate.chapterOrdinal,
                ghostwritePlanId = candidate.planId,
                ghostwritePlanDigest = candidate.planDigest,
                fallbackErrorMessage = fallbackErrorMessage,
                locale = locale,
            ),
            runtime,
        )
        val terminal = try {
            kotlinx.coroutines.withTimeout(REVIEW_TURN_TIMEOUT_MS) {
                handle.events.first { event ->
                    event is NovelWorkspaceRuntime.TurnEvent.Completed ||
                        event is NovelWorkspaceRuntime.TurnEvent.Failed
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            runCatching { handle.awaitTerminal() }
            return JointReviewTurnResult(
                error = localized(
                    locale,
                    chinese = "联合审核超时，已中止本轮",
                    english = "The joint review timed out and was aborted.",
                ),
                retryable = true,
            )
        }
        (terminal as? NovelWorkspaceRuntime.TurnEvent.Failed)?.let {
            return JointReviewTurnResult(error = it.message, retryable = true)
        }
        val raw = (terminal as NovelWorkspaceRuntime.TurnEvent.Completed).finalText.trim()
        val review = parseJointReview(raw) ?: return JointReviewTurnResult(
            error = localized(
                locale,
                chinese = "联合审核未返回符合契约的严格 JSON",
                english = "The joint review did not return strict JSON matching the contract.",
            ),
        )
        val persisted = NovelWorkspaceGhostwriteJobs.recordReview(
            projectDirectory = projectDirectory,
            jobId = ownerJobId,
            executionId = ownerExecutionId,
            review = review,
        ) ?: return JointReviewTurnResult(
            error = localized(
                locale,
                chinese = "联合审核关联字段无效，或批次版本绑定已变化",
                english = "The joint review correlation is invalid, or the batch binding changed.",
            ),
        )
        return JointReviewTurnResult(review = persisted.pendingReview)
    }

    internal fun parseJointReview(text: String): NovelWorkspaceJointReviewResult? = runCatching {
        strictReviewJson.decodeFromString(
            NovelWorkspaceJointReviewResult.serializer(),
            text.trim(),
        )
    }.getOrNull()

    /** One read-only fallback planning turn when a passing review omitted nextPlan. */
    suspend fun planNextChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        settings: Settings,
        model: Model,
        ownerJobId: String,
        ownerExecutionId: String,
        injection: NovelWorkspaceInjectionFlags? = null,
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
    ): NextPlanTurnResult {
        val owned = NovelWorkspaceGhostwriteJobs.withRunningOwner(
            projectDirectory,
            ownerJobId,
            ownerExecutionId,
        ) { NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId) }
            ?: return NextPlanTurnResult(
                error = localized(
                    locale,
                    chinese = "代笔已暂停、取消，或正文与确认计划的版本绑定已变化",
                    english = "The ghostwrite batch was paused or cancelled, or its manuscript/plan binding changed.",
                ),
            )
        val candidate = owned.pendingCandidate
            ?: return NextPlanTurnResult(error = localized(locale, "没有可规划后续的章节候选", "There is no chapter candidate to plan from."))
        val review = owned.pendingReview
            ?: return NextPlanTurnResult(error = localized(locale, "没有可规划后续的联合审核结果", "There is no joint-review result to plan from."))
        review.nextPlan?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return NextPlanTurnResult(nextPlan = it)
        }
        if (owned.stage != NovelWorkspaceGhostwriteStage.Planning ||
            NovelWorkspaceGhostwriteJobs.progress(owned, NovelWorkspaceStore(projectDirectory)) + 1 >=
            owned.targetChapterCount
        ) {
            return NextPlanTurnResult(
                error = localized(locale, "当前批次不需要生成下一章计划", "This batch does not need a next-chapter plan."),
            )
        }
        val handle = turnLauncher.launch(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = projectDirectory,
                branchId = branchId,
                branchSlug = branchSlug,
                userText = localized(
                    locale,
                    chinese = "请为第 ${candidate.chapterOrdinal + 1} 章生成计划，只返回严格 JSON。",
                    english = "Plan chapter ${candidate.chapterOrdinal + 1}; return strict JSON only.",
                ),
                systemPrompt = NovelWorkspacePrompts.nextGhostwritePlan(candidate, review, locale),
                settings = settings,
                model = model,
                maxSteps = 8,
                readOnlyTools = true,
                autoApproveCanon = false,
                injection = injection,
                ownerJobId = ownerJobId,
                ownerExecutionId = ownerExecutionId,
                ghostwriteChapterOrdinal = candidate.chapterOrdinal,
                ghostwritePlanId = candidate.planId,
                ghostwritePlanDigest = candidate.planDigest,
                fallbackErrorMessage = fallbackErrorMessage,
                locale = locale,
            ),
            runtime,
        )
        val terminal = try {
            kotlinx.coroutines.withTimeout(PLANNING_TURN_TIMEOUT_MS) {
                handle.events.first { event ->
                    event is NovelWorkspaceRuntime.TurnEvent.Completed ||
                        event is NovelWorkspaceRuntime.TurnEvent.Failed
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            runCatching { handle.awaitTerminal() }
            return NextPlanTurnResult(
                error = localized(locale, "下一章规划超时，已中止本轮", "Next-chapter planning timed out and was aborted."),
            )
        }
        (terminal as? NovelWorkspaceRuntime.TurnEvent.Failed)?.let {
            return NextPlanTurnResult(error = it.message)
        }
        val nextPlan = parseNextPlan((terminal as NovelWorkspaceRuntime.TurnEvent.Completed).finalText)
            ?: return NextPlanTurnResult(
                error = localized(
                    locale,
                    chinese = "下一章规划未返回符合契约的严格 JSON",
                    english = "Next-chapter planning did not return strict JSON matching the contract.",
                ),
            )
        val persisted = NovelWorkspaceGhostwriteJobs.recordPlannedNextPlan(
            projectDirectory = projectDirectory,
            jobId = ownerJobId,
            executionId = ownerExecutionId,
            candidateId = candidate.id,
            nextPlan = nextPlan,
        ) ?: return NextPlanTurnResult(
            error = localized(
                locale,
                chinese = "下一章计划生成完成，但批次版本绑定已变化",
                english = "The next plan was generated, but the batch binding changed.",
            ),
        )
        return NextPlanTurnResult(nextPlan = persisted.pendingReview?.nextPlan)
    }

    internal fun parseNextPlan(text: String): String? = runCatching {
        strictReviewJson.decodeFromString(NextPlanPayload.serializer(), text.trim()).nextPlan.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Leading "# 标题" / "第N章 标题" line becomes the chapter title; rest is body. */
    private fun splitChapterTitle(text: String, chapterOrdinal: Int): Pair<String, String> {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return "第 $chapterOrdinal 章" to text
        val trimmed = firstLine.trim()
        val heading = trimmed.trimStart('#').trim()
        if (trimmed.startsWith("#") && heading.isNotEmpty()) {
            return heading to text.substringAfter(trimmed).trim()
        }
        if (trimmed.startsWith("第") && trimmed.contains("章")) {
            return trimmed to text.substringAfter(trimmed).trim()
        }
        return "第 $chapterOrdinal 章" to text
    }

    /**
     * Unattended single-chapter polish: one auto-committing agent turn whose write tool
     * is host-locked to the existing chapter file ([NovelWorkspaceRuntime.TurnRequest.polishChapterPath]).
     * Guards (timeout, single retry at the batch level, host-write fallback) reuse the
     * ghostwrite parameters. Turn success = the branch head advanced with a 「润色」
     * commit (the tool path); a narrated final answer of chapter length takes the
     * host-write path preserving the chapter's front matter.
     */
    suspend fun polishOneChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        settings: Settings,
        model: Model,
        chapterOrdinal: Int,
        maxSteps: Int = 24,
        injection: NovelWorkspaceInjectionFlags? = null,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
    ): GhostwriteChapterResult {
        val store = NovelWorkspaceStore(projectDirectory)
        val chaptersPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters"
        val chapterPath = store.list(chaptersPrefix).firstOrNull {
            NovelWorkspacePaths.chapterOrdinalFromPath(it) == chapterOrdinal
        } ?: return GhostwriteChapterResult(
            commitId = null,
            error = localized(
                locale,
                chinese = "第 $chapterOrdinal 章不存在，无法润色",
                english = "Chapter $chapterOrdinal does not exist and cannot be polished.",
            ),
        )
        val chapterBody = store.read(chapterPath)
            ?.let { NovelWorkspaceMarkdown.parseFile(it).body }
            .orEmpty()
        val commitIdBeforeTurn = NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.id
        val turnHandle = turnLauncher.launch(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = projectDirectory,
                branchId = branchId,
                branchSlug = branchSlug,
                userText = localized(
                    locale,
                    chinese = "请润色第 $chapterOrdinal 章。",
                    english = "Polish chapter $chapterOrdinal.",
                ),
                systemPrompt = NovelWorkspacePrompts.polishChapter(
                    chapterOrdinal = chapterOrdinal,
                    chapterPath = chapterPath,
                    chapterBody = chapterBody,
                    writingPreference = readWritingPreference(store),
                    locale = locale,
                ),
                settings = settings,
                model = model,
                maxSteps = maxSteps,
                autoApproveCanon = true,
                autoCommitMessage = NovelWorkspaceLedger.Message.POLISH,
                injection = injection,
                ownerJobId = ownerJobId,
                ownerExecutionId = ownerExecutionId,
                polishChapterPath = chapterPath,
                fallbackErrorMessage = fallbackErrorMessage,
                locale = locale,
            ),
            runtime,
        )
        val terminal = try {
            kotlinx.coroutines.withTimeout(CHAPTER_TURN_TIMEOUT_MS) {
                turnHandle.events.first { event ->
                    event is NovelWorkspaceRuntime.TurnEvent.Completed ||
                        event is NovelWorkspaceRuntime.TurnEvent.Failed
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            runCatching { turnHandle.awaitTerminal() }
            return GhostwriteChapterResult(
                commitId = null,
                error = localized(
                    locale,
                    chinese = "本章润色超时（${CHAPTER_TURN_TIMEOUT_MS / 60_000} 分钟无完成），已中止本轮",
                    english = "Chapter polishing timed out (${CHAPTER_TURN_TIMEOUT_MS / 60_000} minutes without completion); this turn was aborted.",
                ),
            )
        }
        val failure = terminal as? NovelWorkspaceRuntime.TurnEvent.Failed
        if (failure != null) return GhostwriteChapterResult(commitId = null, error = failure.message)
        // Tool path: the runtime committed the buffered chapter write as a 「润色」 commit.
        val commit = NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)
        if (commit != null && commit.id != commitIdBeforeTurn &&
            commit.message == NovelWorkspaceLedger.Message.POLISH
        ) {
            return GhostwriteChapterResult(commitId = commit.id)
        }
        // Host-write fallback (same rationale as ghostwrite): a substantial final
        // answer IS the polished chapter — replace the body, keep the front matter.
        val finalText = (terminal as? NovelWorkspaceRuntime.TurnEvent.Completed)
            ?.finalText
            ?.trim()
            .orEmpty()
        if (finalText.length >= MIN_HOSTWRITE_CHARS) {
            val filed = try {
                runtime.commitPolishedChapter(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = branchSlug,
                    chapterPath = chapterPath,
                    polishedBody = finalText,
                    ownerJobId = ownerJobId,
                    ownerExecutionId = ownerExecutionId,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return GhostwriteChapterResult(
                    commitId = null,
                    error = localizedOwnerError(
                        error = error,
                        locale = locale,
                        fallbackErrorMessage = fallbackErrorMessage,
                        chinese = "润色已暂停或取消",
                        english = "The polishing batch is paused or cancelled.",
                    ),
                )
            }
            return GhostwriteChapterResult(commitId = filed.id)
        }
        return GhostwriteChapterResult(commitId = null)
    }

    /** Writing preference = the setting/writing card body (same source as the panel). */
    private fun readWritingPreference(store: NovelWorkspaceStore): String =
        store.list(NovelWorkspacePaths.SETTING_DIR + "/writing")
            .firstOrNull()
            ?.let { store.read(it) }
            ?.let { NovelWorkspaceMarkdown.parseFile(it).body }
            .orEmpty()

    sealed interface BatchResult {
        data class Completed(val chaptersWritten: Int) : BatchResult
        data class Failed(val chaptersWritten: Int, val error: String) : BatchResult
        data class Stopped(val chaptersWritten: Int) : BatchResult
    }

    /**
     * Run a batch, committing each finished chapter. Version-bound write progress is
     * restored from durable receipts; legacy write and polish keep their existing
     * commit-derived cursor. One loop skeleton serves both modes:
     * - Write: private candidate -> joint review -> one host-owned canonical commit.
     * - Polish: next target = startOrdinal + progress (polish turn + host 剧情指针
     *   commit; see [NovelWorkspaceRuntime.commitPolishPointer] for why the pairing
     *   commit exists — the batch must end NOT plot-stale).
     * Sequential chapters, cooperative pause/cancel, executionId token rotation and
     * the single-retry policy are identical across modes on purpose.
     */
    suspend fun runBatch(
        job: NovelWorkspaceGhostwriteJob,
        projectDirectory: File,
        branchId: String,
        settings: Settings,
        model: Model,
        reviewModel: Model = model,
        isPaused: () -> Boolean,
        injection: NovelWorkspaceInjectionFlags? = null,
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
        onChapter: suspend (Int) -> Unit,
    ): BatchResult {
        val store = NovelWorkspaceStore(projectDirectory)
        val polish = job.mode == NovelWorkspaceGhostwriteMode.Polish
        var written = NovelWorkspaceGhostwriteJobs.progress(job, store)
        var noProgressStreak = 0
        try {
            // Polish invariant repair: a crash (or pointer failure + restart) between a
            // 润色 commit and its 剧情指针 commit would leave chapters/ newer than plot/.
            // Re-emit the pointer for the last finished chapter before continuing.
            if (polish && written > 0 &&
                NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(projectDirectory), job.branchSlug)
            ) {
                try {
                    runtime.commitPolishPointer(
                        projectDirectory,
                        branchId,
                        job.branchSlug,
                        job.startOrdinal + written - 1,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    return BatchResult.Failed(written, pointerCommitFailure(error))
                }
            }
            while (written < job.targetChapterCount) {
                if (isPaused() || isCancelled(job, projectDirectory)) return BatchResult.Stopped(written)
                // D-D: never touch chapters while a middle-chapter edit is unresolved.
                if (NovelWorkspaceUnresolvedStore.entryFor(projectDirectory, job.branchSlug) != null) {
                    return BatchResult.Failed(
                        written,
                        if (polish) {
                            localized(
                                locale,
                                chinese = "存在未解决的中间章修改，请先处理再润色",
                                english = "Unresolved middle-chapter edits exist. Resolve them before polishing.",
                            )
                        } else {
                            localized(
                                locale,
                                chinese = "存在未解决的中间章修改，请先处理再代笔",
                                english = "Unresolved middle-chapter edits exist. Resolve them before ghostwriting.",
                            )
                        },
                    )
                }
                val before = written
                val nextOrdinal = if (polish) {
                    job.startOrdinal + written
                } else {
                    (NovelWorkspaceLedger.committedChapterOrdinals(
                        store,
                        NovelWorkspaceLedger.load(projectDirectory),
                        job.branchSlug,
                    ).maxOrNull() ?: 0) + 1
                }
                if (!polish && job.isVersionBound) {
                    suspend fun generateCandidate(repairInstructions: List<String>): GhostwriteChapterResult {
                        var result = ghostwriteOneChapter(
                            projectDirectory = projectDirectory,
                            branchId = branchId,
                            branchSlug = job.branchSlug,
                            settings = settings,
                            model = model,
                            chapterOrdinal = nextOrdinal,
                            injection = injection,
                            locale = locale,
                            fallbackErrorMessage = fallbackErrorMessage,
                            ownerJobId = job.id,
                            ownerExecutionId = job.executionKey,
                            repairInstructions = repairInstructions,
                        )
                        if (result.error != null &&
                            !isPaused() && !isCancelled(job, projectDirectory)
                        ) {
                            delay(CHAPTER_RETRY_DELAY_MS)
                            if (!isPaused() && !isCancelled(job, projectDirectory)) {
                                result = ghostwriteOneChapter(
                                    projectDirectory = projectDirectory,
                                    branchId = branchId,
                                    branchSlug = job.branchSlug,
                                    settings = settings,
                                    model = model,
                                    chapterOrdinal = nextOrdinal,
                                    injection = injection,
                                    locale = locale,
                                    fallbackErrorMessage = fallbackErrorMessage,
                                    ownerJobId = job.id,
                                    ownerExecutionId = job.executionKey,
                                    repairInstructions = repairInstructions,
                                )
                            }
                        }
                        return result
                    }

                    while (true) {
                        if (isPaused() || isCancelled(job, projectDirectory)) {
                            return BatchResult.Stopped(written)
                        }
                        var latest = NovelWorkspaceGhostwriteJobs.load(projectDirectory, job.id)
                            ?: return BatchResult.Stopped(written)
                        var candidate = latest.pendingCandidate
                        if (candidate == null) {
                            val generated = generateCandidate(emptyList())
                            if (generated.error != null) {
                                if (isPaused() || isCancelled(job, projectDirectory)) {
                                    return BatchResult.Stopped(written)
                                }
                                return BatchResult.Failed(written, generated.error)
                            }
                            candidate = generated.candidate
                                ?: return BatchResult.Failed(
                                    written,
                                    localized(
                                        locale,
                                        chinese = "本轮未生成可审核的章节候选",
                                        english = "This turn did not generate a reviewable chapter candidate.",
                                    ),
                                )
                            latest = NovelWorkspaceGhostwriteJobs.load(projectDirectory, job.id)
                                ?: return BatchResult.Stopped(written)
                        }

                        var reviewed = latest.pendingReview?.let { JointReviewTurnResult(review = it) }
                            ?: reviewCandidate(
                                projectDirectory = projectDirectory,
                                branchId = branchId,
                                branchSlug = job.branchSlug,
                                settings = settings,
                                model = reviewModel,
                                ownerJobId = job.id,
                                ownerExecutionId = job.executionKey,
                                injection = injection,
                                locale = locale,
                                fallbackErrorMessage = fallbackErrorMessage,
                            )
                        if (reviewed.error != null && reviewed.retryable &&
                            !isPaused() && !isCancelled(job, projectDirectory)
                        ) {
                            delay(CHAPTER_RETRY_DELAY_MS)
                            if (!isPaused() && !isCancelled(job, projectDirectory)) {
                                reviewed = reviewCandidate(
                                    projectDirectory = projectDirectory,
                                    branchId = branchId,
                                    branchSlug = job.branchSlug,
                                    settings = settings,
                                    model = reviewModel,
                                    ownerJobId = job.id,
                                    ownerExecutionId = job.executionKey,
                                    injection = injection,
                                    locale = locale,
                                    fallbackErrorMessage = fallbackErrorMessage,
                                )
                            }
                        }
                        if (reviewed.error != null) {
                            if (isPaused() || isCancelled(job, projectDirectory)) {
                                return BatchResult.Stopped(written)
                            }
                            return BatchResult.Failed(written, reviewed.error)
                        }
                        val review = reviewed.review ?: return BatchResult.Failed(
                            written,
                            localized(
                                locale,
                                chinese = "联合审核没有可执行结论",
                                english = "The joint review returned no actionable decision.",
                            ),
                        )
                        if (review.blocking) {
                            return BatchResult.Failed(
                                written,
                                review.findings.joinToString("；") { it.message }
                                    .ifBlank {
                                        localized(
                                            locale,
                                            chinese = "联合审核发现阻断问题",
                                            english = "The joint review found a blocking issue.",
                                        )
                                    },
                            )
                        }
                        if (review.rewriteRequired) {
                            if (candidate.attempt >= NovelWorkspaceGhostwriteCandidate.MAX_REWRITE_ATTEMPTS) {
                                val finalFindings = review.findings
                                    .map { it.message.trim() }
                                    .filter { it.isNotEmpty() }
                                    .joinToString("；")
                                val exhausted = localized(
                                    locale,
                                    chinese = "本章定向重写两次后仍未通过联合审核",
                                    english = "The chapter still failed joint review after two targeted rewrites.",
                                )
                                return BatchResult.Failed(
                                    written,
                                    if (finalFindings.isBlank()) exhausted else "$exhausted：$finalFindings",
                                )
                            }
                            val rewritten = generateCandidate(review.repairInstructions)
                            if (rewritten.error != null) {
                                if (isPaused() || isCancelled(job, projectDirectory)) {
                                    return BatchResult.Stopped(written)
                                }
                                return BatchResult.Failed(written, rewritten.error)
                            }
                            continue
                        }
                        latest = NovelWorkspaceGhostwriteJobs.load(projectDirectory, job.id)
                            ?: return BatchResult.Stopped(written)
                        if (latest.stage == NovelWorkspaceGhostwriteStage.Planning) {
                            val planned = planNextChapter(
                                projectDirectory = projectDirectory,
                                branchId = branchId,
                                branchSlug = job.branchSlug,
                                settings = settings,
                                model = reviewModel,
                                ownerJobId = job.id,
                                ownerExecutionId = job.executionKey,
                                injection = injection,
                                locale = locale,
                                fallbackErrorMessage = fallbackErrorMessage,
                            )
                            if (planned.error != null) {
                                if (isPaused() || isCancelled(job, projectDirectory)) {
                                    return BatchResult.Stopped(written)
                                }
                                return BatchResult.Failed(written, planned.error)
                            }
                        }
                        try {
                            runtime.commitReviewedChapter(
                                projectDirectory = projectDirectory,
                                branchId = branchId,
                                branchSlug = job.branchSlug,
                                ownerJobId = job.id,
                                ownerExecutionId = job.executionKey,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            return BatchResult.Failed(written, error.message ?: fallbackErrorMessage)
                        }
                        val after = NovelWorkspaceGhostwriteJobs.progress(job, store)
                        if (after <= before) {
                            return BatchResult.Failed(
                                written,
                                localized(
                                    locale,
                                    chinese = "章节已审核收录，但批次进度未推进",
                                    english = "The reviewed chapter was committed, but batch progress did not advance.",
                                ),
                            )
                        }
                        written = after
                        onChapter(written)
                        break
                    }
                    continue
                }
                var chapter = if (polish) {
                    polishOneChapter(
                        projectDirectory = projectDirectory,
                        branchId = branchId,
                        branchSlug = job.branchSlug,
                        settings = settings,
                        model = model,
                        chapterOrdinal = nextOrdinal,
                        injection = injection,
                        locale = locale,
                        fallbackErrorMessage = fallbackErrorMessage,
                        ownerJobId = job.id,
                        ownerExecutionId = job.executionKey,
                    )
                } else {
                    ghostwriteOneChapter(
                        projectDirectory = projectDirectory,
                        branchId = branchId,
                        branchSlug = job.branchSlug,
                        settings = settings,
                        model = model,
                        chapterOrdinal = nextOrdinal,
                        injection = injection,
                        locale = locale,
                        fallbackErrorMessage = fallbackErrorMessage,
                        ownerJobId = job.id,
                        ownerExecutionId = job.executionKey,
                    )
                }
                // One retry absorbs transient provider blips (rate limit, dropped
                // connection); a second failure ends the batch with the error surfaced.
                if (chapter.error != null) {
                    if (isPaused() || isCancelled(job, projectDirectory)) return BatchResult.Stopped(written)
                    delay(CHAPTER_RETRY_DELAY_MS)
                    // Re-check after the backoff: pause during the wait must not
                    // still run a full turn (device-observed review finding).
                    if (isPaused() || isCancelled(job, projectDirectory)) return BatchResult.Stopped(written)
                    chapter = if (polish) {
                        polishOneChapter(
                            projectDirectory = projectDirectory,
                            branchId = branchId,
                            branchSlug = job.branchSlug,
                            settings = settings,
                            model = model,
                            chapterOrdinal = nextOrdinal,
                            injection = injection,
                            locale = locale,
                            fallbackErrorMessage = fallbackErrorMessage,
                            ownerJobId = job.id,
                            ownerExecutionId = job.executionKey,
                        )
                    } else {
                        ghostwriteOneChapter(
                            projectDirectory = projectDirectory,
                            branchId = branchId,
                            branchSlug = job.branchSlug,
                            settings = settings,
                            model = model,
                            chapterOrdinal = nextOrdinal,
                            injection = injection,
                            locale = locale,
                            fallbackErrorMessage = fallbackErrorMessage,
                            ownerJobId = job.id,
                            ownerExecutionId = job.executionKey,
                        )
                    }
                }
                if (chapter.error != null) {
                    return BatchResult.Failed(written, chapter.error)
                }
                // Polish freshness pairing: a round that actually landed a 润色 commit
                // (tool path or host-write fallback, chapter.commitId != null) is
                // immediately followed by its 剧情指针 commit so plot staleness never
                // opens mid-batch. A no-output round (模型寒暄：chapter.error == null 且
                // 没有任何润色落盘) must NOT emit a pointer — chapters/ 未动，剧情并未
                // 落后，补指针只会在账本里造出虚假的「第 N 章已润色」记录；该轮由下方
                // no-progress guard 计数并最终终止批次。润色 commit 与指针 commit 之间
                // 的暂停/取消/崩溃窗口不因本条件扩大：指针紧跟润色同步发出，悬挂形状
                // 由 runBatch 入口补发 + repairDanglingPolishPointer 自愈兜底。
                if (polish && chapter.commitId != null) {
                    try {
                        runtime.commitPolishPointer(projectDirectory, branchId, job.branchSlug, nextOrdinal)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        return BatchResult.Failed(written, pointerCommitFailure(error))
                    }
                }
                // No-progress guard: a turn that didn't finish a chapter must not spin forever.
                val after = NovelWorkspaceGhostwriteJobs.progress(job, store)
                if (after > before) {
                    noProgressStreak = 0
                } else {
                    noProgressStreak += 1
                    if (noProgressStreak >= MAX_NO_PROGRESS_TURNS) {
                        return BatchResult.Failed(
                            written,
                            if (polish) {
                                localized(
                                    locale,
                                    chinese = "连续 $MAX_NO_PROGRESS_TURNS 轮未完成章节润色，已停止避免空转；请检查模型是否按要求写回润色正文",
                                    english = "No chapter was polished in $MAX_NO_PROGRESS_TURNS consecutive turns. Stopped to avoid spinning; check that the model writes the polished prose as requested.",
                                )
                            } else {
                                localized(
                                    locale,
                                    chinese = "连续 $MAX_NO_PROGRESS_TURNS 轮未产出新章节，已停止避免空转；请检查模型是否按要求写正文",
                                    english = "No new chapter was produced in $MAX_NO_PROGRESS_TURNS consecutive turns. Stopped to avoid spinning; check that the model writes the chapter as requested.",
                                )
                            },
                        )
                    }
                }
                written = after
                onChapter(written)
            }
        } catch (error: CancellationException) {
            throw error
        }
        return BatchResult.Completed(written)
    }

    private fun isCancelled(job: NovelWorkspaceGhostwriteJob, projectDirectory: File): Boolean =
        NovelWorkspaceGhostwriteJobs.load(projectDirectory, job.id)?.let {
            it.status == NovelWorkspaceGhostwriteJob.STATUS_CANCELLED ||
                it.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED ||
                it.executionKey != job.executionKey
        } ?: true

    fun reconcileReviewedChapter(
        projectDirectory: File,
        jobId: String,
        executionId: String,
    ): Boolean = runtime.reconcileReviewedChapter(projectDirectory, jobId, executionId)

    /**
     * Crash-window self-heal for the polish freshness pairing（润色 → 剧情指针）. If the
     * process died between a 润色 commit and its pairing pointer commit, the branch reads
     * plot-stale while the only missing piece is that pointer — the stale message telling
     * the user to sync plot/current.md would be misleading and the gate a permanent
     * dead-end (the job behind the window is already terminal). Re-emits the pointer for
     * the dangling chapter. Returns the healed ordinal, or null when the staleness has
     * any other shape (manual edit / collection — a real plot gap) or the repair itself
     * failed; the caller's normal freshness gate then keeps refusing as before.
     */
    fun repairDanglingPolishPointer(projectDirectory: File, branchSlug: String): Int? {
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val dangling = NovelWorkspaceLedger.danglingPolishChapterOrdinal(
            NovelWorkspaceStore(projectDirectory),
            ledger,
            branchSlug,
        ) ?: return null
        val store = NovelWorkspaceStore(projectDirectory)
        val branchId = NovelWorkspaceLedger.branchId(store, ledger, branchSlug) ?: return null
        return try {
            runtime.commitPolishPointer(projectDirectory, branchId, branchSlug, dangling)
            dangling
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    /**
     * Validate + heal + claim a polish batch — everything
     * [NovelWorkspaceGhostwriteController.startPolishBatch] does before its WorkManager
     * enqueue, kept on the coordinator (which owns the runtime and no Android types) so
     * the JVM suite can drive the real start path. Order matters: the dangling-pointer
     * self-heal runs FIRST, because a crash between 润色 and its pairing commit is the
     * one stale-plot shape a polish batch can cure by itself; every other stale shape
     * (and a failed repair) still hits the unchanged freshness refusal below.
     */
    fun preparePolishBatch(
        projectDirectory: File,
        branchSlug: String,
        fromOrdinal: Int,
        toOrdinal: Int,
        locale: Locale = Locale.CHINESE,
    ): NovelWorkspaceGhostwriteJob {
        require(fromOrdinal in 1..toOrdinal) {
            localized(locale, "润色范围无效", "The polishing range is invalid.")
        }
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        if (NovelWorkspaceLedger.isPlotStale(store, ledger, branchSlug)) {
            repairDanglingPolishPointer(projectDirectory, branchSlug)
        }
        val healed = NovelWorkspaceLedger.load(projectDirectory)
        check(!NovelWorkspaceLedger.isPlotStale(store, healed, branchSlug)) {
            localized(
                locale,
                "剧情落后于正文。请切到“讨论”，发送“根据最新正文同步 plot/current.md”，并批准剧情修改后再润色",
                "The plot is behind the manuscript. Switch to Discussion, send “Sync plot/current.md from the latest manuscript”, approve the plot changes, then polish again.",
            )
        }
        check(NovelWorkspaceUnresolvedStore.entryFor(projectDirectory, branchSlug) == null) {
            localized(
                locale,
                "存在未解决的中间章修改，请先处理（确认无碍/重写后章）再润色",
                "Unresolved middle-chapter edits exist. Resolve them (confirm safe or rewrite later chapters) before polishing.",
            )
        }
        val existing = NovelWorkspaceLedger.workingChapterOrdinals(store, branchSlug).toSet()
        val missing = (fromOrdinal..toOrdinal).firstOrNull { it !in existing }
        check(missing == null) {
            localized(locale, "第 $missing 章不存在，无法润色", "Chapter $missing does not exist and cannot be polished.")
        }
        return newPolishJob(projectDirectory, branchSlug, fromOrdinal, toOrdinal, locale)
    }

    /** Create a new batch job (cursor = current manuscript state). */
    fun newJob(
        projectDirectory: File,
        branchSlug: String,
        targetChapterCount: Int,
        locale: Locale = Locale.CHINESE,
    ): NovelWorkspaceGhostwriteJob {
        require(targetChapterCount in 1..MAX_GHOSTWRITE_CHAPTERS) {
            localized(
                locale,
                "代笔章数必须在 1 到 $MAX_GHOSTWRITE_CHAPTERS 之间",
                "The ghostwrite target must be between 1 and $MAX_GHOSTWRITE_CHAPTERS chapters.",
            )
        }
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val branchId = checkNotNull(NovelWorkspaceLedger.branchId(store, ledger, branchSlug)) {
            localized(locale, "当前分支不存在", "The current branch does not exist.")
        }
        val planRaw = store.read(NovelWorkspacePaths.branchPrefix(branchSlug) + "/plan/this-chapter.md")
            ?: ""
        val parsedPlan = NovelWorkspaceMarkdown.parseFile(planRaw)
        val confirmedPlan = parsedPlan.body.trim()
        check(confirmedPlan.isNotBlank()) {
            localized(
                locale,
                "请先填写或生成本章计划，再开始代笔",
                "Create or enter the chapter plan before starting ghostwriting.",
            )
        }
        val planDigest = sha256Hex(confirmedPlan)
        val planId = parsedPlan.fields["id"]?.takeIf { it.isNotBlank() }
            ?: "PLAN-${planDigest.take(16).uppercase()}"
        val startOrdinal = NovelWorkspaceLedger.committedChapterOrdinals(
            store,
            ledger,
            branchSlug,
        ).maxOrNull() ?: 0
        val headId = ledger.heads[branchId]
        val treeDigest = NovelWorkspaceLedger.treeSHA256(store.fileTree())
        val jobId = UUID.randomUUID().toString().uppercase()
        val job = NovelWorkspaceGhostwriteJob(
            id = jobId,
            executionId = jobId,
            branchSlug = branchSlug,
            targetChapterCount = targetChapterCount,
            startOrdinal = startOrdinal,
            branchId = branchId,
            baseHeadId = headId,
            expectedHeadId = headId,
            baseTreeDigest = treeDigest,
            expectedTreeDigest = treeDigest,
            planId = planId,
            planDigest = planDigest,
            confirmedPlan = confirmedPlan,
            currentChapterOrdinal = startOrdinal + 1,
            stage = app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage.Writing,
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        check(NovelWorkspaceGhostwriteJobs.saveIfNoActive(job, projectDirectory)) {
            localized(locale, "已有代笔批次占用当前分支，请先继续或取消该批次", "A ghostwrite batch already occupies this branch. Resume or cancel it first.")
        }
        return job
    }

    /**
     * Create a new batch polish job over the inclusive ordinal range
     * [fromOrdinal, toOrdinal]. Range validation (chapters exist, freshness gates,
     * dangling-pointer self-heal) happens in [preparePolishBatch]; the branch claim is
     * shared with Write mode, so a polish batch and a ghostwrite batch can never own
     * the same branch at the same time.
     */
    fun newPolishJob(
        projectDirectory: File,
        branchSlug: String,
        fromOrdinal: Int,
        toOrdinal: Int,
        locale: Locale = Locale.CHINESE,
    ): NovelWorkspaceGhostwriteJob {
        val jobId = UUID.randomUUID().toString().uppercase()
        val job = NovelWorkspaceGhostwriteJob(
            id = jobId,
            executionId = jobId,
            branchSlug = branchSlug,
            targetChapterCount = toOrdinal - fromOrdinal + 1,
            startOrdinal = fromOrdinal,
            endOrdinal = toOrdinal,
            mode = NovelWorkspaceGhostwriteMode.Polish,
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        check(NovelWorkspaceGhostwriteJobs.saveIfNoActive(job, projectDirectory)) {
            localized(locale, "已有批次占用当前分支，请先继续或取消该批次", "A batch already occupies this branch. Resume or cancel it first.")
        }
        return job
    }

    fun saveJob(job: NovelWorkspaceGhostwriteJob, projectDirectory: File) {
        NovelWorkspaceGhostwriteJobs.save(job.copy(updatedAt = Instant.now()), projectDirectory)
    }

    companion object {
        /**
         * 失败原因前缀：润色 commit 已落地、其配对「剧情指针」commit 未落地的窗口
         * （[NovelWorkspaceGhostwrite.repairDanglingPolishPointer] 描述的崩溃窗口在
         * 批次内的两处入口）。失败通知按该前缀把章序回退一格 —— progress 已经把
         * 刚落地的润色 commit 计入，被通知的章节应是刚润色的那章而不是下一章。
         */
        const val REASON_POLISH_POINTER_COMMIT_FAILED = "润色指针提交失败"

        /** Turns without a new chapter commit before the batch stops instead of spinning. */
        private const val MAX_NO_PROGRESS_TURNS = 2

        /** Backoff before the single retry of a failed chapter turn. */
        private const val CHAPTER_RETRY_DELAY_MS = 15_000L

        /** Hard bound for one chapter turn; hung provider connections must not wedge the batch. */
        private const val CHAPTER_TURN_TIMEOUT_MS = 8 * 60_000L

        private const val REVIEW_TURN_TIMEOUT_MS = 8 * 60_000L

        private const val PLANNING_TURN_TIMEOUT_MS = 8 * 60_000L

        const val MIN_REVIEW_OUTPUT_TOKENS = 10_000

        /** Final answers at least this long are treated as the chapter itself (host-write path). */
        private const val MIN_HOSTWRITE_CHARS = 500

        const val MAX_GHOSTWRITE_CHAPTERS = 10

        /**
         * Upper bound used by Android's batch WakeLock. A reviewed chapter can run an
         * initial candidate plus two targeted rewrites; every candidate and review has
         * one provider retry. Legacy write and polish retain one turn plus one retry.
         */
        fun maximumBatchRuntimeMs(job: NovelWorkspaceGhostwriteJob): Long {
            val perChapter = if (job.isVersionBound) {
                val candidateVersions = NovelWorkspaceGhostwriteCandidate.MAX_REWRITE_ATTEMPTS + 1L
                candidateVersions * (
                    2L * CHAPTER_TURN_TIMEOUT_MS +
                        2L * REVIEW_TURN_TIMEOUT_MS +
                        2L * CHAPTER_RETRY_DELAY_MS
                    )
            } else {
                2L * CHAPTER_TURN_TIMEOUT_MS + CHAPTER_RETRY_DELAY_MS
            }
            val planning = if (job.isVersionBound) {
                (job.targetChapterCount - 1).coerceAtLeast(0) * PLANNING_TURN_TIMEOUT_MS
            } else {
                0L
            }
            return job.targetChapterCount * perChapter + planning + 60_000L
        }

        private val strictReviewJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
        }

        private fun localized(locale: Locale, chinese: String, english: String): String =
            if (locale.language.equals("zh", ignoreCase = true)) chinese else english

        private fun localizedOwnerError(
            error: Exception,
            locale: Locale,
            fallbackErrorMessage: String,
            chinese: String,
            english: String,
        ): String = if (error.message == chinese) {
            localized(locale, chinese, english)
        } else {
            error.message ?: fallbackErrorMessage
        }

        private fun pointerCommitFailure(error: Exception): String =
            "$REASON_POLISH_POINTER_COMMIT_FAILED：${error.message ?: "未知错误"}"
    }
}
