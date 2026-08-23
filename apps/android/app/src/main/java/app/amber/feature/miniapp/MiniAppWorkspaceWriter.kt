package app.amber.feature.miniapp

import app.amber.agent.data.workspace.ArtifactRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** P3-04 receipt returned to the MiniApp for `host.createArtifact`. */
data class CreateArtifactReceipt(
    val artifactId: String,
    /** Canonical openable route for the Workspace Artifacts tab. */
    val route: String,
    /** "created" | "existing" (duplicate effectId replay). */
    val status: String,
    val title: String,
    val type: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("artifactId", artifactId)
        put("route", route)
        put("status", status)
        put("title", title)
        put("type", type)
    }

    companion object {
        fun routeOf(artifactId: String): String = "workspace://artifact/$artifactId"
    }
}

/**
 * P3-04: MiniApp `host.createArtifact` real persistence through the Workspace
 * Artifact Registry (Phase 3-A). Writes the registry row + content file with
 * sourceKind=miniapp and registers the reverse reference. The board summary
 * stays a UI projection only.
 *
 * effectId idempotency: a repeated call with the same effectId returns the
 * already-persisted artifactId instead of creating a duplicate row (the
 * MiniApp bridge does not go through the AgentToolDispatcher, so dedup lives
 * here in the bridge-facing writer).
 */
class MiniAppWorkspaceWriter(
    private val artifactRepository: ArtifactRepository,
) {
    suspend fun createArtifact(
        appId: String,
        effectId: String?,
        title: String,
        content: String,
        type: String,
        mimeType: String,
    ): CreateArtifactReceipt {
        if (!effectId.isNullOrBlank()) {
            artifactRepository.findByMiniAppEffect(appId, effectId)?.let { existing ->
                return CreateArtifactReceipt(
                    artifactId = existing.artifactId,
                    route = CreateArtifactReceipt.routeOf(existing.artifactId),
                    status = "existing",
                    title = existing.title,
                    type = existing.type,
                )
            }
        }
        val created = artifactRepository.createMiniAppArtifact(
            appId = appId,
            effectId = effectId,
            title = title,
            content = content,
            type = type,
            mimeType = mimeType,
        )
        return CreateArtifactReceipt(
            artifactId = created.artifactId,
            route = CreateArtifactReceipt.routeOf(created.artifactId),
            status = "created",
            title = created.title,
            type = created.type,
        )
    }
}
