package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchCheckpointRecord
import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCheckpointKind
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelMaterialRevisionId
import app.amber.feature.novel.model.NovelOperationKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelPolishTransactionStatus
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelRecoverySidecarV1
import app.amber.feature.novel.model.NovelRecoveryTerminalKind
import app.amber.feature.novel.model.NovelSessionMessageInteraction
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.model.NovelStateSnapshotRecord
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import java.text.Normalizer
import java.util.Locale

/**
 * Structural integrity validator for [NovelProjectDocumentV1].
 *
 * Phase 1 ships the checks required for create/list/load/rename and fixture
 * round-trips. Transition and polish/generation-specific rules expand later.
 */
object NovelDocumentValidator {
    fun validate(document: NovelProjectDocumentV1) {
        if (document.schemaVersion != NovelProjectDocumentV1.CURRENT_SCHEMA_VERSION) {
            throw NovelError.UnsupportedSchema(document.schemaVersion)
        }
        val issues = mutableListOf<String>()
        validateProject(document, issues)
        validateSessionsAndBranches(document, issues)
        validateChaptersAndState(document, issues)
        validateCheckpoints(document, issues)
        validateCandidates(document, issues)
        validateRunsAndPending(document, issues)
        validateOperationLedger(document, issues)
        if (issues.isNotEmpty()) {
            throw NovelError.InvalidDocument(issues.distinct().sorted())
        }
    }

    fun validateRecovery(sidecar: NovelRecoverySidecarV1) {
        if (sidecar.schemaVersion != NovelRecoverySidecarV1.CURRENT_SCHEMA_VERSION) {
            throw NovelError.InvalidRecovery("Unsupported schema version ${sidecar.schemaVersion}.")
        }
        if (sidecar.sequence < 0) {
            throw NovelError.InvalidRecovery("Sequence must be non-negative.")
        }
        if (sidecar.baseProjectRevision < 1) {
            throw NovelError.InvalidRecovery("Base project revision must be positive.")
        }
        if (!isSHA256(sidecar.partialSHA256)) {
            throw NovelError.InvalidRecovery("Partial content hash is not SHA-256.")
        }
        if (sidecar.partialSHA256.lowercase() != sha256HexOfUtf8(sidecar.partialContent)) {
            throw NovelError.InvalidRecovery("Partial content does not match its SHA-256 hash.")
        }
        sidecar.terminal?.let { terminal ->
            when (terminal.kind) {
                NovelRecoveryTerminalKind.Completed -> {
                    if (terminal.content == null || terminal.failure != null) {
                        throw NovelError.InvalidRecovery("Completed terminal intent has invalid payload.")
                    }
                }
                NovelRecoveryTerminalKind.Failed -> {
                    if (terminal.failure == null || terminal.content != null) {
                        throw NovelError.InvalidRecovery("Failed terminal intent has invalid payload.")
                    }
                }
            }
        }
    }

    fun validateTransition(from: NovelProjectDocumentV1, to: NovelProjectDocumentV1) {
        validate(from)
        validate(to)
        val issues = mutableListOf<String>()
        if (to.project.id != from.project.id) {
            issues += "A project transition changed the project ID."
        }
        if (to.project.createdAt != from.project.createdAt ||
            to.project.creationMode != from.project.creationMode ||
            to.project.quickStartSeed != from.project.quickStartSeed
        ) {
            issues += "A project transition rewrote immutable creation metadata."
        }
        if (to.project.revision != from.project.revision + 1) {
            issues += "A commit must advance project revision exactly once."
        }
        if (!isPrefixUnchanged(from.appliedOperations, to.appliedOperations)) {
            issues += "Applied operations must only append."
        }
        if (!isPrefixUnchanged(from.checkpoints, to.checkpoints)) {
            issues += "Checkpoints must only append."
        }
        if (!isPrefixUnchanged(from.events, to.events)) {
            issues += "Events must only append."
        }
        if (!isPrefixUnchanged(from.chapterVersions, to.chapterVersions)) {
            issues += "Chapter versions must only append."
        }
        validateChapterOperations(from, to, issues)
        validateBranchMaterialOverrideTransition(from, to, issues)
        validateBranchOperations(from, to, issues)
        if (issues.isNotEmpty()) {
            throw NovelError.InvalidDocument(issues.distinct().sorted())
        }
    }

    fun isSHA256(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.length == 64 && normalized.all { it in '0'..'9' || it in 'a'..'f' }
    }

    fun sha256(value: String): String = sha256HexOfUtf8(value)

