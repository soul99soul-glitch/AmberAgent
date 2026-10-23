package app.amber.feature.ui.components.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Durable per-URL thumbnails for web operation previews.
 *
 * [app.amber.feature.webview.WebViewOperationStore] only lives in memory, so after a cold start the
 * composer pill used to re-create a hidden WebView and reload the page just to redraw its
 * thumbnail. Keying the file by the preview URL lets a reopened conversation show the last capture
 * without touching WebView. Every function here does file IO and must run off the main thread.
 */
internal object WebPreviewThumbnails {
    private const val DIRECTORY = "amberagent/artifacts/webview-thumbnails"
    private const val MAX_FILES = 48
    private const val QUALITY = 80

    fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    fun fileFor(context: Context, url: String): File =
        File(directory(context), thumbnailFileName(url.normalizedWebPreviewUrl()))

    fun cached(context: Context, url: String): File? =
        fileFor(context, url).takeIf { it.isFile && it.length() > 0L }

    fun write(context: Context, url: String, bitmap: Bitmap): File {
        val target = fileFor(context, url)
        val directory = target.parentFile ?: directory(context)
        directory.mkdirs()
        val temp = File(directory, "${target.name}.tmp")
        temp.outputStream().use { stream -> bitmap.compress(webpFormat(), QUALITY, stream) }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        trim(directory, keep = target)
        return target
    }

    private fun trim(directory: File, keep: File) {
        val files = directory.listFiles { file -> file.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.filter { it != keep }
            .sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
}

internal fun thumbnailFileName(normalizedUrl: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(normalizedUrl.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { byte -> "%02x".format(byte) }
    return "preview-${hex.take(32)}.webp"
}
