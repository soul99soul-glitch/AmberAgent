package app.amber.feature.tools

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.ssh.SshCredentialPresence
import app.amber.core.settings.ssh.SshKeyStore
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.terminal.SshAuthMethod
import app.amber.feature.terminal.SshHostTrust
import app.amber.feature.terminal.SshKey
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.SshProfilesState
import app.amber.feature.terminal.SshRouteError
import app.amber.feature.terminal.SshRouteException
import app.amber.feature.terminal.SshRouteResolver
import app.amber.feature.terminal.SshTrustPolicy
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Agent-callable SSH profile management, backed by the same [SshProfileStore]
 * singleton the Settings screen uses — every change shows up there immediately
 * through the store's `state` Flow.
 *
 * Credentials are write-only. The create/update tools accept a password,
 * private key or passphrase and hand it to the store's existing SecretStore
 * path; every result reports presence booleans only. The three mutating tools
 * are High risk with `allowsAutoApproval = false`, so ordinary auto-approval
 * never releases them while the explicit high-risk auto-approval switch still
 * runs them unattended — the same contract as `terminal_execute`.
 */
class SshProfileTools(
    private val sshProfileStore: SshProfileStore,
) {
    fun getTools(): List<Tool> = listOf(
        listTool(),
        createTool(),
        updateTool(),
        deleteTool(),
    )

    private fun listTool() = Tool(
        name = TOOL_LIST,
        description = "List saved SSH profiles: id, name, host, port, username, auth method, " +
            "whether the SSH host key was accepted, the default profile, and whether a " +
            "password / private key / passphrase is stored (booleans only). Credential values " +
            "are never returned. Read-only, no approval needed.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val state = sshProfileStore.snapshot()
            val keysById = sshProfileStore.keyStore.snapshot().keys.associateBy { it.id }
            val profilesById = state.profiles.associateBy { it.id }
            textJson {
                put("status", "ok")
                state.defaultProfileId?.let { put("default_profile_id", it) }
                put("profiles", buildJsonArray {
                    state.profiles.forEach { profile ->
                        add(
                            profile.toToolJson(
                                presence = sshProfileStore.credentialPresence(profile, keysById),
                                allProfiles = profilesById,
                                keysById = keysById,
                                store = sshProfileStore,
                            ),
                        )
                    }
                })
            }
        },
    )

    private fun createTool() = Tool(
        name = TOOL_CREATE,
        description = "Create an SSH profile. Requires name, host, username and the credential for " +
            "the chosen auth_method: password for \"password\", or key_id (recommended, from " +
            "ssh_key_list / ssh_key_generate / ssh_key_import) or private_key for \"key\" / \"private_key\". " +
            "还没有密钥时，先调用 ssh_key_generate，把公钥交给用户，加到服务器的 authorized_keys 后再创建 profile / " +
            "When no key exists yet, call ssh_key_generate first, give the public key to the user " +
            "to add to the server's authorized_keys, and then create the profile with key_id. " +
            "Optionally set jump_profile_id to route through a bastion/jump host (ProxyJump, up to 3 jump hosts). " +
            "Credentials go into the encrypted SecretStore and are never returned.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", stringProp("Display name, for example prod-web. Must be unique."))
                    put("host", stringProp("Hostname or IP address, no whitespace."))
                    put("port", integerProp("SSH port, 1-65535. Defaults to 22."))
                    put("username", stringProp("Login user name, no whitespace."))
                    put("auth_method", stringProp("Authentication method: password, key, or private_key. Defaults to password (or key when key_id is supplied)."))
                    put("key_id", stringProp("Stored SSH key id from ssh_key_list / ssh_key_generate / ssh_key_import for key authentication."))
                    put("jump_profile_id", nullableStringProp("Optional jump host (ProxyJump) profile id from ssh_profile_list. Max 3 jump hosts."))
                    put("password", stringProp("Password when auth_method=password. Stored encrypted, never returned."))
                    put("private_key", stringProp("Legacy inline PEM/OpenSSH private key when auth_method=private_key. Prefer key_id with ssh_key_generate/ssh_key_import."))
                    put("passphrase", stringProp("Passphrase for an inline encrypted private key. Stored encrypted, never returned."))
                    put("make_default", booleanProp("Also select the new profile as the default. Defaults to false."))
                },
                required = listOf("name", "host", "username"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            runCatching {
                val request = SaveRequest.parse(input, requireProfileId = false)
                val name = requireNotNull(request.name) { "name is required" }
                val host = requireNotNull(request.host) { "host is required" }
                val username = requireNotNull(request.username) { "username is required" }
                val authMethod = request.authMethod
                    ?: if (request.keyId != null) SshAuthMethod.PRIVATE_KEY else SshAuthMethod.PASSWORD
                val state = sshProfileStore.snapshot()
                requireNameAvailable(state.profiles, name, exceptId = null)
                requireCredentialFor(authMethod, request, existingKeyId = null)
                requireCredentialMatchesMethod(authMethod, request)
                val now = System.currentTimeMillis()
                val profile = SshProfile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    host = host,
                    port = request.port ?: DEFAULT_PORT,
                    username = username,
                    authMethod = authMethod,
                    keyId = if (authMethod == SshAuthMethod.PRIVATE_KEY) request.keyId else null,
                    jumpProfileId = request.jumpProfileId?.takeIf { it.isNotEmpty() },
                    createdAtMs = now,
                    updatedAtMs = now,
                )
                sshProfileStore.save(
                    profile = profile,
                    password = request.password,
                    privateKey = request.privateKey,
                    passphrase = request.passphrase,
                )
                if (request.makeDefault) sshProfileStore.selectDefault(profile.id)
                val after = sshProfileStore.snapshot()
                val persisted = after.profiles.firstOrNull { it.id == profile.id }
                    ?: error("SSH profile was not persisted")
                saveResult("created", persisted, after, request.createdFields(authMethod))
            }.getOrElse(::failure)
        },
    )

    private fun updateTool() = Tool(
        name = TOOL_UPDATE,
        description = "Update an SSH profile by id. Omitted fields keep their current value. " +
            "Supply key_id, jump_profile_id (or empty string to clear jump host), or " +
            "password / private_key / passphrase to set or replace a stored credential; " +
            "passphrase=\"\" clears the stored passphrase. " +
            "还没有密钥时，先调用 ssh_key_generate，把公钥交给用户，加到服务器的 authorized_keys 后再更新 profile / " +
            "When no key exists yet, call ssh_key_generate first, give the public key to the user " +
            "to add to the server's authorized_keys, and then update the profile with key_id. " +
            "Changing host, port, or jump_profile_id clears the accepted host key.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", stringProp("Profile id from ssh_profile_list."))
                    put("name", stringProp("New display name. Must not collide with another profile."))
                    put("host", stringProp("New hostname or IP address."))
                    put("port", integerProp("New SSH port, 1-65535."))
                    put("username", stringProp("New login user name."))
                    put("auth_method", stringProp("New authentication method: password, key, or private_key."))
                    put("key_id", stringProp("New stored SSH key id from ssh_key_list, or empty string to clear."))
                    put("jump_profile_id", nullableStringProp("New jump host (ProxyJump) profile id, or empty string to clear."))
                    put("password", stringProp("Replacement password. Stored encrypted, never returned."))
                    put("private_key", stringProp("Replacement inline private key. Stored encrypted, never returned."))
                    put("passphrase", stringProp("Replacement passphrase; an empty string clears it."))
                    put("make_default", booleanProp("Select this profile as the default. Defaults to false."))
                },
                required = listOf("id"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            runCatching {
                val request = SaveRequest.parse(input, requireProfileId = true)
                val state = sshProfileStore.snapshot()
                val existing = state.profiles.firstOrNull { it.id == request.profileId }
                    ?: error("SSH profile not found: ${request.profileId}")
                request.name?.let { requireNameAvailable(state.profiles, it, exceptId = existing.id) }
                val authMethod = request.authMethod
                    ?: if (request.keyId != null && request.keyId.isNotEmpty()) SshAuthMethod.PRIVATE_KEY else existing.authMethod
                val resolvedKeyId = when {
                    authMethod == SshAuthMethod.PASSWORD -> null
                    request.keyId == null -> if (request.privateKey != null) null else existing.keyId
                    request.keyId.isEmpty() -> null
                    else -> request.keyId
                }
                if (authMethod != existing.authMethod) {
                    requireCredentialFor(authMethod, request, existingKeyId = resolvedKeyId)
                }
                requireCredentialMatchesMethod(authMethod, request)
                val resolvedJumpId = when (request.jumpProfileId) {
                    null -> existing.jumpProfileId
                    "" -> null
                    else -> request.jumpProfileId
                }
                val updated = existing.copy(
                    name = request.name ?: existing.name,
                    host = request.host ?: existing.host,
                    port = request.port ?: existing.port,
                    username = request.username ?: existing.username,
                    authMethod = authMethod,
                    keyId = resolvedKeyId,
                    jumpProfileId = resolvedJumpId,
                    updatedAtMs = System.currentTimeMillis(),
                )
                sshProfileStore.save(
                    profile = updated,
                    password = request.password,
                    privateKey = request.privateKey,
                    passphrase = request.passphrase,
                )
                if (request.makeDefault) sshProfileStore.selectDefault(updated.id)
                val after = sshProfileStore.snapshot()
                val persisted = after.profiles.firstOrNull { it.id == updated.id }
                    ?: error("SSH profile was not persisted")
                saveResult("updated", persisted, after, request.changedFields(existing, persisted))
            }.getOrElse(::failure)
        },
    )

    private fun deleteTool() = Tool(
        name = TOOL_DELETE,
        description = "Delete an SSH profile by id together with its stored credentials. Same " +
            "behavior as deleting it in Settings: terminal jobs that are already running keep " +
            "the credentials they loaded for their connection, and later use of this id fails " +
            "until the profile is recreated.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", stringProp("Profile id from ssh_profile_list."))
                },
                required = listOf("id"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            runCatching {
                val id = input.requiredString("id").trim()
                require(id.isNotEmpty()) { "id is required" }
                val state = sshProfileStore.snapshot()
                val existing = state.profiles.firstOrNull { it.id == id }
                    ?: error("SSH profile not found: $id")
                sshProfileStore.delete(id)
                val after = sshProfileStore.snapshot()
                textJson {
                    put("status", "deleted")
                    put("id", existing.id)
                    put("name", existing.name)
                    put("credentials_removed", true)
                    put("remaining_profiles", after.profiles.size)
                    after.defaultProfileId?.let { put("default_profile_id", it) }
                }
            }.getOrElse(::failure)
        },
    )

    private suspend fun saveResult(
        status: String,
        profile: SshProfile,
        state: SshProfilesState,
        changedFields: List<String>,
    ): List<UIMessagePart> {
        val keysById = sshProfileStore.keyStore.snapshot().keys.associateBy { it.id }
        val profilesById = state.profiles.associateBy { it.id }
        return textJson {
            put("status", status)
            put(
                "profile",
                profile.toToolJson(
                    presence = sshProfileStore.credentialPresence(profile, keysById),
                    allProfiles = profilesById,
                    keysById = keysById,
                    store = sshProfileStore,
                ),
            )
            put("changed_fields", buildJsonArray { changedFields.forEach { add(JsonPrimitive(it)) } })
            state.defaultProfileId?.let { put("default_profile_id", it) }
            put("note", "Credential values are stored encrypted and are never returned.")
        }
    }

    private fun failure(error: Throwable): List<UIMessagePart> {
        if (error is kotlinx.coroutines.CancellationException) throw error
        val routeError = (error as? SshRouteException)?.error
        if (routeError is SshRouteError.InUseAsJump) {
            return textJson {
                put("status", "rejected")
                put("code", routeError.code)
                put("error_code", routeError.code)
                put("profile_id", routeError.profileId)
                put("dependent_profile_ids", buildJsonArray {
                    routeError.dependentProfileIds.forEach { add(JsonPrimitive(it)) }
                })
                put("dependent_profiles", buildJsonArray {
                    routeError.dependentProfileIds.forEach { add(JsonPrimitive(it)) }
                })
                put("error", routeError.message)
                put("recoverable", true)
            }
        }
        return textJson {
            put("status", "rejected")
            if (routeError != null) {
                put("code", routeError.code)
                put("error_code", routeError.code)
                if (routeError is SshRouteError.JumpCycle) {
                    put("cycle", buildJsonArray { routeError.cycle.forEach { add(JsonPrimitive(it)) } })
                }
                if (routeError is SshRouteError.JumpChainTooLong) {
                    put("max_jump_hosts", routeError.maxJumpHosts)
                    put("chain", buildJsonArray { routeError.chain.forEach { add(JsonPrimitive(it)) } })
                }
                put("error", "${routeError.code}: ${routeError.message}")
            } else {
                put("error", error.message ?: error::class.simpleName.orEmpty())
            }
            put("recoverable", true)
        }
    }

    /** Create/update arguments. [toString] never reveals a credential. */
    private class SaveRequest(
        val profileId: String?,
        val name: String?,
        val host: String?,
        val port: Int?,
        val username: String?,
        val authMethod: SshAuthMethod?,
        val keyId: String?,
        val jumpProfileId: String?,
        val password: String?,
        val privateKey: String?,
        val passphrase: String?,
        val makeDefault: Boolean,
    ) {
        fun createdFields(effectiveAuthMethod: SshAuthMethod): List<String> = buildList {
            add("name")
            add("host")
            add("port")
            add("username")
            add("auth_method")
            if (!keyId.isNullOrEmpty()) add("key_id")
            if (!jumpProfileId.isNullOrEmpty()) add("jump_profile_id")
            if (password != null) add("password")
            if (privateKey != null) add("private_key")
            if (passphrase != null && effectiveAuthMethod == SshAuthMethod.PRIVATE_KEY) add("passphrase")
            if (makeDefault) add("make_default")
        }

        fun changedFields(before: SshProfile, after: SshProfile): List<String> = buildList {
            if (before.name != after.name) add("name")
            if (before.host != after.host) add("host")
            if (before.port != after.port) add("port")
            if (before.username != after.username) add("username")
            if (before.authMethod != after.authMethod) add("auth_method")
            if (before.keyId != after.keyId) add("key_id")
            if (before.jumpProfileId != after.jumpProfileId) add("jump_profile_id")
            if (password != null) add("password")
            if (privateKey != null) add("private_key")
            if (passphrase != null && after.authMethod == SshAuthMethod.PRIVATE_KEY) add("passphrase")
            if (makeDefault) add("make_default")
        }

        override fun toString(): String =
            "SaveRequest(profileId=$profileId, name=$name, host=$host, port=$port, " +
                "username=$username, authMethod=$authMethod, keyId=$keyId, " +
                "jumpProfileId=$jumpProfileId, makeDefault=$makeDefault, credentials=<redacted>)"

        companion object {
            fun parse(input: JsonElement, requireProfileId: Boolean): SaveRequest {
                val root = runCatching { input.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                val id = root.optionalString("id")
                if (requireProfileId) require(id != null) { "id is required" }
                val authMethod = root.optionalString("auth_method")?.let { raw ->
                    SshAuthMethod.fromWire(raw)
                        ?: error("auth_method must be password, key, or private_key")
                }
                return SaveRequest(
                    profileId = id,
                    name = root.optionalString("name"),
                    host = root.optionalString("host"),
                    port = root.port(),
                    username = root.optionalString("username"),
                    authMethod = authMethod,
                    keyId = root.clearableString("key_id")?.takeIf { !requireProfileId || root.containsKey("key_id") }
                        ?.let { if (!requireProfileId) it.ifEmpty { null } else it },
                    jumpProfileId = root.clearableString("jump_profile_id")?.let {
                        if (!requireProfileId) it.ifEmpty { null } else it
                    },
                    password = root.secret("password"),
                    privateKey = root.secret("private_key"),
                    passphrase = root.passphraseArgument(),
                    makeDefault = root.booleanOrNull("make_default") ?: false,
                )
            }
        }
    }

    /**
     * The Settings editor requires the credential of the chosen method when a
     * profile is created or its auth method changes.
     */
    private fun requireCredentialFor(
        authMethod: SshAuthMethod,
        request: SaveRequest,
        existingKeyId: String?,
    ) {
        when (authMethod) {
            SshAuthMethod.PASSWORD ->
                require(request.password != null) { "password is required for auth_method=password" }
            SshAuthMethod.PRIVATE_KEY ->
                require(!request.keyId.isNullOrEmpty() || request.privateKey != null || !existingKeyId.isNullOrEmpty()) {
                    "key_id or private_key is required for auth_method=private_key"
                }
        }
    }

    /** Reject a credential that does not belong to the profile's auth method. */
    private fun requireCredentialMatchesMethod(authMethod: SshAuthMethod, request: SaveRequest) {
        when (authMethod) {
            SshAuthMethod.PASSWORD -> {
                require(request.keyId.isNullOrEmpty()) { "key_id cannot be set when auth_method=password" }
                require(request.privateKey == null) { "private_key cannot be set when auth_method=password" }
                require(request.passphrase == null) { "passphrase requires auth_method=private_key" }
            }
            SshAuthMethod.PRIVATE_KEY -> {
                require(request.password == null) { "password cannot be set when auth_method=private_key" }
                require(request.keyId.isNullOrEmpty() || request.privateKey == null) {
                    "Specify either key_id or private_key, not both."
                }
            }
        }
    }

    companion object {
        const val TOOL_LIST = "ssh_profile_list"
        const val TOOL_CREATE = "ssh_profile_create"
        const val TOOL_UPDATE = "ssh_profile_update"
        const val TOOL_DELETE = "ssh_profile_delete"

        /** Every tool this class registers. */
        val TOOL_NAMES = setOf(TOOL_LIST, TOOL_CREATE, TOOL_UPDATE, TOOL_DELETE)

        /** Argument keys that carry a credential and must never be surfaced. */
        val CREDENTIAL_KEYS = setOf("password", "private_key", "passphrase")

        private const val DEFAULT_PORT = 22
    }
}

