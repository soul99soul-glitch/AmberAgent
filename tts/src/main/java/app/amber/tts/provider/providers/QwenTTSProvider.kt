package app.amber.tts.provider.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import app.amber.tts.model.AudioChunk
import app.amber.tts.model.AudioFormat
import app.amber.tts.model.TTSRequest
import app.amber.tts.provider.TTSProvider
import app.amber.tts.provider.TTSProviderSetting
import app.amber.common.http.SseEvent
import app.amber.common.http.sseFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "QwenTTSProvider"

class QwenTTSProvider : TTSProvider<TTSProviderSetting.Qwen> {
    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                readTimeout(120, TimeUnit.SECONDS)
            }
        }
        install(SSE)
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Qwen,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("model", providerSetting.model)
            put("input", JSONObject().apply {
                put("text", request.text)
                put("voice", providerSetting.voice)
                put("language_type", providerSetting.languageType)
            })
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        httpClient.sseFlow("${providerSetting.baseUrl}/services/aigc/multimodal-generation/generation") {
            header("Authorization", "Bearer ${providerSetting.apiKey}")
            header("X-DashScope-SSE", "enable")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }.collect { event ->
            when (event) {
                is SseEvent.Open -> { /* connection opened */ }
                is SseEvent.Event -> {
                    val result = parseSSEData(event.data)
                    if (result != null) {
                        val (audioData, isLast) = result
                        emit(
                            AudioChunk(
                                data = audioData,
                                format = AudioFormat.PCM,
                                sampleRate = 24000,
                                isLast = isLast,
                                metadata = mapOf(
                                    "provider" to "qwen",
                                    "model" to providerSetting.model,
                                    "voice" to providerSetting.voice,
                                    "sampleRate" to "24000",
                                    "channels" to "1",
                                    "bitDepth" to "16"
                                )
                            )
                        )
                    }
                }
                is SseEvent.Closed -> { /* connection closed */ }
                is SseEvent.Failure -> {
                    Log.e(TAG, "Qwen TTS SSE failed", event.throwable)
                    throw event.throwable ?: Exception("Qwen TTS streaming failed")
                }
            }
        }
    }

    private fun parseSSEData(data: String): Pair<ByteArray, Boolean>? {
        return try {
            val json = JSONObject(data)
            val output = json.optJSONObject("output") ?: return null
            val audio = output.optJSONObject("audio") ?: return null
            val audioBase64 = audio.optString("data", "")
            val finishReason = output.optString("finish_reason", "")

            if (audioBase64.isNotEmpty()) {
                val audioData = Base64.decode(audioBase64, Base64.DEFAULT)
                val isLast = finishReason == "stop"
                Pair(audioData, isLast)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SSE data: $data", e)
            null
        }
    }
}
