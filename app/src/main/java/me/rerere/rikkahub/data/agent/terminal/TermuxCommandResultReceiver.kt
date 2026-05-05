package me.rerere.rikkahub.data.agent.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class TermuxCommandResultReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        get<TerminalRuntime>().handleTermuxResult(intent)
    }
}
