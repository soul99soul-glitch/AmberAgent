package app.amber.feature.novel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelContinuityAuditMapperTest {
    @Test
    fun chunking_repeatsCandidateAndReportsOversizedCanonicalChapter() {
        val canonical = listOf(
            chapter(1, "第一章", "一".repeat(30)),
            chapter(2, "第二章", "二".repeat(300)),
            chapter(3, "第三章", "三".repeat(30)),
        )
        val candidate = chapter(4, "候选下一章", "候选正文", isCandidate = true)

        val result = NovelContinuityAuditMapper.chunkCanonicalWithCandidate(
            canonicalChapters = canonical,
            candidate = candidate,
            maxManuscriptChars = 100,
        )

        assertEquals(1, result.failedChunkCount)
        assertEquals(2, result.chunks.size)
        assertTrue(result.chunks.all { it.chapters.last() == candidate })
        assertTrue(result.chunks.all { it.manuscript.length <= 100 })
    }

    @Test
    fun mapping_rejectsHallucinatedEvidence() {
        val canonical = chapter(1, "第一章", "林舟把银钥匙放进抽屉。")
        val candidate = chapter(2, "候选下一章", "林舟说银钥匙已经遗失。", isCandidate = true)
        val audit = audit(
            issue(
                id = "key-conflict",
                references = listOf(
                    reference(canonical, "银钥匙放进抽屉"),
                    reference(candidate, "从未出现在候选里的伪造证据"),
                ),
            ),
        )

        assertTrue(
            runCatching {
                NovelContinuityAuditMapper.mapValidatedIssues(
                    audit,
                    listOf(canonical, candidate),
                    chunkIndex = 0,
                )
            }.isFailure,
        )
    }

    @Test
    fun mapping_separatesCandidateIssuesFromCanonicalOnlyBlockingIssuesAndDeduplicates() {
        val first = chapter(1, "第一章", "林舟把银钥匙放进抽屉。")
        val second = chapter(2, "第二章", "旧正史却说钥匙被投入河中。")
        val candidate = chapter(3, "候选下一章", "林舟说银钥匙已经遗失。", isCandidate = true)
        val candidateIssue = issue(
            id = "candidate-key",
            references = listOf(
                reference(first, "银钥匙放进抽屉"),
                reference(candidate, "银钥匙已经遗失"),
            ),
        )
        val canonicalIssue = issue(
            id = "canonical-key",
            references = listOf(
                reference(first, "银钥匙放进抽屉"),
                reference(second, "钥匙被投入河中"),
            ),
        )

        val mapped = NovelContinuityAuditMapper.mapValidatedIssues(
            audit(candidateIssue, canonicalIssue),
            listOf(first, second, candidate),
            chunkIndex = 0,
        )

        assertEquals(listOf(candidateIssue.copy(id = "chunk-1:candidate-key")), mapped.candidateIssues)
        assertEquals(
            listOf(canonicalIssue.copy(id = "chunk-1:canonical-key")),
            mapped.canonicalOnlyBlockingIssues,
        )
        assertEquals(
            1,
            NovelContinuityAuditMapper.deduplicate(
                listOf(candidateIssue, candidateIssue.copy(id = "same-meaning")),
            ).size,
        )
    }

    @Test
    fun mapping_rejectsDuplicatedCopyOfTheSameReference() {
        val canonical = chapter(1, "第一章", "银钥匙仍在抽屉。")
        val candidate = chapter(2, "候选下一章", "候选正文。", isCandidate = true)
        val copiedReference = reference(canonical, "银钥匙仍在抽屉")

        val result = runCatching {
            NovelContinuityAuditMapper.mapValidatedIssues(
                audit(issue("duplicated-reference", listOf(copiedReference, copiedReference))),
                listOf(canonical, candidate),
                chunkIndex = 0,
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun sameRawIssueIdFromTwoChunksKeepsDifferentAnchoredBlockingIssues() {
        val first = chapter(1, "第一章", "第一处正史证据。")
        val second = chapter(2, "第二章", "第二处正史证据。")
        val candidate = chapter(3, "候选下一章", "候选证据。", isCandidate = true)
        val firstMapped = NovelContinuityAuditMapper.mapValidatedIssues(
            audit(
                issue(
                    "issue-1",
                    listOf(reference(first, "第一处正史证据"), reference(candidate, "候选证据")),
                ),
            ),
            listOf(first, candidate),
            chunkIndex = 0,
        )
        val secondMapped = NovelContinuityAuditMapper.mapValidatedIssues(
            audit(
                issue(
                    "issue-1",
                    listOf(reference(second, "第二处正史证据"), reference(candidate, "候选证据")),
                ),
            ),
            listOf(second, candidate),
            chunkIndex = 1,
        )

        val deduplicated = NovelContinuityAuditMapper.deduplicate(
            firstMapped.candidateIssues + secondMapped.candidateIssues,
        )

        assertEquals(listOf("chunk-1:issue-1", "chunk-2:issue-1"), deduplicated.map { it.id })
    }

    private fun chapter(
        ordinal: Int,
        title: String,
        content: String,
        isCandidate: Boolean = false,
    ) = NovelContinuityAuditChapter(ordinal, title, content, isCandidate)

    private fun reference(
        chapter: NovelContinuityAuditChapter,
        evidence: String,
    ) = NovelContinuityReferenceV1(chapter.ordinal, chapter.title, evidence)

    private fun issue(
        id: String,
        references: List<NovelContinuityReferenceV1>,
    ) = NovelContinuityIssueV1(
        id = id,
        category = NovelContinuityIssueCategoryV1.Contradiction,
        severity = NovelContinuityIssueSeverityV1.Blocking,
        summary = "银钥匙状态冲突",
        references = references,
    )

    private fun audit(vararg issues: NovelContinuityIssueV1) = NovelContinuityAuditV1(
        schemaVersion = 1,
        consistent = issues.isEmpty(),
        issues = issues.toList(),
    )
}
