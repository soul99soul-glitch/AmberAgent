package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchCheckpointRecord
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelChapterPlanRecord
import app.amber.feature.novel.model.NovelChapterPlanStatus
import app.amber.feature.novel.model.NovelCheckpointKind
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelGenerationGranularity
import app.amber.feature.novel.model.NovelMaterialRecord
import app.amber.feature.novel.model.NovelMaterialRevisionRecord
import app.amber.feature.novel.model.NovelOperationKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelProjectRecord
import app.amber.feature.novel.model.NovelSessionCursor
import app.amber.feature.novel.model.NovelSessionRecord
import app.amber.feature.novel.model.NovelStateSnapshotRecord
import app.amber.feature.novel.model.NovelUpcomingArcRecord
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novel.serialization.sha256Hex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

data class NovelReduceResult(
    val document: NovelProjectDocumentV1,
    val outcome: NovelOutcome,
)

object NovelReducer {
    /** Wire-stable clock sample (millisecond Instant). */
    private fun wireNow(now: Instant): Instant = Instant.ofEpochMilli(now.toEpochMilli())

    fun createProject(
        command: NovelCreateProjectCommand,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "createProject")
                put("projectID", command.projectID.rawValue)
                put("branchID", command.branchID.rawValue)
                put("sessionID", command.sessionID.rawValue)
                put("name", command.name.trim())
                put("branchName", command.branchName.trim())
                put("creationMode", command.creationMode.name)
                command.quickStartSeed?.let { seed ->
                    put("genre", seed.genre)
                    put("coreIdea", seed.coreIdea)
                }
            },
        )
        if (command.context.expectedProjectRevision != null ||
            command.context.expectedConfigRevision != null ||
            command.context.expectedBranchHeadRevision != null
        ) {
            throw NovelError.InvalidInput("Project creation must expect the project to be absent.")
        }
        val name = normalizedRequired(command.name, "Project name")
        val branchName = normalizedRequired(command.branchName, "Branch name")
        when (command.creationMode) {
            NovelProjectCreationMode.Blank -> {
                if (command.quickStartSeed != null) {
                    throw NovelError.InvalidInput("Blank projects cannot contain a quick-start seed.")
                }
            }
            NovelProjectCreationMode.QuickStart -> {
                val seed = command.quickStartSeed
                if (seed == null || seed.genre.isBlank() || seed.coreIdea.isBlank()) {
                    throw NovelError.InvalidInput("Quick-start projects require a genre and core idea.")
                }
            }
        }

        val state = NovelStateSnapshotRecord(
            id = command.initialStateSnapshotID,
            eventIDs = emptyList(),
            summary = "",
            branchOutline = "",
            unresolvedEntityNames = emptyList(),
            settingProposalIDs = emptyList(),
            createdAt = now,
        )
        val session = NovelSessionRecord(
            id = command.sessionID,
            branchID = command.branchID,
            revision = 0,
            messages = emptyList(),
        )
        val branch = NovelBranchRecord(
            id = command.branchID,
            name = branchName,
            sessionID = command.sessionID,
            createdAt = now,
            updatedAt = now,
            forkOrigin = null,
            headCheckpointID = command.initialCheckpointID,
            currentStateSnapshotID = state.id,
            headRevision = 0,
            workingRevision = 0,
            syncStatus = NovelBranchSyncStatus.Synchronized,
            lifecycle = NovelBranchLifecycle.Active,
            overrideRevisionIDs = emptyList(),
            workingChapterSelections = emptyList(),
            activeRunID = null,
        )
        val project = NovelProjectRecord(
            id = command.projectID,
            name = name,
            creationMode = command.creationMode,
            quickStartSeed = command.quickStartSeed,
            createdAt = now,
            updatedAt = now,
            revision = 1,
            configRevision = 1,
            mainBranchID = command.branchID,
            modelPolicy = NovelProjectModelPolicy.Global,
            stateSyncModelPolicy = null,
            reviewModelPolicy = null,
            lastGenerationGranularity = NovelGenerationGranularity.WholeChapter,
            polishPreference = "",
            collaborationMode = NovelCollaborationMode.Cocreation,
            pauseGhostwriteOnBlockingContinuity = true,
        )
        val outcome = NovelOutcome.ProjectCreated(
            projectID = command.projectID,
            branchID = command.branchID,
        )
        val applied = NovelAppliedOperationRecord(
            operationID = command.context.operationID,
            kind = NovelOperationKind.CreateProject,
            payloadSHA256 = payloadSHA256,
            outcome = outcome,
            appliedProjectRevision = project.revision,
            appliedAt = now,
        )
        val initialCheckpoint = NovelBranchCheckpointRecord(
            id = command.initialCheckpointID,
            kind = NovelCheckpointKind.Initial,
            createdOnBranchID = command.branchID,
            parentCheckpointID = null,
            chapterSelections = emptyList(),
            stateSnapshotID = state.id,
            sessionCursor = NovelSessionCursor.Empty,
            branchOverrideRevisionIDs = emptyList(),
            sourceCandidateID = null,
            baseHeadRevision = 0,
            operationID = command.context.operationID,
            createdAt = now,
        )
        val document = NovelProjectDocumentV1(
            schemaVersion = NovelProjectDocumentV1.CURRENT_SCHEMA_VERSION,
            project = project,
            materials = emptyList(),
            materialRevisions = emptyList(),
            branches = listOf(branch),
            sessions = listOf(session),
            chapters = emptyList(),
            chapterVersions = emptyList(),
            events = emptyList(),
            stateSnapshots = listOf(state),
            checkpoints = listOf(initialCheckpoint),
            candidates = emptyList(),
            injectionReceipts = emptyList(),
            generationReceipts = emptyList(),
            factAttempts = emptyList(),
            polishTransactions = emptyList(),
            polishAttempts = emptyList(),
            polishAssessments = emptyList(),
            pendingOperations = emptyList(),
            activeRuns = emptyList(),
            settingProposals = emptyList(),
            appliedOperations = listOf(applied),
        )
        NovelDocumentValidator.validate(document)
        return NovelReduceResult(document, outcome)
    }

    fun renameProject(
        command: NovelRenameProjectCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "renameProject")
                put("projectID", command.projectID.rawValue)
                put("name", command.name.trim())
            },
        )
        // Idempotent replay happens before revision guards (matches iOS apply order).
        replayIfPresent(command.context, NovelOperationKind.RenameProject, payloadSHA256, document)
            ?.let { return NovelReduceResult(document, it) }
        requireProjectRevision(command.context, document)

        val name = normalizedRequired(command.name, "Project name")
        val nextProject = document.project.copy(
            name = name,
            revision = document.project.revision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.ProjectRenamed(
            projectID = command.projectID,
            revision = nextProject.revision,
        )
        val next = document.copy(
            project = nextProject,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.RenameProject,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validate(next)
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun setModelPolicy(
        command: NovelSetModelPolicyCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val purposeWire = when (command.purpose) {
            NovelModelPolicyPurpose.Creation -> "creation"
            NovelModelPolicyPurpose.StateSync -> "stateSync"
            NovelModelPolicyPurpose.Review -> "review"
        }
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "setModelPolicy")
                put("projectID", command.projectID.rawValue)
                put("purpose", purposeWire)
                put(
                    "policy",
                    NovelSwiftCompatibleJson.json.encodeToJsonElement(
                        NovelProjectModelPolicy.Serializer,
                        command.policy,
                    ),
                )
            },
        )
        replayIfPresent(command.context, NovelOperationKind.SetModelPolicy, payloadSHA256, document)
            ?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)

        val nextProject = when (command.purpose) {
            NovelModelPolicyPurpose.Creation -> document.project.copy(
                modelPolicy = command.policy,
                revision = document.project.revision + 1,
                configRevision = document.project.configRevision + 1,
                updatedAt = now,
            )
            NovelModelPolicyPurpose.StateSync -> document.project.copy(
                // Store as-is: Global means follow chat global; Fixed pins a model.
                // UI uses ClearStateSyncModelPolicy / clearStateSyncModelPolicy for null
                // (follow writing model) — distinct from Global.
                stateSyncModelPolicy = command.policy,
                revision = document.project.revision + 1,
                configRevision = document.project.configRevision + 1,
                updatedAt = now,
            )
            NovelModelPolicyPurpose.Review -> document.project.copy(
                reviewModelPolicy = command.policy,
                revision = document.project.revision + 1,
                configRevision = document.project.configRevision + 1,
                updatedAt = now,
            )
        }
        val outcome = NovelOutcome.ModelPolicyChanged(
            projectID = command.projectID,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.SetModelPolicy,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validate(next)
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    /**
     * Clear [NovelProjectRecord.stateSyncModelPolicy] so resolve falls back to writing [modelPolicy].
     * Uses a dedicated op payload so it is distinct from setting Global (follow chat model).
     */
    fun clearStateSyncModelPolicy(
        command: NovelSetModelPolicyCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        require(command.purpose == NovelModelPolicyPurpose.StateSync) {
            "clearStateSyncModelPolicy requires StateSync purpose"
        }
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "setModelPolicy")
                put("projectID", command.projectID.rawValue)
                put("purpose", "stateSync")
                put("policy", "clear")
            },
        )
        replayIfPresent(command.context, NovelOperationKind.SetModelPolicy, payloadSHA256, document)
            ?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)

        val nextProject = document.project.copy(
            stateSyncModelPolicy = null,
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.ModelPolicyChanged(
            projectID = command.projectID,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.SetModelPolicy,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validate(next)
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun reviseMaterial(
        command: NovelReviseMaterialCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val title = normalizedRequired(command.title, "Material title")
        val content = command.content.trim()
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "reviseMaterial")
                put("projectID", command.projectID.rawValue)
                put("materialID", command.materialID.rawValue)
                put("revisionID", command.revisionID.rawValue)
                put("title", title)
                put("content", content)
            },
        )
        replayIfPresent(command.context, NovelOperationKind.ReviseMaterial, payloadSHA256, document)
            ?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)

        if (document.materialRevisions.any { it.id == command.revisionID }) {
            throw NovelError.ImmutableRecordConflict("material revision ${command.revisionID}")
        }
        val existingIndex = document.materials.indexOfFirst { it.id == command.materialID }
        val revisionNumber = if (existingIndex >= 0) {
            val material = document.materials[existingIndex]
            if (material.isDeleted) {
                throw NovelError.InvalidInput("Deleted project material cannot be revised.")
            }
            if (material.kind != command.kind) {
                throw NovelError.ImmutableRecordConflict("material kind ${command.materialID}")
            }
            material.revisionIDs.size.toLong() + 1
        } else {
            1L
        }
        val revision = NovelMaterialRevisionRecord(
            id = command.revisionID,
            materialID = command.materialID,
            revision = revisionNumber,
            title = title,
            content = content,
            tags = command.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            injectionMode = command.injectionMode,
            createdAt = now,
            operationID = command.context.operationID,
        )
        val materials = document.materials.toMutableList()
        if (existingIndex >= 0) {
            val material = materials[existingIndex]
            materials[existingIndex] = material.copy(
                currentRevisionID = revision.id,
                revisionIDs = material.revisionIDs + revision.id,
            )
        } else {
            materials += NovelMaterialRecord(
                id = command.materialID,
                kind = command.kind,
                currentRevisionID = revision.id,
                revisionIDs = listOf(revision.id),
                isDeleted = false,
            )
        }
        val nextProject = document.project.copy(
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.MaterialRevised(
            projectID = command.projectID,
            materialID = command.materialID,
            revisionID = revision.id,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            materials = materials,
            materialRevisions = document.materialRevisions + revision,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.ReviseMaterial,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validate(next)
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun deleteMaterial(
        command: NovelDeleteMaterialCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "deleteMaterial")
                put("projectID", command.projectID.rawValue)
                put("materialID", command.materialID.rawValue)
            },
        )
        replayIfPresent(command.context, NovelOperationKind.DeleteMaterial, payloadSHA256, document)
            ?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)

        val existingIndex = document.materials.indexOfFirst { it.id == command.materialID }
        if (existingIndex < 0) {
            throw NovelError.InvalidInput("Material ${command.materialID} not found.")
        }
        val material = document.materials[existingIndex]
        if (material.isDeleted) {
            // Fresh delete ops are not auto-idempotent; only exact operationID replay is.
            throw NovelError.InvalidInput("Material ${command.materialID} is already deleted.")
        }
        val materials = document.materials.toMutableList()
        materials[existingIndex] = material.copy(isDeleted = true)
        val nextProject = document.project.copy(
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.MaterialDeleted(
            projectID = command.projectID,
            materialID = command.materialID,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            materials = materials,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.DeleteMaterial,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validate(next)
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun setPolishPreference(
        command: NovelSetPolishPreferenceCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "setPolishPreference")
                put("projectID", command.projectID.rawValue)
                put("polishPreference", command.polishPreference)
            },
        )
        replayIfPresent(
            command.context,
            NovelOperationKind.SetPolishPreference,
            payloadSHA256,
            document,
        )?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)

        val nextProject = document.project.copy(
            polishPreference = command.polishPreference,
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.PolishPreferenceChanged(
            projectID = command.projectID,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.SetPolishPreference,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validate(next)
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun setCollaborationMode(
        command: NovelSetCollaborationModeCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "setCollaborationMode")
                put("projectID", command.projectID.rawValue)
                put("branchID", command.branchID.rawValue)
                put(
                    "mode",
                    when (command.mode) {
                        NovelCollaborationMode.Cocreation -> "cocreation"
                        NovelCollaborationMode.Ghostwrite -> "ghostwrite"
                    },
                )
            },
        )
        replayIfPresent(command.context, NovelOperationKind.SetCollaborationMode, payloadSHA256, document)
            ?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)
        val branch = document.branches.firstOrNull {
            it.id == command.branchID && it.lifecycle == NovelBranchLifecycle.Active
        } ?: throw NovelError.BranchNotFound(command.branchID)
        if (command.mode == document.project.collaborationMode) {
            throw NovelError.InvalidInput("The project is already in the requested collaboration mode.")
        }
        if (command.mode == NovelCollaborationMode.Cocreation &&
            document.activeRuns.any { it.status == app.amber.feature.novel.model.NovelRunStatus.Running }
        ) {
            throw NovelError.ProjectBusy(command.projectID)
        }
        if (command.mode == NovelCollaborationMode.Ghostwrite) {
            val issues = NovelGhostwriteReadiness.issues(document, branch.id, requireChapterPlan = false)
            if (issues.isNotEmpty()) {
                throw NovelError.InvalidInput(
                    "无法切入代笔模式：${issues.joinToString("；") { it.displayName }}",
                )
            }
        }

        val nextProject = document.project.copy(
            collaborationMode = command.mode,
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.CollaborationModeChanged(
            projectID = command.projectID,
            mode = command.mode,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.SetCollaborationMode,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun setPauseGhostwriteOnBlockingContinuity(
        command: NovelSetPauseGhostwriteOnBlockingContinuityCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "setPauseGhostwriteOnBlockingContinuity")
                put("projectID", command.projectID.rawValue)
                put("enabled", command.enabled)
            },
        )
        replayIfPresent(
            command.context,
            NovelOperationKind.SetPauseGhostwriteOnBlockingContinuity,
            payloadSHA256,
            document,
        )?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)
        if (command.enabled == document.project.pauseGhostwriteOnBlockingContinuity) {
            throw NovelError.InvalidInput("The ghostwrite continuity-pause setting is already unchanged.")
        }

        val nextProject = document.project.copy(
            pauseGhostwriteOnBlockingContinuity = command.enabled,
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.PauseGhostwriteOnBlockingContinuityChanged(
            projectID = command.projectID,
            enabled = command.enabled,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.SetPauseGhostwriteOnBlockingContinuity,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun upsertChapterPlan(
        command: NovelUpsertChapterPlanCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "upsertChapterPlan")
                put("projectID", command.projectID.rawValue)
                put("branchID", command.branchID.rawValue)
                put("planID", command.planID.rawValue)
                put("status", command.status.name.lowercase())
                put("outlinePlacement", command.outlinePlacement)
                put("goalAndConflict", command.goalAndConflict)
                putStringList("mustHappen", command.mustHappen)
                putStringList("mustNotHappen", command.mustNotHappen)
                put("endingHook", command.endingHook)
                putStringList("visibleFacts", command.visibleFacts)
            },
        )
        replayIfPresent(command.context, NovelOperationKind.UpsertChapterPlan, payloadSHA256, document)
            ?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)
        if (document.branches.none {
                it.id == command.branchID && it.lifecycle == NovelBranchLifecycle.Active
            }
        ) {
            throw NovelError.BranchNotFound(command.branchID)
        }
        requireBranchHeadRevisionIfPresent(command.context, command.branchID, document)

        val outlinePlacement = command.outlinePlacement.trim()
        val goalAndConflict = command.goalAndConflict.trim()
        val endingHook = command.endingHook.trim()
        val mustHappen = NovelChapterPlanRecord.normalizedLines(command.mustHappen)
        val mustNotHappen = NovelChapterPlanRecord.normalizedLines(command.mustNotHappen)
        val visibleFacts = NovelChapterPlanRecord.normalizedLines(command.visibleFacts)
        if (outlinePlacement.length > 500) {
            throw NovelError.InvalidInput("The chapter-plan placement note is too long.")
        }
        if (goalAndConflict.isEmpty()) {
            throw NovelError.InvalidInput("The chapter plan needs a goal and conflict.")
        }
        if (goalAndConflict.length > 8_000) {
            throw NovelError.InvalidInput("The chapter-plan goal is too long.")
        }
        if (endingHook.length > 4_000) {
            throw NovelError.InvalidInput("The chapter-plan ending hook is too long.")
        }
        if (mustHappen.size > 32 || mustNotHappen.size > 32 || visibleFacts.size > 32) {
            throw NovelError.InvalidInput("The chapter plan has too many checklist items.")
        }
        if (command.status == NovelChapterPlanStatus.Confirmed && mustHappen.isEmpty()) {
            throw NovelError.InvalidInput(
                "Confirming a chapter plan requires at least one must-happen item.",
            )
        }
        val existing = document.chapterPlan(command.branchID)
        if (existing != null && existing.id != command.planID) {
            throw NovelError.InvalidInput("The branch already has a chapter plan with a different ID.")
        }

        var plan = NovelChapterPlanRecord(
            id = command.planID,
            branchID = command.branchID,
            status = command.status,
            outlinePlacement = outlinePlacement,
            goalAndConflict = goalAndConflict,
            mustHappen = mustHappen,
            mustNotHappen = mustNotHappen,
            endingHook = endingHook,
            visibleFacts = visibleFacts,
            contentDigest = "",
            updatedAt = now,
            confirmedAt = now.takeIf { command.status == NovelChapterPlanStatus.Confirmed },
        )
        plan = plan.copy(
            contentDigest = NovelChapterPlanRecord.digest(plan.canonicalDigestPayload()),
        )
        val plans = document.chapterPlans.toMutableList()
        val planIndex = plans.indexOfFirst { it.branchID == command.branchID }
        if (planIndex >= 0) plans[planIndex] = plan else plans += plan

        val nextProject = document.project.copy(
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.ChapterPlanUpserted(
            projectID = command.projectID,
            branchID = command.branchID,
            planID = plan.id,
            status = plan.status,
            contentDigest = plan.contentDigest,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            chapterPlans = plans,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.UpsertChapterPlan,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun clearChapterPlan(
        command: NovelClearChapterPlanCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "clearChapterPlan")
                put("projectID", command.projectID.rawValue)
                put("branchID", command.branchID.rawValue)
                put(
                    "expectedPlanID",
                    command.expectedPlanID?.let {
                        kotlinx.serialization.json.JsonPrimitive(it.rawValue)
                    } ?: kotlinx.serialization.json.JsonNull,
                )
                put(
                    "expectedPlanDigest",
                    command.expectedPlanDigest?.let {
                        kotlinx.serialization.json.JsonPrimitive(it)
                    } ?: kotlinx.serialization.json.JsonNull,
                )
            },
        )
        replayIfPresent(command.context, NovelOperationKind.ClearChapterPlan, payloadSHA256, document)
            ?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)
        if (document.branches.none {
                it.id == command.branchID && it.lifecycle == NovelBranchLifecycle.Active
            }
        ) {
            throw NovelError.BranchNotFound(command.branchID)
        }
        requireBranchHeadRevisionIfPresent(command.context, command.branchID, document)
        val hasExpectedPlanID = command.expectedPlanID != null
        val hasExpectedPlanDigest = command.expectedPlanDigest != null
        if (hasExpectedPlanID != hasExpectedPlanDigest) {
            throw NovelError.InvalidInput("Expected chapter-plan ID and digest must be provided together.")
        }
        val currentPlan = document.chapterPlan(command.branchID)
        if (currentPlan == null) {
            throw NovelError.InvalidInput("There is no chapter plan to clear on this branch.")
        }
        if (command.expectedPlanID != null &&
            (currentPlan.id != command.expectedPlanID ||
                currentPlan.contentDigest != command.expectedPlanDigest)
        ) {
            throw NovelError.InvalidInput("The chapter plan changed before it could be cleared.")
        }

        val nextProject = document.project.copy(
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.ChapterPlanCleared(
            projectID = command.projectID,
            branchID = command.branchID,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            chapterPlans = document.chapterPlans.filterNot { it.branchID == command.branchID },
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.ClearChapterPlan,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun upsertUpcomingArc(
        command: NovelUpsertUpcomingArcCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "upsertUpcomingArc")
                put("projectID", command.projectID.rawValue)
                put("branchID", command.branchID.rawValue)
                putStringList("beats", command.beats)
            },
        )
        replayIfPresent(command.context, NovelOperationKind.UpsertUpcomingArc, payloadSHA256, document)
            ?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)
        if (document.branches.none {
                it.id == command.branchID && it.lifecycle == NovelBranchLifecycle.Active
            }
        ) {
            throw NovelError.BranchNotFound(command.branchID)
        }
        val beats = NovelUpcomingArcRecord.normalizedBeats(command.beats)
        if (beats.isEmpty()) {
            throw NovelError.InvalidInput("往后几章至少需要一条备注。")
        }
        val arc = NovelUpcomingArcRecord(command.branchID, beats, now)
        val arcs = document.upcomingArcs.toMutableList()
        val arcIndex = arcs.indexOfFirst { it.branchID == command.branchID }
        if (arcIndex >= 0) arcs[arcIndex] = arc else arcs += arc

        val nextProject = document.project.copy(
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.UpcomingArcUpserted(
            projectID = command.projectID,
            branchID = command.branchID,
            beatCount = arc.beats.size,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            upcomingArcs = arcs,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.UpsertUpcomingArc,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun clearUpcomingArc(
        command: NovelClearUpcomingArcCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val now = wireNow(now)
        requireProjectId(command.projectID, document)
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "clearUpcomingArc")
                put("projectID", command.projectID.rawValue)
                put("branchID", command.branchID.rawValue)
            },
        )
        replayIfPresent(command.context, NovelOperationKind.ClearUpcomingArc, payloadSHA256, document)
            ?.let { return NovelReduceResult(document, it) }
        requireConfigRevision(command.context, document)
        if (document.branches.none {
                it.id == command.branchID && it.lifecycle == NovelBranchLifecycle.Active
            }
        ) {
            throw NovelError.BranchNotFound(command.branchID)
        }
        if (document.upcomingArc(command.branchID) == null) {
            throw NovelError.InvalidInput("当前分支没有往后几章的备注可清除。")
        }

        val nextProject = document.project.copy(
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.UpcomingArcCleared(
            projectID = command.projectID,
            branchID = command.branchID,
            projectRevision = nextProject.revision,
            configRevision = nextProject.configRevision,
        )
        val next = document.copy(
            project = nextProject,
            upcomingArcs = document.upcomingArcs.filterNot { it.branchID == command.branchID },
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.context.operationID,
                kind = NovelOperationKind.ClearUpcomingArc,
                payloadSHA256 = payloadSHA256,
                outcome = outcome,
                appliedProjectRevision = nextProject.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    private fun requireProjectId(projectId: app.amber.feature.novel.model.NovelProjectId, document: NovelProjectDocumentV1) {
        if (projectId != document.project.id) {
            throw NovelError.ProjectNotFound(projectId)
        }
    }

    private fun requireProjectRevision(context: NovelMutationContext, document: NovelProjectDocumentV1) {
        val expected = context.expectedProjectRevision
            ?: throw NovelError.InvalidInput("Expected project revision is required.")
        if (expected != document.project.revision) {
            throw NovelError.StaleProjectRevision(expected, document.project.revision)
        }
    }

    private fun requireConfigRevision(context: NovelMutationContext, document: NovelProjectDocumentV1) {
        val expectedProject = context.expectedProjectRevision
            ?: throw NovelError.InvalidInput("Expected project revision is required.")
        val expectedConfig = context.expectedConfigRevision
            ?: throw NovelError.InvalidInput("Expected config revision is required.")
        if (expectedProject != document.project.revision) {
            throw NovelError.StaleProjectRevision(expectedProject, document.project.revision)
        }
        if (expectedConfig != document.project.configRevision) {
            throw NovelError.StaleConfigRevision(expectedConfig, document.project.configRevision)
        }
    }

    private fun requireBranchHeadRevisionIfPresent(
        context: NovelMutationContext,
        branchID: app.amber.feature.novel.model.NovelBranchId,
        document: NovelProjectDocumentV1,
    ) {
        val expected = context.expectedBranchHeadRevision ?: return
        val branch = document.branches.firstOrNull {
            it.id == branchID && it.lifecycle == NovelBranchLifecycle.Active
        } ?: throw NovelError.BranchNotFound(branchID)
        if (expected != branch.headRevision) {
            throw NovelError.StaleBranchHeadRevision(expected, branch.headRevision)
        }
    }

    private fun replayIfPresent(
        context: NovelMutationContext,
        kind: NovelOperationKind,
        payloadSHA256: String,
        document: NovelProjectDocumentV1,
    ): NovelOutcome? {
        val applied = document.appliedOperations.firstOrNull { it.operationID == context.operationID }
            ?: return null
        if (applied.payloadSHA256 != payloadSHA256 || applied.kind != kind) {
            throw NovelError.IdempotencyConflict(context.operationID)
        }
        return applied.outcome
    }

    private fun normalizedRequired(value: String, field: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            throw NovelError.InvalidInput("$field is required.")
        }
        return trimmed
    }

    private fun canonicalPayloadSha(element: kotlinx.serialization.json.JsonElement): String {
        val canonical = NovelSwiftCompatibleJson.canonicalJson(element)
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStringList(
        key: String,
        values: List<String>,
    ) {
        put(
            key,
            kotlinx.serialization.json.JsonArray(
                values.map { kotlinx.serialization.json.JsonPrimitive(it) },
            ),
        )
    }
}
