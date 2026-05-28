package app.amber.feature.ui.pages.setting.memory

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.amber.core.model.AssistantMemory

@Composable
fun MemoryRecordsSection(records: List<AssistantMemory>) {
    Text("正式记忆 ${records.size} 条")
}
