package app.amber.feature.novel.domain

/**
 * Stable paragraph IDs and default full selection for collection UI.
 */
object NovelParagraphSelection {
    data class Paragraph(
        val id: String,
        val index: Int,
        val text: String,
    )

    fun splitParagraphs(content: String): List<Paragraph> {
        if (content.isEmpty()) return emptyList()
        val parts = content.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return parts.mapIndexed { index, text ->
            Paragraph(
                id = "p-${index + 1}-${stableHash(text)}",
                index = index,
                text = text,
            )
        }
    }

    fun defaultSelectedIds(paragraphs: List<Paragraph>): Set<String> =
        paragraphs.map { it.id }.toSet()

    fun joinSelected(paragraphs: List<Paragraph>, selectedIds: Set<String>): String =
        paragraphs.filter { it.id in selectedIds }.joinToString("\n\n") { it.text }

    /**
     * Collection chapter target defaults:
     * - whole chapter -> new chapter N+1
     * - continuation -> append current chapter
     * - no chapters -> first chapter
     */
    enum class SuggestedTarget {
        CreateFirstChapter,
        AppendCurrentChapter,
        CreateNextChapter,
    }

    fun suggestTarget(
        chapterCount: Int,
        isWholeChapter: Boolean,
    ): SuggestedTarget = when {
        chapterCount <= 0 -> SuggestedTarget.CreateFirstChapter
        isWholeChapter -> SuggestedTarget.CreateNextChapter
        else -> SuggestedTarget.AppendCurrentChapter
    }

    private fun stableHash(text: String): String {
        var h = 0
        for (ch in text) {
            h = 31 * h + ch.code
        }
        return Integer.toHexString(h)
    }
}
