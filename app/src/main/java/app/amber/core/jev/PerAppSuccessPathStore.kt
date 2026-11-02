package app.amber.core.jev

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 按 package 隔离的成功路径仓库。无第二套 run 数据库；JSON 文件 + epoch，
 * 与 [FileJevCalibrationStore] 同一落盘习惯。
 *
 * [recordSuccess] 必须带上 run 开始时的 epoch：清除后旧 run 回写会被拒绝，
 * 避免已清经验被 inflight 成功复活。
 */
interface PerAppSuccessPathStore {
    fun currentEpoch(): Long

    fun pathsFor(packageName: String): List<PerAppSuccessPath>

    fun recordSuccess(expectedEpoch: Long, path: PerAppSuccessPath): Boolean

    fun clear(): PerAppSuccessPathClearResult

    fun hasPaths(): Boolean
}

sealed class PerAppSuccessPathClearResult {
    data class Cleared(val epoch: Long) : PerAppSuccessPathClearResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : PerAppSuccessPathClearResult()
}

fun PerAppSuccessPathClearResult.showRetryError(): Boolean =
    this is PerAppSuccessPathClearResult.Failed

fun PerAppSuccessPathClearResult.clearedEpochOrNull(): Long? =
    (this as? PerAppSuccessPathClearResult.Cleared)?.epoch

/** 设置页与测试共用的清除入口，避免 UI 另写一套 wipe。失败必须原样上报，不能假装已清。 */
fun clearPerAppSuccessPaths(store: PerAppSuccessPathStore): PerAppSuccessPathClearResult = store.clear()

@Serializable
internal data class PerAppSuccessPathFile(
    val version: Int = 1,
    val epoch: Long = 0,
    val packages: Map<String, List<PerAppSuccessPath>> = emptyMap(),
)

