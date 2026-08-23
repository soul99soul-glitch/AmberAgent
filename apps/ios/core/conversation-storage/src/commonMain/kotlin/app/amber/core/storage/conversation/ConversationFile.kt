package app.amber.core.storage.conversation

/**
 * 平台抽象文件句柄，供 [JsonConversationStorage] 做 per-file JSON 持久化。
 *
 * 设计参照 feature/task/TaskFile：每个平台提供 `actual` 包装原生文件类型：
 * - iOS（真实使用方）：NSFileManager，Documents/conversations/ 下原子写。
 * - JVM（仅为编译通过）：java.io.File。
 *
 * 操作集合刻意最小化——只覆盖 storage 实际需要的：mkdirs/exists/read/write/delete/list。
 * 文本 IO 全 UTF-8；写操作由实现保证原子性（iOS 用 writeToFile(atomically:)，
 * JVM 用 File.writeText 的 tmp+rename，避免半写损坏）。
 */
expect class ConversationFile(path: String) {

    /** 绝对路径。 */
    val path: String

    /** 创建目录（含父级）。已存在返回 true。 */
    fun mkdirs(): Boolean

    /** 文件/目录是否存在。 */
    fun exists(): Boolean

    /** 删除。不存在返回 false。 */
    fun delete(): Boolean

    /** 写入文本（UTF-8，原子写）。 */
    fun writeText(text: String)

    /** 读文本（UTF-8）；不存在或读失败返回 null。 */
    fun readText(): String?

    /** 列出本目录下扩展名等于 [ext]（不含点）的子文件。非目录/不存在返回空。 */
    fun listFilesByExtension(ext: String): List<ConversationFile>
}

/** 拼接子文件路径。 */
fun ConversationFile.child(name: String): ConversationFile =
    ConversationFile(this.path + separatorChar() + name)

/** 平台路径分隔符。 */
expect fun separatorChar(): Char
