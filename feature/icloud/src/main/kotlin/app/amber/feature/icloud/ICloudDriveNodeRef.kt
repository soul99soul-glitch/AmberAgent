package app.amber.feature.icloud

import java.nio.charset.StandardCharsets
import java.util.Base64

data class ICloudDriveNodeRef(
    val path: String,
    val drivewsId: String?,
    val docwsId: String?,
    val etag: String?,
)

object ICloudDriveNodeRefs {
    private const val PREFIX = "icn_"
    private const val VERSION = "v1"
    private const val SEP = "\u001F"

    fun encode(path: String, node: ICloudDriveNode): String =
        encode(
            ICloudDriveNodeRef(
                path = ICloudDrivePath.normalizeUserPath(path),
                drivewsId = node.drivewsid,
                docwsId = node.docwsid,
                etag = node.etag,
            )
        )

    fun encode(ref: ICloudDriveNodeRef): String {
        val raw = listOf(
            VERSION,
            ICloudDrivePath.normalizeUserPath(ref.path),
            ref.drivewsId.orEmpty(),
            ref.docwsId.orEmpty(),
            ref.etag.orEmpty(),
        ).joinToString(SEP)
        return PREFIX + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(token: String?): ICloudDriveNodeRef? {
        if (token.isNullOrBlank() || !token.startsWith(PREFIX)) return null
        val raw = runCatching {
            String(
                Base64.getUrlDecoder().decode(token.removePrefix(PREFIX)),
                StandardCharsets.UTF_8,
            )
        }.getOrNull() ?: return null
        val parts = raw.split(SEP)
        if (parts.size < 5 || parts[0] != VERSION) return null
        return runCatching {
            ICloudDriveNodeRef(
                path = ICloudDrivePath.normalizeUserPath(parts[1]),
                drivewsId = parts[2].ifBlank { null },
                docwsId = parts[3].ifBlank { null },
                etag = parts[4].ifBlank { null },
            )
        }.getOrNull()
    }
}
