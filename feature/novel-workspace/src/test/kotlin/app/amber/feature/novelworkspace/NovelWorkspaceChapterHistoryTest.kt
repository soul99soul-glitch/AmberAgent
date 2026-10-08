package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceChapterHistoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val path = "branches/主线/chapters/001-第一章.md"
    private val time = Instant.parse("2026-09-30T00:00:00Z")

    private fun commit(store: NovelWorkspaceStore, id: String): NovelWorkspaceCommit {
        val ledger = NovelWorkspaceLedger.load(store.rootDirectory)
        val commit = NovelWorkspaceLedger.makeCommit(id, ledger.heads["B-1"], store.fileTree(), id, time.plusSeconds(ledger.commits.size.toLong()))
        NovelWorkspaceLedger.save(ledger.copy(head = id, heads = mapOf("B-1" to id), commits = ledger.commits + commit), store.rootDirectory)
        return commit
    }

    @Test
    fun `each committed chapter version survives later writes and stays outside exchange`() {
        val store = NovelWorkspaceStore(temporaryFolder.newFolder("project"))
        store.write(path, "A")
        commit(store, "c1")
        store.write(path, "B")
        commit(store, "c2")
        store.write(path, "C")
        val chapterCommit = commit(store, "c3")
        store.write("setting/world.md", "Unrelated edit")
        commit(store, "c4")

        val snapshot = checkNotNull(NovelWorkspaceChapterHistory.snapshot(store.rootDirectory, "B-1", "主线", path))
        assertEquals("c4", snapshot.headId)
        assertEquals(listOf("C", "B", "A"), snapshot.versions.map { NovelWorkspaceChapterHistory.read(store.rootDirectory, it.contentHash) })
        assertEquals(chapterCommit.createdAt, snapshot.versions.first().createdAt)
        assertTrue(snapshot.versions.first().isCurrent)
        assertFalse(snapshot.versions.last().isCurrent)
        assertEquals(store.list().toSet(), NovelWorkspaceExchange.readZipFiles(NovelWorkspaceExchange.exportZipBytes(store.rootDirectory).inputStream()).map { it.path }.toSet())
        assertFalse(store.fileTree().keys.any { it.startsWith(".amber/") })
    }

    @Test
    fun `opening legacy history seeds only current available text`() {
        val store = NovelWorkspaceStore(temporaryFolder.newFolder("legacy"))
        val chapter = File(store.rootDirectory, path)
        chapter.parentFile.mkdirs()
        chapter.writeText("old")
        commit(store, "old-commit")
        chapter.writeText("current")
        commit(store, "current-commit")

        val snapshot = checkNotNull(NovelWorkspaceChapterHistory.snapshot(store.rootDirectory, "B-1", "主线", path))
        assertEquals(listOf(sha256Hex("current")), snapshot.versions.map { it.contentHash })
        assertEquals("current", NovelWorkspaceChapterHistory.read(store.rootDirectory, snapshot.currentContentHash))
        assertNull(NovelWorkspaceChapterHistory.read(store.rootDirectory, sha256Hex("old")))

        // A legacy chapter removed through the store also keeps its real last text.
        chapter.writeText("removed")
        assertTrue(store.delete(path))
        assertEquals("removed", NovelWorkspaceChapterHistory.read(store.rootDirectory, sha256Hex("removed")))
    }

    @Test
    fun `history failure leaves chapter write and deletion untouched`() {
        val store = NovelWorkspaceStore(temporaryFolder.newFolder("blocked"))
        val chapter = File(store.rootDirectory, path)
        chapter.parentFile.mkdirs()
        chapter.writeText("original")
        File(store.rootDirectory, ".amber").mkdirs()
        File(store.rootDirectory, ".amber/history").writeText("Blocks the directory")

        for (operation in listOf<() -> Unit>({ store.write(path, "replacement") }, { store.delete(path) })) {
            try {
                operation()
                fail("Expected history persistence to fail")
            } catch (_: java.io.IOException) {
                // Creating the blob temp file fails when history is a regular file.
            }
            assertEquals("original", chapter.readText())
        }
    }

    @Test
    fun `nested imported chapter captures old new and deleted text`() {
        val store = NovelWorkspaceStore(temporaryFolder.newFolder("nested"))
        val nestedPath = "branches/主线/chapters/卷一/001-第一章.md"
        val chapter = File(store.rootDirectory, nestedPath)
        chapter.parentFile.mkdirs()
        chapter.writeText("imported original")

        store.write(nestedPath, "edited text")
        assertEquals("imported original", NovelWorkspaceChapterHistory.read(store.rootDirectory, sha256Hex("imported original")))
        assertEquals("edited text", NovelWorkspaceChapterHistory.read(store.rootDirectory, sha256Hex("edited text")))

        chapter.writeText("external edit before removal")
        assertTrue(store.delete(nestedPath))
        assertEquals("external edit before removal", NovelWorkspaceChapterHistory.read(store.rootDirectory, sha256Hex("external edit before removal")))
        assertNull(store.read(nestedPath))
    }

    @Test
    fun `selected version read rejects changed blob bytes`() {
        val store = NovelWorkspaceStore(temporaryFolder.newFolder("corrupted"))
        store.write(path, "original")
        val hash = sha256Hex("original")
        File(store.rootDirectory, ".amber/history/$hash.md").writeText("tampered")
        assertNull(NovelWorkspaceChapterHistory.read(store.rootDirectory, hash))
    }
}
