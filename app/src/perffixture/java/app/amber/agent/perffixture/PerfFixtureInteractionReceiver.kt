package app.amber.agent.perffixture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.amber.feature.ui.utils.ChatPerfCommandBridge
import app.amber.feature.ui.utils.ChatPerfTrace
import app.amber.feature.ui.utils.ChatPerfHomeEntry

/**
 * Fixture-only control surface used by scripts/chat-history-entry-perf.py.
 * It is registered by the perffixture manifest only and drives production UI
 * paths (the real composer send action, the real stop action) rather than
 * writing fixture state directly.
 */
class PerfFixtureInteractionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra("runId").orEmpty()
        val current = ChatPerfTrace.currentRunId()
        when (intent.action) {
            ACTION_HOME_STATE -> {
                val state = ChatPerfHomeEntry.snapshot(intent.getStringExtra("conversationId").orEmpty())
                Log.i(TAG, "home-state nonce=${intent.getStringExtra("nonce")} stateJson=$state")
            }
            ACTION_HOME_ARM -> {
                val reason = ChatPerfHomeEntry.arm(runId, intent.getStringExtra("scenario").orEmpty(),
                    intent.getStringExtra("conversationId").orEmpty(), intent.getStringExtra("phase").orEmpty())
                Log.i(TAG, "home-armed runId=$runId accepted=${reason == null} reason=${reason ?: "ok"}")
            }
            ACTION_SET_RUN -> {
                val scenario = intent.getStringExtra("scenario").orEmpty()
                if (runId.isBlank() || scenario.isBlank()) {
                    Log.i(TAG, "set-run-rejected runId=$runId scenario=$scenario")
                    return
                }
                context.writeFixtureString("perf_launcher_run_id", runId)
                context.writeFixtureString("perf_launcher_scenario", scenario)
                Log.i(TAG, "launcher-run-id runId=$runId scenario=$scenario")
            }
            ACTION_BLUR -> {
                val cleared = ChatPerfCommandBridge.clearFocus?.invoke() == true
                Log.i(TAG, "blur-ack runId=$runId ok=$cleared")
            }
            ACTION_INTERACTION -> {
                val kind = intent.getStringExtra("kind")?.takeIf { it.isNotBlank() } ?: "swipe"
                if (!ChatPerfCommandBridge.beginInteraction(runId, kind)) {
                    Log.i(TAG, "interaction-rejected runId=$runId current=$current")
                    return
                }
                ChatPerfTrace.interactionRequested(kind)
                Log.i(TAG, "interaction-requested runId=$runId kind=$kind trace=$current")
            }
            ACTION_SEND -> {
                if (current == null || current != runId) {
                    Log.i(TAG, "send-rejected runId=$runId current=$current")
                    return
                }
                if (!ChatPerfTrace.beginFixtureStream(runId)) {
                    Log.i(TAG, "s7-send-phase-rejected runId=$runId")
                    return
                }
                val text = intent.getStringExtra("text").orEmpty()
                Log.i(TAG, "s7-send-request runId=$runId")
                val accepted = ChatPerfCommandBridge.sendText?.invoke(text) == true
                Log.i(TAG, "s7-send-accepted runId=$runId accepted=$accepted")
                if (!accepted) ChatPerfTrace.failFixtureStream(runId, "S7 composer rejected send")
            }
            ACTION_STOP -> {
                val accepted = ChatPerfCommandBridge.stopGeneration?.invoke() == true
                Log.i(TAG, "stop-requested runId=$runId accepted=$accepted")
            }
            ACTION_SNAPSHOT -> {
                val nonce = intent.getStringExtra("nonce").orEmpty()
                val snapshot = ChatPerfCommandBridge.snapshot?.invoke() ?: "snapshot-unavailable"
                Log.i(
                    TAG,
                    "snapshot nonce=$nonce runId=${current ?: "none"} $snapshot",
                )
            }
        }
    }

    companion object {
        const val ACTION_HOME_STATE = "app.amber.agent.perffixture.PERF_HOME_STATE"
        const val ACTION_HOME_ARM = "app.amber.agent.perffixture.PERF_HOME_ARM"
        const val ACTION_SET_RUN = "app.amber.agent.perffixture.PERF_SET_RUN"
        const val ACTION_BLUR = "app.amber.agent.perffixture.PERF_BLUR"
        const val ACTION_INTERACTION = "app.amber.agent.perffixture.PERF_INTERACTION"
        const val ACTION_SEND = "app.amber.agent.perffixture.PERF_S7_SEND"
        const val ACTION_STOP = "app.amber.agent.perffixture.PERF_STOP"
        const val ACTION_SNAPSHOT = "app.amber.agent.perffixture.PERF_SNAPSHOT"
        private const val TAG = "AmberPerfFixture"
    }
}
