package app.amber.ai.provider.providers.openai

import android.util.Log
import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.ResponsesResumeRequest
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.StreamProtocol
import app.amber.ai.provider.providers.StreamTerminationGuard
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.util.configureReferHeaders
import app.amber.ai.util.configureOpenCodeSessionHeader
import app.amber.ai.util.json
import app.amber.ai.util.parseErrorDetail
import app.amber.ai.util.stringSafe
import app.amber.ai.util.toHeaders
import app.amber.common.http.await
import app.amber.common.http.jsonArrayOrNull
import app.amber.common.http.jsonObjectOrNull
import app.amber.common.http.jsonPrimitiveOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import java.io.IOException

internal class ResponseEventTransport(
    private val client: OkHttpClient,
    private val bearerResolver: suspend (ProviderSetting.OpenAI, Boolean) -> String,
    private val transport: (Request, EventSourceListener) -> EventSource,
) {
    suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        resume: ResponsesResumeRequest?,
    ): MessageChunk {
        val requestBody = ResponseRequestEncoder.buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
            resume = resume,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .configureOpenCodeSessionHeader(providerSetting.baseUrl, params)
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${bearerResolver(providerSetting, false)}"
            )
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: model=${params.model.modelId}")

        val bodyStr = withCancellableCall {
            var response = awaitResponse(client.newCall(request))
            if (response.code == 401) {
                response.close()
                val retryRequest = request.newBuilder()
                    .header("Authorization", "Bearer ${bearerResolver(providerSetting, true)}")
                    .build()
                response = awaitResponse(client.newCall(retryRequest))
            }
            response.use {
                if (!it.isSuccessful) {
                    throw Exception("Failed to get response: ${it.code} ${it.body.string()}")
                }
                it.body?.string().orEmpty()
            }
        }
        Log.i(TAG, "generateText: response ${bodyStr.length} chars")
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val output = ResponseEventDecoder.parseResponseOutput(bodyJson)

        return output
    }

    suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        resume: ResponsesResumeRequest?,
        reconciler: ResponseStreamReconciler,
    ): Flow<MessageChunk> {
        val requestBody = ResponseRequestEncoder.buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
            resume = resume,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .configureOpenCodeSessionHeader(providerSetting.baseUrl, params)
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${bearerResolver(providerSetting, false)}"
            )
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: model=${params.model.modelId} resume=${resume != null}")

        val raw = storedEventStream(
            providerSetting = providerSetting,
            request = request,
            reconciler = reconciler,
            reconnectAllowed = resume != null,
            resume = resume,
            customHeaders = params.customHeaders,
        )
        return persistDrain(
            events = raw,
            resume = resume,
            providerId = providerSetting.id.toString(),
        )
    }

    fun streamCodexText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        val requestBody = ResponseRequestEncoder.buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${bearerResolver(providerSetting, false)}")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamCodexText: model=${params.model.modelId}")

        withCancellableCall {
            var response = awaitResponse(client.newCall(request))
            if (response.code == 401) {
                response.close()
                val retryRequest = request.newBuilder()
                    .header("Authorization", "Bearer ${bearerResolver(providerSetting, true)}")
                    .build()
                response = awaitResponse(client.newCall(retryRequest))
            }
            if (!response.isSuccessful) {
                val bodyText = response.body.stringSafe().orEmpty()
                val parsedSubscriptionError = runCatching {
                    val element = Json.parseToJsonElement(bodyText)
                    if (isSubscriptionSharingErrorCode(element)) parseOpenAIResponseError(element) else null
                }.getOrNull()
                if (parsedSubscriptionError != null) throw parsedSubscriptionError
                throw Exception("Failed to get response: ${response.code} $bodyText")
            }

            response.use { activeResponse ->
                val source = activeResponse.body.source()
                val terminationGuard = StreamTerminationGuard(StreamProtocol.OPENAI_RESPONSES)
                var eventType: String? = null
                val dataLines = mutableListOf<String>()

                fun drainEvent(): MessageChunk? {
                    if (dataLines.isEmpty()) return null
                    val data = dataLines.joinToString("\n")
                    dataLines.clear()
                    terminationGuard.observe(eventType, data)
                    if (data == "[DONE]") {
                        terminationGuard.cleanEofCause()?.let { throw it }
                        return null
                    }
                    val eventJson = Json.parseToJsonElement(data).jsonObjectOrNull ?: return null
                    return ResponseEventDecoder.parseResponseDelta(eventJson)
                }

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            drainEvent()?.let { emit(it) }
                            if (eventType == "response.completed" || eventType == "response.incomplete") {
                                return@use
                            }
                            eventType = null
                        }

                        line.startsWith("event:") -> {
                            eventType = line.removePrefix("event:").trim()
                        }

                        line.startsWith("data:") -> {
                            dataLines += line.removePrefix("data:").trimStart()
                        }
                    }
                }
                drainEvent()?.let { emit(it) }
                terminationGuard.cleanEofCause()?.let { throw it }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ---- P6-01: stored response stream (store=true + resume) ----

    /**
     * One queued event flowing from the SSE listener to the flow body.
     * [Disconnected] is emitted instead of an error when a stored stream
     * drops mid-flight and a reconnect is allowed.
     */
    private sealed interface StoredQueuedChunk {
        data class Data(
            val sequence: Long,
            val responseId: String?,
            val chunk: MessageChunk?,
            val terminal: Boolean = false,
        ) : StoredQueuedChunk

        data class Disconnected(val cause: Throwable) : StoredQueuedChunk
    }

    /**
     * SSE stream over [transport] that parses Responses events into
     * [StoredQueuedChunk]. P6-01 semantics:
     *  - [cursorFloor]: events at or below the floor were already delivered
     *    (reconnect / recovery replay) and are skipped (dedup);
     *  - when [reconnectAllowed] and a stored response id is known, a
     *    mid-stream network failure reconnects ONCE via
     *    GET /responses/{id} from the persisted cursor — the replayed events
     *    continue through the same flow;
     *  - a second failure (or a failure on the initial stream without a
     *    stored id) fails the flow exactly like the pre-P6-01 behavior.
     */
    private fun storedEventStream(
        providerSetting: ProviderSetting.OpenAI,
        request: Request,
        reconciler: ResponseStreamReconciler,
        reconnectAllowed: Boolean,
        resume: ResponsesResumeRequest?,
        customHeaders: List<CustomHeader>,
        cursorFloor: Long? = null,
        knownResponseId: String? = null,
        standaloneSuffixReplay: Boolean = false,
    ): Flow<StoredQueuedChunk> = flow {
        val events = Channel<StoredQueuedChunk>(Channel.UNLIMITED)
        val terminationGuard = StreamTerminationGuard(StreamProtocol.OPENAI_RESPONSES)
        var currentResponseId: String? = knownResponseId
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                terminationGuard.observe(type, data)
                val payloads = normalizeOpenAIStreamDataLines(data)
                if (payloads.isEmpty() && data.contains("[DONE]")) {
                    events.close(terminationGuard.cleanEofCause())
                    return
                }
                Log.d(TAG, "onEvent: id=$id type=$type chars=${data.length}")
                // parse 异常/error payload 必须主动 cancel + close, 不依赖在 OkHttp
                // callback 中抛异常的隐式传播
                runCatching {
                    for (payload in payloads) {
                        val json = json.parseToJsonElement(payload).jsonObject
                        if (json["error"] != null) {
                            throw parseOpenAIResponseError(json["error"]!!)
                        }
                        val sequence = json["sequence_number"]?.jsonPrimitive?.longOrNull ?: -1L
                        if (json["type"]?.jsonPrimitive?.contentOrNull == "response.created") {
                            currentResponseId = json["response"]?.jsonObjectOrNull
                                ?.get("id")?.jsonPrimitiveOrNull?.contentOrNull
                            // Save the server identity through the ordered drain even
                            // when generation disconnects before its first output.
                            events.trySend(StoredQueuedChunk.Data(sequence, currentResponseId, chunk = null))
                            continue
                        }
                        val chunk = ResponseEventDecoder.parseResponseDelta(json)
                        if (chunk != null) {
                            val terminal = type == "response.completed" || type == "response.incomplete"
                            // 无限容量 channel: trySend 永不阻塞, 不会静默丢 token
                            events.trySend(
                                StoredQueuedChunk.Data(
                                    sequence = sequence,
                                    responseId = currentResponseId,
                                    chunk = chunk,
                                    terminal = terminal,
                                )
                            )
                            if (terminal) {
                                events.close()
                                return
                            }
                        }
                    }
                }.onFailure { error ->
                    eventSource.cancel()
                    events.close(error)
                    return
                }
                // completed/incomplete 是终止事件; failed 在 parse 时抛错走上面的 close(error)
                if (type == "response.completed" || type == "response.incomplete") {
                    events.close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                Log.w(TAG, "onFailure: status=${response?.code} type=${t?.javaClass?.simpleName}", t)

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(
                            normalizeOpenAIStreamDataLines(bodyRaw).firstOrNull() ?: bodyRaw
                        )
                        exception = parseOpenAIResponseError(bodyElement)
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse response body chars=${bodyRaw?.length ?: 0}", e)
                }
                if (reconnectAllowed && currentResponseId != null && isReconnectable(t, response)) {
                    // P6-01: 断线先重连 — the flow body reconnects to the
                    // stored response from the cursor.
                    Log.w(TAG, "onFailure: stored response $currentResponseId; reconnecting", exception)
                    events.trySend(
                        StoredQueuedChunk.Disconnected(
                            exception ?: Exception("HTTP ${response?.code ?: "unknown"}")
                        )
                    )
                } else {
                    // 非 2xx 且 body 为空/非 JSON 时 t 为 null；必须合成异常,
                    // 否则 close(null) 会让 flow 以零 chunk "正常完成", 上层把失败当成功
                    events.close(exception ?: Exception("HTTP ${response?.code ?: "unknown"}"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                events.close(terminationGuard.cleanEofCause())
            }
        }

        val eventSource = transport(request, listener)
        try {
            for (event in events) {
                when (event) {
                    is StoredQueuedChunk.Data -> {
                        if (cursorFloor == null || event.sequence > cursorFloor) {
                            // A replayed event must not mutate reconciliation state
                            // before its sequence has passed deduplication.
                            val chunk = when {
                                !standaloneSuffixReplay -> event.chunk?.let(reconciler::reconcile)
                                event.terminal -> event.chunk
                                // This fresh reconciler has no pre-cursor prefix.
                                // Item snapshots cannot be diffed against its suffix,
                                // or treated as a complete response. Keep the actual
                                // deltas until the full terminal snapshot replaces them.
                                event.chunk?.choices?.any { it.delta == null && it.message != null } == true -> null
                                else -> event.chunk?.let(reconciler::reconcile)
                            }
                            emit(event.copy(chunk = chunk))
                        }
                    }

                    is StoredQueuedChunk.Disconnected -> {
                        if (resume == null) throw event.cause
                        val cursor = resume.store.load(resume.runId) ?: throw event.cause
                        val replay = storedEventStream(
                            providerSetting = providerSetting,
                            request = storedRequest(providerSetting, cursor.responseId, customHeaders, cursor.sequence),
                            reconciler = reconciler,
                            reconnectAllowed = false,
                            resume = resume,
                            customHeaders = customHeaders,
                            cursorFloor = cursor.sequence,
                            knownResponseId = cursor.responseId,
                        )
                        replay.collect { emit(it) }
                        return@flow // the reconnect stream ended (terminal or failure)
                    }
                }
            }
        } finally {
            eventSource.cancel()
        }
    }

    /**
     * Persist-then-emit drain with P6-01 write-ahead semantics: the sequence
     * is persisted BEFORE the consumer sees the event, so a reconnect can
     * never re-deliver it. The owning chat flow clears the cursor only after
     * its final conversation checkpoint and terminal state are durable. Dedup
     * by cursor floor happens inside [storedEventStream].
     */
    private fun persistDrain(
        events: Flow<StoredQueuedChunk>,
        resume: ResponsesResumeRequest?,
        providerId: String,
    ): Flow<MessageChunk> = events.transform { event ->
        if (event !is StoredQueuedChunk.Data) return@transform
        val responseId = event.responseId
        if (resume != null && responseId != null && event.sequence >= 0) {
            resume.store.save(resume.runId, responseId, event.sequence, providerId)
        }
        event.chunk?.let { emit(it) }
    }

    private suspend fun storedRequest(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
        customHeaders: List<CustomHeader>,
        startingAfter: Long? = null,
    ): Request = Request.Builder()
        .url("${providerSetting.baseUrl}/responses/$responseId".toHttpUrl().newBuilder()
            .addQueryParameter("stream", "true")
            .apply { startingAfter?.let { addQueryParameter("starting_after", it.toString()) } }
            .build())
        .headers(customHeaders.toHeaders())
        .addHeader("Authorization", "Bearer ${bearerResolver(providerSetting, false)}")
        .addHeader("Accept", "text/event-stream")
        .configureReferHeaders(providerSetting.baseUrl)
        .get()
        .build()

    /** Network-level failures only — HTTP errors (4xx body parsed) stay failures. */
    private fun isReconnectable(t: Throwable?, response: Response?): Boolean =
        t is IOException || (t == null && response != null && response.code >= 500)

    // ---- P6-01: StoredResponseApi ----

    suspend fun fetchStatus(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
    ): StoredResponseStatus {
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses/$responseId")
            .addHeader("Authorization", "Bearer ${bearerResolver(providerSetting, false)}")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Failed to fetch stored response: ${response.code}")
        }
        val body = response.body?.string() ?: ""
        val bodyJson = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val status = bodyJson?.get("status")?.jsonPrimitiveOrNull?.contentOrNull
        val finalMessage = if (status.toStoredResponseState() == StoredResponseState.COMPLETED) {
            // A completed GET normally contains the complete `output`. Keep
            // an empty array as a valid empty response, while a missing field
            // falls back to the stored SSE replay in recovery.
            bodyJson
                ?.takeIf { it["output"]?.jsonArrayOrNull != null }
                ?.let { runCatching { ResponseEventDecoder.parseResponseOutput(it) }.getOrNull() }
        } else {
            null
        }
        return StoredResponseStatus(status.toStoredResponseState(), responseId, finalMessage)
    }

    suspend fun streamStored(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
        cursor: ResponseCursor?,
        store: ResponseResumeStore,
        runId: String,
    ): Flow<MessageChunk> {
        val resume = ResponsesResumeRequest(runId = runId, store = store)
        val raw = storedEventStream(
            providerSetting = providerSetting,
            request = storedRequest(providerSetting, responseId, emptyList(), cursor?.sequence),
            reconciler = ResponseStreamReconciler(),
            reconnectAllowed = false,
            resume = resume,
            customHeaders = emptyList(),
            cursorFloor = cursor?.sequence,
            knownResponseId = responseId,
            standaloneSuffixReplay = cursor != null,
        )
        return persistDrain(
            events = raw,
            resume = resume,
            providerId = providerSetting.id.toString(),
        )
    }

    suspend fun cancel(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
    ): StoredResponseCancelResult = try {
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses/$responseId/cancel")
            .addHeader("Authorization", "Bearer ${bearerResolver(providerSetting, false)}")
            .post("".toRequestBody(null))
            .build()
        val response = client.newCall(request).await()
        response.use {
            if (it.isSuccessful) StoredResponseCancelResult.CancelledDecided
            else StoredResponseCancelResult.CancelFailed
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "cancel: failed for $responseId", e)
        StoredResponseCancelResult.CancelFailed
    }

    private companion object { const val TAG = "ResponseAPI" }
}

private fun String?.toStoredResponseState(): StoredResponseState = when (this) {
    "completed", "incomplete" -> StoredResponseState.COMPLETED
    "cancelled" -> StoredResponseState.CANCELLED
    "failed" -> StoredResponseState.FAILED
    else -> StoredResponseState.IN_PROGRESS
}
