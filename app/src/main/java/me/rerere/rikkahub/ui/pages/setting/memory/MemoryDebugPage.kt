package me.rerere.rikkahub.ui.pages.setting.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.amber.core.memory.model.MemoryEvent

@Composable
fun MemoryDebugPage(events: List<MemoryEvent>) {
    Column {
        Text("记忆事件")
        events.take(50).forEach { event ->
            Text("${event.type.wireName}: ${event.message}")
        }
    }
}
