package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Ghostwrite on the workspace model. One chapter = one auto-committing agent turn
 * (canon writes land without an approval card); the batch cursor is the commit chain,
 * so progress and resume are recomputed from the actual manuscript, never a counter.
 */
class NovelWorkspaceGhostwriteCoordinator(
    private val runtime: NovelWorkspaceRuntime,
    private val turnLauncher: NovelTurnLauncher,
) {

    data class GhostwriteChapterResult(
        val commitId: String?,
        val error: String? = null,
    )

    /** Foreground single-chapter ghostwrite: one unattended turn, auto-committed. */
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
    ): GhostwriteChapterResult {
        val store = NovelWorkspaceStore(projectDirectory)
        val commitIdBeforeTurn = NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.id
        val plan = store.read(NovelWorkspacePaths.branchPrefix(branchSlug) + "/plan/this-chapter.md")
            ?.let { NovelWorkspaceMarkdown.parseFile(it).body }
        // A wedged/trickling provider could otherwise hang a chapter turn for the
        // OkHttp read-timeout window or longer with no feedback anywhere; bound it
        // and surface as a chapter failure (retry/stop logic then applies).
        val turnHandle = turnLauncher.launch(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = projectDirectory,
                branchId = branchId,
                branchSlug = branchSlug,
                userText = "请写第 $chapterOrdinal 章。",
                systemPrompt = NovelWorkspacePrompts.ghostwriteChapter(chapterOrdinal, plan),
                settings = settings,
                model = model,
                maxSteps = maxSteps,
                autoApproveCanon = true,
                autoCommitMessage = "代笔收录",
                injection = injection,
                ownerJobId = ownerJobId,
                ownerExecutionId = ownerExecutionId,
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
            return GhostwriteChapterResult(commitId = null, error = "本章生成超时（${CHAPTER_TURN_TIMEOUT_MS / 60_000} 分钟无完成），已中止本轮")
        }
        val failure = terminal as? NovelWorkspaceRuntime.TurnEvent.Failed
        if (failure != null) return GhostwriteChapterResult(commitId = null, error = failure.message)
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
        val finalText = (terminal as? NovelWorkspaceRuntime.TurnEvent.Completed)
            ?.finalText
            ?.trim()
            .orEmpty()
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
                return GhostwriteChapterResult(commitId = null, error = error.message ?: "代笔收录失败")
            }
            return GhostwriteChapterResult(commitId = filed.id)
        }
        return GhostwriteChapterResult(commitId = null)
    }

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

    sealed interface BatchResult {
        data class Completed(val chaptersWritten: Int) : BatchResult
        data class Failed(val chaptersWritten: Int, val error: String) : BatchResult
        data class Stopped(val chaptersWritten: Int) : BatchResult
    }

    /**
     * Run a batch of chapters, committing each. Resumes from the ledger (startOrdinal is
     * the manuscript state when the job was created, so already-written chapters are skipped).
     */
    suspend fun runBatch(
        job: NovelWorkspaceGhostwriteJob,
        projectDirectory: File,
        branchId: String,
        settings: Settings,
        model: Model,
        isPaused: () -> Boolean,
        injection: NovelWorkspaceInjectionFlags? = null,
        onChapter: suspend (Int) -> Unit,
    ): BatchResult {
        val store = NovelWorkspaceStore(projectDirectory)
        var written = NovelWorkspaceGhostwriteJobs.progress(job, store)
        var noProgressStreak = 0
        try {
            while (written < job.targetChapterCount) {
                if (isPaused() || isCancelled(job, projectDirectory)) return BatchResult.Stopped(written)
                // D-D: never write new chapters while a middle-chapter edit is unresolved.
                if (NovelWorkspaceUnresolvedStore.entryFor(projectDirectory, job.branchSlug) != null) {
                    return BatchResult.Failed(written, "存在未解决的中间章修改，请先处理再代笔")
                }
                val before = NovelWorkspaceLedger.committedChapterOrdinals(
                    store,
                    NovelWorkspaceLedger.load(projectDirectory),
                    job.branchSlug,
                ).maxOrNull() ?: 0
                val nextOrdinal = before + 1
                var chapter = ghostwriteOneChapter(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = job.branchSlug,
                    settings = settings,
                    model = model,
                    chapterOrdinal = nextOrdinal,
                    injection = injection,
                    ownerJobId = job.id,
                    ownerExecutionId = job.executionKey,
                )
                // One retry absorbs transient provider blips (rate limit, dropped
                // connection); a second failure ends the batch with the error surfaced.
                if (chapter.error != null) {
                    if (isPaused() || isCancelled(job, projectDirectory)) return BatchResult.Stopped(written)
                    delay(CHAPTER_RETRY_DELAY_MS)
                    // Re-check after the backoff: pause during the wait must not
                    // still run a full turn (device-observed review finding).
                    if (isPaused() || isCancelled(job, projectDirectory)) return BatchResult.Stopped(written)
                    chapter = ghostwriteOneChapter(
                        projectDirectory = projectDirectory,
                        branchId = branchId,
                        branchSlug = job.branchSlug,
                        settings = settings,
                        model = model,
                        chapterOrdinal = nextOrdinal,
                        injection = injection,
                        ownerJobId = job.id,
                        ownerExecutionId = job.executionKey,
                    )
                }
                if (chapter.error != null) {
                    return BatchResult.Failed(written, chapter.error)
                }
                // No-progress guard: a turn that didn't add a chapter must not spin forever.
                val after = NovelWorkspaceLedger.committedChapterOrdinals(
                    store,
                    NovelWorkspaceLedger.load(projectDirectory),
                    job.branchSlug,
                ).maxOrNull() ?: 0
                if (after > before) {
                    noProgressStreak = 0
                } else {
                    noProgressStreak += 1
                    if (noProgressStreak >= MAX_NO_PROGRESS_TURNS) {
                        return BatchResult.Failed(
                            written,
                            "连续 $MAX_NO_PROGRESS_TURNS 轮未产出新章节，已停止避免空转；请检查模型是否按要求写正文",
                        )
                    }
                }
                written = NovelWorkspaceGhostwriteJobs.progress(job, store)
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

    /** Create a new batch job (cursor = current manuscript state). */
    fun newJob(projectDirectory: File, branchSlug: String, targetChapterCount: Int): NovelWorkspaceGhostwriteJob {
        val store = NovelWorkspaceStore(projectDirectory)
        val startOrdinal = NovelWorkspaceLedger.committedChapterOrdinals(
            store,
            NovelWorkspaceLedger.load(projectDirectory),
            branchSlug,
        ).maxOrNull() ?: 0
        val jobId = UUID.randomUUID().toString().uppercase()
        val job = NovelWorkspaceGhostwriteJob(
            id = jobId,
            executionId = jobId,
            branchSlug = branchSlug,
            targetChapterCount = targetChapterCount,
            startOrdinal = startOrdinal,
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        check(NovelWorkspaceGhostwriteJobs.saveIfNoActive(job, projectDirectory)) {
            "已有代笔批次占用当前分支，请先继续或取消该批次"
        }
        return job
    }

    fun saveJob(job: NovelWorkspaceGhostwriteJob, projectDirectory: File) {
        NovelWorkspaceGhostwriteJobs.save(job.copy(updatedAt = Instant.now()), projectDirectory)
    }

    companion object {
        /** Turns without a new chapter commit before the batch stops instead of spinning. */
        private const val MAX_NO_PROGRESS_TURNS = 2

        /** Backoff before the single retry of a failed chapter turn. */
        private const val CHAPTER_RETRY_DELAY_MS = 15_000L

        /** Hard bound for one chapter turn; hung provider connections must not wedge the batch. */
        private const val CHAPTER_TURN_TIMEOUT_MS = 8 * 60_000L

        /** Final answers at least this long are treated as the chapter itself (host-write path). */
        private const val MIN_HOSTWRITE_CHARS = 500
    }
}
