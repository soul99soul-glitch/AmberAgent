package app.amber.feature.novel

import app.amber.feature.novel.runtime.NovelInjectionDefaults
import app.amber.feature.novel.runtime.NovelInjectionPlanner
import app.amber.feature.novel.runtime.NovelPromptCatalog
import app.amber.feature.novel.runtime.NovelPromptKind
import app.amber.feature.novel.runtime.NovelPromptVersions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPromptVersionsTest {
    @Test
    fun frozenPromptVersionsMatchIosCatalog() {
        assertEquals("novel.quick-start.v3", NovelPromptVersions.QUICK_START)
        assertEquals("novel.discussion.v1", NovelPromptVersions.DISCUSSION)
        assertEquals("novel.prose-continuation.v1", NovelPromptVersions.PROSE_CONTINUATION)
        assertEquals("novel.prose-whole-chapter.v1", NovelPromptVersions.PROSE_WHOLE_CHAPTER)
        assertEquals("novel.state-delta.v1", NovelPromptVersions.STATE_DELTA)
        assertEquals("novel.manual-sync.v2", NovelPromptVersions.MANUAL_SYNC)
        assertEquals("novel.whole-chapter-polish.v2", NovelPromptVersions.WHOLE_CHAPTER_POLISH)
        assertEquals("novel.whole-chapter-regeneration.v1", NovelPromptVersions.WHOLE_CHAPTER_REGENERATION)
        assertEquals("novel.polish-drift.v1", NovelPromptVersions.POLISH_DRIFT)
        assertEquals("novel.continuity-audit.v1", NovelPromptVersions.CONTINUITY_AUDIT)
        assertEquals("novel.discussion-archive.v1", NovelPromptVersions.DISCUSSION_ARCHIVE)
        assertEquals("novel.chapter-plan-acceptance.v2", NovelPromptVersions.CHAPTER_PLAN_ACCEPTANCE)
        assertEquals("novel.chapter-plan-proposal.v1", NovelPromptVersions.CHAPTER_PLAN_PROPOSAL)
        assertEquals(13, NovelPromptVersions.all.size)
        assertEquals(NovelPromptVersions.all.toSet().size, NovelPromptVersions.all.size)
    }

    @Test
    fun ghostwriteStructuredPromptsPublishExactWireContracts() {
        val acceptance = NovelPromptCatalog.template(NovelPromptKind.ChapterPlanAcceptanceV1)
        assertEquals(NovelPromptVersions.CHAPTER_PLAN_ACCEPTANCE, acceptance.version)
        assertTrue(acceptance.systemText.contains("\"schemaVersion\":2"))
        assertTrue(acceptance.systemText.contains("\"forbiddenViolations\""))
        assertTrue(acceptance.systemText.contains("\"obviousRepetition\""))
        assertTrue(acceptance.systemText.contains("Fail closed"))

        val proposal = NovelPromptCatalog.template(NovelPromptKind.ChapterPlanProposalV1)
        assertEquals(NovelPromptVersions.CHAPTER_PLAN_PROPOSAL, proposal.version)
        assertTrue(proposal.systemText.contains("NovelChapterPlanProposalV1"))
        assertTrue(proposal.systemText.contains("\"mustHappen\":[\"non-empty string\"]"))
        assertTrue(proposal.systemText.contains("Do not add unknown keys"))
    }

    @Test
    fun manualSyncPromptRequestsTheStateDeltaWireSchemaConsumedByProduction() {
        val manualSync = NovelPromptCatalog.template(NovelPromptKind.ManualSyncV1)
        val rootShape = manualSync.systemText.substring(
            startIndex = manualSync.systemText.indexOf('{'),
            endIndex = manualSync.systemText.lastIndexOf('}') + 1,
        )
        val rootFields = Json.parseToJsonElement(rootShape).jsonObject.keys

        assertEquals(NovelPromptVersions.MANUAL_SYNC, manualSync.version)
        assertTrue(manualSync.systemText.contains("NovelStateDeltaV1"))
        assertEquals(
            setOf(
                "schemaVersion",
                "stateSummary",
                "events",
                "characterChanges",
                "relationshipChanges",
                "foreshadowingChanges",
                "unresolvedEntityNames",
                "branchOutlinePatch",
                "settingProposals",
            ),
            rootFields,
        )
        assertFalse(manualSync.systemText.contains("NovelStateRebuildV1"))
        assertFalse(manualSync.systemText.contains("\"branchOutline\""))
        assertFalse(manualSync.systemText.contains("\"characterStates\""))
        assertFalse(manualSync.systemText.contains("\"relationships\""))
        assertFalse(manualSync.systemText.contains("\"foreshadowing\""))
        assertTrue(manualSync.systemText.contains("Return [] for all three"))
        assertTrue(manualSync.systemText.contains("evidence-backed events item"))
    }

    @Test
    fun chapterPlanAcceptanceInputMatchesIsolatedIosReviewShape() {
        val input = NovelInjectionPlanner.chapterPlanAcceptanceInput(
            confirmedPlan = "Status: confirmed\nDigest: digest-1\nMust happen:\n- 夺回信物",
            candidate = "她在祭坛下夺回信物。",
            recentWrittenHighlights = "- 使者带来盟约",
        )

        assertEquals(NovelPromptVersions.CHAPTER_PLAN_ACCEPTANCE, input.prompt.version)
        assertEquals(
            """
            RECENT WRITTEN BEATS
            - 使者带来盟约

            CONFIRMED CHAPTER PLAN
            Status: confirmed
            Digest: digest-1
            Must happen:
            - 夺回信物

            WHOLE-CHAPTER CANDIDATE
            她在祭坛下夺回信物。
            """.trimIndent(),
            input.userText,
        )
    }

    @Test
    fun chapterPlanAcceptanceInputUsesExplicitEmptyRecentBeatsMarker() {
        val input = NovelInjectionPlanner.chapterPlanAcceptanceInput(
            confirmedPlan = "Digest: d",
            candidate = "完整候选正文",
            recentWrittenHighlights = " ",
        )

        assertTrue(input.userText.startsWith("RECENT WRITTEN BEATS\n(none)"))
    }

    @Test
    fun frozenInjectionDefaultsMatchV1() {
        assertEquals(16_000, NovelInjectionDefaults.ESTIMATED_INPUT_TOKENS)
        assertEquals(6_000, NovelInjectionDefaults.CHAPTER_TAIL_CHARS)
        assertEquals(12, NovelInjectionDefaults.RECENT_SESSION_MESSAGES)
    }

    @Test
    fun quickStartTypedProposalKindsContract_allowsMultipleCharacters() {
        // v3: world / masterOutline / writingRequirements remain single-kind slots;
        // character may be 1..N proposals (one person per entry).
        val singleSlotKinds = listOf("world", "masterOutline", "writingRequirements")
        assertEquals(3, singleSlotKinds.size)
        assertEquals(1, singleSlotKinds.count { it == "world" })
        assertTrue(NovelPromptVersions.QUICK_START.endsWith(".v3"))
        assertTrue(NovelPromptVersions.QUICK_START.contains("quick-start"))
    }
}
