package app.amber.feature.novel.workspace

import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelSessionMessageKind
import app.amber.feature.novel.model.NovelSessionRole
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceSessionMessage
import app.amber.feature.novelworkspace.NovelWorkspaceSessionsFile
import app.amber.feature.novelworkspace.NovelWorkspaceSlug
import java.time.Instant

/**
 * One-time bridge: legacy JSON document → markdown workspace files (book + sessions).
 *
 * Mapping is a Kotlin port of iOS `NovelWorkspaceBackup.export` so both platforms emit
 * byte-identical trees for the same document. Machine data (CAS revisions, checkpoints,
 * receipts, state snapshots as records) is intentionally left behind.
 */
object NovelLegacyWorkspaceMigrator {

    fun workspaceFiles(document: NovelProjectDocumentV1, exportedAt: Instant): List<NovelWorkspaceFile> {
        val files = mutableListOf<NovelWorkspaceFile>()
        val usedPaths = mutableSetOf<String>()

        val activeBranches = document.branches.filter { it.lifecycle == NovelBranchLifecycle.Active }
        val mainBranch = activeBranches.firstOrNull { it.id == document.project.mainBranchID }
        val mainSlug = NovelWorkspaceSlug.reservedPath(
            NovelWorkspaceSlug.slug(mainBranch?.name ?: "main"),
            usedPaths,
            document.project.mainBranchID.rawValue,
        )
        usedPaths.clear()

        files.add(
            NovelWorkspaceFile(
                path = NovelWorkspacePaths.MANIFEST,
                content = NovelWorkspaceManifestRenderer.render(
                    exportedAt = exportedAt,
                    sourceProjectID = document.project.id.rawValue,
                    sourceProjectRevision = document.project.revision,
                    sourceSchemaVersion = document.schemaVersion,
                    mainBranch = mainSlug,
                ),
            ),
        )
        files.add(
            NovelWorkspaceFile(
                path = NovelWorkspacePaths.PROJECT_FILE,
                content = NovelWorkspaceMarkdown.render(
                    fields = listOf(
                        "id" to document.project.id.rawValue,
                        "kind" to "project",
                        "title" to document.project.name,
                        "collaborationMode" to collaborationModeName(document.project.collaborationMode),
                        "polishPreference" to document.project.polishPreference,
                    ),
                    body = "",
                ),
            ),
        )

        val liveMaterials = document.materials.filter { !it.isDeleted }
        for (material in liveMaterials) {
            val revision = document.materialRevisions.firstOrNull { it.id == material.currentRevisionID }
                ?: continue
            val relative = "setting/${materialFolder(material.kind)}/${NovelWorkspaceSlug.slug(revision.title)}"
            val path = NovelWorkspaceSlug.reservedPath(relative, usedPaths, material.id.rawValue)
            val fields = mutableListOf(
                "id" to material.id.rawValue,
                "kind" to "material",
                "title" to revision.title,
                "materialKind" to materialKindName(material.kind),
                "injection" to revision.injectionMode.rawValue,
                "sourceVersionID" to revision.id.rawValue,
            )
            if (material.kind is NovelMaterialKind.Custom) {
                fields += "customName" to (material.kind as NovelMaterialKind.Custom).value
            }
            files.add(
                NovelWorkspaceFile(
                    path = path,
                    content = NovelWorkspaceMarkdown.render(fields, aliases = revision.aliases, body = revision.content),
                ),
            )
        }

        val branchSlugs = mutableMapOf<String, String>()
        val usedBranchSlugs = mutableSetOf<String>()
        for (branch in activeBranches) {
            branchSlugs[branch.id.rawValue] = NovelWorkspaceSlug.reservedPath(
                NovelWorkspaceSlug.slug(branch.name),
                usedBranchSlugs,
                branch.id.rawValue,
            )
        }

        for (branch in activeBranches) {
            val prefix = NovelWorkspacePaths.branchPrefix(branchSlugs.getValue(branch.id.rawValue))
            files.add(
                NovelWorkspaceFile(
                    path = "$prefix/branch.md",
                    content = NovelWorkspaceMarkdown.render(
                        fields = listOf(
                            "id" to branch.id.rawValue,
                            "kind" to "branch",
                            "title" to branch.name,
                            "syncStatus" to branch.syncStatus.rawValue,
                        ),
                        body = "",
                    ),
                ),
            )

            val usedChapterNames = mutableSetOf<String>()
            var ordinal = 0
            for (selection in branch.workingChapterSelections) {
                val chapter = document.chapters.firstOrNull { it.id == selection.chapterID }
                if (chapter?.discardedAt != null) continue
                val version = document.chapterVersions.firstOrNull {
                    it.id == selection.versionID && it.chapterID == selection.chapterID
                } ?: continue
                ordinal += 1
                val name = NovelWorkspaceSlug.reservedPath(
                    NovelWorkspaceSlug.slug(version.title),
                    usedChapterNames,
                    selection.chapterID.rawValue,
                )
                files.add(
                    NovelWorkspaceFile(
                        path = "$prefix/chapters/${NovelWorkspacePaths.chapterFileName(ordinal, name)}",
                        content = NovelWorkspaceMarkdown.render(
                            fields = listOf(
                                "id" to selection.chapterID.rawValue,
                                "kind" to "chapter",
                                "title" to version.title,
                                "ordinal" to ordinal.toString(),
                                "sourceVersionID" to version.id.rawValue,
                            ),
                            body = version.content,
                        ),
                    ),
                )
            }

            val usedDiscardedNames = mutableSetOf<String>()
            for (chapter in document.chapters.filter { it.discardedAt != null }) {
                val version = discardedVersion(chapter.id.rawValue, branch.id.rawValue, document) ?: continue
                val name = NovelWorkspaceSlug.reservedPath(
                    NovelWorkspaceSlug.slug(version.title),
                    usedDiscardedNames,
                    chapter.id.rawValue,
                )
                files.add(
                    NovelWorkspaceFile(
                        path = "$prefix/discarded/$name.md",
                        content = NovelWorkspaceMarkdown.render(
                            fields = listOf(
                                "id" to chapter.id.rawValue,
                                "kind" to "chapter",
                                "title" to version.title,
                                "sourceVersionID" to version.id.rawValue,
                            ),
                            body = version.content,
                        ),
                    ),
                )
            }

            document.stateSnapshots.firstOrNull { it.id == branch.currentStateSnapshotID }?.let { snapshot ->
                var currentBody = snapshot.summary
                val highlights = snapshot.recentWrittenHighlights.filter { it.isNotBlank() }
                if (highlights.isNotEmpty()) {
                    currentBody += "\n\n## 近期已写\n\n" + highlights.joinToString("\n") { "- $it" }
                }
                files.add(
                    NovelWorkspaceFile(
                        path = "$prefix/plot/current.md",
                        content = NovelWorkspaceMarkdown.render(
                            fields = listOf(
                                "id" to snapshot.id.rawValue,
                                "kind" to "plot",
                                "title" to "当前状态",
                            ),
                            body = currentBody,
                        ),
                    ),
                )
                files.add(
                    NovelWorkspaceFile(
                        path = "$prefix/plot/outline.md",
                        content = NovelWorkspaceMarkdown.render(
                            fields = listOf(
                                "id" to snapshot.id.rawValue,
                                "kind" to "plot",
                                "title" to "分支大纲",
                            ),
                            body = snapshot.branchOutline,
                        ),
                    ),
                )
                val events = snapshot.eventIDs.mapNotNull { eventID ->
                    document.events.firstOrNull { it.id == eventID }
                }
                val eventLines = events.map { event ->
                    val summary = event.summary.trim()
                    if (summary.startsWith("- ")) summary else "- $summary"
                }
                files.add(
                    NovelWorkspaceFile(
                        path = "$prefix/plot/events.md",
                        content = NovelWorkspaceMarkdown.render(
                            fields = listOf(
                                "id" to snapshot.id.rawValue,
                                "kind" to "plot",
                                "title" to "事件",
                            ),
                            body = eventLines.joinToString("\n"),
                        ),
                    ),
                )
            }

            document.chapterPlans.firstOrNull { it.branchID == branch.id }?.let { plan ->
                files.add(
                    NovelWorkspaceFile(
                        path = "$prefix/plan/this-chapter.md",
                        content = NovelWorkspaceMarkdown.render(
                            fields = listOf(
                                "id" to plan.id.rawValue,
                                "kind" to "plan",
                                "title" to "本章计划",
                                "status" to plan.status.serialName(),
                            ),
                            body = planMarkdown(plan),
                        ),
                    ),
                )
            }
            document.upcomingArcs.firstOrNull { it.branchID == branch.id }?.let { arc ->
                files.add(
                    NovelWorkspaceFile(
                        path = "$prefix/plan/upcoming.md",
                        content = NovelWorkspaceMarkdown.render(
                            fields = listOf(
                                "id" to branch.id.rawValue,
                                "kind" to "plan",
                                "title" to "往后几章",
                            ),
                            body = arc.beats.joinToString("\n") { "- $it" },
                        ),
                    ),
                )
            }

            val usedOverrideNames = mutableSetOf<String>()
            for (revisionID in branch.overrideRevisionIDs) {
                val revision = document.materialRevisions.firstOrNull { it.id == revisionID } ?: continue
                val material = document.materials.firstOrNull { it.id == revision.materialID } ?: continue
                val leaf = NovelWorkspaceSlug.reservedPath(
                    NovelWorkspaceSlug.slug(revision.title),
                    usedOverrideNames,
                    revision.id.rawValue,
                )
                val fields = mutableListOf(
                    "id" to material.id.rawValue,
                    "kind" to "material",
                    "title" to revision.title,
                    "materialKind" to materialKindName(material.kind),
                    "injection" to revision.injectionMode.rawValue,
                    "sourceVersionID" to revision.id.rawValue,
                    "override" to "true",
                )
                if (material.kind is NovelMaterialKind.Custom) {
                    fields += "customName" to (material.kind as NovelMaterialKind.Custom).value
                }
                files.add(
                    NovelWorkspaceFile(
                        path = "$prefix/setting/${materialFolder(material.kind)}/$leaf.md",
                        content = NovelWorkspaceMarkdown.render(fields, aliases = revision.aliases, body = revision.content),
                    ),
                )
            }
        }

        val usedInboxNames = mutableSetOf<String>()
        for (proposal in document.settingProposals.filter { !it.isResolved }) {
            val name = NovelWorkspaceSlug.reservedPath(
                NovelWorkspaceSlug.slug(proposal.title),
                usedInboxNames,
                proposal.id.rawValue,
            )
            files.add(
                NovelWorkspaceFile(
                    path = "inbox/$name.md",
                    content = NovelWorkspaceMarkdown.render(
                        fields = listOf(
                            "id" to proposal.id.rawValue,
                            "kind" to "material",
                            "title" to proposal.title,
                            "materialKind" to "custom",
                        ),
                        body = proposal.content,
                    ),
                ),
            )
        }

        val usedDraftNames = mutableSetOf<String>()
        for (candidate in document.candidates.filter { it.status == NovelCandidateStatus.Available }) {
            val name = NovelWorkspaceSlug.reservedPath(
                candidate.id.rawValue.take(8),
                usedDraftNames,
                candidate.id.rawValue,
            )
            files.add(
                NovelWorkspaceFile(
                    path = "drafts/$name.md",
                    content = NovelWorkspaceMarkdown.render(
                        fields = listOf(
                            "id" to candidate.id.rawValue,
                            "kind" to "chapter",
                            "title" to "未收录草稿",
                        ),
                        body = candidate.content,
                    ),
                ),
            )
        }

        return files.sortedBy { it.path }
    }

