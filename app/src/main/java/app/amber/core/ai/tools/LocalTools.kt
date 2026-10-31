package app.amber.core.ai.tools

import android.content.Context
import app.amber.ai.core.Tool
import app.amber.core.model.LocalToolOption
import app.amber.core.event.AppEventBus
import app.amber.feature.system.AgentPermissionBroker
import app.amber.feature.tools.AgentCronTools
import app.amber.feature.tools.ICloudDriveTools
import app.amber.feature.tools.ExternalFileTools
import app.amber.feature.webmount.tools.WebMountLocalTools
import app.amber.feature.tools.ScreenAutomationTools
import app.amber.feature.tools.SshProfileTools
import app.amber.feature.tools.SystemAccessTools
import app.amber.feature.tools.TerminalTools
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.WorkspaceArtifactTools
import app.amber.feature.tools.WorkspaceTools
import app.amber.feature.reminder.ReminderTools
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.board.hotlist.deepread.DeepReadPlaybookRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.appLocale
import app.amber.feature.webview.WebViewOperationStore
import kotlin.uuid.Uuid

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val workspaceTools: WorkspaceTools,
    private val terminalTools: TerminalTools,
    private val screenAutomationTools: ScreenAutomationTools,
    private val systemAccessTools: SystemAccessTools,
    private val workspaceArtifactTools: WorkspaceArtifactTools,
    private val externalFileTools: ExternalFileTools,
    private val permissionBroker: AgentPermissionBroker,
    private val webViewOperationStore: WebViewOperationStore,
    private val iCloudDriveTools: ICloudDriveTools,
    private val agentCronTools: AgentCronTools,
    private val webMountTools: WebMountLocalTools,
    private val settingsStore: SettingsAggregator,
    private val imageGenerationTools: ImageGenerationLocalTools,
    private val promptConfigRepository: AgentPromptConfigRepository,
    private val deepReadPlaybookRepository: DeepReadPlaybookRepository,
    private val healthSummaryReader: app.amber.feature.health.HealthSummaryReader,
    private val reminderTools: ReminderTools,
    private val jevDecisionCoordinator: app.amber.core.jev.JevDecisionCoordinator,
    private val sshProfileTools: SshProfileTools,
) {
    val javascriptTool by lazy { createJavascriptTool() }

    val timeTool by lazy { createTimeTool(context.appLocale()) }

    val clipboardTool by lazy {
        createClipboardTool(context)
    }

    val webViewTool by lazy { createWebViewOpenTool(webViewOperationStore) }

    val webViewSearchOpenTool by lazy { createWebViewSearchOpenTool(webViewOperationStore) }

    val webViewReadTool by lazy { createWebViewReadTool(webViewOperationStore) }

    val webViewWaitForLoadTool by lazy { createWebViewWaitForLoadTool(webViewOperationStore) }

    val webViewFindTextTool by lazy { createWebViewFindTextTool(webViewOperationStore) }

    val webViewLinksTool by lazy { createWebViewLinksTool(webViewOperationStore) }

    val webViewOpenLinkTool by lazy { createWebViewOpenLinkTool(webViewOperationStore) }

    val askUserTool by lazy { createAskUserTool() }

    val deepReadOpenTool by lazy { createDeepReadOpenTool(eventBus) }

    private val deepReadPlaybookTools by lazy { DeepReadPlaybookTools(deepReadPlaybookRepository) }

    /**
     * Registry-introspection tools — the pair (`tools_list`, `tool_policy_explain`)
     * that lets the model enumerate or probe the runtime tool catalog. Built per
     * call because each `getTools(...)` invocation produces a fresh registry; the
     * pair is returned together because ChatService always wires them in the same
     * place and they share the same registry argument.
     */
    fun registryIntrospectionTools(registry: ToolRegistry): List<Tool> = listOf(
        createToolsListTool(registry, permissionBroker, context),
        createToolPolicyExplainTool(registry),
    )

    private val permissionsStatusTool by lazy { createPermissionsStatusTool(permissionBroker, context) }

    private val jevStatusTool by lazy {
        createJevStatusTool(
            settingsProvider = { settingsStore.settingsFlow.value },
            coordinator = jevDecisionCoordinator,
            displayContext = context,
        )
    }

    private val healthSummaryTool by lazy { createHealthSummaryTool(healthSummaryReader) }

    private val runPlanUpdateTool by lazy { createRunPlanUpdateTool() }

    private val agentPromptConfigTool by lazy {
        createAgentPromptConfigTool(settingsStore, promptConfigRepository)
    }

    fun getTools(
        options: List<LocalToolOption>,
        conversationId: Uuid? = null,
        runId: String? = null,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.WorkspaceFiles)) {
            tools.addAll(workspaceTools.getTools())
            tools.addAll(workspaceArtifactTools.getTools())
            tools.addAll(externalFileTools.getTools())
        }
        if (options.contains(LocalToolOption.Terminal)) {
            tools.addAll(terminalTools.getTools())
            tools.addAll(sshProfileTools.getTools())
        }
        if (options.contains(LocalToolOption.ScreenAutomation)) {
            tools.addAll(screenAutomationTools.getTools(runId = runId))
        }
        if (options.contains(LocalToolOption.SystemAccess)) {
            tools.addAll(systemAccessTools.getTools())
        }
        if (options.contains(LocalToolOption.WebView)) {
            tools.add(webViewTool)
            tools.add(webViewSearchOpenTool)
            tools.add(webViewWaitForLoadTool)
            tools.add(webViewReadTool)
            tools.add(webViewFindTextTool)
            tools.add(webViewLinksTool)
            tools.add(webViewOpenLinkTool)
        }
        if (options.contains(LocalToolOption.ICloudDrive)) {
            tools.addAll(iCloudDriveTools.getTools())
        }
        tools.addAll(webMountTools.getTools(conversationId?.toString(), runId))
        tools.add(permissionsStatusTool)
        tools.add(jevStatusTool)
        tools.add(healthSummaryTool)
        tools.addAll(reminderTools.getTools())
        tools.addAll(agentCronTools.getTools())
        tools.add(runPlanUpdateTool)
        tools.add(agentPromptConfigTool)
        tools.addAll(deepReadPlaybookTools.getTools())
        tools.add(deepReadOpenTool)

        imageGenerationTools.getTool(conversationId)?.let(tools::add)

        return tools
    }

    /** Release any WebMount leases that outlive an individual tool call. */
    fun endWebMountRun(
        runId: String,
        conversationId: String?,
        reason: String = "run ended",
        preservePendingHandoff: Boolean = false,
    ) {
        webMountTools.endRun(
            runId = runId,
            conversationId = conversationId,
            reason = reason,
            preservePendingHandoff = preservePendingHandoff,
        )
    }

}
