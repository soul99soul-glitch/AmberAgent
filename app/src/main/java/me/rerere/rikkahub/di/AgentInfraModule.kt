package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.agent.AgentLiveStatusNotifier
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import app.amber.feature.cron.AgentCronManager
import app.amber.feature.live.LiveModeManager
import app.amber.feature.system.AgentPermissionBroker
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.task.AgentTaskStore
import me.rerere.rikkahub.data.agent.terminal.AlpineRuntimeInstaller
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntime
import me.rerere.rikkahub.data.agent.tools.AgentCronTools
import me.rerere.rikkahub.data.agent.tools.ScreenAutomationTools
import me.rerere.rikkahub.data.agent.tools.SystemAccessTools
import me.rerere.rikkahub.data.agent.tools.TerminalTools
import app.amber.feature.webview.WebViewOperationStore
import me.rerere.rikkahub.data.automation.ScreenCaptureManager
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
    single { AgentToolActivityStore() }

    single { AgentTaskStore(get(), get()) }

    single { AgentTaskScheduler(get()) }

    single { WebViewOperationStore() }

    single { AgentLiveStatusNotifier(get()) }

    single { LiveModeManager(get(), get(), get(), get()) }

    single { AlpineRuntimeInstaller(get()) }

    single { TerminalRuntime(get(), get(), get(), get(), get(), get(), get()) }

    single { TerminalTools(get(), get()) }

    single { ScreenCaptureManager(get()) }

    single { ScreenAutomationTools(get(), get(), get()) }

    single { AgentPermissionBroker(get()) }

    single { SystemAccessTools(get(), get(), get(), get()) }

    single { AgentCronManager(get(), get(), get()) }

    single { AgentCronTools(get()) }
}
