package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceParsed
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in replay of the real device package; private book content stays outside the repo. */
class NovelWorkspaceLargePackageImportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun actualLargePackageImportsIntoAReadableBookWithDiscussions() {
        val path = System.getenv("AMBER_NOVEL_IMPORT_REPRO_PATH")
        assumeTrue("Set AMBER_NOVEL_IMPORT_REPRO_PATH to replay the device package", !path.isNullOrBlank())
        val source = File(path!!)
        assertTrue("Replay must exercise a large package", source.length() > 64 * 1024 * 1024)
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))

        val imported = NovelWorkspaceProjectImport.importProject(source.readBytes(), repository)

        val store = NovelWorkspaceStore(imported.projectDirectory)
        val parsed = NovelWorkspaceParsed.parse(store.list().map { NovelWorkspaceFile(it, store.read(it)!!) })
        assertTrue(parsed.hasKnownFormat)
        // Independently counted from the captured source JSON, without decoding it again here.
        assertEquals(53, parsed.workingChapters.size)
        assertEquals(5, parsed.discardedChapters.size)
        assertEquals(19, parsed.materials.size)
        assertEquals(596, NovelWorkspaceSessions.load(imported.projectDirectory).sessions.values.flatten().size)
    }
}