    private fun validateProject(document: NovelProjectDocumentV1, issues: MutableList<String>) {
        if (document.project.name.isBlank()) {
            issues += "project.name: must not be blank"
        }
        if (document.project.revision < 1) {
            issues += "project.revision: must be positive"
        }
        if (document.project.configRevision < 1) {
            issues += "project.configRevision: must be positive"
        }
        if (document.branches.none { it.id == document.project.mainBranchID }) {
            issues += "project.mainBranchID: main branch is missing from branches[]"
        }
        validateFixedModelPolicy(document.project.modelPolicy, "project.modelPolicy", issues)
        document.project.stateSyncModelPolicy?.let {
            validateFixedModelPolicy(it, "project.stateSyncModelPolicy", issues)
        }
        document.project.reviewModelPolicy?.let {
            validateFixedModelPolicy(it, "project.reviewModelPolicy", issues)
        }
    }

    private fun validateSessionsAndBranches(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val branchIds = document.branches.map { it.id }.toSet()
        if (branchIds.size != document.branches.size) {
            issues += "branches[].id: duplicate branch IDs"
        }
        val sessionIds = document.sessions.map { it.id }.toSet()
        if (sessionIds.size != document.sessions.size) {
            issues += "sessions[].id: duplicate session IDs"
        }
        val stateIds = document.stateSnapshots.map { it.id }.toSet()
        val checkpointIds = document.checkpoints.map { it.id }.toSet()
        val sessionsByID = document.sessions.associateBy { it.id }
        val candidatesByID = document.candidates.associateBy { it.id }
        val materialRevisionsByID = document.materialRevisions.associateBy { it.id }
        for (branch in document.branches) {
            val session = sessionsByID[branch.sessionID]
            if (session == null) {
                issues += "branches[${branch.id}].sessionID: references missing session"
            } else if (session.branchID != branch.id) {
                issues += "branches[${branch.id}].sessionID: session belongs to another branch"
            }
            if (branch.currentStateSnapshotID !in stateIds) {
                issues += "branches[${branch.id}].currentStateSnapshotID: references missing state snapshot"
            }
            if (branch.headCheckpointID !in checkpointIds) {
                issues += "branches[${branch.id}].headCheckpointID: references missing head checkpoint"
            }
            if (branch.headRevision < 0 || branch.workingRevision < 0) {
                issues += "branches[${branch.id}].headRevision/workingRevision: negative revisions"
            }
            if (branch.lifecycle == NovelBranchLifecycle.Deleted &&
                document.project.mainBranchID == branch.id
            ) {
                issues += "branches[${branch.id}].lifecycle: main branch cannot be deleted"
            }
            val overrideMaterials = branch.overrideRevisionIDs.mapNotNull { revisionID ->
                materialRevisionsByID[revisionID]?.materialID
            }
            if (branch.overrideRevisionIDs.any { it !in materialRevisionsByID }) {
                issues += "branches[${branch.id}].overrideRevisionIDs: references missing revision"
            }
            if (branch.overrideRevisionIDs.toSet().size != branch.overrideRevisionIDs.size ||
                overrideMaterials.toSet().size != overrideMaterials.size
            ) {
                issues += "branches[${branch.id}].overrideRevisionIDs: repeats a material override"
            }
        }
        for (session in document.sessions) {
            if (session.branchID !in branchIds) {
                issues += "sessions[${session.id}].branchID: references missing branch"
            }
            val messageIds = session.messages.map { it.id }
            if (messageIds.toSet().size != messageIds.size) {
                issues += "sessions[${session.id}].messages[].id: duplicate message IDs"
            }
            val sequences = session.messages.map { it.sequence }
            if (sequences.toSet().size != sequences.size) {
                issues += "sessions[${session.id}].messages[].sequence: duplicate message sequences"
            }
            if (sequences != sequences.sorted()) {
                issues += "sessions[${session.id}].messages[].sequence: messages not ordered by sequence"
            }
            for (message in session.messages) {
                message.candidateID?.let { candidateID ->
                    val candidate = candidatesByID[candidateID]
                    if (candidate == null) {
                        issues += "sessions[${session.id}].messages[${message.id}].candidateID: " +
                            "references missing candidate"
                    } else if (candidate.sessionID != session.id || candidate.branchID != session.branchID) {
                        issues += "sessions[${session.id}].messages[${message.id}].candidateID: " +
                            "candidate belongs to another branch/session"
                    }
                }
                when (val interaction = message.interaction) {
                    is NovelSessionMessageInteraction.AskUserAnswer -> {
                        val prompt = session.messages.firstOrNull {
                            it.id == interaction.response.promptMessageID
                        }
                        if (prompt?.interaction !is NovelSessionMessageInteraction.AskUser) {
                            issues += "sessions[${session.id}].messages[${message.id}].interaction: " +
                                "askUserAnswer prompt must reference an AskUser message in the same session"
                        }
                    }
                    else -> Unit
                }
            }
        }
        val activeBranches = document.branches.count { it.lifecycle == NovelBranchLifecycle.Active }
        if (activeBranches < 1) {
            issues += "branches[].lifecycle: at least one active branch is required"
        }
    }

