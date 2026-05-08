package me.rerere.rikkahub.ui.pages.setting.memory

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.data.model.AssistantMemory

@Composable
fun MemoryRecordsSection(records: List<AssistantMemory>) {
    Text("正式记忆 ${records.size} 条")
}
