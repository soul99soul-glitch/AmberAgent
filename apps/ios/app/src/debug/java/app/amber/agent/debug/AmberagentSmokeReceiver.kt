package app.amber.agent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.SystemAccessTools
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.workspace.WorkspaceManager
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.service.ChatService
import org.koin.core.context.GlobalContext
import java.time.Instant

class AmberAgentSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_TERMINAL -> runTerminalSmoke(context, intent)
                    ACTION_SCREEN -> runScreenSmoke(context, intent)
                    ACTION_SCREENSHOT -> runScreenshotSmoke(context)
                    ACTION_SYSTEM_ACCESS -> runSystemAccessSmoke(context, intent)
                    ACTION_AGENT_TOOL -> runAgentToolSmoke(context, intent)
                    else -> error("Unknown smoke action: ${intent.action}")
                }
            } catch (error: Throwable) {
                writeReport(context, "smoke_error.txt", errorReport(error))
                Log.e(TAG, "smoke failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun runTerminalSmoke(context: Context, intent: Intent) {
        val koin = GlobalContext.get()
        val workspaceManager = koin.get<WorkspaceManager>()
        val terminalRuntime = koin.get<TerminalRuntime>()
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: DEFAULT_SMOKE_COMMAND
        val timeoutMillis = intent.getLongExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
        val result = terminalRuntime.execute(command, timeoutMillis)
        val report = buildString {
            appendLine("timestamp=${Instant.now()}")
            appendLine("workspace_configured=${workspaceManager.state.value.configured}")
            appendLine("runtime=${result.runtime}")
            appendLine("workspace=${result.workspace}")
            appendLine("exit_code=${result.exitCode}")
            appendLine("sync_note=${result.syncNote.replace("\n", "\\n")}")
            appendLine("output_begin")
            append(result.output)
            if (!result.output.endsWith("\n")) appendLine()
            appendLine("output_end")
        }
        val outputFile = writeReport(context, "terminal_execute.txt", report)
        Log.i(TAG, "terminal smoke completed: ${outputFile.absolutePath}")
    }

    private suspend fun runScreenSmoke(context: Context, intent: Intent) {
        val service = AmberAccessibilityService.getActiveService()
            ?: error("AmberAgent Accessibility is not enabled")
        val operation = intent.getStringExtra(EXTRA_OPERATION) ?: "read_ui"
        val success = when (operation) {
            "read_ui" -> true
            "click", "tap" -> service.tap(
                x = intent.requiredFloat(EXTRA_X),
                y = intent.requiredFloat(EXTRA_Y),
            )
            "long_click" -> service.longPress(
                x = intent.requiredFloat(EXTRA_X),
                y = intent.requiredFloat(EXTRA_Y),
                durationMillis = intent.getLongExtra(EXTRA_DURATION_MS, 600L),
            )
            "swipe" -> service.swipe(
                fromX = intent.requiredFloat(EXTRA_FROM_X),
                fromY = intent.requiredFloat(EXTRA_FROM_Y),
                toX = intent.requiredFloat(EXTRA_TO_X),
                toY = intent.requiredFloat(EXTRA_TO_Y),
                durationMillis = intent.getLongExtra(EXTRA_DURATION_MS, 350L),
            )
            "input_text" -> service.setFocusedText(
                intent.getStringExtra(EXTRA_TEXT) ?: error("text is required")
            )
            "back" -> service.back()
            "home" -> service.home()
            "open_app" -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                    ?: error("package_name is required")
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: error("No launch intent for package: $packageName")
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            }
            else -> error("Unknown screen smoke operation: $operation")
        }
        val report = buildString {
            appendLine("timestamp=${Instant.now()}")
            appendLine("operation=$operation")
            appendLine("success=$success")
            appendLine("ui_tree_begin")
            appendLine(service.dumpUiTree(intent.getIntExtra(EXTRA_MAX_NODES, 120)))
            appendLine("ui_tree_end")
        }
        val outputFile = writeReport(context, "screen.txt", report)
        Log.i(TAG, "screen smoke completed: ${outputFile.absolutePath}")
    }

    private suspend fun runScreenshotSmoke(context: Context) {
        val manager = GlobalContext.get().get<ScreenCaptureManager>()
        val result = manager.capture()
        val report = buildString {
            appendLine("timestamp=${Instant.now()}")
            appendLine("debug_only=true")
            appendLine("one_frame_only=true")
            appendLine("debug_entry_not_product_ui=true")
            appendLine("product_tool_wired=true")
            appendLine("status=captured")
            appendLine("path=${result.file.absolutePath}")
            appendLine("width=${result.width}")
            appendLine("height=${result.height}")
            appendLine("size_bytes=${result.sizeBytes}")
            appendLine("created_at=${result.createdAt}")
        }
        val outputFile = writeReport(context, "screenshot.txt", report)
        Log.i(TAG, "screenshot smoke completed: ${outputFile.absolutePath}")
    }

    private suspend fun runSystemAccessSmoke(context: Context, intent: Intent) {
        val tools = GlobalContext.get().get<SystemAccessTools>().getTools()
        val toolName = intent.getStringExtra(EXTRA_TOOL_NAME) ?: "apps_list"
        val args = intent.getStringExtra(EXTRA_ARGS_JSON) ?: "{}"
        val tool = tools.firstOrNull { it.name == toolName } ?: error("Unknown system access tool: $toolName")
        if (tool.needsApproval && !intent.getBooleanExtra(EXTRA_ALLOW_HIGH_RISK, false)) {
            error("Refusing high-risk smoke tool without $EXTRA_ALLOW_HIGH_RISK=true: $toolName")
        }
        val result = tool.execute(Json.parseToJsonElement(args))
        val report = buildString {
            appendLine("timestamp=${Instant.now()}")
            appendLine("debug_only=true")
            appendLine("tool=$toolName")
            appendLine("args=$args")
            appendLine("output_begin")
            result.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> appendLine(part.text)
                    else -> appendLine(part.toString())
                }
            }
            appendLine("output_end")
        }
        val outputFile = writeReport(context, "system_access.txt", report)
        Log.i(TAG, "system access smoke completed: ${outputFile.absolutePath}")
    }

    private suspend fun runAgentToolSmoke(context: Context, intent: Intent) {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsAggregator>().settingsFlow.filterNot { it.init }.first()
        val chatService = withContext(Dispatchers.Main) {
            koin.get<ChatService>()
        }
        val tools = chatService.createDebugRunTools(settings)
        val toolName = intent.getStringExtra(EXTRA_TOOL_NAME) ?: "tools_list"
        val args = intent.getStringExtra(EXTRA_ARGS_JSON) ?: "{}"
        val tool = tools.firstOrNull { it.name == toolName } ?: error("Unknown agent tool: $toolName")
        if (tool.needsApproval && !intent.getBooleanExtra(EXTRA_ALLOW_HIGH_RISK, false)) {
            error("Refusing high-risk smoke tool without $EXTRA_ALLOW_HIGH_RISK=true: $toolName")
        }
        val result = tool.execute(Json.parseToJsonElement(args))
        val report = buildString {
            appendLine("timestamp=${Instant.now()}")
            appendLine("debug_only=true")
            appendLine("tool=$toolName")
            appendLine("args=$args")
            appendLine("available_tool_count=${tools.size}")
            appendLine("output_begin")
            result.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> appendLine(part.text)
                    else -> appendLine(part.toString())
                }
            }
            appendLine("output_end")
        }
        val outputFile = writeReport(context, "agent_tool.txt", report)
        Log.i(TAG, "agent tool smoke completed: ${outputFile.absolutePath}")
    }

    private fun writeReport(context: Context, name: String, report: String) =
        context.filesDir
            .resolve("amberagent/smoke/$name")
            .also { it.parentFile?.mkdirs() }
            .also { it.writeText(report) }

    private fun errorReport(error: Throwable) = buildString {
        appendLine("timestamp=${Instant.now()}")
        appendLine("error=${error::class.java.name}: ${error.message}")
        appendLine("output_begin")
        appendLine(Log.getStackTraceString(error))
        appendLine("output_end")
    }

    private fun Intent.requiredFloat(name: String): Float {
        require(hasExtra(name)) { "$name is required" }
        return getFloatExtra(name, 0f)
    }

    companion object {
        private const val TAG = "AmberAgentSmoke"
        private const val ACTION_TERMINAL = "app.amber.agent.debug.SMOKE_TERMINAL"
        private const val ACTION_SCREEN = "app.amber.agent.debug.SMOKE_SCREEN"
        private const val ACTION_SCREENSHOT = "app.amber.agent.debug.SMOKE_SCREENSHOT"
        private const val ACTION_SYSTEM_ACCESS = "app.amber.agent.debug.SMOKE_SYSTEM_ACCESS"
        private const val ACTION_AGENT_TOOL = "app.amber.agent.debug.SMOKE_AGENT_TOOL"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_TIMEOUT_MS = "timeout_ms"
        private const val EXTRA_OPERATION = "operation"
        private const val EXTRA_MAX_NODES = "max_nodes"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"
        private const val EXTRA_FROM_X = "from_x"
        private const val EXTRA_FROM_Y = "from_y"
        private const val EXTRA_TO_X = "to_x"
        private const val EXTRA_TO_Y = "to_y"
        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_TOOL_NAME = "tool"
        private const val EXTRA_ARGS_JSON = "args"
        private const val EXTRA_ALLOW_HIGH_RISK = "allow_high_risk"
        private const val DEFAULT_TIMEOUT_MS = 120_000L
        private val DEFAULT_SMOKE_COMMAND = """
            uname -m
            cat /etc/alpine-release
            pwd
            command -v apk
            echo amber > /workspace/.amber_alpine_probe
            ls -l /workspace/.amber_alpine_probe
        """.trimIndent()
    }
}
