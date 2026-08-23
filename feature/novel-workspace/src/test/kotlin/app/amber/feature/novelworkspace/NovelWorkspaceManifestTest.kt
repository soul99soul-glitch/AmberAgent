package app.amber.feature.novelworkspace

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelWorkspaceManifestTest {

    private val exportedAt = Instant.parse("2026-08-16T12:00:00Z")

    @Test
    fun `render emits sorted keys, bare numbers, nested source`() {
        val rendered = NovelWorkspaceManifestRenderer.render(
            exportedAt = exportedAt,
            sourceProjectID = "3F2A0C1A-1111-2222-3333-444444444444",
            sourceProjectRevision = 1112,
            sourceSchemaVersion = 1,
            mainBranch = "主线",
        )
        val expected = """
            exportedAt: 2026-08-16T12:00:00Z
            format: amber.novel.workspace
            formatVersion: 1
            mainBranch: 主线
            source:
              projectID: 3F2A0C1A-1111-2222-3333-444444444444
              projectRevision: 1112
              schemaVersion: 1
        """.trimIndent() + "\n"
        assertEquals(expected, rendered)
    }

    @Test
    fun `parse round-trips the rendered manifest`() {
        val rendered = NovelWorkspaceManifestRenderer.render(
            exportedAt = exportedAt,
            sourceProjectID = "abc",
            sourceProjectRevision = 7,
            sourceSchemaVersion = 1,
            mainBranch = "主线",
        )
        val manifest = NovelWorkspaceManifest.parse(rendered)
        assertEquals(NovelWorkspaceManifest.FORMAT, manifest.format)
        assertEquals(1, manifest.formatVersion)
        assertEquals("2026-08-16T12:00:00Z", manifest.exportedAt)
        assertEquals("abc", manifest.sourceProjectID)
        assertEquals(7L, manifest.sourceProjectRevision)
        assertEquals(1, manifest.sourceSchemaVersion)
        assertEquals("主线", manifest.mainBranch)
        assertTrue(manifest.isKnownFormat)
    }

    @Test
    fun `unknown format and version fail the gate`() {
        val foreign = NovelWorkspaceManifest.parse("format: something.else\nformatVersion: 1\n")
        assertFalse(foreign.isKnownFormat)
        val future = NovelWorkspaceManifest.parse(
            "format: amber.novel.workspace\nformatVersion: 99\n",
        )
        assertTrue(future.isKnownFormat)
        assertFalse(future.formatVersion == NovelWorkspaceManifest.FORMAT_VERSION)
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val manifest = NovelWorkspaceManifest.parse("format: amber.novel.workspace\nformatVersion: 1\n")
        assertNull(manifest.exportedAt)
        assertNull(manifest.sourceProjectID)
        assertEquals(NovelWorkspaceManifest.DEFAULT_MAIN_BRANCH, manifest.mainBranch)
    }
}
