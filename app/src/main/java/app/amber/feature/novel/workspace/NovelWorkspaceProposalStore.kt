package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Pending author approvals live beside the ledger, independently of a screen/runtime. */
object NovelWorkspaceProposalStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(directory: File) = File(directory, "${NovelWorkspaceLedger.DIRECTORY_NAME}/proposals.json")

    @Synchronized
    fun load(directory: File): List<NovelWorkspaceWriteProposal> {
        val source = file(directory)
        if (!source.exists()) return emptyList()
        return try {
            json.decodeFromString<List<ProposalRow>>(source.readText(Charsets.UTF_8))
                .map { it.proposal(directory).also(::validate) }
                .also { proposals -> require(proposals.map { it.id }.distinct().size == proposals.size) }
        } catch (error: Exception) {
            throw NovelWorkspaceIoError("待审批改动读取失败，已保留原文件：${source.name}")
        }
    }

    fun add(proposal: NovelWorkspaceWriteProposal, expectedEpoch: Long? = null): List<NovelWorkspaceWriteProposal> =
        NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
            synchronized(this) {
                validate(proposal)
                val existing = load(proposal.projectDirectory)
                require(existing.none { it.id == proposal.id }) { "提案 ID 已存在" }
                (existing + proposal).also { save(proposal.projectDirectory, it) }
            }
        }

    fun edit(directory: File, id: String, entries: List<NovelWorkspaceWriteEntry>, expectedEpoch: Long? = null): List<NovelWorkspaceWriteProposal> =
        NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
            synchronized(this) {
                val existing = load(directory)
                val proposal = existing.firstOrNull { it.id == id } ?: throw NovelWorkspaceIoError("提案不存在，请重新打开工作区")
                require(entries.map { it.path } == proposal.entries.map { it.path }) { "编辑提案不能改变目标文件" }
                val updated = proposal.copy(entries = entries).also(::validate)
                existing.map { if (it.id == id) updated else it }.also { save(directory, it) }
            }
        }

    fun remove(directory: File, id: String, expectedEpoch: Long? = null): List<NovelWorkspaceWriteProposal> =
        NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
            synchronized(this) { load(directory).filterNot { it.id == id }.also { save(directory, it) } }
        }

    private fun validate(proposal: NovelWorkspaceWriteProposal) {
        require(proposal.id.isNotBlank() && proposal.branchId.isNotBlank() && proposal.branchSlug.isNotBlank())
        require(proposal.baseTreeDigest.isNotBlank() && proposal.entries.isNotEmpty())
        require(proposal.entries.map { it.path }.distinct().size == proposal.entries.size)
        proposal.entries.forEach { entry ->
            NovelWorkspacePaths.validate(entry.path)
            require(NovelWorkspacePaths.isProtectedPath(entry.path) &&
                entry.path.startsWith(NovelWorkspacePaths.branchPrefix(proposal.branchSlug) + "/")) {
                "提案只能修改对应分支的正文或剧情"
            }
        }
    }

    private fun save(directory: File, proposals: List<NovelWorkspaceWriteProposal>) {
        val destination = file(directory)
        val parent = destination.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs()) throw NovelWorkspaceIoError("无法创建待审批改动目录")
        val temp = File.createTempFile("novel-proposals-", ".tmp", parent)
        try {
            temp.writeText(json.encodeToString(proposals.map { ProposalRow.from(it) }), Charsets.UTF_8)
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    @Serializable
    private data class EntryRow(val path: String, val content: String, val reason: String? = null)

    @Serializable
    private data class ProposalRow(
        val id: String,
        val branchId: String,
        val branchSlug: String,
        val baseHeadId: String? = null,
        val baseTreeDigest: String,
        val planId: String? = null,
        val planDigest: String? = null,
        val entries: List<EntryRow>,
        val createdAt: String,
    ) {
        fun proposal(directory: File) = NovelWorkspaceWriteProposal(
            id, directory, branchId, branchSlug, baseHeadId, baseTreeDigest, planId, planDigest,
            entries.map { NovelWorkspaceWriteEntry(it.path, it.content, it.reason) }, Instant.parse(createdAt),
        )

        companion object {
            fun from(proposal: NovelWorkspaceWriteProposal) = ProposalRow(
                proposal.id, proposal.branchId, proposal.branchSlug, proposal.baseHeadId, proposal.baseTreeDigest,
                proposal.planId, proposal.planDigest,
                proposal.entries.map { EntryRow(it.path, it.content, it.reason) }, proposal.createdAt.toString(),
            )
        }
    }
}
