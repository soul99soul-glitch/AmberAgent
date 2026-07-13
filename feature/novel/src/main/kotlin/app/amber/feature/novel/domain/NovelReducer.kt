package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchCheckpointRecord
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCheckpointKind
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
            lastGenerationGranularity = NovelGenerationGranularity.WholeChapter,
            polishPreference = "",
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
        val payloadSHA256 = canonicalPayloadSha(
            buildJsonObject {
                put("kind", "setModelPolicy")
                put("projectID", command.projectID.rawValue)
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

        val nextProject = document.project.copy(
            modelPolicy = command.policy,
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
}