    /** Locked decision A: sessions survive local migration inside the ledger. */
    fun sessionsFile(document: NovelProjectDocumentV1): NovelWorkspaceSessionsFile {
        val sessions = document.sessions.groupBy { it.branchID.rawValue }.mapValues { (_, session) ->
            val messages = session.flatMap { it.messages }.sortedBy { it.sequence }
            messages.map { message ->
                NovelWorkspaceSessionMessage(
                    id = message.id.rawValue,
                    role = roleName(message.role),
                    kind = kindName(message.kind),
                    content = message.content,
                    createdAt = message.createdAt,
                )
            }
        }
        return NovelWorkspaceSessionsFile(sessions = sessions)
    }

    private fun discardedVersion(
        chapterID: String,
        branchID: String,
        document: NovelProjectDocumentV1,
    ): app.amber.feature.novel.model.NovelChapterVersionRecord? {
        val branch = document.branches.firstOrNull { it.id.rawValue == branchID }
        val selection = branch?.workingChapterSelections?.firstOrNull { it.chapterID.rawValue == chapterID }
        if (selection != null) {
            document.chapterVersions.firstOrNull {
                it.id == selection.versionID && it.chapterID.rawValue == chapterID
            }?.let { return it }
        }
        return document.chapterVersions
            .filter { it.chapterID.rawValue == chapterID }
            .maxByOrNull { it.createdAt }
    }

