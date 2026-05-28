package app.amber.feature.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class TermuxCommandResultReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val runtime = get<TerminalRuntime>()
        get<AppScope>().launch(Dispatchers.IO) {
            try {
                runtime.handleTermuxResult(intent)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
