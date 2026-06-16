package app.amber.core.infra

import java.util.logging.Level
import java.util.logging.Logger

actual fun logE(tag: String, message: String, throwable: Throwable?) {
    val logger = Logger.getLogger(tag)
    if (throwable != null) {
        logger.log(Level.SEVERE, message, throwable)
    } else {
        logger.log(Level.SEVERE, message)
    }
}