/** The display mask prefix a model may echo back from a redacted transcript. */
private const val SENSITIVE_MASK_PREFIX = "••••"

/**
 * The storage-boundary placeholder a redacted transcript shows in place of a
 * credential. Kept in sync with `CREDENTIAL_REDACTION` in :app, which writes it.
 */
private const val REDACTION_PLACEHOLDER = "<redacted>"

private fun SshProfile.toToolJson(
    presence: SshCredentialPresence,
    allProfiles: Map<String, SshProfile> = mapOf(id to this),
    keysById: Map<String, SshKey> = emptyMap(),
    store: SshProfileStore? = null,
) = buildJsonObject {
    val routeResult = runCatching { SshRouteResolver.resolveRoute(id, allProfiles) }
    val resolvedRoute = routeResult.getOrNull().orEmpty()
    val routeReady = routeResult.isSuccess && resolvedRoute.all { hop ->
        val hopPresence = store?.credentialPresence(hop, keysById) ?: presence
        val hasCred = when (hop.authMethod) {
            SshAuthMethod.PASSWORD -> hopPresence.hasPassword
            SshAuthMethod.PRIVATE_KEY -> hopPresence.hasPrivateKey
        }
        val hopTrusted = SshTrustPolicy.evaluate(hop, hop.acceptedHostKeyFingerprint) is SshHostTrust.Trusted
        hasCred && hopTrusted
    }
    put("id", id)
    put("name", name)
    put("host", host)
    put("port", port)
    put("username", username)
    put("auth_method", authMethod.wireName)
    keyId?.let { key ->
        put("key_id", key)
        keysById[key]?.let { put("key_fingerprint", it.fingerprintSha256) }
    }
    jumpProfileId?.let { put("jump_profile_id", it) }
    put("route", buildJsonArray { resolvedRoute.forEach { add(JsonPrimitive(it.name)) } })
    put("route_ids", buildJsonArray { resolvedRoute.forEach { add(JsonPrimitive(it.id)) } })
    put("route_ready", routeReady)
    put("host_key_accepted", SshTrustPolicy.evaluate(this@toToolJson, acceptedHostKeyFingerprint) is SshHostTrust.Trusted)
    put("has_password", presence.hasPassword)
    put("has_private_key", presence.hasPrivateKey)
    put("has_passphrase", presence.hasPassphrase)
    put("updated_at_ms", updatedAtMs)
}

private fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun integerProp(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun booleanProp(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

/** Same rule as the Settings editor: a profile name must identify one profile. */
private fun requireNameAvailable(
    profiles: List<SshProfile>,
    name: String,
    exceptId: String?,
) {
    val clash = profiles.firstOrNull {
        it.id != exceptId && it.name.equals(name, ignoreCase = true)
    }
    require(clash == null) { "SSH profile name already exists: ${clash?.name}" }
}

private fun JsonObject.optionalString(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.clearableString(key: String): String? =
    if (get(key) == kotlinx.serialization.json.JsonNull) "" else get(key)?.jsonPrimitive?.contentOrNull?.trim()

/** Non-blank credential value; an empty string means "not supplied". */
private fun JsonObject.secret(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotEmpty() }
        ?.also { requireNotRedactionPlaceholder(key, it) }

/**
 * Passphrase argument with the Settings editor's semantics: absent keeps the
 * stored value, an explicit empty string clears it, anything else replaces it.
 */
private fun JsonObject.passphraseArgument(): String? {
    val element = get("passphrase") ?: return null
    val value = (element as? JsonPrimitive)?.contentOrNull ?: error("passphrase must be a string")
    if (value.isNotEmpty()) requireNotRedactionPlaceholder("passphrase", value)
    return value
}

/**
 * The transcript hides credentials, so a model echoing an earlier call can hand
 * back the mask instead of a real secret. Writing that placeholder would
 * silently overwrite a working credential, so it is rejected outright.
 */
private fun requireNotRedactionPlaceholder(key: String, value: String) {
    val trimmed = value.trim()
    require(!trimmed.equals(REDACTION_PLACEHOLDER, ignoreCase = true)) {
        "$key is the redaction placeholder, not a credential. Ask the user for the real value."
    }
    require(!trimmed.startsWith(SENSITIVE_MASK_PREFIX)) {
        "$key looks like a masked credential, not a real value. Ask the user for the real value."
    }
    require(!SshKeyStore.isMaskedOrRedactedPlaceholder(trimmed)) {
        "$key looks like a masked or redacted placeholder, not a real value. Ask the user for the real value."
    }
}

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    get(key)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.port(): Int? {
    val raw = get("port")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val port = raw.toIntOrNull()
    require(port != null && port in 1..65535) { "port must be an integer between 1 and 65535" }
    return port
}

private fun nullableStringProp(description: String) = buildJsonObject {
    put("type", buildJsonArray { add(JsonPrimitive("string")); add(JsonPrimitive("null")) })
    put("description", description)
}
