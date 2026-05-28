package me.rerere.rikkahub.ui.pages.setting.memory

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.amber.core.memory.model.MemoryWorkerSetting

@Composable
fun MemoryWorkerSettingsSection(setting: MemoryWorkerSetting) {
    Text("记忆后台任务：${if (setting.enabled) "已启用" else "已关闭"}")
}
