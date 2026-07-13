package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelRecoverySidecarV1
import app.amber.feature.novel.serialization.sha256HexOfUtf8

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
            issues += "Project name must not be blank."
        }
        if (document.project.revision < 1) {
            issues += "Project revision must be positive."
        }
        if (document.project.configRevision < 1) {
            issues += "Config revision must be positive."
        }
        if (document.branches.none { it.id == document.project.mainBranchID }) {
            issues += "Main branch is missing from branches."
        }
    }

    private fun validateSessionsAndBranches(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val branchIds = document.branches.map { it.id }.toSet()
        if (branchIds.size != document.branches.size) {
            issues += "Duplicate branch IDs."
        }
        val sessionIds = document.sessions.map { it.id }.toSet()
        if (sessionIds.size != document.sessions.size) {
            issues += "Duplicate session IDs."
        }
        val stateIds = document.stateSnapshots.map { it.id }.toSet()
        val checkpointIds = document.checkpoints.map { it.id }.toSet()
        for (branch in document.branches) {
            if (branch.sessionID !in sessionIds) {
                issues += "Branch ${branch.id} references missing session."
            }
            if (branch.currentStateSnapshotID !in stateIds) {
                issues += "Branch ${branch.id} references missing state snapshot."
            }
            if (branch.headCheckpointID !in checkpointIds) {
                issues += "Branch ${branch.id} references missing head checkpoint."
            }
            if (branch.headRevision < 0 || branch.workingRevision < 0) {
                issues += "Branch ${branch.id} has negative revisions."
            }
            if (branch.lifecycle == NovelBranchLifecycle.Deleted &&
                document.project.mainBranchID == branch.id
            ) {
                issues += "Main branch cannot be deleted."
            }
        }
        for (session in document.sessions) {
            if (session.branchID !in branchIds) {
                issues += "Session ${session.id} references missing branch."
            }
            val sequences = session.messages.map { it.sequence }
            if (sequences.toSet().size != sequences.size) {
                issues += "Session ${session.id} has duplicate message sequences."
            }
            if (sequences != sequences.sorted()) {
                issues += "Session ${session.id} messages are not ordered by sequence."
            }
        }
        val activeBranches = document.branches.count { it.lifecycle == NovelBranchLifecycle.Active }
        if (activeBranches < 1) {
            issues += "At least one active branch is required."
        }
    }

    private fun validateChaptersAndState(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val chapterIds = document.chapters.map { it.id }.toSet()
        val versionIds = document.chapterVersions.map { it.id }.toSet()
        val eventIds = document.events.map { it.id }.toSet()
        if (chapterIds.size != document.chapters.size) issues += "Duplicate chapter IDs."
        if (versionIds.size != document.chapterVersions.size) issues += "Duplicate chapter version IDs."
        if (eventIds.size != document.events.size) issues += "Duplicate event IDs."
        for (version in document.chapterVersions) {
            if (version.chapterID !in chapterIds) {
                issues += "Chapter version ${version.id} references missing chapter."
            }
        }
        for (snapshot in document.stateSnapshots) {
            for (eventId in snapshot.eventIDs) {
                if (eventId !in eventIds) {
                    issues += "State snapshot ${snapshot.id} references missing event."
                }
            }
        }
    }

    private fun validateCheckpoints(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val checkpointIds = document.checkpoints.map { it.id }.toSet()
        if (checkpointIds.size != document.checkpoints.size) {
            issues += "Duplicate checkpoint IDs."
        }
        val stateIds = document.stateSnapshots.map { it.id }.toSet()
        val versionIds = document.chapterVersions.map { it.id }.toSet()
        val branchIds = document.branches.map { it.id }.toSet()
        for (checkpoint in document.checkpoints) {
            if (checkpoint.createdOnBranchID !in branchIds) {
                issues += "Checkpoint ${checkpoint.id} references missing branch."
            }
            if (checkpoint.stateSnapshotID !in stateIds) {
                issues += "Checkpoint ${checkpoint.id} references missing state snapshot."
            }
            checkpoint.parentCheckpointID?.let { parent ->
                if (parent !in checkpointIds) {
                    issues += "Checkpoint ${checkpoint.id} references missing parent."
                }
            }
            for (selection in checkpoint.chapterSelections) {
                if (selection.versionID !in versionIds) {
                    issues += "Checkpoint ${checkpoint.id} references missing chapter version."
                }
            }
        }
    }

    private fun validateCandidates(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val candidateIds = document.candidates.map { it.id }.toSet()
        if (candidateIds.size != document.candidates.size) {
            issues += "Duplicate candidate IDs."
        }
        val branchIds = document.branches.map { it.id }.toSet()
        val sessionIds = document.sessions.map { it.id }.toSet()
        for (candidate in document.candidates) {
            if (candidate.branchID !in branchIds) {
                issues += "Candidate ${candidate.id} references missing branch."
            }
            if (candidate.sessionID !in sessionIds) {
                issues += "Candidate ${candidate.id} references missing session."
            }
        }
    }

    private fun validateRunsAndPending(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val runIds = document.activeRuns.map { it.id }.toSet()
        if (runIds.size != document.activeRuns.size) {
            issues += "Duplicate active run IDs."
        }
        val pendingIds = document.pendingOperations.map { it.id }.toSet()
        if (pendingIds.size != document.pendingOperations.size) {
            issues += "Duplicate pending operation IDs."
        }
        for (run in document.activeRuns) {
            if (!isSHA256(run.requestPayloadSHA256)) {
                issues += "Active run ${run.id} has invalid request payload hash."
            }
        }
        for (pending in document.pendingOperations) {
            if (!isSHA256(pending.payloadSHA256)) {
                issues += "Pending operation ${pending.id} has invalid payload hash."
            }
        }
    }

    private fun validateOperationLedger(
        document: NovelProjectDocumentV1,
        issues: MutableList<String>,
    ) {
        val opIds = document.appliedOperations.map { it.operationID }.toSet()
        if (opIds.size != document.appliedOperations.size) {
            issues += "Duplicate applied operation IDs."
        }
        for (applied in document.appliedOperations) {
            if (!isSHA256(applied.payloadSHA256)) {
                issues += "Applied operation ${applied.operationID} has invalid payload hash."
            }
        }
    }

    private fun <T> isPrefixUnchanged(previous: List<T>, next: List<T>): Boolean {
        if (next.size < previous.size) return false
        return previous.indices.all { previous[it] == next[it] }
    }
}
