package app.amber.feature.novel.background

import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelGhostwriteCorrectionPacketV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelGhostwriteBatchEffectsTest {
    @Test
    fun correctionPrompt_containsOnlyImmediateRejectedDraftAndStructuredReasons() {
        val currentCandidate = NovelCandidateId.generate()
        val packet = NovelGhostwriteCorrectionPacketV1.bounded(
            reasonCode = "plan_acceptance_failed",
            summary = "REVIEWER_FREE_TEXT_MUST_NOT_LEAK",
            missingMustHappen = listOf("Open the sealed gate"),
            forbiddenViolations = listOf("Do not reveal the hidden name"),
            sourceCandidateID = currentCandidate,
            planDigest = sha256HexOfUtf8("plan"),
        )

        val prompt = novelGhostwriteUserText(
            NovelGhostwriteProseRequest(
                projectId = NovelProjectId.generate(),
                branchId = NovelBranchId.generate(),
                runId = NovelRunId.generate(),
                planId = NovelChapterPlanId.generate(),
                expectedProjectRevision = 10,
                expectedConfigRevision = 2,
                expectedBranchHeadRevision = 4,
                correctionPacket = packet,
            ),
        )

        assertTrue(prompt.contains("plan_acceptance_failed"))
        assertTrue(prompt.contains("Open the sealed gate"))
        assertFalse(prompt.contains("CURRENT_REJECTED_DRAFT_MARKER"))
        assertFalse(prompt.contains("REVIEWER_FREE_TEXT_MUST_NOT_LEAK"))
        assertFalse(prompt.contains("OLDER_REJECTED_DRAFT_MARKER"))
        assertFalse(prompt.contains("ORDINARY_SESSION_MARKER"))
    }

    @Test
    fun firstAttemptPrompt_doesNotContainSessionOrRejectedDraftMaterial() {
        val prompt = novelGhostwriteUserText(
            NovelGhostwriteProseRequest(
                projectId = NovelProjectId.generate(),
                branchId = NovelBranchId.generate(),
                runId = NovelRunId.generate(),
                planId = NovelChapterPlanId.generate(),
                expectedProjectRevision = 10,
                expectedConfigRevision = 2,
                expectedBranchHeadRevision = 4,
            ),
        )

        assertTrue(prompt.contains("confirmed chapter plan"))
        assertFalse(prompt.contains("REJECTED_DRAFT"))
        assertFalse(prompt.contains("ORDINARY_SESSION"))
    }
}
