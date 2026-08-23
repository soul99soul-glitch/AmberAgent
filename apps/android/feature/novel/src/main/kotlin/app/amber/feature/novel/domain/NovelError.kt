package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelStateSnapshotId

sealed class NovelError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    data class InvalidInput(val detail: String) : NovelError(detail)
    data class ProjectNotFound(val projectId: NovelProjectId) :
        NovelError("Project not found: ${projectId.rawValue}")

    data class ProjectAlreadyExists(val projectId: NovelProjectId) :
        NovelError("Project already exists: ${projectId.rawValue}")

    data class BranchNotFound(val branchId: NovelBranchId) :
        NovelError("Branch not found: ${branchId.rawValue}")

    data class SessionNotFound(val sessionId: NovelSessionId) :
        NovelError("Session not found: ${sessionId.rawValue}")

    data class StateSnapshotNotFound(val id: NovelStateSnapshotId) :
        NovelError("State snapshot not found: ${id.rawValue}")

    data class CheckpointNotFound(val id: NovelCheckpointId) :
        NovelError("Checkpoint not found: ${id.rawValue}")

    data class RunNotFound(val runId: NovelRunId) :
        NovelError("Run not found: ${runId.rawValue}")

    data class StaleProjectRevision(val expected: Long, val actual: Long) :
        NovelError("Stale project revision: expected $expected actual $actual")

    data class StaleConfigRevision(val expected: Long, val actual: Long) :
        NovelError("Stale config revision: expected $expected actual $actual")

    data class StaleBranchHeadRevision(val expected: Long, val actual: Long) :
        NovelError("Stale branch head revision: expected $expected actual $actual")

    data class StaleWorkingRevision(val expected: Long, val actual: Long) :
        NovelError("Stale working revision: expected $expected actual $actual")

    data class IdempotencyConflict(val operationId: NovelOperationId) :
        NovelError("Idempotency conflict: ${operationId.rawValue}")

    data class ImmutableRecordConflict(val detail: String) : NovelError(detail)
    data class InvalidDocument(val issues: List<String>) :
        NovelError(issues.joinToString("; "))

    data class CorruptedProject(val projectId: NovelProjectId, val details: String) :
        NovelError("Corrupted project ${projectId.rawValue}: $details")

    data class DegradedReadOnly(val projectId: NovelProjectId) :
        NovelError("Project is degraded read-only: ${projectId.rawValue}")

    data class RepositoryFailure(val detail: String) : NovelError(detail)
    data class StorageUnavailable(val detail: String) : NovelError(detail)
    data class StorageIndeterminate(val projectId: NovelProjectId) :
        NovelError("Storage indeterminate for ${projectId.rawValue}")

    data class InvalidRecovery(val detail: String) : NovelError(detail)
    data class InvalidPackage(val detail: String) : NovelError(detail)
    data class PackageTooLarge(val maximumBytes: Int) :
        NovelError("Package exceeds maximum $maximumBytes bytes")

    data object PackageChecksumMismatch : NovelError("Package checksum mismatch")
    data class ModelUnavailable(val detail: String) : NovelError(detail)
    data object GenerationUnavailable : NovelError("Generation unavailable")
    data class ProviderError(val detail: String) : NovelError(detail)
    data class UnsupportedSchema(val version: Int) :
        NovelError("Unsupported schema version $version")

    data class ProjectBusy(val projectId: NovelProjectId) :
        NovelError("Project is busy: ${projectId.rawValue}")

    /** Stable user-facing error code for localization mapping in app. */
    fun code(): String = when (this) {
        is InvalidInput -> "invalid_input"
        is ProjectNotFound -> "project_not_found"
        is ProjectAlreadyExists -> "project_already_exists"
        is BranchNotFound -> "branch_not_found"
        is SessionNotFound -> "session_not_found"
        is StateSnapshotNotFound -> "state_snapshot_not_found"
        is CheckpointNotFound -> "checkpoint_not_found"
        is RunNotFound -> "run_not_found"
        is StaleProjectRevision -> "stale_project_revision"
        is StaleConfigRevision -> "stale_config_revision"
        is StaleBranchHeadRevision -> "stale_branch_head_revision"
        is StaleWorkingRevision -> "stale_working_revision"
        is IdempotencyConflict -> "idempotency_conflict"
        is ImmutableRecordConflict -> "immutable_record_conflict"
        is InvalidDocument -> "invalid_document"
        is CorruptedProject -> "corrupted_project"
        is DegradedReadOnly -> "degraded_read_only"
        is RepositoryFailure -> "repository_failure"
        is StorageUnavailable -> "storage_unavailable"
        is StorageIndeterminate -> "storage_indeterminate"
        is InvalidRecovery -> "invalid_recovery"
        is InvalidPackage -> "invalid_package"
        is PackageTooLarge -> "package_too_large"
        PackageChecksumMismatch -> "package_checksum_mismatch"
        is ModelUnavailable -> "model_unavailable"
        GenerationUnavailable -> "generation_unavailable"
        is ProviderError -> "provider_error"
        is UnsupportedSchema -> "unsupported_schema"
        is ProjectBusy -> "project_busy"
    }
}
