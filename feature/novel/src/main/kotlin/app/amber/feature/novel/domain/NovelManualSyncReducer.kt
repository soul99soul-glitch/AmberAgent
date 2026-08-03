package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchCheckpointRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCheckpointKind
import app.amber.feature.novel.model.NovelEventId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOperationKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProposalId
import app.amber.feature.novel.model.NovelSessionCursor
import app.amber.feature.novel.model.NovelSettingProposalOrigin
import app.amber.feature.novel.model.NovelSettingProposalRecord
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.model.NovelStateSnapshotRecord
import app.amber.feature.novel.model.NovelStoryEventRecord
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import java.time.Instant

/**
 * Commit a manualSync checkpoint after needsSync.
 * Rebuild uses optional model-produced state delta over concatenated working manuscript;
 * if delta is null, reuses current summary and clears only via empty event append.
 */
object NovelManualSyncReducer {
    fun sync(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        operationId: NovelOperationId = NovelOperationId.generate(),
        expectedProjectRevision: Long,
        expectedBranchHeadRevision: Long,
        stateDelta: NovelStateDeltaV1?,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        if (projectId != document.project.id) throw NovelError.ProjectNotFound(projectId)
        if (expectedProjectRevision != document.project.revision) {
            throw NovelError.StaleProjectRevision(expectedProjectRevision, document.project.revision)
        }
        val branchIndex = document.branches.indexOfFirst { it.id == branchId }
        if (branchIndex < 0) throw NovelError.BranchNotFound(branchId)
        val branch = document.branches[branchIndex]
        if (branch.headRevision != expectedBranchHeadRevision) {
            throw NovelError.StaleBranchHeadRevision(expectedBranchHeadRevision, branch.headRevision)
        }
        if (branch.syncStatus != NovelBranchSyncStatus.NeedsSync) {
            throw NovelError.InvalidInput("Branch does not need sync.")
        }
        if (branch.activeRunID != null) throw NovelError.ProjectBusy(projectId)

        val baseState = document.stateSnapshots.first { it.id == branch.currentStateSnapshotID }
        val newEvents = stateDelta?.events?.mapIndexed { index, e ->
            NovelStoryEventRecord(
                id = NovelEventId.generate(),
                sequence = (baseState.eventIDs.size + index + 1).toLong(),
                kind = e.kind,
                summary = e.summary,
                entityReferences = e.entityReferences,
                createdAt = now,
            )
        }.orEmpty()
        val proposals = stateDelta?.settingProposals?.map { p ->
            NovelSettingProposalRecord(
                id = NovelProposalId.generate(),
                branchID = branchId,
                title = p.title,
                content = p.content,
                createdAt = now,
                isResolved = false,
                origin = NovelSettingProposalOrigin.DerivedState,
            )
        }.orEmpty()
        val newState = NovelStateSnapshotRecord(
            id = NovelStateSnapshotId.generate(),
            eventIDs = baseState.eventIDs + newEvents.map { it.id },
            summary = stateDelta?.stateSummary ?: baseState.summary.ifBlank { "Synced after manual edit." },
            branchOutline = stateDelta?.branchOutlinePatch ?: baseState.branchOutline,
            unresolvedEntityNames = stateDelta?.unresolvedEntityNames ?: baseState.unresolvedEntityNames,
            settingProposalIDs = baseState.settingProposalIDs + proposals.map { it.id },
            createdAt = now,
        )
        val session = document.sessions.first { it.id == branch.sessionID }
        val cursor = if (session.messages.isEmpty()) {
            NovelSessionCursor.Empty
        } else {
            NovelSessionCursor.Through(session.messages.maxOf { it.sequence })
        }
        val checkpoint = NovelBranchCheckpointRecord(
            id = NovelCheckpointId.generate(),
            kind = NovelCheckpointKind.ManualSync,
            createdOnBranchID = branchId,
            parentCheckpointID = branch.headCheckpointID,
            chapterSelections = branch.workingChapterSelections,
            stateSnapshotID = newState.id,
            sessionCursor = cursor,
            branchOverrideRevisionIDs = branch.overrideRevisionIDs,
            sourceCandidateID = null,
            baseHeadRevision = branch.headRevision,
            operationID = operationId,
            createdAt = now,
        )
        val branches = document.branches.toMutableList()
        branches[branchIndex] = branch.copy(
            headCheckpointID = checkpoint.id,
            currentStateSnapshotID = newState.id,
            headRevision = branch.headRevision + 1,
            workingRevision = branch.workingRevision + 1,
            syncStatus = NovelBranchSyncStatus.Synchronized,
            updatedAt = now,
        )
        val project = document.project.copy(revision = document.project.revision + 1, updatedAt = now)
        val outcome = NovelOutcome.ManualSyncCommitted(
            projectID = projectId,
            branchID = branchId,
            checkpointID = checkpoint.id,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
            events = document.events + newEvents,
            stateSnapshots = document.stateSnapshots + newState,
            checkpoints = document.checkpoints + checkpoint,
            settingProposals = document.settingProposals + proposals,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.SyncManualEdits,
                payloadSHA256 = sha256HexOfUtf8("manualSync:${branchId.rawValue}:${checkpoint.id.rawValue}"),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun workingManuscript(document: NovelProjectDocumentV1, branchId: app.amber.feature.novel.model.NovelBranchId): String {
        return workingManuscriptChunks(document, branchId).joinToString("\n\n")
    }

    /**
     * Split working manuscript into chapter-sized chunks for multi-pass state rebuild.
     * Single empty chapter still yields one empty chunk so callers see length ≥ 1 when
     * there is at least a working selection.
     */
    fun workingManuscriptChunks(
        document: NovelProjectDocumentV1,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS,
    ): List<String> {
        val branch = document.branches.first { it.id == branchId }
        if (branch.workingChapterSelections.isEmpty()) return emptyList()
        val chapterTexts = branch.workingChapterSelections.map { sel ->
            val version = document.chapterVersions.firstOrNull { it.id == sel.versionID }
            "## ${version?.title.orEmpty()}\n\n${version?.content.orEmpty()}"
        }
        // Prefer one chapter per chunk; if a single chapter exceeds budget, hard-split.
        val chunks = mutableListOf<String>()
        for (chapter in chapterTexts) {
            if (chapter.length <= maxChunkChars) {
                chunks += chapter
            } else {
                var offset = 0
                while (offset < chapter.length) {
                    val end = (offset + maxChunkChars).coerceAtMost(chapter.length)
                    chunks += chapter.substring(offset, end)
                    offset = end
                }
            }
        }
        return chunks
    }

    fun modelInputForChunk(chunk: String, index: Int, total: Int): String = buildString {
        appendLine("MANUAL SYNC CHUNK ${index + 1}/$total")
        appendLine(
            "Extract only story-state changes evidenced in CURRENT MANUSCRIPT CHUNK. " +
                "Do not invent facts from prior chunks.",
        )
        appendLine()
        append(chunk)
    }

    /**
     * Merge multi-chunk state deltas into one commit-ready delta.
     * Last non-blank summary/outline wins; events & proposals are concatenated.
     */
    fun mergeChunkDeltas(deltas: List<NovelStateDeltaV1>): NovelStateDeltaV1? {
        if (deltas.isEmpty()) return null
        if (deltas.size == 1) return deltas.single()
        val summary = deltas.map { it.stateSummary.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
            .ifBlank { deltas.last().stateSummary }
        val outline = deltas.mapNotNull { it.branchOutlinePatch?.trim()?.takeIf { p -> p.isNotEmpty() } }
            .lastOrNull()
        val events = deltas.flatMap { it.events }
        val proposals = deltas.flatMap { it.settingProposals }
        val unresolved = deltas.flatMap { it.unresolvedEntityNames }.distinct()
        return NovelStateDeltaV1(
            schemaVersion = 1,
            stateSummary = summary.ifBlank { "Synced after manual edit." },
            events = events,
            characterChanges = deltas.flatMap { it.characterChanges },
            relationshipChanges = deltas.flatMap { it.relationshipChanges },
            foreshadowingChanges = deltas.flatMap { it.foreshadowingChanges },
            unresolvedEntityNames = unresolved,
            branchOutlinePatch = outline,
            settingProposals = proposals,
        )
    }

    const val DEFAULT_MAX_CHUNK_CHARS: Int = 12_000
}
