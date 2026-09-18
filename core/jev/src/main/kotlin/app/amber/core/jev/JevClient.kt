package app.amber.core.jev

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一次出站 HTTP 尝试的分类结果；重试与预算由 [JevDecisionCoordinator] 决定。 */
sealed interface JevCallResult {
    data class Success(
        val answers: Map<String, JevAnswer>,
        val model: String?,
        val usage: JevUsage?,
        val responseBytes: Int,
    ) : JevCallResult

    data class HttpError(val code: Int, val retryAfterMs: Long?, val bodySnippet: String?) : JevCallResult

    data class Transport(val message: String?) : JevCallResult

    /** 解码/校验失败：确定性错误，不重试，上报 INVALID_RESPONSE。 */
    data class Decode(val message: String?) : JevCallResult
}

/** 传输抽象：生产为 OkHttp，测试注入桩。 */
fun interface JevTransport {
    suspend fun execute(request: JevHttpRequest): JevTransportResponse
}

data class JevHttpRequest(
    val url: String,
    val apiKey: String,
    val body: String,
    val timeoutMs: Long,
)

sealed interface JevTransportResponse {
    data class Http(val code: Int, val body: ByteArray?, val retryAfterHeader: String?) : JevTransportResponse

    data class Failure(val message: String?) : JevTransportResponse
}

