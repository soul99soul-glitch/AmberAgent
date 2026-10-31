package app.amber.core.settings.ssh

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretStore
import app.amber.feature.terminal.SshKey
import app.amber.feature.terminal.SshKeysState
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.SshRouteError
import app.amber.feature.terminal.SshRouteException
import app.amber.feature.terminal.SshRouteResolver
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Runtime-only private key material loaded from [SecretStore] for an [SshKey].
 * Never persist or log raw fields; [toString] is always redacted.
 */
class SshKeyMaterial(
    val privateKey: String,
    val passphrase: String? = null,
) {
    override fun toString(): String =
        "SshKeyMaterial(privateKey=<redacted>, passphrase=${if (passphrase == null) "null" else "<redacted>"})"
}

/**
 * Durable storage for local SSH keys.
 *
 * Non-sensitive key metadata (`SshKey`) lives in `DataStore` under `ssh_keys`;
 * private key PEM/OpenSSH text and optional passphrase live in [SecretStore]
 * under scope `"ssh_key"`.
 */
class SshKeyStore(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
) {
    // Shared with SshProfileStore: reference validation and key deletion must
    // serialize with profile writes, rather than trusting a caller's UI snapshot.
    internal val mutationMutex = Mutex()

    val state: Flow<SshKeysState> = dataStore.data.map { preferences ->
        decodeState(preferences[SSH_KEYS_PREF_KEY])
    }.flowOn(Dispatchers.Default)

    suspend fun snapshot(): SshKeysState = state.first()

    suspend fun findById(keyId: String): SshKey? =
        snapshot().keys.firstOrNull { it.id == keyId }

    suspend fun resolve(keyId: String): SshKey =
        findById(keyId) ?: throw SshRouteException(SshRouteError.KeyNotFound(keyId))

    /**
     * Persist a generated or imported SSH key and its private key material.
     */
    suspend fun save(
        key: SshKey,
        privateKey: String,
        passphrase: String? = null,
    ): SshKey = mutationMutex.withLock {
        validateKeyMetadata(key)
        validateSecret(privateKey, "private key")
        validatePassphrase(passphrase)

        val currentSnapshot = snapshot()
        require(currentSnapshot.keys.none { it.id == key.id }) { "SSH keys are immutable; import a new key instead." }
        val colliding = currentSnapshot.keys.firstOrNull {
            it.id != key.id && it.name.equals(key.name, ignoreCase = true)
        }
        require(colliding == null) {
            "An SSH key named '${key.name}' already exists (id=${colliding?.id})."
        }

        val normalizedPassphrase = passphrase?.takeIf { it.isNotEmpty() }
        val revision = newSecretRevision()
        val persistedKey = key.copy(
            hasPassphrase = normalizedPassphrase != null,
            secretRevision = revision,
        )

        val privateKeyDesc = secretDescriptor(key.id, revision, PRIVATE_KEY_FIELD)
        val newDescriptors = mutableListOf(privateKeyDesc)
        writeSecret(privateKeyDesc, privateKey, "private key")

        if (normalizedPassphrase != null) {
            val passphraseDesc = secretDescriptor(key.id, revision, PASSPHRASE_FIELD)
            newDescriptors += passphraseDesc
            writeSecret(passphraseDesc, normalizedPassphrase, "passphrase")
        }

        dataStore.edit { preferences ->
            val current = decodeState(preferences[SSH_KEYS_PREF_KEY])
            val exists = current.keys.any { it.id == persistedKey.id }
            val updated = if (exists) {
                current.keys.map { if (it.id == persistedKey.id) persistedKey else it }
            } else {
                current.keys + persistedKey
            }
            preferences[SSH_KEYS_PREF_KEY] = JsonInstant.encodeToString(SshKeysState(updated))
        }

        deleteSecretsBestEffort(
            secretStore.listOrphans(emptySet()).filter {
                it.scope == SCOPE && it.ownerId == key.id && it !in newDescriptors
            },
        )
        persistedKey
    }

    /**
     * Delete a stored SSH key only after reading the current profile references.
     * Throws [SshRouteException] with [SshRouteError.KeyInUse] if referenced.
     *
     * @return true if the key existed and was removed, false if it was absent.
     */
    suspend fun delete(
        keyId: String,
    ): Boolean = mutationMutex.withLock {
        require(keyId.isNotBlank()) { "key_id must not be blank" }
        val activeProfiles = dataStore.data.first()[stringPreferencesKey("ssh_profiles")]
            ?.let { JsonInstant.decodeFromString<app.amber.feature.terminal.SshProfilesState>(it).profiles }
            .orEmpty()
        val referencing = SshRouteResolver.profilesUsingKey(keyId, activeProfiles)
        if (referencing.isNotEmpty()) {
            throw SshRouteException(SshRouteError.KeyInUse(keyId, referencing))
        }
        val before = snapshot()
        if (before.keys.none { it.id == keyId }) return@withLock false

        dataStore.edit { preferences ->
            val current = decodeState(preferences[SSH_KEYS_PREF_KEY])
            val remaining = current.keys.filterNot { it.id == keyId }
            if (remaining.isEmpty()) {
                preferences.remove(SSH_KEYS_PREF_KEY)
            } else {
                preferences[SSH_KEYS_PREF_KEY] = JsonInstant.encodeToString(SshKeysState(remaining))
            }
        }

        deleteSecretsBestEffort(
            secretStore.listOrphans(emptySet()).filter {
                it.scope == SCOPE && it.ownerId == keyId
            },
        )
        true
    }

    fun hasPrivateKey(key: SshKey): Boolean =
        secretStore.has(secretDescriptor(key, PRIVATE_KEY_FIELD))

    fun hasPassphrase(key: SshKey): Boolean =
        secretStore.has(secretDescriptor(key, PASSPHRASE_FIELD))

    suspend fun hasPrivateKeyById(keyId: String): Boolean =
        findById(keyId)?.let(::hasPrivateKey) ?: false

    suspend fun hasPassphraseById(keyId: String): Boolean =
        findById(keyId)?.let(::hasPassphrase) ?: false

    fun readMaterial(key: SshKey): SshKeyMaterial {
        val privateKey = secretStore.read(secretDescriptor(key, PRIVATE_KEY_FIELD))
            ?: error("SSH private key material is missing for key: ${key.id}")
        val passphrase = secretStore.read(secretDescriptor(key, PASSPHRASE_FIELD))
        return SshKeyMaterial(privateKey = privateKey, passphrase = passphrase)
    }

    suspend fun readMaterialById(keyId: String): SshKeyMaterial? =
        findById(keyId)?.let(::readMaterial)

    suspend fun materialFor(keyId: String): SshKeyMaterial = mutationMutex.withLock {
        val key = resolve(keyId)
        readMaterial(key)
    }

    private fun validateKeyMetadata(key: SshKey) {
        require(!key.requireUserPresence) { "Per-use user presence is not supported by Android SSH keys." }
        require(KEY_ID_PATTERN.matches(key.id)) { "invalid SSH key id" }
        require(key.name.length <= MAX_NAME_LENGTH) { "SSH key name is too long" }
        require(key.name == key.name.trim()) { "SSH key name must not be padded" }
        require(key.name.none { it.isISOControl() }) { "SSH key name contains control characters" }
    }

    private fun validateSecret(value: String?, label: String) {
        require(!value.isNullOrBlank()) { "SSH $label must not be blank" }
        require(!isMaskedOrRedactedPlaceholder(value)) {
            "SSH $label looks like a masked or redacted placeholder, not a real credential"
        }
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES) {
            "SSH $label exceeds maximum size of $MAX_SECRET_BYTES bytes"
        }
    }

    private fun validatePassphrase(value: String?) {
        if (value.isNullOrEmpty()) return
        require(!isMaskedOrRedactedPlaceholder(value)) {
            "SSH passphrase looks like a masked or redacted placeholder, not a real credential"
        }
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES) {
            "SSH passphrase exceeds maximum size of $MAX_SECRET_BYTES bytes"
        }
    }

    private fun writeSecret(descriptor: SecretDescriptor, value: String, label: String) {
        secretStore.update(descriptor, value)
        check(secretStore.read(descriptor) == value) {
            "Unable to verify SSH key $label in SecretStore"
        }
    }

    private fun secretDescriptor(key: SshKey, field: String): SecretDescriptor =
        secretDescriptor(key.id, key.secretRevision, field)

    private fun secretDescriptor(keyId: String, revision: String, field: String): SecretDescriptor {
        require(KEY_ID_PATTERN.matches(keyId)) { "invalid SSH key id" }
        require(revision.isBlank() || REVISION_PATTERN.matches(revision)) {
            "invalid SSH key secret revision"
        }
        return SecretDescriptor(
            scope = SCOPE,
            ownerId = keyId,
            fieldName = if (revision.isBlank()) field else "$revision.$field",
        )
    }

    private fun deleteSecretsBestEffort(descriptors: Iterable<SecretDescriptor>) {
        descriptors.distinct().forEach { descriptor ->
            runCatching { secretStore.delete(descriptor) }
        }
    }

    private fun decodeState(raw: String?): SshKeysState =
        raw?.let { JsonInstant.decodeFromString<SshKeysState>(it) }
            ?: SshKeysState()

    companion object {
        const val SCOPE = "ssh_key"
        const val PRIVATE_KEY_FIELD = "privateKey"
        const val PASSPHRASE_FIELD = "passphrase"
        const val MAX_SECRET_BYTES = 64 * 1024
        const val MAX_NAME_LENGTH = 64
        val KEY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val REVISION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SSH_KEYS_PREF_KEY = stringPreferencesKey("ssh_keys")

        fun isMaskedOrRedactedPlaceholder(value: String): Boolean {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return false
            val lower = trimmed.lowercase()
            if (trimmed.startsWith("••••") || trimmed.all { it == '•' || it == '*' }) return true
            if (lower in REDACTED_PLACEHOLDERS) return true
            return false
        }

        private val REDACTED_PLACEHOLDERS = setOf(
            "<redacted>",
            "[redacted]",
            "(empty)",
            "__masked_by_amberagent_backup__",
        )

        fun newSecretRevision(): String = UUID.randomUUID().toString()
    }
}
