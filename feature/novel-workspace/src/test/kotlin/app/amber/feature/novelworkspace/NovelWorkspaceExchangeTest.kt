package app.amber.feature.novelworkspace

import java.io.ByteArrayInputStream
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceExchangeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val exportedAt = Instant.parse("2026-08-19T00:00:00Z")

    private fun bookFiles(): List<NovelWorkspaceFile> = listOf(
        NovelWorkspaceFile(
            "manifest.yaml",
            NovelWorkspaceManifestRenderer.render(
                exportedAt = exportedAt,
                sourceProjectID = "p-1",
                sourceProjectRevision = 1,
                sourceSchemaVersion = 1,
                mainBranch = "主线",
            ),
        ),
        NovelWorkspaceFile(
            "project.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "p-1", "kind" to "project", "title" to "赵大来了"),
                body = "",
            ),
        ),
        NovelWorkspaceFile(
            "branches/主线/branch.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf(
                    "id" to "b-1",
                    "kind" to "branch",
                    "title" to "主线",
                    "syncStatus" to "synchronized",
                ),
                body = "",
            ),
        ),
        NovelWorkspaceFile(
            "branches/主线/chapters/001-山呼.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf(
                    "id" to "c-1",
                    "kind" to "chapter",
                    "title" to "山呼",
                    "ordinal" to "1",
                ),
                body = "陈桥驿的风先到。",
            ),
        ),
        NovelWorkspaceFile(
            "setting/characters/赵匡胤.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "m-1", "kind" to "material", "title" to "赵匡胤", "materialKind" to "character"),
                aliases = listOf("赵大"),
                body = "后周殿前司都点检。",
            ),
        ),
    )

    @Test
    fun `zip export and import round-trips the book`() {
        val sourceDir = tempFolder.newFolder("source")
        NovelWorkspaceInstaller.install(bookFiles(), sourceDir)

        val zip = NovelWorkspaceExchange.exportZipBytes(sourceDir)
        val targetDir = tempFolder.root.resolve("imported")
        val result = NovelWorkspaceExchange.importZip(ByteArrayInputStream(zip), targetDir)

        // Import always creates a fresh project; branch id comes from branch.md.
        assertEquals("b-1", result.mainBranchId)
        assertTrue(targetDir.resolve("branches/主线/chapters/001-山呼.md").exists())
        assertEquals("陈桥驿的风先到。", NovelWorkspaceStore(targetDir).read("branches/主线/chapters/001-山呼.md")
            ?.let { NovelWorkspaceMarkdown.parseFile(it).body })

        // Ledger: exactly one initial commit pinning the installed tree.
        val ledger = NovelWorkspaceLedger.load(targetDir)
        assertEquals(1, ledger.commits.size)
        assertEquals(NovelWorkspaceLedger.Message.INITIAL, ledger.commits.single().message)
        assertEquals(result.initialCommitId, ledger.head)
        assertEquals(result.initialCommitId, ledger.heads["b-1"])
        assertTrue(ledger.commits.single().files.containsKey("branches/主线/chapters/001-山呼.md"))
        // The export never travels with the ledger.
        assertTrue(NovelWorkspaceExchange.readZipFiles(ByteArrayInputStream(zip)).none {
            it.path.startsWith(".amber")
        })
    }

    @Test
    fun `import rejects unknown formats`() {
        val foreign = bookFiles().map { file ->
            if (file.path == "manifest.yaml") {
                NovelWorkspaceFile("manifest.yaml", "format: something.else\nformatVersion: 1\n")
            } else {
                file
            }
        }
        val zipBytes = zipOf(foreign)
        try {
            NovelWorkspaceExchange.importZip(ByteArrayInputStream(zipBytes), tempFolder.root.resolve("x"))
            fail("expected format rejection")
        } catch (expected: NovelWorkspaceFormatError) {
            // ok
        }
    }

    @Test
    fun `import rejects a missing manifest`() {
        val zipBytes = zipOf(bookFiles().filter { it.path != "manifest.yaml" })
        try {
            NovelWorkspaceExchange.importZip(ByteArrayInputStream(zipBytes), tempFolder.root.resolve("y"))
            fail("expected manifest rejection")
        } catch (expected: NovelWorkspaceFormatError) {
            // ok
        }
    }

    @Test
    fun `zip reader rejects non-utf8 payloads`() {
        val buffer = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.yaml"))
            zip.write(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00))
            zip.closeEntry()
        }
        try {
            NovelWorkspaceExchange.readZipFiles(ByteArrayInputStream(buffer.toByteArray()))
            fail("expected UTF-8 rejection")
        } catch (expected: NovelWorkspaceFormatError) {
            // ok
        }
    }

    @Test
    fun `installer refuses a non-empty target`() {
        val occupied = tempFolder.newFolder("occupied")
        occupied.resolve("stray.md").writeText("x")
        try {
            NovelWorkspaceInstaller.install(bookFiles(), occupied)
            fail("expected non-empty rejection")
        } catch (expected: NovelWorkspaceFormatError) {
            // ok
        }
    }

    @Test
    fun `export then import preserves node extensions (status, relations, foreshadowing)`() {
        // The interchange contract must carry the consistency-engine node schema
        // losslessly; iOS must likewise preserve these as opaque data.
        val sourceDir = tempFolder.newFolder("src-nodebook")
        val store = NovelWorkspaceStore(sourceDir)
        store.write(
            "manifest.yaml",
            NovelWorkspaceManifestRenderer.render(
                exportedAt = exportedAt,
                sourceProjectID = "p-node",
                sourceProjectRevision = 1,
                sourceSchemaVersion = 1,
                mainBranch = "主线",
            ),
        )
        store.write("project.md", NovelWorkspaceMarkdown.render(
            fields = listOf("id" to "p-node", "kind" to "project", "title" to "节点书"),
            body = "",
        ))
        store.write(
            "setting/characters/赵匡胤.md",
            """
            ---
            kind: material
            materialKind: character
            title: 赵匡胤
            status: 殿前司都点检
            aliases:
              - 官家
            relations:
              - {with: 赵大, type: 结拜兄弟}
            ---
            正文。
            """.trimIndent(),
        )
        store.write(
            "branches/主线/plot/foreshadowing/黄龙旗.md",
            """
            ---
            kind: foreshadowing
            title: 黄龙旗
            status: open
            ---
            应在兵变夜揭晓。
            """.trimIndent(),
        )

        val zip = NovelWorkspaceExchange.exportZipBytes(sourceDir)
        val files = NovelWorkspaceExchange.readZipFiles(ByteArrayInputStream(zip))
        val targetDir = tempFolder.root.resolve("reimported")
        NovelWorkspaceInstaller.install(files, targetDir)

        val reimported = NovelWorkspaceStore(targetDir)
        val nodes = NovelWorkspaceNodes.collect(reimported, "主线")
        val zhao = nodes.firstOrNull { it.title == "赵匡胤" }
        assertEquals("殿前司都点检", zhao?.status)
        assertEquals(listOf("官家"), zhao?.aliases)
        assertEquals("赵大", zhao?.relations?.singleOrNull()?.withRef)
        assertEquals("结拜兄弟", zhao?.relations?.singleOrNull()?.type)
        val open = NovelWorkspaceNodes.openForeshadowing(nodes)
        assertEquals(listOf("黄龙旗"), open.map { it.title })
    }

    private fun zipOf(files: List<NovelWorkspaceFile>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buffer).use { zip ->
            for (file in files) {
                zip.putNextEntry(java.util.zip.ZipEntry(file.path))
                zip.write(file.content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
}
