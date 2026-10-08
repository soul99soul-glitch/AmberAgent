package app.amber.feature.novelworkspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceProjectSettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `old settings default to co-create while changing mode preserves model and injection choices`() {
        val directory = temporary.newFolder()
        val source = File(directory, ".amber/settings.json")
        source.parentFile!!.mkdirs()
        source.writeText("""{"version":1,"writingModelId":"writer","reviewModelId":"reviewer","injection":{"plot":false}}""")
        val original = NovelWorkspaceProjectSettingsStore.load(directory)
        assertFalse(original.ghostwriteMode)
        NovelWorkspaceProjectSettingsStore.save(original.copy(ghostwriteMode = true), directory)
        val restored = NovelWorkspaceProjectSettingsStore.load(directory)
        assertTrue(restored.ghostwriteMode)
        assertEquals("writer", restored.writingModelId)
        assertEquals("reviewer", restored.reviewModelId)
        assertFalse(restored.injection!!.plot)
        assertEquals(original, restored.copy(ghostwriteMode = false))
    }
}
