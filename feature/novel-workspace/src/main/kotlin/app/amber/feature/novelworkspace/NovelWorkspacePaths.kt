package app.amber.feature.novelworkspace

/**
 * Tree layout constants + path rules from the cross-platform workspace spec
 * (docs/2026-08-19-novel-workspace-conventions.md).
 */
object NovelWorkspacePaths {
    const val MANIFEST = "manifest.yaml"
    const val PROJECT_FILE = "project.md"
    const val SETTING_DIR = "setting"
    const val INBOX_DIR = "inbox"
    const val DRAFTS_DIR = "drafts"
    const val BRANCHES_DIR = "branches"

    fun branchPrefix(branchSlug: String): String = "$BRANCHES_DIR/$branchSlug"

    /** Chapter identity is the ordinal, not the slug: `031-入汴.md`. Locale-pinned: ar/fa
     *  devices would otherwise emit localized digits and break the cross-platform contract. */
    fun chapterFileName(ordinal: Int, slug: String): String =
        String.format(java.util.Locale.ROOT, "%03d-%s.md", ordinal, slug.ifEmpty { "untitled" })

    fun chapterOrdinalFromPath(path: String): Int? {
        val name = path.substringAfterLast('/').removeSuffix(".md")
        val prefix = name.takeWhile { it.isDigit() }
        return prefix.toIntOrNull()
    }

    /** Title fallback from a file name: strips the numeric ordinal prefix (matches iOS,
     *  which also accepts an empty digit run before the dash). */
    fun fileNameTitle(path: String): String {
        val name = path.substringAfterLast('/').removeSuffix(".md")
        val dash = name.indexOf('-')
        return if (dash >= 0 && name.substring(0, dash).all { it.isDigit() }) {
            name.substring(dash + 1)
        } else {
            name
        }
    }

    /**
     * The single canon gate: agent writes to these paths must become approval-card commits.
     */
    fun isProtectedPath(path: String): Boolean {
        val segments = path.split('/')
        if (segments.size < 4 || segments[0] != BRANCHES_DIR) return false
        val section = segments[2]
        return section == "chapters" || section == "plot"
    }

    /**
     * Free-write whitelist (matches iOS): setting/plan/inbox/drafts save directly.
     * Anything neither protected nor free must be rejected by the write tool —
     * manifest.yaml, project.md, branch.md and discarded/ are host-owned.
     */
    fun isFreeWritePath(path: String): Boolean {
        val segments = path.split('/')
        if (segments.isEmpty() || segments.first().startsWith(".")) return false
        return when (segments[0]) {
            SETTING_DIR, INBOX_DIR, DRAFTS_DIR -> segments.size >= 2
            BRANCHES_DIR -> segments.size >= 4 && (segments[2] == SETTING_DIR || segments[2] == "plan")
            else -> false
        }
    }

    /**
     * Reject absolute paths, parent escapes, hidden trees, and anything outside the book.
     * Tool input must pass this before touching the filesystem.
     */
    fun validate(path: String) {
        require(path.isNotEmpty()) { "Workspace path is empty." }
        require(!path.startsWith("/")) { "Workspace path must be tree-relative: $path" }
        require(!path.contains('\\')) { "Workspace path must use '/': $path" }
        val segments = path.split('/')
        require(segments.none { it.isEmpty() }) { "Workspace path has empty segments: $path" }
        require(segments.none { it == ".." }) { "Workspace path escapes the tree: $path" }
        require(!segments.first().startsWith(".")) { "Workspace path is hidden: $path" }
    }
}
