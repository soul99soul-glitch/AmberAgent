package app.amber.feature.ui.theme

import app.amber.agent.data.db.dao.ThemePackageDAO
import app.amber.agent.data.db.entity.ThemePackageEntity
import app.amber.core.settings.Settings
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.JsonInstant
import app.amber.feature.runtime.ContentDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant

/** 主题库写入用的窄接口（真实实现包 SettingsAggregator；测试用内存实现）。 */
interface ThemeSettingsStore {
    val settingsFlow: Flow<Settings>

    suspend fun update(settings: Settings)
}

class SettingsAggregatorThemeStore(
    private val aggregator: SettingsAggregator,
) : ThemeSettingsStore {
    override val settingsFlow: Flow<Settings> = aggregator.settingsFlow

    override suspend fun update(settings: Settings) = aggregator.update(settings)
}

sealed interface ThemePackageImportResult {
    /** 导入成功：包只进入内存 try-on，[unknownTokens] 为 allowlist 外 token（仅提示）。 */
    data class Preview(
        val pkg: ThemePackage,
        val unknownTokens: List<String>,
        val candidate: DisplaySetting,
        val candidateDigest: String,
    ) : ThemePackageImportResult

    data class Rejected(val issues: List<String>) : ThemePackageImportResult
}

sealed interface ThemePackageApplyResult {
    data object Applied : ThemePackageApplyResult
    data object AlreadyApplied : ThemePackageApplyResult
    data object NotFound : ThemePackageApplyResult

    /** 没有可供 apply 的内存 try-on 候选，或候选 id 与请求不一致。 */
    data object NotPrepared : ThemePackageApplyResult

    /** 库中 JSON 损坏或值非法（导入时已校验，仅防外部篡改）。 */
    data object Corrupt : ThemePackageApplyResult

    /** 应用写入失败，已回退到上一个可用主题。 */
    data object Reverted : ThemePackageApplyResult
}

/** 当前主题包的内存 try-on；直到 [ThemePackageManager.applyPrepared] 才会写入。 */
data class ThemePackageTryOn(
    val pkg: ThemePackage,
    val unknownTokens: List<String>,
    val candidate: DisplaySetting,
    /** Digest of the exact JSON used to prepare this candidate. */
    val candidateDigest: String,
    /** 原始 JSON；apply 时保留未知 token 与原始字段。 */
    val rawJson: String,
)

data class ThemePackageStatus(
    val current: Settings,
    val installed: List<ThemePackageEntity>,
    val tryOn: ThemePackageTryOn?,
)

/**
 * P8-09 — 主题库管理：导入 preview + 内存 try-on、apply/remove、内置主题保护、
 * 应用失败回退到上一个可用主题。内置主题不落库且 `builtin:` id 被校验器
 * 拒绝，导入包永远无法覆盖内置主题。
 */
