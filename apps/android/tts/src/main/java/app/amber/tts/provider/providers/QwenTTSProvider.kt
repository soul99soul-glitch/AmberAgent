package app.amber.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.tts.model.AudioChunk
import app.amber.tts.model.AudioFormat
import app.amber.tts.model.TTSRequest
import app.amber.tts.provider.TTSProvider
import app.amber.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "QwenTTSProvider"

class QwenTTSProvider : TTSProvider<TTSProviderSetting.Qwen> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

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

        Log.i(TAG, "generateSpeech: provider=qwen model=${providerSetting.model} textChars=${request.text.length}")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/services/aigc/multimodal-generation/generation")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-DashScope-SSE", "enable")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(httpRequest).awaitAndUseCancellable { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Qwen TTS request failed: ${response.code} ${response.message}")
                throw Exception("Qwen TTS request failed: ${response.code} ${response.message}")
            }
            val reader = response.body.byteStream().bufferedReader()
            var currentData = StringBuilder()

            reader.lineSequence().forEach { line ->
                when {
                    line.startsWith("data:") -> {
                        currentData.append(line.removePrefix("data:"))
                    }

                    line.isEmpty() && currentData.isNotEmpty() -> {
                        val result = parseQwenSseData(currentData.toString())
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
                        currentData = StringBuilder()
                    }
                }
            }
        }
    }

}

internal fun parseQwenSseData(data: String): Pair<ByteArray, Boolean>? {
    val json = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
    val code = json["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (code.isNotBlank()) {
        val message = json["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
        throw IllegalStateException("Qwen TTS error $code: $message")
    }
    val output = json["output"]?.jsonObject ?: return null
    val audio = output["audio"]?.jsonObject ?: return null
    val audioBase64 = audio["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val finishReason = output["finish_reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (audioBase64.isEmpty()) return null
    return java.util.Base64.getDecoder().decode(audioBase64) to (finishReason == "stop")
}
