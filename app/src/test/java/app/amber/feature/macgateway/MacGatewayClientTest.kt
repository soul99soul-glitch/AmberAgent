package app.amber.feature.macgateway

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacGatewayClientTest {
    private fun encode(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private val validJson =
        """{"v":1,"id":"gw","name":"Studio","addrs":["studio.local","192.168.1.5"],"port":47821,"fp":"abc=","s":"secret"}"""

    @Test
    fun parsesTheFullLinkAndTheBareValue() {
        val encoded = encode(validJson)
        val fromLink = MacGatewayPairingPayload.parse("amber://gateway/pair?p=$encoded")
        assertEquals(listOf("studio.local", "192.168.1.5"), fromLink?.addrs)
        assertEquals("secret", fromLink?.s)
        assertEquals(fromLink, MacGatewayPairingPayload.parse("  $encoded\n"))
    }

    @Test
    fun rejectsGarbageAndUnknownVersions() {
        assertNull(MacGatewayPairingPayload.parse(""))
        assertNull(MacGatewayPairingPayload.parse("amber://gateway/pair?p=!!!"))
        assertNull(MacGatewayPairingPayload.parse(encode(validJson.replace("\"v\":1", "\"v\":2"))))
        assertNull(MacGatewayPairingPayload.parse(encode(validJson.replace("[\"studio.local\",\"192.168.1.5\"]", "[]"))))
        assertNull(MacGatewayPairingPayload.parse("amber://gateway/pair?p=" + "A".repeat(5000)))
    }

    @Test
    fun reportsUnreachableWhenNoAddressAnswers() {
        val client = MacGatewayClient(listOf("127.0.0.1"), port = 1, fingerprint = "abc=", token = "t")
        val error = runCatching { runBlocking { client.status() } }.exceptionOrNull()
        assertTrue(error is MacGatewayException.Unreachable)
    }
}
