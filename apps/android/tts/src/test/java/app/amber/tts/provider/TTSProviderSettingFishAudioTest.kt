package app.amber.tts.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderSettingFishAudioTest {
    @Test
    fun fishAudio_defaults_are_expected() {
        val setting = TTSProviderSetting.FishAudio()

        assertEquals("Fish Audio TTS", setting.name)
        assertEquals("https://api.fish.audio", setting.baseUrl)
        assertEquals("s2.1-pro", setting.model)
        assertEquals("", setting.referenceId)
        assertEquals(0.7f, setting.temperature)
        assertEquals(1.0f, setting.speed)
        assertEquals("mp3", setting.format)
        assertEquals(0.7f, setting.topP)
        assertEquals(300, setting.chunkLength)
        assertEquals(true, setting.normalize)
        assertEquals("normal", setting.latency)
        assertEquals("", setting.apiKey)
    }

    @Test
    fun fishAudio_is_registered_in_provider_types() {
        assertTrue(TTSProviderSetting.Types.contains(TTSProviderSetting.FishAudio::class))
    }
}
