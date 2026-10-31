package app.amber.feature.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Durable metadata for a local SSH key managed by Amber.
 *
 * Private key material and optional passphrases are never stored in this model;
 * they live in `SecretStore` under the `"ssh_key"` scope.
 */
@Serializable
data class SshKey(
    val id: String,
    val name: String,
    /** `"ssh-ed25519"`, `"ecdsa-sha2-nistp256"`, or `"ssh-rsa"` (imported only). */
    val algorithm: String,
    /** Complete single-line OpenSSH public key (`ssh-ed25519 AAAA... amber-<name>`). */
    val publicKeyOpenSsh: String,
    /** OpenSSH SHA-256 fingerprint (`SHA256:<base64-no-pad>`). */
    val fingerprintSha256: String,
    /** `"generated"` or `"imported"`. */
    val origin: String = ORIGIN_GENERATED,
    /** `"keystore_wrapped"` on Android. */
    val protection: String = PROTECTION_KEYSTORE_WRAPPED,
    val hasPassphrase: Boolean = false,
    val requireUserPresence: Boolean = false,
    val createdAtMs: Long = 0L,
    /** Opaque SecretStore generation token for atomic rotation/cleanup. */
    val secretRevision: String = "",
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(name == name.trim()) { "name must not be padded" }
        require(name.none { it.isISOControl() }) { "name contains control characters" }
        require(algorithm in SUPPORTED_ALGORITHMS) { "unsupported SSH key algorithm: $algorithm" }
        require(publicKeyOpenSsh.isNotBlank() && !publicKeyOpenSsh.contains('\n') && !publicKeyOpenSsh.contains('\r')) {
            "publicKeyOpenSsh must be a single non-empty line"
        }
        require(fingerprintSha256.startsWith("SHA256:")) {
            "fingerprintSha256 must start with SHA256:"
        }
        require(origin == ORIGIN_GENERATED || origin == ORIGIN_IMPORTED) {
            "origin must be generated or imported"
        }
    }

    companion object {
        const val ORIGIN_GENERATED = "generated"
        const val ORIGIN_IMPORTED = "imported"
        const val PROTECTION_KEYSTORE_WRAPPED = "keystore_wrapped"

        const val ALG_ED25519 = "ssh-ed25519"
        const val ALG_ECDSA_P256 = "ecdsa-sha2-nistp256"
        const val ALG_RSA = "ssh-rsa"

        val SUPPORTED_ALGORITHMS = setOf(ALG_ED25519, ALG_ECDSA_P256, ALG_RSA)
    }
}

@Serializable
data class SshKeysState(
    val keys: List<SshKey> = emptyList(),
) {
    val items: List<SshKey>
        get() = keys
}

@Serializable
enum class SshKeyGenType(val wireName: String, val sshAlgorithm: String) {
    @SerialName("ed25519")
    ED25519("ed25519", SshKey.ALG_ED25519),

    @SerialName("ecdsa-p256")
    ECDSA_P256("ecdsa-p256", SshKey.ALG_ECDSA_P256);

    companion object {
        fun fromWire(raw: String?): SshKeyGenType? {
            if (raw == null) return ED25519
            val normalized = raw.trim().lowercase()
            return when (normalized) {
                "ed25519", "ssh-ed25519" -> ED25519
                "ecdsa-p256", "ecdsa_p256", "ecdsa-sha2-nistp256", "p256" -> ECDSA_P256
                else -> null
            }
        }
    }
}
