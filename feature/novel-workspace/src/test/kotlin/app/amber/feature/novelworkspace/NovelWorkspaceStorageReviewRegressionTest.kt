package app.amber.feature.novelworkspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceStorageReviewRegressionTest {
    @get:Rule val tempFolder = TemporaryFolder()
    private val now = Instant.parse("2026-10-02T00:00:00Z")

    private fun files() = listOf(
        NovelWorkspaceFile("manifest.yaml", NovelWorkspaceManifestRenderer.render(now, "source", 1, 1, "main")),
        NovelWorkspaceFile("project.md", NovelWorkspaceMarkdown.render(listOf("title" to "Book"), body = "")),
        NovelWorkspaceFile("branches/main/branch.md", NovelWorkspaceMarkdown.render(listOf("id" to "branch", "title" to "Main"), body = "")),
    )

    @Test
    fun failedInstallationNeverPublishesCompletedProjectAndSameIdCanRetry() {
        val repository = NovelWorkspaceProjectRepository(tempFolder.newFolder("books"))
        val projectId = "11111111-1111-4111-8111-111111111111"
        // Both paths are legal separately, but the second cannot be written below a file.
        val conflicting = files() + listOf(
            NovelWorkspaceFile("setting/world/rule.md", "rule"),
            NovelWorkspaceFile("setting/world/rule.md/child.md", "cannot be written"),
        )
        assertTrue(runCatching { repository.install(projectId, conflicting, now) }.isFailure)
        assertFalse("Manifest must publish only a completed installation", repository.exists(projectId))
        assertTrue(repository.listProjects().isEmpty())
        val retried = repository.install(projectId, files(), now)
        assertTrue(repository.exists(projectId))
        assertNotNull(NovelWorkspaceLedger.load(retried.projectDirectory).headCommit)
        assertEquals(
            NovelWorkspaceStore(retried.projectDirectory).read("manifest.yaml"),
            File(retried.projectDirectory, ".amber/checkout/manifest.yaml").readText(),
        )
    }

    @Test
    fun renameAdvancesMirroredBranchAndKeepsRepeatedRenamesInItsAncestry() {
        val repository = NovelWorkspaceProjectRepository(tempFolder.newFolder("rename"))
        val installed = repository.createBlank("Before", now = now)
        val projectId = installed.projectDirectory.name.uppercase()
        repository.renameProject(projectId, "First", now.plusSeconds(1))
        val first = NovelWorkspaceLedger.load(installed.projectDirectory)
        assertEquals(first.head, first.heads[installed.mainBranchId])
        repository.renameProject(projectId, "Second", now.plusSeconds(2))
        val second = NovelWorkspaceLedger.load(installed.projectDirectory)
        assertEquals(second.head, second.heads[installed.mainBranchId])
        assertEquals(first.head, second.headCommit?.parentId)
        assertEquals(listOf(installed.initialCommitId, first.head, second.head), second.ancestry(second.heads[installed.mainBranchId]).map { it.id })
        assertEquals("Second", NovelWorkspaceProjectTitle.read(NovelWorkspaceStore(installed.projectDirectory)))
    }

    @Test
    fun directBookWritesRejectEveryHiddenSegment() {
        val store = NovelWorkspaceStore(tempFolder.newFolder("hidden"))
        for (path in listOf("setting/.secret/rule.md", "setting/world/.rule.md", "branches/.side/branch.md", "setting/./rule.md")) {
            assertTrue("Hidden path accepted: $path", runCatching { store.write(path, "secret") }.isFailure)
        }
    }

    @Test
    fun hiddenQueryPrefixesStayEmptyButRejectAbsolutePathsAndParentEscapes() {
        val store = NovelWorkspaceStore(tempFolder.newFolder("queries"))
        store.write("setting/world/rule.md", "public")
        assertTrue(store.list("setting/.hidden").isEmpty())
        assertTrue(store.list("setting/./world").isEmpty())
        for (path in listOf("/setting/.hidden", "setting/.hidden/../world", "setting/.hidden//world", "setting/.hidden\\world")) {
            assertTrue("Unsafe query accepted: $path", runCatching { store.list(path) }.isFailure)
        }
    }

    @Test
    fun zipRejectsOversizedActualEntryBytesWithUnknownDeclaredSize() {
        expectZipBudgetRejection(compressedEntries(listOf(101 * 1024 * 1024)))
    }

    @Test
    fun zipBudgetCountsAggregateDecompressedBytesAcrossEntries() {
        expectZipBudgetRejection(compressedEntries(List(5) { 21 * 1024 * 1024 }))
    }

    private fun expectZipBudgetRejection(bytes: ByteArray) {
        val error = runCatching { NovelWorkspaceExchange.readZipFiles(ByteArrayInputStream(bytes)) }.exceptionOrNull()
        assertTrue("Expected a bounded format error, got $error", error is NovelWorkspaceFormatError)
    }

    private fun compressedEntries(sizes: List<Int>): ByteArray {
        val output = ByteArrayOutputStream()
        // Deflated entries omit size/CRC before writing and use a data descriptor.
        val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
        ZipOutputStream(output).use { zip ->
            sizes.forEachIndexed { index, size ->
                zip.putNextEntry(ZipEntry("setting/world/$index.md"))
                var remaining = size
                while (remaining > 0) {
                    val count = minOf(remaining, chunk.size)
                    zip.write(chunk, 0, count)
                    remaining -= count
                }
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun rawMultilineScalarsAndAliasesParseWithoutLosingContinuationText() {
        val title = "第一行\n第二行: 有冒号\n第三行"
        val alias = "别名第一行\n第二行"
        val rendered = NovelWorkspaceMarkdown.render(listOf("id" to "book", "title" to title), aliases = listOf(alias), body = "正文")
        assertTrue(rendered.contains("title: \"第一行\n第二行"))
        val parsed = NovelWorkspaceMarkdown.parseFile(rendered)
        assertEquals(title, parsed.fields["title"])
        assertEquals(listOf(alias), parsed.lists["aliases"])
        assertEquals(setOf("id", "title"), parsed.fields.keys)
        assertEquals("正文", parsed.body)
    }

    @Test
    fun fieldReplacementRemovesWholeOldMultilineScalarAndPreservesOtherYaml() {
        val original = NovelWorkspaceMarkdown.render(
            listOf("id" to "book", "title" to "旧第一行\n旧第二行: 废弃", "custom" to "保留第一行\n保留第二行"),
            aliases = listOf("别名"), body = "正文",
        )
        val updated = NovelWorkspaceMarkdown.withFields(original, mapOf("title" to "新第一行\n新第二行: 保留"))
        val parsed = NovelWorkspaceMarkdown.parseFile(updated)
        assertEquals("新第一行\n新第二行: 保留", parsed.fields["title"])
        assertEquals("保留第一行\n保留第二行", parsed.fields["custom"])
        assertFalse(updated.contains("旧第二行"))
        assertEquals(listOf("别名"), parsed.lists["aliases"])
        assertEquals("正文", parsed.body)
    }

    @Test
    fun fenceLookingScalarLineDoesNotTerminateHeaderOrLeaveOldFieldContinuation() {
        val originalTitle = "第一行\n---\n最后一行"
        val original = NovelWorkspaceMarkdown.render(listOf("id" to "book", "title" to originalTitle, "custom" to "保留"), body = "正文")
        assertEquals(originalTitle, NovelWorkspaceMarkdown.parseFile(original).fields["title"])
        assertEquals("正文", NovelWorkspaceMarkdown.parseFile(original).body)
        val updated = NovelWorkspaceMarkdown.withFields(original, mapOf("title" to "替换后\n---\n新最后一行"))
        assertEquals("替换后\n---\n新最后一行", NovelWorkspaceMarkdown.parseFile(updated).fields["title"])
        assertEquals("保留", NovelWorkspaceMarkdown.parseFile(updated).fields["custom"])
        assertFalse(updated.contains("第一行"))
        assertEquals("正文", NovelWorkspaceMarkdown.parseFile(updated).body)
    }

    @Test
    fun metadataFreeThematicFencesRetainEntireProse() {
        val prose = "---\n开场正文\n---\n末段正文"
        val parsed = NovelWorkspaceMarkdown.parseFile(prose)
        assertTrue(parsed.fields.isEmpty())
        assertTrue(parsed.lists.isEmpty())
        assertEquals(prose, parsed.body)
    }

    @Test
    fun changingOnlyFieldsPreservesOriginalBodySuffixIncludingCodeIndentation() {
        val suffix = "\r\n\r\n    code line\r\n\r\nPlain text  \r\n\r\n"
        val original = "---\r\nid: branch\r\ntitle: Before\r\n---" + suffix
        val changed = NovelWorkspaceMarkdown.withFields(original, mapOf("title" to "After"))
        assertEquals(original.replace("title: Before", "title: After"), changed)
    }
}
