package app.amber.core.automation.display.adb

import android.net.ssl.SSLSockets
import org.bouncycastle.math.ec.rfc7748.X25519Field as Field
import org.bouncycastle.util.encoders.Hex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/** AOSP pairing_connection v1. Only called on an already-handshaken local pairing socket. */
internal object AdbSpake2Pairing {
    private const val PEER_INFO_SIZE = 8192

    fun pair(socket: SSLSocket, code: String, adbPublicKey: String): String {
        require(code.length == 6 && code.all { it in '0'..'9' }) { "Expected six-digit pairing code" }
        check(socket.session.protocol == "TLSv1.3") { "Pairing requires TLS 1.3" }
        val binding = checkNotNull(SSLSockets.exportKeyingMaterial(socket, "adb-label\u0000", null, 64))
        val exchange = AdbSpake2(code.toByteArray(Charsets.US_ASCII) + binding, client = true)
        val input = DataInputStream(socket.inputStream)
        val output = DataOutputStream(socket.outputStream)
        writePacket(output, 0, exchange.message)
        val sharedKey = exchange.finish(readPacket(input, 0, 32))

        val publicKey = adbPublicKey.toByteArray(Charsets.US_ASCII)
        require(publicKey.size in 1 until PEER_INFO_SIZE - 1 && 0.toByte() !in publicKey)
        val info = ByteArray(PEER_INFO_SIZE) // type 0: ADB_RSA_PUB_KEY followed by NUL-terminated text.
        publicKey.copyInto(info, destinationOffset = 1)
        writePacket(output, 1, peerInfoCipher(sharedKey, Cipher.ENCRYPT_MODE).doFinal(info))
        val reply = peerInfoCipher(sharedKey, Cipher.DECRYPT_MODE).doFinal(readPacket(input, 1, PEER_INFO_SIZE + 16))
        require(reply[0] == 1.toByte()) { "Expected authenticated device GUID" }
        val end = (1 until reply.size).firstOrNull { reply[it] == 0.toByte() }
        require(end != null && end > 1) { "Missing device GUID" }
        return String(reply, 1, end - 1, Charsets.US_ASCII)
    }

    private fun readPacket(input: DataInputStream, type: Int, size: Int): ByteArray {
        require(input.readUnsignedByte() == 1 && input.readUnsignedByte() == type && input.readInt() == size) {
            "Unexpected ADB pairing packet"
        }
        return ByteArray(size).also(input::readFully)
    }

    private fun writePacket(output: DataOutputStream, type: Int, bytes: ByteArray) {
        output.writeByte(1)
        output.writeByte(type)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    // AOSP starts each direction at sequence zero and exchanges exactly one encrypted PeerInfo.
    internal fun peerInfoCipher(sharedKey: ByteArray, mode: Int): Cipher {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val extracted = hmac.doFinal(sharedKey)
        hmac.init(SecretKeySpec(extracted, "HmacSHA256"))
        val key = hmac.doFinal("adb pairing_auth aes-128-gcm key\u0001".toByteArray(Charsets.US_ASCII))
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, 0, 16, "AES"), GCMParameterSpec(128, ByteArray(12)))
        }
    }
}

