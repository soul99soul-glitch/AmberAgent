package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Workspace Artifact Registry — P3-01.
 *
 * One row per content artifact produced by Chat / MiniApp / DeepRead. The
 * artifact is the durable, manageable record: metadata here, large content in
 * a workspace file referenced by [contentLocator] (plan P3-01: "大内容使用文件/
 * 数据库正文，不把全部内容塞入设置").
 *
 * Rollback rule (plan §17.2): disabling `workspace_artifacts_v2` never deletes
 * rows — the registry stays read-only visible.
 */
@Entity(
    tableName = "artifact",
    indices = [
        Index("workspace_id"),
        Index("source_message_id"),
        Index(value = ["source_kind", "source_id"]),
    ],
)
data class ArtifactEntity(
    @PrimaryKey
    @ColumnInfo(name = "artifact_id") val artifactId: String,
    /** Logical grouping inside the workspace; "default" is the workspace root. */
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    /** Artifact kind, e.g. "chat_message". */
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "title") val title: String,
    /** Source origin: "chat" | "miniapp" | "deepread" (see ArtifactSourceKind). */
    @ColumnInfo(name = "source_kind") val sourceKind: String?,
    /** Source conversation id (chat), app id (miniapp), item id (deepread). */
    @ColumnInfo(name = "source_id") val sourceId: String?,
    @ColumnInfo(name = "source_run_id") val sourceRunId: String?,
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String?,
    /** Workspace-relative path of the content file, e.g. "artifacts/<id>.md". */
    @ColumnInfo(name = "content_locator") val contentLocator: String,
    /** SHA-256 hex of the content bytes; same content → same digest. */
    @ColumnInfo(name = "content_digest") val contentDigest: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "parser_version") val parserVersion: String?,
    /** PENDING | PARSED | FAILED (see ArtifactParseStatus). */
    @ColumnInfo(name = "parse_status") val parseStatus: String,
    @ColumnInfo(name = "parse_error") val parseError: String?,
    /** Optional domain metadata (e.g. chat save options / attachment refs). */
    @ColumnInfo(name = "metadata_json") val metadataJson: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

/**
 * Cross-feature reference to an artifact — P3-01 "删除前显示被哪些功能引用".
 *
 * Future features (MiniApp, DeepRead) register references so the delete
 * confirmation can surface who depends on the artifact. Deleting an artifact
 * cascades its references.
 */
@Entity(
    tableName = "artifact_reference",
    primaryKeys = ["artifact_id", "ref_kind", "ref_id"],
    foreignKeys = [
        ForeignKey(
            entity = ArtifactEntity::class,
            parentColumns = ["artifact_id"],
            childColumns = ["artifact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artifact_id")],
)
data class ArtifactReferenceEntity(
    @ColumnInfo(name = "artifact_id") val artifactId: String,
    /** Referencing feature, e.g. "miniapp" / "deepread". */
    @ColumnInfo(name = "ref_kind") val refKind: String,
    @ColumnInfo(name = "ref_id") val refId: String,
)
