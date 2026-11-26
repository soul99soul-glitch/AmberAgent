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
        val runId = intent.getStringExtra(VirtualDisplayRunNotifier.EXTRA_RUN_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val activeRun = VirtualDisplayRunRegistry.get(runId) ?: return
        val notifier = VirtualDisplayRunNotifier(context.applicationContext)

        when (intent.action) {
            VirtualDisplayRunNotifier.ACTION_PAUSE_VIRTUAL_RUN -> {
                activeRun.pause()
                notifier.showRunning(
                    runId = activeRun.runId,
                    packageName = activeRun.packageName,
                    goal = activeRun.goal,
                    paused = true,
                )
            }

            VirtualDisplayRunNotifier.ACTION_EMERGENCY_STOP_VIRTUAL_RUN -> {
                val pendingResult = goAsync()
                receiverScope.launch {
                    try {
                        activeRun.emergencyStop()
                        notifier.cancel(runId)
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
