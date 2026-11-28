package app.amber.core.automation.display.adb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class LocalAdbKeyStoreTest {
    @Test
    fun publicKeyMatchesAospMontgomeryRsaLayout() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        val bytes = encodeAdbRsaKey(key)
        assertEquals(524, bytes.size)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, input.int)
        val inverse = BigInteger.valueOf(input.int.toLong() and 0xffff_ffffL)
        val modulus = BigInteger(1, ByteArray(256).apply { input.get(this) }.reversedArray())
        val rr = BigInteger(1, ByteArray(256).apply { input.get(this) }.reversedArray())
        assertEquals(key.modulus, modulus)
        assertEquals(BigInteger.ZERO, (modulus * inverse + BigInteger.ONE).mod(BigInteger.ONE.shiftLeft(32)))
        assertEquals(BigInteger.TWO.modPow(BigInteger.valueOf(4096), modulus), rr)
        assertEquals(key.publicExponent.toInt(), input.int)
        assertEquals(0, input.remaining())
    }
}
