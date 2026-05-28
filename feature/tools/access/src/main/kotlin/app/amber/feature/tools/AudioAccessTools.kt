package app.amber.feature.tools

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import java.io.File

fun createAudioRecordOnceTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "audio_record_once",
    description = "Record a short microphone clip to app-private storage. Requires RECORD_AUDIO and explicit approval.",
    parameters = {
        obj(
            "duration_ms" to integerProp("Recording duration in milliseconds. Defaults to 5000, max 30000."),
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        deps.trackSystemTool("audio_record_once", "录制音频", "audio_record", input.safePreview()) {
            val file = recordAudioOnce(context, input.limit("duration_ms", default = 5_000, max = 30_000).toLong())
            textJson {
                put("artifact_type", "audio")
                put("path", file.absolutePath)
                put("size_bytes", file.length())
            }
        }
    }
)

private suspend fun recordAudioOnce(context: Context, durationMillis: Long): File {
    val outputDir = File(context.filesDir, "agent-artifacts/audio").apply { mkdirs() }
    val output = File(outputDir, "recording-${System.currentTimeMillis()}.m4a")
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
    try {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setOutputFile(output.absolutePath)
        recorder.prepare()
        recorder.start()
        delay(durationMillis)
        recorder.stop()
    } finally {
        recorder.release()
    }
    return output
}
