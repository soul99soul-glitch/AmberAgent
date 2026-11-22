package app.amber.ai.provider.providers.openai

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.ResponsesResumeRequest
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.util.KeyRoulette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class ResponseAPI(
    client: OkHttpClient,
    keyRoulette: KeyRoulette = KeyRoulette.default(),
    bearerResolver: suspend (ProviderSetting.OpenAI, Boolean) -> String = { providerSetting, _ ->
        keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
    },
    /**
     * SSE transport seam (P6-01 testability): the default wires OkHttp's
     * EventSources; tests inject a scripted fake transport.
     */
    transport: (Request, EventSourceListener) -> EventSource = { request, listener ->
        EventSources.createFactory(client).newEventSource(request, listener)
    },
) : OpenAIImpl, StoredResponseApi {
    private val eventTransport = ResponseEventTransport(client, bearerResolver, transport)

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        // P6-01: same gate as streamText — strict endpoint match AND the
        // user switch (defense-in-depth: off never sends store=true).
        val resume = params.responsesResume?.takeIf {
            providerSetting.supportsResponsesResume() && providerSetting.enableResponsesResume
        }
        if (resume?.resumeFrom != null) {
            return streamText(providerSetting, messages, params).last()
        }
        return eventTransport.generateText(providerSetting, messages, params, resume)
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> {
        // ResponseStreamReconciler 解决两类问题 (详见该类注释):
        //   1) id 标准化: 所有 ASSISTANT chunk 的 id 统一为流级 sharedId, 防止
        //      ChatService merge by-id 失败产生 orphan node
        //   2) final message 调和: done/completed 的 message=...,delta=null 不再
        //      无条件转空 delta (会丢 final-only 的 refusal/annotations/tool args),
        //      而是与流内累积做 diff, 缺失部分降级为增量 delta, 一致部分只保留
        //      usage/annotations/finishReason; 同时仍阻止 replaceActive 重置累积
        val reconciler = ResponseStreamReconciler()
        if (providerSetting.authMode == OpenAIAuthMode.CODEX_OAUTH ||
            providerSetting.authMode == OpenAIAuthMode.CHATGPT_SIWC
        ) {
            return eventTransport.streamCodexText(providerSetting, messages, params)
                .map { chunk -> reconciler.reconcile(chunk) }
        }

        // P6-01: resume is honored only when every gate passed — the app layer
        // set params.responsesResume under the capability flag + user toggle,
        // and this provider must still strictly match the official endpoint
        // (custom endpoints / Codex OAuth / third-party providers never
        // qualify, so they keep today's store=false behavior). The user switch
        // is re-checked here as defense-in-depth: off never sends store=true,
        // even if a stale resume request reaches the wire.
        val resume = params.responsesResume?.takeIf {
            providerSetting.supportsResponsesResume() && providerSetting.enableResponsesResume
        }
        resume?.resumeFrom?.let { cursor ->
            require(cursor.providerId == providerSetting.id.toString()) {
                "Stored response belongs to a different provider"
            }
            // After process death the cursor can be ahead of the durable
            // partial. Replay the original response in full and replace that
            // partial, rather than append a suffix or create a new response.
            val partial = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
            val accumulator = MessageStreamAccumulator(listOf(UIMessage.assistant("")), params.model)
            return streamStored(providerSetting, cursor.responseId, null, resume.store, resume.runId)
                .map { chunk ->
                    accumulator.append(chunk)
                    val recovered = accumulator.snapshot().last()
                    chunk.copy(
                        choices = listOf(UIMessageChoice(
                            index = 0,
                            delta = null,
                            message = recovered.copy(id = partial?.id ?: recovered.id),
                            finishReason = chunk.choices.firstOrNull()?.finishReason,
                        )),
                        usage = null, // the complete snapshot already carries accumulated usage
                    )
                }
        }
        return eventTransport.streamText(providerSetting, messages, params, resume, reconciler)
    }

    override suspend fun fetchStatus(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
    ): StoredResponseStatus = eventTransport.fetchStatus(providerSetting, responseId)

    override suspend fun streamStored(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
        cursor: ResponseCursor?,
        store: ResponseResumeStore,
        runId: String,
    ): Flow<MessageChunk> = eventTransport.streamStored(providerSetting, responseId, cursor, store, runId)

    override suspend fun cancel(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
    ): StoredResponseCancelResult = eventTransport.cancel(providerSetting, responseId)

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
        resume: ResponsesResumeRequest? = null,
    ): JsonObject = ResponseRequestEncoder.buildRequestBody(providerSetting, messages, params, stream, resume)

    internal fun buildMessages(messages: List<UIMessage>) = ResponseRequestEncoder.buildMessages(messages)

    internal fun parseResponseDelta(jsonObject: JsonObject): MessageChunk? =
        ResponseEventDecoder.parseResponseDelta(jsonObject)

    internal fun parseResponseOutput(jsonObject: JsonObject): MessageChunk =
        ResponseEventDecoder.parseResponseOutput(jsonObject)
}

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true,
    /** P6-01: provider declares background/store/stream semantics for stored responses. */
    val supportsStoredResponses: Boolean = false,
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        // P6-01: official api.openai.com only. Custom endpoints, Codex OAuth
        // backend (chatgpt.com) and third-party OpenAI-compatible hosts never
        // declare stored-response support — static match, no runtime probe.
        "api.openai.com" -> ResponseProviderCapabilities(
            supportsStoredResponses = true,
        )

        else -> ResponseProviderCapabilities()
    }
}

/**
 * P6-01 strict eligibility (plan §P6-01 "严格适用范围"): official
 * https://api.openai.com + API key auth + the Responses API selected.
 * OpenAI-compatible custom endpoints, the Codex OAuth path and third-party
 * providers never qualify.
 */
fun ProviderSetting.OpenAI.supportsResponsesResume(): Boolean =
    useResponseApi &&
        authMode == OpenAIAuthMode.API_KEY &&
        resolveResponseProviderCapabilities(baseUrl.toHttpUrl().host).supportsStoredResponses
