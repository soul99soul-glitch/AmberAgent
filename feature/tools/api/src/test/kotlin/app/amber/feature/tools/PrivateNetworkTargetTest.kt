package app.amber.feature.tools

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateNetworkTargetTest {
    @Test
    fun classifiesExplicitPrivateAndPublicTargets() {
        assertTrue("http://127.0.0.1/status".isPrivateNetworkTarget())
        assertTrue("http://192.168.1.10/status".isPrivateNetworkTarget())
        assertTrue("http://[fd00::1]/status".isPrivateNetworkTarget())
        assertFalse("https://8.8.8.8/status".isPrivateNetworkTarget())
    }

    @Test
    fun classifiesResolvedPrivateAndPublicAddresses() {
        assertTrue(InetAddress.getByName("100.64.0.1").isPrivateNetworkAddress())
        assertTrue(InetAddress.getByName("fd00::1").isPrivateNetworkAddress())
        assertFalse(InetAddress.getByName("8.8.8.8").isPrivateNetworkAddress())
    }
}
