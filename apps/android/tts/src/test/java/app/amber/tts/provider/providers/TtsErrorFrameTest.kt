package app.amber.tts.provider.providers

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TtsErrorFrameTest {

    @Test
    fun `MiniMax business error frame is propagated`() {
        try {
            decodeMiniMaxAudioFrame(
                """{"base_resp":{"status_code":1008,"status_msg":"invalid api key"}}"""
            )
            fail("Expected MiniMax error")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("1008"))
            assertTrue(error.message.orEmpty().contains("invalid api key"))
        }
    }

    @Test
    fun `Qwen business error frame is propagated`() {
        try {
            parseQwenSseData(
                """{"code":"InvalidApiKey","message":"invalid api key","request_id":"r1"}"""
            )
            fail("Expected Qwen error")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("InvalidApiKey"))
            assertTrue(error.message.orEmpty().contains("invalid api key"))
        }
    }
}