/** 线格式编解码 + 单次调用。字段名以 docs.typesafe.ai 2026-09-17 核对为准。 */
class JevClient(
    private val transport: JevTransport = OkHttpJevTransport(),
    private val json: Json = Json { encodeDefaults = false; ignoreUnknownKeys = true },
) {

    fun encodeRequestBody(model: String, state: JsonElement, questions: Map<String, JevQuestion>): String {
        val body = buildJsonObject {
            put("model", model)
            put("state", state)
            put("questions", buildJsonObject {
                questions.forEach { (id, question) -> put(id, question.toJson()) }
            })
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    /**
     * 解码并逐题校验：缺题、类型不符、未知候选、非有限数值、超出值域均抛
     * [JevDecodeException]——结构正确不等于事实正确，业务侧仍需自证。
     */
    fun decodeResponse(bodyText: String, questions: Map<String, JevQuestion>): JevDecodedResponse {
        val root = try {
            json.parseToJsonElement(bodyText).jsonObject
        } catch (e: Exception) {
            throw JevDecodeException("response is not a JSON object", e)
        }
        val answersObject = root["answers"] as? JsonObject
            ?: throw JevDecodeException("missing answers")
        val answers = buildMap {
            questions.forEach { (id, question) ->
                val entry = answersObject[id] ?: throw JevDecodeException("missing answer for question $id")
                put(id, decodeAnswer(id, entry, question))
            }
        }
        val usage = (root["usage"] as? JsonObject)?.let { usageObject ->
            JevUsage(
                inputTokens = usageObject["input_tokens"].intOrNullSafe("usage.input_tokens"),
                outputTokens = usageObject["output_tokens"].intOrNullSafe("usage.output_tokens"),
            )
        }
        val model = (root["model"] as? JsonPrimitive)?.contentOrNull
        return JevDecodedResponse(answers = answers, usage = usage, model = model)
    }

    private fun decodeAnswer(id: String, entry: JsonElement, question: JevQuestion): JevAnswer {
        val obj = entry as? JsonObject ?: throw JevDecodeException("answer $id is not an object")
        val type = obj["type"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        return when (question) {
            is JevQuestion.Noul -> {
                if (type != "noul") throw JevDecodeException("answer $id type mismatch: $type")
                val noul = obj["noul"].finiteDouble("answer $id noul")
                    ?: throw JevDecodeException("answer $id missing noul")
                if (noul !in 0.0..1.0) throw JevDecodeException("answer $id noul out of range: $noul")
                JevAnswer.Noul(noul)
            }
            is JevQuestion.Choice -> {
                if (type != "choice") throw JevDecodeException("answer $id type mismatch: $type")
                val selected = obj["choice"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?: throw JevDecodeException("answer $id missing choice")
                if (selected !in question.options) {
                    throw JevDecodeException("answer $id unknown option: $selected")
                }
                JevAnswer.Choice(selected, obj.confidenceOrNull("answer $id"))
            }
            is JevQuestion.Score -> {
                if (type != "score") throw JevDecodeException("answer $id type mismatch: $type")
                val score = obj["score"].finiteDouble("answer $id score")
                    ?: throw JevDecodeException("answer $id missing score")
                if (score < 0.0) throw JevDecodeException("answer $id score negative: $score")
                JevAnswer.Score(score, obj.confidenceOrNull("answer $id"))
            }
        }
    }

    private fun JsonObject.confidenceOrNull(field: String): Double? {
        val value = this["confidence"] ?: return null
        val parsed = value.finiteDouble(field) ?: throw JevDecodeException("$field non-finite")
        if (parsed !in 0.0..1.0) throw JevDecodeException("$field out of range: $parsed")
        return parsed
    }

    private fun JsonElement?.finiteDouble(field: String): Double? =
        (this as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonElement?.intOrNullSafe(field: String): Int =
        (this as? JsonPrimitive)?.intOrNull
            ?: throw JevDecodeException("$field is not an int")
    private fun JevQuestion.toJson(): JsonObject = buildJsonObject {
        put("type", when (this@toJson) {
            is JevQuestion.Noul -> "noul"
            is JevQuestion.Choice -> "choice"
            is JevQuestion.Score -> "score"
        })
        put("instructions", instructions)
        when (val q = this@toJson) {
            is JevQuestion.Noul -> {
                if (q.trueCriteria != null || q.falseCriteria != null) {
                    put("criteria", buildJsonObject {
                        q.trueCriteria?.let { put("true", it) }
                        q.falseCriteria?.let { put("false", it) }
                    })
                }
            }
            is JevQuestion.Choice -> put("criteria", buildJsonObject {
                q.options.forEach { (option, description) ->
                    put(option, description)
                }
            })
            is JevQuestion.Score -> put("criteria", JsonArray(q.levels.map { JsonPrimitive(it) }))
        }
    }

    /** 单次出站尝试。deadline 由 [JevDecisionCoordinator] 通过 withTimeout 施加。 */
    suspend fun call(
        model: String,
        apiKey: String,
        state: JsonElement,
        questions: Map<String, JevQuestion>,
    ): JevCallResult {
        val body = encodeRequestBody(model, state, questions)
        if (body.toByteArray(Charsets.UTF_8).size > JevLimits.MAX_REQUEST_BODY_BYTES) {
            return JevCallResult.Transport("request body too large")
        }
        val response = try {
            transport.execute(
                JevHttpRequest(
                    url = JevLimits.ENDPOINT,
                    apiKey = apiKey,
                    body = body,
                    timeoutMs = JevLimits.DECISION_DEADLINE_MS,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return JevCallResult.Transport(e.message ?: e.javaClass.simpleName)
        }
        return when (response) {
            is JevTransportResponse.Failure -> JevCallResult.Transport(response.message)
            is JevTransportResponse.Http -> {
                val bytes = response.body
                if (response.code !in 200..299) {
                    return JevCallResult.HttpError(
                        code = response.code,
                        retryAfterMs = response.retryAfterHeader?.parseRetryAfterMs(),
                        bodySnippet = bytes?.toString(Charsets.UTF_8)?.take(200),
                    )
                }
                if (bytes == null) return JevCallResult.Transport("empty body")
                if (bytes.size > JevLimits.MAX_RESPONSE_BODY_BYTES) {
                    return JevCallResult.Transport("response body too large")
                }
                try {
                    val decoded = decodeResponse(bytes.toString(Charsets.UTF_8), questions)
                    JevCallResult.Success(decoded.answers, decoded.model, decoded.usage, bytes.size)
                } catch (e: JevDecodeException) {
                    JevCallResult.Decode(e.message)
                } catch (e: Exception) {
                    JevCallResult.Decode(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
}

class JevDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class JevDecodedResponse(
    val answers: Map<String, JevAnswer>,
    val usage: JevUsage?,
    val model: String?,
)

/** Retry-After: 仅解析整数秒形式；HTTP 日期等无法解析时返回 null（由 deadline 兜底）。 */
internal fun String.parseRetryAfterMs(): Long? = trim().toLongOrNull()?.times(1000)

/** 生产传输：OkHttp enqueue，取消沿协程传播到 Call.cancel。 */
class OkHttpJevTransport(
    client: OkHttpClient = OkHttpClient(),
    private val callFactory: (Request) -> Call = { client.newCall(it) },
) : JevTransport {

    override suspend fun execute(request: JevHttpRequest): JevTransportResponse =
        withContext(Dispatchers.IO) {
            val httpRequest = Request.Builder()
                .url(request.url)
                .header("Authorization", "Bearer ${request.apiKey}")
                .post(request.body.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                callFactory(httpRequest).awaitResponse().use { response ->
                    val body = response.body?.bytes()
                    JevTransportResponse.Http(
                        code = response.code,
                        body = body?.takeIf { it.size <= JevLimits.MAX_RESPONSE_BODY_BYTES * 2 },
                        retryAfterHeader = response.header("Retry-After"),
                    )
                }
            } catch (e: IOException) {
                JevTransportResponse.Failure(e.message ?: e.javaClass.simpleName)
            }
        }
}

/** enqueue 包装：协程取消即 Call.cancel，失败经 [IOException] 传播。 */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // 竞态：deadline 已取消 continuation 时关闭迟到响应，避免连接泄漏。
            continuation.resume(response) { _ -> response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
