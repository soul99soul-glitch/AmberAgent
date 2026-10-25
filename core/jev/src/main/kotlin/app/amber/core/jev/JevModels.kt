package app.amber.core.jev

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Jev (TypeSafe systemone) 判断服务的领域模型。
 *
 * 架构约定：Jev 只返回有限候选的评分与选择；过滤范围、预算、执行与权限
 * 全部留在调用方代码。失败、超时、缺题、非法数值一律回退原路径。
 */
@Serializable
enum class JevMode { OFF, SHADOW, ACTIVE }

/**
 * 出站调用方言。
 *
 * - TYPESAFE = typesafe 原生 `/v1/systemone`；
 * - VERCEL = Vercel AI Gateway 的 decision-model 契约 `POST {base}/decision-model`：
 *   body 为 `{stateParts, questions}`（状态按有序文本块发送，模型走 `ai-model-id`
 *   header，题型 noul 在线上记作 boolean，spec 版本走 `ai-decision-model-specification-version`）；
 * - PERPLEXITY / LIQUID = 第三方 decision 模型，与原生 systemone 同构
 *   （body `{model, state, questions}`、答案内嵌 confidence），只有 endpoint 与模型 id 不同。
 */
@Serializable
enum class JevApiMode { TYPESAFE, VERCEL, PERPLEXITY, LIQUID }

/** [requiredScopes]：该用途每次请求都需要的外发授权；运行时与设置页的可用性判断共用这一处定义。 */
@Serializable
enum class JevPurpose(val requiredScopes: Set<JevDataScope>) {
    TOOL_DISCOVERY(setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT)),
    MEMORY_RECALL(setOf(JevDataScope.PERSONAL_MEMORY, JevDataScope.TASK_TEXT)),
    CONTEXT_SELECTION(setOf(JevDataScope.TOOL_OUTPUT, JevDataScope.TASK_TEXT)),
    MODEL_ROUTING(setOf(JevDataScope.TASK_TEXT, JevDataScope.TOOL_METADATA)),
    WEB_AUTOMATION(setOf(JevDataScope.WEB_CONTENT, JevDataScope.TASK_TEXT)),
    PHONE_VERIFICATION(setOf(JevDataScope.SCREEN_CONTENT, JevDataScope.TASK_TEXT)),
    PHONE_ACTIONS(setOf(JevDataScope.SCREEN_CONTENT, JevDataScope.TASK_TEXT)),
    TOOL_RESULT_RETENTION(setOf(JevDataScope.TOOL_OUTPUT, JevDataScope.TASK_TEXT)),
    AUTO_APPROVAL_GATE(setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT)),
    APPROVAL_TRIAGE(setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT)),
    COMPLETION_CHECK(setOf(JevDataScope.TASK_TEXT, JevDataScope.TOOL_METADATA)),
    SUBAGENT_INTENT(setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT)),
    SCRIPT_JUDGMENT(setOf(JevDataScope.TOOL_OUTPUT)),
    ;

    companion object {
        const val VISIBLE_COUNT = 13
        const val LEGACY_SCREEN_AUTOMATION = "SCREEN_AUTOMATION"
    }
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
    /** 手机屏幕截图（实验）。默认关闭，必须单独授权；未授权时请求只发文字。 */
    SCREEN_SCREENSHOTS,
}