    private fun validateChaptersAndState(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val chapterIds = document.chapters.map { it.id }.toSet()
        val versionIds = document.chapterVersions.map { it.id }.toSet()
        val eventIds = document.events.map { it.id }.toSet()
        if (chapterIds.size != document.chapters.size) issues += "chapters[].id: duplicate chapter IDs"
        if (versionIds.size != document.chapterVersions.size) {
            issues += "chapterVersions[].id: duplicate chapter version IDs"
        }
        if (eventIds.size != document.events.size) issues += "events[].id: duplicate event IDs"
        for (version in document.chapterVersions) {
            if (version.chapterID !in chapterIds) {
                issues += "chapterVersions[${version.id}].chapterID: references missing chapter"
            }
        }
        val appliedOperationsById = document.appliedOperations.associateBy { it.operationID }
        for (snapshot in document.stateSnapshots) {
            for (eventId in snapshot.eventIDs) {
                if (eventId !in eventIds) {
                    issues += "stateSnapshots[${snapshot.id}].eventIDs: references missing event"
                }
            }
            val clarificationKeys = snapshot.characterIdentityClarifications.map {
                normalizedCharacterIdentity(it.mention)
            }
            if (clarificationKeys.any { it.isEmpty() } ||
                clarificationKeys.toSet().size != clarificationKeys.size
            ) {
                issues += "stateSnapshots[${snapshot.id}].characterIdentityClarifications: invalid mentions"
            }
            for (clarification in snapshot.characterIdentityClarifications) {
                if (clarification.mention.length > 200 ||
                    clarification.clarification.trim().isEmpty() ||
                    clarification.clarification.length > 1_000 ||
                    appliedOperationsById[clarification.operationID]?.kind !=
                        NovelOperationKind.ClarifyCharacterIdentity
                ) {
                    issues += "stateSnapshots[${snapshot.id}].characterIdentityClarifications: invalid data"
                }
            }
        }
        val branchIds = document.branches.map { it.id }.toSet()
        val planIds = document.chapterPlans.map { it.id }
        if (planIds.toSet().size != planIds.size) issues += "chapterPlans[].id: duplicate chapter plan IDs"
        val planBranchIds = document.chapterPlans.map { it.branchID }
        if (planBranchIds.toSet().size != planBranchIds.size) {
            issues += "chapterPlans[].branchID: duplicate chapter plans for a branch"
        }
        for (plan in document.chapterPlans) {
            if (plan.branchID !in branchIds) {
                issues += "chapterPlans[${plan.id}].branchID: references a missing branch"
            }
            val expectedDigest = app.amber.feature.novel.model.NovelChapterPlanRecord.digest(
                plan.canonicalDigestPayload(),
            )
            if (plan.contentDigest != expectedDigest) {
                issues += "chapterPlans[${plan.id}].contentDigest: digest does not match its content"
            }
            if (plan.isConfirmed && plan.confirmedAt == null) {
                issues += "chapterPlans[${plan.id}].confirmedAt: confirmed plan is missing confirmedAt"
            }
            if (plan.isConfirmed && plan.mustHappen.isEmpty()) {
                issues += "chapterPlans[${plan.id}].mustHappen: confirmed plan has no must-happen items"
            }
            if (plan.goalAndConflict.isBlank()) {
                issues += "chapterPlans[${plan.id}].goalAndConflict: missing goal and conflict"
            }
        }
        val arcBranchIds = document.upcomingArcs.map { it.branchID }
        if (arcBranchIds.toSet().size != arcBranchIds.size) {
            issues += "upcomingArcs[].branchID: duplicate upcoming arcs for a branch"
        }
        for (arc in document.upcomingArcs) {
            if (arc.branchID !in branchIds) {
                issues += "upcomingArcs[${arc.branchID}].branchID: references a missing branch"
            }
            if (arc.beats.isEmpty()) {
                issues += "upcomingArcs[${arc.branchID}].beats: has no beats"
            }
            if (arc.beats != app.amber.feature.novel.model.NovelUpcomingArcRecord.normalizedBeats(arc.beats)) {
                issues += "upcomingArcs[${arc.branchID}].beats: not normalized"
            }
        }
    }