class FilePerAppSuccessPathStore(
    private val file: File,
    private val limits: PerAppSuccessPathLimits = PerAppSuccessPathLimits.DEFAULT,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val writeText: (File, String) -> Unit = { target, text -> target.writeText(text) },
    private val renameTo: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) : PerAppSuccessPathStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    override fun currentEpoch(): Long = readDisk().epoch

    @Synchronized
    override fun pathsFor(packageName: String): List<PerAppSuccessPath> {
        val pkg = packageName.trim()
        if (!PerAppSuccessPathSanitizer.validPackageName(pkg)) return emptyList()
        return readDisk().packages[pkg].orEmpty()
            .filter { it.packageName == pkg }
    }

    @Synchronized
    override fun recordSuccess(expectedEpoch: Long, path: PerAppSuccessPath): Boolean {
        val disk = readDisk()
        if (disk.epoch != expectedEpoch) return false
        val built = sanitizeRecord(path) ?: return false
        val pkg = built.packageName
        val existing = disk.packages[pkg].orEmpty()
            .filter { it.packageName == pkg }
        val merged = (listOf(built) + existing).take(limits.maxPathsPerPackage)
        val packages = (disk.packages + (pkg to merged)).toMutableMap()
        evictOldestPackages(packages)
        val bounded = boundFile(PerAppSuccessPathFile(version = 1, epoch = disk.epoch, packages = packages))
        return writeDisk(bounded)
    }

    @Synchronized
    override fun clear(): PerAppSuccessPathClearResult {
        return try {
            val next = readDisk().epoch + 1L
            val ok = writeDisk(PerAppSuccessPathFile(version = 1, epoch = next, packages = emptyMap()))
            if (ok) PerAppSuccessPathClearResult.Cleared(next)
            else PerAppSuccessPathClearResult.Failed("persist_failed")
        } catch (error: Exception) {
            PerAppSuccessPathClearResult.Failed("persist_failed", error)
        }
    }

    @Synchronized
    override fun hasPaths(): Boolean = readDisk().packages.values.any { it.isNotEmpty() }

    private fun readDisk(): PerAppSuccessPathFile {
        if (!file.exists()) return PerAppSuccessPathFile()
        return try {
            val text = file.readText()
            if (text.isBlank()) return PerAppSuccessPathFile()
            val parsed = json.decodeFromString(PerAppSuccessPathFile.serializer(), text)
            val packages = linkedMapOf<String, List<PerAppSuccessPath>>()
            parsed.packages.forEach { (rawKey, rawPaths) ->
                val pkg = rawKey.trim()
                if (!PerAppSuccessPathSanitizer.validPackageName(pkg)) return@forEach
                val paths = rawPaths.mapNotNull { raw -> sanitizeRecord(raw.copy(packageName = pkg)) }
                if (paths.isNotEmpty()) packages[pkg] = paths.take(limits.maxPathsPerPackage)
            }
            val sanitized = PerAppSuccessPathFile(
                version = 1,
                epoch = parsed.epoch.coerceAtLeast(0L),
                packages = packages,
            )
            if (shouldPersistScrub(parsed, sanitized)) {
                writeDisk(sanitized)
            }
            sanitized
        } catch (_: Exception) {
            val epoch = runCatching {
                Regex("\"epoch\"\\s*:\\s*(\\d+)").find(file.readText())?.groupValues?.get(1)?.toLong()
            }.getOrNull() ?: 0L
            PerAppSuccessPathFile(epoch = epoch.coerceAtLeast(0L))
        }
    }

    private fun shouldPersistScrub(parsed: PerAppSuccessPathFile, sanitized: PerAppSuccessPathFile): Boolean {
        if (parsed.packages.keys.any { !PerAppSuccessPathSanitizer.validPackageName(it.trim()) }) return true
        if (parsed.packages.size != sanitized.packages.size) return true
        parsed.packages.forEach { (pkg, paths) ->
            val kept = sanitized.packages[pkg].orEmpty()
            if (kept.size != paths.size) return true
            if (paths.any { PerAppSuccessPathSanitizer.looksSensitive(it.goalHint) }) return true
            if (paths.any { path -> path.steps.any { step -> PerAppSuccessPathSanitizer.sanitizeStep(step, limits) == null } }) {
                return true
            }
        }
        return false
    }

    private fun sanitizeRecord(path: PerAppSuccessPath): PerAppSuccessPath? {
        val pkg = path.packageName.trim()
        if (!PerAppSuccessPathSanitizer.validPackageName(pkg)) return null
        val steps = path.steps.mapNotNull { step ->
            PerAppSuccessPathSanitizer.sanitizeStep(step, limits)
        }.take(limits.maxStepsPerPath)
        if (steps.isEmpty()) return null
        val sensitiveHint = PerAppSuccessPathSanitizer.looksSensitive(path.goalHint)
        val fingerprint = path.goalFingerprint.trim().lowercase()
            .takeIf { it.length in 8..64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
            .orEmpty()
        return PerAppSuccessPath(
            packageName = pkg,
            goalFingerprint = if (sensitiveHint) "" else fingerprint,
            goalHint = if (sensitiveHint) "" else PerAppSuccessPathSanitizer.clip(path.goalHint, limits.maxTextChars),
            steps = steps,
            recordedAtMs = path.recordedAtMs.takeIf { it > 0L } ?: clock(),
        )
    }

    private fun evictOldestPackages(packages: MutableMap<String, List<PerAppSuccessPath>>) {
        while (packages.size > limits.maxPackages) {
            val victim = packages.minByOrNull { (_, paths) ->
                paths.maxOfOrNull { it.recordedAtMs } ?: 0L
            }?.key ?: break
            packages.remove(victim)
        }
    }

    private fun boundFile(payload: PerAppSuccessPathFile): PerAppSuccessPathFile {
        var current = payload
        var encoded = encode(current)
        if (encoded.size <= limits.maxFileBytes) return current
        val ranked = current.packages.flatMap { (pkg, paths) ->
            paths.map { pkg to it }
        }.sortedBy { it.second.recordedAtMs }
        val packages = current.packages.mapValues { it.value.toMutableList() }.toMutableMap()
        for ((pkg, path) in ranked) {
            if (encoded.size <= limits.maxFileBytes) break
            val remaining = packages[pkg] ?: continue
            remaining.removeAll { it.recordedAtMs == path.recordedAtMs && it.steps == path.steps }
            if (remaining.isEmpty()) packages.remove(pkg) else packages[pkg] = remaining
            current = current.copy(packages = packages.mapValues { it.value.toList() })
            encoded = encode(current)
        }
        return current
    }

    private fun encode(payload: PerAppSuccessPathFile): ByteArray =
        json.encodeToString(PerAppSuccessPathFile.serializer(), payload).toByteArray(Charsets.UTF_8)

    /**
     * Same-directory tmp + rename. Rename/write failure leaves the original file intact;
     * never fall back to in-place overwrite of [file].
     */
    private fun writeDisk(payload: PerAppSuccessPathFile): Boolean {
        val parent = file.parentFile
        val tmp = File(parent, file.name + ".tmp")
        val backup = File(parent, file.name + ".bak")
        return try {
            parent?.mkdirs()
            writeText(tmp, json.encodeToString(PerAppSuccessPathFile.serializer(), payload))
            if (renameTo(tmp, file)) return true
            val hadOriginal = file.exists()
            if (hadOriginal) {
                if (backup.exists()) backup.delete()
                if (!renameTo(file, backup)) {
                    tmp.delete()
                    return false
                }
            }
            if (renameTo(tmp, file)) {
                if (backup.exists()) backup.delete()
                return true
            }
            if (hadOriginal && backup.exists()) renameTo(backup, file)
            tmp.delete()
            false
        } catch (_: Exception) {
            runCatching { if (tmp.exists()) tmp.delete() }
            runCatching { if (!file.exists() && backup.exists()) renameTo(backup, file) }
            false
        }
    }
}
