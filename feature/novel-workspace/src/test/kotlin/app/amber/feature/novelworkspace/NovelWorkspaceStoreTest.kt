package app.amber.feature.novelworkspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(): NovelWorkspaceStore {
        val root = tempFolder.newFolder("project")
        return NovelWorkspaceStore(root)
    }

    @Test
    fun `write read delete round-trip with nested directories`() {
        val store = store()
        store.write("branches/主线/chapters/001-山呼.md", "正文")
        assertEquals("正文", store.read("branches/主线/chapters/001-山呼.md"))
        assertTrue(store.exists("branches/主线/chapters/001-山呼.md"))
        assertTrue(store.delete("branches/主线/chapters/001-山呼.md"))
        assertNull(store.read("branches/主线/chapters/001-山呼.md"))
    }

    @Test
    fun `list returns only md and yaml, skips hidden trees, sorted`() {
        val store = store()
        store.write("manifest.yaml", "format: amber.novel.workspace\n")
        store.write("project.md", "---\nkind: project\n---\n")
        store.write("setting/characters/赵匡胤.md", "x")
        // The ledger is host-owned and never goes through the book store.
        File(store.rootDirectory, ".amber").mkdirs()
        File(store.rootDirectory, ".amber/commits.json").writeText("{}")
        File(store.rootDirectory, ".amber/checkout").mkdirs()
        File(store.rootDirectory, ".amber/checkout/leak.md").writeText("no")
        File(store.rootDirectory, "notes.txt").writeText("no")
        assertEquals(
            listOf("manifest.yaml", "project.md", "setting/characters/赵匡胤.md"),
            store.list(),
        )
        assertEquals(listOf("setting/characters/赵匡胤.md"), store.list("setting"))
    }

    @Test
    fun `file tree excludes manifest and hashes content`() {
        val store = store()
        store.write("manifest.yaml", "m")
        store.write("a.md", "hello")
        val tree = store.fileTree()
        assertEquals(mapOf("a.md" to sha256Hex("hello")), tree)
    }

    @Test
    fun `verify detects tampered and missing files`() {
        val store = store()
        store.write("a.md", "hello")
        store.write("b.md", "world")
        val tree = store.fileTree()
        store.write("a.md", "tampered")
        store.delete("b.md")
        assertEquals(listOf("a.md", "b.md"), store.verify(tree))
        assertEquals(emptyList<String>(), store.verify(emptyMap()))
    }

    @Test
    fun `materialize checkout mirrors the book under the ledger`() {
        val store = store()
        store.write("project.md", "p")
        store.write("branches/主线/plot/current.md", "剧情")
        store.materializeCheckout()
        assertEquals("p", File(store.checkoutDirectory, "project.md").readText())
        assertEquals("剧情", File(store.checkoutDirectory, "branches/主线/plot/current.md").readText())
        // Checkout lives under .amber so it never pollutes the exported tree.
        assertFalse(store.list().any { it.startsWith(".amber") })
    }

    @Test
    fun `path validation rejects escapes, absolutes, and hidden trees`() {
        val store = store()
        for (bad in listOf("", "/abs", "..", "a/../b", ".amber/commits.json", "a\\b", "a//b")) {
            try {
                store.read(bad)
                fail("expected rejection for '$bad'")
            } catch (expected: IllegalArgumentException) {
                // ok
            }
        }
    }

    @Test
    fun `exists reflects manifest presence`() {
        val store = store()
        assertFalse(store.exists())
        store.write("manifest.yaml", "format: amber.novel.workspace\n")
        assertTrue(store.exists())
    }
}
