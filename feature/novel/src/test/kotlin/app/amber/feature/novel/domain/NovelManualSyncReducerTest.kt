package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchCheckpointRecord
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterRecord
import app.amber.feature.novel.model.NovelChapterSelection
import app.amber.feature.novel.model.NovelChapterVersionId
import app.amber.feature.novel.model.NovelChapterVersionKind
import app.amber.feature.novel.model.NovelChapterVersionRecord
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelStateSnapshotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class NovelManualSyncReducerTest {
    @Test
    fun appendOnlyHeadSuffix_returnsOnlyOneChapterAfterExactPrefix() {
        val fixture = fixture()
        assertEquals(
            listOf("## 第二章\n\n新增正文"),
            NovelManualSyncReducer.appendOnlyHeadSuffixChunks(
                fixture.document,
                fixture.branchID,
            ),
        )
    }

    @Test
    fun appendOnlyHeadSuffix_failsClosedForNonAppendAndMalformedDocuments() {
        val fixture = fixture()
        val document = fixture.document
        val branch = document.branches.single()
        val parent = document.checkpoints.first { it.id == fixture.parentID }
        val head = document.checkpoints.first { it.id == fixture.headID }
        val a = parent.chapterSelections.single()
        val b = head.chapterSelections.last()
        val rewrittenA = NovelChapterSelection(a.chapterID, NovelChapterVersionId.generate())
        val c = NovelChapterSelection(NovelChapterId.generate(), NovelChapterVersionId.generate())

        val malformed = listOf(
            document.copy(
                branches = listOf(branch.copy(workingChapterSelections = listOf(a))),
                checkpoints = listOf(parent, head.copy(chapterSelections = listOf(a))),
            ),
            document.copy(
                branches = listOf(branch.copy(workingChapterSelections = listOf(rewrittenA, b))),
                checkpoints = listOf(parent, head.copy(chapterSelections = listOf(rewrittenA, b))),
            ),
            document.copy(
                branches = listOf(branch.copy(workingChapterSelections = listOf(a))),
                checkpoints = listOf(
                    parent.copy(chapterSelections = listOf(a, b)),
                    head.copy(chapterSelections = listOf(a)),
                ),
            ),
            document.copy(
                branches = listOf(branch.copy(workingChapterSelections = listOf(a, b, c))),
                checkpoints = listOf(parent, head.copy(chapterSelections = listOf(a, b, c))),
            ),
            document.copy(branches = listOf(branch.copy(headCheckpointID = NovelCheckpointId.generate()))),
            document.copy(checkpoints = listOf(parent, head.copy(parentCheckpointID = null))),
            document.copy(
                checkpoints = listOf(
                    parent,
                    head.copy(parentCheckpointID = NovelCheckpointId.generate()),
                ),
            ),
            document.copy(
                checkpoints = listOf(parent, head.copy(chapterSelections = listOf(a))),
            ),
            document.copy(
                chapterVersions = document.chapterVersions.filterNot { it.id == b.versionID },
            ),
            document.copy(
                chapterVersions = document.chapterVersions.map {
                    if (it.id == b.versionID) it.copy(chapterID = a.chapterID) else it
                },
            ),
        )
        malformed.forEach { candidate ->
            assertNull(
                NovelManualSyncReducer.appendOnlyHeadSuffixChunks(
                    candidate,
                    fixture.branchID,
                ),
            )
        }
        assertNull(
            NovelManualSyncReducer.appendOnlyHeadSuffixChunks(
                document,
                fixture.branchID,
                maxChunkChars = 0,
            ),
        )
    }

    @Test
    fun appendOnlyHeadSuffix_singleSelectionMaySplitIntoSeveralChunks() {
        val fixture = fixture(secondTitle = "B", secondContent = "abcdef")
        assertEquals(
            listOf("## B\n\n", "abcdef"),
            NovelManualSyncReducer.appendOnlyHeadSuffixChunks(
                fixture.document,
                fixture.branchID,
                maxChunkChars = 6,
            ),
        )
    }

    @Test
    fun replayApplied_recoversCrashAfterSyncWithoutDuplicatingRecords() {
        val fixture = syncReadyFixture()
        val operationID = NovelOperationId.generate()
        val checkpointID = NovelCheckpointId.generate()
        val stateSnapshotID = NovelStateSnapshotId.generate()
        val first = sync(
            fixture = fixture,
            operationID = operationID,
            checkpointID = checkpointID,
            stateSnapshotID = stateSnapshotID,
        )

        val replayedOutcome = NovelManualSyncReducer.replayApplied(
            projectId = first.document.project.id,
            branchId = fixture.branchID,
            operationId = operationID,
            newCheckpointId = checkpointID,
            newStateSnapshotId = stateSnapshotID,
            document = first.document,
        )

        assertEquals(first.outcome, replayedOutcome)
        assertEquals(1, first.document.appliedOperations.count { it.operationID == operationID })
        assertEquals(1, first.document.checkpoints.count { it.id == checkpointID })
        assertEquals(1, first.document.stateSnapshots.count { it.id == stateSnapshotID })
        assertEquals(1, first.document.events.count { it.summary == "主角找到钥匙" })
        assertEquals(1, first.document.settingProposals.count { it.title == "旧门规则" })
    }

    @Test
    fun sync_sameOperationWithConflictingPayload_failsClosed() {
        val fixture = syncReadyFixture()
        val operationID = NovelOperationId.generate()
        val checkpointID = NovelCheckpointId.generate()
        val stateSnapshotID = NovelStateSnapshotId.generate()
        val first = sync(
            fixture = fixture,
            operationID = operationID,
            checkpointID = checkpointID,
            stateSnapshotID = stateSnapshotID,
        )

        val error = runCatching {
            NovelManualSyncReducer.sync(
                projectId = first.document.project.id,
                branchId = fixture.branchID,
                operationId = operationID,
                newCheckpointId = checkpointID,
                newStateSnapshotId = stateSnapshotID,
                expectedProjectRevision = first.document.project.revision,
                expectedConfigRevision = first.document.project.configRevision,
                expectedBranchHeadRevision = first.document.branches.single().headRevision,
                stateDelta = stateDelta().copy(stateSummary = "冲突状态"),
                document = first.document,
                now = NOW.plusSeconds(2),
            )
        }.exceptionOrNull()
        val identityError = runCatching {
            NovelManualSyncReducer.replayApplied(
                projectId = first.document.project.id,
                branchId = fixture.branchID,
                operationId = operationID,
                newCheckpointId = checkpointID,
                newStateSnapshotId = NovelStateSnapshotId.generate(),
                document = first.document,
            )
        }.exceptionOrNull()

        assertTrue(error is NovelError.IdempotencyConflict)
        assertTrue(identityError is NovelError.IdempotencyConflict)
    }

    @Test
    fun sync_exactReplay_precedesAllStaleRevisionChecks() {
        val fixture = syncReadyFixture()
        val operationID = NovelOperationId.generate()
        val checkpointID = NovelCheckpointId.generate()
        val stateSnapshotID = NovelStateSnapshotId.generate()
        val first = sync(
            fixture = fixture,
            operationID = operationID,
            checkpointID = checkpointID,
            stateSnapshotID = stateSnapshotID,
        )

        val replayed = NovelManualSyncReducer.sync(
            projectId = first.document.project.id,
            branchId = fixture.branchID,
            operationId = operationID,
            newCheckpointId = checkpointID,
            newStateSnapshotId = stateSnapshotID,
            expectedProjectRevision = Long.MIN_VALUE,
            expectedConfigRevision = Long.MIN_VALUE,
            expectedBranchHeadRevision = Long.MIN_VALUE,
            stateDelta = stateDelta(),
            document = first.document,
            now = NOW.plusSeconds(3),
        )

        assertEquals(first.document, replayed.document)
        assertEquals(first.outcome, replayed.outcome)
    }

    private fun sync(
        fixture: SuffixFixture,
        operationID: NovelOperationId,
        checkpointID: NovelCheckpointId,
        stateSnapshotID: NovelStateSnapshotId,
    ): NovelReduceResult = NovelManualSyncReducer.sync(
        projectId = fixture.document.project.id,
        branchId = fixture.branchID,
        operationId = operationID,
        newCheckpointId = checkpointID,
        newStateSnapshotId = stateSnapshotID,
        expectedProjectRevision = fixture.document.project.revision,
        expectedConfigRevision = fixture.document.project.configRevision,
        expectedBranchHeadRevision = fixture.document.branches.single().headRevision,
        stateDelta = stateDelta(),
        document = fixture.document,
        now = NOW.plusSeconds(1),
    )

    private fun stateDelta(): NovelStateDeltaV1 = NovelStateDeltaV1(
        schemaVersion = 1,
        stateSummary = "主角拿到打开旧门的钥匙",
        events = listOf(
            NovelStateEventV1(
                id = "event-1",
                kind = "discovery",
                summary = "主角找到钥匙",
                entityReferences = listOf("主角", "钥匙"),
                evidence = "新增正文",
            ),
        ),
        unresolvedEntityNames = listOf("旧门"),
        branchOutlinePatch = "主角将前往旧门",
        settingProposals = listOf(
            NovelSettingProposalV1(
                id = "proposal-1",
                title = "旧门规则",
                content = "钥匙只能使用一次",
                evidence = "新增正文",
            ),
        ),
    )

    private fun syncReadyFixture(): SuffixFixture {
        val fixture = fixture()
        val branch = fixture.document.branches.single()
        return fixture.copy(
            document = fixture.document.copy(
                branches = listOf(branch.copy(syncStatus = NovelBranchSyncStatus.NeedsSync)),
            ),
        )
    }

    private fun fixture(
        secondTitle: String = "第二章",
        secondContent: String = "新增正文",
    ): SuffixFixture {
        val created = NovelReducer.createProject(
            NovelCreateProjectCommand(
                context = NovelMutationContext(NovelOperationId.generate()),
                projectID = NovelProjectId.generate(),
                branchID = NovelBranchId.generate(),
                sessionID = NovelSessionId.generate(),
                initialStateSnapshotID = NovelStateSnapshotId.generate(),
                initialCheckpointID = NovelCheckpointId.generate(),
                name = "Suffix Fixture",
                creationMode = NovelProjectCreationMode.Blank,
            ),
            NOW,
        ).document
        val branch = created.branches.single()
        val originalCheckpoint = created.checkpoints.single()
        val chapterA = NovelChapterRecord(NovelChapterId.generate(), NOW)
        val chapterB = NovelChapterRecord(NovelChapterId.generate(), NOW)
        val versionA = chapterVersion(chapterA.id, "第一章", "已有正文")
        val versionB = chapterVersion(chapterB.id, secondTitle, secondContent)
        val selectionA = NovelChapterSelection(chapterA.id, versionA.id)
        val selectionB = NovelChapterSelection(chapterB.id, versionB.id)
        val parent = originalCheckpoint.copy(chapterSelections = listOf(selectionA))
        val head = NovelBranchCheckpointRecord(
            id = NovelCheckpointId.generate(),
            kind = originalCheckpoint.kind,
            createdOnBranchID = branch.id,
            parentCheckpointID = parent.id,
            chapterSelections = listOf(selectionA, selectionB),
            stateSnapshotID = branch.currentStateSnapshotID,
            sessionCursor = originalCheckpoint.sessionCursor,
            baseHeadRevision = branch.headRevision,
            operationID = NovelOperationId.generate(),
            createdAt = NOW.plusSeconds(1),
        )
        val document = created.copy(
            branches = listOf(
                branch.copy(
                    headCheckpointID = head.id,
                    headRevision = branch.headRevision + 1,
                    workingRevision = branch.workingRevision + 1,
                    workingChapterSelections = head.chapterSelections,
                    updatedAt = NOW.plusSeconds(1),
                ),
            ),
            chapters = listOf(chapterA, chapterB),
            chapterVersions = listOf(versionA, versionB),
            checkpoints = listOf(parent, head),
        )
        return SuffixFixture(document, branch.id, parent.id, head.id)
    }

    private fun chapterVersion(
        chapterID: NovelChapterId,
        title: String,
        content: String,
    ): NovelChapterVersionRecord = NovelChapterVersionRecord(
        id = NovelChapterVersionId.generate(),
        chapterID = chapterID,
        kind = NovelChapterVersionKind.Collected,
        title = title,
        content = content,
        factCompatibilityID = UUID.randomUUID(),
        createdAt = NOW,
        operationID = NovelOperationId.generate(),
    )

    private data class SuffixFixture(
        val document: NovelProjectDocumentV1,
        val branchID: NovelBranchId,
        val parentID: NovelCheckpointId,
        val headID: NovelCheckpointId,
    )

    companion object {
        private val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
    }
}
