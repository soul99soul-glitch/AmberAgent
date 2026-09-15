package app.amber.core.settings.prefs

import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.core.settings.Settings
import app.amber.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * M1.1.8 prefs-split 启动竞态的 rescue 判定（纯 JVM，直接测 Settings 扩展函数）：
 * 只有当前配置形如被抹成出厂默认时才允许恢复备份，健康配置永不回滚。
 */
class SettingsProviderRescueTest {

    @Test
    fun `healthy custom provider configuration is never rescued even when backup looks richer`() {
        val current = Settings(
            providers = listOf(ProviderSetting.OpenAI(apiKey = "sk-user-custom-key")),
            searchServices = emptyList(),
        )
        val backup = Settings(
            providers = listOf(ProviderSetting.OpenAI(apiKey = "sk-backup-key")),
            searchServices = listOf(SearchServiceOptions.TavilyOptions(apiKey = "backup-search-key")),
        )

        // 用户已有真实自定义 provider：即使备份 searchServices 更多也不得整份恢复
        assertFalse(current.needsSettingsRescueFrom(JsonInstant, backup))
    }

    @Test
    fun `wiped factory defaults with richer cached backup trigger rescue`() {
        val current = Settings(providers = DEFAULT_PROVIDERS)
        val backupModel = Model(modelId = "backup-chat-model")
        val backup = Settings(
            providers = DEFAULT_PROVIDERS + ProviderSetting.OpenAI(
                apiKey = "sk-backup-key",
                models = listOf(backupModel),
            ),
        )

        assertTrue(current.needsSettingsRescueFrom(JsonInstant, backup))
    }

    @Test
    fun `recover adopts backup providers and model selections with dangling fallback`() {
        val backupModel = Model(modelId = "backup-chat-model")
        val backup = Settings(
            providers = DEFAULT_PROVIDERS + ProviderSetting.OpenAI(
                apiKey = "sk-backup-key",
                models = listOf(backupModel),
            ),
            chatModelId = backupModel.id,
            // 备份自身的 titleModelId 不指向备份里的任何模型
            titleModelId = Uuid.random(),
        )
        val current = Settings(
            providers = DEFAULT_PROVIDERS,
            chatModelId = Uuid.random(),
        )

        val recovered = current.recoverSettingsFrom(backup)

        assertEquals(backup.providers, recovered.providers)
        // 备份 chatModelId 有效 → 采用备份值
        assertEquals(backupModel.id, recovered.chatModelId)
        // 备份 titleModelId 悬空 → 回退为当前值
        assertEquals(current.titleModelId, recovered.titleModelId)
    }
}
