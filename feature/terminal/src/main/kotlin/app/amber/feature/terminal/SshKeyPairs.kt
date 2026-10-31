package app.amber.feature.terminal

import app.amber.core.settings.ssh.SshKeyStore
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Result of generating or importing an SSH key pair.
 * [privateKeyOpenSsh] must only be passed into [SshKeyStore.save] and never
 * returned to agent tool outputs or logs.
 */
class SshGeneratedKeyPair(
    val key: SshKey,
    val privateKeyOpenSsh: String,
) {
    override fun toString(): String =
        "SshGeneratedKeyPair(key=$key, privateKeyOpenSsh=<redacted>)"
}

/**
 * Generates and parses OpenSSH/PEM key pairs using JSch + Bouncy Castle.
 *
 * Ensures Bouncy Castle Ed25519 / X25519 implementations are bound on [JSch]
 * so behavior is identical across JVM 21 unit tests and Android runtime.
 */
object SshKeyPairs {

    init {
        configureBouncyCastleEdDsa()
    }

    fun configureBouncyCastleEdDsa() {
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        JSch.setConfig("curve25519-sha256", "com.jcraft.jsch.DH25519")
        JSch.setConfig("curve25519-sha256@libssh.org", "com.jcraft.jsch.DH25519")
    }

    /**
     * Generates a fresh `ed25519` or `ecdsa-p256` SSH key pair in memory,
     * formats its OpenSSH public key line and `SHA256:...` fingerprint, and
     * exports the OpenSSH v1 private key text for [SshKeyStore].
     */
    fun generate(
        name: String,
        type: SshKeyGenType = SshKeyGenType.ED25519,
        id: String = UUID.randomUUID().toString(),
        nowMs: Long = System.currentTimeMillis(),
        requireUserPresence: Boolean = false,
    ): SshGeneratedKeyPair {
        require(!requireUserPresence) { "Per-use user presence is not supported by Android SSH keys." }
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "name must not be blank" }
        require(trimmedName == name) { "name must not be padded" }
        require(trimmedName.none { it.isISOControl() }) { "name contains control characters" }

