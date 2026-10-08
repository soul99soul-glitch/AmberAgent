package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceEffectiveMaterials
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.sha256Hex
import java.io.File
import java.util.UUID

/** The catalog edits effective material files; stable identities survive author renames. */
object NovelWorkspaceMaterialActions {
    private val directories = mapOf(
        "world" to "world", "character" to "characters", "relationship" to "relationships",
        "masterOutline" to "outline", "writingRequirements" to "writing", "custom" to "custom",
    )
    val kinds: Set<String> = directories.keys

    fun create(
        runtime: NovelWorkspaceRuntime, directory: File, branchId: String, branchSlug: String,
        materialKind: String, title: String, body: String,
    ): NovelWorkspaceCommit {
        val normalizedTitle = singleLineTitle(title)
        require(normalizedTitle.isNotBlank() && body.isNotBlank()) { "请填写资料标题和内容" }
        val category = directories[materialKind] ?: throw NovelWorkspaceIoError("不支持的资料类型")
        val id = UUID.randomUUID().toString().uppercase()
        val path = "setting/$category/$id.md"
        val content = NovelWorkspaceMarkdown.render(
            listOf("id" to id, "kind" to "material", "materialKind" to materialKind, "title" to normalizedTitle,
                "injection" to if (materialKind in setOf("masterOutline", "writingRequirements")) "always" else "smart"),
            body = body,
        )
        return apply(runtime, directory, branchId, branchSlug, mapOf(path to content))
    }

    fun save(
        runtime: NovelWorkspaceRuntime, directory: File, branchId: String, branchSlug: String,
        path: String, title: String, body: String, expectedContent: String,
    ): NovelWorkspaceCommit {
        val normalizedTitle = singleLineTitle(title)
        require(normalizedTitle.isNotBlank()) { "请填写资料标题" }
        val base = snapshot(directory, branchId)
        requireEffective(directory, branchSlug, path, expectedContent)
        val content = NovelWorkspaceMarkdown.withBody(
            NovelWorkspaceMarkdown.withFields(expectedContent, mapOf("title" to normalizedTitle)), body,
        )
        return apply(runtime, directory, branchId, branchSlug, mapOf(path to content), base = base)
    }

    fun delete(
        runtime: NovelWorkspaceRuntime, directory: File, branchId: String, branchSlug: String,
        path: String, expectedContent: String,
    ): NovelWorkspaceCommit {
        val base = snapshot(directory, branchId)
        requireEffective(directory, branchSlug, path, expectedContent)
        return apply(runtime, directory, branchId, branchSlug, mapOf(path to null), base = base)
    }

    fun archiveDecision(
        runtime: NovelWorkspaceRuntime, directory: File, branchId: String, branchSlug: String,
        sourceMessageId: String, title: String, body: String,
    ): NovelWorkspaceCommit {
        val normalizedTitle = singleLineTitle(title)
        require(sourceMessageId.isNotBlank() && normalizedTitle.isNotBlank() && body.isNotBlank()) { "请填写决定标题和内容" }
        val base = snapshot(directory, branchId)
        val path = "${NovelWorkspacePaths.branchPrefix(branchSlug)}/setting/decisions/${sha256Hex(sourceMessageId).take(24)}.md"
        val existing = NovelWorkspaceStore(directory).read(path)
        val content = if (existing == null) {
            NovelWorkspaceMarkdown.render(
                listOf("id" to UUID.nameUUIDFromBytes("decision:$branchId:$sourceMessageId".toByteArray(Charsets.UTF_8)).toString().uppercase(),
                    "kind" to "material", "materialKind" to "decisionLog", "title" to normalizedTitle,
                    "injection" to "always", "sourceMessageId" to sourceMessageId),
                body = body,
            )
        } else {
            NovelWorkspaceMarkdown.withBody(NovelWorkspaceMarkdown.withFields(existing, mapOf("title" to normalizedTitle)), body)
        }
        return apply(runtime, directory, branchId, branchSlug, mapOf(path to content), NovelWorkspaceLedger.Message.DISCUSSION_ARCHIVE, base)
    }

    private fun requireEffective(directory: File, branchSlug: String, path: String, expectedContent: String) {
        val store = NovelWorkspaceStore(directory)
        if (NovelWorkspaceEffectiveMaterials.collect(store, branchSlug).none { it.path == path }) {
            throw NovelWorkspaceIoError("资料不属于当前分支，请重新打开资料列表")
        }
        if (store.read(path) != expectedContent) throw NovelWorkspaceIoError("资料版本已变化，请重新打开后再保存")
    }

    private fun apply(
        runtime: NovelWorkspaceRuntime, directory: File, branchId: String, branchSlug: String,
        changes: Map<String, String?>, message: String = NovelWorkspaceLedger.Message.MANUAL_EDIT,
        base: Pair<String?, String> = snapshot(directory, branchId),
    ): NovelWorkspaceCommit {
        return runtime.commitAuthorChanges(directory, branchId, branchSlug, changes, base.first, base.second, message)
    }

    private fun snapshot(directory: File, branchId: String): Pair<String?, String> {
        val store = NovelWorkspaceStore(directory)
        val ledger = NovelWorkspaceLedger.load(directory)
        return ledger.headOf(branchId)?.id to NovelWorkspaceLedger.treeSHA256(store.fileTree())
    }

    private fun singleLineTitle(title: String): String = title.replace(Regex("[\\r\\n]+"), " ").trim()
}