    private fun validateCheckpoints(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val checkpointIds = document.checkpoints.map { it.id }.toSet()
        if (checkpointIds.size != document.checkpoints.size) {
            issues += "checkpoints[].id: duplicate checkpoint IDs"
        }
        val stateIds = document.stateSnapshots.map { it.id }.toSet()
        val versionIds = document.chapterVersions.map { it.id }.toSet()
        val branchIds = document.branches.map { it.id }.toSet()
        val checkpointsById = document.checkpoints.associateBy { it.id }
        val statesById = document.stateSnapshots.associateBy { it.id }
        val appliedOperationsById = document.appliedOperations.associateBy { it.operationID }
        for (checkpoint in document.checkpoints) {
            if (checkpoint.createdOnBranchID !in branchIds) {
                issues += "checkpoints[${checkpoint.id}].createdOnBranchID: references missing branch"
            }
            if (checkpoint.stateSnapshotID !in stateIds) {
                issues += "checkpoints[${checkpoint.id}].stateSnapshotID: references missing state snapshot"
            }
            checkpoint.parentCheckpointID?.let { parent ->
                if (parent !in checkpointIds) {
                    issues += "checkpoints[${checkpoint.id}].parentCheckpointID: references missing parent"
                }
            }
            for (selection in checkpoint.chapterSelections) {
                if (selection.versionID !in versionIds) {
                    issues += "checkpoints[${checkpoint.id}].chapterSelections[].versionID: references missing chapter version"
                }
            }
            if (checkpoint.kind == NovelCheckpointKind.IdentityClarification) {
                if (checkpoint.sourceCandidateID != null) {
                    issues += "checkpoints[${checkpoint.id}].sourceCandidateID: identity clarification has a candidate"
                }
                if (appliedOperationsById[checkpoint.operationID]?.kind !=
                    NovelOperationKind.ClarifyCharacterIdentity
                ) {
                    issues += "checkpoints[${checkpoint.id}].operationID: " +
                        "identity clarification has no matching operation"
                }
                val parent = checkpoint.parentCheckpointID?.let(checkpointsById::get)
                if (parent == null) {
                    issues += "checkpoints[${checkpoint.id}].parentCheckpointID: identity clarification has no parent"
                } else {
                    validateIdentityClarificationCheckpoint(
                        checkpoint = checkpoint,
                        parent = parent,
                        statesById = statesById,
                        issues = issues,
                    )
                }
            }
        }
    }

    private fun validateIdentityClarificationCheckpoint(
        checkpoint: NovelBranchCheckpointRecord,
        parent: NovelBranchCheckpointRecord,
        statesById: Map<NovelStateSnapshotId, NovelStateSnapshotRecord>,
        issues: MutableList<String>,
    ) {
        val state = statesById[checkpoint.stateSnapshotID]
        val parentState = statesById[parent.stateSnapshotID]
        val clarification = state?.characterIdentityClarifications?.lastOrNull()
        if (state == null || parentState == null || clarification == null) {
            issues += "checkpoints[${checkpoint.id}]: identity clarification has no state decision"
            return
        }

        val mentionKey = normalizedCharacterIdentity(clarification.mention)
        val expectedUnresolved = parentState.unresolvedEntityNames.filter {
            normalizedCharacterIdentity(it) != mentionKey
        }
        if (checkpoint.chapterSelections != parent.chapterSelections ||
            checkpoint.sessionCursor != parent.sessionCursor ||
            checkpoint.branchOverrideRevisionIDs != parent.branchOverrideRevisionIDs ||
            state.eventIDs != parentState.eventIDs ||
            state.summary != parentState.summary ||
            state.branchOutline != parentState.branchOutline ||
            state.settingProposalIDs != parentState.settingProposalIDs ||
            state.recentWrittenHighlights != parentState.recentWrittenHighlights ||
            state.characterIdentityClarifications.dropLast(1) != parentState.characterIdentityClarifications ||
            clarification.operationID != checkpoint.operationID ||
            expectedUnresolved == parentState.unresolvedEntityNames ||
            state.unresolvedEntityNames != expectedUnresolved
        ) {
            issues += "checkpoints[${checkpoint.id}]: identity clarification rewrites unrelated state"
        }
    }

    private fun validateCandidates(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val candidateIds = document.candidates.map { it.id }.toSet()
        if (candidateIds.size != document.candidates.size) {
            issues += "candidates[].id: duplicate candidate IDs"
        }
        val branchIds = document.branches.map { it.id }.toSet()
        val sessionIds = document.sessions.map { it.id }.toSet()
        val sessionsByID = document.sessions.associateBy { it.id }
        for (candidate in document.candidates) {
            if (candidate.branchID !in branchIds) {
                issues += "candidates[${candidate.id}].branchID: references missing branch"
            }
            if (candidate.sessionID !in sessionIds) {
                issues += "candidates[${candidate.id}].sessionID: references missing session"
            } else {
                val session = sessionsByID.getValue(candidate.sessionID)
                if (session.branchID != candidate.branchID) {
                    issues += "candidates[${candidate.id}].sessionID: session belongs to another branch"
                }
                if (session.messages.none { it.id == candidate.sourceMessageID }) {
                    issues += "candidates[${candidate.id}].sourceMessageID: references missing session message"
                }
            }
            candidate.clonedFromCandidateID?.let { sourceID ->
                if (sourceID !in candidateIds) {
                    issues += "candidates[${candidate.id}].clonedFromCandidateID: references missing candidate"
                }
            }
        }
    }

