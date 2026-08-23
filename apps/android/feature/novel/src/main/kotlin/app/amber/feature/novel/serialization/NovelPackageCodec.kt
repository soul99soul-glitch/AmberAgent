package app.amber.feature.novel.serialization

import app.amber.feature.novel.domain.NovelDocumentValidator
import app.amber.feature.novel.domain.NovelError
import app.amber.feature.novel.domain.NovelLegacyForkMigration
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelRunStatus

object NovelPackageCodec {
    fun encode(document: NovelProjectDocumentV1): NovelPackageEnvelope {
        NovelDocumentValidator.validate(document)
        return NovelSwiftCompatibleJson.encodePackageFromDocument(document)
    }

    fun decode(bytes: ByteArray): NovelProjectDocumentV1 {
        if (bytes.isEmpty()) throw NovelError.InvalidPackage("empty package")
        if (bytes.size > NovelSwiftWireContract.MAX_ENVELOPE_BYTES) {
            throw NovelError.PackageTooLarge(NovelSwiftWireContract.MAX_ENVELOPE_BYTES)
        }
        return try {
            val doc = NovelLegacyForkMigration.migrate(
                NovelSwiftCompatibleJson.decodeProjectDocumentFromPackage(bytes),
            )
            // Import normalization: running runs become interrupted
            val normalized = doc.copy(
                activeRuns = doc.activeRuns.map { run ->
                    if (run.status == NovelRunStatus.Running) {
                        run.copy(status = NovelRunStatus.Interrupted)
                    } else run
                },
                branches = doc.branches.map { branch ->
                    if (branch.activeRunID != null &&
                        doc.activeRuns.any { it.id == branch.activeRunID && it.status == NovelRunStatus.Running }
                    ) {
                        branch.copy(activeRunID = null)
                    } else branch
                },
            )
            NovelDocumentValidator.validate(normalized)
            normalized
        } catch (error: NovelError) {
            throw error
        } catch (error: Exception) {
            throw NovelError.InvalidPackage(error.message ?: "decode failed")
        }
    }

    fun exportMarkdown(document: NovelProjectDocumentV1, branchId: app.amber.feature.novel.model.NovelBranchId): String {
        if (document.activeRuns.any { it.status == NovelRunStatus.Running } ||
            document.pendingOperations.isNotEmpty()
        ) {
            throw NovelError.ProjectBusy(document.project.id)
        }
        val branch = document.branches.firstOrNull { it.id == branchId }
            ?: throw NovelError.BranchNotFound(branchId)
        val lines = mutableListOf("# ${document.project.name}", "")
        for (sel in branch.workingChapterSelections) {
            val version = document.chapterVersions.firstOrNull { it.id == sel.versionID } ?: continue
            lines += "## ${version.title}"
            lines += ""
            lines += version.content
            lines += ""
        }
        return lines.joinToString("\n")
    }
}