    private fun planMarkdown(plan: app.amber.feature.novel.model.NovelChapterPlanRecord): String {
        val sections = mutableListOf<String>()
        if (plan.outlinePlacement.isNotEmpty()) sections.add("## 位置\n\n${plan.outlinePlacement}")
        if (plan.goalAndConflict.isNotEmpty()) sections.add("## 目标与冲突\n\n${plan.goalAndConflict}")
        if (plan.mustHappen.isNotEmpty()) {
            sections.add("## 必须发生\n\n" + plan.mustHappen.joinToString("\n") { "- $it" })
        }
        if (plan.mustNotHappen.isNotEmpty()) {
            sections.add("## 不可发生\n\n" + plan.mustNotHappen.joinToString("\n") { "- $it" })
        }
        if (plan.visibleFacts.isNotEmpty()) {
            sections.add("## 可见事实\n\n" + plan.visibleFacts.joinToString("\n") { "- $it" })
        }
        if (plan.endingHook.isNotEmpty()) sections.add("## 收束\n\n${plan.endingHook}")
        return sections.joinToString("\n\n")
    }

    private fun materialFolder(kind: NovelMaterialKind): String = when (kind) {
        NovelMaterialKind.World -> "world"
        NovelMaterialKind.MasterOutline -> "outline"
        NovelMaterialKind.WritingRequirements -> "writing"
        NovelMaterialKind.DecisionLog -> "log"
        NovelMaterialKind.Character -> "characters"
        NovelMaterialKind.Relationship -> "relationships"
        is NovelMaterialKind.Custom -> "custom"
    }

