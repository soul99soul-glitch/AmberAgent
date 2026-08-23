package app.amber.feature.novelworkspace

/** One file of a workspace tree (path relative to the manifest root, UTF-8 content). */
data class NovelWorkspaceFile(val path: String, val content: String)

data class NovelWorkspaceParsedMaterial(
    val id: String?,
    val materialKind: String,
    val customName: String?,
    val title: String,
    val content: String,
    val injection: String,
    val aliases: List<String>,
    val path: String,
)

data class NovelWorkspaceParsedChapter(
    val id: String?,
    val title: String,
    val content: String,
    val ordinal: Int,
)

/**
 * Validated view of a workspace file list — port of iOS `ParsedWorkspace`.
 * Import preview and install both work from this shape.
 */
data class NovelWorkspaceParsed(
    val format: String,
    val formatVersion: Int,
    val sourceProjectID: String?,
    val projectTitle: String,
    val polishPreference: String,
    val collaborationMode: String,
    val mainBranchSlug: String,
    val mainBranchID: String?,
    val materials: List<NovelWorkspaceParsedMaterial>,
    val workingChapters: List<NovelWorkspaceParsedChapter>,
    val discardedChapters: List<NovelWorkspaceParsedChapter>,
    val plotSummary: String?,
    val plotOutline: String?,
    val plotEvents: List<String>?,
    val highlights: List<String>?,
    val upcomingBeats: List<String>,
) {
    val hasKnownFormat: Boolean
        get() = format == NovelWorkspaceManifest.FORMAT &&
            formatVersion == NovelWorkspaceManifest.FORMAT_VERSION

    /** Missing plot means the importer must treat the branch as needs-sync. */
    val plotMissing: Boolean get() = plotSummary == null

    companion object {
        fun parse(files: List<NovelWorkspaceFile>): NovelWorkspaceParsed {
            val byPath = files.associateBy { it.path }
            val manifestText = byPath[NovelWorkspacePaths.MANIFEST]?.content
                ?: throw NovelWorkspaceFormatError("Workspace is missing manifest.yaml.")
            val manifest = NovelWorkspaceManifest.parse(manifestText)
            val projectFile = NovelWorkspaceMarkdown.parseFile(byPath[NovelWorkspacePaths.PROJECT_FILE]?.content ?: "")
            val mainPrefix = NovelWorkspacePaths.branchPrefix(manifest.mainBranch) + "/"

            val materials = mutableListOf<NovelWorkspaceParsedMaterial>()
            val working = mutableListOf<NovelWorkspaceParsedChapter>()
            val discarded = mutableListOf<NovelWorkspaceParsedChapter>()
            var plotSummary: String? = null
            var plotOutline: String? = null
            var plotEvents: List<String>? = null
            var highlights: List<String>? = null
            var upcoming = emptyList<String>()
            var branchID: String? = null

            for (file in files) {
                val parsed = NovelWorkspaceMarkdown.parseFile(file.content)
                val onMain = file.path.startsWith(mainPrefix)
                when {
                    file.path.startsWith("${NovelWorkspacePaths.SETTING_DIR}/") &&
                        !file.path.startsWith("${NovelWorkspacePaths.BRANCHES_DIR}/") -> {
                        materials.add(materialOf(file.path, parsed))
                    }
                    onMain && "/chapters/" in file.path && file.path.endsWith(".md") -> {
                        working.add(
                            NovelWorkspaceParsedChapter(
                                id = parsed.fields["id"],
                                title = parsed.fields["title"] ?: NovelWorkspacePaths.fileNameTitle(file.path),
                                content = parsed.body,
                                ordinal = NovelWorkspacePaths.chapterOrdinalFromPath(file.path)
                                    ?: (working.size + 1),
                            ),
                        )
                    }
                    onMain && "/discarded/" in file.path && file.path.endsWith(".md") -> {
                        discarded.add(
                            NovelWorkspaceParsedChapter(
                                id = parsed.fields["id"],
                                title = parsed.fields["title"] ?: NovelWorkspacePaths.fileNameTitle(file.path),
                                content = parsed.body,
                                ordinal = discarded.size + 1,
                            ),
                        )
                    }
                    onMain && file.path.endsWith("/plot/current.md") -> {
                        val (summary, splitHighlights) = NovelWorkspaceMarkdown.splitHighlights(parsed.body)
                        plotSummary = summary
                        highlights = splitHighlights
                    }
                    onMain && file.path.endsWith("/plot/outline.md") -> plotOutline = parsed.body
                    onMain && file.path.endsWith("/plot/events.md") -> plotEvents = parsed.body.let { body ->
                        body.split('\n')
                            .map { it.trim() }
                            .map { if (it.startsWith("- ")) it.drop(2) else it }
                            .filter { it.isNotEmpty() }
                    }
                    onMain && file.path.endsWith("/plan/upcoming.md") -> upcoming = parsed.body.let { body ->
                        body.split('\n')
                            .map { it.trim() }
                            .map { if (it.startsWith("- ")) it.drop(2) else it }
                            .filter { it.isNotEmpty() }
                    }
                    onMain && file.path.endsWith("/branch.md") -> branchID = parsed.fields["id"]
                }
            }

            return NovelWorkspaceParsed(
                format = manifest.format,
                formatVersion = manifest.formatVersion,
                sourceProjectID = manifest.sourceProjectID,
                projectTitle = projectFile.fields["title"]?.takeIf { it.isNotEmpty() } ?: "Untitled",
                polishPreference = projectFile.fields["polishPreference"] ?: "",
                collaborationMode = projectFile.fields["collaborationMode"] ?: "cocreation",
                mainBranchSlug = manifest.mainBranch,
                mainBranchID = branchID,
                materials = materials,
                workingChapters = working.sortedBy { it.ordinal },
                discardedChapters = discarded,
                plotSummary = plotSummary,
                plotOutline = plotOutline,
                plotEvents = plotEvents,
                highlights = highlights,
                upcomingBeats = upcoming,
            )
        }

        private fun materialOf(path: String, parsed: NovelWorkspaceMarkdown.ParsedFile): NovelWorkspaceParsedMaterial {
            val kind = when (val raw = parsed.fields["materialKind"]) {
                "world", "character", "relationship", "masterOutline",
                "writingRequirements", "decisionLog", "custom", -> raw
                else -> when {
                    "/characters/" in path -> "character"
                    "/relationships/" in path -> "relationship"
                    "/outline/" in path || "master-outline" in path -> "masterOutline"
                    "/writing/" in path || "writing-requirements" in path -> "writingRequirements"
                    "/log/" in path || "decision-log" in path -> "decisionLog"
                    "/custom/" in path -> "custom"
                    else -> "world"
                }
            }
            return NovelWorkspaceParsedMaterial(
                id = parsed.fields["id"],
                materialKind = kind,
                customName = parsed.fields["customName"],
                title = parsed.fields["title"] ?: NovelWorkspacePaths.fileNameTitle(path),
                content = parsed.body,
                injection = parsed.fields["injection"] ?: "smart",
                aliases = parsed.lists["aliases"].orEmpty(),
                path = path,
            )
        }
    }
}

class NovelWorkspaceFormatError(message: String) : IllegalArgumentException(message)
