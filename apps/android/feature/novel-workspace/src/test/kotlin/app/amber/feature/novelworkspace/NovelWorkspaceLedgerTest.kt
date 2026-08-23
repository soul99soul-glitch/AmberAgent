package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceLedgerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = Instant.parse("2026-08-19T08:00:00Z")

    private fun commit(id: String, parent: String? = null, files: Map<String, String> = emptyMap()) =
        NovelWorkspaceLedger.makeCommit(id, parent, files, NovelWorkspaceLedger.Message.COLLECTION, now)

    @Test
    fun `tree sha is order-insensitive and excludes nothing implicitly`() {
        val a = NovelWorkspaceLedger.treeSHA256(mapOf("a" to "1", "b" to "2"))
        val b = NovelWorkspaceLedger.treeSHA256(mapOf("b" to "2", "a" to "1"))
        assertEquals(a, b)
        assertTrue(a.length == 64)
    }

    @Test
    fun `make commit computes tree hash`() {
        val made = commit("c1", files = mapOf("chapters/001-x.md" to "deadbeef"))
        assertEquals(NovelWorkspaceLedger.treeSHA256(mapOf("chapters/001-x.md" to "deadbeef")), made.treeSHA256)
        assertEquals("c1", made.id)
        assertNull(made.parentId)
    }

    @Test
    fun `appending advances head and deduplicates by id`() {
        var store = NovelWorkspaceLedgerStore()
        val first = commit("c1")
        val second = commit("c2", parent = "c1")
        store = NovelWorkspaceLedger.appending(first, store)
        store = NovelWorkspaceLedger.appending(second, store)
        store = NovelWorkspaceLedger.appending(second, store)
        assertEquals("c2", store.head)
        assertEquals(2, store.commits.size)
        assertEquals(first, store.headCommit?.let { store.commit(it.parentId) })
    }

    @Test
    fun `ancestry walks parent chain oldest first`() {
        var store = NovelWorkspaceLedgerStore()
        store = NovelWorkspaceLedger.appending(commit("c1"), store)
        store = NovelWorkspaceLedger.appending(commit("c2", "c1"), store)
        store = NovelWorkspaceLedger.appending(commit("c3", "c2"), store)
        assertEquals(listOf("c1", "c2", "c3"), store.ancestry("c3").map { it.id })
        assertEquals(emptyList<String>(), store.ancestry(null).map { it.id })
    }

    @Test
    fun `status reports dirty paths, plot staleness, unresolved`() {
        val head = commit("c1", files = mapOf("a.md" to "1", "b.md" to "2"))
        val status = NovelWorkspaceLedger.status(
            head = head,
            working = mapOf("b.md" to "2", "c.md" to "3"),
            plotStale = true,
            unresolved = false,
        )
        assertEquals("c1", status.headID)
        assertEquals(listOf("a.md", "c.md", "plot/"), status.dirtyPaths)
        assertTrue(status.plotStale)
        assertFalse(status.unresolved)
    }

    @Test
    fun `save and load round-trip with ios wire shape`() {
        val dir = tempFolder.root
        var store = NovelWorkspaceLedgerStore()
        store = NovelWorkspaceLedger.appending(
            commit("c1", files = mapOf("chapters/001-a.md" to "ff")),
            store,
        )
        store = store.copy(heads = mapOf("branch-1" to "c1"))
        NovelWorkspaceLedger.save(store, dir)

        val raw = File(dir, ".amber/commits.json").readText()
        // Swift-compatible field names and second-precision ISO dates; null parent omitted.
        assertFalse(raw.contains("parentID"))
        assertTrue(raw.contains("\"createdAt\":\"2026-08-19T08:00:00Z\""))
        assertTrue(raw.contains("\"treeSHA256\""))

        val loaded = NovelWorkspaceLedger.load(dir)
        assertEquals(store, loaded)
        assertEquals("c1", loaded.heads["branch-1"])
    }

    @Test
    fun `omits null parent and empty heads`() {
        val store = NovelWorkspaceLedgerStore(
            head = "c1",
            commits = listOf(commit("c1")),
        )
        val raw = Json { encodeDefaults = false }.encodeToString(
            NovelWorkspaceLedgerStore.serializer(),
            store,
        )
        assertFalse(raw.contains("parentID"))
        assertFalse(raw.contains("heads"))
    }

    @Test
    fun `corrupt ledger is quarantined, not silently replaced`() {
        val dir = tempFolder.root
        File(dir, ".amber").mkdirs()
        val corrupt = File(dir, ".amber/commits.json")
        corrupt.writeText("{ not json")
        assertEquals(NovelWorkspaceLedgerStore(), NovelWorkspaceLedger.load(dir))
        // The unreadable file must survive as evidence; a later save must not
        // overwrite the only copy of the history without a trace.
        assertFalse(corrupt.exists())
        assertTrue(dir.resolve(".amber").list().orEmpty().any { it.startsWith("commits.json.corrupt-") })
    }

    @Test
    fun `missing ledger is empty`() {
        assertEquals(NovelWorkspaceLedgerStore(), NovelWorkspaceLedger.load(tempFolder.root))
    }
}
