package app.amber.feature.macgateway

/**
 * Push message kind carried in FCM data-only payloads (`amber_v = "1"`).
 */
enum class ProactivePushType(val wire: String) {
    PROACTIVE("proactive"),
    APPROVAL("approval"),
    GENERIC("generic"),
    ;

    companion object {
        fun fromWire(value: String?): ProactivePushType? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/**
 * Interruption level mapped onto the three dedicated Android notification channels (§3.6 / §3.7).
 */
enum class ProactiveInterruptionLevel(val wire: String) {
    PASSIVE("passive"),
    ACTIVE("active"),
    TIME_SENSITIVE("time-sensitive"),
    ;

    companion object {
        fun fromWire(value: String?, default: ProactiveInterruptionLevel): ProactiveInterruptionLevel {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return default
            return entries.firstOrNull { it.wire == normalized } ?: default
        }
    }
}

/**
 * Validated representation of an FCM `amber_v = "1"` data-only push (§3.6).
 *
 * Security invariants:
 * - Never carries or logs secrets / tokens / topicKey.
 * - `gatewayId` and `deviceId` are mandatory and must match the currently paired gateway.
 * - `signalId` (mandatory for [ProactivePushType.PROACTIVE]) strictly matches `^[A-Za-z0-9_-]{1,48}$`.
 */
data class ProactivePushMessage(
    val amberVersion: String,
    val type: ProactivePushType,
    val title: String,
    val body: String,
    val gatewayId: String,
    val deviceId: String,
    val signalId: String? = null,
    val deepLink: String? = null,
    val interruptionLevel: ProactiveInterruptionLevel = ProactiveInterruptionLevel.ACTIVE,
    val category: String? = null,
    val tag: String,
    val sessionKey: String? = null,
    val toolCallId: String? = null,
    val workItemId: String? = null,
    val highRisk: Boolean = false,
) {
    fun matchesPairing(connection: MacGatewayConnection?): Boolean =
        connection != null &&
            connection.gatewayId == gatewayId &&
            connection.deviceId == deviceId

    fun canonicalProactiveDeepLink(): String? =
        signalId?.takeIf { type == ProactivePushType.PROACTIVE }?.let { "amber://proactive/$it" }

    companion object {
        const val AMBER_VERSION_1 = "1"
        const val MAX_TITLE_LENGTH = 100
        const val MAX_BODY_LENGTH = 180

        const val KEY_AMBER_V = "amber_v"
        const val KEY_TYPE = "type"
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
        const val KEY_GATEWAY_ID = "gateway_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SIGNAL_ID = "signal_id"
        const val KEY_DEEP_LINK = "deep_link"
        const val KEY_INTERRUPTION_LEVEL = "interruption_level"
        const val KEY_CATEGORY = "category"
        const val KEY_TAG = "tag"
        const val KEY_SESSION_KEY = "session_key"
        const val KEY_TOOL_CALL_ID = "tool_call_id"
        const val KEY_WORK_ITEM_ID = "work_item_id"
        const val KEY_HIGH_RISK = "high_risk"

        fun isAmberV1DataPush(data: Map<String, String>): Boolean =
            data[KEY_AMBER_V] == AMBER_VERSION_1

        fun parse(data: Map<String, String>): ProactivePushMessage? {
            if (!isAmberV1DataPush(data)) return null
            val type = ProactivePushType.fromWire(data[KEY_TYPE]) ?: return null

            val title = sanitizeText(data[KEY_TITLE], MAX_TITLE_LENGTH) ?: return null
            val body = sanitizeText(data[KEY_BODY], MAX_BODY_LENGTH) ?: return null
            val gatewayId = data[KEY_GATEWAY_ID]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val deviceId = data[KEY_DEVICE_ID]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            val rawSignalId = data[KEY_SIGNAL_ID]
            val signalId = when (type) {
                ProactivePushType.PROACTIVE -> {
                    val candidate = rawSignalId ?: return null
                    if (!ProactiveContract.isValidSignalId(candidate)) return null
                    candidate
                }
                ProactivePushType.APPROVAL,
                ProactivePushType.GENERIC,
                -> {
                    if (rawSignalId.isNullOrEmpty()) {
                        null
                    } else if (ProactiveContract.isValidSignalId(rawSignalId)) {
                        rawSignalId
                    } else {
                        return null
                    }
                }
            }

            val defaultLevel = if (type == ProactivePushType.APPROVAL) {
                ProactiveInterruptionLevel.TIME_SENSITIVE
            } else {
                ProactiveInterruptionLevel.ACTIVE
            }
            val interruptionLevel = ProactiveInterruptionLevel.fromWire(
                value = data[KEY_INTERRUPTION_LEVEL],
                default = defaultLevel,
            )

            val sessionKey = data[KEY_SESSION_KEY]?.trim()?.takeIf { it.isNotEmpty() }
            val toolCallId = data[KEY_TOOL_CALL_ID]?.trim()?.takeIf { it.isNotEmpty() }
            val workItemId = data[KEY_WORK_ITEM_ID]?.trim()?.takeIf { it.isNotEmpty() }
            val highRisk = data[KEY_HIGH_RISK]?.trim()?.equals("true", ignoreCase = true) == true
            val category = data[KEY_CATEGORY]?.trim()?.takeIf { it.isNotEmpty() }
            val deepLink = data[KEY_DEEP_LINK]?.trim()?.takeIf { it.isNotEmpty() }
                ?: signalId?.takeIf { type == ProactivePushType.PROACTIVE }?.let { "amber://proactive/$it" }

            val fallbackTag = when (type) {
                ProactivePushType.PROACTIVE -> "proactive:${signalId!!}"
                ProactivePushType.APPROVAL -> "approval:${toolCallId ?: workItemId ?: "pending"}"
                ProactivePushType.GENERIC -> "generic:$gatewayId"
            }
            val tag = data[KEY_TAG]?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTag

            return ProactivePushMessage(
                amberVersion = AMBER_VERSION_1,
                type = type,
                title = title,
                body = body,
                gatewayId = gatewayId,
                deviceId = deviceId,
                signalId = signalId,
                deepLink = deepLink,
                interruptionLevel = interruptionLevel,
                category = category,
                tag = tag,
                sessionKey = sessionKey,
                toolCallId = toolCallId,
                workItemId = workItemId,
                highRisk = highRisk,
            )
        }

        fun parseForPairing(
            data: Map<String, String>,
            connection: MacGatewayConnection?,
        ): ProactivePushMessage? {
            val parsed = parse(data) ?: return null
            return parsed.takeIf { it.matchesPairing(connection) }
        }

        private fun sanitizeText(raw: String?, maxLength: Int): String? {
            if (raw == null) return null
            val cleaned = raw
                .filterNot { it.isISOControl() && it != '\n' && it != '\t' }
                .replace(Regex("[\\r\\n\\t]+"), " ")
                .trim()
            if (cleaned.isEmpty()) return null
            return cleaned.take(maxLength)
        }
    }
}