    private fun validateRunsAndPending(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val runIds = document.activeRuns.map { it.id }.toSet()
        if (runIds.size != document.activeRuns.size) {
            issues += "activeRuns[].id: duplicate active run IDs"
        }
        val pendingIds = document.pendingOperations.map { it.id }.toSet()
        if (pendingIds.size != document.pendingOperations.size) {
            issues += "pendingOperations[].id: duplicate pending operation IDs"
        }
        for (run in document.activeRuns) {
            if (!isSHA256(run.requestPayloadSHA256)) {
                issues += "activeRuns[${run.id}].requestPayloadSHA256: invalid request payload hash"
            }
        }
        for (pending in document.pendingOperations) {
            if (!isSHA256(pending.payloadSHA256)) {
                issues += "pendingOperations[${pending.id}].payloadSHA256: invalid payload hash"
            }
        }
    }

    private fun validateOperationLedger(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val opIds = document.appliedOperations.map { it.operationID }.toSet()
        if (opIds.size != document.appliedOperations.size) {
            issues += "appliedOperations[].operationID: duplicate applied operation IDs"
        }
        for (applied in document.appliedOperations) {
            if (!isSHA256(applied.payloadSHA256)) {
                issues += "appliedOperations[${applied.operationID}].payloadSHA256: invalid payload hash"
            }
            validateChapterOperationOutcome(applied, document, issues)
            validateBranchMaterialOverrideOutcome(applied, document, issues)
            val outcome = applied.outcome
            if (applied.kind == NovelOperationKind.ClarifyCharacterIdentity) {
                if (outcome !is NovelOutcome.CharacterIdentityClarified) {
                    issues += "appliedOperations[${applied.operationID}]: identity clarification has invalid outcome"
                    continue
                }
                val checkpoints = document.checkpoints.filter {
                    it.id == outcome.checkpointID &&
                        it.createdOnBranchID == outcome.branchID &&
                        it.kind == NovelCheckpointKind.IdentityClarification &&
                        it.stateSnapshotID == outcome.stateSnapshotID &&
                        it.operationID == applied.operationID
                }
                val identityCheckpoints = document.checkpoints.filter {
                    it.kind == NovelCheckpointKind.IdentityClarification &&
                        it.operationID == applied.operationID
                }
                val clarification = document.stateSnapshots
                    .firstOrNull { it.id == outcome.stateSnapshotID }
                    ?.characterIdentityClarifications
                    ?.firstOrNull { it.operationID == applied.operationID }
                if (outcome.projectID != document.project.id ||
                    outcome.revision != applied.appliedProjectRevision ||
                    checkpoints.size != 1 ||
                    identityCheckpoints.size != 1 ||
                    clarification?.mention != outcome.mention
                ) {
                    issues += "appliedOperations[${applied.operationID}]: identity clarification has invalid outcome"
                }
            } else if (outcome is NovelOutcome.CharacterIdentityClarified) {
                issues += "appliedOperations[${applied.operationID}]: " +
                    "identity clarification kind does not match outcome"
            }
        }
        validateLatestBranchMaterialOverrides(document, issues)
    }

    private fun validateBranchMaterialOverrideOutcome(
        applied: NovelAppliedOperationRecord,
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val outcome = applied.outcome
        if (applied.kind == NovelOperationKind.SetBranchMaterialOverride) {
            if (outcome !is NovelOutcome.BranchMaterialOverrideChanged ||
                outcome.projectID != document.project.id ||
                outcome.projectRevision != applied.appliedProjectRevision ||
                document.branches.none { it.id == outcome.branchID } ||
                document.materials.none { it.id == outcome.materialID } ||
                outcome.revisionID?.let { revisionID ->
                    document.materialRevisions.none {
                        it.id == revisionID && it.materialID == outcome.materialID
                    }
                } == true
            ) {
                issues += "appliedOperations[${applied.operationID}]: branch override has invalid outcome"
            }
        } else if (outcome is NovelOutcome.BranchMaterialOverrideChanged) {
            issues += "appliedOperations[${applied.operationID}]: branch override kind does not match outcome"
        }
    }

    private fun validateLatestBranchMaterialOverrides(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val latest = mutableMapOf<Pair<app.amber.feature.novel.model.NovelBranchId, NovelMaterialId>, NovelMaterialRevisionId?>()
        for (operation in document.appliedOperations) {
            val outcome = operation.outcome as? NovelOutcome.BranchMaterialOverrideChanged ?: continue
            if (operation.kind == NovelOperationKind.SetBranchMaterialOverride) {
                latest[outcome.branchID to outcome.materialID] = outcome.revisionID
            }
        }
        val revisionByID = document.materialRevisions.associateBy { it.id }
        for ((key, expectedRevisionID) in latest) {
            val branch = document.branches.firstOrNull { it.id == key.first } ?: continue
            val actual = branch.overrideRevisionIDs.filter { revisionID ->
                revisionByID[revisionID]?.materialID == key.second
            }
            val expected = listOfNotNull(expectedRevisionID)
            if (actual != expected) {
                issues += "Branch ${key.first} does not match its latest override for material ${key.second}."
            }
        }
    }

