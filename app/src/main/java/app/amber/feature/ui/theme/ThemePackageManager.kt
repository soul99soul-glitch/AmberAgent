package app.amber.feature.ui.theme

import app.amber.agent.data.db.dao.ThemePackageDAO
import app.amber.agent.data.db.entity.ThemePackageEntity
import app.amber.core.settings.Settings
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.ThemePackDocument
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.utils.JsonInstant
import app.amber.feature.runtime.ContentDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** 主题库写入用的窄接口（真实实现包 SettingsAggregator；测试用内存实现）。 */
interface ThemeSettingsStore {
    val settingsFlow: Flow<Settings>

    suspend fun update(settings: Settings)

    suspend fun update(transform: (Settings) -> Settings) {
        update(transform(settingsFlow.first()))
    }
}

class SettingsAggregatorThemeStore(
    private val aggregator: SettingsAggregator,
) : ThemeSettingsStore {
    override val settingsFlow: Flow<Settings> = aggregator.settingsFlow

    override suspend fun update(settings: Settings) = aggregator.update(settings)

    override suspend fun update(transform: (Settings) -> Settings) = aggregator.update(transform)
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
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {

    private val _tryOn = MutableStateFlow<ThemePackageTryOn?>(null)
    val tryOn: StateFlow<ThemePackageTryOn?> = _tryOn.asStateFlow()
    private val themeWriteMutex = Mutex()

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
                val prepared = ThemePackageTryOn(
                    pkg = validation.themePackage,
                    unknownTokens = validation.unknownTokens,
                    candidate = candidate,
                    candidateDigest = candidateDigestFor(json),
                    rawJson = json,
                )
                _tryOn.value = prepared
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
        val prepared = currentTryOn() ?: return ThemePackageApplyResult.NotPrepared
        if (packageId != prepared.pkg.id || candidateDigest != prepared.candidateDigest) {
            return ThemePackageApplyResult.NotPrepared
        }

        return withThemeWrite {
            if (!isCurrentTryOn(prepared)) return@withThemeWrite ThemePackageApplyResult.NotPrepared
            val previousEntity = dao.getById(prepared.pkg.id)
            val nextEntity = ThemePackageEntity(
                id = prepared.pkg.id,
                name = prepared.pkg.name,
                json = preparedJson(prepared),
                importedAtMs = now().toEpochMilli(),
            )

            try {
                dao.upsert(nextEntity)
                // The DAO may suspend after writing. A try-on discarded or replaced
                // during that write must not be allowed to apply its stale snapshot.
                if (!isCurrentTryOn(prepared)) {
                    restoreEntity(prepared.pkg.id, previousEntity)
                    return@withThemeWrite ThemePackageApplyResult.NotPrepared
                }

                when (val settingsWrite = writeDisplaySetting(
                    isCurrent = { isCurrentTryOn(prepared) },
                    transform = { current ->
                        ThemePackageApplier.applyTokens(prepared.pkg, current)
                            .copy(appliedThemePackageId = prepared.pkg.id)
                    },
                )) {
                    is ThemeSettingsWriteResult.Invalidated -> {
                        restoreEntity(prepared.pkg.id, previousEntity)
                        ThemePackageApplyResult.NotPrepared
                    }
                    is ThemeSettingsWriteResult.Reverted -> {
                        restoreEntity(prepared.pkg.id, previousEntity)
                        if (isCurrentTryOn(prepared)) ThemePackageApplyResult.Reverted
                        else ThemePackageApplyResult.NotPrepared
                    }
                    is ThemeSettingsWriteResult.Applied -> {
                        // This compare-and-set is the try-on commit point.
                        // If discard/import won while Settings was being persisted, undo
                        // only this write's theme fields and restore the prior library row.
                        if (!clearTryOnIfCurrent(prepared)) {
                            rollbackThemeFields(settingsWrite.previous, settingsWrite.applied)
                            restoreEntity(prepared.pkg.id, previousEntity)
                            ThemePackageApplyResult.NotPrepared
                        } else {
                            // Applying an identical package still stores the explicit import.
                            ThemePackageApplyResult.Applied
                        }
                    }
                }
            } catch (_: Exception) {
                // Roll back while this writer still owns the short persistence
                // boundary; a restore/new writer cannot observe a half-applied
                // theme package.
                restoreEntity(prepared.pkg.id, previousEntity)
                ThemePackageApplyResult.Reverted
            }
        }
    }

    /** 放弃内存 try-on；不写库、不写 Settings。 */
    fun discardTryOn(packageId: String, candidateDigest: String): Boolean {
        val prepared = _tryOn.value ?: return false
        if (packageId != prepared.pkg.id || candidateDigest != prepared.candidateDigest) return false
        return _tryOn.compareAndSet(prepared, null)
    }

    suspend fun status(): ThemePackageStatus = ThemePackageStatus(
        current = settingsStore.settingsFlow.first(),
        installed = dao.observeAll().first(),
        tryOn = _tryOn.value,
    )

    /** A continuation always edits the visible candidate before the saved recipe. */
    suspend fun recipe(id: String = "current"): ThemePackDocument {
        val current = settingsStore.settingsFlow.first().displaySetting
        _tryOn.value?.let { preview ->
            if (id == "current" || id == preview.pkg.id) {
                return ThemePackTransfer.fromPackage(preview.pkg, preview.candidate)
            }
        }
        if (id == "current" || id == current.appliedThemePackageId) {
            if (current.appliedThemePackageId == null && current.themePack == null) {
                val builtin = ThemePackTransfer.builtin("builtin:${current.amberBaseFamily}")
                if (builtin?.accentHex.equals(current.accentColor, ignoreCase = true)) return requireNotNull(builtin)
            }
            val exported = ThemePackTransfer.export(current)
            val entity = current.appliedThemePackageId?.let { dao.getById(it) }
            return if (entity != null) exported.copy(id = entity.id, displayName = entity.name) else exported
        }
        ThemePackTransfer.builtin(id)?.let { return it }
        val entity = dao.getById(id) ?: error("找不到主题 $id，请先调用 theme_pack_status")
        val validation = ThemePackageValidator.validateJson(entity.json) as? ThemePackageValidation.Valid
            ?: error("主题 $id 的配方已损坏")
        return ThemePackTransfer.fromPackage(validation.themePackage, current)
    }

    suspend fun apply(packageId: String): ThemePackageApplyResult {
        return withThemeWrite {
            val preparedAtStart = currentTryOn()
            val entity = dao.getById(packageId) ?: return@withThemeWrite ThemePackageApplyResult.NotFound
            val validation = ThemePackageValidator.validateJson(entity.json) as? ThemePackageValidation.Valid
                ?: return@withThemeWrite ThemePackageApplyResult.Corrupt
            val pkg = validation.themePackage
            val result = when (val write = writeDisplaySetting { current ->
                ThemePackageApplier.applyTokens(pkg, current).copy(appliedThemePackageId = pkg.id)
            }) {
                is ThemeSettingsWriteResult.Applied -> if (write.previous == write.applied) {
                    ThemePackageApplyResult.AlreadyApplied
                } else {
                    ThemePackageApplyResult.Applied
                }
                ThemeSettingsWriteResult.Invalidated -> error("Unexpected try-on invalidation")
                ThemeSettingsWriteResult.Reverted -> ThemePackageApplyResult.Reverted
            }
            if (result == ThemePackageApplyResult.Applied || result == ThemePackageApplyResult.AlreadyApplied) {
                preparedAtStart?.let(::clearTryOnIfCurrent)
            }
            result
        }
    }

    /** Warm is the default dot-grid terracotta preset; custom typography/layout are retained. */
    suspend fun applyBuiltin(baseFamily: String): ThemePackageApplyResult {
        require(baseFamily in setOf("WARM", "SAGE")) { "未知的内置色系：$baseFamily" }
        return withThemeWrite {
            val preparedAtStart = currentTryOn()
            val result = when (val write = writeDisplaySetting { current -> current.copy(
                amberBaseFamily = baseFamily,
                accentColor = if (baseFamily == "WARM") {
                    SIT_TERRACOTTA_ACCENT_HEX
                } else current.accentColor,
                appliedThemePackageId = null,
                themePack = null,
            ) }) {
                is ThemeSettingsWriteResult.Applied -> if (write.previous == write.applied) {
                    ThemePackageApplyResult.AlreadyApplied
                } else {
                    ThemePackageApplyResult.Applied
                }
                ThemeSettingsWriteResult.Invalidated -> error("Unexpected try-on invalidation")
                ThemeSettingsWriteResult.Reverted -> ThemePackageApplyResult.Reverted
            }
            if (result == ThemePackageApplyResult.Applied || result == ThemePackageApplyResult.AlreadyApplied) {
                preparedAtStart?.let(::clearTryOnIfCurrent)
            }
            result
        }
    }

    /** 从主题库移除导入包（`builtin:` 条目不在库中，天然不可移除）。 */
    suspend fun remove(packageId: String): Boolean {
        return withThemeWrite {
            val preparedAtStart = currentTryOn()?.takeIf { it.pkg.id == packageId }
            val previousEntity = dao.getById(packageId) ?: return@withThemeWrite false
            val deleted = dao.delete(packageId) > 0
            if (!deleted) return@withThemeWrite false
            try {
                // Remove only the marker from the latest settings snapshot. The transform
                // avoids overwriting concurrent settings edits with the pre-delete snapshot.
                settingsStore.update { latest ->
                    if (latest.displaySetting.appliedThemePackageId == packageId) {
                        latest.copy(
                            displaySetting = latest.displaySetting.copy(
                                appliedThemePackageId = null,
                            ),
                        )
                    } else {
                        latest
                    }
                }
            } catch (error: Exception) {
                restoreEntity(packageId, previousEntity)
                throw error
            }
            preparedAtStart?.let(::clearTryOnIfCurrent)
            deleted
        }
    }

    private fun preparedJson(prepared: ThemePackageTryOn): String =
        prepared.rawJson

    private fun currentTryOn(): ThemePackageTryOn? = _tryOn.value

    private fun isCurrentTryOn(prepared: ThemePackageTryOn): Boolean =
        _tryOn.value == prepared

    private fun clearTryOnIfCurrent(prepared: ThemePackageTryOn): Boolean =
        _tryOn.compareAndSet(prepared, null)

    private fun candidateDigestFor(rawJson: String): String =
        ContentDigest.sha256(rawJson)

    private suspend fun restoreEntity(id: String, previous: ThemePackageEntity?) {
        if (previous == null) {
            dao.delete(id)
        } else {
            dao.upsert(previous)
        }
    }

    private sealed interface ThemeSettingsWriteResult {
        data class Applied(val previous: DisplaySetting, val applied: DisplaySetting) : ThemeSettingsWriteResult
        data object Invalidated : ThemeSettingsWriteResult
        data object Reverted : ThemeSettingsWriteResult
    }

    private class TryOnInvalidatedException : RuntimeException()

    private suspend fun writeDisplaySetting(
        isCurrent: (() -> Boolean)? = null,
        transform: (DisplaySetting) -> DisplaySetting,
    ): ThemeSettingsWriteResult {
        var previous: DisplaySetting? = null
        var applied: DisplaySetting? = null
        return try {
            settingsStore.update { latest ->
                if (isCurrent != null && !isCurrent()) throw TryOnInvalidatedException()
                val before = latest.displaySetting
                val next = transform(before)
                previous = before
                applied = next
                latest.copy(displaySetting = next)
            }
            ThemeSettingsWriteResult.Applied(checkNotNull(previous), checkNotNull(applied))
        } catch (_: TryOnInvalidatedException) {
            ThemeSettingsWriteResult.Invalidated
        } catch (_: Exception) {
            if (previous != null && applied != null) {
                rollbackThemeFields(previous!!, applied!!)
            }
            ThemeSettingsWriteResult.Reverted
        }
    }

    /** Restore only theme fields written by this operation and still unchanged since it. */
    private suspend fun rollbackThemeFields(previous: DisplaySetting, applied: DisplaySetting) {
        runCatching {
            settingsStore.update { latest ->
                val display = latest.displaySetting
                fun <T> restoreIfStillApplied(current: T, written: T, before: T): T =
                    if (current == written) before else current
                val restored = display.copy(
                    amberBaseFamily = restoreIfStillApplied(
                        display.amberBaseFamily, applied.amberBaseFamily, previous.amberBaseFamily,
                    ),
                    accentColor = restoreIfStillApplied(
                        display.accentColor, applied.accentColor, previous.accentColor,
                    ),
                    appliedThemePackageId = restoreIfStillApplied(
                        display.appliedThemePackageId, applied.appliedThemePackageId,
                        previous.appliedThemePackageId,
                    ),
                    themePack = restoreIfStillApplied(display.themePack, applied.themePack, previous.themePack),
                    chatFontFamily = restoreIfStillApplied(
                        display.chatFontFamily, applied.chatFontFamily, previous.chatFontFamily,
                    ),
                    fontSizeRatio = restoreIfStillApplied(
                        display.fontSizeRatio, applied.fontSizeRatio, previous.fontSizeRatio,
                    ),
                    showUserAvatar = restoreIfStillApplied(
                        display.showUserAvatar, applied.showUserAvatar, previous.showUserAvatar,
                    ),
                    showAssistantBubble = restoreIfStillApplied(
                        display.showAssistantBubble, applied.showAssistantBubble, previous.showAssistantBubble,
                    ),
                )
                latest.copy(displaySetting = restored)
            }
        }
    }

    private suspend fun <T> withThemeWrite(block: suspend () -> T): T =
        if (restoreWriteGate != null) {
            restoreWriteGate.withCurrentWriterOrCancel { themeWriteMutex.withLock { block() } }
        } else {
            themeWriteMutex.withLock { block() }
        }
}
