package app.amber.feature.novelworkspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The executable cross-platform contract check. The shell runner first asks the
 * current iOS exporter for a tree, then invokes this test with AMBER_W15_ROOT.
 * Keeping the test assumption-gated lets ordinary JVM suites run without an iOS
 * checkout while the named runner remains a real end-to-end check.
 */
class NovelWorkspaceExchangeRunnerTest {
    @Test
    fun `real iOS workspace imports modifies and exports for iOS importer`() {
        val rootPath = System.getenv("AMBER_W15_ROOT")
        assumeTrue("AMBER_W15_ROOT is required by the cross-platform runner", !rootPath.isNullOrBlank())
        val root = File(rootPath!!)
        val iosTree = root.resolve("ios-export")
        assertTrue("iOS exporter tree is missing: $iosTree", iosTree.isDirectory)

        val iosFiles = readTree(iosTree)
        val parsed = NovelWorkspaceParsed.parse(iosFiles)
        assertEquals("amber.novel.workspace", parsed.format)
        assertEquals(1, parsed.formatVersion)
        assertEquals("Test Novel", parsed.projectTitle)
        assertEquals(listOf("山呼"), parsed.workingChapters.map { it.title })
        assertEquals(listOf("水稿"), parsed.discardedChapters.map { it.title })
        assertEquals(listOf("赵大", "World Rule"), parsed.materials.map { it.title })
        assertEquals("赵大已在陈桥。", parsed.plotSummary)
        assertEquals(listOf("入汴"), parsed.upcomingBeats)

        val importedDirectory = root.resolve("android-import")
        importedDirectory.deleteRecursively()
        val result = NovelWorkspaceExchange.importZip(
            ByteArrayInputStream(zipOf(iosFiles)),
            importedDirectory,
        )
        assertEquals(parsed.mainBranchID, result.mainBranchId)
        assertFalse(result.plotMissing)

        val store = NovelWorkspaceStore(importedDirectory)
        val chapterPath = "branches/main/chapters/001-山呼.md"
        assertEquals("陈桥驿的风先到。", NovelWorkspaceMarkdown.parseFile(store.read(chapterPath)!!).body)
        assertEquals("赵大", NovelWorkspaceMarkdown.parseFile(store.read("setting/characters/赵大.md")!!).fields["title"])

        // This is the Android-side edit in the handoff, performed on the
        // imported tree before the existing production zip exporter runs.
        val projectPath = NovelWorkspacePaths.PROJECT_FILE
        val project = store.read(projectPath)!!
        store.write(projectPath, project.replace("title: Test Novel", "title: Test Novel (Android)"))

        val androidFiles = NovelWorkspaceExchange.readZipFiles(
            ByteArrayInputStream(NovelWorkspaceExchange.exportZipBytes(importedDirectory)),
        )
        assertTrue(androidFiles.any { it.path == projectPath })
        assertTrue(androidFiles.any { it.path == chapterPath })
        assertTrue(androidFiles.none { it.path.startsWith(".amber/") })
        assertEquals("Test Novel (Android)", androidFiles.first { it.path == projectPath }
            .let { NovelWorkspaceMarkdown.parseFile(it.content).fields["title"] })
        writeTree(androidFiles, root.resolve("android-export"))

        val ledger = NovelWorkspaceLedger.load(importedDirectory)
        assertEquals(1, ledger.commits.size)
        assertEquals(result.initialCommitId, ledger.head)
        writeText(
            root.resolve("android-export-metadata.txt"),
            buildString {
                appendLine("sourceFiles=${iosFiles.size}")
                appendLine("androidFiles=${androidFiles.size}")
                appendLine("mainBranchID=${result.mainBranchId}")
                appendLine("initialCommitID=${result.initialCommitId}")
                appendLine("exportedAt=${Instant.now()}")
            },
        )
    }

    private fun readTree(directory: File): List<NovelWorkspaceFile> = directory
        .walkTopDown()
        .filter { it.isFile && (it.extension == "md" || it.extension == "yaml") }
        .map { file ->
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            NovelWorkspaceFile(relative, file.readText(Charsets.UTF_8))
        }
        .sortedBy { it.path }
        .toList()

    private fun writeTree(files: List<NovelWorkspaceFile>, directory: File) {
        directory.deleteRecursively()
        assertTrue(directory.mkdirs())
        files.forEach { file ->
            NovelWorkspacePaths.validate(file.path)
            val target = directory.resolve(file.path)
            target.parentFile?.mkdirs()
            target.writeText(file.content, Charsets.UTF_8)
        }
    }

    private fun zipOf(files: List<NovelWorkspaceFile>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.path))
                zip.write(file.content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun writeText(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }
}
