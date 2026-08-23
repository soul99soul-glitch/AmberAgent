package app.amber.core.memory.model

import app.amber.ai.core.ReasoningLevel
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class MemoryRecallSetting(
    val maxItems: Int = 24,
    val maxPromptChars: Int = 6_000,
    val debug: Boolean = false,
)

@Serializable
data class MemoryWorkerSetting(
    val enabled: Boolean = true,
    val modelId: Uuid = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08"),
    val followCompressModel: Boolean = true,
    val daydreamModelId: Uuid = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08"),
    val daydreamFollowCompressModel: Boolean = true,
    val daydreamReasoningLevel: ReasoningLevel = ReasoningLevel.HIGH,
    val extractionEnabled: Boolean = true,
    val dreamMaintenanceEnabled: Boolean = true,
    val dreamModelEnabled: Boolean = false,
    // Legacy alias from the old Daydream switch. Settings decode migrates this
    // into dreamModelEnabled and keeps this false on newly written settings.
    val dreamEnabled: Boolean = false,
    val runOnlyOnIdle: Boolean = true,
    val runOnlyOnCharging: Boolean = true,
    val maxDailyRuns: Int = 8,
    val dreamMaxDailyRuns: Int = 1,
    val timeoutMs: Long = 120_000L,
)
