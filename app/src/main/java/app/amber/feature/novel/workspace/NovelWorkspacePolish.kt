package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceEffectiveMaterials
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.sha256Hex
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Batch polish changes canon only after a separate, original-bound factual review. */
internal class NovelWorkspacePolisher(
    private val runtime: NovelWorkspaceRuntime,
    private val turnLauncher: NovelTurnLauncher,
) {
    private data class Original(
        val path: String,
        val content: String,
        val headId: String?,
        val treeDigest: String,
    ) {
        val body: String get() = NovelWorkspaceMarkdown.parseFile(content).body
    }

    @Serializable
    private data class FactReview(
        val originalSHA256: String,
        val candidateSHA256: String,
        val factsUnchanged: Boolean,
        val issues: List<String>,
    )

    suspend fun runChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        settings: Settings,
        model: Model,
        reviewModel: Model,
        chapterOrdinal: Int,
        maxSteps: Int,
        injection: NovelWorkspaceInjectionFlags?,
        ownerJobId: String?,
        ownerExecutionId: String?,
        locale: Locale,
        fallbackErrorMessage: String,
    ): NovelWorkspaceGhostwriteCoordinator.GhostwriteChapterResult {
        val chinese = locale.language.equals("zh", ignoreCase = true)
        fun failure(zh: String, en: String, retryable: Boolean = false) =
            NovelWorkspaceGhostwriteCoordinator.GhostwriteChapterResult(
                commitId = null,
                error = if (chinese) zh else en,
                retryable = retryable,
            )
        val store = NovelWorkspaceStore(projectDirectory)
        val snapshot = {
            val prefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters"
            store.list(prefix).firstOrNull {
                NovelWorkspacePaths.chapterOrdinalFromPath(it) == chapterOrdinal
            }?.let { path ->
                store.read(path)?.let { content ->
                    Original(
                        path,
                        content,
                        NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.id,
                        NovelWorkspaceLedger.treeSHA256(store.fileTree()),
                    )
                }
            }
        }
        val original = if (ownerJobId == null) {
            NovelWorkspaceGhostwriteJobs.withNoActiveBranch(projectDirectory, branchSlug, snapshot)
        } else {
            NovelWorkspaceGhostwriteJobs.withRunningOwner(
                projectDirectory, ownerJobId, checkNotNull(ownerExecutionId), snapshot,
            )
        } ?: return failure("章节不存在或润色已暂停、取消", "The chapter is missing, or polishing was paused or cancelled.")
        fun stage(value: NovelWorkspaceGhostwriteStage): Boolean {
            if (ownerJobId == null) return true
            return NovelWorkspaceGhostwriteJobs.withRunningOwner(projectDirectory, ownerJobId, checkNotNull(ownerExecutionId)) {
                val current = checkNotNull(NovelWorkspaceGhostwriteJobs.load(projectDirectory, ownerJobId))
                NovelWorkspaceGhostwriteJobs.save(current.copy(stage = value, currentChapterOrdinal = chapterOrdinal, updatedAt = Instant.now()), projectDirectory)
                true
            } ?: false
        }
        suspend fun turn(systemPrompt: String, userText: String, turnModel: Model): NovelWorkspaceRuntime.TurnEvent {
            val handle = turnLauncher.launch(
                NovelWorkspaceRuntime.TurnRequest(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = branchSlug,
                    userText = userText,
                    systemPrompt = systemPrompt,
                    settings = settings,
                    model = turnModel,
                    maxSteps = maxSteps,
                    readOnlyTools = true,
                    injection = injection,
                    ownerJobId = ownerJobId,
                    ownerExecutionId = ownerExecutionId,
                    polishChapterPath = original.path,
                    fallbackErrorMessage = fallbackErrorMessage,
                    locale = locale,
                ),
                runtime,
            )
            return try {
                withTimeout(TURN_TIMEOUT_MS) {
                    handle.events.first {
                        it is NovelWorkspaceRuntime.TurnEvent.Completed || it is NovelWorkspaceRuntime.TurnEvent.Failed
                    }
                }
            } catch (error: TimeoutCancellationException) {
                runCatching { handle.awaitTerminal() }
                NovelWorkspaceRuntime.TurnEvent.Failed(if (chinese) "润色或审核超时，正文未改动" else "Polish or review timed out; the manuscript was not changed.")
            }
        }
        if (!stage(NovelWorkspaceGhostwriteStage.Writing)) return failure("润色已暂停或取消", "Polishing was paused or cancelled.")
        val generated = turn(
            NovelWorkspacePrompts.polishChapter(
                chapterOrdinal, original.path, original.body,
                NovelWorkspaceEffectiveMaterials.writingPreferenceForPrompt(store, branchSlug),
                locale = locale,
                readOnlyCandidate = true,
            ),
            if (chinese) "请润色第 $chapterOrdinal 章。" else "Polish chapter $chapterOrdinal.",
            model,
        )
        if (generated is NovelWorkspaceRuntime.TurnEvent.Failed) {
            return failure(generated.message, generated.message, retryable = true)
        }
        val candidate = (generated as NovelWorkspaceRuntime.TurnEvent.Completed).finalText.trim()
        if (candidate.isBlank()) return NovelWorkspaceGhostwriteCoordinator.GhostwriteChapterResult(commitId = null)
        val originalHash = sha256Hex(original.body)
        val candidateHash = sha256Hex(candidate)
        if (!stage(NovelWorkspaceGhostwriteStage.Reviewing)) return failure("润色已暂停或取消", "Polishing was paused or cancelled.")
        val reviewed = turn(
            NovelWorkspacePrompts.polishFactReview(original.body, candidate, originalHash, candidateHash, locale),
            if (chinese) "请独立审核第 $chapterOrdinal 章的润色候选，只返回 JSON。" else "Independently review the polish candidate for chapter $chapterOrdinal; return JSON only.",
            reviewModel,
        )
        if (reviewed is NovelWorkspaceRuntime.TurnEvent.Failed) {
            return failure(reviewed.message, reviewed.message, retryable = true)
        }
        val review = runCatching {
            reviewJson.decodeFromString(FactReview.serializer(), (reviewed as NovelWorkspaceRuntime.TurnEvent.Completed).finalText.trim())
        }.getOrNull() ?: return failure("润色审核未返回有效结果，正文未改动", "The polish review did not return a valid result; the manuscript was not changed.")
        if (review.originalSHA256 != originalHash || review.candidateSHA256 != candidateHash) {
            return failure("润色审核版本不匹配，正文未改动", "The polish review does not match the original and candidate; the manuscript was not changed.")
        }
        if (!review.factsUnchanged || review.issues.isNotEmpty()) {
            val details = review.issues.joinToString("；")
            return failure("润色改变了故事事实，正文未改动${if (details.isEmpty()) "" else "：$details"}", "The polish changed story facts; the manuscript was not changed. $details")
        }
        if (!stage(NovelWorkspaceGhostwriteStage.Committing)) return failure("润色已暂停或取消", "Polishing was paused or cancelled.")
        val commit = try {
            runtime.commitPolishedChapter(
                projectDirectory, branchId, branchSlug, original.path, candidate,
                ownerJobId = ownerJobId,
                ownerExecutionId = ownerExecutionId,
                expectedHeadId = original.headId,
                expectedTreeDigest = original.treeDigest,
                expectedChapterContent = original.content,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return failure(error.message ?: fallbackErrorMessage, error.message ?: fallbackErrorMessage)
        }
        return NovelWorkspaceGhostwriteCoordinator.GhostwriteChapterResult(commitId = commit.id)
    }

    companion object {
        private const val TURN_TIMEOUT_MS = 8 * 60_000L
        private val reviewJson = Json { ignoreUnknownKeys = false }
    }
}
