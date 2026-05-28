package app.amber.core.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

/**
 * Factory for the `ask_user` agent tool — human-in-the-loop question
 * surface. The execute body intentionally errors; actual answering is
 * handled by the HITL approval flow in ChatService/ToolApprovalCoordinator
 * before the tool is ever invoked through the normal pipeline.
 *
 * Extracted from `LocalTools.askUserTool` in M1.4 demo (one-tool-per-file).
 * No state, no deps; the resulting Tool object can be cached behind
 * `lazy { createAskUserTool() }`.
 */
fun createAskUserTool(): Tool = Tool(
    name = "ask_user",
    description = """
        Ask the user one or more questions when you need clarification, additional information, or confirmation.
        Each question can optionally provide a list of suggested options for the user to choose from.
        The user may select an option or provide their own free-text answer for each question.
        The answers will be returned as a JSON object mapping question IDs to the user's responses.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "List of questions to ask the user")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Optional list of suggested options for the user to choose from"
                                )
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("text")
                                        add("single")
                                        add("multi")
                                    }
                                )
                                put(
                                    "description",
                                    "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = true,
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)
