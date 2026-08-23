package app.amber.core.sync.provider

import android.content.Context
import android.content.SharedPreferences

/**
 * P7-01 本地文件夹 Provider 的持久 URI permission 存储。
 *
 * 保存用户通过 SAF OpenDocumentTree 选中的 tree URI 与展示名。调用方在
 * Activity 层先 `contentResolver.takePersistableUriPermission(uri, ...)`
 * 完成持久授权，这里只负责把 URI 稳定落盘；重启后直接复用，无需重新选择。
 */
class PersistedFolderStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): Folder? {
        val uri = prefs.getString(KEY_URI, null) ?: return null
        return Folder(
            uri = uri,
            displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty(),
            savedAt = prefs.getLong(KEY_SAVED_AT, 0L),
        )
    }

    fun save(uri: String, displayName: String) {
        prefs.edit()
            .putString(KEY_URI, uri)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    data class Folder(
        val uri: String,
        val displayName: String,
        val savedAt: Long,
    )

    companion object {
        private const val PREFS_NAME = "amber_sync_local_folder"
        private const val KEY_URI = "tree_uri"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_SAVED_AT = "saved_at"
    }
}