    private fun materialKindName(kind: NovelMaterialKind): String = when (kind) {
        NovelMaterialKind.World -> "world"
        NovelMaterialKind.Character -> "character"
        NovelMaterialKind.Relationship -> "relationship"
        NovelMaterialKind.MasterOutline -> "masterOutline"
        NovelMaterialKind.WritingRequirements -> "writingRequirements"
        NovelMaterialKind.DecisionLog -> "decisionLog"
        is NovelMaterialKind.Custom -> "custom"
    }

    private fun collaborationModeName(mode: NovelCollaborationMode): String = when (mode) {
        NovelCollaborationMode.Cocreation -> "cocreation"
        NovelCollaborationMode.Ghostwrite -> "ghostwrite"
    }

    private fun roleName(role: NovelSessionRole): String = when (role) {
        NovelSessionRole.User -> "user"
        NovelSessionRole.Assistant -> "assistant"
        NovelSessionRole.System -> "system"
    }

    private fun kindName(kind: NovelSessionMessageKind): String = when (kind) {
        NovelSessionMessageKind.UserInput -> "userInput"
        NovelSessionMessageKind.Discussion -> "discussion"
        NovelSessionMessageKind.ProseCandidate -> "proseCandidate"
        NovelSessionMessageKind.PolishCandidate -> "polishCandidate"
        NovelSessionMessageKind.InterruptedDraft -> "interruptedDraft"
        NovelSessionMessageKind.Error -> "error"
    }

    private fun app.amber.feature.novel.model.NovelChapterPlanStatus.serialName(): String = when (this) {
        app.amber.feature.novel.model.NovelChapterPlanStatus.Draft -> "draft"
        app.amber.feature.novel.model.NovelChapterPlanStatus.Confirmed -> "confirmed"
    }
}

private val app.amber.feature.novel.model.NovelBranchSyncStatus.rawValue: String
    get() = when (this) {
        app.amber.feature.novel.model.NovelBranchSyncStatus.Synchronized -> "synchronized"
        app.amber.feature.novel.model.NovelBranchSyncStatus.NeedsSync -> "needsSync"
    }

private val app.amber.feature.novel.model.NovelInjectionMode.rawValue: String
    get() = when (this) {
        app.amber.feature.novel.model.NovelInjectionMode.Always -> "always"
        app.amber.feature.novel.model.NovelInjectionMode.Smart -> "smart"
        app.amber.feature.novel.model.NovelInjectionMode.Off -> "off"
    }
