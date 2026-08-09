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
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/**
 * Commit a manualSync checkpoint after needsSync.
 * Rebuild uses optional model-produced state delta over concatenated working manuscript;
 * if delta is null, reuses current summary and clears only via empty event append.
 */
object NovelManualSyncReducer {
    /**
     * Fast path for durable orchestrators resuming after the sync commit succeeded but before
     * their own ledger advanced. This performs no provider work and accepts only the exact
     * caller-owned operation/checkpoint/state identities whose persisted payload is intact.
     */
    fun replayApplied(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        operationId: NovelOperationId,
        newCheckpointId: NovelCheckpointId,
        newStateSnapshotId: NovelStateSnapshotId,
        document: NovelProjectDocumentV1,
    ): NovelOutcome? {
        if (projectId != document.project.id) throw NovelError.ProjectNotFound(projectId)
        val replay = replayContext(
            projectId = projectId,
            branchId = branchId,
            operationId = operationId,
            newCheckpointId = newCheckpointId,
            newStateSnapshotId = newStateSnapshotId,
            document = document,
        ) ?: return null
        val persistedPayloadSHA256 = payloadSHA256(
            projectId = projectId,
            branchId = branchId,
            newCheckpointId = newCheckpointId,
            newStateSnapshotId = newStateSnapshotId,
            source = replay.source,
            effective = replay.persistedEffective,
        )
        if (persistedPayloadSHA256 != replay.applied.payloadSHA256) {
            throw NovelError.IdempotencyConflict(operationId)
        }
        return replay.applied.outcome
    }

