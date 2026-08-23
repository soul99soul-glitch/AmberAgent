package app.amber.feature.novel.persistence

import app.amber.feature.novel.domain.NovelError
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.serialization.NovelSwiftWireContract
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelProjectRepositorySizeGuardTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun oversizedProjectIsRejectedBeforeReadingItsPayload() = runBlocking {
        val root = tempFolder.newFolder("novel-root")
        val projectId = NovelProjectId.generate()
        val projects = File(root, "projects").apply { mkdirs() }
        val primary = File(projects, "${projectId.rawValue.lowercase()}.json")
        RandomAccessFile(primary, "rw").use { file ->
            file.setLength(NovelSwiftWireContract.MAX_PROJECT_BYTES.toLong() + 1L)
        }

        val failure = runCatching {
            NovelFileProjectRepository(root).loadProject(projectId)
        }.exceptionOrNull()

        assertTrue(failure is NovelError.CorruptedProject)
        assertTrue(failure?.message.orEmpty().contains("exceeds max size"))
    }
}
