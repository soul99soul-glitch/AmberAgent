package app.amber.core.settings.secret

/**
 * 测试夹具：Robolectric 无真实 AndroidKeyStore，用内存 backend + 桩 cipher
 * 复用 SecretStore 的接口拆分（生产实现见 KeystoreSecretCipher）。
 */
fun inMemoryBackend(): SecretStoreBackend = object : SecretStoreBackend {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun keys(): Set<String> = map.keys.toSet()
}

fun fakeCipher(): SecretCipher = object : SecretCipher {
    override fun encrypt(plaintext: String): String = "enc:$plaintext"
    override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
}

fun fakeSecretStore(backend: SecretStoreBackend = inMemoryBackend()): SecretStore =
    SecretStore(backend = backend, cipher = fakeCipher())
