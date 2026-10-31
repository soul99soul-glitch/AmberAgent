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
    /** Referenced local SSH key id in SshKeyStore when using key authentication. */
    val keyId: String? = null,
    /** Optional single-hop jump profile id; multi-hop chains are formed by chaining profiles. */
    val jumpProfileId: String? = null,
    /** SHA-256 fingerprint of the accepted host key, hex without colons. */
    val acceptedHostKeyFingerprint: String? = null,
    /** Host for which [acceptedHostKeyFingerprint] was confirmed. */
    val acceptedHostKeyHost: String? = if (acceptedHostKeyFingerprint != null) host else null,
    /** Port for which [acceptedHostKeyFingerprint] was confirmed. */
    val acceptedHostKeyPort: Int? = if (acceptedHostKeyFingerprint != null) port else null,
    /** Jump profile id via which [acceptedHostKeyFingerprint] was confirmed. */
    val acceptedHostKeyViaJump: String? = if (acceptedHostKeyFingerprint != null) jumpProfileId else null,
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
        require(keyId == null || keyId.isNotBlank()) { "keyId must not be blank" }
        require(jumpProfileId == null || jumpProfileId.isNotBlank()) { "jumpProfileId must not be blank" }
    }
}

/** Persisted SSH profiles and the selected default profile. */
@Serializable
data class SshProfilesState(
    val profiles: List<SshProfile> = emptyList(),
    val defaultProfileId: String? = null,
) {
    val items: List<SshProfile>
        get() = profiles
}

@Serializable
enum class SshAuthMethod(val wireName: String) {
    @SerialName("password")
    PASSWORD("password"),

    @SerialName("private_key")
    PRIVATE_KEY("key");

    companion object {
        fun fromWire(value: String?): SshAuthMethod? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return when (normalized) {
                "password" -> PASSWORD
                "key", "private_key", "privatekey", "publickey", "public_key" -> PRIVATE_KEY
                else -> entries.firstOrNull {
                    it.wireName.equals(normalized, ignoreCase = true) ||
                        it.name.equals(normalized, ignoreCase = true)
                }
            }
        }
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
        if (profile.acceptedHostKeyHost != null && profile.acceptedHostKeyHost != profile.host) {
            return SshHostTrust.Untrusted
        }
        if (profile.acceptedHostKeyPort != null && profile.acceptedHostKeyPort != profile.port) {
            return SshHostTrust.Untrusted
        }
        if (profile.acceptedHostKeyViaJump != profile.jumpProfileId) {
            return SshHostTrust.Untrusted
        }
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

    /** Normalizes a fingerprint for storage: lowercase hex, no colons/spaces (also accepts SHA256:<base64>). */
    fun normalizeFingerprint(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("SHA256:", ignoreCase = true)) {
            val b64 = trimmed.substringAfter(':').trim().trimEnd('=')
            val bytes = try {
                java.util.Base64.getDecoder().decode(b64)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("fingerprint must be 32 SHA-256 bytes", e)
            }
            require(bytes.size == 32) { "fingerprint must be 32 SHA-256 bytes" }
            return bytes.joinToString("") { "%02x".format(it) }
        }
        return trimmed.replace(":", "").lowercase()
            .also { normalized ->
                require(Regex("[0-9a-f]{64}").matches(normalized)) {
                    "fingerprint must be 32 SHA-256 bytes in hex"
                }
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
