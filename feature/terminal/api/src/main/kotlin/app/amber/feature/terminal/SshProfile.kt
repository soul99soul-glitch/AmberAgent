package app.amber.feature.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Durable endpoint and host-key trust metadata for one managed SSH profile. */
@Serializable
data class SshProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    /** Authentication method; credential values are resolved separately. */
    val authMethod: SshAuthMethod,
    /** SHA-256 fingerprint of the accepted host key, hex without colons. */
    val acceptedHostKeyFingerprint: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Opaque SecretStore generation; empty means the legacy credential keys. */
    val credentialRevision: String = "",
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(name == name.trim()) { "name must not be padded" }
        require(name.none { it.isISOControl() }) { "name contains control characters" }
        require(host.isNotBlank()) { "host must not be blank" }
        require(host == host.trim()) { "host must not be padded" }
        require(host.none { it.isWhitespace() || it.isISOControl() }) {
            "host contains whitespace"
        }
        require(username.isNotBlank()) { "username must not be blank" }
        require(username == username.trim()) { "username must not be padded" }
        require(username.none { it.isWhitespace() || it.isISOControl() }) {
            "username contains whitespace"
        }
        require(port in 1..65535) { "port out of range" }
    }
}

/** Persisted SSH profiles and the selected default profile. */
@Serializable
data class SshProfilesState(
    val profiles: List<SshProfile> = emptyList(),
    val defaultProfileId: String? = null,
)

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

    /** Formats a stored SHA-256 fingerprint the way OpenSSH displays it. */
    fun displayFingerprint(raw: String): String {
        val normalized = normalizeFingerprint(raw)
        val bytes = ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return "SHA256:" + java.util.Base64.getEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }
}
