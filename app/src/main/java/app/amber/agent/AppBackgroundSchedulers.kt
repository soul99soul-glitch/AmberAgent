package app.amber.agent

import android.util.Log
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.memory.dream.MemoryDreamScheduler
import app.amber.feature.cron.AgentCronManager
import app.amber.feature.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Process scheduling subscriptions; service providers resolve inside their original jobs. */
internal class AppBackgroundSchedulers(
    private val scope: AppScope,
    private val settings: () -> SettingsAggregator,
    private val cron: () -> AgentCronManager,
    private val reminders: () -> ReminderScheduler,
    private val memory: () -> MemoryDreamScheduler,
) {
    fun rescheduleCronTasks() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                cron().rescheduleAll()
            }.onFailure {
                Log.e("AmberAgentApp", "rescheduleCronTasks failed", it)
            }
        }
    }

    fun rescheduleReminders() {
        scope.launch(Dispatchers.IO) {
            runCatching { reminders().rescheduleAll() }
                .onFailure { Log.e("AmberAgentApp", "rescheduleReminders failed", it) }
        }
    }

    fun syncMemoryDreamTasks() {
        scope.launch(Dispatchers.IO) {
            val scheduler = memory()
            val settingsStore = settings()
            settingsStore.settingsFlow
                .map { it.agentRuntime.memoryWorker }
                .distinctUntilChanged()
                .collect {
                    runCatching {
                        scheduler.sync()
                    }.onFailure { error ->
                        Log.e("AmberAgentApp", "syncMemoryDreamTasks failed", error)
                    }
                }
        }
    }

}
