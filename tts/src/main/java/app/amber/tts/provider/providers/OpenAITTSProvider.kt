package app.amber.tts.provider.providers

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
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

private const val TAG = "OpenAITTSProvider"

class OpenAITTSProvider : TTSProvider<TTSProviderSetting.OpenAI> {
    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                readTimeout(120, TimeUnit.SECONDS)
            }
        }
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.OpenAI,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("model", providerSetting.model)
            put("input", request.text)
            put("voice", providerSetting.voice)
            put("response_format", "mp3") // Default to MP3
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val response = httpClient.post("${providerSetting.baseUrl}/audio/speech") {
            header("Authorization", "Bearer ${providerSetting.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        if (!response.status.isSuccess()) {
            throw Exception("TTS request failed: ${response.status.value} ${response.status.description}")
        }

        val audioData = response.bodyAsChannel().toInputStream().readBytes()

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                isLast = true,
                metadata = mapOf(
                    "provider" to "openai",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voice
                )
            )
        )
    }
}
