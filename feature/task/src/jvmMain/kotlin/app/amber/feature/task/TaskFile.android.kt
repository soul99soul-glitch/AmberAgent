package app.amber.feature.task

import java.io.File

actual class TaskFile actual constructor(actual val path: String) {

    private val file: File = File(path)

    actual fun parent(): TaskFile = TaskFile(file.parent ?: "")

    actual fun mkdirs(): Boolean = file.mkdirs() || file.isDirectory

    actual fun exists(): Boolean = file.exists()

    actual fun delete(): Boolean = file.delete()

    actual fun writeText(text: String) {
        file.writeText(text)
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
