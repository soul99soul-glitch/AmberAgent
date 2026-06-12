package app.amber.ai.provider.providers

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import app.amber.ai.util.json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class OAuthTokenSecureStore(
    context: Context,
    prefName: String,
    private val keyAlias: String,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)

    init {
        migratePlaintextValues()
    }

    fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        decodeStoredSecretOrNull(raw)?.let { stored ->
            return decryptOrNull(stored) ?: error("Unable to decrypt stored OAuth token")
        }
        put(key, raw)
        return raw
    }

    fun put(key: String, value: String) {
        check(prefs.edit().putString(key, encrypt(value)).commit()) { "Unable to store encrypted OAuth token" }
    }

    fun remove(key: String) {
        check(prefs.edit().remove(key).commit()) { "Unable to remove OAuth token" }
    }

    fun exportPlainValues(): Map<String, String> = prefs.all.mapNotNull { (key, value) ->
        val raw = value as? String ?: return@mapNotNull null
        val stored = decodeStoredSecretOrNull(raw)
        key to if (stored == null) raw else decryptOrNull(stored) ?: error("Unable to decrypt stored OAuth token")
    }.toMap()

    fun replacePlainValues(values: Map<String, String>) {
        val encryptedValues = values.mapValues { (_, value) -> encrypt(value) }
        check(prefs.edit().apply {
            clear()
            encryptedValues.forEach { (key, value) -> putString(key, value) }
        }.commit()) { "Unable to replace encrypted OAuth tokens" }
    }

    private fun migratePlaintextValues() {
        val updates = prefs.all.mapNotNull { (key, value) ->
            val raw = value as? String ?: return@mapNotNull null
            if (decodeStoredSecretOrNull(raw) != null) return@mapNotNull null
            key to encrypt(raw)
        }
        if (updates.isEmpty()) return
        check(prefs.edit().apply { updates.forEach { (key, value) -> putString(key, value) } }.commit()) {
            "Unable to migrate OAuth tokens to encrypted storage"
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return json.encodeToString(
            StoredSecret(
                iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            )
        )
    }

    private fun decodeStoredSecretOrNull(raw: String): StoredSecret? = runCatching {
        json.decodeFromString<StoredSecret>(raw).takeIf { it.version == STORED_SECRET_VERSION }
    }.getOrNull()

    private fun decryptOrNull(stored: StoredSecret): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(stored.iv, Base64.NO_WRAP)),
        )
        cipher.doFinal(Base64.decode(stored.ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    @Serializable
    private data class StoredSecret(
        val version: Int = 1,
        val iv: String,
        val ciphertext: String,
    )

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val STORED_SECRET_VERSION = 1
    }
}
