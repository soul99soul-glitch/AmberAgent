package app.amber.core.sync.core

import android.content.Context
import android.util.Base64
import app.amber.core.settings.secret.KeystoreSecretCipher
import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SharedPrefsSecretStoreBackend
import java.security.SecureRandom

/**
 * P7-02 设备绑定加密的设备秘密。
 *
 * 每次导出随机生成 32 字节秘密，用 Android Keystore 保护的 AES/GCM 信封
 * （[KeystoreSecretCipher]）持久化在本机 SharedPreferences。归档文件头只记录
 * KDF 参数与 salt/iv，不记录口令或任何可逆提示 —— 恢复时只有持有该 Keystore
 * key 的设备（创建备份的设备）能解出设备秘密并解密负载。
 *
 * 存储与加密复用 P1-01 SecretStore 的接口拆分，便于单元测试用内存 backend +
 * 桩 cipher 替换（Robolectric 无真实 AndroidKeyStore）。
 */
class DeviceBoundBackupKey(private val store: SecretStore) {
    /** 读取设备秘密；不存在时生成并持久化（幂等，重入安全）。 */
    fun getOrCreate(): String =
        store.read(DESCRIPTOR) ?: randomSecret().also { store.create(DESCRIPTOR, it) }

    /** 仅读取；Keystore 失效/未创建时返回 null（调用方按“非本设备”处理）。 */
    fun current(): String? = store.read(DESCRIPTOR)

    private fun randomSecret(): String {
        val bytes = ByteArray(SECRET_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    companion object {
        val DESCRIPTOR = SecretDescriptor("device_bound", "backup", "device_key")

        /** 生产组装：独立 SharedPreferences + 独立 Keystore key alias。 */
        fun createAndroid(context: Context): DeviceBoundBackupKey = DeviceBoundBackupKey(
            SecretStore(
                backend = SharedPrefsSecretStoreBackend(context, "amberagent_backup_device_key"),
                cipher = KeystoreSecretCipher("amberagent_backup_device_key"),
            )
        )

        private const val SECRET_BYTES = 32
        private val secureRandom = SecureRandom()
    }
}
