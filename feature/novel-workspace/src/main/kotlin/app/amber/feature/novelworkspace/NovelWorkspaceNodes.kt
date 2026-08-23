package app.amber.feature.novelworkspace

/** Typed edge of the story graph, declared in node front matter (`relations:`). */
data class NovelWorkspaceRelation(
    val withRef: String,
    val type: String,
)

/**
 * One consistency node of the story graph. Nodes are ordinary markdown files
 * (setting cards + foreshadowing); the graph is the set of files plus their
 * declared relations — no separate database, no automatic extraction.
 */
data class NovelWorkspaceNode(
    val path: String,
    val nodeKind: String,
    val title: String,
    val aliases: List<String>,
    /** One-line current state; the injector's compact form for this node. */
    val status: String?,
    val relations: List<NovelWorkspaceRelation>,
    val body: String,
) {
    fun matches(text: String): Boolean {
        if (title.length >= 2 && text.contains(title)) return true
        return aliases.any { it.length >= 2 && text.contains(it) }
    }

    fun referredBy(name: String): Boolean =
        title == name || aliases.any { it == name }
}

object NovelWorkspaceNodes {

    const val KIND_FORESHADOWING = "foreshadowing"
    const val KIND_DECISION_LOG = "decisionLog"
    const val FORESHADOWING_DIR = "plot/foreshadowing"
    const val STATUS_OPEN = "open"
    const val STATUS_RESOLVED = "resolved"

    private val NON_NODE_KINDS = setOf("chapter", "plot", "plan", "project", "branch")

    fun parse(path: String, content: String): NovelWorkspaceNode? {
        val parsed = NovelWorkspaceMarkdown.parseFile(content)
        val kind = parsed.fields["materialKind"] ?: parsed.fields["kind"] ?: return null
        if (kind in NON_NODE_KINDS) return null
        return NovelWorkspaceNode(
            path = path,
            nodeKind = kind,
            title = parsed.fields["title"] ?: NovelWorkspacePaths.fileNameTitle(path),
            aliases = parsed.lists["aliases"].orEmpty(),
            status = parsed.fields["status"]?.takeIf { it.isNotBlank() },
            relations = parsed.maps["relations"].orEmpty().mapNotNull { map ->
                val withRef = map["with"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NovelWorkspaceRelation(withRef = withRef, type = map["type"].orEmpty())
            },
            body = parsed.body,
        )
    }

    /** Global setting cards plus this branch's foreshadowing files. */
    fun collect(store: NovelWorkspaceStore, branchSlug: String): List<NovelWorkspaceNode> {
        val paths = store.list(NovelWorkspacePaths.SETTING_DIR) +
            store.list(NovelWorkspacePaths.branchPrefix(branchSlug) + "/" + FORESHADOWING_DIR)
        return paths.mapNotNull { path ->
            val content = store.read(path) ?: return@mapNotNull null
            parse(path, content)
        }
    }

    /** Foreshadowing without an explicit `resolved` status counts as still open. */
    fun openForeshadowing(nodes: List<NovelWorkspaceNode>): List<NovelWorkspaceNode> = nodes
        .filter { it.nodeKind == KIND_FORESHADOWING }
        .filter { it.status == null || it.status.equals(STATUS_OPEN, ignoreCase = true) }

    /**
     * Entities named in [text] plus one relation hop outward — the chapter's subgraph.
     * Order-preserving and de-duplicated so the brief stays stable.
     */
    fun neighborhood(nodes: List<NovelWorkspaceNode>, text: String): List<NovelWorkspaceNode> {
        val matched = nodes.filter { it.matches(text) }
        val result = LinkedHashMap<String, NovelWorkspaceNode>()
        matched.forEach { result[it.path] = it }
        for (node in matched) {
            for (relation in node.relations) {
                val neighbor = nodes.firstOrNull { other ->
                    other.path != node.path && other.referredBy(relation.withRef)
                }
                if (neighbor != null) result.putIfAbsent(neighbor.path, neighbor)
            }
        }
        return result.values.toList()
    }
}