/** 独立于聊天 provider 的 Jev 设置；真实 API Key 只存 SecretStore，这里仅留掩码。 */
@Serializable
data class JevSetting(
    val enabled: Boolean = false,
    /** TYPESAFE 模式的固定模型版本；null 回落 [JevLimits.DEFAULT_MODEL]。 */
    val model: String? = null,
    /** VERCEL 模式的网关 decision 模型 id（默认 typesafe-ai/jev-1.14.0）；必填，缺省由协调器 NO_MODEL 跳过。 */
    val vercelModel: String? = null,
    @Serializable(with = JevPurposeModesSerializer::class)
    val purposes: Map<JevPurpose, JevMode> = emptyMap(),
    val dataScopes: Set<JevDataScope> = emptySet(),
    val apiKeyMask: String? = null,
    val apiMode: JevApiMode = JevApiMode.TYPESAFE,
    /** VERCEL 模式的网关 base URL；null 用 [JevLimits.VERCEL_DEFAULT_BASE_URL]。 */
    val baseUrl: String? = null,
    /** PERPLEXITY 模式的模型 id；null 用 [JevLimits.PERPLEXITY_DEFAULT_MODEL]。 */
    val perplexityModel: String? = null,
    /** LIQUID 模式的模型 id；null 用 [JevLimits.LIQUID_DEFAULT_MODEL]。 */
    val liquidModel: String? = null,
) {
    fun modeFor(purpose: JevPurpose): JevMode = if (!enabled) JevMode.OFF else purposes[purpose] ?: JevMode.OFF

    /** 当前方言下用户显式固定的模型；null = 未配置，按 [defaultModelFor] 回落。 */
    fun modelFor(apiMode: JevApiMode): String? = when (apiMode) {
        JevApiMode.TYPESAFE -> model
        JevApiMode.VERCEL -> vercelModel
        JevApiMode.PERPLEXITY -> perplexityModel
        JevApiMode.LIQUID -> liquidModel
    }

    fun withModel(apiMode: JevApiMode, value: String?): JevSetting = when (apiMode) {
        JevApiMode.TYPESAFE -> copy(model = value)
        JevApiMode.VERCEL -> copy(vercelModel = value)
        JevApiMode.PERPLEXITY -> copy(perplexityModel = value)
        JevApiMode.LIQUID -> copy(liquidModel = value)
    }
}

/**
 * Persist [JevSetting.purposes] without a 14th visible enum.
 *
 * Coexist priority: explicit `PHONE_*` keys win; a legacy `SCREEN_AUTOMATION`
 * value fills only missing phone keys; absent legacy (old OFF stored as
 * missing) stays OFF. Encode never writes `SCREEN_AUTOMATION`.
 */
object JevPurposeModesSerializer : KSerializer<Map<JevPurpose, JevMode>> {
    private val mapSerializer = MapSerializer(JevPurpose.serializer(), JevMode.serializer())

    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Map<JevPurpose, JevMode>) {
        mapSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): Map<JevPurpose, JevMode> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return mapSerializer.deserialize(decoder)
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        val known = linkedMapOf<JevPurpose, JevMode>()
        var legacy: JevMode? = null
        for ((key, element) in obj) {
            if (key == JevPurpose.LEGACY_SCREEN_AUTOMATION) {
                legacy = jsonDecoder.json.decodeFromJsonElement(JevMode.serializer(), element)
                continue
            }
            val purpose = JevPurpose.entries.firstOrNull { it.name == key } ?: continue
            known[purpose] = jsonDecoder.json.decodeFromJsonElement(JevMode.serializer(), element)
        }
        return migrateJevPurposeModes(known, legacy)
    }
}

/**
 * Apply the documented coexist priority used by persisted settings decode.
 *
 * @param known already-decoded visible purposes (explicit `PHONE_*` included)
 * @param legacyScreenAutomation mode of the retired `SCREEN_AUTOMATION` key, or
 *   null if that key was absent (old OFF stored as missing)
 */
fun migrateJevPurposeModes(
    known: Map<JevPurpose, JevMode>,
    legacyScreenAutomation: JevMode?,
): Map<JevPurpose, JevMode> {
    if (legacyScreenAutomation == null) return known
    val result = known.toMutableMap()
    result.putIfAbsent(JevPurpose.PHONE_VERIFICATION, legacyScreenAutomation)
    result.putIfAbsent(JevPurpose.PHONE_ACTIONS, legacyScreenAutomation)
    return result
}

/** 一次 decide 调用携带的配置快照；异步返回后与当前配置比对，防止旧结果串任务。 */
data class JevRuntimeConfig(
    val mode: JevMode,
    val allowedScopes: Set<JevDataScope>,
    val model: String,
    val policyVersion: Int,
    val apiMode: JevApiMode = JevApiMode.TYPESAFE,
    /** 完整 URL，须由 [jevEndpointFor] 解析；不给默认值——VERCEL 误用 typesafe 域名会静默发错信封。 */
    val endpoint: String,
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

    /**
     * 目标定位题：返回归一化四边与 confidence（0..1 比例，相对整张图）。
     * 只在支持 Box 的原生方言上可用；**Box 永不作为发起点击的依据**，
     * 目标不存在时服务端同样会返回 Box，只能当候选定位的辅助证据。
     */
    data class Box(
        override val instructions: String,
        val targetDescription: String? = null,
    ) : JevQuestion
}

