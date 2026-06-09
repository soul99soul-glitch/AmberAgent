package app.amber.core.ai.tools

import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart

/**
 * Factory for the `eval_javascript` agent tool — evaluates a JavaScript
 * expression / program in a fresh QuickJS (ES2020) context and returns the
 * value of the last expression along with any captured console output.
 *
 * Stateless — each call constructs its own [QuickJSContext], no class-scoped
 * dependencies. Same pattern as [createTimeTool].
 *
 * Extracted from `LocalTools.javascriptTool` in M1.4 continuation.
 */
fun createJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        Execute JavaScript code using QuickJS engine (ES2020).
        The result is the value of the last expression in the code.
        For calculations with decimals, use toFixed() to control precision.
        Console output (log/info/warn/error) is captured and returned in 'logs' field.
        No DOM or Node.js APIs available.
        Do not use this tool to create SVG, HTML, charts, diagrams, or generative UI widgets; write those directly in the assistant response.
        Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
            },
            required = listOf("code")
        )
    },
    execute = {
        val logs = arrayListOf<String>()
        val context = QuickJSContext.create()
        context.setConsole(object : QuickJSContext.Console {
            override fun log(info: String?) {
                logs.add("[LOG] $info")
            }

            override fun info(info: String?) {
                logs.add("[INFO] $info")
            }

            override fun warn(info: String?) {
                logs.add("[WARN] $info")
            }

            override fun error(info: String?) {
                logs.add("[ERROR] $info")
            }
        })
        val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
        val result = context.evaluate(code)
        val payload = buildJsonObject {
            if (logs.isNotEmpty()) {
                put("logs", JsonPrimitive(logs.joinToString("\n")))
            }
            put(
                key = "result",
                element = when (result) {
                    null -> JsonNull
                    is QuickJSObject -> JsonPrimitive(result.stringify())
                    else -> JsonPrimitive(result.toString())
                }
            )
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
