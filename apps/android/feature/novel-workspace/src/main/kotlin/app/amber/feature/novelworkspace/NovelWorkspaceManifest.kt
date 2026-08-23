package app.amber.feature.novelworkspace

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The import gate: a tree without a recognizable manifest is not a novel workspace. */
data class NovelWorkspaceManifest(
    val format: String,
    val formatVersion: Int,
    val exportedAt: String?,
    val sourceProjectID: String?,
    val sourceProjectRevision: Long?,
    val sourceSchemaVersion: Int?,
    val mainBranch: String,
) {
    val isKnownFormat: Boolean get() = format == FORMAT

    companion object {
        const val FORMAT = "amber.novel.workspace"
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "manifest.yaml"
        const val DEFAULT_MAIN_BRANCH = "Main"

        fun parse(text: String): NovelWorkspaceManifest {
            val mapping = NovelWorkspaceMarkdown.parseMapping(text)
            return NovelWorkspaceManifest(
                format = mapping["format"] ?: "",
                formatVersion = mapping["formatVersion"]?.toIntOrNull() ?: 0,
                exportedAt = mapping["exportedAt"],
                sourceProjectID = mapping["source.projectID"] ?: mapping["projectID"],
                sourceProjectRevision = (mapping["source.projectRevision"] ?: mapping["projectRevision"])
                    ?.toLongOrNull(),
                sourceSchemaVersion = (mapping["source.schemaVersion"] ?: mapping["schemaVersion"])
                    ?.toIntOrNull(),
                mainBranch = mapping["mainBranch"]?.takeIf { it.isNotEmpty() } ?: DEFAULT_MAIN_BRANCH,
            )
        }
    }
}

object NovelWorkspaceManifestRenderer {
    fun render(
        exportedAt: Instant,
        sourceProjectID: String,
        sourceProjectRevision: Long,
        sourceSchemaVersion: Int,
        mainBranch: String,
    ): String = NovelWorkspaceMarkdown.yamlMapping(
        mapOf(
            "format" to NovelWorkspaceManifest.FORMAT,
            "formatVersion" to NovelWorkspaceManifest.FORMAT_VERSION.toString(),
            "exportedAt" to NovelWorkspaceTime.format(exportedAt),
            "source.projectID" to sourceProjectID,
            "source.projectRevision" to sourceProjectRevision.toString(),
            "source.schemaVersion" to sourceSchemaVersion.toString(),
            "mainBranch" to mainBranch,
        ),
    )
}

/** ISO8601 UTC second precision, matching iOS ISO8601DateFormatter(.withInternetDateTime). */
object NovelWorkspaceTime {
    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = FORMATTER.format(instant.truncatedTo(ChronoUnit.SECONDS))

    /** Lenient read: accepts fractional seconds and offsets as well. */
    fun parse(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()
}
