package app.amber.ai.provider.openai

import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The small callback surface used by the Responses background transport.
 *
 * Callbacks are invoked serially on the transport coroutine. For each network
 * event a chunk is delivered first, followed by its checkpoint. A checkpoint
 * is still delivered when the event has no visible chunk (notably
 * `response.created`).
 */
typealias OpenAIResponsesBackgroundChunkCallback = (MessageChunk) -> Unit
typealias OpenAIResponsesBackgroundCheckpointCallback = (String, Long) -> Unit
typealias OpenAIResponsesBackgroundCompletionCallback = () -> Unit
typealias OpenAIResponsesBackgroundErrorCallback = (Throwable) -> Unit

/** A terminal Responses event, distinct from a transport interruption. */
internal class OpenAIResponsesBackgroundTerminalFailure(message: String) : Exception(message)

/**
 * Thin KMP transport for OpenAI's first-party Responses API background mode.
 *
 * It is deliberately not part of [OpenAIKmpProvider.streamText]: compatible
 * providers are not assumed to implement OpenAI background semantics. The
 * transport accepts only `https://api.openai.com/...` with
 * `useResponseApi == true` and only adds `background=true` for the explicit
 * background entry point.
 */
class OpenAIResponsesBackgroundTransport {
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { HttpClient { } }
    private val provider = OpenAIKmpProvider()

    /**
     * Starts a stored, background Responses stream and returns its cancellable
     * coroutine [Job]. Cancellation only closes the local stream; call
     * [cancelBackground] when the server response itself must be cancelled.
     */
    @Throws(Throwable::class)
    fun startBackground(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        onChunk: OpenAIResponsesBackgroundChunkCallback,
        onCheckpoint: OpenAIResponsesBackgroundCheckpointCallback,
        onComplete: OpenAIResponsesBackgroundCompletionCallback,
        onDisconnected: OpenAIResponsesBackgroundErrorCallback,
        onFailure: OpenAIResponsesBackgroundErrorCallback,
    ): Job {
        requireOfficialResponsesProvider(providerSetting)
        val requestBody = buildBackgroundRequestBody(providerSetting, messages, params)
        val url = responsesUrl(providerSetting, "responses")
        return stream(
            request = {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                configureAuth(providerSetting, params.customHeaders)
                setBody(json.encodeToString(requestBody))
            },
            url = url,
            onChunk = onChunk,
            onCheckpoint = onCheckpoint,
            onComplete = onComplete,
            onDisconnected = onDisconnected,
            onFailure = onFailure,
            initialResponseId = null,
        )
    }

    /**
     * Resumes an interrupted stream from the last atomically persisted
     * sequence number. The endpoint is exactly
     * `GET /responses/{response_id}?stream=true&starting_after=N`.
     */
    @Throws(Throwable::class)
    fun resumeBackground(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
        startingAfter: Long,
        customHeaders: List<CustomHeader> = emptyList(),
        onChunk: OpenAIResponsesBackgroundChunkCallback,
        onCheckpoint: OpenAIResponsesBackgroundCheckpointCallback,
        onComplete: OpenAIResponsesBackgroundCompletionCallback,
        onDisconnected: OpenAIResponsesBackgroundErrorCallback,
        onFailure: OpenAIResponsesBackgroundErrorCallback,
    ): Job {
        requireOfficialResponsesProvider(providerSetting)
        requireValidResponseId(responseId)
        require(startingAfter >= 0) { "startingAfter must be non-negative" }
        return stream(
            request = {
                method = HttpMethod.Get
                configureAuth(providerSetting, customHeaders)
            },
            url = resumeUrl(providerSetting, responseId, startingAfter),
            onChunk = onChunk,
            onCheckpoint = onCheckpoint,
            onComplete = onComplete,
            onDisconnected = onDisconnected,
            onFailure = onFailure,
            initialResponseId = responseId,
        )
    }

