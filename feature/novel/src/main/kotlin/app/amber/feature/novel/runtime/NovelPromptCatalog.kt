package app.amber.feature.novel.runtime

enum class NovelPromptKind {
    QuickStart,
    Discussion,
    ProseContinuation,
    ProseWholeChapter,
    StateDeltaV1,
    ManualSyncV1,
    WholeChapterPolish,
    PolishDriftV1,
}

data class NovelPromptTemplate(
    val kind: NovelPromptKind,
    val version: String,
    val systemText: String,
)

object NovelPromptCatalog {
    const val POLISH_COMPLETION_SENTINEL = "<AMBER_NOVEL_POLISH_COMPLETE>"

    fun template(kind: NovelPromptKind): NovelPromptTemplate = when (kind) {
        NovelPromptKind.QuickStart -> NovelPromptTemplate(
            kind = kind,
            version = NovelPromptVersions.QUICK_START,
            systemText = """
                You help shape a new novel from a short seed. Return exactly one JSON object and no Markdown,
                prose outside the object, or code fence. Every suggestion is a proposal that requires explicit
                user confirmation. Do not claim that proposed events have happened, and do not mutate project
                materials or branch state. Use the user's language.

                The object must contain exactly these fields and all strings must be non-empty:
                {
                  "schemaVersion": 1,
                  "overview": "A concise overview of the proposed direction",
                  "world": {"title": "...", "content": "Concrete world rules and constraints"},
                  "characters": {"title": "...", "content": "Core character profiles and motivations"},
                  "masterOutline": {"title": "...", "content": "A clear master plot outline"},
                  "writingRequirements": {"title": "...", "content": "Voice, pacing, and style requirements"}
                }
            """.trimIndent(),
        )
        NovelPromptKind.Discussion -> NovelPromptTemplate(
            kind = kind,
            version = NovelPromptVersions.DISCUSSION,
            systemText = """
                You are a novel-planning partner. Discuss plot options, character motivations, pacing, and
                consequences using the supplied project and branch context. Clearly distinguish established
                branch facts from suggestions. Do not write canonical manuscript, advance the story, or treat
                any suggestion as an event that has happened.
            """.trimIndent(),
        )
        NovelPromptKind.ProseContinuation -> NovelPromptTemplate(
            kind = kind,
            version = NovelPromptVersions.PROSE_CONTINUATION,
            systemText = """
                Write one polished prose continuation that can be appended to the current chapter. Preserve all
                supplied project rules and established branch facts. Continue naturally from the current chapter
                tail without recapping it. Return only the candidate prose as one complete response. This output
                is a draft candidate and does not become canonical until the user collects it.
            """.trimIndent(),
        )
        NovelPromptKind.ProseWholeChapter -> NovelPromptTemplate(
            kind = kind,
            version = NovelPromptVersions.PROSE_WHOLE_CHAPTER,
            systemText = """
                Write one complete next chapter with a coherent opening, development, and ending beat. Preserve
                all supplied project rules and established branch facts, and continue from the prior chapter
                without rewriting it. Return only the full chapter candidate as one complete response. This output
                is a draft candidate and does not become canonical until the user collects it.
            """.trimIndent(),
        )
        NovelPromptKind.StateDeltaV1 -> NovelPromptTemplate(
            kind = kind,
            version = NovelPromptVersions.STATE_DELTA,
            systemText = """
                Extract only story-state changes caused by the newly collected manuscript.
                Do not infer unsupported facts. Project-setting changes must be proposals, never direct mutations.
                Return exactly one JSON object (schemaVersion 1) with fields:
                stateSummary, events, characterChanges, relationshipChanges, foreshadowingChanges,
                unresolvedEntityNames, branchOutlinePatch, settingProposals.
            """.trimIndent(),
        )
        NovelPromptKind.ManualSyncV1 -> NovelPromptTemplate(
            kind = kind,
            version = NovelPromptVersions.MANUAL_SYNC,
            systemText = """
                Rebuild derived branch state from a manuscript chunk. Return NovelStateRebuildV1 JSON
                (schemaVersion 1) with stateSummary, branchOutline, events, characterStates, relationships,
                foreshadowing, unresolvedEntityNames, settingProposals.
            """.trimIndent(),
        )
        NovelPromptKind.WholeChapterPolish -> NovelPromptTemplate(
            kind = kind,
            version = NovelPromptVersions.WHOLE_CHAPTER_POLISH,
            systemText = """
                Polish the complete supplied chapter while preserving its story facts exactly. You may improve
                wording, rhythm, description, dialogue flow, and local clarity. You must not add, remove, reorder,
                merge, or split story events. Append a final line containing exactly $POLISH_COMPLETION_SENTINEL.
            """.trimIndent(),
        )
        NovelPromptKind.PolishDriftV1 -> NovelPromptTemplate(
            kind = kind,
            version = NovelPromptVersions.POLISH_DRIFT,
            systemText = """
                Compare source and polished chapter for story-fact compatibility. Return JSON:
                {"schemaVersion":1,"compatible":true|false,"differences":[]}
            """.trimIndent(),
        )
    }

    fun completedPolishContent(output: String): String? {
        val trimmed = output.trim()
        val lineBreak = trimmed.lastIndexOf('\n')
        if (lineBreak < 0) return null
        val marker = trimmed.substring(lineBreak + 1).trim()
        if (marker != POLISH_COMPLETION_SENTINEL) return null
        val content = trimmed.substring(0, lineBreak).trim()
        if (content.isEmpty() || content.contains(POLISH_COMPLETION_SENTINEL)) return null
        return content
    }
}
