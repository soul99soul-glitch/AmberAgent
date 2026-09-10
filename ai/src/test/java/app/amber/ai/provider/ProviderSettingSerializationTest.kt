package app.amber.ai.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderSettingSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `antigravity auth mode uses stable wire value`() {
        val encoded = json.encodeToString(GoogleAuthMode.serializer(), GoogleAuthMode.ANTIGRAVITY_OAUTH)
        assertEquals("\"antigravity_oauth\"", encoded)
        assertEquals(GoogleAuthMode.ANTIGRAVITY_OAUTH, json.decodeFromString(GoogleAuthMode.serializer(), encoded))
    }

    @Test fun `old api key data remains api key when auth mode is absent`() {
        val old = "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"enabled\":true,\"name\":\"Google\",\"models\":[],\"balanceOption\":{}}"
        val decoded = json.decodeFromString<ProviderSetting.Google>(old)
        assertEquals(GoogleAuthMode.API_KEY, decoded.authMode)
    }
}