/** BoringSSL crypto/curve25519/spake25519.cc with AOSP's NUL-terminated role names. */
internal class AdbSpake2(
    password: ByteArray,
    private val client: Boolean,
    random: SecureRandom = SecureRandom(),
) {
    private val hash = MessageDigest.getInstance("SHA-512").digest(password)
    private val privateScalar = ByteArray(64).also(random::nextBytes).apply {
        // Uniform cofactor multiple. Scalar multiplication implicitly reduces modulo the group order.
        this[0] = (this[0].toInt() and 0xf8).toByte()
    }
    private val maskScalar = passwordScalar(hash)
    val message = (base.multiply(privateScalar) + (if (client) m else n).multiply(maskScalar)).encode()

    fun finish(peerMessage: ByteArray): ByteArray {
        val unmasked = EdwardsPoint.decode(peerMessage) + (if (client) n else m).multiply(maskScalar).negated()
        val shared = unmasked.multiply(privateScalar).encode()
        require(!shared.contentEquals(identityEncoding)) { "Invalid SPAKE2 shared point" }
        val transcript = MessageDigest.getInstance("SHA-512")
        for (value in listOf(clientName, serverName, if (client) message else peerMessage,
            if (client) peerMessage else message, shared, hash)) {
            // BoringSSL hashes each field prefixed by its uint64 little-endian byte length.
            val length = value.size.toLong()
            repeat(8) { transcript.update((length ushr (8 * it)).toByte()) }
            transcript.update(value)
        }
        return transcript.digest()
    }

    private companion object {
        val clientName = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        val serverName = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)
        val identityEncoding = ByteArray(32).apply { this[0] = 1 }
        val base = EdwardsPoint.decode(Hex.decode("5866666666666666666666666666666666666666666666666666666666666666"))
        val m = EdwardsPoint.decode(Hex.decode("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
        val n = EdwardsPoint.decode(Hex.decode("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))
        val order = Hex.decode("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010")

        fun passwordScalar(hash: ByteArray): ByteArray {
            // BoringSSL chooses a representative congruent to the hash mod L and zero mod 8.
            // L mod 8 = 5, whose inverse mod 8 is 5. Retaining the full-width scalar gives the
            // same point because any two such representatives differ by a multiple of 8L.
            val multiple = (-5 * hash[0].toInt()) and 7
            val result = hash.copyOf(65)
            var carry = 0
            for (i in result.indices) {
                val sum = (result[i].toInt() and 255) + carry +
                    if (i < order.size) multiple * (order[i].toInt() and 255) else 0
                result[i] = sum.toByte()
                carry = sum ushr 8
            }
            return result
        }
    }
}

/** Extended Edwards coordinates, complete addition for a=-1 (Hisil et al., add-2008-hwcd-3).
 * Bouncy Castle supplies field arithmetic and constant-time conditional moves. */
private class EdwardsPoint(val x: IntArray, val y: IntArray, val z: IntArray, val t: IntArray) {
    operator fun plus(other: EdwardsPoint): EdwardsPoint {
        val a = mul(sub(y, x), sub(other.y, other.x))
        val b = mul(add(y, x), add(other.y, other.x))
        val c = mul(mul(t, other.t), twiceD)
        val zz = mul(z, other.z)
        val d = add(zz, zz)
        val e = sub(b, a)
        val f = sub(d, c)
        val g = add(d, c)
        val h = add(b, a)
        return EdwardsPoint(mul(e, f), mul(g, h), mul(f, g), mul(e, h))
    }

    fun negated() = EdwardsPoint(sub(zero, x), y, z, sub(zero, t))

    fun multiply(scalar: ByteArray): EdwardsPoint {
        var result = identity
        for (i in scalar.size * 8 - 1 downTo 0) {
            result += result
            val sum = result + this
            val mask = -((scalar[i / 8].toInt() ushr (i % 8)) and 1)
            Field.cmov(mask, sum.x, 0, result.x, 0)
            Field.cmov(mask, sum.y, 0, result.y, 0)
            Field.cmov(mask, sum.z, 0, result.z, 0)
            Field.cmov(mask, sum.t, 0, result.t, 0)
        }
        return result
    }

    fun encode(): ByteArray {
        val inverseZ = Field.create().also { Field.inv(z, it) }
        val affineX = mul(x, inverseZ).also(Field::normalize)
        val affineY = mul(y, inverseZ).also(Field::normalize)
        return ByteArray(32).apply {
            Field.encode(affineY, this)
            this[31] = (this[31].toInt() or ((affineX[0] and 1) shl 7)).toByte()
        }
    }

    companion object {
        private val zero = Field.create()
        private val one = Field.create().also(Field::one)
        private val identity = EdwardsPoint(zero, one, one, zero)
        private val d = Field.create().also { Field.decode(Hex.decode("a3785913ca4deb75abd841414d0a700098e879777940c78c73fe6f2bee6c0352"), it) }
        private val twiceD = add(d, d)
        private fun mul(a: IntArray, b: IntArray) = Field.create().also { Field.mul(a, b, it) }
        private fun add(a: IntArray, b: IntArray) = Field.create().also { Field.add(a, b, it); Field.carry(it) }
        private fun sub(a: IntArray, b: IntArray) = Field.create().also { Field.sub(a, b, it); Field.carry(it) }

        fun decode(encoded: ByteArray): EdwardsPoint {
            require(encoded.size == 32)
            val yBytes = encoded.clone()
            val sign = (yBytes[31].toInt() ushr 7) and 1
            yBytes[31] = (yBytes[31].toInt() and 127).toByte()
            val y = Field.create().also { Field.decode(yBytes, it) }
            val y2 = mul(y, y)
            val x = Field.create()
            require(Field.sqrtRatioVar(sub(y2, one), add(mul(d, y2), one), x)) { "Invalid SPAKE2 point" }
            Field.normalize(x)
            Field.cnegate((x[0] and 1) xor sign, x)
            val point = EdwardsPoint(x, y, one, mul(x, y))
            require(point.encode().contentEquals(encoded)) { "Non-canonical SPAKE2 point" }
            return point
        }
    }
}
