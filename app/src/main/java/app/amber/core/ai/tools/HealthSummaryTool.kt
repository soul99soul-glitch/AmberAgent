package app.amber.core.ai.tools

import app.amber.ai.core.Tool
import app.amber.feature.tools.integerProp
import app.amber.feature.tools.obj
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.health.HealthSummaryReader
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive

/** Read-only, approval-gated Health Connect summary tool. */
fun createHealthSummaryTool(reader: HealthSummaryReader): Tool = Tool(
    name = "health_summary",
    description = "读取用户已授权的 Health Connect 健康摘要（日/周步数、心率、睡眠和体重）；不写入健康数据。",
    parameters = {
        obj(
            "days" to integerProp("回看天数，1 到 30，默认 7。"),
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        val days = input.jsonObject["days"]?.jsonPrimitive?.intOrNull ?: HealthSummaryReader.DEFAULT_DAYS
        // The host owns the adapter authorization state. This first tool boundary
        // never treats a settings intent or missing state as an authorization grant.
        val result = reader.readSummary(
            days = days,
            authorizationGranted = reader.status().state == app.amber.feature.health.HealthAdapterState.READY,
        )
        listOf(UIMessagePart.Text(result.fold(HealthSummaryTool::success, HealthSummaryTool::failure)))
    },
)

private object HealthSummaryTool {
    fun success(summary: app.amber.feature.health.HealthSummary): String = summary.toJson()

    fun failure(error: Throwable): String = buildJsonObject {
        put("ok", false)
        put("tool", "health_summary")
        put("reason", error.message ?: "健康摘要读取失败。")
    }.toString()
}
