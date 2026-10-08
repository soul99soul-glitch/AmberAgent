package app.amber.feature.novel.serialization

import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchRecord
import app.amber.feature.novel.model.NovelCandidateRecord
import app.amber.feature.novel.model.NovelChapterPlanRecord
import app.amber.feature.novel.model.NovelChapterRecord
import app.amber.feature.novel.model.NovelChapterVersionRecord
import app.amber.feature.novel.model.NovelMaterialRecord
import app.amber.feature.novel.model.NovelMaterialRevisionRecord
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectRecord
import app.amber.feature.novel.model.NovelSessionRecord
import app.amber.feature.novel.model.NovelSettingProposalRecord
import app.amber.feature.novel.model.NovelStateSnapshotRecord
import app.amber.feature.novel.model.NovelStoryEventRecord
import app.amber.feature.novel.model.NovelUpcomingArcRecord
import kotlinx.serialization.Serializable

/** Only book/discussion fields are consumed; host runtime histories are skipped while decoding. */
@Serializable
internal data class NovelWorkspaceImportDocument(
    val schemaVersion: Int = NovelProjectDocumentV1.CURRENT_SCHEMA_VERSION,
    val project: NovelProjectRecord,
    val materials: List<NovelMaterialRecord> = emptyList(),
    val materialRevisions: List<NovelMaterialRevisionRecord> = emptyList(),
    val branches: List<NovelBranchRecord> = emptyList(),
    val sessions: List<NovelSessionRecord> = emptyList(),
    val chapters: List<NovelChapterRecord> = emptyList(),
    val chapterVersions: List<NovelChapterVersionRecord> = emptyList(),
    val events: List<NovelStoryEventRecord> = emptyList(),
    val stateSnapshots: List<NovelStateSnapshotRecord> = emptyList(),
    val candidates: List<NovelCandidateRecord> = emptyList(),
    val settingProposals: List<NovelSettingProposalRecord> = emptyList(),
    val chapterPlans: List<NovelChapterPlanRecord> = emptyList(),
    val upcomingArcs: List<NovelUpcomingArcRecord> = emptyList(),
) {
    /** Adapt into the existing exporter input, without constructing an Android runtime history. */
    fun asDocument(): NovelProjectDocumentV1 {
        require(schemaVersion == NovelProjectDocumentV1.CURRENT_SCHEMA_VERSION) { "Unsupported project schema" }
        val activeBranches = branches.filter { it.lifecycle == NovelBranchLifecycle.Active }
        require(activeBranches.any { it.id == project.mainBranchID }) { "Main branch is missing or inactive" }
        val chaptersById = chapters.associateBy { it.id }
        val versionsById = chapterVersions.associateBy { it.id }
        val revisionsById = materialRevisions.associateBy { it.id }
        val snapshotsById = stateSnapshots.associateBy { it.id }
        val materialIds = materials.map { it.id }.toSet()
        val eventIds = events.map { it.id }.toSet()
        for (material in materials.filter { !it.isDeleted }) {
            require(revisionsById[material.currentRevisionID]?.materialID == material.id) {
                "Material current revision is missing or belongs to another material"
            }
        }
        for (branch in activeBranches) {
            for (selection in branch.workingChapterSelections) {
                require(chaptersById.containsKey(selection.chapterID) &&
                    versionsById[selection.versionID]?.chapterID == selection.chapterID
                ) { "Working chapter version is missing or belongs to another chapter" }
            }
            require(branch.overrideRevisionIDs.all { revisionsById[it]?.materialID in materialIds }) {
                "Branch material override is missing"
            }
            val snapshot = snapshotsById[branch.currentStateSnapshotID]
            require(snapshot != null) { "Branch current state snapshot is missing" }
            require(snapshot.eventIDs.all { it in eventIds }) { "Current state refers to missing events" }
        }
        return NovelProjectDocumentV1(
            schemaVersion = schemaVersion,
            project = project,
            materials = materials,
            materialRevisions = materialRevisions,
            branches = branches,
            sessions = sessions,
            chapters = chapters,
            chapterVersions = chapterVersions,
            events = events,
            stateSnapshots = stateSnapshots,
            candidates = candidates,
            settingProposals = settingProposals,
            chapterPlans = chapterPlans,
            upcomingArcs = upcomingArcs,
        )
    }
}
