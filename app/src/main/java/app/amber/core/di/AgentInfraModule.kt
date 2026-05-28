package app.amber.core.di

import app.amber.agent.BuildConfig
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.cron.AgentCronManager
import app.amber.feature.live.LiveModeManager
import app.amber.feature.system.AgentPermissionBroker
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
    single { AgentToolActivityStore() }

    single { AgentTaskStore(get(), get()) }

    single { AgentTaskScheduler(get()) }

    single { WebViewOperationStore() }

    single { AgentLiveStatusNotifier(get()) }

    single { LiveModeManager(get(), get(), get(), get()) }

    single { AlpineRuntimeInstaller(get(), BuildConfig.VERSION_CODE) }

    single { TerminalRuntime(get(), get(), get(), get(), get(), get(), get()) }

    single { TerminalTools(get(), get()) }

    single { ScreenCaptureManager(get()) }

    single { ScreenAutomationTools(get(), get(), get()) }

    single { AgentPermissionBroker(get(), BuildConfig.DEBUG) }

    single { SystemAccessTools(get(), get(), get(), get()) }

    single { AgentCronManager(get(), get(), get()) }

    single { AgentCronTools(get()) }
}