    /**
     * Validate the part of a chapter operation that remains provable from one
     * persisted document. Do not compare an old discard/restore outcome with the
     * chapter's current state: a later operation may have legitimately reversed it.
     */
    private fun validateChapterOperationOutcome(
        applied: NovelAppliedOperationRecord,
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        fun invalid(detail: String) {
            issues += "appliedOperations[${applied.operationID}]: $detail"
        }

        fun validateCommon(
            projectID: app.amber.feature.novel.model.NovelProjectId,
            branchID: app.amber.feature.novel.model.NovelBranchId,
            chapterID: app.amber.feature.novel.model.NovelChapterId,
            revision: Long,
        ) {
            if (projectID != document.project.id ||
                revision != applied.appliedProjectRevision ||
                document.branches.none { it.id == branchID } ||
                document.chapters.none { it.id == chapterID }
            ) {
                invalid("chapter operation has invalid project, branch, chapter, or revision")
            }
        }

        when (applied.kind) {
            NovelOperationKind.DiscardChapter -> {
                val outcome = applied.outcome as? NovelOutcome.ChapterDiscardStateChanged
                if (outcome == null || !outcome.isDiscarded) {
                    invalid("discardChapter has an invalid outcome")
                } else {
                    validateCommon(outcome.projectID, outcome.branchID, outcome.chapterID, outcome.revision)
                }
            }
            NovelOperationKind.RestoreChapter -> {
                val outcome = applied.outcome as? NovelOutcome.ChapterDiscardStateChanged
                if (outcome == null || outcome.isDiscarded) {
                    invalid("restoreChapter has an invalid outcome")
                } else {
                    validateCommon(outcome.projectID, outcome.branchID, outcome.chapterID, outcome.revision)
                }
            }
            NovelOperationKind.DeleteChapterFromManuscript -> {
                val outcome = applied.outcome as? NovelOutcome.ChapterRemovedFromManuscript
                if (outcome == null || outcome.workingRevision < 1) {
                    invalid("deleteChapterFromManuscript has an invalid outcome")
                } else {
                    validateCommon(outcome.projectID, outcome.branchID, outcome.chapterID, outcome.revision)
                }
            }
            else -> when (applied.outcome) {
                is NovelOutcome.ChapterDiscardStateChanged,
                is NovelOutcome.ChapterRemovedFromManuscript,
                -> invalid("chapter outcome does not match operation kind")
                else -> Unit
            }
        }
    }

    private fun validateBranchMaterialOverrideTransition(
        from: NovelProjectDocumentV1,
        to: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        if (!isPrefixUnchanged(from.appliedOperations, to.appliedOperations)) return
        val appended = to.appliedOperations.drop(from.appliedOperations.size)
        val overrideOperations = appended.filter {
            it.kind == NovelOperationKind.SetBranchMaterialOverride
        }
        if (overrideOperations.isEmpty()) return
        if (appended.size != 1 || overrideOperations.size != 1) {
            issues += "A branch material override transition must append exactly one operation."
            return
        }
        val operation = overrideOperations.single()
        val outcome = operation.outcome as? NovelOutcome.BranchMaterialOverrideChanged
        val branchIndex = from.branches.indexOfFirst { it.id == outcome?.branchID }
        val materialIndex = from.materials.indexOfFirst { it.id == outcome?.materialID }
        if (outcome == null || branchIndex < 0 || materialIndex < 0) {
            issues += "A branch material override has no matching source branch or material."
            return
        }
        val branch = from.branches[branchIndex]
        val material = from.materials[materialIndex]
        val revisionByID = from.materialRevisions.associateBy { it.id }
        val selectedRevisionID = outcome.revisionID
        val newRevision = selectedRevisionID?.takeIf { it !in revisionByID }?.let { revisionID ->
            to.materialRevisions.firstOrNull { it.id == revisionID }
        }
        val selectedBelongsToMaterial = selectedRevisionID == null ||
            revisionByID[selectedRevisionID]?.materialID == material.id ||
            newRevision?.materialID == material.id
        val expectedOverrides = (
            branch.overrideRevisionIDs.filterNot { revisionID ->
                revisionByID[revisionID]?.materialID == material.id
            } + listOfNotNull(selectedRevisionID)
            ).sortedBy { it.rawValue }
        val expectedBranch = branch.copy(
            overrideRevisionIDs = expectedOverrides,
            updatedAt = operation.appliedAt,
        )
        val expectedBranches = from.branches.toMutableList().also {
            it[branchIndex] = expectedBranch
        }
        val expectedMaterials = from.materials.toMutableList()
        val expectedRevisions = if (newRevision == null) {
            from.materialRevisions
        } else {
            expectedMaterials[materialIndex] = material.copy(
                revisionIDs = material.revisionIDs + newRevision.id,
            )
            from.materialRevisions + newRevision
        }
        val expectedProject = from.project.copy(
            revision = from.project.revision + 1,
            configRevision = from.project.configRevision + 1,
            updatedAt = operation.appliedAt,
        )
        val otherFieldsUnchanged = to.copy(
            project = from.project,
            branches = from.branches,
            materials = from.materials,
            materialRevisions = from.materialRevisions,
            appliedOperations = from.appliedOperations,
        ) == from
        val validNewRevision = newRevision == null ||
            (newRevision.id == selectedRevisionID &&
                newRevision.materialID == material.id &&
                newRevision.revision == material.revisionIDs.size.toLong() + 1 &&
                newRevision.operationID == operation.operationID)
        val valid = appended.size == 1 &&
            branch.lifecycle == NovelBranchLifecycle.Active &&
            branch.syncStatus == NovelBranchSyncStatus.Synchronized &&
            branch.activeRunID == null &&
            from.pendingOperations.none { it.branchID == branch.id } &&
            from.polishTransactions.none {
                it.branchID == branch.id &&
                    (it.status == NovelPolishTransactionStatus.Pending ||
                        it.status == NovelPolishTransactionStatus.Retryable)
            } &&
            !material.isDeleted &&
            selectedBelongsToMaterial &&
            validNewRevision &&
            outcome.projectID == from.project.id &&
            outcome.projectRevision == to.project.revision &&
            outcome.configRevision == to.project.configRevision &&
            operation.appliedProjectRevision == to.project.revision &&
            to.project == expectedProject &&
            to.branches == expectedBranches &&
            to.materials == expectedMaterials &&
            to.materialRevisions == expectedRevisions &&
            otherFieldsUnchanged
        if (!valid) {
            issues += "A branch material override has no matching configuration transition."
        }
    }

