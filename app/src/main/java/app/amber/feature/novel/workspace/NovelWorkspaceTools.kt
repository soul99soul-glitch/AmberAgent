package app.amber.feature.novel.workspace

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The five workspace primitives (names/schemas mirror the shared iOS declarations).
 * One session per turn: free writes land immediately; canon writes collect into the
 * turn's proposal batch for the author gate.
 */
class NovelWorkspaceToolSession(
    private val store: NovelWorkspaceStore,
    private val branchSlug: String,
    private val projectTitle: String,
    private val batch: NovelWorkspaceWriteBatch,
    /** Read-only model rounds can inspect the workspace but every write is refused. */
    private val readOnly: Boolean = false,
    /**
     * Ghostwrite/unattended mode: canon writes land on disk immediately (the runtime
     * commits them at turn end) instead of buffering for an author approval card.
     */
    private val autoApproveCanon: Boolean = false,
    /** A bound write batch may read its confirmed plan, but only the host may rotate it. */
    private val confirmedPlanLocked: Boolean = false,
    /**
     * Polish mode: the one EXISTING chapter this unattended turn may rewrite
     * (host-locked path). Non-null switches the unattended chapter guard from
     * ghostwrite semantics (manuscript max + 1) to polish semantics (exactly this
     * file; any other protected write — other chapters, plot/ — is refused, because
     * polish must not move the story; the host owns the plot-pointer commit).
     */
    private val polishTargetPath: String? = null,
) {

    /** Locked by the first chapter write of a ghostwrite turn (see [ghostwriteChapterRefusal]). */
    private var ghostwriteTargetPath: String? = null

    fun tools(): List<Tool> = listOf(listTool(), readTool(), grepTool(), statusTool(), writeTool())

    private fun listTool() = Tool(
        name = "novel_workspace_list",
        description = """
            List files in the novel workspace tree (chapters, setting, plot, plan, inbox, drafts).
            Optional `prefix` limits to a subdirectory such as setting/characters or branches.
            This is read-only and does not change the project.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prefix", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional subdirectory prefix such as setting/characters")
                    })
                },
            )
        },
        execute = { input ->
            val prefix = input.str("prefix")
            val paths = store.list(prefix).take(MAX_LIST_ENTRIES)
            textResult(if (paths.isEmpty()) "(empty)" else paths.joinToString("\n"))
        },
    )

    private fun readTool() = Tool(
        name = "novel_workspace_read",
        description = """
            Read one workspace file by path from novel_workspace_list.
            Use this instead of inventing new novel_* verbs when you need the file contents.
            This is read-only and does not change the project.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Workspace-relative path from novel_workspace_list")
                    })
                },
                required = listOf("path"),
            )
        },
        execute = { input ->
            val path = requirePath(input)
            val content = store.read(path)
            textResult(content ?: "(missing file: $path)")
        },
    )

    private fun grepTool() = Tool(
        name = "novel_workspace_grep",
        description = """
            Search workspace files for a literal or simple substring `query`.
            Optional `prefix` limits the search. Returns matching paths and a short excerpt.
            This is read-only and does not change the project.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Substring to search for")
                    })
                    put("prefix", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional subdirectory prefix")
                    })
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            val query = input.str("query").orEmpty()
            if (query.isEmpty()) return@Tool textResult("(empty query)")
            val prefix = input.str("prefix")
            val matches = StringBuilder()
            var count = 0
            for (path in store.list(prefix)) {
                val content = store.read(path) ?: continue
                for ((index, line) in content.lineSequence().withIndex()) {
                    // Case-insensitive like the iOS tool.
                    if (!line.contains(query, ignoreCase = true)) continue
                    matches.append(path).append(':').append(index + 1).append(": ")
                        .append(line.trim().take(EXCERPT_CHARS)).append('\n')
                    count += 1
                    if (count >= MAX_GREP_MATCHES) {
                        matches.append("(truncated)\n")
                        return@Tool textResult(matches.toString())
                    }
                }
            }
            textResult(if (count == 0) "(no matches)" else matches.toString())
        },
    )

    private fun statusTool() = Tool(
        name = "novel_workspace_status",
        description = """
            Show workspace status: project name, branch, working chapter count,
            and whether the ledger sees dirty or unresolved paths. This is read-only.
        """.trimIndent(),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val ledger = NovelWorkspaceLedger.load(store.rootDirectory)
            val branchPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/"
            val chapters = store.list(branchPrefix + "chapters")
            // Head 按本分支解析（与 Worker 的 headOf(branchId(...)) 同款）：全局 head 只镜像
            // 分叉前被镜像的分支，createBranch 之后全局 head 钉在 fork commit 上——若用全局
            // head，源分支此后的合法提交会被 status 全部当成脏文件。
            val branchId = NovelWorkspaceLedger.branchId(store, ledger, branchSlug)
            val status = NovelWorkspaceLedger.status(
                head = branchId?.let { ledger.headOf(it) },
                working = store.fileTree(),
                plotStale = false,
                unresolved = false,
            )
            textResult(
                buildString {
                    appendLine("project: $projectTitle")
                    appendLine("branch: $branchSlug")
                    appendLine("working chapters: ${chapters.size}")
                    appendLine("head: ${status.headID ?: "(no commits)"} ${status.message.orEmpty()}")
                    if (status.dirtyPaths.isNotEmpty()) {
                        appendLine("dirty: ${status.dirtyPaths.joinToString(", ")}")
                    }
                }.trimEnd(),
            )
        },
    )

    private fun writeTool() = Tool(
        name = "novel_workspace_write",
        description = """
            Write one workspace file by path. Setting, plan, inbox, and drafts save directly.
            Writing an already collected chapter or plot/ file registers an approval card first;
            nothing reaches the manuscript until the author confirms.
            `path` is a workspace path from novel_workspace_list. `content` is the new file body
            (front matter optional; the host keeps identity from the path).
            After submitting a chapter or plot write, wrap up the turn.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Workspace-relative path to write")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "New file body")
                    })
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional short note shown on the approval card")
                    })
                },
                required = listOf("path", "content"),
            )
        },
        // Self-gated, deliberately NOT needsApproval=true: the generic permission pipeline
        // would pause the loop at WaitingUser before execute() runs, which breaks the novel
        // runtime (it drives its own author gate). The approval boundary lives inside
        // execute(): chapters/plot writes are buffered as proposals and only reach disk via
        // NovelWorkspaceRuntime.approve(); free paths are a fixed whitelist; host files refuse.
        needsApproval = false,
        execute = { input ->
            val path = requirePath(input)
            val content = input.str("content") ?: ""
            val reason = input.str("reason")
            android.util.Log.i(
                "NovelWorkspace",
                "write tool: path=$path contentLen=${content.length} keys=${input.toString().take(120)}",
            )
            if (readOnly) {
                return@Tool textResult("当前轮次为只读生成，不能修改工作区；请在最终回答中返回完整内容。")
            }
            when {
                isOtherBranchPath(path) -> {
                    textResult("无法写入其他分支：$path。当前轮次只能修改 branches/$branchSlug/。")
                }
                NovelWorkspacePaths.isProtectedPath(path) -> {
                    if (autoApproveCanon) {
                        // Unattended canon writes are restricted to this turn's one
                        // locked target chapter: touching anything older (or a second
                        // new chapter) lands in the same commit and trips the D-D
                        // unresolved gate, killing the batch one turn later.
                        chapterWriteRefusal(path)?.let { return@Tool textResult(it) }
                        // Polish replaces the body only: auto-approved writes land raw,
                        // so the host re-renders the EXISTING chapter front matter
                        // around the new body — chapter identity (id/ordinal/title)
                        // is host-owned and must survive a polish batch.
                        val contentToBuffer = if (path == polishTargetPath) {
                            polishedContent(path, content)
                        } else {
                            content
                        }
                        // Buffer until the runtime's final owner-token check. An
                        // obsolete Worker must never write into a resumed execution.
                        batch.add(NovelWorkspaceWriteEntry(path, contentToBuffer, reason))
                        textResult("已暂存 $path（本轮完成后自动收录）。")
                    } else {
                        batch.add(NovelWorkspaceWriteEntry(path, content, reason))
                        textResult(
                            "已登记修改提案（$path），等待作者确认后才会写入正文。" +
                                "本轮不要再次写同一文件，可以收尾。",
                        )
                    }
                }
                NovelWorkspacePaths.isFreeWritePath(path) -> {
                    if (autoApproveCanon && confirmedPlanLocked && path == confirmedPlanPath()) {
                        textResult("本章计划已由当前代笔批次确认，完成前不能由模型改写。")
                    } else if (autoApproveCanon) {
                        // The same execution-token rule applies to plan/setting/draft
                        // writes made by an unattended provider.
                        batch.add(NovelWorkspaceWriteEntry(path, content, reason))
                        textResult("已暂存 $path（本轮完成后自动收录）。")
                    } else {
                        store.write(path, content)
                        batch.noteFreeWrite()
                        textResult("已保存 $path")
                    }
                }
                else -> {
                    android.util.Log.i("NovelWorkspace", "write tool: REJECTED path=$path")
                    textResult(
                        "无法写入 $path（路径前缀不被接受，注意目录名全小写）。" +
                            "设定请写入 setting/<分组>/<文件>.md（如 setting/characters/主角.md）；" +
                            "灵感写 inbox/<文件>.md；草稿写 drafts/<文件>.md；章节大纲写 branches/主线/plan/。" +
                            "manifest.yaml、project.md、branch.md 由宿主管理，不可直接写。请换用正确前缀重试。",
                    )
                }
            }
        },
    )

    private fun requirePath(input: JsonElement): String {
        val path = input.str("path")
        require(!path.isNullOrEmpty()) { "novel tool requires a non-empty path" }
        return path
    }

    private fun isOtherBranchPath(path: String): Boolean {
        val segments = path.split('/')
        return segments.size >= 2 && segments[0] == NovelWorkspacePaths.BRANCHES_DIR && segments[1] != branchSlug
    }

    /**
     * Ghostwrite-only guard for chapter files (plot stays free — D-C wants plot
     * updated in the same turn). One unattended turn writes exactly one chapter:
     * the first allowed write locks the target path (manuscript max + 1), and
     * later writes may only rewrite that exact file — same ordinal under a
     * different filename is refused too (duplicate numbering, review finding).
     * Anything else is refused with the path to use instead — without this,
     * touching any older chapter (or sneaking a second new one) lands in the
     * same commit and trips the D-D unresolved gate, killing the batch later.
     */
    private fun ghostwriteChapterRefusal(path: String): String? {
        val segments = path.split('/')
        if (segments.size < 4 || segments[0] != "branches" || segments[2] != "chapters") return null
        val newest = NovelWorkspaceLedger.workingChapterOrdinals(store, branchSlug).maxOrNull() ?: 0
        val ordinal = NovelWorkspacePaths.chapterOrdinalFromPath(path)
        val lockedPath = ghostwriteTargetPath
        if (lockedPath != null) {
            return if (path == lockedPath) null else refusalMessage(lockedPath)
        }
        if (segments[1] == branchSlug && ordinal == newest + 1) {
            ghostwriteTargetPath = path
            return null
        }
        return refusalMessage(
            NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters/" +
                NovelWorkspacePaths.chapterFileName(newest + 1, "标题"),
        )
    }

    /**
     * Unattended canon guard dispatcher: polish turns may write ONLY their host-locked
     * existing chapter; ghostwrite turns keep the one-new-chapter rule.
     */
    private fun chapterWriteRefusal(path: String): String? {
        val target = polishTargetPath ?: return ghostwriteChapterRefusal(path)
        if (path == target) return null
        return "润色轮只重写这一个章节文件：$target。" +
            "请把该章润色后的完整正文写回这个路径；不要修改其他章节、剧情或设定文件，写完请收尾本轮。"
    }

    /** Existing chapter front matter re-rendered around the polished body (bare body in → identity kept). */
    private fun polishedContent(path: String, content: String): String {
        val newBody = NovelWorkspaceMarkdown.parseFile(content).body
        val existing = store.read(path) ?: return newBody
        if (!existing.trim().startsWith("---")) return newBody
        val parsed = NovelWorkspaceMarkdown.parseFile(existing)
        return NovelWorkspaceMarkdown.render(
            fields = parsed.fields.toList(),
            aliases = parsed.lists["aliases"].orEmpty(),
            body = newBody,
        )
    }

    private fun refusalMessage(expected: String): String =
        "代笔轮只写一个章节文件，请把正文写入 $expected（标题可自定，已写过则原路径精修）。" +
            "不要修改更早的章节、不要新写其他章——写完本章请收尾，下一轮会自动写下一章。"

    private fun JsonElement.str(key: String): String? = runCatching {
        jsonObject[key]?.jsonPrimitive?.content
    }.getOrNull()

    private fun textResult(text: String): List<UIMessagePart> = listOf(UIMessagePart.Text(text))

    companion object {
        private const val MAX_LIST_ENTRIES = 400
        private const val MAX_GREP_MATCHES = 40
        private const val EXCERPT_CHARS = 120
    }

    private fun confirmedPlanPath(): String =
        NovelWorkspacePaths.branchPrefix(branchSlug) + "/plan/this-chapter.md"
}

