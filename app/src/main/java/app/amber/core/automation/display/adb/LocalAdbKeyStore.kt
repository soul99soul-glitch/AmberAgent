package app.amber.core.automation.display.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.math.BigInteger
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

/** One non-exportable ADB identity for this installation. Only the paired device GUID is a file. */
internal class LocalAdbKeyStore(context: Context) {
    private val deviceGuid = AtomicFile(File(context.noBackupFilesDir, "wireless-adb-device"))

    val pairedGuid: String?
        get() = synchronized(lock) {
            try {
                deviceGuid.openRead().bufferedReader(Charsets.UTF_8).use { it.readText().takeIf(String::isNotBlank) }
            } catch (_: FileNotFoundException) {
                null
            }
        }

    val paired: Boolean get() = pairedGuid != null

    fun recordPaired(guid: String) = synchronized(lock) {
        require(guid.isNotBlank() && guid.length <= 255 && guid.none(Char::isISOControl)) { "Invalid ADB device GUID" }
        val out = deviceGuid.startWrite()
        try {
            out.write(guid.toByteArray(Charsets.UTF_8))
            deviceGuid.finishWrite(out)
        } catch (failure: Throwable) {
            deviceGuid.failWrite(out)
            throw failure
        }
    }

    private fun identity(): KeyStore.PrivateKeyEntry = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            deviceGuid.delete()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                // Conscrypt can pass pre-encoded TLS CertificateVerify signatures to KeyMint.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(X500Principal("CN=AmberAgent local ADB"))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 20L * 365 * 24 * 60 * 60 * 1000))
                .build()
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    fun adbPublicKey(): String {
        val key = identity().certificate.publicKey as RSAPublicKey
        return Base64.getEncoder().encodeToString(encodeAdbRsaKey(key)) + " amber@localhost"
    }

    fun sslContext(): SSLContext {
        val identity = identity()
        // adbd advertises authorized keys as acceptable issuer names, which generally do not
        // match the subject of our self-signed certificate. Send only this ADB identity.
        val manager = object : X509KeyManager {
            override fun getClientAliases(type: String, issuers: Array<out Principal>?) =
                if (type == "RSA") arrayOf(ALIAS) else null
            override fun chooseClientAlias(types: Array<out String>, issuers: Array<out Principal>?, socket: Socket?) =
                ALIAS.takeIf { "RSA" in types }
            override fun getServerAliases(type: String, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(type: String, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getPrivateKey(alias: String?): PrivateKey? = identity.privateKey.takeIf { alias == ALIAS }
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == ALIAS) arrayOf(identity.certificate as X509Certificate) else null
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(manager), arrayOf(LocalAdbdTrust), null)
        }
    }

    private companion object {
        const val ALIAS = "amber-local-wireless-adb"
        val lock = Any()
    }
}

// AOSP android_pubkey_encode: word count, -1/n mod 2^32, modulus, R² mod n, exponent.
internal fun encodeAdbRsaKey(key: RSAPublicKey): ByteArray {
    val n = key.modulus
    require(n.bitLength() == 2048 && n.testBit(0))
    val result = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
    result.putInt(64)
    result.putInt(n.modInverse(BigInteger.ONE.shiftLeft(32)).negate().toInt())
    for (field in listOf(n, BigInteger.ONE.shiftLeft(4096).mod(n))) {
        val littleEndian = field.toByteArray().reversedArray()
        result.put(littleEndian.copyOf(256))
    }
    result.putInt(key.publicExponent.intValueExact())
    return result.array()
}

// AOSP generates independent ephemeral server keys for pairing and adbd. The pairing code
// authenticates the exporter-bound exchange. Never use this trust policy on a remote endpoint.
private object LocalAdbdTrust : X509ExtendedTrustManager() {
    override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
        val addr = socket.inetAddress ?: throw CertificateException("ADB socket is not connected")
        if (chain.isEmpty() || !(addr.isLoopbackAddress || NetworkInterface.getByInetAddress(addr) != null)) {
            throw CertificateException("ADB TLS requires a local device endpoint")
        }
    }

    private fun reject(): Nothing = throw CertificateException("ADB TLS requires a local client socket")
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = reject()
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = reject()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = reject()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = reject()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = reject()
}
