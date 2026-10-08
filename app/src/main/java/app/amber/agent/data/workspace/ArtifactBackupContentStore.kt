package app.amber.agent.data.workspace

import app.amber.feature.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.CancellationException

/** Restored artifact bodies stay inside the app; restore never writes the user's SAF tree. */
class ArtifactBackupContentStore(private val workspace: WorkspaceManager) {
    private val root get() = File(workspace.mirrorDir.parentFile, "artifact-content")

    fun restoredFile(locator: String): File? = ownedFile(locator)?.takeIf { it.isFile }

    suspend fun readForBackup(locator: String): ByteArray? {
        require(isOwnedLocator(locator)) { "Invalid artifact content locator: $locator" }
        restoredFile(locator)?.let { return it.readBytes() }
        if (workspace.state.value.configured) {
            try {
                return workspace.readBytes(locator)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
        val mirror = File(workspace.mirrorDir, locator).canonicalFile
        require(mirror.path.startsWith(workspace.mirrorDir.canonicalPath + File.separator)) {
            "Artifact content escaped workspace mirror: $locator"
        }
        return mirror.takeIf { it.isFile }?.readBytes()
    }

    fun updateRestored(locator: String, content: String) {
        restoredFile(locator)?.writeText(content)
    }

    fun deleteRestored(locator: String) {
        restoredFile(locator)?.let { check(it.delete()) { "Unable to delete restored artifact body: $locator" } }
    }

    private fun ownedFile(locator: String): File? {
        if (!isOwnedLocator(locator)) return null
        val file = File(root, locator).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "Invalid artifact content path" }
        return file
    }

    companion object {
        const val RELATIVE_ROOT = "amberagent/artifact-content"
        const val DATASET = "artifact-content"
        private val ownedLocator = Regex("artifacts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(md|json)")
        fun isOwnedLocator(locator: String): Boolean = ownedLocator.matches(locator)
    }
}
