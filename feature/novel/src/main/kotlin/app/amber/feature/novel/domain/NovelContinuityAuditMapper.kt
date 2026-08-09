package app.amber.feature.novel.domain

import java.util.Locale

/** One exact chapter payload shown to the continuity reviewer. */
data class NovelContinuityAuditChapter(
    val ordinal: Int,
    val title: String,
    val content: String,
    val isCandidate: Boolean,
) {
    init {
        require(ordinal >= 1) { "chapter ordinal must be at least 1" }
        require(title.isNotBlank()) { "chapter title must be non-blank" }
        require(content.isNotBlank()) { "chapter content must be non-blank" }
    }

    fun rendered(): String = "# Chapter $ordinal: $title\n\n$content"
}

data class NovelContinuityAuditChunk(
    val chapters: List<NovelContinuityAuditChapter>,
) {
    init {
        require(chapters.count { it.isCandidate } == 1) {
            "continuity audit chunk must contain exactly one candidate"
        }
        require(chapters.map { it.ordinal }.distinct().size == chapters.size) {
            "continuity audit chunk chapter ordinals must be unique"
        }
    }

    val manuscript: String = chapters.joinToString("\n\n") { it.rendered() }
}

data class NovelContinuityAuditChunkingResult(
    val chunks: List<NovelContinuityAuditChunk>,
    val failedChunkCount: Int,
)

data class NovelContinuityMappedIssues(
    /** Issues whose evidence includes the uncollected candidate. */
    val candidateIssues: List<NovelContinuityIssueV1>,
    /** Existing canonical defects must not be used as a reason to rewrite the candidate. */
    val canonicalOnlyBlockingIssues: List<NovelContinuityIssueV1>,
)

/**
 * Builds bounded candidate audit requests and maps model references back to the exact supplied text.
 * Rejected mappings are deliberately fail-closed; callers must count the whole chunk as failed.
 */
object NovelContinuityAuditMapper {
    fun chunkCanonicalWithCandidate(
        canonicalChapters: List<NovelContinuityAuditChapter>,
        candidate: NovelContinuityAuditChapter,
        maxManuscriptChars: Int,
    ): NovelContinuityAuditChunkingResult {
        require(!canonicalChapters.any { it.isCandidate }) {
            "canonical chapter list cannot contain a candidate"
        }
        require(candidate.isCandidate) { "candidate chapter must be marked as candidate" }
        require(maxManuscriptChars > 0) { "maxManuscriptChars must be positive" }
        require((canonicalChapters + candidate).map { it.ordinal }.distinct().size == canonicalChapters.size + 1) {
            "continuity audit chapter ordinals must be unique"
        }
        if (canonicalChapters.isEmpty()) {
            return NovelContinuityAuditChunkingResult(emptyList(), failedChunkCount = 0)
        }

        val candidateChars = candidate.rendered().length
        if (candidateChars > maxManuscriptChars) {
            return NovelContinuityAuditChunkingResult(emptyList(), failedChunkCount = 1)
        }
        val canonicalBudget = maxManuscriptChars - candidateChars - CHAPTER_SEPARATOR.length
        if (canonicalBudget <= 0) {
            return NovelContinuityAuditChunkingResult(emptyList(), failedChunkCount = 1)
        }

        val chunks = mutableListOf<NovelContinuityAuditChunk>()
        val current = mutableListOf<NovelContinuityAuditChapter>()
        var currentChars = 0
        var failed = 0

        fun flush() {
            if (current.isEmpty()) return
            chunks += NovelContinuityAuditChunk(current.toList() + candidate)
            current.clear()
            currentChars = 0
        }

        canonicalChapters.forEach { chapter ->
            val chapterChars = chapter.rendered().length
            if (chapterChars > canonicalBudget) {
                flush()
                failed += 1
            } else {
                val addedChars = chapterChars + if (current.isEmpty()) 0 else CHAPTER_SEPARATOR.length
                if (current.isNotEmpty() && currentChars + addedChars > canonicalBudget) {
                    flush()
                }
                current += chapter
                currentChars += chapterChars + if (current.size == 1) 0 else CHAPTER_SEPARATOR.length
            }
        }
        flush()
        return NovelContinuityAuditChunkingResult(chunks, failed)
    }

    fun mapValidatedIssues(
        audit: NovelContinuityAuditV1,
        suppliedChapters: List<NovelContinuityAuditChapter>,
        chunkIndex: Int,
    ): NovelContinuityMappedIssues {
        require(chunkIndex >= 0) { "chunkIndex must be non-negative" }
        val chaptersByOrdinal = suppliedChapters.associateBy { it.ordinal }
        require(chaptersByOrdinal.size == suppliedChapters.size) {
            "supplied chapter ordinals must be unique"
        }

        val candidateIssues = mutableListOf<NovelContinuityIssueV1>()
        val canonicalOnlyBlockingIssues = mutableListOf<NovelContinuityIssueV1>()
        audit.issues.forEach { issue ->
            var referencesCandidate = false
            val distinctAnchoredReferences = mutableSetOf<Pair<Int, String>>()
            issue.references.forEach { reference ->
                val chapter = chaptersByOrdinal[reference.chapterOrdinal]
                    ?: throw IllegalArgumentException(
                        "continuity reference points outside the supplied chunk: ${reference.chapterOrdinal}",
                    )
                require(reference.chapterTitle.trim() == chapter.title) {
                    "continuity reference title does not match chapter ${chapter.ordinal}"
                }
                val evidence = reference.evidence.trim()
                require(evidence.isNotEmpty() && chapter.content.contains(evidence)) {
                    "continuity reference evidence is not an exact excerpt of chapter ${chapter.ordinal}"
                }
                distinctAnchoredReferences += reference.chapterOrdinal to evidence
                referencesCandidate = referencesCandidate || chapter.isCandidate
            }
            require(distinctAnchoredReferences.size >= 2) {
                "continuity issue needs at least 2 distinct anchored references"
            }
            val locallyStableIssue = issue.copy(id = "chunk-${chunkIndex + 1}:${issue.id.trim()}")
            when {
                referencesCandidate -> candidateIssues += locallyStableIssue
                issue.severity == NovelContinuityIssueSeverityV1.Blocking ->
                    canonicalOnlyBlockingIssues += locallyStableIssue
            }
        }
        return NovelContinuityMappedIssues(
            candidateIssues = candidateIssues,
            canonicalOnlyBlockingIssues = canonicalOnlyBlockingIssues,
        )
    }

    fun deduplicate(issues: List<NovelContinuityIssueV1>): List<NovelContinuityIssueV1> {
        val seenFingerprints = mutableSetOf<String>()
        return issues.filter { issue ->
            val normalizedSummary = issue.summary
                .trim()
                .replace(WHITESPACE, " ")
                .lowercase(Locale.ROOT)
            val references = issue.references
                .map { reference ->
                    listOf(
                        reference.chapterOrdinal.toString(),
                        reference.chapterTitle.trim().lowercase(Locale.ROOT),
                        reference.evidence.trim().replace(WHITESPACE, " "),
                    ).joinToString("|")
                }
                .sorted()
                .joinToString("||")
            val fingerprint = "${issue.category}|${issue.severity}|$normalizedSummary|$references"
            seenFingerprints.add(fingerprint)
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private const val CHAPTER_SEPARATOR = "\n\n"
}
