package app.amber.core.jev

import kotlinx.serialization.Serializable

/**
 * Jev (TypeSafe systemone) 判断服务的领域模型。
 *
 * 架构约定：Jev 只返回有限候选的评分与选择；过滤范围、预算、执行与权限
 * 全部留在调用方代码。失败、超时、缺题、非法数值一律回退原路径。
 */
@Serializable
enum class JevMode { OFF, SHADOW, ACTIVE }

@Serializable
enum class JevPurpose {
    TOOL_DISCOVERY,
    MEMORY_RECALL,
    CONTEXT_SELECTION,
    MODEL_ROUTING,
    WEB_AUTOMATION,
    SCREEN_AUTOMATION,
}

/** 数据外发范围。请求所需范围全部被允许才发送；shadow 同样外发，不是本地模式。 */
@Serializable
enum class JevDataScope {
    TOOL_METADATA,
    TASK_TEXT,
    PERSONAL_MEMORY,
    TOOL_OUTPUT,
    WEB_CONTENT,
    SCREEN_CONTENT,
}

/** 独立于聊天 provider 的 Jev 设置；真实 API Key 只存 SecretStore，这里仅留掩码。 */
@Serializable
data class JevSetting(
    val enabled: Boolean = false,
    /** active/shadow 使用的固定模型版本；null 回落 [JevLimits.DEFAULT_MODEL]。 */
    val model: String? = null,
    val purposes: Map<JevPurpose, JevMode> = emptyMap(),
    val dataScopes: Set<JevDataScope> = emptySet(),
    val apiKeyMask: String? = null,
) {
    fun modeFor(purpose: JevPurpose): JevMode = if (!enabled) JevMode.OFF else purposes[purpose] ?: JevMode.OFF
}

/** 一次 decide 调用携带的配置快照；异步返回后与当前配置比对，防止旧结果串任务。 */
data class JevRuntimeConfig(
    val mode: JevMode,
    val allowedScopes: Set<JevDataScope>,
    val model: String,
    val policyVersion: Int,
)

sealed interface JevQuestion {
    val instructions: String

    /** 是非概率题；答案为 0..1 概率，协议不提供 confidence，不得伪造。 */
    data class Noul(
        override val instructions: String,
        val trueCriteria: String? = null,
        val falseCriteria: String? = null,
    ) : JevQuestion

    /** 有限选项单选题；criteria 为选项 id 到说明（可为 null）的映射。 */
    data class Choice(
        override val instructions: String,
        val options: Map<String, String?>,
    ) : JevQuestion

    /** 有序等级评分题（≥2 级）；答案 score 可落在等级之间。 */
    data class Score(
        override val instructions: String,
        val levels: List<String>,
    ) : JevQuestion
}

sealed interface JevAnswer {
    data class Noul(val probability: Double) : JevAnswer

    data class Choice(val selected: String, val confidence: Double?) : JevAnswer

    data class Score(val score: Double, val confidence: Double?) : JevAnswer
}

@Serializable
data class JevUsage(val inputTokens: Int, val outputTokens: Int)

enum class JevSkipReason {
    OFF, NO_KEY, SCOPE_NOT_ALLOWED, BUDGET_EXHAUSTED, COOLDOWN, AUTH_PAUSED,
    EMPTY_QUESTIONS, TOO_MANY_QUESTIONS, STATE_TOO_LARGE, REQUEST_TOO_LARGE,
}

enum class JevFailureReason { TIMEOUT, AUTH, RATE_LIMITED, CLIENT_ERROR, SERVER_ERROR, NETWORK, INVALID_RESPONSE }

sealed interface JevDecision {
    data class Evaluated(
        val answers: Map<String, JevAnswer>,
        val model: String?,
        val usage: JevUsage?,
        val latencyMs: Long,
        val fromCache: Boolean,
    ) : JevDecision

    data class Skipped(val reason: JevSkipReason) : JevDecision

    data class Failed(val reason: JevFailureReason, val detail: String? = null) : JevDecision
}

/**
 * 初始实验参数（非供应商承诺）。实测后可调整并升级 policyVersion。
 */
object JevLimits {
    const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
    /** 连接测试与实验用；active 用途应使用经验收固定的 [JevSetting.model]。 */
    const val CONNECTION_TEST_MODEL = "jev-latest"
    const val DEFAULT_MODEL = "jev-latest"

    /** 单次出站请求（含排队/重试/解析）的总 deadline；超时不再阻塞原路径。 */
    const val DECISION_DEADLINE_MS = 1_200L

    const val MAX_QUESTIONS_PER_REQUEST = 32
    const val MAX_CANDIDATES = 64
    const val MAX_STATE_BYTES = 48 * 1024
    const val MAX_REQUEST_BODY_BYTES = 64 * 1024
    const val MAX_RESPONSE_BODY_BYTES = 256 * 1024

    /** 在途上限：每 run 最多 1 个、App 全局最多 3 个实际请求。 */
    const val MAX_INFLIGHT_GLOBAL = 3

    /** 单用户轮（run）共享预算；重试计数，缓存命中不重复计费。 */
    const val PER_RUN_MAX_REQUESTS = 6
    const val PER_RUN_MAX_STATE_BYTES = 256 * 1024

    /** App 日预算（本地软上限，非供应商账单）。 */
    const val DAILY_MAX_REQUESTS = 1_000
    const val DAILY_MAX_BODY_BYTES = 16L * 1024 * 1024

    const val CACHE_MAX_ENTRIES = 128
    const val CACHE_TTL_MS = 5 * 60_000L

    const val TRANSIENT_RETRY_LIMIT = 1
    const val RETRY_BACKOFF_MS = 150L
    const val COOLDOWN_AFTER_CONSECUTIVE_FAILURES = 3
    const val COOLDOWN_MS = 60_000L
}