/**
 * 一张随请求发送的图片。参考标记必须出现在 state 文本里，
 * 网关按它在 stateParts 的位置切成 file part；原生方言走 `assets` 映射。
 */
data class JevImageAsset(
    val reference: String,
    /** `png` 或 `jpeg`。 */
    val format: String,
    val data: ByteArray,
    /** 出图尺寸；已知时按官方 280–1568 每边范围校验，未知则不猜。 */
    val widthPx: Int? = null,
    val heightPx: Int? = null,
) {
    init {
        require(reference.isNotBlank()) { "image reference is required" }
        require(format == "png" || format == "jpeg") { "unsupported image format: $format" }
        require(data.isNotEmpty()) { "empty image data" }
        listOfNotNull(widthPx, heightPx).forEach { edge ->
            require(edge in JevLimits.MIN_IMAGE_EDGE_PX..JevLimits.MAX_IMAGE_EDGE_PX) {
                "image edge out of range: $edge (allowed ${JevLimits.MIN_IMAGE_EDGE_PX}..${JevLimits.MAX_IMAGE_EDGE_PX})"
            }
        }
    }

    fun base64(): String = java.util.Base64.getEncoder().encodeToString(data)
}

sealed interface JevAnswer {
    data class Noul(val probability: Double) : JevAnswer

    data class Choice(val selected: String, val confidence: Double?) : JevAnswer

    data class Score(val score: Double, val confidence: Double?) : JevAnswer

    /** 归一化四边（0..1）与 confidence；坐标只描述位置，不构成可执行动作。 */
    data class Box(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val confidence: Double?,
    ) : JevAnswer
}

@Serializable
data class JevUsage(val inputTokens: Int, val outputTokens: Int)

enum class JevSkipReason {
    OFF, NO_KEY, NO_MODEL, SCOPE_NOT_ALLOWED, BUDGET_EXHAUSTED, COOLDOWN, AUTH_PAUSED,
    EMPTY_QUESTIONS, TOO_MANY_QUESTIONS, STATE_TOO_LARGE, REQUEST_TOO_LARGE, CONFIGURATION_CHANGED,
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
 * 初始实验参数（非供应商承诺）。实测后可调整并升级 JevPolicy.CURRENT_POLICY_VERSION。
 */
object JevLimits {
    const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
    /** VERCEL 模式缺省网关；`{base}/decision-model` 追加路径。 */
    const val VERCEL_DEFAULT_BASE_URL = "https://ai-gateway.vercel.sh/v4/ai"
    /** VERCEL 模式缺省 decision 模型（Gateway 上的 canonical Model ID，已含 1.14）。 */
    const val VERCEL_DEFAULT_MODEL = "typesafe-ai/jev-1.14.0"
    /** Perplexity Decisions 端点：与 systemone 同构，只有 endpoint 与模型 id 不同。 */
    const val PERPLEXITY_ENDPOINT = "https://api.perplexity.ai/v1/decisions"
    const val PERPLEXITY_DEFAULT_MODEL = "pplx-decider-v1.2"
    /** Liquid AI decision models 端点；模型 d1 / d1-ultra。 */
    const val LIQUID_ENDPOINT = "https://api.liquid.ai/decisions/v1/systemone"
    const val LIQUID_DEFAULT_MODEL = "d1"
    /** 连接测试与实验用；active 用途应使用经验收固定的 [JevSetting.model]。 */
    const val CONNECTION_TEST_MODEL = "jev-1.14.0"
    /** 推荐固定版本：图片/Box 与新协议均已验收的 1.14。 */
    const val RECOMMENDED_MODEL = "jev-1.14.0"
    /** 未固定时的缺省版本；浮动别名（jev-latest / jev-preview）不计为已验收版本。 */
    const val DEFAULT_MODEL = RECOMMENDED_MODEL