/** Buffered owner-gated writes of one turn; the runtime applies them as a single commit. */
class NovelWorkspaceWriteBatch {
    private val entries = mutableListOf<NovelWorkspaceWriteEntry>()
    private var freeWrites = 0

    /**
     * Pre-turn content of auto-approved writes (null = file did not exist).
     * A failed turn restores these: an uncommitted orphan chapter would later join
     * a fresh chapter in one commit and trip the D-D unresolved gate.
     */
    private val previousContents = mutableMapOf<String, String?>()

    fun add(entry: NovelWorkspaceWriteEntry) {
        // Last write to the same path within a turn wins.
        entries.removeAll { it.path == entry.path }
        entries.add(entry)
    }

    fun rememberPrevious(path: String, content: String?) {
        previousContents.putIfAbsent(path, content)
    }

    fun previousSnapshot(): Map<String, String?> = previousContents.toMap()

    fun snapshot(): List<NovelWorkspaceWriteEntry> = entries.toList()

    fun isEmpty(): Boolean = entries.isEmpty()

    /** Free writes (setting/inbox/drafts) land on disk immediately; tracked so the
     *  runtime can still commit them at turn end instead of drifting from the ledger. */
    fun noteFreeWrite() {
        freeWrites += 1
    }

    fun hasFreeWrites(): Boolean = freeWrites > 0
}

data class NovelWorkspaceWriteEntry(
    val path: String,
    val content: String,
    val reason: String?,
)
