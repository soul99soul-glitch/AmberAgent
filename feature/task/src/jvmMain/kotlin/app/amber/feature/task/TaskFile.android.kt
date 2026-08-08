package app.amber.feature.task

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class TaskFile actual constructor(actual val path: String) {

    private val file: File = File(path)

    actual fun parent(): TaskFile = TaskFile(file.parent ?: "")

    actual fun mkdirs(): Boolean = file.mkdirs() || file.isDirectory

    actual fun exists(): Boolean = file.exists()

    actual fun delete(): Boolean = file.delete()

    actual fun writeText(text: String) {
        val directory = file.parentFile ?: error("Task file has no parent directory: $path")
        check(directory.mkdirs() || directory.isDirectory) {
            "Unable to create task file parent directory: ${directory.absolutePath}"
        }
        val temporary = File.createTempFile(".${file.name}.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    actual fun readText(): String? =
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    actual fun canonicalPath(): String? =
        runCatching { file.canonicalPath }.getOrNull()

    actual fun listFilesByExtension(ext: String): List<TaskFile> =
        file.listFiles { f -> f.extension == ext }.orEmpty().map { TaskFile(it.absolutePath) }

    actual fun listDirectories(): List<TaskFile> =
        file.listFiles { f -> f.isDirectory }.orEmpty().map { TaskFile(it.absolutePath) }
}

actual fun separatorChar(): Char = File.separatorChar
