package app.amber.core.automation.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Non-exported broadcast receiver for ongoing virtual display run controls.
 *
 * Accepts ONLY pause and emergency stop intents bound to an active `runId` in
 * [VirtualDisplayRunRegistry]. Never handles approvals or unverified run IDs.
 */
class VirtualDisplayRunControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val activeRun = VirtualDisplayRunRegistry.forIntent(intent) ?: return
        val notifier = VirtualDisplayRunNotifier(context.applicationContext)

        when (intent.action) {
            VirtualDisplayRunNotifier.ACTION_PAUSE_VIRTUAL_RUN -> {
                activeRun.pause()
                notifier.showRunning(activeRun, paused = true)
            }

            VirtualDisplayRunNotifier.ACTION_EMERGENCY_STOP_VIRTUAL_RUN -> {
                val pendingResult = goAsync()
                receiverScope.launch {
                    try {
                        if (VirtualDisplayRunRegistry.forIntent(intent) !== activeRun) return@launch
                        activeRun.emergencyStop()
                        notifier.cancel(activeRun)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            else -> return
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