    /** 单次出站请求（含排队/重试/解析）的总 deadline；超时不再阻塞原路径。 */
    const val DECISION_DEADLINE_MS = 1_200L

    /** VERCEL 走通用 chat 模型过网关，结构化输出常态 1~3s；沿用 1.2s 会把模式打成事实不可用。 */
    const val VERCEL_DECISION_DEADLINE_MS = 4_000L

    const val MAX_QUESTIONS_PER_REQUEST = 32
    const val MAX_CANDIDATES = 64
    const val MAX_STATE_BYTES = 48 * 1024
    const val MAX_REQUEST_BODY_BYTES = 64 * 1024
    /** 带图片的请求单独放行到 2 MiB；纯文本请求仍守 64 KiB，不放宽。 */
    const val MAX_IMAGE_REQUEST_BODY_BYTES = 2 * 1024 * 1024
    /** 图片每边像素范围（与官方 images 契约一致）。 */
    const val MIN_IMAGE_EDGE_PX = 280
    const val MAX_IMAGE_EDGE_PX = 1568
    const val MAX_RESPONSE_BODY_BYTES = 256 * 1024

    /** 在途上限：每 run 最多 1 个、App 全局最多 3 个实际请求。 */
    const val MAX_INFLIGHT_GLOBAL = 3

    /** 单用户轮（run）共享预算；重试计数，缓存命中不重复计费。 */
    const val PER_RUN_MAX_REQUESTS = 2_000
    const val PER_RUN_MAX_STATE_BYTES = 64L * 1024 * 1024

    /** App 日预算（本地软上限，非供应商账单）。 */
    const val DAILY_MAX_REQUESTS = 100_000
    const val DAILY_MAX_BODY_BYTES = 2L * 1024 * 1024 * 1024

    const val CACHE_MAX_ENTRIES = 128
    const val CACHE_TTL_MS = 5 * 60_000L

    const val TRANSIENT_RETRY_LIMIT = 1
    const val RETRY_BACKOFF_MS = 150L
    const val COOLDOWN_AFTER_CONSECUTIVE_FAILURES = 3
    const val COOLDOWN_MS = 60_000L
}

private val JEV_ZERO_WIDTH_CHARS = charArrayOf(
    0x200B.toChar(), // ZERO WIDTH SPACE
    0x200C.toChar(), // ZERO WIDTH NON-JOINER
    0x200D.toChar(), // ZERO WIDTH JOINER
    0xFEFF.toChar(), // ZERO WIDTH NO-BREAK SPACE (BOM)
    0x2060.toChar(), // WORD JOINER
)

private fun Char.isJevIgnorableOrControl(): Boolean =
    isWhitespace() || code <= 0x1f || code == 0x7f || this in JEV_ZERO_WIDTH_CHARS

/**
 * 清洗 Jev API Key：
 * 1. 先去除零宽字符、首尾空白与控制字符，并剥离首尾成对引号（`"`、`'`）；
 * 2. 若包含 `Authorization:` 或 `Bearer ` 前缀（忽略大小写），自动剥离并再次去首尾成对引号；
 * 3. 移除内部所有换行（`\r`、`\n`）、制表符、空白字符、ASCII 控制字符（`<= 0x1f` 或 `0x7f`）及零宽字符。
 */
fun sanitizeJevApiKey(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""
    var current = buildString(raw.length) {
        for (ch in raw) {
            if (ch !in JEV_ZERO_WIDTH_CHARS) append(ch)
        }
    }.trim { it.isJevIgnorableOrControl() }

    fun stripQuotes(input: String): String {
        var s = input.trim { it.isJevIgnorableOrControl() }
        while (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\'')))) {
            s = s.substring(1, s.length - 1).trim { it.isJevIgnorableOrControl() }
        }
        return s
    }

    current = stripQuotes(current)
    if (current.startsWith("Authorization:", ignoreCase = true)) {
        current = stripQuotes(current.substring("Authorization:".length))
    }
    if (current.startsWith("Bearer", ignoreCase = true) &&
        (current.length == 6 || current[6].isJevIgnorableOrControl())
    ) {
        current = stripQuotes(current.substring("Bearer".length))
    }

    return buildString(current.length) {
        for (ch in current) {
            if (!ch.isJevIgnorableOrControl()) {
                append(ch)
            }
        }
    }
}