    fun sync(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        operationId: NovelOperationId = NovelOperationId.generate(),
        newCheckpointId: NovelCheckpointId = NovelCheckpointId.generate(),
        newStateSnapshotId: NovelStateSnapshotId = NovelStateSnapshotId.generate(),
        expectedProjectRevision: Long,
        expectedConfigRevision: Long? = null,
        expectedBranchHeadRevision: Long,
        stateDelta: NovelStateDeltaV1?,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        if (projectId != document.project.id) throw NovelError.ProjectNotFound(projectId)
        replayContext(
            projectId = projectId,
            branchId = branchId,
            operationId = operationId,
            newCheckpointId = newCheckpointId,
            newStateSnapshotId = newStateSnapshotId,
            document = document,
        )?.let { replay ->
            val persistedPayloadSHA256 = payloadSHA256(
                projectId = projectId,
                branchId = branchId,
                newCheckpointId = newCheckpointId,
                newStateSnapshotId = newStateSnapshotId,
                source = replay.source,
                effective = replay.persistedEffective,
            )
            val callerPayloadSHA256 = payloadSHA256(
                projectId = projectId,
                branchId = branchId,
                newCheckpointId = newCheckpointId,
                newStateSnapshotId = newStateSnapshotId,
                source = replay.source,
                effective = effectivePayload(replay.source.baseState, stateDelta),
            )
            if (persistedPayloadSHA256 != replay.applied.payloadSHA256 ||
                callerPayloadSHA256 != replay.applied.payloadSHA256
            ) {
                throw NovelError.IdempotencyConflict(operationId)
            }
            return NovelReduceResult(document, replay.applied.outcome)
        }
        if (expectedProjectRevision != document.project.revision) {
            throw NovelError.StaleProjectRevision(expectedProjectRevision, document.project.revision)
        }
        if (expectedConfigRevision != null && expectedConfigRevision != document.project.configRevision) {
            throw NovelError.StaleConfigRevision(expectedConfigRevision, document.project.configRevision)
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
        val session = document.sessions.first { it.id == branch.sessionID }
        val cursor = if (session.messages.isEmpty()) {
            NovelSessionCursor.Empty
        } else {
            NovelSessionCursor.Through(session.messages.maxOf { it.sequence })
        }
        val source = SyncSourceFacts(
            baseProjectRevision = document.project.revision,
            baseCheckpointId = branch.headCheckpointID,
            baseHeadRevision = branch.headRevision,
            chapterSelections = branch.workingChapterSelections,
            baseState = baseState,
            sessionCursor = cursor,
            branchOverrideRevisionIds = branch.overrideRevisionIDs,
        )
        val effective = effectivePayload(baseState, stateDelta)
        val payloadSHA256 = payloadSHA256(
            projectId = projectId,
            branchId = branchId,
            newCheckpointId = newCheckpointId,
            newStateSnapshotId = newStateSnapshotId,
            source = source,
            effective = effective,
        )
        val newEvents = effective.events.mapIndexed { index, e ->
            NovelStoryEventRecord(
                id = NovelEventId.generate(),
                sequence = (baseState.eventIDs.size + index + 1).toLong(),
                kind = e.kind,
                summary = e.summary,
                entityReferences = e.entityReferences,
                createdAt = now,
            )
        }
        val proposals = effective.settingProposals.map { p ->
            NovelSettingProposalRecord(
                id = NovelProposalId.generate(),
                branchID = branchId,
                title = p.title,
                content = p.content,
                createdAt = now,
                isResolved = false,
                origin = NovelSettingProposalOrigin.DerivedState,
            )
        }
        val newState = NovelStateSnapshotRecord(
            id = newStateSnapshotId,
            eventIDs = baseState.eventIDs + newEvents.map { it.id },
            summary = effective.stateSummary,
            branchOutline = effective.branchOutline,
            unresolvedEntityNames = effective.unresolvedEntityNames,
            settingProposalIDs = baseState.settingProposalIDs + proposals.map { it.id },
            recentWrittenHighlights = NovelStateSnapshotRecord.mergedHighlights(
                prior = baseState.recentWrittenHighlights,
                newEventSummaries = newEvents.map { it.summary },
            ),
            createdAt = now,
        )
        val checkpoint = NovelBranchCheckpointRecord(
            id = newCheckpointId,
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
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    private fun replayContext(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        operationId: NovelOperationId,
        newCheckpointId: NovelCheckpointId,
        newStateSnapshotId: NovelStateSnapshotId,
        document: NovelProjectDocumentV1,
    ): ReplayContext? {
        fun conflict(): Nothing = throw NovelError.IdempotencyConflict(operationId)
        val applied = document.appliedOperations.firstOrNull { it.operationID == operationId } ?: run {
            if (document.checkpoints.any { it.id == newCheckpointId } ||
                document.stateSnapshots.any { it.id == newStateSnapshotId }
            ) {
                conflict()
            }
            return null
        }
        if (applied.kind != NovelOperationKind.SyncManualEdits) conflict()
        val outcome = applied.outcome as? NovelOutcome.ManualSyncCommitted ?: conflict()
        if (outcome.projectID != projectId || outcome.branchID != branchId ||
            outcome.checkpointID != newCheckpointId || outcome.revision != applied.appliedProjectRevision
        ) {
            conflict()
        }
        val checkpoint = document.checkpoints.firstOrNull { it.id == newCheckpointId } ?: conflict()
        if (checkpoint.kind != NovelCheckpointKind.ManualSync ||
            checkpoint.createdOnBranchID != branchId ||
            checkpoint.operationID != operationId ||
            checkpoint.stateSnapshotID != newStateSnapshotId ||
            checkpoint.sourceCandidateID != null
        ) {
            conflict()
        }
        val parentId = checkpoint.parentCheckpointID ?: conflict()
        val parent = document.checkpoints.firstOrNull { it.id == parentId } ?: conflict()
        val baseState = document.stateSnapshots.firstOrNull { it.id == parent.stateSnapshotID } ?: conflict()
        val targetState = document.stateSnapshots.firstOrNull { it.id == newStateSnapshotId } ?: conflict()
        if (targetState.eventIDs.take(baseState.eventIDs.size) != baseState.eventIDs ||
            targetState.settingProposalIDs.take(baseState.settingProposalIDs.size) != baseState.settingProposalIDs
        ) {
            conflict()
        }
        val eventIds = targetState.eventIDs.drop(baseState.eventIDs.size)
        val proposalIds = targetState.settingProposalIDs.drop(baseState.settingProposalIDs.size)
        val events = eventIds.mapIndexed { index, id ->
            val event = document.events.firstOrNull { it.id == id } ?: conflict()
            if (event.sequence != (baseState.eventIDs.size + index + 1).toLong()) conflict()
            EffectiveEvent(event.kind, event.summary, event.entityReferences)
        }
        val proposals = proposalIds.map { id ->
            val proposal = document.settingProposals.firstOrNull { it.id == id } ?: conflict()
            if (proposal.branchID != branchId ||
                proposal.origin != NovelSettingProposalOrigin.DerivedState
            ) {
                conflict()
            }
            EffectiveSettingProposal(proposal.title, proposal.content)
        }
        val expectedHighlights = NovelStateSnapshotRecord.mergedHighlights(
            prior = baseState.recentWrittenHighlights,
            newEventSummaries = events.map { it.summary },
        )
        if (targetState.recentWrittenHighlights != expectedHighlights ||
            applied.appliedProjectRevision <= 0L
        ) {
            conflict()
        }
        return ReplayContext(
            applied = applied,
            source = SyncSourceFacts(
                baseProjectRevision = applied.appliedProjectRevision - 1,
                baseCheckpointId = parentId,
                baseHeadRevision = checkpoint.baseHeadRevision,
                chapterSelections = checkpoint.chapterSelections,
                baseState = baseState,
                sessionCursor = checkpoint.sessionCursor,
                branchOverrideRevisionIds = checkpoint.branchOverrideRevisionIDs,
            ),
            persistedEffective = EffectiveSyncPayload(
                stateSummary = targetState.summary,
                branchOutline = targetState.branchOutline,
                unresolvedEntityNames = targetState.unresolvedEntityNames,
                events = events,
                settingProposals = proposals,
            ),
        )
    }

    private fun effectivePayload(
        baseState: NovelStateSnapshotRecord,
        stateDelta: NovelStateDeltaV1?,
    ): EffectiveSyncPayload = EffectiveSyncPayload(
        stateSummary = stateDelta?.stateSummary ?: baseState.summary.ifBlank { "Synced after manual edit." },
        branchOutline = stateDelta?.branchOutlinePatch ?: baseState.branchOutline,
        unresolvedEntityNames = stateDelta?.unresolvedEntityNames ?: baseState.unresolvedEntityNames,
        events = stateDelta?.events?.map {
            EffectiveEvent(it.kind, it.summary, it.entityReferences)
        }.orEmpty(),
        settingProposals = stateDelta?.settingProposals?.map {
            EffectiveSettingProposal(it.title, it.content)
        }.orEmpty(),
    )

    private fun payloadSHA256(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        newCheckpointId: NovelCheckpointId,
        newStateSnapshotId: NovelStateSnapshotId,
        source: SyncSourceFacts,
        effective: EffectiveSyncPayload,
    ): String {
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            put("projectID", projectId.rawValue)
            put("branchID", branchId.rawValue)
            put("newCheckpointID", newCheckpointId.rawValue)
            put("newStateSnapshotID", newStateSnapshotId.rawValue)
            put("baseProjectRevision", source.baseProjectRevision)
            put("baseCheckpointID", source.baseCheckpointId.rawValue)
            put("baseHeadRevision", source.baseHeadRevision)
            putJsonArray("chapterSelections") {
                source.chapterSelections.forEach { selection ->
                    add(
                        buildJsonObject {
                            put("chapterID", selection.chapterID.rawValue)
                            put("versionID", selection.versionID.rawValue)
                        },
                    )
                }
            }
            when (val cursor = source.sessionCursor) {
                NovelSessionCursor.Empty -> put("sessionCursor", "empty")
                is NovelSessionCursor.Through -> put("sessionCursor", "through:${cursor.sequence}")
            }
            putJsonArray("branchOverrideRevisionIDs") {
                source.branchOverrideRevisionIds.forEach { add(it.rawValue) }
            }
            put("baseStateID", source.baseState.id.rawValue)
            putJsonArray("baseEventIDs") { source.baseState.eventIDs.forEach { add(it.rawValue) } }
            put("baseSummary", source.baseState.summary)
            put("baseBranchOutline", source.baseState.branchOutline)
            putJsonArray("baseUnresolvedEntityNames") {
                source.baseState.unresolvedEntityNames.forEach { add(it) }
            }
            putJsonArray("baseSettingProposalIDs") {
                source.baseState.settingProposalIDs.forEach { add(it.rawValue) }
            }
            putJsonArray("baseRecentWrittenHighlights") {
                source.baseState.recentWrittenHighlights.forEach { add(it) }
            }
            put("stateSummary", effective.stateSummary)
            put("branchOutline", effective.branchOutline)
            putJsonArray("unresolvedEntityNames") {
                effective.unresolvedEntityNames.forEach { add(it) }
            }
            putJsonArray("events") {
                effective.events.forEach { event ->
                    add(
                        buildJsonObject {
                            put("kind", event.kind)
                            put("summary", event.summary)
                            putJsonArray("entityReferences") {
                                event.entityReferences.forEach { add(it) }
                            }
                        },
                    )
                }
            }
            putJsonArray("settingProposals") {
                effective.settingProposals.forEach { proposal ->
                    add(
                        buildJsonObject {
                            put("title", proposal.title)
                            put("content", proposal.content)
                        },
                    )
                }
            }
        }
        return sha256HexOfUtf8(NovelSwiftCompatibleJson.canonicalJson(payload))
    }

    private data class ReplayContext(
        val applied: NovelAppliedOperationRecord,
        val source: SyncSourceFacts,
        val persistedEffective: EffectiveSyncPayload,
    )

    private data class SyncSourceFacts(
        val baseProjectRevision: Long,
        val baseCheckpointId: NovelCheckpointId,
        val baseHeadRevision: Long,
        val chapterSelections: List<app.amber.feature.novel.model.NovelChapterSelection>,
        val baseState: NovelStateSnapshotRecord,
        val sessionCursor: NovelSessionCursor,
        val branchOverrideRevisionIds: List<app.amber.feature.novel.model.NovelMaterialRevisionId>,
    )

    private data class EffectiveSyncPayload(
        val stateSummary: String,
        val branchOutline: String,
        val unresolvedEntityNames: List<String>,
        val events: List<EffectiveEvent>,
        val settingProposals: List<EffectiveSettingProposal>,
    )

    private data class EffectiveEvent(
        val kind: String,
        val summary: String,
        val entityReferences: List<String>,
    )

    private data class EffectiveSettingProposal(
        val title: String,
        val content: String,
    )

    fun workingManuscript(
        document: NovelProjectDocumentV1,
        branchId: app.amber.feature.novel.model.NovelBranchId,
    ): String {
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
        return chunksForSelections(document, branch.workingChapterSelections, maxChunkChars)
    }

    /**
     * Returns only the append-only manuscript suffix introduced by the current head checkpoint.
     * Ghostwrite uses this strict path after auto-collect so chapter N does not rebuild chapters 1...(N-1)
     * and append their story events again. Any rewrite/removal/reorder fails closed instead of guessing.
     */
    fun appendOnlyHeadSuffixChunks(
        document: NovelProjectDocumentV1,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS,
    ): List<String>? {
        if (maxChunkChars <= 0) return null
        val branch = document.branches.first { it.id == branchId }
        val head = document.checkpoints.firstOrNull { it.id == branch.headCheckpointID } ?: return null
        if (head.createdOnBranchID != branchId || head.chapterSelections != branch.workingChapterSelections) {
            return null
        }
        val parentId = head.parentCheckpointID ?: return null
        val parent = document.checkpoints.firstOrNull { it.id == parentId } ?: return null
        val current = branch.workingChapterSelections
        val previous = parent.chapterSelections
        if (current.size <= previous.size || current.take(previous.size) != previous) return null
        val suffix = current.drop(previous.size)
        if (suffix.size != 1) return null
        val selection = suffix.single()
        val version = document.chapterVersions.firstOrNull { it.id == selection.versionID } ?: return null
        if (version.chapterID != selection.chapterID) return null
        return chunksForSelections(document, suffix, maxChunkChars)
    }

    private fun chunksForSelections(
        document: NovelProjectDocumentV1,
        selections: List<app.amber.feature.novel.model.NovelChapterSelection>,
        maxChunkChars: Int,
    ): List<String> {
        require(maxChunkChars > 0) { "maxChunkChars must be positive" }
        if (selections.isEmpty()) return emptyList()
        val chapterTexts = selections.map { sel ->
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
                    var end = (offset + maxChunkChars).coerceAtMost(chapter.length)
                    // Never split a supplementary Unicode code point between its surrogate pair.
                    if (end < chapter.length && end > offset &&
                        Character.isHighSurrogate(chapter[end - 1]) &&
                        Character.isLowSurrogate(chapter[end])
                    ) {
                        end--
                    }
                    if (end == offset) {
                        end = (offset + 2).coerceAtMost(chapter.length)
                    }
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
     * Projects the cumulative narrative state between chunks without persisting it. This lets
     * chunk N+1 summarize from the accepted output of chunk N instead of repeatedly seeing the
     * pre-sync base state.
     */
    fun projectStateForNextChunk(
        document: NovelProjectDocumentV1,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        stateDelta: NovelStateDeltaV1,
    ): NovelProjectDocumentV1 {
        val branch = document.branches.first { it.id == branchId }
        val stateIndex = document.stateSnapshots.indexOfFirst { it.id == branch.currentStateSnapshotID }
        if (stateIndex < 0) return document
        val current = document.stateSnapshots[stateIndex]
        val projected = current.copy(
            summary = stateDelta.stateSummary,
            branchOutline = stateDelta.branchOutlinePatch ?: current.branchOutline,
            unresolvedEntityNames = stateDelta.unresolvedEntityNames,
            recentWrittenHighlights = NovelStateSnapshotRecord.mergedHighlights(
                prior = current.recentWrittenHighlights,
                newEventSummaries = stateDelta.events.map { it.summary },
            ),
        )
        val states = document.stateSnapshots.toMutableList()
        states[stateIndex] = projected
        return document.copy(stateSnapshots = states)
    }

    /**
     * Enforces the cumulative-state contract used by strict ghostwrite synchronization.
     * A model cannot rewrite the living summary/outline/unresolved set without at least one
     * evidence-backed fact from the current canonical chunk.
     */
    fun validateCumulativeStateDelta(
        document: NovelProjectDocumentV1,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        stateDelta: NovelStateDeltaV1,
    ) {
        val branch = document.branches.first { it.id == branchId }
        val base = document.stateSnapshots.first { it.id == branch.currentStateSnapshotID }
        val hasEvidenceBackedFact = stateDelta.events.isNotEmpty() ||
            stateDelta.settingProposals.isNotEmpty()
        val baseUnresolved = base.unresolvedEntityNames.map { it.trim() }.filter { it.isNotEmpty() }
        val nextUnresolved = stateDelta.unresolvedEntityNames.map { it.trim() }.filter { it.isNotEmpty() }
        val baseUnresolvedSet = baseUnresolved.toSet()
        val nextUnresolvedSet = nextUnresolved.toSet()
        // Android currently has no typed identity-resolution transaction. Until it does, a
        // chunk-local model output must not silently erase an older unresolved entity.
        require(baseUnresolvedSet.all { it in nextUnresolvedSet }) {
            "existing unresolved entities cannot disappear during strict synchronization"
        }
        if (!hasEvidenceBackedFact) {
            val expectedSummary = base.summary.trim().ifBlank { EMPTY_DERIVED_STATE_SUMMARY }
            require(stateDelta.stateSummary.trim() == expectedSummary) {
                "stateSummary changed without an evidence-backed fact"
            }
            require(
                stateDelta.branchOutlinePatch == null ||
                    stateDelta.branchOutlinePatch.trim() == base.branchOutline.trim(),
            ) {
                "branchOutlinePatch changed without an evidence-backed fact"
            }
            require(nextUnresolvedSet == baseUnresolvedSet) {
                "unresolvedEntityNames changed without an evidence-backed fact"
            }
            return
        }

        val referencedEntities = stateDelta.events
            .flatMap { it.entityReferences }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val newlyUnresolved = nextUnresolvedSet - baseUnresolvedSet
        require(newlyUnresolved.all { it in referencedEntities }) {
            "new unresolved entities must be referenced by an evidence-backed event"
        }
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
        // The prompt requires each chunk to return the cumulative unresolved set after applying
        // the projected base. Using the final set allows a later chunk to resolve an older item.
        val unresolved = deltas.last().unresolvedEntityNames.distinct()
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
    const val EMPTY_DERIVED_STATE_SUMMARY: String = "No derived story facts yet."
}
