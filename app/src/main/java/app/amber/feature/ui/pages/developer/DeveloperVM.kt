package app.amber.feature.ui.pages.developer

import androidx.lifecycle.ViewModel
import app.amber.core.ai.AILoggingManager

class DeveloperVM(
    private val aiLoggingManager: AILoggingManager
) : ViewModel() {
    val logs = aiLoggingManager.getLogs()
}
