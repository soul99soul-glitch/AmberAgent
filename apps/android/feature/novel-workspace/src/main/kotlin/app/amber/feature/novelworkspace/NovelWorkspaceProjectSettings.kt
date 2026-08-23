package app.amber.feature.novelworkspace

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-project workspace settings (host-local, like the ledger). Model overrides take
 * precedence over the global chat model for this project's writing turns.
 */
/** Which constraint sections the per-turn brief injects (ghostwrite panel toggles). */
@Serializable
data class NovelWorkspaceInjectionFlags(
    /** plot/current.md state summary. */
    val plot: Boolean = true,
    /** Open foreshadowing nodes. */
    val foreshadowing: Boolean = true,
    /** Entity subgraph around the chapter plan. */
    val neighborhood: Boolean = true,
    /** Confirmed decision log. */
    val decisions: Boolean = true,
)

@Serializable
data class NovelWorkspaceProjectSettings(
    val version: Int = 1,
    /** Optional per-project writing model id; null = follow the global chat model. */
    val writingModelId: String? = null,
    /** Optional review model id (continuity / contradiction review turns). */
    val reviewModelId: String? = null,
    /** Which sections the injected brief carries; null = defaults (all on). */
    val injection: NovelWorkspaceInjectionFlags? = null,
)

object NovelWorkspaceProjectSettingsStore {
    private const val FILE_NAME = "settings.json"

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(projectDirectory: File): File =
        File(File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME), FILE_NAME)

    fun load(projectDirectory: File): NovelWorkspaceProjectSettings {
        val f = file(projectDirectory)
        if (!f.exists()) return NovelWorkspaceProjectSettings()
        return try {
            json.decodeFromString(NovelWorkspaceProjectSettings.serializer(), f.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            NovelWorkspaceProjectSettings()
        }
    }

    fun save(settings: NovelWorkspaceProjectSettings, projectDirectory: File) {
        val ledgerDir = File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME)
        if (!ledgerDir.exists() && !ledgerDir.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create ledger directory: $ledgerDir")
        }
        val destination = file(projectDirectory)
        val temp = File.createTempFile("novel-settings-", ".tmp", ledgerDir)
        try {
            temp.writeText(
                json.encodeToString(NovelWorkspaceProjectSettings.serializer(), settings),
                Charsets.UTF_8,
            )
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            NovelWorkspaceLedger.atomicMove(temp, destination)
        } finally {
            temp.delete()
        }
    }
}
