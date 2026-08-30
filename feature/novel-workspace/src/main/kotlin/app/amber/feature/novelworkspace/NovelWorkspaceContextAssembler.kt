package app.amber.feature.novelworkspace

import java.util.Locale

/**
 * Constraint brief builder — the context-engineering core.
 *
 * Instead of dumping the whole book into the prompt, the host assembles a compact
 * brief per turn: current plot state, open foreshadowing, pinned decisions, and the
 * subgraph around the entities named in this chapter's plan (neighborhood retrieval,
 * the novel analogue of a code dependency graph driving targeted context loading).
 */
object NovelWorkspaceContextAssembler {

    private const val NODE_EXCERPT_CHARS = 120

    /**
     * Plot is always the first section (never dropped by the budget), so it must
     * be truncated itself — plot/current.md tends to grow with every chapter and
     * would otherwise push the whole brief past any context budget.
     */
    private const val PLOT_SECTION_CHARS = 3_000

    fun assemble(
        store: NovelWorkspaceStore,
        branchSlug: String,
        maxChars: Int = 6_000,
        flags: NovelWorkspaceInjectionFlags = NovelWorkspaceInjectionFlags(),
        locale: Locale = Locale.CHINESE,
    ): String {
        val nodes = NovelWorkspaceNodes.collect(store, branchSlug)
        val sections = mutableListOf<String>()

        if (flags.plot) {
            plotSection(store, branchSlug, locale)?.let { sections.add(it) }
        }
        if (flags.foreshadowing) {
            foreshadowingSection(nodes, locale)?.let { sections.add(it) }
        }

        if (flags.neighborhood) {
            val plan = store.read(
                NovelWorkspacePaths.branchPrefix(branchSlug) + "/plan/this-chapter.md",
            )
            if (plan != null) {
                val planText = NovelWorkspaceMarkdown.parseFile(plan).body
                val subgraph = NovelWorkspaceNodes.neighborhood(nodes, planText)
                neighborhoodSection(subgraph, locale)?.let { sections.add(it) }
            }
        }

        if (flags.decisions) {
            decisionsSection(nodes.filter { it.nodeKind == NovelWorkspaceNodes.KIND_DECISION_LOG }, locale)
                ?.let { sections.add(it) }
        }

        val kept = mutableListOf<String>()
        var used = 0
        for (section in sections) {
            if (used + section.length > maxChars && kept.isNotEmpty()) continue
            kept.add(section)
            used += section.length
        }

        // D-C freshness gate: this warning is mandatory, never dropped by the budget.
        val stale = NovelWorkspaceLedger.isPlotStale(
            store,
            NovelWorkspaceLedger.load(store.rootDirectory),
            branchSlug,
        )
        if (stale) kept.add(0, freshnessWarning(locale))

        // D-D unresolved gate: a middle-chapter edit invalidates everything after it.
        val unresolved = NovelWorkspaceUnresolvedStore.entryFor(store.rootDirectory, branchSlug)
        if (unresolved != null) {
            kept.add(0, unresolvedWarning(unresolved.fromOrdinal, locale))
        }
        return kept.joinToString("\n\n")
    }

    private val FRESHNESS_WARNING_ZH = """
        ## ⚠️ 剧情落后于正文（写新内容前必须先同步）
        正文已更新到比 plot/ 更新的版本。请先读取最新章节，更新 plot/current.md 与受影响的角色/关系节点，
        使其与正文一致，然后再写新章节。未同步前不要推进新的正文内容。
    """.trimIndent()

    private val FRESHNESS_WARNING_EN = """
        ## ⚠️ Plot is behind the manuscript (sync before writing new content)
        The manuscript is newer than plot/. Read the latest chapters, update plot/current.md and the affected character/relationship nodes,
        make them match the manuscript, and only then write a new chapter. Do not advance the manuscript before syncing.
    """.trimIndent()

    private fun freshnessWarning(locale: Locale): String =
        if (isChinese(locale)) FRESHNESS_WARNING_ZH else FRESHNESS_WARNING_EN

    private fun unresolvedWarning(fromOrdinal: Int, locale: Locale): String = if (isChinese(locale)) """
        ## ⛔ 存在未解决的中间章修改（暂禁推进新正文）
        第 $fromOrdinal 章之前的某章被修改，第 $fromOrdinal 章及之后的内容与剧情可能已对不上。
        在作者处理（确认无碍 / fork / 重写后章）之前，不要写新章节、不要收录、不要代笔。
        可以讨论、阅读、以及修订受影响的后章。
    """.trimIndent() else """
        ## ⛔ Unresolved middle-chapter edit (new prose is paused)
        A chapter before chapter $fromOrdinal was edited, so chapter $fromOrdinal and later chapters may no longer match the plot.
        Until the author resolves it (confirm safe / fork / rewrite later chapters), do not write, collect, or ghostwrite new chapters.
        Discussion, reading, and revision of affected later chapters are allowed.
    """.trimIndent()