        configureBouncyCastleEdDsa()
        val jsch = JSch()
        val jschType = when (type) {
            SshKeyGenType.ED25519 -> KeyPair.ED25519
            SshKeyGenType.ECDSA_P256 -> KeyPair.ECDSA
        }
        val keyPair = KeyPair.genKeyPair(jsch, jschType, 256)
        val privateKeyOut = ByteArrayOutputStream()
        try {
            val comment = "amber-${sanitizeComment(trimmedName)}"
            keyPair.publicKeyComment = comment
            keyPair.writeOpenSSHv1PrivateKey(privateKeyOut, null)
            val privateKeyBytes = privateKeyOut.toByteArray()
            val privateKeyPem = try {
                privateKeyBytes.toString(Charsets.UTF_8)
            } finally {
                privateKeyBytes.fill(0)
            }

            val pubBlob = keyPair.publicKeyBlob
                ?: error("Generated SSH key pair did not produce a public key blob")
            val algorithm = type.sshAlgorithm
            val pubBase64 = Base64.getEncoder().encodeToString(pubBlob)
            val publicKeyOpenSsh = "$algorithm $pubBase64 $comment"
            val fingerprint = sha256Fingerprint(pubBlob)

            val metadata = SshKey(
                id = id,
                name = trimmedName,
                algorithm = algorithm,
                publicKeyOpenSsh = publicKeyOpenSsh,
                fingerprintSha256 = fingerprint,
                origin = SshKey.ORIGIN_GENERATED,
                protection = SshKey.PROTECTION_KEYSTORE_WRAPPED,
                hasPassphrase = false,
                requireUserPresence = requireUserPresence,
                createdAtMs = nowMs,
            )
            return SshGeneratedKeyPair(
                key = metadata,
                privateKeyOpenSsh = privateKeyPem,
            )
        } finally {
            keyPair.dispose()
        }
    }

    /**
     * Parses and validates an imported OpenSSH or PEM private key (optionally encrypted
     * with [passphrase]), extracting its algorithm, OpenSSH public key line, and
     * `SHA256:<base64>` fingerprint.
     */
    fun parseImportedKey(
        name: String,
        privateKeyText: String,
        passphrase: String? = null,
        id: String = UUID.randomUUID().toString(),
        nowMs: Long = System.currentTimeMillis(),
        requireUserPresence: Boolean = false,
    ): SshKey {
        require(!requireUserPresence) { "Per-use user presence is not supported by Android SSH keys." }
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "name must not be blank" }
        require(trimmedName == name) { "name must not be padded" }
        require(trimmedName.none { it.isISOControl() }) { "name contains control characters" }
        require(privateKeyText.isNotBlank()) { "private_key must not be blank" }
        require(!SshKeyStore.isMaskedOrRedactedPlaceholder(privateKeyText)) {
            "private_key looks like a masked or redacted placeholder, not a real credential"
        }
        val normalizedKeyText = normalizePemLineEndings(privateKeyText)
        val prvBytes = normalizedKeyText.toByteArray(Charsets.UTF_8)
        require(prvBytes.size <= SshKeyStore.MAX_SECRET_BYTES) {
            "private_key exceeds maximum size of ${SshKeyStore.MAX_SECRET_BYTES} bytes"
        }
        if (!passphrase.isNullOrEmpty()) {
            require(!SshKeyStore.isMaskedOrRedactedPlaceholder(passphrase)) {
                "passphrase looks like a masked or redacted placeholder, not a real credential"
            }
            require(passphrase.toByteArray(Charsets.UTF_8).size <= SshKeyStore.MAX_SECRET_BYTES) {
                "passphrase exceeds maximum size of ${SshKeyStore.MAX_SECRET_BYTES} bytes"
            }
        }

        configureBouncyCastleEdDsa()
        val jsch = JSch()
        var keyPair: KeyPair? = null
        val passphraseBytes = passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        try {
            keyPair = try {
                KeyPair.load(jsch, prvBytes, null)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid or unsupported SSH private key format.")
            }
            if (keyPair.isEncrypted) {
                require(passphraseBytes != null && passphraseBytes.isNotEmpty()) {
                    "SSH private key is encrypted; passphrase is required."
                }
                val decrypted = keyPair.decrypt(passphraseBytes)
                require(decrypted) {
                    "Failed to decrypt SSH private key with the supplied passphrase."
                }
            }
            val pubBlob = keyPair.publicKeyBlob
                ?: throw IllegalArgumentException("Unable to derive public key from SSH private key.")
            val rawAlgorithm = keyPair.keyTypeString?.trim().orEmpty()
            val algorithm = when {
                rawAlgorithm.equals("ssh-ed25519", ignoreCase = true) -> SshKey.ALG_ED25519
                rawAlgorithm.equals("ecdsa-sha2-nistp256", ignoreCase = true) -> SshKey.ALG_ECDSA_P256
                rawAlgorithm.equals("ssh-rsa", ignoreCase = true) -> SshKey.ALG_RSA
                else -> throw IllegalArgumentException("Unsupported SSH key algorithm: '$rawAlgorithm'.")
            }
            val comment = keyPair.publicKeyComment
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.none { ch -> ch.isISOControl() || ch == '\n' || ch == '\r' } }
                ?: "amber-${sanitizeComment(trimmedName)}"
            val pubBase64 = Base64.getEncoder().encodeToString(pubBlob)
            val publicKeyOpenSsh = "$algorithm $pubBase64 $comment"
            val fingerprint = sha256Fingerprint(pubBlob)

            return SshKey(
                id = id,
                name = trimmedName,
                algorithm = algorithm,
                publicKeyOpenSsh = publicKeyOpenSsh,
                fingerprintSha256 = fingerprint,
                origin = SshKey.ORIGIN_IMPORTED,
                protection = SshKey.PROTECTION_KEYSTORE_WRAPPED,
                hasPassphrase = passphraseBytes != null,
                requireUserPresence = requireUserPresence,
                createdAtMs = nowMs,
            )
        } finally {
            prvBytes.fill(0)
            passphraseBytes?.fill(0)
            runCatching { keyPair?.dispose() }
        }
    }

    /** Formats a raw SSH public key blob into OpenSSH `SHA256:<base64-no-pad>` form. */
    fun sha256Fingerprint(publicKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** Formats a raw SSH public key blob into 64 lowercase hex characters. */
    fun sha256HexFingerprint(publicKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun normalizePemLineEndings(raw: String): String =
        raw.trim().replace("\r\n", "\n").replace("\r", "\n") + "\n"

    private fun sanitizeComment(name: String): String =
        name.replace(Regex("\\s+"), "-")
            .replace(Regex("[^A-Za-z0-9._@-]"), "")
            .ifBlank { "key" }
}
