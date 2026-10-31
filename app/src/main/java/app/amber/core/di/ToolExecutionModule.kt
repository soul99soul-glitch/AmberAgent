package app.amber.core.di

import android.content.Context
import app.amber.core.ai.tools.LocalTools
import app.amber.core.ai.tools.ImageGenerationLocalTools
import app.amber.feature.tools.SshProfileTools
import app.amber.feature.webmount.tools.WebMountLocalTools
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.runtime.defaultToolInvocationHooks
import org.koin.dsl.module

/** Local tool composition and the common permission/dispatch boundary. */
val toolExecutionModule = module {
    single {
        WebMountLocalTools(
            primitiveTools = get(),
            manager = get(),
            userSiteRegistry = get(),
        )
    }
    single {
        ImageGenerationLocalTools(
            settingsStore = get(),
            imageGenerationRepository = get(),
            conversationRepository = get(),
        )
    }
    single { SshProfileTools(sshProfileStore = get()) }

    single {
        LocalTools(
            context = get(),
            eventBus = get(),
            workspaceTools = get(),
            terminalTools = get(),
            screenAutomationTools = get(),
            systemAccessTools = get(),
            workspaceArtifactTools = get(),
            externalFileTools = get(),
            permissionBroker = get(),
            webViewOperationStore = get(),
            iCloudDriveTools = get(),
            agentCronTools = get(),
            webMountTools = get(),
            settingsStore = get(),
            imageGenerationTools = get(),
            promptConfigRepository = get(),
            deepReadPlaybookRepository = get(),
            healthSummaryReader = get(),
            reminderTools = get(),
            jevDecisionCoordinator = get(),
            sshProfileTools = get(),
        )
    }

    single { PermissionDecisionResolver(appContext = get<Context>()) }

    single {
        AgentToolDispatcher(
            json = get(),
            permissionDecisionResolver = get(),
            hooks = defaultToolInvocationHooks(),
        )
    }

}
