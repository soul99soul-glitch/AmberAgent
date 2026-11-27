package app.amber.feature.macgateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class MacBoardItem(
    val id: String,
    val kind: String,
    val column: Column,
    val title: String,
    val detail: String,
    val updatedAt: Double,
    val nextRunAt: Double? = null,
    val approval: Approval? = null,
) {
    @Serializable
    enum class Column {
        @SerialName("needsYou") NEEDS_YOU,
        @SerialName("running") RUNNING,
        @SerialName("scheduled") SCHEDULED,
        @SerialName("done") DONE,
    }

    @Serializable
    data class Approval(
        val sessionKey: String,
        val toolCallId: String,
        val tool: String,
        val detail: String? = null,
        val highRisk: Boolean = true,
        val requiresLocalApproval: Boolean? = null,
        val riskLevel: String? = null,
        val targetSummary: String? = null,
        val summary: String? = null,
        val targetDevice: String? = null,
        val targetDeviceName: String? = null,
        val diffPreview: String? = null,
    ) {
        fun asPendingApproval() = MacGatewayStatus.PendingApproval(
            toolCallId = toolCallId,
            tool = tool,
            detail = detail,
            highRisk = highRisk,
            riskLevel = riskLevel,
            targetDevice = targetDevice ?: targetDeviceName,
            targetSummary = targetSummary ?: summary,
            diffPreview = diffPreview,
            requiresLocalApproval = requiresLocalApproval,
        )
    }
}

@Serializable
data class MacGatewaySchedule(
    val id: String,
    val name: String,
    val rule: String,
    val enabled: Boolean,
    val running: Boolean,
    val paused: Boolean = false,
    val nextRunAt: Double? = null,
    val lastStatus: String? = null,
    val lastRunAt: Double? = null,
    val autoApprovesHighRisk: Boolean,
)

data class MacScheduleDraft(
    val name: String = "",
    val prompt: String = "",
    val rule: Rule = Rule.DAILY,
    val hour: Int = 9,
    val minute: Int = 0,
    val weekdays: Set<Int> = setOf(2),
    val intervalMinutes: Int = 60,
) {
    enum class Rule(val wire: String) {
        DAILY("daily"), WEEKDAYS("weekdays"), WEEKLY("weekly"), INTERVAL("interval"),
    }

    val isComplete: Boolean
        get() = name.isNotBlank() && prompt.isNotBlank() && when (rule) {
            Rule.INTERVAL -> intervalMinutes in selectableIntervals
            Rule.WEEKLY -> hour in 0..23 && minute in 0..59 && weekdays.isNotEmpty() && weekdays.all { it in 1..7 }
            else -> hour in 0..23 && minute in 0..59
        }

    internal fun requestBody(): Map<String, Any> {
        require(isComplete)
        return buildMap {
            put("name", name.trim())
            put("prompt", prompt.trim())
            put("rule", rule.wire)
            if (rule == Rule.INTERVAL) {
                put("intervalMinutes", intervalMinutes)
            } else {
                put("hour", hour)
                put("minute", minute)
                if (rule == Rule.WEEKLY) put("weekdays", JsonArray(weekdays.sorted().map(::JsonPrimitive)))
            }
        }
    }

    companion object {
        val selectableIntervals = listOf(15, 30, 60, 120, 180, 360, 720)
    }
}

@Serializable
internal data class MacBoardResponse(val board: List<MacBoardItem>)

@Serializable
internal data class MacSchedulesResponse(val schedules: List<MacGatewaySchedule>)

data class MacTaskSnapshot(
    val board: Result<List<MacBoardItem>>? = null,
    val schedules: Result<List<MacGatewaySchedule>>? = null,
)
