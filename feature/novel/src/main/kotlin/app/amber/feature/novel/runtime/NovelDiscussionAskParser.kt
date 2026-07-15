package app.amber.feature.novel.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured "ask user" block embedded in discussion replies.
 *
 * Models append a fenced JSON block so the UI can mount chat's ask_user control:
 * ```ask_user
 * {"questions":[{"id":"q1","question":"...","options":["A","B"],"selection_type":"single"}]}
 * ```
 */
object NovelDiscussionAskParser {
    private val completeFenceRegex = Regex(
        pattern = """```ask_user\s*([\s\S]*?)```""",
        option = RegexOption.IGNORE_CASE,
    )
    private val openFenceRegex = Regex(
        pattern = """```ask_user\b""",
        option = RegexOption.IGNORE_CASE,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Serializable
    data class Payload(
        val questions: List<Question> = emptyList(),
    )

    @Serializable
    data class Question(
        val id: String = "",
        val question: String = "",
        val options: List<String> = emptyList(),
        @SerialName("selection_type")
        val selectionType: String = "single",
    )

    data class Parsed(
        /** Markdown/body with the ask_user fence removed. */
        val displayContent: String,
        val questions: List<Question>,
        val rawFence: String,
        /** JSON object string for [app.amber.ai.ui.UIMessagePart.Tool.input]. */
        val toolInput: String,
    )

    fun parse(content: String): Parsed? {
        val match = completeFenceRegex.find(content) ?: return null
        val body = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (body.isEmpty()) return null
        val normalizedBody = normalizeKeys(body)
        val payload = runCatching { json.decodeFromString(Payload.serializer(), normalizedBody) }.getOrNull()
            ?: return null
        val questions = payload.questions
            .map { q ->
                q.copy(
                    id = q.id.ifBlank { "q${q.hashCode()}" },
                    question = q.question.trim(),
                    options = q.options.map { it.trim() }.filter { it.isNotEmpty() },
                    selectionType = normalizeSelectionType(q.selectionType),
                )
            }
            .filter { it.question.isNotEmpty() }
        if (questions.isEmpty()) return null
        val display = content.replace(match.value, "").trim()
        val toolInput = encodeToolInput(questions)
        return Parsed(
            displayContent = display.ifBlank { content.trim() },
            questions = questions,
            rawFence = match.value,
            toolInput = toolInput,
        )
    }

    /**
     * Strip ask_user fence for display.
     * - Complete fence → remove block only.
     * - Incomplete streaming open fence → truncate from ```ask_user to end.
     */
    fun stripFence(content: String): String {
        completeFenceRegex.find(content)?.let { match ->
            return content.replace(match.value, "").trim().ifBlank { content }
        }
        val open = openFenceRegex.find(content) ?: return content
        return content.substring(0, open.range.first).trim().ifBlank {
            // Fence at start with only partial JSON — hide all of it.
            ""
        }
    }

    fun encodeToolInput(questions: List<Question>): String =
        json.encodeToString(Payload.serializer(), Payload(questions = questions))

    fun formatAnswer(
        questions: List<Question>,
        answers: Map<String, String>,
    ): String {
        if (questions.isEmpty()) return ""
        return buildString {
            appendLine("【我的选择】")
            questions.forEach { q ->
                val ans = answers[q.id]?.trim().orEmpty()
                if (ans.isNotEmpty()) {
                    append("- ")
                    append(q.question)
                    append(" → ")
                    appendLine(ans)
                }
            }
        }.trim()
    }

    /**
     * Chat ask_user submit payload: `{"answers":{"q1":"...","q2":["a","b"]}}`.
     */
    fun formatAnswerFromToolPayload(
        answerJson: String,
        questions: List<Question>,
    ): String {
        val answersObj = runCatching {
            json.parseToJsonElement(answerJson).jsonObject["answers"]?.jsonObject
        }.getOrNull() ?: return answerJson.trim()
        val map = linkedMapOf<String, String>()
        questions.forEach { q ->
            val el = answersObj[q.id] ?: return@forEach
            val text = when (el) {
                is JsonArray -> el.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("、")
                is JsonPrimitive -> el.contentOrNull?.trim().orEmpty()
                is JsonObject -> el.toString()
            }
            if (text.isNotEmpty()) map[q.id] = text
        }
        val formatted = formatAnswer(questions, map)
        return formatted.ifBlank { answerJson.trim() }
    }

    /** Allow camelCase keys from models before decoding. */
    private fun normalizeKeys(body: String): String =
        body
            .replace(Regex("\"selectionType\""), "\"selection_type\"")
            .replace(Regex("\"selectiontype\"", RegexOption.IGNORE_CASE), "\"selection_type\"")

    private fun normalizeSelectionType(raw: String): String =
        when (raw.trim().lowercase()) {
            "multi", "multiple" -> "multi"
            "text", "free", "freetext" -> "text"
            else -> "single"
        }
}
