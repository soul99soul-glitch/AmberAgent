package me.rerere.rikkahub.data.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AgentToolActivityStore {
    private val _sandboxActivity = MutableStateFlow<SandboxActivityUiState?>(null)
    val sandboxActivity: StateFlow<SandboxActivityUiState?> = _sandboxActivity.asStateFlow()

    fun start(activity: SandboxActivityUiState) {
        _sandboxActivity.value = activity
    }

    fun appendOutput(toolCallId: String, line: String) {
        _sandboxActivity.update { current ->
            if (current?.toolCallId == toolCallId) {
                current.copy(outputTail = current.outputTail.appendTailLine(line))
            } else {
                current
            }
        }
    }

    fun startTool(
        toolName: String,
        title: String,
        inputPreview: String = "",
        runtime: String = "",
        workspace: String = "",
        canCancel: Boolean = false,
    ): String {
        val toolCallId = "${toolName}_${System.currentTimeMillis()}"
        start(
            SandboxActivityUiState(
                toolCallId = toolCallId,
                toolName = toolName,
                title = title,
                status = ToolActivityStatus.RUNNING,
                inputPreview = inputPreview.take(MAX_INPUT_PREVIEW_CHARS),
                runtime = runtime,
                workspace = workspace,
                startedAtEpochMillis = System.currentTimeMillis(),
                canCancel = canCancel,
            )
        )
        return toolCallId
    }

    fun complete(toolCallId: String, exitCode: Int, output: String) {
        _sandboxActivity.update { current ->
            if (current?.toolCallId == toolCallId) {
                current.copy(
                    status = if (exitCode == 0) ToolActivityStatus.SUCCEEDED else ToolActivityStatus.FAILED,
                    outputTail = output.trim().takeLast(MAX_OUTPUT_TAIL_CHARS),
                    endedAtEpochMillis = System.currentTimeMillis(),
                    canCancel = false,
                )
            } else {
                current
            }
        }
    }

    fun complete(toolCallId: String, output: String = "") {
        _sandboxActivity.update { current ->
            if (current?.toolCallId == toolCallId) {
                current.copy(
                    status = ToolActivityStatus.SUCCEEDED,
                    outputTail = output.trim().takeLast(MAX_OUTPUT_TAIL_CHARS),
                    endedAtEpochMillis = System.currentTimeMillis(),
                    canCancel = false,
                )
            } else {
                current
            }
        }
    }

    fun fail(toolCallId: String, error: Throwable) {
        Log.e(TAG, "Tool activity failed: $toolCallId", error)
        _sandboxActivity.update { current ->
            if (current?.toolCallId == toolCallId) {
                current.copy(
                    status = ToolActivityStatus.FAILED,
                    outputTail = error.toAgentToolFailureJson().takeLast(MAX_OUTPUT_TAIL_CHARS),
                    endedAtEpochMillis = System.currentTimeMillis(),
                    canCancel = false,
                )
            } else {
                current
            }
        }
    }

    fun cancel(toolCallId: String, output: String = "") {
        _sandboxActivity.update { current ->
            if (current?.toolCallId == toolCallId) {
                current.copy(
                    status = ToolActivityStatus.CANCELLED,
                    outputTail = output.trim().takeLast(MAX_OUTPUT_TAIL_CHARS),
                    endedAtEpochMillis = System.currentTimeMillis(),
                    canCancel = false,
                )
            } else {
                current
            }
        }
    }

    fun clear(toolCallId: String) {
        _sandboxActivity.update { current ->
            if (current?.toolCallId == toolCallId) null else current
        }
    }

    private fun String.appendTailLine(line: String): String =
        buildString {
            if (isNotBlank()) {
                append(this@appendTailLine)
                append('\n')
            }
            append(line)
        }.takeLast(MAX_OUTPUT_TAIL_CHARS)

    companion object {
        private const val TAG = "AgentToolActivityStore"
        private const val MAX_INPUT_PREVIEW_CHARS = 800
        private const val MAX_OUTPUT_TAIL_CHARS = 1_600
    }
}
