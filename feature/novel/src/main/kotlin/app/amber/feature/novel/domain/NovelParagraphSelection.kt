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

    /**
     * Align with iOS [NovelParagraphParser]: blank / whitespace-only lines separate paragraphs;
     * consecutive non-blank lines stay in one paragraph (joined with single `\n`).
     */
    fun splitParagraphs(content: String): List<Paragraph> {
        if (content.isEmpty()) return emptyList()
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val result = ArrayList<Paragraph>()
        val lines = ArrayList<String>()
        fun flush() {
            if (lines.isEmpty()) return
            val text = lines.joinToString("\n").trim()
            lines.clear()
            if (text.isEmpty()) return
            val index = result.size
            result += Paragraph(
                id = "p-${index + 1}-${stableHash(text)}",
                index = index,
                text = text,
            )
        }
        for (line in normalized.split('\n')) {
            if (line.trim().isEmpty()) flush() else lines += line
        }
        flush()
        return result
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
