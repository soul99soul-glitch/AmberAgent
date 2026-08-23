package app.amber.feature.novelworkspace

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-exact goldens for the cross-platform wire contract.
 *
 * Scenario ported from iOS NovelWorkspaceBackupTests (《Test Novel》, chapter 山呼);
 * expected bytes are derived from the iOS exporter. If either platform changes a
 * single rendered byte, this test is the alarm.
 */
class NovelWorkspaceGoldenTest {

    private val exportedAt = Instant.parse("2026-08-19T00:00:00Z")

    @Test
    fun `manifest golden bytes`() {
        val rendered = NovelWorkspaceManifestRenderer.render(
            exportedAt = exportedAt,
            sourceProjectID = "3f2a0c1a-9b4e-4e4e-9e9e-000000000001",
            sourceProjectRevision = 5,
            sourceSchemaVersion = 1,
            mainBranch = "主线",
        )
        val expected = """
            exportedAt: 2026-08-19T00:00:00Z
            format: amber.novel.workspace
            formatVersion: 1
            mainBranch: 主线
            source:
              projectID: 3f2a0c1a-9b4e-4e4e-9e9e-000000000001
              projectRevision: 5
              schemaVersion: 1
        """.trimIndent() + "\n"
        assertEquals(expected, rendered)
    }

    @Test
    fun `chapter file golden bytes`() {
        // Field order = iOS exporter insertion order (id, kind, title, ordinal, sourceVersionID).
        val rendered = NovelWorkspaceMarkdown.render(
            fields = listOf(
                "id" to "3f2a0c1a-9b4e-4e4e-9e9e-0000000000c1",
                "kind" to "chapter",
                "title" to "山呼",
                "ordinal" to "1",
                "sourceVersionID" to "3f2a0c1a-9b4e-4e4e-9e9e-0000000000v1",
            ),
            body = "陈桥驿的风先到。",
        )
        val expected = """
            ---
            id: 3f2a0c1a-9b4e-4e4e-9e9e-0000000000c1
            kind: chapter
            title: 山呼
            ordinal: 1
            sourceVersionID: 3f2a0c1a-9b4e-4e4e-9e9e-0000000000v1
            ---

            陈桥驿的风先到。
        """.trimIndent() + "\n"
        assertEquals(expected, rendered)
    }

    @Test
    fun `plot current golden bytes with highlights`() {
        val summary = "赵大已在陈桥。"
        val highlights = listOf("山呼：陈桥驿的风先到。")
        val body = summary + "\n\n## 近期已写\n\n" + highlights.joinToString("\n") { "- $it" }
        val rendered = NovelWorkspaceMarkdown.render(
            fields = listOf(
                "id" to "3f2a0c1a-9b4e-4e4e-9e9e-0000000000s1",
                "kind" to "plot",
                "title" to "当前状态",
            ),
            body = body,
        )
        val expected = """
            ---
            id: 3f2a0c1a-9b4e-4e4e-9e9e-0000000000s1
            kind: plot
            title: 当前状态
            ---

            赵大已在陈桥。

            ## 近期已写

            - 山呼：陈桥驿的风先到。
        """.trimIndent() + "\n"
        assertEquals(expected, rendered)

        val (parsedSummary, parsedHighlights) = NovelWorkspaceMarkdown.splitHighlights(body)
        assertEquals(summary, parsedSummary)
        assertEquals(highlights, parsedHighlights)
    }

    @Test
    fun `chapter file name uses zero-padded ordinal identity`() {
        assertEquals("001-山呼.md", NovelWorkspacePaths.chapterFileName(1, "山呼"))
        assertEquals("030-山呼.md", NovelWorkspacePaths.chapterFileName(30, "山呼"))
        assertEquals(24, NovelWorkspacePaths.chapterOrdinalFromPath("branches/主线/chapters/024-山呼.md"))
        assertEquals("山呼", NovelWorkspacePaths.fileNameTitle("branches/主线/chapters/024-山呼.md"))
        assertEquals("赵匡胤", NovelWorkspacePaths.fileNameTitle("setting/characters/赵匡胤.md"))
    }

    @Test
    fun `canon gate protects chapters and plot only`() {
        assertEquals(true, NovelWorkspacePaths.isProtectedPath("branches/主线/chapters/001-山呼.md"))
        assertEquals(true, NovelWorkspacePaths.isProtectedPath("branches/主线/plot/current.md"))
        assertEquals(false, NovelWorkspacePaths.isProtectedPath("branches/主线/plan/this-chapter.md"))
        assertEquals(false, NovelWorkspacePaths.isProtectedPath("branches/主线/setting/characters/赵匡胤.md"))
        assertEquals(false, NovelWorkspacePaths.isProtectedPath("setting/characters/赵匡胤.md"))
        assertEquals(false, NovelWorkspacePaths.isProtectedPath("drafts/abc12345.md"))
        assertEquals(false, NovelWorkspacePaths.isProtectedPath("inbox/提案.md"))
    }

    @Test
    fun `free write whitelist matches ios`() {
        assertEquals(true, NovelWorkspacePaths.isFreeWritePath("setting/characters/赵匡胤.md"))
        assertEquals(true, NovelWorkspacePaths.isFreeWritePath("inbox/提案.md"))
        assertEquals(true, NovelWorkspacePaths.isFreeWritePath("drafts/abc12345.md"))
        assertEquals(true, NovelWorkspacePaths.isFreeWritePath("branches/主线/plan/this-chapter.md"))
        assertEquals(true, NovelWorkspacePaths.isFreeWritePath("branches/主线/setting/characters/赵匡胤.md"))
        // Host-owned files are neither protected-by-approval nor free: refuse.
        assertEquals(false, NovelWorkspacePaths.isFreeWritePath("manifest.yaml"))
        assertEquals(false, NovelWorkspacePaths.isFreeWritePath("project.md"))
        assertEquals(false, NovelWorkspacePaths.isFreeWritePath("branches/主线/branch.md"))
        assertEquals(false, NovelWorkspacePaths.isFreeWritePath("branches/主线/discarded/山呼.md"))
        assertEquals(false, NovelWorkspacePaths.isFreeWritePath("setting"))
    }
}
