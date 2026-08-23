package app.amber.core.infra

import platform.Foundation.NSLog

actual fun logE(tag: String, message: String, throwable: Throwable?) {
    val detail = if (throwable != null) "$message: ${throwable.message}" else message
    NSLog("$tag: $detail")
}
