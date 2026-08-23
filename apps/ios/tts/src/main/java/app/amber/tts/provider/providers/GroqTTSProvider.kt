package app.amber.tts.provider.providers

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import app.amber.tts.model.AudioChunk
import app.amber.tts.model.AudioFormat
import app.amber.tts.model.TTSRequest
import app.amber.tts.provider.TTSProvider
import app.amber.tts.provider.TTSProviderSetting
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "GroqTTSProvider"

class GroqTTSProvider : TTSProvider<TTSProviderSetting.Groq> {
    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                readTimeout(120, TimeUnit.SECONDS)
            }
        }
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Groq,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("model", providerSetting.model)
            put("input", request.text)
            put("voice", providerSetting.voice)
            put("response_format", "wav")
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val response = httpClient.post("${providerSetting.baseUrl}/audio/speech") {
            header("Authorization", "Bearer ${providerSetting.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            Log.e(TAG, "generateSpeech: ${response.status.value} ${response.status.description}")
            Log.e(TAG, "generateSpeech: $errorBody")
            throw Exception("Groq TTS request failed: ${response.status.value} ${response.status.description}")
        }

        val audioData = response.bodyAsChannel().toInputStream().readBytes()

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.WAV,
                isLast = true,
                metadata = mapOf(
                    "provider" to "groq",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voice,
                    "response_format" to "wav"
                )
            )
        )
    }
}
