package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Temporary scratch verification — do not commit. */
class ScratchVerifyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun commit(
        id: String,
        parent: String?,
        files: Map<String, String>,
        at: String = "2026-08-21T00:00:00Z",
    ) = NovelWorkspaceCommit(
        id = id,
        parentId = parent,
        createdAt = Instant.parse(at),
        message = "test",
        treeSHA256 = NovelWorkspaceLedger.treeSHA256(files),
        files = files,
    )

    private fun ch(store: NovelWorkspaceStore, n: Int, body: String = "正文$n") {
        val name = "%03d".format(n)
        store.write(
            "branches/主线/chapters/$name-章$n.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("kind" to "chapter", "title" to "章$n", "ordinal" to "$n"),
                body = body,
            ),
        )
    }

    private fun chFiles(n: Int): Map<String, String> {
        val m = mutableMapOf<String, String>()
        for (i in 1..n) {
            val name = "%03d".format(i)
            m["branches/主线/chapters/$name-章$i.md"] = "hash-$i"
        }
        return m
    }

    @Test
    fun `rewrite commit re-arms the unresolved gate at a shifted ordinal`() {
        val dir = tempFolder.newFolder("book")
        val store = NovelWorkspaceStore(dir)
        store.write("manifest.yaml", "format: amber.novel.workspace\nformatVersion: 1\n")
        for (i in 1..5) ch(store, i)

        // Root commit with all 5 chapters.
        val root = commit("ROOT", null, chFiles(5))
        // Middle-chapter edit (ch3) that arms the gate at ordinal 4.
        val edited = chFiles(5).toMutableMap()
        edited["branches/主线/chapters/003-章3.md"] = "hash-3-edited"
        val middleEdit = commit("MID", "ROOT", edited)

        // CommitTree hook would arm the gate at 4 for the middle edit.
        assertEquals(
            4,
            NovelWorkspaceLedger.firstUnresolvedOrdinalAfterEdit(store, NovelWorkspaceLedgerStore(head = "MID", commits = listOf(root, middleEdit)), "主线", middleEdit),
        )

        // A "rewrite later chapters" commit that rewrites ch4..ch5 in ONE commit.
        val rewritten = edited.toMutableMap()
        rewritten["branches/主线/chapters/004-章4.md"] = "hash-4-rewritten"
        rewritten["branches/主线/chapters/005-章5.md"] = "hash-5-rewritten"
        val rewriteCommit = commit("REW", "MID", rewritten)

        // The rewrite commit — which is exactly the chapter the gate intended to be resolved —
        // re-arms the gate at 5 instead of clearing it.
        val rearmed = NovelWorkspaceLedger.firstUnresolvedOrdinalAfterEdit(
            store,
            NovelWorkspaceLedgerStore(head = "REW", commits = listOf(root, middleEdit, rewriteCommit)),
            "主线",
            rewriteCommit,
        )
        assertEquals(5, rearmed)
        // Note: this is what runtime.commitTree feeds into NovelWorkspaceUnresolvedStore.set.
    }

    @Test
    fun `rewriting ONLY the newest chapter does not re-arm`() {
        val dir = tempFolder.newFolder("book2")
        val store = NovelWorkspaceStore(dir)
        store.write("manifest.yaml", "format: amber.novel.workspace\nformatVersion: 1\n")
        for (i in 1..5) ch(store, i)
        val root = commit("ROOT", null, chFiles(5))

        // Rewrite only the newest chapter (5).
        val newestOnly = chFiles(5).toMutableMap()
        newestOnly["branches/主线/chapters/005-章5.md"] = "hash-5-rewritten"
        val rewrite = commit("REW", "ROOT", newestOnly)
        assertNull(
            NovelWorkspaceLedger.firstUnresolvedOrdinalAfterEdit(
                store,
                NovelWorkspaceLedgerStore(head = "REW", commits = listOf(root, rewrite)),
                "主线",
                rewrite,
            ),
        )
    }

    @Test
    fun `renameProject breaks the head-vs-branch invariant`() {
        val dir = tempFolder.newFolder("book3")
        val repo = NovelWorkspaceProjectRepository(dir)
        val result = repo.createBlank("项目甲", mainBranchName = "主线")
        val branchSlug = "主线"
        val store = NovelWorkspaceStore(result.projectDirectory)
        // Add a chapter so there's a real canon commit to seed the branch head.
        store.write(
            "drafts/d1.md",
            NovelWorkspaceMarkdown.render(fields = listOf("title" to "d1"), body = "第一章正文。"),
        )
        // (We cannot use the app-module runtime here; simulate a canon commit via a manual
        // append + branching so the branch head points at it.)
        val ledger1 = NovelWorkspaceLedger.load(result.projectDirectory)
        val manual = NovelWorkspaceLedger.makeCommit(
            id = UUID.randomUUID().toString().uppercase(),
            parentId = ledger1.head,
            files = store.fileTree(),
            message = NovelWorkspaceLedger.Message.COLLECTION,
            createdAt = Instant.now(),
        )
        val withChapter = NovelWorkspaceLedger.appending(manual, ledger1).copy(
            heads = ledger1.heads + (ledger1.heads.keys.firstOrNull()!! to manual.id),
        )
        NovelWorkspaceLedger.save(withChapter, result.projectDirectory)

        // Invariant before rename: global head == branch head.
        val before = NovelWorkspaceLedger.load(result.projectDirectory)
        val branchId = before.heads.keys.first()
        assertEquals(before.head, before.heads[branchId])

        // Rename: updates project.md title and appends a commit via appending() only.
        repo.renameProject(result.projectDirectory.name.uppercase(), "项目乙")
        val after = NovelWorkspaceLedger.load(result.projectDirectory)
        // Head advanced to the rename commit...
        assertEquals("项目乙", NovelWorkspaceProjectTitle.read(NovelWorkspaceStore(result.projectDirectory)))
        assertNotEquals(before.head, after.head)
        // ...but the branch pointer did NOT follow (appending() only changes `head`).
        assertNotEquals(after.head, after.heads[branchId])
    }
}
