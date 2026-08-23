package app.amber.core.settings.secret

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import app.amber.core.agent.utils.JsonInstant
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 统一 SecretStore —— P1-01 的核心。
 *
 * 凭据以稳定 `scope + ownerId + fieldName` 为键存入加密存储（Android Keystore 保护的
 * AES/GCM，见 [KeystoreSecretCipher]，复用 `OAuthTokenSecureStore` 的 Keystore 包装模式），
 * 真实值从不进入键名。普通设置（Preferences DataStore）只保留 [SecretReference]
 * （reference + 掩码），业务使用边界通过 [SecretRedactor] 按需 rehydrate。
 *
 * 存储与加密分离成两个接口，便于单元测试用内存 backend + 桩 cipher 替换
 * （Robolectric 不提供真实 AndroidKeyStore）。
 */
data class SecretDescriptor(
    val scope: String,
    val ownerId: String,
    val fieldName: String,
) {
    /** 稳定存储键：`scope/ownerId/fieldName`，各段经过净化，真实凭据值永不进入键名。 */
    val key: String = listOf(scope, ownerId, fieldName).joinToString("/") { it.sanitizeKeySegment() }

    companion object {
        fun fromKey(key: String): SecretDescriptor? {
            val parts = key.split("/")
            if (parts.size != 3) return null
            return SecretDescriptor(parts[0], parts[1], parts[2])
        }

        private fun String.sanitizeKeySegment(): String =
            replace("/", "_").replace(":", "_")
    }
}

/**
 * 设置在 DataStore 中保留的 reference + 掩码位。UI 默认只看到 [mask] 与是否已设置；
 * 读取设置时按 [descriptor] 从 SecretStore 按需 rehydrate 真实值。
 */
@Serializable
data class SecretReference(
    val scope: String,
    val ownerId: String,
    val fieldName: String,
    val mask: String,
) {
    fun descriptor(): SecretDescriptor = SecretDescriptor(scope, ownerId, fieldName)
}

/** 加密原语：encrypt 产出可持久化的密文信封，decrypt 失败返回 null（密钥失效等）。 */
interface SecretCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(stored: String): String?
}

/** SecretStore 的原始存储 backend：只负责按 key 存/取/删密文 blob。 */
interface SecretStoreBackend {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)

    fun keys(): Set<String>
}

/**
 * 统一 SecretStore。create/read/update/delete/listOrphans 全部幂等可重跑：
 * update 是 upsert，delete 幂等；迁移中断后重跑不会丢旧值。
 */
class SecretStore(
    private val backend: SecretStoreBackend,
    private val cipher: SecretCipher,
) {
    /** 新建一条 secret；键已存在时抛错（避免迁移逻辑误覆盖已有凭据）。 */
    fun create(descriptor: SecretDescriptor, value: String) {
        check(descriptor.key !in backend.keys()) { "Secret already exists: ${descriptor.key}" }
        backend.put(descriptor.key, cipher.encrypt(value))
    }

    /** 更新（upsert）一条 secret。 */
    fun update(descriptor: SecretDescriptor, value: String) {
        backend.put(descriptor.key, cipher.encrypt(value))
    }

    /** 读取真实值；密钥失效/密文损坏时返回 null（调用方按未设置处理，不删 reference）。 */
    fun read(descriptor: SecretDescriptor): String? =
        backend.get(descriptor.key)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    /**
     * 是否已存在该 descriptor 的密文条目 —— 不解密、不读真值，只按存储键存在性判断。
     * 供「已配置 key」类状态上报使用（如 agent provider_config_status 的 has_api_key）。
     */
    fun has(descriptor: SecretDescriptor): Boolean = backend.get(descriptor.key) != null

    /** 删除一条 secret（幂等）。 */
    fun delete(descriptor: SecretDescriptor) {
        backend.remove(descriptor.key)
    }

    /** 当前存储的全部稳定键。 */
    private fun listKeys(): Set<String> = backend.keys().filterNot { it in RESERVED_KEYS }.toSet()

    /**
     * 列出确认无引用的 orphan：存在但不在 [active] 中的条目。只列不删，
     * 由调用方在确认引用集合完整后调用 [deleteOrphans]。
     */
    fun listOrphans(active: Set<SecretDescriptor>): List<SecretDescriptor> {
        val activeKeys = active.map { it.key }.toSet()
        return listKeys().mapNotNull { key ->
            if (key in activeKeys) null else SecretDescriptor.fromKey(key)
        }
    }

    /** 只删除确认无引用的项。 */
    fun deleteOrphans(active: Set<SecretDescriptor>) {
        listOrphans(active).forEach { delete(it) }
    }

    /** 迁移版本标记（幂等迁移的持久化 gate）。 */
    fun migrationVersion(): Int = backend.get(MIGRATION_VERSION_KEY)?.toIntOrNull() ?: 0

    fun markMigrated(version: Int) {
        backend.put(MIGRATION_VERSION_KEY, version.toString())
    }

    companion object {
        internal const val MIGRATION_VERSION_KEY = "__secret_store_migration_version__"
        private val RESERVED_KEYS = setOf(MIGRATION_VERSION_KEY)
    }
}

/** SharedPreferences 版 backend —— 与 OAuthTokenSecureStore 同一存储形态。 */
class SharedPrefsSecretStoreBackend(
    context: Context,
    fileName: String = "amberagent_secret_store",
) : SecretStoreBackend {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        check(prefs.edit().putString(key, value).commit()) { "Unable to store secret" }
    }

    override fun remove(key: String) {
        check(prefs.edit().remove(key).commit()) { "Unable to remove secret" }
    }

    override fun keys(): Set<String> = prefs.all.keys
}

/**
 * Android Keystore 保护的 AES/GCM cipher —— 直接复用 `OAuthTokenSecureStore`
 * 的包装模式（KeyGenParameterSpec AES/GCM/NoPadding、iv+ciphertext Base64 JSON 信封）。
 */
class KeystoreSecretCipher(
    private val keyAlias: String = "amberagent_secret_store_key",
) : SecretCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JsonInstant.encodeToString(
            StoredSecret(
                iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            )
        )
    }

    override fun decrypt(stored: String): String? = runCatching {
        val decoded = JsonInstant.decodeFromString<StoredSecret>(stored)
        if (decoded.version != STORED_SECRET_VERSION) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(decoded.iv, Base64.NO_WRAP)),
        )
        cipher.doFinal(Base64.decode(decoded.ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
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

/** 生产组装：SharedPreferences 存储 + Android Keystore AES/GCM cipher。 */
fun createAndroidSecretStore(context: Context): SecretStore = SecretStore(
    backend = SharedPrefsSecretStoreBackend(context),
    cipher = KeystoreSecretCipher(),
)
