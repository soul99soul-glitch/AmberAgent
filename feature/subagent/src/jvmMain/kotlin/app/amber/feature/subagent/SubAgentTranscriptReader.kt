package app.amber.feature.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.RandomAccessFile

private val transcriptJson = Json { ignoreUnknownKeys = true }

fun readSubAgentDisplayTextFromTranscript(
    transcriptPath: String,
    runRoot: File,
): String {
    if (transcriptPath.isBlank()) return ""
    val root = runCatching { runRoot.canonicalFile }.getOrNull() ?: return ""
    val transcript = runCatching { File(transcriptPath).canonicalFile }.getOrNull() ?: return ""
    if (!transcript.isFile || transcript.extension != "jsonl") return ""
    if (!transcript.path.startsWith(root.path + File.separator)) return ""

    return runCatching {
        transcript.tailText(MAX_TRANSCRIPT_TAIL_BYTES)
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .asReversed()
            .firstNotNullOfOrNull(::displayTextFromTranscriptLine)
            .orEmpty()
    }.getOrDefault("")
}

private fun displayTextFromTranscriptLine(line: String): String? {
    val event = runCatching { transcriptJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
        ?: return null
    val payload = event["payload"] as? JsonObject
        ?: return null
    return (payload["display_text"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }
}

private fun File.tailText(maxBytes: Int): String {
    val length = length()
    val start = (length - maxBytes).coerceAtLeast(0)
    val size = (length - start).coerceAtMost(maxBytes.toLong()).toInt()
    if (size <= 0) return ""
    val bytes = ByteArray(size)
    RandomAccessFile(this, "r").use { file ->
        file.seek(start)
        file.readFully(bytes)
    }
    val text = bytes.toString(Charsets.UTF_8)
    return if (start > 0) text.substringAfter('\n', "") else text
}

private const val MAX_TRANSCRIPT_TAIL_BYTES = 256 * 1024
