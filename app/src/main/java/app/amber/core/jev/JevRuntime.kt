package app.amber.core.jev

import app.amber.core.settings.Settings
import kotlinx.serialization.json.JsonElement

/**
 * app 层 Jev 运行时入口：把 Settings.jev 解析为协调器配置快照，
 * 并在异步返回后重验配置，防止旧结果串入新任务。
 */
class JevRuntime(
    private val coordinator: JevDecisionCoordinator,
    private val settingsProvider: () -> Settings,
    /** 逐用途置信阈值；policyVersion 参与缓存键，阈值变更须递增版本。 */
    val policy: JevPolicy = JevPolicy(),
    /** 校准记录落点；默认内存实现（测试），生产由 DI 注入文件实现。 */
    val calibration: JevCalibrationStore = JevCalibrationStore.IN_MEMORY,
) {
    fun configFor(purpose: JevPurpose): JevRuntimeConfig? {
        val setting = settingsProvider().jev
        val mode = setting.modeFor(purpose)
        if (mode == JevMode.OFF) return null
        return JevRuntimeConfig(
            mode = mode,
            allowedScopes = setting.dataScopes,
            // 两模式模型字段独立（model=typesafe 固定版本，vercelModel=网关评估模型 id）
            // 互不串味；VERCEL 空值回落 canonical 默认 typesafe-ai/jev。
            model = when (setting.apiMode) {
                JevApiMode.TYPESAFE -> setting.model ?: JevLimits.DEFAULT_MODEL
                JevApiMode.VERCEL -> setting.vercelModel?.takeIf { it.isNotBlank() }
                    ?: JevLimits.VERCEL_DEFAULT_MODEL
            },
            policyVersion = policy.policyVersion,
            apiMode = setting.apiMode,
            endpoint = jevEndpointFor(setting.apiMode, setting.baseUrl),
        )
    }

    /**
     * 单次判断。返回 null 表示该用途当前 OFF（零开销快路径）；
     * 返回的结果若 [JevRuntimeOutcome.applicable] 为 false，调用方一律走原路径。
     */
    suspend fun decide(
        purpose: JevPurpose,
        runKey: String?,
        state: JsonElement,
        questions: Map<String, JevQuestion>,
        requiredScopes: Set<JevDataScope>,
        cacheAnchor: String? = null,
    ): JevRuntimeOutcome? {
        val config = configFor(purpose) ?: return null
        val decision = coordinator.decide(purpose, config, runKey, state, questions, requiredScopes, cacheAnchor)
        val stale = configFor(purpose) != config
        return JevRuntimeOutcome(config.mode, decision, stale)
    }
}

class JevRuntimeOutcome internal constructor(
    val mode: JevMode,
    val decision: JevDecision,
    /** 判断期间用途配置发生变化：Evaluated 也不得应用。 */
    val stale: Boolean,
) {
    /** 已评估、未过期且为 ACTIVE：结果可用于业务应用。 */
    val applicable: Boolean
        get() = decision is JevDecision.Evaluated && !stale && mode == JevMode.ACTIVE

    val evaluated: JevDecision.Evaluated?
        get() = decision as? JevDecision.Evaluated
}
