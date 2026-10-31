package app.amber.feature.tools

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.ssh.SshKeyStore
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.terminal.SshKey
import app.amber.feature.terminal.SshKeyGenType
import app.amber.feature.terminal.SshKeyPairs
import app.amber.feature.terminal.SshRouteError
import app.amber.feature.terminal.SshRouteException
import app.amber.feature.terminal.SshRouteResolver
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
 * Agent-callable SSH local key management tools (`ssh_key_list`, `ssh_key_generate`,
 * `ssh_key_import`, `ssh_key_delete`), backed by [SshKeyStore] and [SshProfileStore].
 *
 * Private key material never leaves [SshKeyStore] / `SecretStore`; only the single-line
 * OpenSSH public key (`public_key_openssh`) and `SHA256:...` fingerprint are returned.
 */
class SshKeyTools(
    private val sshProfileStore: SshProfileStore,
    private val sshKeyStore: SshKeyStore = sshProfileStore.keyStore,
) {
    fun getTools(): List<Tool> = listOf(
        listTool(),
        generateTool(),
        importTool(),
        deleteTool(),
    )

    private fun listTool() = Tool(
        name = TOOL_LIST,
        description = "List local SSH keys managed by Amber: id, name, algorithm, public_key_openssh, " +
            "fingerprint_sha256, origin, protection, has_passphrase, and used_by_profile_ids. " +
            "Private key material and passphrases are never returned. Read-only, no approval needed.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val keysState = sshKeyStore.snapshot()
            val profiles = sshProfileStore.snapshot().profiles
            textJson {
                put("status", "ok")
                put("keys", buildJsonArray {
                    keysState.keys.forEach { key ->
                        val usedBy = SshRouteResolver.profilesUsingKey(key.id, profiles)
                        add(key.toToolJson(usedBy))
                    }
                })
            }
        },
    )

    private fun generateTool() = Tool(
        name = TOOL_GENERATE,
        description = "Generate a new SSH key pair on device (ed25519 default or ecdsa-p256). " +
            "The private key is stored directly in Android Keystore-wrapped SecretStore and " +
            "never returned to the model or transcript; only the OpenSSH public key line " +
            "(public_key_openssh) and SHA256 fingerprint are returned. " +
            "还没有密钥时，先调用 ssh_key_generate，把公钥交给用户，加到服务器的 authorized_keys 后再创建 profile / " +
            "When no key exists yet, call ssh_key_generate first, give the public key to the user " +
            "to add to the server's authorized_keys, and then create the profile with key_id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", stringProp("Unique display name for the SSH key, for example prod-bastion-key."))
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add(JsonPrimitive("ed25519")); add(JsonPrimitive("ecdsa-p256")) })
                        put("default", "ed25519")
                    })
                },
                required = listOf("name"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            runCatching {
                val root = input.asObject()
                val name = root.optTrimmedString("name")
                    ?: error("name is required")
                val rawType = root.optTrimmedString("type")
                val keyType = SshKeyGenType.fromWire(rawType)
                    ?: error("type must be ed25519 or ecdsa-p256")
                val requireUserPresence = root.optBoolean("require_user_presence") ?: false

                val generated = SshKeyPairs.generate(
                    name = name,
                    type = keyType,
                    requireUserPresence = requireUserPresence,
                )
                val persisted = sshKeyStore.save(
                    key = generated.key,
                    privateKey = generated.privateKeyOpenSsh,
                    passphrase = null,
                )
                textJson {
                    put("status", "generated")
                    put("key", persisted.toToolJson(usedByProfileIds = emptyList()))
                    put(
                        "next_step",
                        "Add public_key_openssh to ~/.ssh/authorized_keys on the target server, then reference this key_id in ssh_profile_create or ssh_profile_update.",
                    )
                }
            }.getOrElse(::failure)
        },
    )

    private fun importTool() = Tool(
        name = TOOL_IMPORT,
        description = "Import an existing OpenSSH or PEM private key (with optional passphrase for encrypted keys) " +
            "into Amber's encrypted SecretStore. Validates the key and passphrase before saving and returns " +
            "only key_id, public_key_openssh, and fingerprint_sha256 — never the private key or passphrase.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", stringProp("Unique display name for the imported SSH key."))
                    put("private_key", stringProp("OpenSSH or PEM private key text. Stored encrypted, never returned."))
                    put("passphrase", stringProp("Optional passphrase for an encrypted private key. Stored encrypted, never returned."))
                },
                required = listOf("name", "private_key"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            runCatching {
                val root = input.asObject()
                val name = root.optTrimmedString("name")
                    ?: error("name is required")
                val privateKey = root.rawSecret("private_key")
                    ?: error("private_key is required")
                val passphrase = root.rawPassphrase("passphrase")
                val requireUserPresence = root.optBoolean("require_user_presence") ?: false

                val parsedKey = SshKeyPairs.parseImportedKey(
                    name = name,
                    privateKeyText = privateKey,
                    passphrase = passphrase,
                    requireUserPresence = requireUserPresence,
                )
                val persisted = sshKeyStore.save(
                    key = parsedKey,
                    privateKey = SshKeyPairs.normalizePemLineEndings(privateKey),
                    passphrase = passphrase,
                )
                textJson {
                    put("status", "imported")
                    put("key", persisted.toToolJson(usedByProfileIds = emptyList()))
                }
            }.getOrElse(::failure)
        },
    )

    private fun deleteTool() = Tool(
        name = TOOL_DELETE,
        description = "Delete a stored local SSH key by key_id. Fails with status=key_in_use if any SSH profile " +
            "still references this key_id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("key_id", stringProp("Key id from ssh_key_list."))
                },
                required = listOf("key_id"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            runCatching {
                val root = input.asObject()
                val keyId = root.optTrimmedString("key_id")
                    ?: root.optTrimmedString("id")
                    ?: error("key_id is required")
                val existing = sshKeyStore.findById(keyId)
                    ?: error("SSH key not found: $keyId")
                sshKeyStore.delete(keyId)
                val remaining = sshKeyStore.snapshot().keys.size
                textJson {
                    put("status", "deleted")
                    put("key_id", existing.id)
                    put("name", existing.name)
                    put("remaining_keys", remaining)
                }
            }.getOrElse(::failure)
        },
    )

    private fun failure(error: Throwable): List<UIMessagePart> {
        if (error is kotlinx.coroutines.CancellationException) throw error
        val routeError = (error as? SshRouteException)?.error
        if (routeError is SshRouteError.KeyInUse) {
            return textJson {
                put("status", "rejected")
                put("code", routeError.code)
                put("error_code", routeError.code)
                put("key_id", routeError.keyId)
                put("used_by_profile_ids", buildJsonArray {
                    routeError.usedByProfileIds.forEach { add(JsonPrimitive(it)) }
                })
                put("dependent_profiles", buildJsonArray {
                    routeError.usedByProfileIds.forEach { add(JsonPrimitive(it)) }
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
            }
            put("error", error.message ?: error::class.simpleName.orEmpty())
            put("recoverable", true)
        }
    }

    companion object {
        const val TOOL_LIST = "ssh_key_list"
        const val TOOL_GENERATE = "ssh_key_generate"
        const val TOOL_IMPORT = "ssh_key_import"
        const val TOOL_DELETE = "ssh_key_delete"

        val TOOL_NAMES = setOf(TOOL_LIST, TOOL_GENERATE, TOOL_IMPORT, TOOL_DELETE)
        val WRITE_TOOL_NAMES = setOf(TOOL_GENERATE, TOOL_IMPORT, TOOL_DELETE)
        val CREDENTIAL_KEYS = setOf("private_key", "passphrase")
    }
}

internal fun SshKey.toToolJson(usedByProfileIds: List<String>) = buildJsonObject {
    put("id", id)
    put("key_id", id)
    put("name", name)
    put("algorithm", algorithm)
    put("public_key", publicKeyOpenSsh)
    put("public_key_openssh", publicKeyOpenSsh)
    put("fingerprint", fingerprintSha256)
    put("fingerprint_sha256", fingerprintSha256)
    put("origin", origin)
    put("protection", protection)
    put("has_passphrase", hasPassphrase)
    put("require_user_presence", requireUserPresence)
    put("created_at_ms", createdAtMs)
    put("used_by_profile_ids", buildJsonArray {
        usedByProfileIds.forEach { add(JsonPrimitive(it)) }
    })
}

private fun JsonElement.asObject(): JsonObject =
    runCatching { jsonObject }.getOrNull() ?: JsonObject(emptyMap())

private fun JsonObject.optTrimmedString(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.optBoolean(key: String): Boolean? =
    get(key)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.rawSecret(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotEmpty() }
        ?.also { value ->
            require(!SshKeyStore.isMaskedOrRedactedPlaceholder(value)) {
                "$key is a masked or redacted placeholder, not a real credential. Ask the user for the real value."
            }
        }

private fun JsonObject.rawPassphrase(key: String): String? {
    val value = (get(key) as? JsonPrimitive)?.contentOrNull ?: return null
    if (value.isEmpty()) return null
    require(!SshKeyStore.isMaskedOrRedactedPlaceholder(value)) {
        "$key is a masked or redacted placeholder, not a real credential. Ask the user for the real value."
    }
    return value
}

private fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun booleanProp(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}
