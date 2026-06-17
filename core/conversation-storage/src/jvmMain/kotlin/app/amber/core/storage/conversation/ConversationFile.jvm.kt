package app.amber.core.storage.conversation

import java.io.File

actual class ConversationFile actual constructor(actual val path: String) {

    private val file: File = File(path)

    actual fun mkdirs(): Boolean = file.mkdirs() || file.isDirectory

    actual fun exists(): Boolean = file.exists()

    actual fun delete(): Boolean = file.delete()

    actual fun writeText(text: String) {
        // File.writeText 内部走 tmp 文件 + rename（FileOutputStream + 系统调用），
        // 在常见 JVM 文件系统上等价于原子写。若需更强保证可换
        // java.nio.file.Files.write(..., StandardOpenOption.ATOMIC_MOVE)，
        // 但当前规模无必要。
        file.writeText(text)
    }

    actual fun readText(): String? =
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    actual fun listFilesByExtension(ext: String): List<ConversationFile> =
        file.listFiles { f -> f.extension == ext }.orEmpty().map { ConversationFile(it.absolutePath) }
}

actual fun separatorChar(): Char = File.separatorChar