    private fun plotSection(store: NovelWorkspaceStore, branchSlug: String, locale: Locale): String? {
        val content = store.read(
            NovelWorkspacePaths.branchPrefix(branchSlug) + "/plot/current.md"
        ) ?: return null
        val body = NovelWorkspaceMarkdown.parseFile(content).body
        if (body.isBlank()) return null
        val capped = if (body.length > PLOT_SECTION_CHARS) {
            body.take(PLOT_SECTION_CHARS).trimEnd() + if (isChinese(locale)) {
                "\n（剧情状态过长已截断，完整内容可 read plot/current.md）"
            } else {
                "\n(Plot state truncated; read plot/current.md for the full content)"
            }
        } else {
            body
        }
        return if (isChinese(locale)) "## 当前剧情状态\n$capped" else "## Plot state\n$capped"
    }

    private fun foreshadowingSection(nodes: List<NovelWorkspaceNode>, locale: Locale): String? {
        val open = NovelWorkspaceNodes.openForeshadowing(nodes)
        if (open.isEmpty()) return null
        val lines = open.joinToString("\n") { node ->
            buildString {
                append("- ").append(node.title)
                if (node.body.isNotBlank()) {
                    append(if (isChinese(locale)) "：" else ": ").append(excerpt(node.body))
                }
            }
        }
        return if (isChinese(locale)) {
            "## 未回收伏笔（写到的要照应，别忘了收）\n$lines"
        } else {
            "## Open foreshadowing (honor these when writing)\n$lines"
        }
    }

    private fun neighborhoodSection(subgraph: List<NovelWorkspaceNode>, locale: Locale): String? {
        if (subgraph.isEmpty()) return null
        val lines = subgraph.joinToString("\n") { node -> renderNode(node, locale) }
        return if (isChinese(locale)) {
            "## 本章相关节点（以这些为准，勿与之矛盾）\n$lines"
        } else {
            "## Chapter-related nodes (source of truth; do not contradict)\n$lines"
        }
    }

    private fun decisionsSection(decisions: List<NovelWorkspaceNode>, locale: Locale): String? {
        if (decisions.isEmpty()) return null
        val lines = decisions.joinToString("\n") { node ->
            val detail = excerpt(node.body)
            if (detail.isBlank()) "- ${node.title}" else "- ${node.title}${if (isChinese(locale)) "：" else ": "}$detail"
        }
        return if (isChinese(locale)) {
            "## 已确认决定（不可违背）\n$lines"
        } else {
            "## Confirmed decisions (must not be contradicted)\n$lines"
        }
    }

    private fun renderNode(node: NovelWorkspaceNode, locale: Locale): String = buildString {
        append("- ").append(node.title)
        if (node.aliases.isNotEmpty()) {
            if (isChinese(locale)) {
                append("（").append(node.aliases.joinToString("、")).append("）")
            } else {
                append(" (").append(node.aliases.joinToString(", ")).append(")")
            }
        }
        if (node.status != null) {
            append(if (isChinese(locale)) "｜状态：" else " | status: ").append(node.status)
        }
        if (node.relations.isNotEmpty()) {
            append(if (isChinese(locale)) "｜关系：" else " | relationships: ")
            append(node.relations.joinToString(if (isChinese(locale)) "、" else ", ") { relation ->
                if (relation.type.isBlank()) relation.withRef else if (isChinese(locale)) {
                    "${relation.withRef}（${relation.type}）"
                } else {
                    "${relation.withRef} (${relation.type})"
                }
            })
        }
        val detail = excerpt(node.body)
        if (detail.isNotBlank()) {
            append(if (isChinese(locale)) "｜" else " | ").append(detail)
        }
    }

    private fun isChinese(locale: Locale): Boolean =
        locale.language.equals("zh", ignoreCase = true)

    private fun excerpt(body: String): String {
        val firstLine = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .firstOrNull()
            ?: return ""
        return if (firstLine.length > NODE_EXCERPT_CHARS) {
            firstLine.take(NODE_EXCERPT_CHARS)
        } else {
            firstLine
        }
    }
}
