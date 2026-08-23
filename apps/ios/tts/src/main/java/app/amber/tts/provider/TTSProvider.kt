package app.amber.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import app.amber.tts.model.AudioChunk
import app.amber.tts.model.TTSRequest

interface TTSProvider<T : TTSProviderSetting> {
    fun generateSpeech(
        context: Context,
        providerSetting: T,
        request: TTSRequest
    ): Flow<AudioChunk>
}
