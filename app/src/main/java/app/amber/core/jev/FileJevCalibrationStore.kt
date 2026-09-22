package app.amber.core.jev

import kotlinx.serialization.json.Json
import java.io.File

/**
 * 校准记录 JSONL 落盘。有界：超过 [maxRecords] 后重写文件只留最新一半，
 * 把重写成本摊薄到每半程一次；解析失败的行跳过，不让单行损坏丢掉整卷。
 * 所有 IO 失败静默放弃（见各方法），记录仅含候选 id 与概率，泄漏面与已
 * 出站数据一致。
 */
class FileJevCalibrationStore(
    private val file: File,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
) : JevCalibrationStore {

    private val json = Json { ignoreUnknownKeys = true }
    private var cached: MutableList<JevCalibrationRecord>? = null

    @Synchronized
    override fun append(record: JevCalibrationRecord) {
        // 诊断日志不拖垮业务链路：磁盘满/IO 失败放弃落盘，内存缓存里这条
        // 记录会在下次成功重写时一并补上。
        try {
            val records = loaded().apply { add(record) }
            if (records.size > maxRecords) {
                rewrite(records.takeLast(maxRecords / 2))
            } else {
                file.parentFile?.mkdirs()
                file.appendText(json.encodeToString(JevCalibrationRecord.serializer(), record) + "\n")
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    override fun readAll(): List<JevCalibrationRecord> = try {
        loaded().toList()
    } catch (_: Exception) {
        cached?.toList() ?: emptyList()
    }

    @Synchronized
    override fun clear() {
        cached = mutableListOf()
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    private fun loaded(): MutableList<JevCalibrationRecord> {
        cached?.let { return it }
        val records = mutableListOf<JevCalibrationRecord>()
        if (file.exists()) {
            file.useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    runCatching { json.decodeFromString(JevCalibrationRecord.serializer(), line) }
                        .getOrNull()?.let(records::add)
                }
            }
        }
        return (if (records.size > maxRecords) {
            records.takeLast(maxRecords / 2).toMutableList().also { rewrite(it) }
        } else {
            records
        }).also { cached = it }
    }

    private fun rewrite(keep: List<JevCalibrationRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { writer ->
            keep.forEach { record ->
                writer.write(json.encodeToString(JevCalibrationRecord.serializer(), record))
                writer.write("\n")
            }
        }
        if (!tmp.renameTo(file)) {
            // 极端文件系统状态下退化为直接覆写，不让诊断日志拖垮调用方。
            file.writeText(tmp.readText())
            tmp.delete()
        }
        cached = keep.toMutableList()
    }

    companion object {
        const val DEFAULT_MAX_RECORDS = 1_000
    }
}