    private fun validateBranchOperations(
        from: NovelProjectDocumentV1,
        to: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        if (!isPrefixUnchanged(from.appliedOperations, to.appliedOperations)) return
        val appended = to.appliedOperations.drop(from.appliedOperations.size)
        for (operation in appended) {
            if (operation.kind != NovelOperationKind.DeleteBranch) continue
            val outcome = operation.outcome as? NovelOutcome.BranchDeleted
            val branchIndex = from.branches.indexOfFirst { it.id == outcome?.branchID }
            val current = branchIndex.takeIf { it >= 0 }?.let(from.branches::get)
            val updated = branchIndex.takeIf { it >= 0 && it < to.branches.size }?.let(to.branches::get)
            val expectedProject = from.project.copy(
                revision = from.project.revision + 1,
                updatedAt = operation.appliedAt,
            )
            val valid = outcome != null &&
                outcome.projectID == from.project.id &&
                outcome.revision == to.project.revision &&
                operation.appliedProjectRevision == to.project.revision &&
                from.project.revision + 1 == to.project.revision &&
                to.project == expectedProject &&
                branchIndex >= 0 &&
                current != null &&
                current.lifecycle == NovelBranchLifecycle.Active &&
                current.id != from.project.mainBranchID &&
                current.activeRunID == null &&
                from.pendingOperations.none { it.branchID == current.id } &&
                from.polishTransactions.none {
                    it.branchID == current.id &&
                        (it.status == NovelPolishTransactionStatus.Pending ||
                            it.status == NovelPolishTransactionStatus.Retryable)
                } &&
                updated == current.copy(
                    lifecycle = NovelBranchLifecycle.Deleted,
                    updatedAt = operation.appliedAt,
                ) &&
                from.branches.count { it.lifecycle == NovelBranchLifecycle.Active } > 1 &&
                to.branches.count { it.lifecycle == NovelBranchLifecycle.Active } ==
                from.branches.count { it.lifecycle == NovelBranchLifecycle.Active } - 1 &&
                from.branches.indices.all { index ->
                    index == branchIndex || from.branches[index] == to.branches.getOrNull(index)
                }
            if (!valid) {
                issues += "A deleteBranch operation has no matching branch lifecycle transition."
            }
        }
    }

