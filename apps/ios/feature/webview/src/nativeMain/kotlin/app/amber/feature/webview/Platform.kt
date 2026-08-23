package app.amber.feature.webview

import kotlin.time.Clock

internal actual fun epochMilliseconds(): Long = Clock.System.now().toEpochMilliseconds()

internal actual fun isValidFilePath(path: String): Boolean = path.isNotBlank()
