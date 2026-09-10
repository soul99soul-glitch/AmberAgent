package app.amber.feature.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * W17: managed remote SSH profile models. These are the durable data
 * contracts for the first non-PTY SSH runtime — endpoint, authentication
 * material references and the host-key trust state. Secrets never live here:
 * password / private key / passphrase are stored in the Keystore-backed
 * SecretStore under `scope=ssh, ownerId=profileId` and referenced by field
 * name only.
 *
 * The client backend (audited ARM64 OpenSSH binary or an approved JVM SSH
 * library) is still an open sourcing decision — these models are deliberately
 * client-agnostic so the runtime wiring can land without re-shaping persisted
 * data.
 */
@Serializable
data class SshProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    /** SecretStore reference — never a literal secret. */
    val authMethod: SshAuthMethod,
    /** SHA-256 fingerprint of the accepted host key, hex without colons. */
    val acceptedHostKeyFingerprint: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(port in 1..65535) { "port out of range" }
    }
}

@Serializable
enum class SshAuthMethod(val wireName: String) {
    @SerialName("password")
    PASSWORD("password"),

    @SerialName("private_key")
    PRIVATE_KEY("private_key");

    companion object {
        fun fromWire(value: String?): SshAuthMethod? =
            entries.firstOrNull { it.wireName == value || it.name.equals(value, ignoreCase = true) }
    }
}

/** Host-key trust state for a profile, resolved before any authentication. */
sealed interface SshHostTrust {
    /** No fingerprint accepted yet: the first connect must probe and ask. */
    data object Untrusted : SshHostTrust

    /** The probed fingerprint differs from the accepted one — block before auth. */
    data class Mismatch(
        val acceptedFingerprint: String,
        val probedFingerprint: String,
    ) : SshHostTrust

    /** The probed fingerprint equals the accepted one. */
    data class Trusted(val fingerprint: String) : SshHostTrust
}

object SshTrustPolicy {
    /**
     * First-connection policy: an accepted fingerprint only exists after the
     * user explicitly confirmed a probe. A missing accepted fingerprint can
     * never evaluate to Trusted — TOFU is a user decision, not a default.
     * Both sides are normalized before comparison; any non-normalizable
     * value (empty/illegal hex) is treated as Untrusted, never Trusted.
     */
    fun evaluate(profile: SshProfile, probedFingerprint: String?): SshHostTrust {
        val accepted = profile.acceptedHostKeyFingerprint
            ?.let { runCatching { normalizeFingerprint(it) }.getOrNull() }
            ?: return SshHostTrust.Untrusted
        val probed = probedFingerprint
            ?.let { runCatching { normalizeFingerprint(it) }.getOrNull() }
            ?: return SshHostTrust.Untrusted
        return if (probed == accepted) {
            SshHostTrust.Trusted(accepted)
        } else {
            SshHostTrust.Mismatch(accepted, probed)
        }
    }

    /** Normalizes a fingerprint for storage: lowercase hex, no colons/spaces. */
    fun normalizeFingerprint(raw: String): String =
        raw.trim().replace(":", "").lowercase()
            .also { normalized ->
                require(Regex("[0-9a-f]{64}").matches(normalized)) {
                    "fingerprint must be 32 SHA-256 bytes in hex"
                }
            }
}