    /**
     * Explicitly cancels a server-side background response via
     * `POST /responses/{response_id}/cancel` and returns a cancellable [Job].
     */
    @Throws(Throwable::class)
    fun cancelBackground(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
        customHeaders: List<CustomHeader> = emptyList(),
        onComplete: OpenAIResponsesBackgroundCompletionCallback,
        onError: OpenAIResponsesBackgroundErrorCallback,
    ): Job {
        requireOfficialResponsesProvider(providerSetting)
        requireValidResponseId(responseId)
        return CoroutineScope(Dispatchers.Default).launch {
            try {
                val response = client.post(cancelUrl(providerSetting, responseId)) {
                    configureAuth(providerSetting, customHeaders)
                }
                if (!response.status.isSuccess()) {
                    throw Exception(
                        "OpenAI Responses cancel failed: " +
                            "${response.status.value} ${response.bodyAsText().take(1200)}"
                    )
                }
                onComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }

    private fun stream(
        url: String,
        request: HttpRequestBuilder.() -> Unit,
        onChunk: OpenAIResponsesBackgroundChunkCallback,
        onCheckpoint: OpenAIResponsesBackgroundCheckpointCallback,
        onComplete: OpenAIResponsesBackgroundCompletionCallback,
        onDisconnected: OpenAIResponsesBackgroundErrorCallback,
        onFailure: OpenAIResponsesBackgroundErrorCallback,
        initialResponseId: String?,
    ): Job = CoroutineScope(Dispatchers.Default).launch {
        try {
            val processor = OpenAIResponsesBackgroundEventProcessor(
                provider = provider,
                onChunk = onChunk,
                onCheckpoint = onCheckpoint,
                onComplete = onComplete,
                initialResponseId = initialResponseId,
            )
            client.sseFlow(url, request).collect { event ->
                when (event) {
                    is SseEvent.Open -> Unit
                    is SseEvent.Event -> processor.consume(event.data)
                    is SseEvent.Failure -> throw event.throwable ?: Exception("Stream failed")
                    is SseEvent.Closed -> processor.finish()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenAIResponsesBackgroundTerminalFailure) {
            onFailure(e)
        } catch (e: OpenAISseHttpFailure) {
            onFailure(e)
        } catch (e: Throwable) {
            onDisconnected(e)
        }
    }

    private fun HttpRequestBuilder.configureAuth(
        providerSetting: ProviderSetting.OpenAI,
        customHeaders: List<CustomHeader>,
    ) {
        header("Authorization", "Bearer ${providerSetting.apiKey}")
        customHeaders.filter { it.name.isNotBlank() }.forEach { header(it.name, it.value) }
    }

    private fun buildBackgroundRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): JsonObject {
        val base = provider.buildResponsesRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        return buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            // These values are owned by this transport. A custom body must not
            // silently turn an explicit background request back into foreground.
            put("background", true)
            put("stream", true)
            put("store", true)
        }
    }

    private fun responsesUrl(providerSetting: ProviderSetting.OpenAI, suffix: String): String =
        providerSetting.baseUrl.trimEnd('/') + "/$suffix"

    companion object {
        private val RESPONSE_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")

        internal fun requireOfficialResponsesProvider(providerSetting: ProviderSetting.OpenAI) {
            require(providerSetting.useResponseApi) {
                "Responses background transport requires useResponseApi=true"
            }
            val baseUrl = providerSetting.baseUrl.trim()
            require(baseUrl.startsWith("https://", ignoreCase = true)) {
                "Responses background transport requires HTTPS api.openai.com"
            }
            val authority = baseUrl
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
            require(authority.equals("api.openai.com", ignoreCase = true)) {
                "Responses background transport is only enabled for api.openai.com"
            }
        }

        internal fun requireValidResponseId(responseId: String) {
            require(RESPONSE_ID_PATTERN.matches(responseId)) {
                "Invalid OpenAI response id"
            }
        }

        internal fun resumeUrl(
            providerSetting: ProviderSetting.OpenAI,
            responseId: String,
            startingAfter: Long,
        ): String {
            requireValidResponseId(responseId)
            require(startingAfter >= 0) { "startingAfter must be non-negative" }
            return providerSetting.baseUrl.trimEnd('/') +
                "/responses/$responseId?stream=true&starting_after=$startingAfter"
        }

        internal fun cancelUrl(
            providerSetting: ProviderSetting.OpenAI,
            responseId: String,
        ): String {
            requireValidResponseId(responseId)
            return providerSetting.baseUrl.trimEnd('/') + "/responses/$responseId/cancel"
        }

        internal fun backgroundRequestBodyForTesting(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): JsonObject = OpenAIResponsesBackgroundTransport()
            .buildBackgroundRequestBody(providerSetting, messages, params)
    }
}

/**
 * Serial event reducer shared by initial and resumed streams. Keeping this
 * separate makes the checkpoint ordering independently testable without a
 * live network engine.
 */
internal class OpenAIResponsesBackgroundEventProcessor(
    private val provider: OpenAIKmpProvider,
    private val onChunk: OpenAIResponsesBackgroundChunkCallback,
    private val onCheckpoint: OpenAIResponsesBackgroundCheckpointCallback,
    private val onComplete: OpenAIResponsesBackgroundCompletionCallback,
    initialResponseId: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val isResumedStream = initialResponseId != null
    private var responseId: String? = initialResponseId
    private var terminalObserved = false
    private var completionDelivered = false

    fun consume(data: String) {
        normalizePayloads(data).forEach { payload ->
            if (payload == "[DONE]") {
                // A resumed stream may begin after the terminal event was
                // persisted. In that case `[DONE]` alone leaves the prior
                // response state unknown (for example, a crash between a
                // failure checkpoint and its terminal state). Only accept it
                // as completion for an initial stream, or after this resumed
                // stream has observed an explicit terminal event.
                if (!isResumedStream || terminalObserved) {
                    terminalObserved = true
                    deliverCompletion()
                }
                return@forEach
            }

            val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                ?: return@forEach
            val type = obj.string("type")
            obj.responseId()?.let { responseId = it }
            val sequenceNumber = obj.long("sequence_number")
            if (type == "response.created" && sequenceNumber == null) {
                throw IllegalStateException("OpenAI Responses response.created is missing sequence_number")
            }

            // Parse first so all visible chunks for this event are delivered in
            // order. If parsing raises for a terminal error, the checkpoint is
            // still emitted below before the error reaches onError.
            val parsed = runCatching { provider.parseResponsesStreamData(payload) }
            parsed.getOrNull().orEmpty()
                .filter { it.hasCallbackPayload() }
                .forEach(onChunk)

            sequenceNumber?.let { sequence ->
                val id = responseId ?: throw IllegalStateException(
                    "OpenAI Responses checkpoint is missing response id"
                )
                onCheckpoint(id, sequence)
            }

            parsed.exceptionOrNull()?.let { error ->
                if (type == "response.failed" || type == "response.incomplete") {
                    throw OpenAIResponsesBackgroundTerminalFailure(
                        error.message ?: "OpenAI Responses ended with a terminal failure"
                    )
                }
                throw error
            }

            if (type == "response.completed" || type == "response.incomplete") {
                terminalObserved = true
                deliverCompletion()
            }
        }
    }

    fun finish() {
        if (!terminalObserved) {
            throw IllegalStateException("OpenAI Responses background stream ended before a terminal event")
        }
        deliverCompletion()
    }

    private fun deliverCompletion() {
        if (completionDelivered) return
        completionDelivered = true
        onComplete()
    }

    private fun normalizePayloads(data: String): List<String> = data.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { value ->
            var payload = value
            while (payload.startsWith("data:")) payload = payload.removePrefix("data:").trimStart()
            payload
        }
        .filter { it.isNotBlank() }
        .toList()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        string(key)?.toLongOrNull()

    private fun JsonObject.responseId(): String? =
        string("response_id") ?: (this["response"] as? JsonObject)?.string("id")

    private fun MessageChunk.hasCallbackPayload(): Boolean =
        usage != null || choices.any { choice ->
            choice.finishReason != null ||
                choice.delta?.parts?.isNotEmpty() == true ||
                choice.message?.parts?.isNotEmpty() == true
        }
}
