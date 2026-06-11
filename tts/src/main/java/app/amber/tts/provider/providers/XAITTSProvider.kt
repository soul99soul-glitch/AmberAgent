package app.amber.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

private const val TAG = "XAITTSProvider"

class XAITTSProvider : TTSProvider<TTSProviderSetting.XAI> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.XAI,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("text", request.text)
            put("voice_id", providerSetting.voiceId)
            put("language", providerSetting.language)
        }

        Log.i(TAG, "generateSpeech: provider=xai textChars=${request.text.length}")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/tts")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            Log.e(TAG, "generateSpeech: ${response.code} ${response.message}")
            throw Exception("xAI TTS request failed: ${response.code} ${response.message}")
        }

        val audioData = response.body.bytes()

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                isLast = true,
                metadata = mapOf(
                    "provider" to "xai",
                    "voice_id" to providerSetting.voiceId,
                    "language" to providerSetting.language
                )
            )
        )
    }
}
