package app.amber.feature.miniapp

import android.content.Context

class MiniAppStorage(context: Context) {
    private val prefs = context.getSharedPreferences("mini_app_storage", Context.MODE_PRIVATE)

    fun get(appId: String, key: String): String? {
        validateKey(key)
        return prefs.getString(storageKey(appId, key), null)
    }

    fun set(appId: String, key: String, value: String) {
        validateKey(key)
        val valueBytes = value.encodeToByteArray().size
        if (valueBytes > MAX_VALUE_BYTES) {
            throw MiniAppValidationException("Storage value is too large")
        }
        enforceAppQuota(appId, key, valueBytes)
        prefs.edit().putString(storageKey(appId, key), value).apply()
    }

    fun remove(appId: String, key: String) {
        validateKey(key)
        prefs.edit().remove(storageKey(appId, key)).apply()
    }

    private fun validateKey(key: String) {
        if (!keyPattern.matches(key)) {
            throw MiniAppValidationException("Invalid storage key")
        }
    }

    private fun storageKey(appId: String, key: String): String = "$appId:$key"

    private fun enforceAppQuota(appId: String, key: String, newValueBytes: Int) {
        val prefix = "$appId:"
        val targetKey = storageKey(appId, key)
        var count = 0
        var totalBytes = 0
        var oldValueBytes = 0
        prefs.all.forEach { (storedKey, storedValue) ->
            if (!storedKey.startsWith(prefix)) return@forEach
            count++
            val bytes = storedValue.toString().encodeToByteArray().size
            totalBytes += bytes
            if (storedKey == targetKey) oldValueBytes = bytes
        }
        if (oldValueBytes == 0 && count >= MAX_KEYS_PER_APP) {
            throw MiniAppValidationException("Storage key limit exceeded")
        }
        if (totalBytes - oldValueBytes + newValueBytes > MAX_TOTAL_BYTES_PER_APP) {
            throw MiniAppValidationException("Storage quota exceeded")
        }
    }

    private companion object {
        const val MAX_VALUE_BYTES = 32 * 1024
        const val MAX_KEYS_PER_APP = 128
        const val MAX_TOTAL_BYTES_PER_APP = 512 * 1024
        val keyPattern = Regex("""[a-zA-Z0-9._:-]{1,64}""")
    }
}
