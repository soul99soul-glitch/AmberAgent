package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelMaterialRevisionId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelQuickStartSeed
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.model.NovelInjectionMode

data class NovelMutationContext(
    val operationID: NovelOperationId,
    val expectedProjectRevision: Long? = null,
    val expectedConfigRevision: Long? = null,
    val expectedBranchHeadRevision: Long? = null,
)

data class NovelCreateProjectCommand(
    val context: NovelMutationContext,
    val projectID: NovelProjectId,
    val branchID: NovelBranchId,
    val sessionID: NovelSessionId,
    val initialStateSnapshotID: NovelStateSnapshotId,
    val initialCheckpointID: NovelCheckpointId,
    val name: String,
    val branchName: String = "Main",
    val creationMode: NovelProjectCreationMode,
    val quickStartSeed: NovelQuickStartSeed? = null,
)

data class NovelRenameProjectCommand(
    val context: NovelMutationContext,
    val projectID: NovelProjectId,
    val name: String,
)

/** Which project model slot [NovelSetModelPolicyCommand] mutates. */
enum class NovelModelPolicyPurpose {
    /** Writing / generation model → [app.amber.feature.novel.model.NovelProjectRecord.modelPolicy]. */
    Creation,

    /** Collect / manual-sync state extraction → [app.amber.feature.novel.model.NovelProjectRecord.stateSyncModelPolicy]. */
    StateSync,
}

data class NovelSetModelPolicyCommand(
    val context: NovelMutationContext,
    val projectID: NovelProjectId,
    val policy: NovelProjectModelPolicy,
    val purpose: NovelModelPolicyPurpose = NovelModelPolicyPurpose.Creation,
)

data class NovelReviseMaterialCommand(
    val context: NovelMutationContext,
    val projectID: NovelProjectId,
    val materialID: NovelMaterialId,
    val revisionID: NovelMaterialRevisionId,
    val kind: NovelMaterialKind,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val injectionMode: NovelInjectionMode = NovelInjectionMode.Smart,
)

data class NovelDeleteMaterialCommand(
    val context: NovelMutationContext,
    val projectID: NovelProjectId,
    val materialID: NovelMaterialId,
)

data class NovelSetPolishPreferenceCommand(
    val context: NovelMutationContext,
    val projectID: NovelProjectId,
    val polishPreference: String,
)
