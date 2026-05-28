package app.amber.feature.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MiniAppUrlGuardTest {
    @Test
    fun allowsPublicHttpsHosts() {
        val guard = guardResolving("93.184.216.34")

        val url = guard.check("https://example.com/news")

        assertEquals("https", url.scheme)
        assertEquals("example.com", url.host)
    }

    @Test
    fun rejectsNonHttps() {
        val guard = guardResolving("93.184.216.34")

        assertRejected { guard.check("http://example.com") }
    }

    @Test
    fun rejectsPrivateAndLoopbackIpv4() {
        listOf(
            "0.0.0.0",
            "10.1.2.3",
            "127.0.0.1",
            "100.64.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.1",
            "224.0.0.1",
        ).forEach { ip ->
            val guard = guardResolving(ip)
            assertRejected("Expected $ip to be rejected") { guard.check("https://example.com") }
        }
    }

    @Test
    fun rejectsPrivateAndLoopbackIpv6() {
        listOf(
            "::1",
            "fc00::1",
            "fd12::1",
            "fe80::1",
            "ff02::1",
            "::ffff:127.0.0.1",
            "::ffff:192.168.1.1",
        ).forEach { ip ->
            val guard = guardResolving(ip)
            assertRejected("Expected $ip to be rejected") { guard.check("https://example.com") }
        }
    }

    @Test
    fun resolveAllowedUsesSamePrivateAddressGuard() {
        val guard = guardResolving("127.0.0.1")

        assertRejected { guard.resolveAllowed("example.com") }
    }

    private fun guardResolving(vararg addresses: String): MiniAppUrlGuard {
        return MiniAppUrlGuard { addresses.map(InetAddress::getByName) }
    }

    private fun assertRejected(message: String = "Expected URL to be rejected", block: () -> Unit) {
        assertTrue(message, runCatching(block).isFailure)
    }
}
