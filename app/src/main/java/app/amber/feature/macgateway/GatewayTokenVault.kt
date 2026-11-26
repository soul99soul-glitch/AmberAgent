package app.amber.feature.macgateway

import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretStore

/**
 * Stores paired Mac/Cloud gateway device tokens in the shared Keystore-backed [SecretStore].
 *
 * Device tokens must never appear in logs, exception messages, WorkManager parameters, or URIs.
 */
class GatewayTokenVault(
    private val secretStore: SecretStore,
) {
    fun read(gatewayId: String): String? {
        if (gatewayId.isBlank()) return null
        return secretStore.read(descriptor(gatewayId))
    }

    /**
     * Persists [token] for [gatewayId] via [SecretStore.update] and immediately reads it back
     * to verify integrity. Throws without including the token if storage or verification fails.
     */
    fun write(gatewayId: String, token: String) {
        require(gatewayId.isNotBlank()) { "gatewayId must not be blank" }
        require(token.isNotBlank()) { "gateway device token must not be blank" }
        val desc = descriptor(gatewayId)
        secretStore.update(desc, token)
        val readBack = secretStore.read(desc)
        if (readBack != token) {
            throw IllegalStateException("Gateway device token read-back verification failed")
        }
    }

    fun delete(gatewayId: String) {
        if (gatewayId.isBlank()) return
        secretStore.delete(descriptor(gatewayId))
    }

    internal fun descriptor(gatewayId: String): SecretDescriptor =
        SecretDescriptor(
            scope = SECRET_SCOPE,
            ownerId = gatewayId,
            fieldName = SECRET_FIELD_NAME,
        )

    companion object {
        const val SECRET_SCOPE = "mac_gateway"
        const val SECRET_FIELD_NAME = "device_token"
    }
}