class ThemePackageManager(
    private val dao: ThemePackageDAO,
    private val settingsStore: ThemeSettingsStore,
    private val now: () -> Instant = Instant::now,
) {

    private val _tryOn = MutableStateFlow<ThemePackageTryOn?>(null)
    val tryOn: StateFlow<ThemePackageTryOn?> = _tryOn.asStateFlow()

    fun observeLibrary(): Flow<List<ThemePackageEntity>> = dao.observeAll()

    /** 解析 + 校验 + 内存 try-on；不会写主题库或 Settings。 */
    suspend fun importPackage(json: String): ThemePackageImportResult {
        val validation = ThemePackageValidator.validateJson(json)
        return when (validation) {
            is ThemePackageValidation.Invalid -> ThemePackageImportResult.Rejected(validation.issues)
            is ThemePackageValidation.Valid -> {
                val current = settingsStore.settingsFlow.first()
                val candidate = ThemePackageApplier.applyTokens(
                    validation.themePackage,
                    current.displaySetting,
                ).copy(appliedThemePackageId = validation.themePackage.id)
                _tryOn.value = ThemePackageTryOn(
                    pkg = validation.themePackage,
                    unknownTokens = validation.unknownTokens,
                    candidate = candidate,
                    candidateDigest = candidateDigestFor(json),
                    rawJson = json,
                )
                ThemePackageImportResult.Preview(
                    pkg = validation.themePackage,
                    unknownTokens = validation.unknownTokens,
                    candidate = candidate,
                    candidateDigest = candidateDigestFor(json),
                )
            }
        }
    }

    /** 显式命名的 try-on 入口，供 agent 工具和 UI 共同使用。 */
    suspend fun prepareImport(json: String): ThemePackageImportResult = importPackage(json)

    /**
     * 把当前内存候选一次性写入主题库和 Settings。主题库写失败或 Settings 应用失败时，
     * 恢复原有库项与原有 Settings；成功后清除 try-on。
     */
    suspend fun applyPrepared(packageId: String, candidateDigest: String): ThemePackageApplyResult {
        val prepared = _tryOn.value ?: return ThemePackageApplyResult.NotPrepared
        if (packageId != prepared.pkg.id || candidateDigest != prepared.candidateDigest) {
            return ThemePackageApplyResult.NotPrepared
        }

        val current = settingsStore.settingsFlow.first()
        val nextDisplay = ThemePackageApplier.applyTokens(prepared.pkg, current.displaySetting)
            .copy(appliedThemePackageId = prepared.pkg.id)
        val previousEntity = dao.getById(prepared.pkg.id)
        val nextEntity = ThemePackageEntity(
            id = prepared.pkg.id,
            name = prepared.pkg.name,
            json = preparedJson(prepared),
            importedAtMs = now().toEpochMilli(),
        )

        return try {
            dao.upsert(nextEntity)
            val result = if (nextDisplay == current.displaySetting) {
                // A package may be identical to the active settings; applying it still
                // persists the package so the user's explicit import is not lost.
                ThemePackageApplyResult.Applied
            } else {
                updateWithRollback(current, current.copy(displaySetting = nextDisplay))
            }
            if (result == ThemePackageApplyResult.Applied || result == ThemePackageApplyResult.AlreadyApplied) {
                clearTryOn()
            } else {
                restoreEntity(prepared.pkg.id, previousEntity)
            }
            result
        } catch (_: Exception) {
            restoreEntity(prepared.pkg.id, previousEntity)
            ThemePackageApplyResult.Reverted
        }
    }

    /** 放弃内存 try-on；不写库、不写 Settings。 */
    fun discardTryOn(packageId: String, candidateDigest: String): Boolean {
        val prepared = _tryOn.value ?: return false
        if (packageId != prepared.pkg.id || candidateDigest != prepared.candidateDigest) return false
        clearTryOn()
        return true
    }

    suspend fun status(): ThemePackageStatus = ThemePackageStatus(
        current = settingsStore.settingsFlow.first(),
        installed = dao.observeAll().first(),
        tryOn = _tryOn.value,
    )

    suspend fun apply(packageId: String): ThemePackageApplyResult {
        val entity = dao.getById(packageId) ?: return ThemePackageApplyResult.NotFound
        val pkg = runCatching { JsonInstant.decodeFromString(ThemePackage.serializer(), entity.json) }
            .getOrNull() ?: return ThemePackageApplyResult.Corrupt
        if (ThemePackageValidator.validatePackage(pkg) !is ThemePackageValidation.Valid) {
            return ThemePackageApplyResult.Corrupt
        }
        val current = settingsStore.settingsFlow.first()
        val nextDisplay = ThemePackageApplier.applyTokens(pkg, current.displaySetting)
            .copy(appliedThemePackageId = pkg.id)
        if (nextDisplay == current.displaySetting) {
            clearTryOn()
            return ThemePackageApplyResult.AlreadyApplied
        }
        return updateWithRollback(current, current.copy(displaySetting = nextDisplay)).also {
            if (it == ThemePackageApplyResult.Applied) clearTryOn()
        }
    }

    /** 应用内置主题（只写 baseFamily，清除包标记）；内置主题不可被移除/覆盖。 */
    suspend fun applyBuiltin(baseFamily: String): ThemePackageApplyResult {
        require(baseFamily in setOf("WARM", "SAGE")) { "未知的内置色系：$baseFamily" }
        clearTryOn()
        val current = settingsStore.settingsFlow.first()
        if (current.displaySetting.amberBaseFamily == baseFamily &&
            current.displaySetting.appliedThemePackageId == null
        ) {
            return ThemePackageApplyResult.AlreadyApplied
        }
        return updateWithRollback(
            current,
            current.copy(
                displaySetting = current.displaySetting.copy(
                    amberBaseFamily = baseFamily,
                    appliedThemePackageId = null,
                ),
            ),
        )
    }

    /** 从主题库移除导入包（`builtin:` 条目不在库中，天然不可移除）。 */
    suspend fun remove(packageId: String): Boolean {
        if (_tryOn.value?.pkg?.id == packageId) clearTryOn()
        return dao.delete(packageId) > 0
    }

    private fun preparedJson(prepared: ThemePackageTryOn): String =
        prepared.rawJson

    private fun clearTryOn() {
        _tryOn.value = null
    }

    private fun candidateDigestFor(rawJson: String): String =
        ContentDigest.sha256(rawJson)

    private suspend fun restoreEntity(id: String, previous: ThemePackageEntity?) {
        if (previous == null) {
            dao.delete(id)
        } else {
            dao.upsert(previous)
        }
    }

    private suspend fun updateWithRollback(previous: Settings, next: Settings): ThemePackageApplyResult =
        try {
            settingsStore.update(next)
            ThemePackageApplyResult.Applied
        } catch (error: Exception) {
            // 应用失败回退到上一个可用主题（写入失败时整份 Settings 回滚）
            runCatching { settingsStore.update(previous) }
            ThemePackageApplyResult.Reverted
        }
}
