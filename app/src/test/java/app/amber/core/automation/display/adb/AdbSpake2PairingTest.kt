package app.amber.core.automation.display.adb

import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

class AdbSpake2PairingTest {
    private val password = "123456".toByteArray() + ByteArray(64) { it.toByte() }

    @Test
    fun bothRolesMatchIndependentEdwardsTranscriptAndExchangePeerInfo() {
        val client = AdbSpake2(password, client = true, random = sequence(reverse = false))
        val server = AdbSpake2(password, client = false, random = sequence(reverse = true))
        // Independently calculated using affine Edwards addition and BoringSSL's reduced scalars.
        assertArrayEquals(Hex.decode("eabf2a3a0cf98757b7f1d64ee3d709cb2aa19390ff37cfb92b37860b0e67ebd5"), client.message)
        assertArrayEquals(Hex.decode("1fb70d81ff66c8bdb1ecf5a0913c54229c264d5e1385459e4ed7d69127e3dad1"), server.message)
        val clientKey = client.finish(server.message)
        val serverKey = server.finish(client.message)
        assertArrayEquals(Hex.decode(
            "815e6153a5dcb41137efe43edfee3e25a4603b396755639fee68af5cfea2b8de" +
                "3cc19d59a6967b601b098bbee93c2cd0960c62d76790a54d35b05012bbd35174",
        ), clientKey)
        assertArrayEquals(clientKey, serverKey)
        val info = ByteArray(8192).apply { "test public key".toByteArray().copyInto(this, 1) }
        val ciphertext = AdbSpake2Pairing.peerInfoCipher(clientKey, Cipher.ENCRYPT_MODE).doFinal(info)
        assertArrayEquals(info, AdbSpake2Pairing.peerInfoCipher(serverKey, Cipher.DECRYPT_MODE).doFinal(ciphertext))
        ciphertext[123] = (ciphertext[123].toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) {
            AdbSpake2Pairing.peerInfoCipher(serverKey, Cipher.DECRYPT_MODE).doFinal(ciphertext)
        }
    }

    @Test
    fun wrongPairingCodeCannotAuthenticatePeerInfo() {
        val client = AdbSpake2(password, client = true)
        val server = AdbSpake2(password.clone().apply { this[0] = '9'.code.toByte() }, client = false)
        val ciphertext = AdbSpake2Pairing.peerInfoCipher(client.finish(server.message), Cipher.ENCRYPT_MODE)
            .doFinal(ByteArray(8192))
        assertThrows(AEADBadTagException::class.java) {
            AdbSpake2Pairing.peerInfoCipher(server.finish(client.message), Cipher.DECRYPT_MODE).doFinal(ciphertext)
        }
    }

    private fun sequence(reverse: Boolean): SecureRandom = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (if (reverse) bytes.lastIndex - i else i).toByte()
        }
    }
}
