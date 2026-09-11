package app.amber.feature.novelworkspace

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceCatalogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `catalog uses actual file changes and explicit resolved status`() {
        val directory = tempFolder.newFolder("catalog")
        val store = NovelWorkspaceStore(directory)
        val characterPath = "setting/characters/赵大.md"
        store.write(
            characterPath,
            NovelWorkspaceMarkdown.render(
                fields = listOf("kind" to "character", "title" to "赵大"),
                body = "殿前都点检。",
            ),
        )
        store.write("setting/写作要求.md", "古雅克制。")
        store.write(
            "branches/主线/plot/foreshadowing/黄龙旗.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf(
                    "kind" to NovelWorkspaceNodes.KIND_FORESHADOWING,
                    "title" to "黄龙旗",
                    "status" to "planned",
                ),
                body = "尚未回收。",
            ),
        )
        store.write(
            "branches/主线/plot/foreshadowing/旧伤.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf(
                    "kind" to NovelWorkspaceNodes.KIND_FORESHADOWING,
                    "title" to "旧伤",
                    "status" to NovelWorkspaceNodes.STATUS_RESOLVED,
                ),
                body = "已回收。",
            ),
        )

        val first = Instant.parse("2026-08-20T00:00:00Z")
        val unrelated = Instant.parse("2026-08-21T00:00:00Z")
        val ledger = NovelWorkspaceLedgerStore(
            head = "C-2",
            commits = listOf(
                NovelWorkspaceCommit(
                    id = "C-1",
                    createdAt = first,
                    message = NovelWorkspaceLedger.Message.INITIAL,
                    treeSHA256 = "tree-1",
                    files = mapOf(characterPath to "character-v1", "project.md" to "project-v1"),
                ),
                NovelWorkspaceCommit(
                    id = "C-2",
                    parentId = "C-1",
                    createdAt = unrelated,
                    message = NovelWorkspaceLedger.Message.MANUAL_EDIT,
                    treeSHA256 = "tree-2",
                    files = mapOf(characterPath to "character-v1", "project.md" to "project-v2"),
                ),
            ),
        )

        val catalog = NovelWorkspaceCatalog.load(store, ledger, "主线")
        val character = catalog.settingGroups
            .flatMap { it.entries }
            .single { it.path == characterPath }
        assertEquals(first, character.updatedAt)
        assertTrue(catalog.settingGroups.any { it.directory == NovelWorkspaceCatalog.ROOT_GROUP })

        val foreshadowing = catalog.foreshadowing.associateBy { it.title }
        assertFalse(foreshadowing.getValue("黄龙旗").resolved)
        assertTrue(foreshadowing.getValue("旧伤").resolved)
    }
}
