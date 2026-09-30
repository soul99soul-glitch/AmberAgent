package app.amber.feature.ui.pages.share.handler

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.amber.core.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Narrow content check for a shared v1 theme file; ordinary JSON remains a regular attachment. */
internal object ThemeShareImport {
    private const val FORMAT = "amber.theme.pack"
    private const val MAX_JSON_BYTES = 1024 * 1024

    /** Returns the original JSON only when it is a bounded, valid JSON object with the v1 marker. */
    fun readV1DocumentIfPresent(context: Context, uri: Uri, declaredMimeType: String?): String? {
        if (uri.scheme != "content" && uri.scheme != "file") return null
        if (!isJsonCandidate(context, uri, declaredMimeType)) return null

        val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it) } ?: return null
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val json = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: return null
        val root = runCatching { JsonInstant.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val format = root["format"] as? JsonPrimitive ?: return null
        return json.takeIf { format.isString && format.content == FORMAT }
    }

    private fun isJsonCandidate(context: Context, uri: Uri, declaredMimeType: String?): Boolean {
        fun isJsonMimeType(value: String?): Boolean {
            val mimeType = value?.substringBefore(';')?.trim()?.lowercase() ?: return false
            return mimeType == "application/json" || mimeType == "text/json" || mimeType.endsWith("+json")
        }

        val providerMimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (isJsonMimeType(declaredMimeType) || isJsonMimeType(providerMimeType)) return true

        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return (displayName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment)
            ?.endsWith(".json", ignoreCase = true) == true
    }

    private fun readBounded(input: java.io.InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_JSON_BYTES + 1 - total))
            if (read < 0) return output.toByteArray()
            total += read
            if (total > MAX_JSON_BYTES) return null
            output.write(buffer, 0, read)
        }
    }
}
