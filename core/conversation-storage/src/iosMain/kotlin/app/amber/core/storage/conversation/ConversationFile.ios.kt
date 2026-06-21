package app.amber.core.storage.conversation

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
actual class ConversationFile actual constructor(actual val path: String) {

    private val fm: NSFileManager = NSFileManager.defaultManager

    actual fun mkdirs(): Boolean {
        if (fm.fileExistsAtPath(path)) return true
        // 复刻 TaskFile.ios.kt 的递归创建，保证 withIntermediateDirectories 行为一致。
        val parentPath = (path as NSString).stringByDeletingLastPathComponent()
        if (parentPath.isNotEmpty() && parentPath != path &&
            !fm.fileExistsAtPath(parentPath)
        ) {
            ConversationFile(parentPath).mkdirs()
        }
        return runCatching {
            fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        }.getOrDefault(false)
    }

    actual fun exists(): Boolean = fm.fileExistsAtPath(path)

    actual fun delete(): Boolean {
        if (!exists()) return false
        return fm.removeItemAtPath(path, error = null)
    }

    actual fun writeText(text: String) {
        // writeToFile(atomically: true) 走 tmp 文件 + rename，Foundation 原子写。
        val nsString = text as NSString
        nsString.writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    actual fun readText(): String? {
        if (!exists()) return null
        return runCatching {
            NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) as String
        }.getOrNull()
    }

    actual fun listFilesByExtension(ext: String): List<ConversationFile> {
        if (!exists()) return emptyList()
        val names: List<String> = runCatching {
            (fm.contentsOfDirectoryAtPath(path, error = null) as? List<String>).orEmpty()
        }.getOrDefault(emptyList())
        return names.filter { name ->
            val dotIndex = name.lastIndexOf('.')
            dotIndex >= 0 && name.substring(dotIndex + 1) == ext
        }.map { name -> ConversationFile(path + "/" + name) }
    }
}

actual fun separatorChar(): Char = '/'
