package app.amber.core.storage.conversation

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class ConversationFile actual constructor(actual val path: String) {

    private val file: File = File(path)

    actual fun mkdirs(): Boolean = file.mkdirs() || file.isDirectory

    actual fun exists(): Boolean = file.exists()

    actual fun delete(): Boolean = file.delete()

    actual fun writeText(text: String) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val tmp = File.createTempFile(file.name, ".tmp", parent)
        try {
            tmp.writeText(text)
            runCatching {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    actual fun readText(): String? =
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    actual fun listFilesByExtension(ext: String): List<ConversationFile> =
        file.listFiles { f -> f.extension == ext }.orEmpty().map { ConversationFile(it.absolutePath) }
}

actual fun separatorChar(): Char = File.separatorChar
