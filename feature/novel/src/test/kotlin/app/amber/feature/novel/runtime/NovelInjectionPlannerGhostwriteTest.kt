package app.amber.feature.novel.runtime

import app.amber.feature.novel.domain.NovelCreateProjectCommand
import app.amber.feature.novel.domain.NovelMutationContext
import app.amber.feature.novel.domain.NovelReducer
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelChapterPlanRecord
import app.amber.feature.novel.model.NovelChapterPlanStatus
import app.amber.feature.novel.model.NovelChapterRecord
import app.amber.feature.novel.model.NovelChapterSelection
import app.amber.feature.novel.model.NovelChapterVersionId
import app.amber.feature.novel.model.NovelChapterVersionKind
import app.amber.feature.novel.model.NovelChapterVersionRecord
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelInjectionMode
import app.amber.feature.novel.model.NovelInjectionSectionKind
import app.amber.feature.novel.model.NovelInjectionSelectionReason
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelMaterialRecord
import app.amber.feature.novel.model.NovelMaterialRevisionId
import app.amber.feature.novel.model.NovelMaterialRevisionRecord
import app.amber.feature.novel.model.NovelMessageId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelSessionMessageKind
import app.amber.feature.novel.model.NovelSessionMessageRecord
import app.amber.feature.novel.model.NovelSessionMode
import app.amber.feature.novel.model.NovelSessionRole
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.model.NovelUpcomingArcRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class NovelInjectionPlannerGhostwriteTest {
    @Test
    fun manualSyncIsAllowedWhileBranchNeedsSyncButFormalGenerationRemainsBlocked() {
        val document = ghostwriteDocument().let { base ->
            base.copy(
                branches = listOf(
                    base.branches.single().copy(syncStatus = NovelBranchSyncStatus.NeedsSync),
                ),
            )
        }
        val branch = document.branches.single()

        val syncPlan = NovelInjectionPlanner.plan(
            document = document,
            branchId = branch.id,
            promptKind = NovelPromptKind.ManualSyncV1,
            userText = "同步当前手稿分块",
        )

        assertEquals(NovelPromptKind.ManualSyncV1, syncPlan.prompt.kind)
        assertEquals(NovelPromptVersions.MANUAL_SYNC, syncPlan.prompt.version)
        assertTrue(
            runCatching {
                NovelInjectionPlanner.plan(
                    document = document,
                    branchId = branch.id,
                    promptKind = NovelPromptKind.ProseWholeChapter,
                    userText = "写完整下一章",
                )
            }.isFailure,
        )
    }

    @Test
    fun wholeChapterInjectsConfirmedPlanDigestRecentBeatsAndUpcomingArc() {
        val document = ghostwriteDocument()
        val branch = document.branches.single()

        val plan = NovelInjectionPlanner.plan(
            document = document,
            branchId = branch.id,
            promptKind = NovelPromptKind.ProseWholeChapter,
            userText = "写完整下一章",
        )

        val chapterPlan = plan.sections.single { it.kind is NovelInjectionSectionKind.ChapterPlan }
        assertEquals(NovelInjectionSelectionReason.ConfirmedChapterPlan, chapterPlan.reason)
        assertTrue(chapterPlan.label.contains("BINDING OBLIGATIONS"))
        assertTrue(chapterPlan.content.contains("Digest: ${document.chapterPlans.single().contentDigest}"))
        assertTrue(chapterPlan.content.contains("Must happen"))

        val recent = plan.sections.single {
            it.kind is NovelInjectionSectionKind.RecentWrittenHighlights
        }
        assertEquals(NovelInjectionSelectionReason.RecentWrittenHighlights, recent.reason)
        assertTrue(recent.label.contains("DO NOT REHASH"))
        assertTrue(recent.content.contains("祭坛下找到信物"))

        val arc = plan.sections.single { it.kind is NovelInjectionSectionKind.UpcomingArc }
        assertEquals(NovelInjectionSelectionReason.UpcomingArc, arc.reason)
        assertTrue(arc.label.contains("SOFT DIRECTION"))
        assertTrue(arc.content.contains("使者身份曝光"))
        assertTrue(plan.contextText.contains("CONFIRMED CHAPTER PLAN"))
        assertTrue(plan.contextText.contains("RECENT WRITTEN BEATS"))
        assertTrue(plan.contextText.contains("UPCOMING ARC"))
    }

    @Test
    fun continuationDoesNotInjectGhostwritePlanningSections() {
        val document = ghostwriteDocument()
        val branch = document.branches.single()

        val plan = NovelInjectionPlanner.plan(
            document = document,
            branchId = branch.id,
            promptKind = NovelPromptKind.ProseContinuation,
            userText = "续写一段",
        )

        assertFalse(plan.sections.any { it.kind is NovelInjectionSectionKind.ChapterPlan })
        assertFalse(
            plan.sections.any { it.kind is NovelInjectionSectionKind.RecentWrittenHighlights },
        )
        assertFalse(plan.sections.any { it.kind is NovelInjectionSectionKind.UpcomingArc })
        assertFalse(plan.contextText.contains("BINDING OBLIGATIONS"))
        assertFalse(plan.contextText.contains("DO NOT REHASH"))
        assertFalse(plan.contextText.contains("SOFT DIRECTION"))
    }

    @Test
    fun wholeChapterDoesNotInjectDraftPlanOrBlankOptionalDirections() {
        val document = ghostwriteDocument().let { base ->
            base.copy(
                chapterPlans = listOf(
                    base.chapterPlans.single().copy(status = NovelChapterPlanStatus.Draft),
                ),
                upcomingArcs = listOf(base.upcomingArcs.single().copy(beats = emptyList())),
                stateSnapshots = listOf(
                    base.stateSnapshots.single().copy(recentWrittenHighlights = emptyList()),
                ),
            )
        }
        val plan = NovelInjectionPlanner.plan(
            document = document,
            branchId = document.branches.single().id,
            promptKind = NovelPromptKind.ProseWholeChapter,
            userText = "写完整下一章",
        )

        assertFalse(plan.sections.any { it.kind is NovelInjectionSectionKind.ChapterPlan })
        assertFalse(
            plan.sections.any { it.kind is NovelInjectionSectionKind.RecentWrittenHighlights },
        )
        assertFalse(plan.sections.any { it.kind is NovelInjectionSectionKind.UpcomingArc })
    }

    @Test
    fun chapterPlanProposalInputUsesOnlyBoundedCanonicalSources() {
        val document = canonicalContextDocument()
        val input = NovelInjectionPlanner.chapterPlanProposalInput(
            document = document,
            branchId = document.branches.single().id,
            nextOrdinal = 3,
            previousPlanSummary = "Placement: 第 2 章\nGoal: 试探",
        )

        assertEquals(NovelPromptKind.ChapterPlanProposalV1, input.prompt.kind)
        assertEquals(NovelPromptVersions.CHAPTER_PLAN_PROPOSAL, input.prompt.version)
        assertTrue(input.userText.contains("NEXT CHAPTER ORDINAL\n3"))
        assertTrue(input.userText.contains("MASTER OUTLINE\n总纲\n追查信物来源"))
        assertTrue(input.userText.contains("WRITING REQUIREMENTS\n文风\n第三人称限知"))
        assertTrue(input.userText.contains("CURRENT STORY STATE"))
        assertTrue(input.userText.contains("当前追到港口"))
        assertTrue(input.userText.contains("Recent written beats:"))
        assertTrue(input.userText.contains("祭坛下找到信物"))
        assertTrue(input.userText.contains("UPCOMING ARC"))
        assertTrue(input.userText.contains("使者身份曝光"))
        assertTrue(input.userText.contains("PREVIOUS CHAPTER PLAN SUMMARY"))
        assertTrue(input.userText.contains("CANON CHAPTER COUNT ON BRANCH\n1"))
        assertFalse(input.userText.contains("失败候选旧讨论，不得注入"))
        assertFalse(input.userText.contains("已收录章唯一正文"))
    }

    @Test
    fun ghostwriteWholeChapterAndManualSyncExcludePollutingSessionContext() {
        val canonical = canonicalContextDocument()
        val ghostwrite = canonical.copy(
            project = canonical.project.copy(collaborationMode = NovelCollaborationMode.Ghostwrite),
        )
        val branch = ghostwrite.branches.single()

        val wholeChapter = NovelInjectionPlanner.plan(
            document = ghostwrite,
            branchId = branch.id,
            promptKind = NovelPromptKind.ProseWholeChapter,
            userText = "写完整下一章",
        )
        assertFalse(wholeChapter.sections.any { it.kind is NovelInjectionSectionKind.SessionMessage })
        assertFalse(wholeChapter.contextText.contains("失败候选旧讨论，不得注入"))
        assertTrue(wholeChapter.sections.any { it.kind is NovelInjectionSectionKind.ChapterContext })

        val cocreation = NovelInjectionPlanner.plan(
            document = canonical,
            branchId = canonical.branches.single().id,
            promptKind = NovelPromptKind.ProseWholeChapter,
            userText = "写完整下一章",
        )
        assertTrue(cocreation.sections.any { it.kind is NovelInjectionSectionKind.SessionMessage })

        val needsSync = ghostwrite.copy(
            branches = listOf(branch.copy(syncStatus = NovelBranchSyncStatus.NeedsSync)),
        )
        val manualSync = NovelInjectionPlanner.plan(
            document = needsSync,
            branchId = branch.id,
            promptKind = NovelPromptKind.ManualSyncV1,
            userText = "只同步显式传入的当前分块",
        )
        assertFalse(manualSync.sections.any { it.kind is NovelInjectionSectionKind.SessionMessage })
        assertFalse(manualSync.sections.any { it.kind is NovelInjectionSectionKind.ChapterContext })
        assertFalse(manualSync.contextText.contains("失败候选旧讨论，不得注入"))
        assertFalse(manualSync.contextText.contains("已收录章唯一正文"))
        assertTrue(manualSync.sections.any { it.kind is NovelInjectionSectionKind.FixedPrompt })
        assertTrue(manualSync.sections.any { it.kind is NovelInjectionSectionKind.CurrentState })
        assertTrue(manualSync.sections.any { it.kind is NovelInjectionSectionKind.Material })
        assertTrue(manualSync.sections.any { it.kind is NovelInjectionSectionKind.UserInput })
        assertTrue(
            manualSync.sections.all {
                it.kind is NovelInjectionSectionKind.FixedPrompt ||
                    it.kind is NovelInjectionSectionKind.CurrentState ||
                    it.kind is NovelInjectionSectionKind.Material ||
                    it.kind is NovelInjectionSectionKind.UserInput
            },
        )
    }

    private fun ghostwriteDocument(): app.amber.feature.novel.model.NovelProjectDocumentV1 {
        val created = NovelReducer.createProject(
            NovelCreateProjectCommand(
                context = NovelMutationContext(operationID = NovelOperationId.generate()),
                projectID = NovelProjectId.generate(),
                branchID = NovelBranchId.generate(),
                sessionID = NovelSessionId.generate(),
                initialStateSnapshotID = NovelStateSnapshotId.generate(),
                initialCheckpointID = NovelCheckpointId.generate(),
                name = "Ghostwrite",
                creationMode = NovelProjectCreationMode.Blank,
            ),
            now = Instant.parse("2026-08-09T00:00:00Z"),
        ).document
        val branch = created.branches.single()
        val draftPlan = NovelChapterPlanRecord(
            id = NovelChapterPlanId.generate(),
            branchID = branch.id,
            status = NovelChapterPlanStatus.Confirmed,
            outlinePlacement = "第 4 章 · 中段",
            goalAndConflict = "揭露使者身份并逼主角表态",
            mustHappen = listOf("使者身份被当众揭穿"),
            mustNotHappen = listOf("主角死亡"),
            endingHook = "门外响起脚步声",
            visibleFacts = listOf("主角已经知道信封来源"),
            contentDigest = "",
            updatedAt = Instant.parse("2026-08-09T00:00:01Z"),
            confirmedAt = Instant.parse("2026-08-09T00:00:01Z"),
        )
        val confirmedPlan = draftPlan.copy(
            contentDigest = NovelChapterPlanRecord.digest(draftPlan.canonicalDigestPayload()),
        )
        return created.copy(
            stateSnapshots = listOf(
                created.stateSnapshots.single().copy(
                    recentWrittenHighlights = listOf("祭坛下找到信物", "使者带来盟约"),
                ),
            ),
            chapterPlans = listOf(confirmedPlan),
            upcomingArcs = listOf(
                NovelUpcomingArcRecord(
                    branchID = branch.id,
                    beats = listOf("使者身份曝光", "夺回信物"),
                    updatedAt = Instant.parse("2026-08-09T00:00:01Z"),
                ),
            ),
        )
    }

    private fun canonicalContextDocument(): app.amber.feature.novel.model.NovelProjectDocumentV1 {
        val base = ghostwriteDocument()
        val now = Instant.parse("2026-08-09T00:00:02Z")
        val branch = base.branches.single()
        val chapterID = NovelChapterId.generate()
        val versionID = NovelChapterVersionId.generate()
        val chapter = NovelChapterRecord(id = chapterID, createdAt = now)
        val version = NovelChapterVersionRecord(
            id = versionID,
            chapterID = chapterID,
            kind = NovelChapterVersionKind.Collected,
            title = "第 1 章",
            content = "已收录章唯一正文",
            factCompatibilityID = UUID.randomUUID(),
            createdAt = now,
            operationID = NovelOperationId.generate(),
        )

        fun material(
            kind: NovelMaterialKind,
            title: String,
            content: String,
        ): Pair<NovelMaterialRecord, NovelMaterialRevisionRecord> {
            val materialID = NovelMaterialId.generate()
            val revisionID = NovelMaterialRevisionId.generate()
            return NovelMaterialRecord(
                id = materialID,
                kind = kind,
                currentRevisionID = revisionID,
                revisionIDs = listOf(revisionID),
            ) to NovelMaterialRevisionRecord(
                id = revisionID,
                materialID = materialID,
                revision = 1,
                title = title,
                content = content,
                tags = emptyList(),
                injectionMode = NovelInjectionMode.Always,
                createdAt = now,
                operationID = NovelOperationId.generate(),
            )
        }

        val outline = material(NovelMaterialKind.MasterOutline, "总纲", "追查信物来源")
        val requirements = material(NovelMaterialKind.WritingRequirements, "文风", "第三人称限知")
        val message = NovelSessionMessageRecord(
            id = NovelMessageId.generate(),
            sequence = 0,
            role = NovelSessionRole.Assistant,
            mode = NovelSessionMode.WriteProse,
            kind = NovelSessionMessageKind.ProseCandidate,
            content = "失败候选旧讨论，不得注入",
            createdAt = now,
        )

        return base.copy(
            branches = listOf(
                branch.copy(
                    workingChapterSelections = listOf(NovelChapterSelection(chapterID, versionID)),
                ),
            ),
            sessions = listOf(base.sessions.single().copy(revision = 1, messages = listOf(message))),
            chapters = listOf(chapter),
            chapterVersions = listOf(version),
            materials = listOf(outline.first, requirements.first),
            materialRevisions = listOf(outline.second, requirements.second),
            stateSnapshots = listOf(
                base.stateSnapshots.single().copy(
                    summary = "当前追到港口",
                    branchOutline = "下一步逼问使者",
                ),
            ),
        )
    }
}
