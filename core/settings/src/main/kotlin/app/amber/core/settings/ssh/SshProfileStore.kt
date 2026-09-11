package app.amber.core.settings.ssh

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretStore
import app.amber.feature.terminal.SshAuthMethod
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.SshProfilesState
import app.amber.feature.terminal.SshTrustPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * The runtime-facing credentials loaded from SecretStore for one profile.
 * Keep this type out of persisted models and redact it when it is logged.
 */
class SshCredentials(
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
) {
    override fun toString(): String =
        "SshCredentials(" +
            "password=${password.redacted()}, " +
            "privateKey=${privateKey.redacted()}, " +
            "passphrase=${passphrase.redacted()})"

    private fun String?.redacted(): String = if (this == null) "null" else "<redacted>"
}

/**
 * Durable storage for SSH profiles. Profile metadata is kept in the dedicated
 * `ssh_profiles` DataStore entry; credential values only live in SecretStore.
 */
class SshProfileStore(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
) {
    private val mutationMutex = Mutex()

    /** The DataStore-backed flow. It intentionally has no synthetic ready state. */
    val state: Flow<SshProfilesState> = dataStore.data.map { preferences ->
        decodeState(preferences[SSH_PROFILES_KEY])
    }

    /** Read the latest persisted state. */
    suspend fun snapshot(): SshProfilesState = state.first()

    /**
     * Save or update a profile. Null credentials preserve their current value;
     * an empty passphrase removes the passphrase. Host-key trust is only changed
     * by [acceptHostKey], and endpoint changes always clear it.
     */
    suspend fun save(
        profile: SshProfile,
        password: String? = null,
        privateKey: String? = null,
        passphrase: String? = null,
    ) = mutationMutex.withLock {
        validateProfile(profile)
        validateSecret(password, "password")
        validateSecret(privateKey, "private key")
        validatePassphrase(passphrase)

        val before = snapshot()
        val existing = before.profiles.firstOrNull { it.id == profile.id }
        val endpointChanged = existing != null &&
            (existing.host != profile.host || existing.port != profile.port)
        val previousCredentials = existing?.let(::readCredentials)
            ?: SshCredentials(password = null, privateKey = null, passphrase = null)
        if (existing != null) {
            val retainedFields = when (profile.authMethod) {
                SshAuthMethod.PASSWORD -> listOf(Triple(PASSWORD_FIELD, password, previousCredentials.password))
                SshAuthMethod.PRIVATE_KEY -> listOf(
                    Triple(PRIVATE_KEY_FIELD, privateKey, previousCredentials.privateKey),
                    Triple(PASSPHRASE_FIELD, passphrase, previousCredentials.passphrase),
                )
            }
            retainedFields.forEach { (field, replacement, previous) ->
                check(replacement != null || previous != null || !secretStore.has(credentialDescriptor(existing, field))) {
                    "SSH credentials cannot be decrypted. Enter them again before saving."
                }
            }
        }
        val suppliedCredentials = SshCredentials(
            password = password ?: previousCredentials.password,
            privateKey = privateKey ?: previousCredentials.privateKey,
            passphrase = when {
                passphrase == null -> previousCredentials.passphrase
                passphrase.isEmpty() -> null
                else -> passphrase
            },
        )
        val credentials = when (profile.authMethod) {
            SshAuthMethod.PASSWORD -> SshCredentials(
                password = suppliedCredentials.password,
                privateKey = null,
                passphrase = null,
            )
            SshAuthMethod.PRIVATE_KEY -> SshCredentials(
                password = null,
                privateKey = suppliedCredentials.privateKey,
                passphrase = suppliedCredentials.passphrase,
            )
        }
        validateSecret(credentials.password, "password")
        validateSecret(credentials.privateKey, "private key")
        validatePassphrase(credentials.passphrase)
        val now = System.currentTimeMillis()
        val credentialRevision = newCredentialRevision()
        val persistedProfile = profile.copy(
            createdAtMs = existing?.createdAtMs ?: profile.createdAtMs,
            acceptedHostKeyFingerprint = when {
                existing == null -> null
                endpointChanged -> null
                else -> existing.acceptedHostKeyFingerprint
            },
            updatedAtMs = now,
            credentialRevision = credentialRevision,
        )

        val newDescriptors = buildList {
            if (credentials.password != null) add(credentialDescriptor(profile.id, credentialRevision, PASSWORD_FIELD))
            if (credentials.privateKey != null) add(credentialDescriptor(profile.id, credentialRevision, PRIVATE_KEY_FIELD))
            if (credentials.passphrase != null) add(credentialDescriptor(profile.id, credentialRevision, PASSPHRASE_FIELD))
        }
        credentials.password?.let { writeCredential(profile.id, credentialRevision, PASSWORD_FIELD, it) }
        credentials.privateKey?.let { writeCredential(profile.id, credentialRevision, PRIVATE_KEY_FIELD, it) }
        credentials.passphrase?.let { writeCredential(profile.id, credentialRevision, PASSPHRASE_FIELD, it) }

        // Publish metadata only after the complete new credential generation is ready.
        dataStore.edit { preferences ->
            val current = decodeState(preferences[SSH_PROFILES_KEY])
            val updatedProfiles = if (existing == null) {
                current.profiles + persistedProfile
            } else {
                current.profiles.map { stored ->
                    if (stored.id == profile.id) persistedProfile else stored
                }
            }
            val defaultId = current.defaultProfileId
                ?.takeIf { id -> updatedProfiles.any { it.id == id } }
                ?: updatedProfiles.firstOrNull()?.id
            preferences[SSH_PROFILES_KEY] = JsonInstant.encodeToString(
                SshProfilesState(
                    profiles = updatedProfiles,
                    defaultProfileId = defaultId,
                )
            )
        }
        // A cancelled/failed publication may already have committed. Never remove
        // the candidate generation on failure. A later successful save or deletion
        // reclaims encrypted orphans without risking the active credentials.
        deleteSecretsBestEffort(secretStore.listOrphans(emptySet()).filter {
            it.scope == SCOPE && it.ownerId == profile.id && it !in newDescriptors
        })
    }

    /** Remove one profile and its owned SSH secrets. */
    suspend fun delete(id: String) = mutationMutex.withLock {
        require(id.isNotBlank()) { "id must not be blank" }
        val before = snapshot()
        if (before.profiles.none { it.id == id }) return@withLock

        // Remove metadata first. If the process dies before cleanup, only
        // encrypted SSH-owned orphans remain and can never be resolved by id.
        dataStore.edit { preferences ->
            val current = decodeState(preferences[SSH_PROFILES_KEY])
            val remaining = current.profiles.filterNot { it.id == id }
            val defaultId = current.defaultProfileId
                ?.takeIf { default -> remaining.any { it.id == default } }
                ?: remaining.firstOrNull()?.id
            if (remaining.isEmpty()) {
                preferences.remove(SSH_PROFILES_KEY)
            } else {
                preferences[SSH_PROFILES_KEY] = JsonInstant.encodeToString(
                    SshProfilesState(remaining, defaultId)
                )
            }
        }

        // Do not use a global orphan sweep here: another profile (or another
        // feature using SecretStore) must not lose its credentials.
        secretStore.listOrphans(emptySet())
            .filter { it.scope == SCOPE && it.ownerId == id }
            .forEach { descriptor -> runCatching { secretStore.delete(descriptor) } }
    }

    /** Select an existing profile as the default. */
    suspend fun selectDefault(id: String) = mutationMutex.withLock {
        require(id.isNotBlank()) { "id must not be blank" }
        dataStore.edit { preferences ->
            val current = decodeState(preferences[SSH_PROFILES_KEY])
            check(current.profiles.any { it.id == id }) { "SSH profile not found: $id" }
            if (current.defaultProfileId != id) {
                preferences[SSH_PROFILES_KEY] = JsonInstant.encodeToString(
                    current.copy(defaultProfileId = id)
                )
            }
        }
    }

    /**
     * Accept a probed host key only when the caller still holds the exact
     * profile snapshot it inspected. This prevents accepting a key for a
     * changed endpoint or authentication configuration.
     */
    suspend fun acceptHostKey(expectedProfile: SshProfile, fingerprint: String) =
        mutationMutex.withLock {
            validateProfile(expectedProfile)
            val normalized = SshTrustPolicy.normalizeFingerprint(fingerprint)
            dataStore.edit { preferences ->
                val current = decodeState(preferences[SSH_PROFILES_KEY])
                val stored = current.profiles.firstOrNull { it.id == expectedProfile.id }
                    ?: error("SSH profile not found: ${expectedProfile.id}")
                check(stored == expectedProfile) {
                    "SSH profile changed while accepting host key: ${expectedProfile.id}"
                }
                preferences[SSH_PROFILES_KEY] = JsonInstant.encodeToString(
                    current.copy(
                        profiles = current.profiles.map { profile ->
                            if (profile.id == expectedProfile.id) {
                                profile.copy(
                                    acceptedHostKeyFingerprint = normalized,
                                    updatedAtMs = System.currentTimeMillis(),
                                )
                            } else {
                                profile
                            }
                        }
                    )
                )
            }
        }

    /** Resolve an explicit id, or the persisted default id, without loading secrets. */
    suspend fun resolve(id: String? = null): SshProfile {
        val current = snapshot()
        val profileId = id ?: current.defaultProfileId
            ?: error("No default SSH profile configured")
        return current.profiles.firstOrNull { it.id == profileId }
            ?: error("SSH profile not found: $profileId")
    }

    /** Load runtime-only credentials; callers must not persist or log this object. */
    fun readCredentials(profile: SshProfile): SshCredentials = SshCredentials(
        password = secretStore.read(credentialDescriptor(profile, PASSWORD_FIELD)),
        privateKey = secretStore.read(credentialDescriptor(profile, PRIVATE_KEY_FIELD)),
        passphrase = secretStore.read(credentialDescriptor(profile, PASSPHRASE_FIELD)),
    )

    /**
     * Capture credentials only while the persisted profile is still equal to
     * [profile]. A runtime should use this before opening a connection and
     * retain the returned immutable values for that connection.
     */
    suspend fun credentialsFor(profile: SshProfile): SshCredentials =
        mutationMutex.withLock {
            check(snapshot().profiles.firstOrNull { it.id == profile.id } == profile) {
                "SSH profile changed before credentials were read: ${profile.id}"
            }
            readCredentials(profile)
        }

    private fun validateProfile(profile: SshProfile) {
        require(PROFILE_ID_PATTERN.matches(profile.id)) { "invalid SSH profile id" }
        require(profile.name.length <= MAX_NAME_LENGTH) { "SSH profile name is too long" }
        require(profile.name == profile.name.trim()) { "SSH profile name must not be padded" }
        require(profile.name.none { it.isISOControl() }) { "SSH profile name contains control characters" }
        require(profile.host.length <= MAX_HOST_LENGTH) { "SSH host is too long" }
        require(profile.host == profile.host.trim()) { "SSH host must not be padded" }
        require(profile.host.none { it.isWhitespace() || it.isISOControl() }) {
            "SSH host contains whitespace"
        }
        require('/' !in profile.host && '\\' !in profile.host) { "SSH host contains a path separator" }
        require(profile.username.length <= MAX_USERNAME_LENGTH) { "SSH username is too long" }
        require(profile.username == profile.username.trim()) { "SSH username must not be padded" }
        require(profile.username.none { it.isWhitespace() || it.isISOControl() }) {
            "SSH username contains whitespace"
        }
    }

    private fun validateSecret(value: String?, label: String) {
        if (value == null) return
        require(value.isNotBlank()) { "SSH $label must not be blank" }
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES) {
            "SSH $label is too large"
        }
    }

    private fun validatePassphrase(value: String?) {
        if (value == null || value.isEmpty()) return
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES) {
            "SSH passphrase is too large"
        }
    }

    private fun writeCredential(profileId: String, revision: String, field: String, value: String) {
        val descriptor = credentialDescriptor(profileId, revision, field)
        secretStore.update(descriptor, value)
        check(secretStore.read(descriptor) == value) {
            "Unable to verify SSH $field credential"
        }
    }

    private fun credentialDescriptor(profile: SshProfile, field: String): SecretDescriptor =
        credentialDescriptor(profile.id, profile.credentialRevision, field)

    private fun credentialDescriptor(
        profileId: String,
        revision: String,
        field: String,
    ): SecretDescriptor {
        require(PROFILE_ID_PATTERN.matches(profileId)) { "invalid SSH profile id" }
        require(revision.isBlank() || REVISION_PATTERN.matches(revision)) {
            "invalid SSH credential revision"
        }
        return SecretDescriptor(
            scope = SCOPE,
            ownerId = profileId,
            fieldName = if (revision.isBlank()) field else "$revision.$field",
        )
    }

    private fun deleteSecretsBestEffort(descriptors: Iterable<SecretDescriptor>) {
        descriptors.distinct().forEach { descriptor ->
            runCatching { secretStore.delete(descriptor) }
        }
    }

    private fun decodeState(raw: String?): SshProfilesState =
        raw?.let { JsonInstant.decodeFromString<SshProfilesState>(it) }
            ?: SshProfilesState()

    private companion object {
        const val SCOPE = "ssh"
        const val PASSWORD_FIELD = "password"
        const val PRIVATE_KEY_FIELD = "privateKey"
        const val PASSPHRASE_FIELD = "passphrase"
        const val MAX_SECRET_BYTES = 64 * 1024
        const val MAX_NAME_LENGTH = 128
        const val MAX_USERNAME_LENGTH = 128
        const val MAX_HOST_LENGTH = 255
        val PROFILE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val REVISION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SSH_PROFILES_KEY = stringPreferencesKey("ssh_profiles")

        fun newCredentialRevision(): String = UUID.randomUUID().toString()
    }
}
