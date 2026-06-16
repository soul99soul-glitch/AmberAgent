package app.amber.feature.task

/**
 * Platform-abstract file handle for [AgentTaskStore] persistence.
 *
 * Each platform provides an `actual` that wraps the native file type:
 * - Android/JVM: `java.io.File`
 * - iOS: `platform.Foundation.NSFileManager` / `NSString`
 *
 * The operations mirror exactly what `AgentTaskStore` needs: per-task JSON files
 * under a base directory, sandboxed to the app's files directory.
 */
expect class TaskFile(path: String) {

    /** Absolute path of this file. */
    val path: String

    /** Parent directory as a [TaskFile]. */
    fun parent(): TaskFile

    /** Create this directory (including parents) if absent. */
    fun mkdirs(): Boolean

    /** Whether this file/directory exists. */
    fun exists(): Boolean

    /** Delete this file/directory. Returns true if deleted. */
    fun delete(): Boolean

    /** Write text content (UTF-8). */
    fun writeText(text: String)

    /** Read text content (UTF-8), or null if the file does not exist / fails. */
    fun readText(): String?

    /**
     * Canonical absolute path (symlinks resolved), or null if resolution fails.
     */
    fun canonicalPath(): String?

    /** List child files whose extension equals [ext] (no leading dot). */
    fun listFilesByExtension(ext: String): List<TaskFile>
}

/**
 * Construct a child [TaskFile] of this directory.
 */
fun TaskFile.child(name: String): TaskFile =
    TaskFile(this.path + separatorChar() + name)

/** Platform path separator ('/' on iOS/JVM-Unix, may differ elsewhere). */
expect fun separatorChar(): Char
