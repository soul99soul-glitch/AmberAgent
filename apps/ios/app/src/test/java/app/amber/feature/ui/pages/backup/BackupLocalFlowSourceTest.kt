package app.amber.feature.ui.pages.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupLocalFlowSourceTest {
    @Test
    fun localRestoreUriIsOwnedByViewModel() {
        val page = source("app/src/main/java/app/amber/feature/ui/pages/backup/BackupPage.kt")
        val vm = source("app/src/main/java/app/amber/feature/ui/pages/backup/BackupVM.kt")

        assertFalse(page.contains("pendingImportUri"))
        assertTrue(page.contains("vm.restorePendingLocal("))
        assertTrue(vm.contains("private var pendingLocalRestoreUri"))
        assertTrue(vm.contains("fun restorePendingLocal("))
        assertTrue(vm.contains("没有待恢复的本地备份"))
    }

    @Test
    fun localExportTruncatesSafTarget() {
        val repository = source("app/src/main/java/app/amber/core/sync/local/LocalBackupRepository.kt")

        assertTrue(repository.contains("openOutputStream(uri, \"wt\")"))
        assertFalse(repository.contains("openOutputStream(uri)?.use"))
    }

    private fun source(path: String): String {
        val candidates = listOf(
            File(path),
            File(path.removePrefix("app/")),
            File("../$path"),
        )
        return candidates.first { it.isFile }.readText()
    }
}
