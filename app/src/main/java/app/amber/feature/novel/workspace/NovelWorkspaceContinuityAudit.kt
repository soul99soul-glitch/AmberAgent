package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Author-triggered whole-branch review. Every chapter is sent in full; canon is never written. */
class NovelWorkspaceContinuityAudit(
    private val runtime: NovelWorkspaceRuntime,
    private val turnLauncher: NovelTurnLauncher,
) {
    data class Request(
        val projectDirectory: File,
        val branchId: String,
        val branchSlug: String,
        val settings: Settings,
        val model: Model,
        val injection: NovelWorkspaceInjectionFlags? = null,
        val locale: Locale = Locale.CHINESE,
        val restoreEpoch: Long = NovelWorkspaceRestoreBoundary.currentEpoch(),
    )

    data class Result(
        val report: String,
        val checked: Int,
        val total: Int,
        val complete: Boolean,
        val error: String? = null,
    )

    private data class Chapter(val path: String, val ordinal: Int, val title: String, val body: String)
    private data class Source(val headId: String?, val treeDigest: String, val chapters: List<Chapter>)
    private data class SourcedFact(val chapter: Chapter, val fact: Fact)

    @Serializable
    private data class Fact(val text: String, val evidence: String)

    @Serializable
    private data class Issue(
        val summary: String,
        val evidence: String,
        val suggestion: String,
        val relatedPath: String? = null,
        val relatedEvidence: String? = null,
    )

    @Serializable
    private data class ChapterReview(val chapterPath: String, val issues: List<Issue>, val facts: List<Fact>)

    suspend fun run(request: Request, onProgress: (Result) -> Unit = {}): Result {
        val chinese = request.locale.language.equals("zh", ignoreCase = true)
        fun text(zh: String, en: String) = if (chinese) zh else en
        val sections = mutableListOf<String>()
        var checked = 0
        var total = 0
        fun result(complete: Boolean, error: String? = null, stopped: Boolean = false): Result {
            val status = when {
                complete -> text("检查完成", "Review complete")
                stopped -> text("已停止，检查未完成", "Stopped; review incomplete")
                error != null -> text("检查未完成", "Review incomplete")
                else -> text("正在检查，尚未完成", "Review in progress; not complete")
            }
            return Result(buildString {
                appendLine(text("全书一致性审稿 · ${request.branchSlug}", "Whole-book continuity review · ${request.branchSlug}"))
                appendLine("$status · ${text("已检查 $checked / $total 章", "$checked / $total chapters checked")}")
                appendLine(text("以下是需要作者核实的审稿结论，正文未改动。", "These review findings need author verification. The manuscript was not changed."))
                if (error != null) appendLine(error)
                if (sections.isNotEmpty()) append("\n" + sections.joinToString("\n\n"))
            }.trimEnd(), checked, total, complete, error)
        }
        try {
            val store = NovelWorkspaceStore(request.projectDirectory)
            val source = withContext(Dispatchers.IO) { NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) {
                NovelWorkspaceGhostwriteJobs.withNoActiveBranch(request.projectDirectory, request.branchSlug) {
                    val ledger = NovelWorkspaceLedger.load(request.projectDirectory)
                    check(NovelWorkspaceLedger.branchId(store, ledger, request.branchSlug) == request.branchId) {
                        text("当前分支版本绑定已变化，请重新打开后检查", "The branch binding changed. Reopen it before reviewing.")
                    }
                    val prefix = NovelWorkspacePaths.branchPrefix(request.branchSlug) + "/chapters"
                    val chapters = store.list(prefix).filter { it.endsWith(".md") }.mapIndexed { index, path ->
                        val parsed = NovelWorkspaceMarkdown.parseFile(checkNotNull(store.read(path)))
                        Chapter(
                            path,
                            parsed.fields["ordinal"]?.toIntOrNull() ?: NovelWorkspacePaths.chapterOrdinalFromPath(path) ?: index + 1,
                            parsed.fields["title"] ?: NovelWorkspacePaths.fileNameTitle(path),
                            parsed.body,
                        )
                    }.sortedWith(compareBy<Chapter> { it.ordinal }.thenBy { it.path })
                    Source(ledger.headOf(request.branchId)?.id, NovelWorkspaceLedger.treeSHA256(store.fileTree()), chapters)
                } ?: error(text("当前分支仍有创作任务，请结束或取消任务后检查", "This branch has an active writing job. Finish or cancel it before reviewing."))
            } }
            total = source.chapters.size
            if (total == 0) return result(false, text("当前分支暂无正文章节", "This branch has no manuscript chapters."))
            onProgress(result(false))
            val facts = mutableListOf<SourcedFact>()
            for (chapter in source.chapters) {
                withContext(Dispatchers.IO) { NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) {
                    check(NovelWorkspaceLedger.load(request.projectDirectory).headOf(request.branchId)?.id == source.headId) {
                        text("检查期间正文版本发生变化，已有报告基于原稿；请重新检查", "The manuscript version changed during review. Existing findings refer to the original version; review again.")
                    }
                } }
                val handle = turnLauncher.launch(
                    NovelWorkspaceRuntime.TurnRequest(
                        projectDirectory = request.projectDirectory,
                        branchId = request.branchId,
                        branchSlug = request.branchSlug,
                        userText = text("审核第 ${chapter.ordinal} 章，返回严格 JSON。", "Review chapter ${chapter.ordinal}; return strict JSON."),
                        systemPrompt = prompt(chapter, facts, chinese),
                        settings = request.settings,
                        model = request.model,
                        readOnlyTools = true,
                        injection = request.injection,
                        locale = request.locale,
                        restoreEpoch = request.restoreEpoch,
                        fallbackErrorMessage = text("审稿失败", "Review failed"),
                    ),
                    runtime,
                )
                val terminal = try {
                    withTimeout(TURN_TIMEOUT_MS) {
                        handle.events.first { it is NovelWorkspaceRuntime.TurnEvent.Completed || it is NovelWorkspaceRuntime.TurnEvent.Failed }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    runCatching { handle.awaitTerminal() }
                    return result(false, text("第 ${chapter.ordinal} 章审稿超时，后续章节未检查", "Review of chapter ${chapter.ordinal} timed out; later chapters were not checked."))
                }
                if (terminal is NovelWorkspaceRuntime.TurnEvent.Failed) return result(false, terminal.message)
                val review = runCatching {
                    reviewJson.decodeFromString(ChapterReview.serializer(), (terminal as NovelWorkspaceRuntime.TurnEvent.Completed).finalText.trim())
                }.getOrNull() ?: return result(false, text("第 ${chapter.ordinal} 章未返回有效审稿结果", "Chapter ${chapter.ordinal} did not return a valid review."))
                val previous = source.chapters.take(checked).associateBy { it.path }
                val valid = review.chapterPath == chapter.path && review.facts.all {
                    it.text.isNotBlank() && it.evidence.isNotBlank() && chapter.body.contains(it.evidence)
                } && review.issues.all {
                    it.summary.isNotBlank() && it.suggestion.isNotBlank() && it.evidence.isNotBlank() && chapter.body.contains(it.evidence) &&
                        (if (it.relatedPath == null) it.relatedEvidence == null else {
                            val related = previous[it.relatedPath]
                            related != null && !it.relatedEvidence.isNullOrBlank() && related.body.contains(it.relatedEvidence)
                        })
                }
                if (!valid) return result(false, text("第 ${chapter.ordinal} 章报告引用与原稿不符，未计入已检查章节", "The evidence in chapter ${chapter.ordinal}'s report does not match the original; it was not counted as checked."))
                sections.add(render(chapter, review, previous, chinese))
                facts.addAll(review.facts.map { SourcedFact(chapter, it) })
                checked += 1
                onProgress(result(false))
            }
            val unchanged = withContext(Dispatchers.IO) { NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) {
                NovelWorkspaceLedger.load(request.projectDirectory).headOf(request.branchId)?.id == source.headId &&
                    NovelWorkspaceLedger.treeSHA256(store.fileTree()) == source.treeDigest
            } }
            return if (unchanged) result(true) else result(false, text("检查期间正文或资料发生变化，以上结果基于开始时的原稿；请重新检查", "The manuscript or materials changed during review. These findings refer to the starting version; review again."))
        } catch (cancelled: CancellationException) {
            if (NovelWorkspaceRestoreBoundary.isCurrent(request.restoreEpoch)) {
                onProgress(result(false, text("保留已完成章节的报告，后续章节未检查", "Completed chapter reports were retained; later chapters were not checked."), stopped = true))
            }
            throw cancelled
        } catch (error: Exception) {
            return result(false, error.message ?: text("审稿失败", "Review failed"))
        }
    }

    private fun prompt(chapter: Chapter, facts: List<SourcedFact>, chinese: Boolean): String = buildString {
        appendLine(if (chinese) {
            "你正在逐章进行全书只读一致性审稿。本章完整正文是检查对象，之前各章事实笔记带原文出处。检查事实、因果、时间线、人物状态、关系和信息揭露的可能矛盾。正常状态变化、回忆、伏笔、叙述者偏见或后续解释不能直接认定矛盾；只报告有正文原句依据的问题，并给作者核实建议。当前剧情状态可能来自后文或落后于正文，不要强迫早期正文匹配当前状态。工具只读，不修复、不改任何文件。"
        } else {
            "Perform a read-only whole-book continuity review, one complete chapter at a time. Earlier facts include original citations. Check possible conflicts in facts, causality, timeline, character states, relationships, and revelations. Normal state changes, flashbacks, foreshadowing, narrator bias, or later explanations are not automatically contradictions. Report only issues supported by exact prose quotes and suggest author verification. Current plot state may refer to later events or lag behind the manuscript; do not force earlier prose to match it. Tools are read-only. Do not repair or change files."
        })
        appendLine("CHAPTER_PATH: ${chapter.path}")
        appendLine("CHAPTER_ORDINAL: ${chapter.ordinal}")
        appendLine("Return strict JSON only, without code fences:")
        appendLine("{\"chapterPath\":\"<current path>\",\"issues\":[{\"summary\":\"possible conflict\",\"evidence\":\"exact current-chapter quote\",\"relatedPath\":\"earlier chapter path\",\"relatedEvidence\":\"exact earlier quote\",\"suggestion\":\"what the author should verify\"}],\"facts\":[{\"text\":\"concise story fact or state transition\",\"evidence\":\"exact current-chapter quote\"}]}")
        appendLine("Use empty issues when no evidenced conflict was found. relatedPath and relatedEvidence may both be omitted for an internal conflict. Facts must come only from this chapter and quote it exactly; retain concise important states, chronology, outcomes, relationships, and unresolved promises for later comparison. Do not invent evidence.")
        appendLine("## Earlier chapter facts")
        for (fact in facts) appendLine("[${fact.chapter.ordinal}; ${fact.chapter.path}] ${fact.fact.text}\nQuote: ${fact.fact.evidence}")
        appendLine("## Complete current chapter: ${chapter.title}")
        append(chapter.body)
    }

    private fun render(chapter: Chapter, review: ChapterReview, previous: Map<String, Chapter>, chinese: Boolean): String = buildString {
        appendLine(if (chinese) "第 ${chapter.ordinal} 章 · ${chapter.title}" else "Chapter ${chapter.ordinal} · ${chapter.title}")
        appendLine(chapter.path)
        if (review.issues.isEmpty()) append(if (chinese) "本章未发现有原句依据的矛盾。" else "No contradiction supported by prose quotes was found in this chapter.")
        review.issues.forEachIndexed { index, issue ->
            appendLine("${index + 1}. ${issue.summary}")
            appendLine(if (chinese) "本章原句：${issue.evidence}" else "Current quote: ${issue.evidence}")
            issue.relatedPath?.let { path ->
                appendLine(if (chinese) "关联第 ${previous.getValue(path).ordinal} 章 · $path" else "Related chapter ${previous.getValue(path).ordinal} · $path")
                appendLine(if (chinese) "关联原句：${issue.relatedEvidence}" else "Related quote: ${issue.relatedEvidence}")
            }
            appendLine(if (chinese) "建议：${issue.suggestion}" else "Suggestion: ${issue.suggestion}")
        }
    }.trimEnd()

    companion object {
        private const val TURN_TIMEOUT_MS = 8 * 60_000L
        private val reviewJson = Json { ignoreUnknownKeys = false }
    }
}
