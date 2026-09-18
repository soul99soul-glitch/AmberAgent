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
) {
    fun configFor(purpose: JevPurpose): JevRuntimeConfig? {
        val setting = settingsProvider().jev
        val mode = setting.modeFor(purpose)
        if (mode == JevMode.OFF) return null
        return JevRuntimeConfig(
            mode = mode,
            allowedScopes = setting.dataScopes,
            model = setting.model ?: JevLimits.DEFAULT_MODEL,
            policyVersion = POLICY_VERSION,
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

    companion object {
        const val POLICY_VERSION = 1
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
