package me.rerere.rikkahub.data.agent.tools

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool

internal fun createMediaSearchTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "media_search",
    description = "Search Android MediaStore images, videos, or audio after the matching media permission is granted.",
    parameters = {
        obj(
            "type" to enumProp("Media type.", listOf("images", "video", "audio", "all")),
            "query" to stringProp("Optional file name filter."),
            "limit" to integerProp("Maximum media entries. Defaults to 30."),
        )
    },
    execute = { input ->
        val type = input.string("type") ?: "all"
        val capabilities = when (type) {
            "images" -> listOf("media_images")
            "video" -> listOf("media_video")
            "audio" -> listOf("media_audio")
            else -> listOf("media_images", "media_video", "media_audio")
        }
        // Multi-capability case: trackSystemTool gates on capabilities.first();
        // pre-flight the rest so a partial grant doesn't silently scan only
        // the first surface.
        capabilities.drop(1).forEach { capability ->
            deps.permissionBroker.ensureGranted(
                capabilityId = capability,
                toolName = "media_search",
                reason = "搜索媒体库",
            )
        }
        deps.trackSystemTool("media_search", "搜索媒体库", capabilities.first(), input.safePreview()) {
            textJson {
                put("media", queryMedia(context, type = type, query = input.string("query").orEmpty(), limit = input.limit(default = 30, max = 100)))
            }
        }
    }
)

private fun queryMedia(context: Context, type: String, query: String, limit: Int) = buildJsonArray {
    val targets = when (type) {
        "images" -> listOf("image" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        "video" -> listOf("video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        "audio" -> listOf("audio" to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        else -> listOf(
            "image" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "audio" to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        )
    }
    var count = 0
    for ((kind, uri) in targets) {
        if (count >= limit) break
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            ),
            query.takeIf { it.isNotBlank() }?.let { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" },
            query.takeIf { it.isNotBlank() }?.let { arrayOf("%$it%") },
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext() && count < limit) {
                val id = cursor.getLong(idIndex)
                add(buildJsonObject {
                    put("type", kind)
                    put("uri", ContentUris.withAppendedId(uri, id).toString())
                    put("name", cursor.getString(nameIndex).orEmpty())
                    put("mime_type", cursor.getString(mimeIndex).orEmpty())
                    put("size_bytes", cursor.getLong(sizeIndex))
                    put("date_modified_seconds", cursor.getLong(modifiedIndex))
                })
                count++
            }
        }
    }
}
