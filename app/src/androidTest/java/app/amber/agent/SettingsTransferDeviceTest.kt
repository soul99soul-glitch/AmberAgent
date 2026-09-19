package app.amber.agent

import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.di.JevApiKeyDescriptor
import app.amber.core.settings.Settings
import app.amber.core.settings.settingsStore
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.KeystoreSecretCipher
import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretReference
import app.amber.core.settings.secret.SecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One-time, explicitly invoked configuration migration. Never logs values or touches chat tables. */
class SettingsTransferDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val koin get() = GlobalContext.get()
    private val store get() = koin.get<SettingsAggregator>()
    private val secrets get() = koin.get<SecretStore>()
    private val redactor get() = koin.get<SecretRedactor>()
    private val directory: File get() {
        val id = InstrumentationRegistry.getArguments().getString("transferId").orEmpty()
        require(id.matches(Regex("[a-z0-9-]{8,64}"))) { "Explicit transferId required" }
        return File(context.filesDir, "settings-transfer-$id").also { it.mkdirs() }
    }

    private suspend fun settings(): Settings = withTimeout(15_000) { store.settingsFlow.first { !it.init } }

    private suspend fun snapshot(): JsonObject {
        val current = settings()
        val refs = redactor.readRefs(context.settingsStore.data.first()).values.toList()
        val descriptors = refs.map { it.descriptor() }.toSet() + JevApiKeyDescriptor
        return buildJsonObject {
            put("version", 1)
            put("settings", JsonInstant.parseToJsonElement(JsonInstant.encodeToString(current)))
            put("refs", JsonInstant.parseToJsonElement(JsonInstant.encodeToString(refs)))
            put("secrets", buildJsonObject {
                descriptors.forEach { descriptor ->
                    secrets.read(descriptor)?.takeIf { it.isNotBlank() }?.let { put(descriptor.key, it) }
                }
            })
        }
    }

    private fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("amber-settings-transfer-v1".toByteArray())
        return nonce + cipher.doFinal(plaintext)
    }

    private fun readTransfer(): JsonObject {
        val key = File(directory, "transfer.key").readBytes()
        require(key.size == 32)
        val ciphertext = File(directory, "settings.enc").readBytes()
        require(ciphertext.size in 29..16_777_216)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        cipher.updateAAD("amber-settings-transfer-v1".toByteArray())
        val clear = cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
        key.fill(0)
        return try { JsonInstant.parseToJsonElement(clear.decodeToString()).jsonObject }
        finally { clear.fill(0) }
    }

    private fun report(stage: String, payload: JsonObject) {
        val imported = JsonInstant.decodeFromString<Settings>(payload.getValue("settings").toString())
        val values = payload.getValue("secrets").jsonObject
        val summary = buildJsonObject {
            put("stage", stage)
            put("providers", imported.providers.size)
            put("models", imported.providers.sumOf { it.models.size })
            put("secrets", values.size)
            put("jev_key_present", values.containsKey(JevApiKeyDescriptor.key))
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SETTINGS_TRANSFER=$summary\n") })
    }

    @Test
    fun exportConfiguration() = runBlocking {
        require(Build.MODEL == "M610BB") { "Wrong source device" }
        val folder = directory
        check(!File(folder, "settings.enc").exists()) { "Transfer already exported" }
        val payload = snapshot()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val clear = payload.toString().toByteArray()
        try {
            File(folder, "transfer.key").writeBytes(key)
            File(folder, "settings.enc").writeBytes(encrypt(clear, key))
        } finally {
            key.fill(0)
            clear.fill(0)
        }
        // Round-trip verification does not print or assert secret values.
        check(readTransfer() == payload) { "Export integrity verification failed" }
        report("export_verified", payload)
    }

    @Test
    fun importConfiguration() = runBlocking {
        require(Build.MODEL == "PMA110") { "Wrong target device" }
        val payload = readTransfer()
        check(payload["version"]?.jsonPrimitive?.content == "1") { "Unsupported transfer version" }
        val incoming = JsonInstant.decodeFromString<Settings>(payload.getValue("settings").toString())
        val refs = JsonInstant.decodeFromString<List<SecretReference>>(payload.getValue("refs").toString())
        val values = payload.getValue("secrets").jsonObject
        val original = snapshot()
        val rollback = File(directory, "target-rollback.json")
        check(!rollback.exists()) { "Import already attempted; verify or restore first" }
        rollback.writeText(KeystoreSecretCipher("amber_settings_transfer_rollback").encrypt(original.toString()))
        val local = settings()
        val desired = incoming.copy(
            syncSettings = local.syncSettings,
            launchCount = local.launchCount,
            agentRuntime = incoming.agentRuntime.copy(
                externalFileAccess = local.agentRuntime.externalFileAccess,
                autoApproveAllToolCalls = local.agentRuntime.autoApproveAllToolCalls,
                autoApproveHighRiskToolCalls = local.agentRuntime.autoApproveHighRiskToolCalls,
            ),
        )
        try {
            values.forEach { (key, value) ->
                val descriptor = requireNotNull(SecretDescriptor.fromKey(key)) { "Invalid secret descriptor" }
                secrets.update(descriptor, value.jsonPrimitive.content)
            }
            store.restoreSecretRefs(refs)
            store.update(desired)
            verify(payload)
            report("import_verified", payload)
        } catch (error: Exception) {
            // Roll back only configuration; chat storage has never been touched.
            val oldValues = original.getValue("secrets").jsonObject
            values.keys.filterNot { it in oldValues }.forEach { key -> SecretDescriptor.fromKey(key)?.let(secrets::delete) }
            oldValues.forEach { (key, value) -> secrets.update(requireNotNull(SecretDescriptor.fromKey(key)), value.jsonPrimitive.content) }
            store.restoreSecretRefs(JsonInstant.decodeFromString(original.getValue("refs").toString()))
            store.update(local)
            throw IllegalStateException("Configuration import failed and was rolled back")
        }
    }

    private suspend fun verify(payload: JsonObject) {
        val expected = JsonInstant.decodeFromString<Settings>(payload.getValue("settings").toString())
        withTimeout(15_000) { store.settingsFlow.first { actual ->
            !actual.init && actual.providers.map { it.id } == expected.providers.map { it.id } &&
                actual.chatModelId == expected.chatModelId && actual.jev == expected.jev
        } }
        val values = payload.getValue("secrets").jsonObject
        check(values.all { (key, value) -> secrets.read(requireNotNull(SecretDescriptor.fromKey(key))) == value.jsonPrimitive.content }) {
            "Target encrypted secret verification failed"
        }
    }

    @Test
    fun verifyAfterRestart() = runBlocking {
        require(Build.MODEL == "PMA110") { "Wrong target device" }
        val payload = readTransfer()
        settings()
        verify(payload)
        report("restart_verified", payload)
    }
}
