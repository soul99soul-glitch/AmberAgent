package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.migration.migrateMcpServersJson

class PreferenceStoreV1Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[PreferencesKeys.VERSION]
        return version == null || version < 1
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        // 清理老的没有设置@SerialName的字段
        prefs[PreferencesKeys.MCP_SERVERS] = migrateMcpServersJson(prefs[PreferencesKeys.MCP_SERVERS] ?: "[]")

        // 更新版本
        prefs[PreferencesKeys.VERSION] = 1

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
