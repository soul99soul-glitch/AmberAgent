package app.amber.feature.ui.pages.phoneuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAdbPortInputTest {
    @Test
    fun acceptsPlainPortsAndSystemAddresses() {
        for (input in listOf("38421", "192.168.100.124:38421", " 127.0.0.1 ： 38421  ",
            "10.0.0.3:38421", "172.16.0.1:38421", "172.31.255.254:38421", "169.254.1.1:38421")) {
            assertEquals(input, 38421, parseLocalAdbPortInput(input))
        }
        assertEquals(1, parseLocalAdbPortInput(" 1 "))
        assertEquals(65535, parseLocalAdbPortInput("65535"))
    }

    @Test
    fun blankHasNoExplicitPort() {
        assertNull(parseLocalAdbPortInput(""))
        assertNull(parseLocalAdbPortInput("  "))
    }

    @Test
    fun rejectsInvalidPortsAndNonlocalOrMalformedAddresses() {
        for (input in listOf("0", "65536", "-1", "+80", "123456", "1.2", "192.168.100.124",
            "8.8.8.8:38421", "172.32.0.1:38421", "192.168.1.256:9", "192.168.01.1:80",
            "192.168.1:80", "192.168.a.1:80", ":38421", "127.0.0.1:4:2", "127.0.0.1:",
            "https://127.0.0.1:80", "example.com:80")) {
            assertNull(input, parseLocalAdbPortInput(input))
        }
    }
}
