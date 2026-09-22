package app.amber.core.jev

import kotlinx.serialization.Serializable

/**
 * 单次完整评估的校准记录（A3）：逐候选判分与原路径既任首选的对账。
 * 只含候选 id 与概率，不含任务文本、记忆原文或工具输出。
 */
@Serializable
data class JevCalibrationRecord(
    val timestamp: Long,
    val purpose: JevPurpose,
    /** 仅记录真正完成评估的调用：SHADOW 或 ACTIVE。 */
    val mode: JevMode,
    val model: String?,
    val latencyMs: Long,
    /** 评估生效的本用途阈值（JevPolicy）。 */
    val threshold: Double,
    /** 每候选判分（候选 id → 概率）。 */
    val scores: Map<String, Double>,
    /** 原路径（词面序/轮转序）的既任首选；无排序语义的用途为 null。 */
    val incumbentTop1: String?,
    /** 过阈值最高分候选；null 即弃权（全部低于阈值）或无排序语义（CONTEXT_SELECTION）。 */
    val jevTop1: String?,
)

/**
 * 校准记录落盘接口；app 层 JSONL 实现，core 层零 Android 依赖（对照 JevUsageStore）。
 */
interface JevCalibrationStore {
    fun append(record: JevCalibrationRecord)

    fun readAll(): List<JevCalibrationRecord>

    fun clear()

    companion object {
        /** 内存实现（测试/无注入场景）；进程重启即丢。 */
        val IN_MEMORY: JevCalibrationStore = object : JevCalibrationStore {
            private val records = mutableListOf<JevCalibrationRecord>()

            @Synchronized
            override fun append(record: JevCalibrationRecord) {
                records += record
            }

            @Synchronized
            override fun readAll(): List<JevCalibrationRecord> = records.toList()

            @Synchronized
            override fun clear() = records.clear()
        }
    }
}
