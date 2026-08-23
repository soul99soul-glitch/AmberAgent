package app.amber.feature.ui.pages.synara

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 配对二维码 payload 解析契约：bridge 打印的 HTTP URL 与 WS bootstrap URL
 * 都能还原出 host/port/token，且 workspaceUrl() 生成的 URL 可原路解析回来。
 */
class SynaraConnectionTest {

    @Test
    fun `parses bridge http pairing url`() {
        val conn = SynaraConnection.fromQrPayload("http://192.168.100.107:3773/?token=abc123")

        assertEquals("192.168.100.107", conn?.host)
        assertEquals(3773, conn?.port)
        assertEquals("abc123", conn?.token)
        assertEquals(false, conn?.useHttps)
    }

    @Test
    fun `parses ws bootstrap url`() {
        val conn = SynaraConnection.fromQrPayload("ws://192.168.100.107:3773/ws?token=abc123")

        assertEquals("192.168.100.107", conn?.host)
        assertEquals(3773, conn?.port)
        assertEquals("abc123", conn?.token)
    }

    @Test
    fun `parses wss bootstrap url`() {
        val conn = SynaraConnection.fromQrPayload("wss://192.168.100.107:3773/ws?token=abc123")

        assertEquals("192.168.100.107", conn?.host)
        assertEquals(3773, conn?.port)
        assertEquals("abc123", conn?.token)
        assertEquals(true, conn?.useHttps)
    }

    @Test
    fun `restores brackets on ipv6 literal host`() {
        val conn = SynaraConnection.fromQrPayload("http://[::1]:3773/?token=abc123")

        assertEquals("[::1]", conn?.host)
        assertEquals(3773, conn?.port)
        assertEquals("abc123", conn?.token)
    }

    @Test
    fun `url-decodes token and honours https scheme`() {
        val conn = SynaraConnection.fromQrPayload("https://10.0.0.5:8443/?token=a%20b%2Fc")

        assertEquals(8443, conn?.port)
        assertEquals("a b/c", conn?.token)
        assertEquals(true, conn?.useHttps)
    }

    @Test
    fun `roundtrips workspaceUrl`() {
        val original = SynaraConnection(host = "192.168.1.5", port = 3773, token = "t k/c")

        val parsed = SynaraConnection.fromQrPayload(original.workspaceUrl())

        assertEquals(original.copy(), parsed)
    }

    @Test
    fun `ws probe urls try bootstrap first then legacy`() {
        val urls = SynaraConnection(host = "192.168.1.5", port = 3773, token = "a b")
            .wsProbeUrls()

        assertEquals(2, urls.size)
        assertEquals("ws://192.168.1.5:3773/ws/bootstrap?token=a%20b", urls[0])
        assertEquals("ws://192.168.1.5:3773/ws?token=a%20b", urls[1])
    }

    @Test
    fun `rejects payloads without token or scheme`() {
        assertNull(SynaraConnection.fromQrPayload("http://192.168.100.107:3773/"))
        assertNull(SynaraConnection.fromQrPayload("synara://192.168.100.107:3773/?token=abc"))
        assertNull(SynaraConnection.fromQrPayload("not a url"))
        assertNull(SynaraConnection.fromQrPayload(""))
    }
}