/**
 * 清洗模型名、Base URL 及 HTTP Header 名/值：
 * 移除所有换行（`\r`、`\n`）、制表符、ASCII 控制字符（`<= 0x1f` 或 `0x7f`）及零宽字符，并去除首尾空白。
 */
fun sanitizeJevHeaderValue(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""
    return buildString(raw.length) {
        for (ch in raw) {
            if (ch.code > 0x1f && ch.code != 0x7f && ch !in JEV_ZERO_WIDTH_CHARS) {
                append(ch)
            }
        }
    }.trim()
}

/** 解析本次调用的完整 URL；VERCEL 模式对 base 去尾斜杠后追加 /decision-model。 */
fun jevEndpointFor(apiMode: JevApiMode, baseUrl: String?): String = when (apiMode) {
    JevApiMode.TYPESAFE -> JevLimits.ENDPOINT
    JevApiMode.VERCEL ->
        (sanitizeJevHeaderValue(baseUrl).trimEnd('/').takeIf { it.isNotEmpty() }
            ?: JevLimits.VERCEL_DEFAULT_BASE_URL) + "/decision-model"
    JevApiMode.PERPLEXITY -> JevLimits.PERPLEXITY_ENDPOINT
    JevApiMode.LIQUID -> JevLimits.LIQUID_ENDPOINT
}

/**
 * 该方言是否支持图片输入。Perplexity / Liquid 走 systemone 同构但未实现图片编码，
 * 必须显式拒绝，否则会把图片按 systemone 形状发到不支持它的服务换一个 400。
 */
fun jevSupportsImages(apiMode: JevApiMode): Boolean = when (apiMode) {
    JevApiMode.TYPESAFE -> true
    // 网关只有固定 1.14 支持图片；浮动别名不作为已验收版本。
    JevApiMode.VERCEL -> true
    JevApiMode.PERPLEXITY, JevApiMode.LIQUID -> false
}

/** 只有原生 systemone 支持 Box 题型（Gateway 不支持）。 */
fun jevSupportsBox(apiMode: JevApiMode): Boolean = apiMode == JevApiMode.TYPESAFE

/** 未显式固定模型时该方言的缺省模型 id。 */
fun jevDefaultModelFor(apiMode: JevApiMode): String = when (apiMode) {
    JevApiMode.TYPESAFE -> JevLimits.DEFAULT_MODEL
    JevApiMode.VERCEL -> JevLimits.VERCEL_DEFAULT_MODEL
    JevApiMode.PERPLEXITY -> JevLimits.PERPLEXITY_DEFAULT_MODEL
    JevApiMode.LIQUID -> JevLimits.LIQUID_DEFAULT_MODEL
}

/** 连接测试用的模型 id：TypeSafe 探测已验收的固定版本，其余用各自默认 id。 */
fun jevConnectionTestModelFor(apiMode: JevApiMode): String = when (apiMode) {
    JevApiMode.TYPESAFE -> JevLimits.CONNECTION_TEST_MODEL
    JevApiMode.VERCEL -> JevLimits.VERCEL_DEFAULT_MODEL
    JevApiMode.PERPLEXITY -> JevLimits.PERPLEXITY_DEFAULT_MODEL
    JevApiMode.LIQUID -> JevLimits.LIQUID_DEFAULT_MODEL
}

/**
 * 单次 decide 总 deadline，按方言分档：VERCEL 经网关 + 通用模型显著更慢；
 * Perplexity / Liquid 是第三方托管的 decision 服务，与网关同档以免把可用服务判成超时。
 */
fun jevDeadlineMs(apiMode: JevApiMode): Long = when (apiMode) {
    JevApiMode.TYPESAFE -> JevLimits.DECISION_DEADLINE_MS
    JevApiMode.VERCEL, JevApiMode.PERPLEXITY, JevApiMode.LIQUID -> JevLimits.VERCEL_DECISION_DEADLINE_MS
}
