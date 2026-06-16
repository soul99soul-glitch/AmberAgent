package app.amber.feature.task

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Foundation.stringByDeletingLastPathComponent

@OptIn(ExperimentalForeignApi::class)
actual class TaskFile actual constructor(actual val path: String) {

    private val fm: NSFileManager = NSFileManager.defaultManager

    actual fun parent(): TaskFile =
        TaskFile((path as NSString).stringByDeletingLastPathComponent())

    actual fun mkdirs(): Boolean {
        // Create intermediate directories like File.mkdirs().
        if (fm.fileExistsAtPath(path)) return true
        val parent = parent()
        if (!parent.exists()) parent.mkdirs()
        return runCatching { fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null) }.getOrDefault(false)
    }

    actual fun exists(): Boolean = fm.fileExistsAtPath(path)

    actual fun delete(): Boolean =
        runCatching { fm.removeItemAtPath(path, error = null) }.isSuccess

    actual fun writeText(text: String) {
        val nsString = text as NSString
        nsString.writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    actual fun readText(): String? {
        if (!exists()) return null
        return runCatching {
            NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) as String
        }.getOrNull()
    }

    actual fun canonicalPath(): String? {
        // Resolve symlinks + standardize path (remove ../ etc.) to mimic
        // java.io.File.canonicalPath for sandbox validation.
        return runCatching {
            NSURL.fileURLWithPath(path).standardizedURL?.path ?: path
        }.getOrNull()
    }

    actual fun listFilesByExtension(ext: String): List<TaskFile> {
        if (!exists()) return emptyList()
        val names: List<String> = runCatching {
            (fm.contentsOfDirectoryAtPath(path, error = null) as? List<String>).orEmpty()
        }.getOrDefault(emptyList())
        return names.filter { name ->
            // Compare extension without leading dot.
            val dotIndex = name.lastIndexOf('.')
            dotIndex >= 0 && name.substring(dotIndex + 1) == ext
        }.map { name -> TaskFile(path + "/" + name) }
    }

    actual fun listDirectories(): List<TaskFile> {
        if (!exists()) return emptyList()
        val names: List<String> = runCatching {
            (fm.contentsOfDirectoryAtPath(path, error = null) as? List<String>).orEmpty()
        }.getOrDefault(emptyList())
        return names.filter { name ->
            val childPath = path + "/" + name
            kotlinx.cinterop.memScoped {
                val isDirPtr = alloc<BooleanVar>()
                fm.fileExistsAtPath(childPath, isDirectory = isDirPtr.ptr)
                isDirPtr.value
            }
        }.map { name -> TaskFile(path + "/" + name) }
    }
}

actual fun separatorChar(): Char = '/'
