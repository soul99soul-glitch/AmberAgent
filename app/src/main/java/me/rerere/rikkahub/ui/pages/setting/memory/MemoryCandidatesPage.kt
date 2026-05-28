package me.rerere.rikkahub.ui.pages.setting.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.amber.core.memory.model.MemoryCandidate

@Composable
fun MemoryCandidatesPage(candidates: List<MemoryCandidate>) {
    Column {
        Text("候选记忆")
        candidates.take(20).forEach { candidate ->
            Text("• ${candidate.content}")
        }
    }
}
