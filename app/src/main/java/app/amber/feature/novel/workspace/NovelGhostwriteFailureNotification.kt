package app.amber.feature.novel.workspace

/**
 * Pure copy/decision logic for the ghostwrite batch failure notification. The worker
 * calls this when a transition lands the job in the terminal failed state; keeping it
 * free of Android types makes the wording unit-testable on the JVM.
 */
internal object NovelGhostwriteFailureNotification {

    data class Content(
        val title: String,
        val text: String,
    )

    /** Localized copy supplied by the Worker, which owns the Android Context. */
    data class Templates(
        val fallbackTitle: String,
        val unknownReason: String,
        val chapterFailure: (chapterOrdinal: Int, reason: String) -> String,
        val taskFailure: (taskLabel: String, reason: String) -> String,
    )

    /**
     * The chapter the failed batch was attempting, or null when unknown.
     *
     * - Write：失败的一轮不会留下 commit，正文最新已提交章 + 1 就是刚失败的那章。
     * - Polish：范围固定，正在尝试的章 = startOrdinal + 账本推导的 progress；但
     *   「指针提交失败」窗口（[NovelWorkspaceGhostwriteCoordinator.REASON_POLISH_POINTER_COMMIT_FAILED]）
     *   例外 —— 该章的润色 commit 已经落地、progress 已把它计入，缺的只是配对的
     *   剧情指针 commit，因此通知必须回退一格指向刚润色的那章。
     */
    fun failedChapterOrdinal(
        polishMode: Boolean,
        startOrdinal: Int,
        ledgerProgress: Int,
        newestCommittedOrdinal: Int?,
        reason: String?,
    ): Int? = if (polishMode) {
        val attempted = startOrdinal + ledgerProgress
        if (reason != null &&
            reason.startsWith(NovelWorkspaceGhostwriteCoordinator.REASON_POLISH_POINTER_COMMIT_FAILED)
        ) {
            (attempted - 1).coerceAtLeast(startOrdinal)
        } else {
            attempted
        }
    } else {
        newestCommittedOrdinal?.plus(1)
    }

    @Deprecated(
        message = "Production callers must pass localized notification templates",
        level = DeprecationLevel.WARNING,
    )
    fun content(
        bookTitle: String?,
        chapterOrdinal: Int?,
        reason: String?,
        /** Task label shown in copy: 「代笔」 for write batches, 「润色」 for polish. */
        taskLabel: String = "代笔",
    ): Content = content(
        bookTitle = bookTitle,
        chapterOrdinal = chapterOrdinal,
        reason = reason,
        taskLabel = taskLabel,
        templates = legacyTemplates(taskLabel),
    )

    fun content(
        bookTitle: String?,
        chapterOrdinal: Int?,
        reason: String?,
        /** Task label is already localized by the Worker. */
        taskLabel: String,
        templates: Templates,
    ): Content {
        val title = bookTitle?.trim()?.takeIf { it.isNotEmpty() } ?: templates.fallbackTitle
        val reasonLine = reason
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.compact()
            ?: templates.unknownReason
        val text = if (chapterOrdinal != null) {
            templates.chapterFailure(chapterOrdinal, reasonLine)
        } else {
            templates.taskFailure(taskLabel, reasonLine)
        }
        return Content(title = title, text = text)
    }

    /** Compatibility copy for the pre-localization JVM tests; production uses [Templates]. */
    private fun legacyTemplates(taskLabel: String): Templates = Templates(
        fallbackTitle = "小说$taskLabel",
        unknownReason = "原因未知",
        chapterFailure = { ordinal, reason -> "第 $ordinal 章失败：$reason" },
        taskFailure = { label, reason -> "${label}失败：$reason" },
    )

    /** Bound the reason so a stack-trace-sized error cannot flood the shade. */
    private fun String.compact(): String =
        if (length <= MAX_REASON_CHARS) this else take(MAX_REASON_CHARS - 1).trimEnd() + "…"

    private const val MAX_REASON_CHARS = 80

    private val WHITESPACE_REGEX = Regex("\\s+")
}
