package app.amber.agent.data.workspace

import app.amber.agent.data.db.entity.ArtifactEntity

/** Origin of an artifact's content (plan P3-01 source tracking). */
enum class ArtifactSourceKind(val id: String) {
    CHAT("chat"),
    MINIAPP("miniapp"),
    DEEPREAD("deepread"),
    UNKNOWN("unknown");

    companion object {
        fun fromId(id: String?): ArtifactSourceKind = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** Parser lifecycle of an artifact's content. */
enum class ArtifactParseStatus(val id: String) {
    PARSED("PARSED"),
    FAILED("FAILED");

    companion object {
        fun fromId(id: String?): ArtifactParseStatus = entries.firstOrNull { it.id == id } ?: PARSED
    }
}

/** Registry row for one workspace artifact (P3-01). */
data class Artifact(
    val artifactId: String,
    val workspaceId: String,
    val type: String,
    val mimeType: String,
    val title: String,
    val sourceKind: ArtifactSourceKind,
    val sourceId: String?,
    val sourceRunId: String?,
    val sourceMessageId: String?,
    val contentLocator: String,
    val contentDigest: String,
    val sizeBytes: Long,
    val parserVersion: String?,
    val parseStatus: ArtifactParseStatus,
    val parseError: String?,
    val metadataJson: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

internal fun ArtifactEntity.toArtifact(): Artifact = Artifact(
    artifactId = artifactId,
    workspaceId = workspaceId,
    type = type,
    mimeType = mimeType,
    title = title,
    sourceKind = ArtifactSourceKind.fromId(sourceKind),
    sourceId = sourceId,
    sourceRunId = sourceRunId,
    sourceMessageId = sourceMessageId,
    contentLocator = contentLocator,
    contentDigest = contentDigest,
    sizeBytes = sizeBytes,
    parserVersion = parserVersion,
    parseStatus = ArtifactParseStatus.fromId(parseStatus),
    parseError = parseError,
    metadataJson = metadataJson,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
)

internal fun Artifact.toEntity(): ArtifactEntity = ArtifactEntity(
    artifactId = artifactId,
    workspaceId = workspaceId,
    type = type,
    mimeType = mimeType,
    title = title,
    sourceKind = sourceKind.id,
    sourceId = sourceId,
    sourceRunId = sourceRunId,
    sourceMessageId = sourceMessageId,
    contentLocator = contentLocator,
    contentDigest = contentDigest,
    sizeBytes = sizeBytes,
    parserVersion = parserVersion,
    parseStatus = parseStatus.id,
    parseError = parseError,
    metadataJson = metadataJson,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
)
