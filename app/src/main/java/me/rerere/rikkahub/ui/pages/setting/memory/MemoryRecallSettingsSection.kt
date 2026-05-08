package me.rerere.rikkahub.ui.pages.setting.memory

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.data.memory.model.MemoryRecallSetting

@Composable
fun MemoryRecallSettingsSection(setting: MemoryRecallSetting) {
    Text("选择性召回：最多 ${setting.maxItems} 条 / ${setting.maxPromptChars} 字符")
}
