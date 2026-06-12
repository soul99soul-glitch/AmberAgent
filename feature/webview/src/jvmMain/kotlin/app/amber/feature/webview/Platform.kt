package app.amber.feature.webview

import java.io.File

internal actual fun epochMilliseconds(): Long = System.currentTimeMillis()

internal actual fun isValidFilePath(path: String): Boolean =
    path.isNotBlank() && runCatching {
        val file = File(path)
        file.exists() && file.length() > 0L
    }.getOrDefault(false)
