package app.amber.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.common.http.SseEvent
import app.amber.common.http.sseFlow
import app.amber.tts.model.AudioChunk
import app.amber.tts.model.AudioFormat
import app.amber.tts.model.TTSRequest
import app.amber.tts.provider.TTSProvider
import app.amber.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "MiniMaxTTSProvider"
private val miniMaxJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
private data class MiniMaxResponseData(
    val audio: String = "",
    val status: Int = 0,
    val ced: String = "",
)

@Serializable
private data class MiniMaxResponse(
    val data: MiniMaxResponseData? = null,
    @SerialName("base_resp") val baseResponse: MiniMaxBaseResponse? = null,
)

@Serializable
private data class MiniMaxBaseResponse(
    @SerialName("status_code") val statusCode: Int = 0,
    @SerialName("status_msg") val statusMessage: String = "",
)

internal data class MiniMaxAudioFrame(
    val bytes: ByteArray,
    val status: Int,
    val ced: String,
)

internal fun decodeMiniMaxAudioFrame(payload: String): MiniMaxAudioFrame? {
    // SSE keep-alives/non-data frames are not provider failures. Preserve the
    // old skip behavior for malformed frames, but surface a decoded business error.
    val response = runCatching { miniMaxJson.decodeFromString<MiniMaxResponse>(payload) }
        .getOrNull() ?: return null
    response.baseResponse?.takeIf { it.statusCode != 0 }?.let { error ->
        throw IllegalStateException("MiniMax TTS error ${error.statusCode}: ${error.statusMessage}")
    }
    val data = response.data ?: return null
    if (data.audio.isBlank()) return null
    return MiniMaxAudioFrame(
        bytes = hexStringToBytes(data.audio),
        status = data.status,
        ced = data.ced,
    )
}

class MiniMaxTTSProvider : TTSProvider<TTSProviderSetting.MiniMax> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiniMax,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = buildJsonObject {
            put("model", providerSetting.model)
            put("text", request.text)
            put("stream", true)
            put("output_format", "hex")
            put("stream_options", buildJsonObject {
                put("exclude_aggregated_audio", true)
            })
            put("voice_setting", buildJsonObject {
                put("voice_id", providerSetting.voiceId)
                put("emotion", providerSetting.emotion)
                put("speed", providerSetting.speed)
            })
        }

        Log.i(TAG, "generateSpeech: provider=minimax model=${providerSetting.model} textChars=${request.text.length}")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/t2a_v2")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(miniMaxJson.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        var hasEmittedAudio = false

        httpClient.sseFlow(httpRequest).collect {
            when (it) {
                is SseEvent.Open -> Log.i(TAG, "SSE connection opened")
                is SseEvent.Event -> {
                    val frame = decodeMiniMaxAudioFrame(it.data) ?: return@collect
                    emit(
                        AudioChunk(
                            data = frame.bytes,
                            format = AudioFormat.MP3,
                            sampleRate = 32000,
                            isLast = false,
                            metadata = mapOf(
                                "provider" to "minimax",
                                "model" to providerSetting.model,
                                "voice" to providerSetting.voiceId,
                                "status" to frame.status.toString(),
                                "ced" to frame.ced,
                            )
                        )
                    )
                    hasEmittedAudio = true
                }

                is SseEvent.Closed -> {
                    Log.i(TAG, "SSE connection closed")
                    // Emit final chunk if we haven't already
                    if (hasEmittedAudio) {
                        emit(
                            AudioChunk(
                                data = byteArrayOf(), // Empty data for last chunk
                                format = AudioFormat.MP3,
                                sampleRate = 32000,
                                isLast = true,
                                metadata = mapOf("provider" to "minimax")
                            )
                        )
                    }
                }

                is SseEvent.Failure -> {
                    Log.e(TAG, "SSE connection failed", it.throwable)
                    throw it.throwable ?: Exception("MiniMax TTS streaming failed")
                }
            }
        }
    }
}

private fun hexStringToBytes(hexString: String): ByteArray {
    val cleanHex = hexString.replace("\\s+".toRegex(), "")
    val length = cleanHex.length

    // Check for even number of characters
    if (length % 2 != 0) {
        throw IllegalArgumentException("Hex string must have even number of characters")
    }

    val bytes = ByteArray(length / 2)
    for (i in 0 until length step 2) {
        val hexByte = cleanHex.substring(i, i + 2)
        bytes[i / 2] = hexByte.toInt(16).toByte()
    }
    return bytes
}
