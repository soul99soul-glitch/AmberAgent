package app.amber.feature.tools

import android.content.Context
import app.amber.ai.core.Tool
import app.amber.core.automation.DefaultScreenOcrEngine
import app.amber.core.automation.PhoneAutomationTakeoverController
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.automation.ScreenOcrEngine
import app.amber.core.jev.JevScreenGoalRunner
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.runtime.AgentToolActivityStore

class ScreenAutomationTools(
    context: Context,
    screenCaptureManager: ScreenCaptureManager,
    activityStore: AgentToolActivityStore,
    jevScreenGoalRunner: JevScreenGoalRunner? = null,
    settingsStore: SettingsAggregator? = null,
    ocrEngine: ScreenOcrEngine = DefaultScreenOcrEngine(context, screenCaptureManager),
    takeoverController: PhoneAutomationTakeoverController? = null,
) {
    constructor(
        context: Context,
        screenCaptureManager: ScreenCaptureManager,
        activityStore: AgentToolActivityStore,
        jevScreenGoalRunner: JevScreenGoalRunner? = null,
        ocrEngine: ScreenOcrEngine,
        takeoverController: PhoneAutomationTakeoverController? = null,
    ) : this(
        context = context,
        screenCaptureManager = screenCaptureManager,
        activityStore = activityStore,
        jevScreenGoalRunner = jevScreenGoalRunner,
        settingsStore = null,
        ocrEngine = ocrEngine,
        takeoverController = takeoverController,
    )

    private val operations = ScreenOperationOwner(activityStore, takeoverController)
    private val scope = ScreenAppScopeTools(context, settingsStore, jevScreenGoalRunner, operations)
    private val primitives = ScreenPrimitiveTools(context, screenCaptureManager, operations, ocrEngine)
    private val flow = ScreenFlowTools(settingsStore, operations)
    private val collection = ScreenListCollection(operations)

    fun getTools(runId: String? = null): List<Tool> {
        operations.bindRun(runId)
        return listOf(
            scope.screenStatusTool(runId),
            scope.screenAppsTool,
            scope.screenAddAppTool(runId),
            scope.screenRemoveAppTool,
            scope.screenStopTool(runId),
            scope.screenGoalTool(runId),
            primitives.screenClickTool,
            primitives.screenLongClickTool,
            primitives.screenSwipeTool,
            primitives.screenInputTextTool,
            primitives.screenBackTool,
            primitives.screenHomeTool,
            primitives.screenOpenAppTool,
            primitives.screenOpenUrlTool,
            primitives.screenReadUiTool,
            primitives.screenFindTextTool,
            primitives.screenTapTextTool,
            primitives.screenWaitForTextTool,
            primitives.screenScrollUntilTool,
            flow.screenRunFlowTool(runId),
            collection.screenCollectListTool,
            primitives.screenScreenshotTool,
            primitives.vlmTaskTool,
        )
    }
}