    private fun validateChapterOperations(
        from: NovelProjectDocumentV1,
        to: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        if (!isPrefixUnchanged(from.appliedOperations, to.appliedOperations)) return
        val appended = to.appliedOperations.drop(from.appliedOperations.size)
        for (operation in appended) {
            when (operation.kind) {
                NovelOperationKind.DiscardChapter -> validateChapterDiscardTransition(
                    operation = operation,
                    from = from,
                    to = to,
                    expectedDiscarded = true,
                    issues = issues,
                )
                NovelOperationKind.RestoreChapter -> validateChapterDiscardTransition(
                    operation = operation,
                    from = from,
                    to = to,
                    expectedDiscarded = false,
                    issues = issues,
                )
                NovelOperationKind.DeleteChapterFromManuscript -> validateChapterDeleteTransition(
                    operation = operation,
                    from = from,
                    to = to,
                    issues = issues,
                )
                else -> Unit
            }
        }

        val fromChaptersById = from.chapters.associateBy { it.id }
        val toChaptersById = to.chapters.associateBy { it.id }
        for ((chapterID, current) in fromChaptersById) {
            val updated = toChaptersById[chapterID] ?: continue
            if (current.discardedAt == updated.discardedAt) continue
            val wasDiscarded = current.discardedAt != null
            val isDiscarded = updated.discardedAt != null
            if (wasDiscarded == isDiscarded) {
                issues += "A chapter discard transition rewrote a discard timestamp."
                continue
            }
            val owners = appended.filter { operation ->
                when (val outcome = operation.outcome) {
                    is NovelOutcome.ChapterDiscardStateChanged ->
                        outcome.chapterID == chapterID && outcome.isDiscarded == isDiscarded &&
                            operation.kind == if (isDiscarded) {
                                NovelOperationKind.DiscardChapter
                            } else {
                                NovelOperationKind.RestoreChapter
                            }
                    is NovelOutcome.ChapterRemovedFromManuscript ->
                        isDiscarded && outcome.chapterID == chapterID &&
                            operation.kind == NovelOperationKind.DeleteChapterFromManuscript
                    else -> false
                }
            }
            if (owners.size != 1 ||
                (isDiscarded && updated.discardedAt != owners.singleOrNull()?.appliedAt)
            ) {
                issues += "A chapter discard transition has no matching atomic operation."
            }
        }
    }

    private fun validateChapterDiscardTransition(
        operation: NovelAppliedOperationRecord,
        from: NovelProjectDocumentV1,
        to: NovelProjectDocumentV1,
        expectedDiscarded: Boolean,
        issues: MutableList<String>,
    ) {
        val outcome = operation.outcome as? NovelOutcome.ChapterDiscardStateChanged ?: return
        val current = from.chapters.firstOrNull { it.id == outcome.chapterID }
        val updated = to.chapters.firstOrNull { it.id == outcome.chapterID }
        val valid = operation.appliedProjectRevision == to.project.revision &&
            current != null &&
            updated != null &&
            outcome.isDiscarded == expectedDiscarded &&
            if (expectedDiscarded) {
                current.discardedAt == null &&
                    updated.discardedAt == operation.appliedAt
            } else {
                current.discardedAt != null && updated.discardedAt == null
            }
        if (!valid) {
            issues += "A ${operation.kind} operation has no matching chapter discard state transition."
        }
    }

    private fun validateChapterDeleteTransition(
        operation: NovelAppliedOperationRecord,
        from: NovelProjectDocumentV1,
        to: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val outcome = operation.outcome as? NovelOutcome.ChapterRemovedFromManuscript ?: return
        val currentBranch = from.branches.firstOrNull { it.id == outcome.branchID }
        val updatedBranch = to.branches.firstOrNull { it.id == outcome.branchID }
        val currentChapter = from.chapters.firstOrNull { it.id == outcome.chapterID }
        val updatedChapter = to.chapters.firstOrNull { it.id == outcome.chapterID }
        val expectedDiscardedAt = currentChapter?.discardedAt ?: operation.appliedAt
        val valid = operation.appliedProjectRevision == to.project.revision &&
            currentBranch != null &&
            updatedBranch != null &&
            currentChapter != null &&
            updatedChapter != null &&
            currentBranch.workingChapterSelections.any { it.chapterID == outcome.chapterID } &&
            updatedBranch.workingChapterSelections.none { it.chapterID == outcome.chapterID } &&
            updatedBranch.workingRevision == currentBranch.workingRevision + 1 &&
            updatedBranch.syncStatus == NovelBranchSyncStatus.NeedsSync &&
            updatedChapter.discardedAt == expectedDiscardedAt &&
            outcome.workingRevision == updatedBranch.workingRevision
        if (!valid) {
            issues += "A deleteChapterFromManuscript operation has no matching manuscript state transition."
        }
    }

    private fun normalizedCharacterIdentity(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)

    private fun validateFixedModelPolicy(
        policy: NovelProjectModelPolicy,
        label: String,
        issues: MutableList<String>,
    ) {
        if (policy is NovelProjectModelPolicy.Fixed &&
            (policy.providerID.isBlank() || policy.modelID.isBlank())
        ) {
            issues += "$label: fixed model policy has an empty stable ID"
        }
    }

    private fun <T> isPrefixUnchanged(previous: List<T>, next: List<T>): Boolean {
        if (next.size < previous.size) return false
        return previous.indices.all { previous[it] == next[it] }
    }
}
