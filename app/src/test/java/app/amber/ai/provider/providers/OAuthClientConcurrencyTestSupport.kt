package app.amber.ai.provider.providers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

internal class TestContext : ContextWrapper(null) {
    private val preferences = InMemorySharedPreferences()

    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences
}

internal fun tokenResponseClient(
    endpoint: String,
    entered: CountDownLatch,
    release: CountDownLatch,
    statusCode: Int,
    responseBody: String,
): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(Interceptor { chain ->
        val request = chain.request()
        if (request.url.toString() != endpoint) return@Interceptor chain.proceed(request)
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "test response was not released" }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message("test")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
    })
    .build()

internal object TestAndroidKeyStore {
    private const val PROVIDER_NAME = "AndroidKeyStore"
    private var previous: Provider? = null

    @Synchronized
    fun install() {
        if (Security.getProvider(PROVIDER_NAME) is TestAndroidKeyStoreProvider) return
        previous = Security.getProvider(PROVIDER_NAME)
        Security.removeProvider(PROVIDER_NAME)
        Security.insertProviderAt(TestAndroidKeyStoreProvider, 1)
    }

    @Synchronized
    fun restore() {
        Security.removeProvider(PROVIDER_NAME)
        previous?.let { Security.insertProviderAt(it, 1) }
        previous = null
        TestAndroidKeyStoreSpi.keys.clear()
    }
}

internal object TestAndroidKeyStoreProvider : Provider(
    "AndroidKeyStore",
    1.0,
    "In-memory AndroidKeyStore for OAuth client tests",
) {
    init {
        put("KeyStore.AndroidKeyStore", TestAndroidKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", TestAesKeyGeneratorSpi::class.java.name)
    }
}

internal class TestAndroidKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String?, password: CharArray?): Key? =
        alias?.let { keys[it] }

    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCreationDate(alias: String?): Date? = null
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<Certificate>?) {
        if (alias != null && key is SecretKey) keys[alias] = key
    }
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<Certificate>?) = Unit
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
    override fun engineDeleteEntry(alias: String?) {
        if (alias != null) keys.remove(alias)
    }
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
    override fun engineContainsAlias(alias: String?): Boolean = alias != null && keys.containsKey(alias)
    override fun engineSize(): Int = keys.size
    override fun engineIsKeyEntry(alias: String?): Boolean = alias != null && keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String?): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

    internal companion object {
        val keys = ConcurrentHashMap<String, SecretKey>()
    }
}

internal class TestAesKeyGeneratorSpi : KeyGeneratorSpi() {
    private var alias: String? = null

    override fun engineInit(random: SecureRandom?) = Unit
    override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
    override fun engineInit(params: java.security.spec.AlgorithmParameterSpec?, random: SecureRandom?) {
        alias = (params as? KeyGenParameterSpec)?.keystoreAlias
    }

    override fun engineGenerateKey(): SecretKey {
        val key = SecretKeySpec(ByteArray(16).also(SecureRandom()::nextBytes), "AES")
        alias?.let { TestAndroidKeyStoreSpi.keys[it] = key }
        return key
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }
    override fun getAll(): Map<String, *> = synchronized(values) { values.toMap() }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(values) { values[key] as? Boolean ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(values) { values[key] as? Float ?: defValue }
    override fun getInt(key: String, defValue: Int): Int = synchronized(values) { values[key] as? Int ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(values) { values[key] as? Long ?: defValue }
    override fun getString(key: String, defValue: String?): String? = synchronized(values) { values[key] as? String ?: defValue }
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = synchronized(values) {
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
    }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, value: Set<String>?) = put(key, value?.toSet())
        override fun remove(key: String): SharedPreferences.Editor = apply {
            removed += key
            pending.remove(key)
        }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun apply() {
            commit()
        }
        override fun commit(): Boolean {
            val changed = mutableSetOf<String>()
            synchronized(values) {
                if (clear) {
                    changed += values.keys
                    values.clear()
                }
                removed.forEach {
                    if (values.remove(it) != null) changed += it
                }
                pending.forEach { (key, value) ->
                    values[key] = value
                    changed += key
                }
            }
            changed.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) } }
            return true
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply {
            pending[key] = value
            removed.remove(key)
        }
    }
}
