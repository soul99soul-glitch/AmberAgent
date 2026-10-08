package app.amber.feature.novelworkspace

/** The current branch's setting view; a branch card replaces the global card with the same id. */
object NovelWorkspaceEffectiveMaterials {
    data class Entry(
        val path: String,
        val parsed: NovelWorkspaceMarkdown.ParsedFile,
        /** Path below either the global or branch setting directory, used for catalog grouping. */
        val settingRelativePath: String,
    )

    fun collect(store: NovelWorkspaceStore, branchSlug: String): List<Entry> {
        val entries = LinkedHashMap<String, Entry>()
        val prefixes = listOf(
            NovelWorkspacePaths.SETTING_DIR,
            "${NovelWorkspacePaths.branchPrefix(branchSlug)}/${NovelWorkspacePaths.SETTING_DIR}",
        )
        for (prefix in prefixes) {
            for (path in store.list(prefix)) {
                val content = store.read(path) ?: continue
                val parsed = NovelWorkspaceMarkdown.parseFile(content)
                val relative = path.removePrefix("$prefix/")
                val id = parsed.fields["id"]?.takeIf { it.isNotBlank() }
                val key = if (id == null) "path:$relative" else "id:$id"
                entries[key] = Entry(path, parsed, relative)
            }
        }
        return entries.values.toList()
    }

    /** Same effective source for the preference editor and regeneration/polish prompts. */
    fun writingPreference(store: NovelWorkspaceStore, branchSlug: String): Entry? =
        collect(store, branchSlug).firstOrNull { entry ->
            entry.parsed.fields["materialKind"] == "writingRequirements" ||
                entry.settingRelativePath.startsWith("writing/") ||
                entry.settingRelativePath.substringAfterLast('/').startsWith("writing-requirements")
        }

    fun writingPreferenceForPrompt(store: NovelWorkspaceStore, branchSlug: String): String =
        writingPreference(store, branchSlug)
            ?.takeUnless { it.parsed.fields["injection"].equals("off", ignoreCase = true) }
            ?.parsed?.body.orEmpty()
}
