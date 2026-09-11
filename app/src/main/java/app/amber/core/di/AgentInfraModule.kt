package app.amber.core.di

import app.amber.agent.BuildConfig
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.NotificationApprovalTokenRegistry
import app.amber.feature.runtime.RunOwnershipRegistry
import app.amber.feature.runtime.ToolActivityTitleLocalizer
import app.amber.feature.runtime.ToolActivityTitleResolver
import app.amber.feature.cron.AgentCronManager
import app.amber.feature.live.LiveModeManager
import app.amber.feature.reminder.AndroidReminderAlarmOperations
import app.amber.feature.reminder.ReminderScheduler
import app.amber.feature.reminder.ReminderStore
import app.amber.feature.reminder.ReminderTools
import app.amber.feature.system.AgentPermissionBroker
import app.amber.feature.health.HealthSummaryReader
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.task.AgentTaskStore
import app.amber.feature.terminal.AlpineRuntimeInstaller
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.tools.AgentCronTools
import app.amber.feature.tools.ScreenAutomationTools
import app.amber.feature.tools.SystemAccessTools
import app.amber.feature.tools.TerminalTools
import app.amber.feature.webview.WebViewOperationStore
import app.amber.core.automation.ScreenCaptureManager
import org.koin.dsl.module

/**
 * Agent infrastructure Koin module — the supporting cast around the
 * SubAgent / ModelCouncil runtime (in [agentRuntimeModule]):
 *
 *  - Task & cron scheduling (AgentTaskStore / AgentTaskScheduler /
 *    AgentCronManager / AgentCronTools)
 *  - Live status reporting + LiveMode mounting (AgentLiveStatusNotifier /
 *    LiveModeManager)
 *  - Tool activity audit & WebView ops (AgentToolActivityStore /
 *    WebViewOperationStore)
 *  - Terminal sandbox + system access (AlpineRuntimeInstaller / TerminalRuntime
 *    / TerminalTools / ScreenCaptureManager / ScreenAutomationTools /
 *    AgentPermissionBroker / SystemAccessTools)
 *
 * Extracted from AppModule in M1.5 continuation.
 */
val agentInfraModule = module {
    single<ToolActivityTitleResolver> { ToolActivityTitleLocalizer(get()) }

    single { AgentToolActivityStore(get()) }

    single { AgentTaskStore(get(), get()) }

    single { AgentTaskScheduler(get()) }

    single { WebViewOperationStore() }

    single { AgentLiveStatusNotifier(get(), get()) }

    // P1-05: ownership map for scoped run cancellation (assistantId +
    // conversationId + runId → generation job).
    single { RunOwnershipRegistry() }

    // P8-10: one-time approval tokens for notification approve/deny/reply
    // actions (bound to runId + toolCallId + args digest).
    single { NotificationApprovalTokenRegistry() }

    single { LiveModeManager(get(), get(), get(), get()) }

    single { AlpineRuntimeInstaller(get()) }

    single { app.amber.feature.terminal.SshClient() }

    single { TerminalRuntime(get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    single { TerminalTools(get(), get(), get()) }

    single { ScreenCaptureManager(get()) }

    single { ScreenAutomationTools(get(), get(), get()) }

    single { AgentPermissionBroker(get(), BuildConfig.DEBUG) }

    single { HealthSummaryReader(get()) }

    single { SystemAccessTools(get(), get(), get(), get()) }

    single { AgentCronManager(get(), get(), get()) }

    single { ReminderStore(get<android.content.Context>()) }
    single<app.amber.feature.reminder.ReminderAlarmOperations> { AndroidReminderAlarmOperations(get()) }
    single {
        ReminderScheduler(
            store = get(),
            operations = get(),
            exactAlarmAllowed = { ReminderScheduler.exactAlarmAllowed(get<android.content.Context>()) },
        )
    }
    single { ReminderTools(get(), get()) }

    single { AgentCronTools(get()) }
}
